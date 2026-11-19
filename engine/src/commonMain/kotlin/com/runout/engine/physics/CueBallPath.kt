package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vertical-plane spin preset for post-impact cue-ball trajectory prediction.
 */
enum class CueBallSpinPreset {
    STUN,
    FOLLOW,
    DRAW,
}

/**
 * Predicted post-impact trajectory for a given [CueBallSpinPreset].
 *
 * @property spin The spin preset used.
 * @property points Polyline starting at `ghostBallCenter` and ending at the stopping point or pocket.
 * @property initialDeflectionAngleRad Signed deflection angle in radians from `aimDirection` to
 *   the initial post-impact cue-ball velocity vector (`0.0` if the cue ball stops at impact).
 * @property hitsScratchPocket Non-null if the trajectory enters a pocket's acceptance radius.
 */
data class TrajectoryPath(
    val spin: CueBallSpinPreset,
    val points: List<Vec2>,
    val initialDeflectionAngleRad: Double,
    val hitsScratchPocket: PocketId?,
)

object CueBallPath {
    private const val STRAIGHT_IN_ANGLE_EPSILON_RAD: Double = 1e-4
    private const val DRAW_SPIN_RATIO: Double = 1.5

    /**
     * Computes post-impact trajectories for [CueBallSpinPreset.STUN] (90-degree tangent rule),
     * [CueBallSpinPreset.FOLLOW] (30-degree natural-roll rule), and [CueBallSpinPreset.DRAW].
     *
     * Post-slide cue-ball velocity follows $\mathbf{v}_f = \frac{5}{7}\mathbf{v}' + \frac{2}{7}\mathbf{v}_{\text{spin}}$
     * in the orthogonal basis $\{\hat{\mathbf{d}}_{\text{ob}}, \hat{\mathbf{t}}\}$, where
     * $\mathbf{v}' = v_0 \sin|\phi|\,\hat{\mathbf{t}}$ is the immediate post-impact CM velocity and
     * $\hat{\mathbf{a}} = \cos|\phi|\,\hat{\mathbf{d}}_{\text{ob}} + \sin|\phi|\,\hat{\mathbf{t}}$ is the aim line:
     * - `STUN` ($\mathbf{v}_{\text{spin}} = \mathbf{0}$): $\mathbf{v}_f = \frac{5}{7}v_0\sin|\phi|\,\hat{\mathbf{t}}$,
     *   with total slide-plus-roll travel distance from [MotionPhysics.stunStoppingDistanceMeters].
     * - `FOLLOW` ($\mathbf{v}_{\text{spin}} = v_0\,\hat{\mathbf{a}}$):
     *   $\mathbf{v}_f = v_0\left(\frac{2}{7}\cos|\phi|\,\hat{\mathbf{d}}_{\text{ob}} + \sin|\phi|\,\hat{\mathbf{t}}\right)$.
     * - `DRAW` ($\mathbf{v}_{\text{spin}} = -k\,v_0\,\hat{\mathbf{a}}$, $k = \text{DRAW\_SPIN\_RATIO} = 1.5$):
     *   $\mathbf{v}_f = v_0\left[-\frac{2}{7}k\cos|\phi|\,\hat{\mathbf{d}}_{\text{ob}} + \left(\frac{5}{7} - \frac{2}{7}k\right)\sin|\phi|\,\hat{\mathbf{t}}\right]$,
     *   giving tangent coefficient $\frac{2}{7}\sin|\phi|$ ($-128.95^\circ$ deflection at a $30^\circ$ cut).
     *
     * Paths trace straight segments from `ghostBallCenter` along $\mathbf{v}_f$ (omitting the short
     * curved slide phase for `FOLLOW`/`DRAW`), with at most one mirror-reflected cushion bounce scaled by $e_c$.
     */
    fun computePostImpactPaths(
        aim: AimGeometry,
        table: TableSpec,
        initialCueSpeedMps: Double = 2.0,
    ): Map<CueBallSpinPreset, TrajectoryPath> {
        val phi = aim.cutAngleRadians
        val absPhi = abs(phi)
        val sinPhi = sin(absPhi)
        val cosPhi = cos(absPhi)

        // Tangent unit vector perpendicular to objectBallDirection on the side away from the object ball path.
        // Recall: phi = aimDirection.angleTo(objectBallDirection).
        // When phi > 0, objectBallDirection is CCW (+phi) from aimDirection, so aimDirection is CW (-phi)
        // from objectBallDirection and the tangent side is -90 deg (-PI / 2).
        // When phi < 0, the tangent side is +90 deg (+PI / 2).
        val tangentDir = if (phi >= 0.0) {
            aim.objectBallDirection.rotate(-PI / 2.0)
        } else {
            aim.objectBallDirection.rotate(PI / 2.0)
        }

        // 1. STUN (90-degree rule):
        // Leaves along tangentDir with post-impact speed v0 * sin|phi| and zero spin, so slide + roll
        // stays strictly collinear along tangentDir with exact total distance from stunStoppingDistanceMeters.
        val stunPath = if (absPhi <= STRAIGHT_IN_ANGLE_EPSILON_RAD || initialCueSpeedMps <= 1e-6) {
            TrajectoryPath(
                spin = CueBallSpinPreset.STUN,
                points = listOf(aim.ghostBallCenter),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = detectScratchOnSegment(
                    start = aim.ghostBallCenter,
                    end = aim.ghostBallCenter,
                    pockets = table.pockets,
                )?.id,
            )
        } else {
            val stunSpeed = initialCueSpeedMps * sinPhi
            val stunTotalDistance = MotionPhysics.stunStoppingDistanceMeters(
                initialCueSpeedMps = initialCueSpeedMps,
                cutAngleRad = absPhi,
                muS = table.cloth.slidingFrictionMuS,
                muR = table.cloth.rollingResistanceMuR,
            )
            tracePath(
                spin = CueBallSpinPreset.STUN,
                start = aim.ghostBallCenter,
                aimDirection = aim.aimDirection,
                velocityDir = tangentDir,
                rollingSpeedMps = MotionPhysics.stunRollingSpeed(stunSpeed),
                table = table,
                totalDistanceMetersOverride = stunTotalDistance,
            )
        }

        // 2. FOLLOW (30-degree natural-roll rule):
        // Analytical rolling cue-ball post-slide velocity:
        // v_f = v0 * ( (2/7) * cos|phi| * d_ob + sin|phi| * t )
        // At phi = 0, follows straight forward along objectBallDirection.
        // At 1/4 to 3/4 ball cuts, deflects ~27°–33.7° from aimDirection (33.67° at half-ball phi = 30°).
        val followVelocity = (
            aim.objectBallDirection * ((2.0 / 7.0) * cosPhi) +
                tangentDir * sinPhi
            ) * initialCueSpeedMps
        val followSpeed = followVelocity.length()
        val followDir = if (followSpeed > 1e-9) {
            followVelocity / followSpeed
        } else {
            aim.objectBallDirection
        }
        val followPath = tracePath(
            spin = CueBallSpinPreset.FOLLOW,
            start = aim.ghostBallCenter,
            aimDirection = aim.aimDirection,
            velocityDir = followDir,
            rollingSpeedMps = followSpeed,
            table = table,
        )

        // 3. DRAW (backspin behind the tangent line):
        // With v_spin = -DRAW_SPIN_RATIO * v0 * aimDirection
        // = -DRAW_SPIN_RATIO * v0 * (cos|phi| * d_ob + sin|phi| * t),
        // applying v_f = (5/7) * v' + (2/7) * v_spin gives:
        // v_f = v0 * (-(2/7)*DRAW_SPIN_RATIO*cos|phi|*d_ob + (5/7 - (2/7)*DRAW_SPIN_RATIO)*sin|phi|*t).
        // For DRAW_SPIN_RATIO = 1.5, tangent coefficient is (2/7)*sin|phi| (-128.95° deflection at 30° cut),
        // reducing to -(2/7)*DRAW_SPIN_RATIO*v0*aimDirection straight back at phi = 0.
        val drawVelocity = if (absPhi <= STRAIGHT_IN_ANGLE_EPSILON_RAD) {
            -aim.aimDirection * ((2.0 / 7.0) * DRAW_SPIN_RATIO * initialCueSpeedMps)
        } else {
            (
                -aim.objectBallDirection * ((2.0 / 7.0) * DRAW_SPIN_RATIO * cosPhi) +
                    tangentDir * ((5.0 / 7.0 - (2.0 / 7.0) * DRAW_SPIN_RATIO) * sinPhi)
                ) * initialCueSpeedMps
        }
        val drawSpeed = drawVelocity.length()
        val drawDir = if (drawSpeed > 1e-9) {
            drawVelocity / drawSpeed
        } else {
            -aim.aimDirection
        }
        val drawPath = tracePath(
            spin = CueBallSpinPreset.DRAW,
            start = aim.ghostBallCenter,
            aimDirection = aim.aimDirection,
            velocityDir = drawDir,
            rollingSpeedMps = drawSpeed,
            table = table,
        )

        return mapOf(
            CueBallSpinPreset.STUN to stunPath,
            CueBallSpinPreset.FOLLOW to followPath,
            CueBallSpinPreset.DRAW to drawPath,
        )
    }

