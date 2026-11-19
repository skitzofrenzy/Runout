package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

typealias GhostBallAim = AimGeometry
typealias CueBallTrajectory = TrajectoryPath

val TrajectoryPath.scratchPocketId: PocketId?
    get() = hitsScratchPocket

/**
 * Normalized 2D cue-tip offset on the cue ball face in SI/dimensionless ratios relative to ball radius `R`.
 *
 * - [sideRatio]: negative = left english, positive = right english (normalized by `R`).
 * - [verticalRatio]: negative = draw, `0.0` = stun, positive = follow (normalized by `R`).
 * - [elevationRadians]: cue butt elevation in radians (default ~3 degrees).
 */
data class TipOffset(
    val sideRatio: Double = 0.0,
    val verticalRatio: Double = 0.0,
    val elevationRadians: Double = 0.05235987755982989,
) {
    val magnitudeRatio: Double = hypot(sideRatio, verticalRatio)

    val isWithinMiscueLimit: Boolean = magnitudeRatio <= MAX_SAFE_OFFSET_RATIO + 1e-9

    val clampedSideRatio: Double = if (magnitudeRatio <= MAX_SAFE_OFFSET_RATIO || magnitudeRatio < 1e-12) {
        sideRatio
    } else {
        sideRatio * (MAX_SAFE_OFFSET_RATIO / magnitudeRatio)
    }

    val clampedVerticalRatio: Double = if (magnitudeRatio <= MAX_SAFE_OFFSET_RATIO || magnitudeRatio < 1e-12) {
        verticalRatio
    } else {
        verticalRatio * (MAX_SAFE_OFFSET_RATIO / magnitudeRatio)
    }

    fun toSpinPreset(): CueBallSpinPreset = when {
        clampedVerticalRatio > 0.12 -> CueBallSpinPreset.FOLLOW
        clampedVerticalRatio < -0.12 -> CueBallSpinPreset.DRAW
        else -> CueBallSpinPreset.STUN
    }

    companion object {
        const val MAX_SAFE_OFFSET_RATIO: Double = 0.50

        fun fromPreset(preset: CueBallSpinPreset, sideRatio: Double = 0.0): TipOffset {
            val verticalRatio = when (preset) {
                CueBallSpinPreset.STUN -> 0.0
                CueBallSpinPreset.FOLLOW -> 0.35
                CueBallSpinPreset.DRAW -> -0.38
            }
            return TipOffset(
                sideRatio = sideRatio,
                verticalRatio = verticalRatio,
            )
        }

        fun standardCandidates(): List<TipOffset> {
            val verticals = listOf(-0.38, 0.0, 0.35)
            val sides = listOf(-0.30, 0.0, 0.30)
            val candidates = ArrayList<TipOffset>(verticals.size * sides.size)
            for (vertical in verticals) {
                for (side in sides) {
                    candidates += TipOffset(sideRatio = side, verticalRatio = vertical)
                }
            }
            return candidates
        }
    }
}

/**
 * Spin-aware aiming compensation breakdown around a geometric ghost-ball solution.
 */
data class SpinCompensatedAim(
    val geometricAim: GhostBallAim,
    val squirtAngleRadians: Double,
    val swerveAngleRadians: Double,
    val cutThrowAngleRadians: Double,
    val spinThrowAngleRadians: Double,
    val totalThrowAngleRadians: Double,
    val netCueAimAdjustmentRadians: Double,
    val compensatedGhostBallCenter: Vec2,
)

/**
 * Closed-form cue-strike spin physics (squirt, swerve, cut/spin-induced throw) and
 * Han (2005)-inspired cushion rebound trajectory tracer in SI units.
 */
object SpinPhysics {
    const val DEFAULT_BALL_BALL_FRICTION_MU: Double = 0.055
    private const val STRAIGHT_IN_ANGLE_EPSILON_RAD: Double = 1e-4

    /**
     * Computes cue-ball deflection (squirt) angle in radians when striking with [tip] and [shaftType].
     * Side spin pushes the cue ball away from the tip side.
     */
    fun computeSquirtAngleRadians(tip: TipOffset, shaftType: ShaftType): Double {
        val kShaft = if (shaftType == ShaftType.LOW_DEFLECTION) 0.044 else 0.076
        return -kShaft * tip.clampedSideRatio
    }

    /**
     * Computes cue-ball swerve angle in radians over [cueToGhostDistanceMeters] for an elevated cue.
     * Side spin curves the cue ball toward the tip side.
     */
    fun computeSwerveAngleRadians(
        tip: TipOffset,
        cueSpeedMps: Double,
        cueToGhostDistanceMeters: Double,
    ): Double {
        val safeSpeed = cueSpeedMps.coerceAtLeast(0.35)
        val distFactor = (cueToGhostDistanceMeters.coerceAtLeast(0.0) / (safeSpeed * 0.85)).coerceIn(0.15, 1.4)
        return 0.18 * tip.clampedSideRatio * sin(tip.elevationRadians.coerceIn(0.0, 0.55)) * distFactor
    }

    /**
     * Computes `(cutThrowRadians, spinThrowRadians)` imparted to the object ball at ball-ball contact.
     */
    fun computeThrowAnglesRadians(
        cutAngleSignedRadians: Double,
        cueSpeedMps: Double,
        tip: TipOffset,
        ballBallFrictionMu: Double = DEFAULT_BALL_BALL_FRICTION_MU,
    ): Pair<Double, Double> {
        val safeSpeed = cueSpeedMps.coerceAtLeast(0.35)
        val speedFactor = (1.6 / (safeSpeed + 0.8)).coerceIn(0.35, 1.35)
        val sinPhi = sin(cutAngleSignedRadians)
        val cosPhi = cos(cutAngleSignedRadians).coerceAtLeast(0.15)
        val cutThrow = atan2(ballBallFrictionMu * sinPhi, cosPhi + ballBallFrictionMu * abs(sinPhi)) * speedFactor
        val spinThrow = -0.85 * ballBallFrictionMu * tip.clampedSideRatio * speedFactor
        return cutThrow to spinThrow
    }

    /**
     * Computes geometric ghost-ball aim plus squirt, swerve, and throw compensation.
     */
    fun solveCompensatedAim(
        cueBallPos: Vec2,
        objectBallPos: Vec2,
        pocketPos: Vec2,
        cueBallRadiusMeters: Double,
        objectBallRadiusMeters: Double,
        cueSpeedMps: Double,
        tip: TipOffset,
        shaftType: ShaftType,
        table: TableSpec? = null,
    ): SpinCompensatedAim? {
        val cueBall = Ball(number = 0, position = cueBallPos, radiusMeters = cueBallRadiusMeters)
        val objectBall = Ball(number = 1, position = objectBallPos, radiusMeters = objectBallRadiusMeters)
        val pocket = PocketSpec(
            id = PocketId.FOOT_RIGHT,
            center = pocketPos,
            mouthWidthMeters = cueBallRadiusMeters * 4.0,
            acceptanceRadiusMeters = cueBallRadiusMeters * 2.2,
        )
        val geom = GhostBallAiming.computeAimGeometry(
            cueBall = cueBall,
            objectBall = objectBall,
            pocket = pocket,
            otherBalls = emptyList(),
            table = table,
        )
        if (!geom.isCutPossible) return null
        return solveCompensatedAim(
            geometricAim = geom,
            cueBallRadiusMeters = cueBallRadiusMeters,
            objectBallRadiusMeters = objectBallRadiusMeters,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            shaftType = shaftType,
            table = table,
        )
    }

    /**
     * Overload solving squirt, swerve, and throw compensation directly from a precomputed [geometricAim].
     */
    fun solveCompensatedAim(
        geometricAim: AimGeometry,
        cueBallRadiusMeters: Double,
        objectBallRadiusMeters: Double,
        cueSpeedMps: Double,
        tip: TipOffset,
        shaftType: ShaftType,
        table: TableSpec? = null,
    ): SpinCompensatedAim? {
        if (!geometricAim.isCutPossible) return null

        val cross = geometricAim.aimDirection.x * geometricAim.objectBallDirection.y -
            geometricAim.aimDirection.y * geometricAim.objectBallDirection.x
        val absCut = abs(geometricAim.cutAngleRadians)
        val signedCut = if (cross >= 0.0) absCut else -absCut

        val squirt = computeSquirtAngleRadians(tip, shaftType)
        val swerve = computeSwerveAngleRadians(tip, cueSpeedMps, geometricAim.cueToGhostDistanceMeters)
        val (cutThrow, spinThrow) = computeThrowAnglesRadians(signedCut, cueSpeedMps, tip)
        val totalThrow = cutThrow + spinThrow

        val compImpactDir = geometricAim.objectBallDirection.rotate(-totalThrow).normalized()
        val rawCompensatedGhost = geometricAim.objectBallPos -
            compImpactDir * (cueBallRadiusMeters + objectBallRadiusMeters)
        val compensatedGhost = if (
            table != null && !table.isWithinPlayableBounds(rawCompensatedGhost, cueBallRadiusMeters)
        ) {
            geometricAim.ghostBallCenter
        } else {
            rawCompensatedGhost
        }
        val netCueAimAdjustment = -(squirt + swerve)

        return SpinCompensatedAim(
            geometricAim = geometricAim,
            squirtAngleRadians = squirt,
            swerveAngleRadians = swerve,
            cutThrowAngleRadians = cutThrow,
            spinThrowAngleRadians = spinThrow,
            totalThrowAngleRadians = totalThrow,
            netCueAimAdjustmentRadians = netCueAimAdjustment,
            compensatedGhostBallCenter = compensatedGhost,
        )
    }