    private fun tracePath(
        spin: CueBallSpinPreset,
        start: Vec2,
        aimDirection: Vec2,
        velocityDir: Vec2,
        rollingSpeedMps: Double,
        table: TableSpec,
        totalDistanceMetersOverride: Double? = null,
    ): TrajectoryPath {
        val dir = velocityDir.normalized()
        val deflectionRad = aimDirection.angleTo(dir)
        if (rollingSpeedMps <= 1e-6 || dir.lengthSquared() < 1e-12) {
            return TrajectoryPath(
                spin = spin,
                points = listOf(start),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = detectScratchOnSegment(start, start, table.pockets)?.id,
            )
        }

        val totalDistance = totalDistanceMetersOverride ?: MotionPhysics.rollingStoppingDistanceMeters(
            rollingSpeedMps = rollingSpeedMps,
            muR = table.cloth.rollingResistanceMuR,
        )

        val firstHit = intersectRails(start, dir, table)
        val firstTravel = min(totalDistance, firstHit.distance)
        val firstEnd = start + dir * firstTravel

        // Check if the first segment (or its continuation into the pocket mouth at the rail) enters a pocket.
        val extendedFirstEnd = if (totalDistance >= firstHit.distance) {
            start + dir * (firstHit.distance + table.ballRadiusMeters)
        } else {
            firstEnd
        }
        val scratch1 = detectScratchOnSegment(start, extendedFirstEnd, table.pockets)
        if (scratch1 != null) {
            return TrajectoryPath(
                spin = spin,
                points = listOf(start, scratch1.center),
                initialDeflectionAngleRad = deflectionRad,
                hitsScratchPocket = scratch1.id,
            )
        }

        // Stopped before reaching a cushion.
        if (totalDistance <= firstHit.distance + 1e-6) {
            return TrajectoryPath(
                spin = spin,
                points = listOf(start, firstEnd),
                initialDeflectionAngleRad = deflectionRad,
                hitsScratchPocket = null,
            )
        }

        // One simple cushion bounce with restitution ec.
        val ec = table.cloth.cushionRestitutionEc
        val remainingDistanceAfterBounce = (totalDistance - firstHit.distance) * (ec * ec)
        if (remainingDistanceAfterBounce <= 1e-5) {
            return TrajectoryPath(
                spin = spin,
                points = listOf(start, firstEnd),
                initialDeflectionAngleRad = deflectionRad,
                hitsScratchPocket = null,
            )
        }

        val bouncedDir = Vec2(
            x = if (firstHit.hitVerticalRail) -dir.x else dir.x,
            y = if (firstHit.hitHorizontalRail) -dir.y else dir.y,
        ).normalized()

        val secondHit = intersectRails(firstEnd, bouncedDir, table)
        val secondTravel = min(remainingDistanceAfterBounce, secondHit.distance)
        val secondEnd = firstEnd + bouncedDir * secondTravel
        val extendedSecondEnd = if (remainingDistanceAfterBounce >= secondHit.distance) {
            firstEnd + bouncedDir * (secondHit.distance + table.ballRadiusMeters)
        } else {
            secondEnd
        }

        val scratch2 = detectScratchOnSegment(firstEnd, extendedSecondEnd, table.pockets)
        if (scratch2 != null) {
            return TrajectoryPath(
                spin = spin,
                points = listOf(start, firstEnd, scratch2.center),
                initialDeflectionAngleRad = deflectionRad,
                hitsScratchPocket = scratch2.id,
            )
        }

        return TrajectoryPath(
            spin = spin,
            points = listOf(start, firstEnd, secondEnd),
            initialDeflectionAngleRad = deflectionRad,
            hitsScratchPocket = null,
        )
    }

    private data class RailIntersection(
        val distance: Double,
        val hitVerticalRail: Boolean,
        val hitHorizontalRail: Boolean,
    )

    private fun intersectRails(
        origin: Vec2,
        dir: Vec2,
        table: TableSpec,
    ): RailIntersection {
        val r = table.ballRadiusMeters
        val minX = r
        val maxX = table.lengthMeters - r
        val minY = r
        val maxY = table.widthMeters - r

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
        return RailIntersection(
            distance = minDist,
            hitVerticalRail = hitVertical,
            hitHorizontalRail = hitHorizontal,
        )
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

fun computePostImpactPaths(
    aim: AimGeometry,
    table: TableSpec,
    initialCueSpeedMps: Double = 2.0,
): Map<CueBallSpinPreset, TrajectoryPath> = CueBallPath.computePostImpactPaths(
    aim = aim,
    table = table,
    initialCueSpeedMps = initialCueSpeedMps,
)