    /**
     * Traces post-impact cue-ball motion across cloth and up to [maxBounces] cushion rebounds using
     * continuous vertical spin (`TipOffset.clampedVerticalRatio`) and a Han (2005)-inspired
     * side-spin cushion rebound model.
     */
    fun traceCueBallPathWithSpin(
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
        ballRadiusMeters: Double = TableSpec.POOL_BALL_RADIUS_METERS,
        maxBounces: Int = 3,
    ): CueBallTrajectory {
        val preset = tip.toSpinPreset()
        val start = aim.ghostBallCenter
        val absPhi = abs(aim.cutAngleRadians)
        val sinPhi = sin(absPhi)
        val cosPhi = cos(absPhi)

        if (cueSpeedMps <= 1e-6) {
            return CueBallTrajectory(
                spin = preset,
                points = listOf(start),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = detectScratchOnSegment(start, start, table.pockets)?.id,
            )
        }

        val tangentDir = if (aim.cutAngleRadians >= 0.0) {
            aim.objectBallDirection.rotate(-PI / 2.0)
        } else {
            aim.objectBallDirection.rotate(PI / 2.0)
        }

        val vertical = tip.clampedVerticalRatio
        // Map normalized vertical tip offset smoothly to dimensionless spin factor k in [-1.85, 1.35]:
        // STUN (0.0) -> 0.0, FOLLOW (+0.35) -> +1.0, DRAW (-0.38) -> -1.5.
        val spinFactorK = if (vertical >= 0.0) {
            (vertical / 0.35).coerceIn(0.0, 1.35)
        } else {
            (vertical / 0.38 * 1.5).coerceIn(-1.85, 0.0)
        }

        val postSlideVelocity = if (absPhi <= STRAIGHT_IN_ANGLE_EPSILON_RAD) {
            if (abs(spinFactorK) <= 1e-4) {
                Vec2.ZERO
            } else {
                aim.objectBallDirection * ((2.0 / 7.0) * spinFactorK * cueSpeedMps)
            }
        } else {
            (
                aim.objectBallDirection * ((2.0 / 7.0) * spinFactorK * cosPhi) +
                    tangentDir * ((5.0 / 7.0 + (2.0 / 7.0) * spinFactorK) * sinPhi)
                ) * cueSpeedMps
        }

        val baseSpeed = postSlideVelocity.length()
        if (baseSpeed <= 1e-6) {
            return CueBallTrajectory(
                spin = preset,
                points = listOf(start),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = detectScratchOnSegment(start, start, table.pockets)?.id,
            )
        }

        val initialDir = postSlideVelocity / baseSpeed
        val initialDeflectionRad = aim.aimDirection.angleTo(initialDir)

        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2

        // Blend in the exact pure-stun slide distance near verticalRatio == 0 so STUN distance matches MotionPhysics.
        val stunWeight = (1.0 - abs(spinFactorK)).coerceIn(0.0, 1.0)
        val extraSlideDist = if (absPhi > STRAIGHT_IN_ANGLE_EPSILON_RAD && stunWeight > 0.0) {
            val vSlide0 = cueSpeedMps * sinPhi
            val slideDist = (12.0 * vSlide0 * vSlide0) / (49.0 * muS * g)
            slideDist * stunWeight
        } else {
            0.0
        }
        val initialRollingDist = MotionPhysics.rollingStoppingDistanceMeters(baseSpeed, muR, g) + extraSlideDist
        val effectiveInitialSpeed = MotionPhysics.initialRollingSpeedForDistance(initialRollingDist, muR, g)

        val points = mutableListOf(start)
        var currentPos = start
        var currentVel = initialDir * effectiveInitialSpeed
        val safeMaxBounces = maxBounces.coerceAtLeast(0)

        for (bounceIndex in 0..safeMaxBounces) {
            val speed = currentVel.length()
            if (speed <= 1e-6) break

            val dir = currentVel / speed
            val stoppingDist = MotionPhysics.rollingStoppingDistanceMeters(speed, muR, g)
            if (stoppingDist <= 1e-6) break

            val hit = intersectRails(currentPos, dir, table, ballRadiusMeters)
            val travel = min(stoppingDist, hit.distance)
            val segEnd = currentPos + dir * travel
            val extendedEnd = if (stoppingDist >= hit.distance) {
                currentPos + dir * (hit.distance + ballRadiusMeters)
            } else {
                segEnd
            }

            val scratch = detectScratchOnSegment(currentPos, extendedEnd, table.pockets)
            if (scratch != null) {
                points += scratch.center
                return CueBallTrajectory(
                    spin = preset,
                    points = points,
                    initialDeflectionAngleRad = initialDeflectionRad,
                    hitsScratchPocket = scratch.id,
                )
            }

            points += segEnd
            if (stoppingDist <= hit.distance + 1e-6 || bounceIndex == safeMaxBounces) {
                break
            }

            val remainingDist = (stoppingDist - hit.distance).coerceAtLeast(0.0)
            val impactSpeed = MotionPhysics.initialRollingSpeedForDistance(remainingDist, muR, g)
            val impactVel = dir * impactSpeed
            val normal = inwardNormalForHit(hit, table, segEnd)
            val vNormalMag = abs(impactVel.dot(normal))
            if (vNormalMag <= 1e-6) break

            // Table coordinates have +x Head->Foot and +y Left->Right, so (-normal).rotate(+PI/2) = Vec2(n.y, -n.x)
            // points to the player's right when facing the cushion; right english (+sideRatio) kicks right (+t).
            val tangent = Vec2(normal.y, -normal.x)
            val ecEffective = (table.cloth.cushionRestitutionEc - 0.035 * vNormalMag).coerceIn(0.55, 0.90)
            val vNormalOut = vNormalMag * ecEffective

            val sideSpinRatio = tip.clampedSideRatio * (0.72.pow(bounceIndex))
            val vTanIn = impactVel.dot(tangent)
            val vTanOut = vTanIn * 0.82 + sideSpinRatio * vNormalMag * 0.55

            currentVel = normal * vNormalOut + tangent * vTanOut
            currentPos = Vec2(
                x = segEnd.x.coerceIn(ballRadiusMeters, (table.lengthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)),
                y = segEnd.y.coerceIn(ballRadiusMeters, (table.widthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)),
            )
        }

        return CueBallTrajectory(
            spin = preset,
            points = points,
            initialDeflectionAngleRad = initialDeflectionRad,
            hitsScratchPocket = null,
        )
    }

    private data class RailHit(
        val distance: Double,
        val hitVerticalRail: Boolean,
        val hitHorizontalRail: Boolean,
    )

    private fun intersectRails(
        origin: Vec2,
        dir: Vec2,
        table: TableSpec,
        ballRadiusMeters: Double,
    ): RailHit {
        val minX = ballRadiusMeters
        val maxX = (table.lengthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)
        val minY = ballRadiusMeters
        val maxY = (table.widthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)

        val tx = when {
            dir.x > 1e-9 -> ((maxX - origin.x) / dir.x).coerceAtLeast(0.0)
            dir.x < -1e-9 -> ((minX - origin.x) / dir.x).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }
        val ty = when {
            dir.y > 1e-9 -> ((maxY - origin.y) / dir.y).coerceAtLeast(0.0)
            dir.y < -1e-9 -> ((minY - origin.y) / dir.y).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }

        val minDist = min(tx, ty)
        val hitVertical = abs(tx - minDist) <= 1e-6
        val hitHorizontal = abs(ty - minDist) <= 1e-6
        return RailHit(
            distance = minDist,
            hitVerticalRail = hitVertical,
            hitHorizontalRail = hitHorizontal,
        )
    }

    private fun inwardNormalForHit(
        hit: RailHit,
        table: TableSpec,
        hitPos: Vec2,
    ): Vec2 {
        val nx = if (hit.hitVerticalRail) {
            if (hitPos.x <= table.lengthMeters * 0.5) 1.0 else -1.0
        } else {
            0.0
        }
        val ny = if (hit.hitHorizontalRail) {
            if (hitPos.y <= table.widthMeters * 0.5) 1.0 else -1.0
        } else {
            0.0
        }
        val raw = Vec2(nx, ny)
        return if (raw.lengthSquared() < 1e-12) Vec2(1.0, 0.0) else raw.normalized()
    }

    private fun detectScratchOnSegment(
        start: Vec2,
        end: Vec2,
        pockets: List<PocketSpec>,
    ): PocketSpec? {
        val seg = end - start
        val segLenSq = seg.lengthSquared()
        var bestPocket: PocketSpec? = null
        var bestT = Double.POSITIVE_INFINITY

        for (pocket in pockets) {
            val t = if (segLenSq < 1e-12) {
                0.0
            } else {
                ((pocket.center - start).dot(seg) / segLenSq).coerceIn(0.0, 1.0)
            }
            val closest = start + seg * t
            if (pocket.center.distanceTo(closest) <= pocket.acceptanceRadiusMeters) {
                if (t < bestT) {
                    bestT = t
                    bestPocket = pocket
                }
            }
        }
        return bestPocket
    }
}
