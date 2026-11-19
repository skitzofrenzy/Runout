package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.*
import kotlin.math.*

/**
 * Discrete animation stages across a single pool or snooker shot timeline.
 */
enum class ShotAnimationPhase {
    CUE_ADDRESS_AND_STRIKE,
    CUE_BALL_APPROACH,
    CONTACT_AND_THROW,
    POST_IMPACT_ROLL,
    BALLS_AT_REST,
}

/**
 * Sampled kinematic snapshot of the table, balls, cue stick, and path trails at [progressFraction].
 */
data class ShotTimelineFrame(
    val progressFraction: Double,
    val timestampSeconds: Double,
    val totalDurationSeconds: Double,
    val phase: ShotAnimationPhase,
    val balls: List<Ball>,
    val pocketedBallNumbers: Set<Int>,
    val cueBallPositionMeters: Vec2,
    val targetBallPositionMeters: Vec2?,
    val cueBallSpeedMps: Double,
    val targetBallSpeedMps: Double,
    val cueStickVisible: Boolean,
    val cueStickTipMeters: Vec2,
    val cueStickButtMeters: Vec2,
    val traveledCuePathPoints: List<Vec2>,
    val traveledObjectPathPoints: List<Vec2>,
)

/**
 * Precomputed kinematic timeline for a single shot from cue address through ball rest.
 *
 * All distances are in SI metres, durations in seconds, and angles in radians.
 */
class ShotAnimationTimeline(
    val initialLayout: TableLayout,
    val targetBall: Ball,
    val pocket: PocketSpec,
    val compensatedAim: SpinCompensatedAim,
    val cueBallTrajectory: CueBallTrajectory,
    val cueSpeedMps: Double,
    val tip: TipOffset,
    val strikeDurationSeconds: Double,
    val approachDurationSeconds: Double,
    val objectPocketDurationSeconds: Double,
    val cueLeaveDurationSeconds: Double,
    val totalDurationSeconds: Double,
) {
    /**
     * Normalized progress fraction `[0.05, 0.85]` at which the cue ball reaches ghost-ball impact.
     */
    val impactProgressFraction: Double =
        ((strikeDurationSeconds + approachDurationSeconds) / totalDurationSeconds.coerceAtLeast(1e-6))
            .coerceIn(0.05, 0.85)

    private val initialCuePos: Vec2 =
        initialLayout.cueBall()?.position ?: compensatedAim.geometricAim.cueBallPos

    private val cueBallRadius: Double =
        initialLayout.cueBall()?.radiusMeters ?: initialLayout.table.ballRadiusMeters

    private val ghostPos: Vec2 = compensatedAim.compensatedGhostBallCenter

    private val aimDir: Vec2 = run {
        val delta = ghostPos - initialCuePos
        if (delta.lengthSquared() > 1e-12) delta.normalized() else compensatedAim.geometricAim.aimDirection
    }

    private val postPolyline: List<Vec2> = buildList {
        add(ghostPos)
        if (cueBallTrajectory.points.size >= 2) {
            for (pt in cueBallTrajectory.points.drop(1)) {
                if (last().distanceTo(pt) > 1e-9) {
                    add(pt)
                }
            }
        }
    }

    private val postPolylineLengthMeters: Double = computePolylineLength(postPolyline)

    /**
     * Samples the shot animation state at normalized progress [progressFraction] (clamped to `0.0..1.0`).
     */
    fun sampleAtProgress(progressFraction: Double): ShotTimelineFrame {
        val p = progressFraction.coerceIn(0.0, 1.0)
        val t = p * totalDurationSeconds
        val impactTime = strikeDurationSeconds + approachDurationSeconds
        val defaultTip = initialCuePos - aimDir * cueBallRadius
        val defaultButt = defaultTip - aimDir * CUE_STICK_LENGTH_METERS

        if (t <= strikeDurationSeconds) {
            val uStrike = (t / strikeDurationSeconds.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            val pullbackMeters = computeCuePullbackMeters(uStrike, cueBallRadius)
            val tipPos = initialCuePos - aimDir * pullbackMeters
            val buttPos = tipPos - aimDir * CUE_STICK_LENGTH_METERS

            return ShotTimelineFrame(
                progressFraction = p,
                timestampSeconds = t,
                totalDurationSeconds = totalDurationSeconds,
                phase = ShotAnimationPhase.CUE_ADDRESS_AND_STRIKE,
                balls = initialLayout.balls,
                pocketedBallNumbers = emptySet(),
                cueBallPositionMeters = initialCuePos,
                targetBallPositionMeters = targetBall.position,
                cueBallSpeedMps = 0.0,
                targetBallSpeedMps = 0.0,
                cueStickVisible = true,
                cueStickTipMeters = tipPos,
                cueStickButtMeters = buttPos,
                traveledCuePathPoints = listOf(initialCuePos),
                traveledObjectPathPoints = listOf(targetBall.position),
            )
        }

        if (t <= impactTime) {
            val alpha = ((t - strikeDurationSeconds) / approachDurationSeconds.coerceAtLeast(1e-6))
                .coerceIn(0.0, 1.0)
            val currentCuePos = initialCuePos.lerp(ghostPos, alpha)
            val updatedBalls = initialLayout.balls.map { ball ->
                if (ball.isCueBall) ball.copy(position = currentCuePos) else ball
            }

            return ShotTimelineFrame(
                progressFraction = p,
                timestampSeconds = t,
                totalDurationSeconds = totalDurationSeconds,
                phase = ShotAnimationPhase.CUE_BALL_APPROACH,
                balls = updatedBalls,
                pocketedBallNumbers = emptySet(),
                cueBallPositionMeters = currentCuePos,
                targetBallPositionMeters = targetBall.position,
                cueBallSpeedMps = cueSpeedMps,
                targetBallSpeedMps = 0.0,
                cueStickVisible = false,
                cueStickTipMeters = defaultTip,
                cueStickButtMeters = defaultButt,
                traveledCuePathPoints = listOf(initialCuePos, currentCuePos),
                traveledObjectPathPoints = listOf(targetBall.position),
            )
        }

        val tPost = (t - impactTime).coerceAtLeast(0.0)
        val phase = when {
            tPost <= CONTACT_THROW_WINDOW_SECONDS -> ShotAnimationPhase.CONTACT_AND_THROW
            p >= 0.999 -> ShotAnimationPhase.BALLS_AT_REST
            else -> ShotAnimationPhase.POST_IMPACT_ROLL
        }

        val pocketed = mutableSetOf<Int>()
        val safeObjDuration = objectPocketDurationSeconds.coerceAtLeast(1e-6)
        val isTargetPocketed = tPost >= safeObjDuration - 1e-9
        val objDist = targetBall.position.distanceTo(pocket.center)
        val targetPos: Vec2?
        val targetSpeed: Double
        val traveledObjectPoints: List<Vec2>

        if (isTargetPocketed) {
            pocketed += targetBall.number
            targetPos = null
            targetSpeed = 0.0
            traveledObjectPoints = listOf(targetBall.position, pocket.center)
        } else {
            val vObj = (tPost / safeObjDuration).coerceIn(0.0, 1.0)
            val sObj = (1.12 * vObj - 0.12 * vObj * vObj).coerceIn(0.0, 1.0)
            val objPos = targetBall.position.lerp(pocket.center, sObj)
            targetPos = objPos
            targetSpeed = if (phase == ShotAnimationPhase.BALLS_AT_REST) {
                0.0
            } else {
                (objDist / safeObjDuration) * (1.12 - 0.24 * vObj)
            }
            traveledObjectPoints = listOf(targetBall.position, objPos)
        }

        val safeCueLeaveDuration = cueLeaveDurationSeconds.coerceAtLeast(1e-6)
        val uCue = (tPost / safeCueLeaveDuration).coerceIn(0.0, 1.0)
        val sCue = 1.0 - (1.0 - uCue).pow(2)
        val (currentCuePos, postTraveled) = samplePolylineAtFraction(postPolyline, postPolylineLengthMeters, sCue)

        if (cueBallTrajectory.hitsScratchPocket != null && uCue >= 1.0 - 1e-9) {
            pocketed += 0
        }

        val cueSpeed = if (phase == ShotAnimationPhase.BALLS_AT_REST || postPolylineLengthMeters <= 1e-6) {
            0.0
        } else {
            (2.0 * postPolylineLengthMeters / safeCueLeaveDuration) * (1.0 - uCue)
        }

        val traveledCue = buildList {
            add(initialCuePos)
            for (pt in postTraveled) {
                if (last().distanceTo(pt) > 1e-9) {
                    add(pt)
                }
            }
        }

        val updatedBalls = initialLayout.balls.mapNotNull { ball ->
            when {
                ball.number in pocketed -> null
                ball.isCueBall -> ball.copy(position = currentCuePos)
                ball.number == targetBall.number && targetPos != null -> ball.copy(position = targetPos)
                else -> ball
            }
        }

        return ShotTimelineFrame(
            progressFraction = p,
            timestampSeconds = t,
            totalDurationSeconds = totalDurationSeconds,
            phase = phase,
            balls = updatedBalls,
            pocketedBallNumbers = pocketed,
            cueBallPositionMeters = currentCuePos,
            targetBallPositionMeters = targetPos,
            cueBallSpeedMps = cueSpeed,
            targetBallSpeedMps = targetSpeed,
            cueStickVisible = false,
            cueStickTipMeters = defaultTip,
            cueStickButtMeters = defaultButt,
            traveledCuePathPoints = traveledCue,
            traveledObjectPathPoints = traveledObjectPoints,
        )
    }

    /**
     * Samples [frameCount] evenly spaced frames across `0.0..1.0`.
     */
    fun sampleKeyframes(frameCount: Int = 21): List<ShotTimelineFrame> {
        if (frameCount <= 1) {
            return listOf(sampleAtProgress(0.0))
        }
        val denom = (frameCount - 1).toDouble()
        return List(frameCount) { index ->
            sampleAtProgress(index.toDouble() / denom)
        }
    }

    private companion object {
        private const val CUE_STICK_LENGTH_METERS: Double = 1.45
        private const val MAX_CUE_PULLBACK_METERS: Double = 0.18
        private const val CONTACT_THROW_WINDOW_SECONDS: Double = 0.04

        private fun computeCuePullbackMeters(uStrike: Double, cueRadius: Double): Double {
            val contactDist = cueRadius.coerceIn(0.01, MAX_CUE_PULLBACK_METERS)
            val addressDist = (contactDist + 0.035).coerceAtMost(MAX_CUE_PULLBACK_METERS)
            val peakFraction = 0.60
            return if (uStrike <= peakFraction) {
                val b = (uStrike / peakFraction).coerceIn(0.0, 1.0)
                val smoothB = 0.5 - 0.5 * cos(PI * b)
                addressDist + (MAX_CUE_PULLBACK_METERS - addressDist) * smoothB
            } else {
                val f = ((uStrike - peakFraction) / (1.0 - peakFraction)).coerceIn(0.0, 1.0)
                val accelF = f * f
                MAX_CUE_PULLBACK_METERS + (contactDist - MAX_CUE_PULLBACK_METERS) * accelF
            }
        }

        private fun computePolylineLength(points: List<Vec2>): Double {
            if (points.size < 2) return 0.0
            var total = 0.0
            for (i in 0 until points.lastIndex) {
                total += points[i].distanceTo(points[i + 1])
            }
            return total
        }

        private fun samplePolylineAtFraction(
            points: List<Vec2>,
            totalLength: Double,
            fraction: Double,
        ): Pair<Vec2, List<Vec2>> {
            if (points.isEmpty()) return Vec2.ZERO to emptyList()
            if (points.size == 1 || totalLength <= 1e-9) {
                return points.first() to listOf(points.first())
            }
            val clampedFraction = fraction.coerceIn(0.0, 1.0)
            val targetDist = clampedFraction * totalLength
            val traveled = mutableListOf(points.first())
            var accumulated = 0.0

            for (i in 0 until points.lastIndex) {
                val segStart = points[i]
                val segEnd = points[i + 1]
                val segLen = segStart.distanceTo(segEnd)
                if (accumulated + segLen >= targetDist - 1e-9) {
                    val tSeg = if (segLen > 1e-12) {
                        ((targetDist - accumulated) / segLen).coerceIn(0.0, 1.0)
                    } else {
                        1.0
                    }
                    val pos = segStart.lerp(segEnd, tSeg)
                    if (traveled.last().distanceTo(pos) > 1e-9) {
                        traveled += pos
                    }
                    return pos to traveled
                }
                accumulated += segLen
                if (traveled.last().distanceTo(segEnd) > 1e-9) {
                    traveled += segEnd
                }
            }

            return points.last() to traveled
        }
    }
}

/**
 * Perspective-projected 3D table point in normalized view-plane coordinates.
 */
data class Projected3DPoint(
    val normalizedX: Double,
    val normalizedY: Double,
    val depthMeters: Double,
    val apparentRadiusNormalized: Double,
)

/**
 * Orbit camera specification centered on the table playing surface center in SI units.
 */
data class Camera3DOrbitSpec(
    val yawRadians: Double = -0.65,
    val pitchRadians: Double = 0.72,
    val distanceMeters: Double = 2.85,
    val fovVerticalRadians: Double = 0.85,
) {
    companion object {
        /**
         * Positions the camera behind the cue ball looking down [aimDirection].
         */
        fun behindCueView(aimDirection: Vec2 = Vec2(1.0, 0.0)): Camera3DOrbitSpec {
            val yaw = if (aimDirection.lengthSquared() > 1e-12) {
                atan2(aimDirection.y, aimDirection.x)
            } else {
                0.0
            }
            return Camera3DOrbitSpec(
                yawRadians = yaw,
                pitchRadians = 0.48,
                distanceMeters = 2.65,
            )
        }

        fun cornerIsometricView(): Camera3DOrbitSpec = Camera3DOrbitSpec(
            yawRadians = -0.65,
            pitchRadians = 0.68,
            distanceMeters = 2.85,
        )

        fun sideBroadcastView(): Camera3DOrbitSpec = Camera3DOrbitSpec(
            yawRadians = -1.5707963267948966,
            pitchRadians = 0.62,
            distanceMeters = 2.70,
        )

        fun overheadTopDownView(): Camera3DOrbitSpec = Camera3DOrbitSpec(
            yawRadians = -1.5707963267948966,
            pitchRadians = 1.48,
            distanceMeters = 3.10,
        )
    }
}

/**
 * Builds deterministic shot-animation timelines and projects 3D table coordinates for visualization.
 */
object ShotTimelineAnimator {
    private const val DEFAULT_STRIKE_DURATION_SECONDS: Double = 0.22

    /**
     * Builds a complete [ShotAnimationTimeline] for potting [targetBallNumber] into [pocketId],
     * or returns `null` if required balls/pockets are missing or the cut is impossible.
     */
    fun buildTimeline(
        layout: TableLayout,
        targetBallNumber: Int,
        pocketId: PocketId,
        cueSpeedMps: Double = 2.0,
        tip: TipOffset = TipOffset(),
        shaftType: ShaftType = ShaftType.STANDARD,
    ): ShotAnimationTimeline? {
        if (!cueSpeedMps.isFinite()) return null
        val cueBall = layout.cueBall() ?: return null
        val targetBall = layout.ballByNumber(targetBallNumber) ?: return null
        if (targetBall.isCueBall) return null
        val pocket = layout.table.pockets.firstOrNull { it.id == pocketId } ?: return null

        val safeSpeed = cueSpeedMps.coerceIn(0.3, 12.0)
        val geom = GhostBallAiming.computeAimGeometry(
            cueBall = cueBall,
            objectBall = targetBall,
            pocket = pocket,
            otherBalls = layout.balls,
            table = layout.table,
        )
        val comp = SpinPhysics.solveCompensatedAim(
            geometricAim = geom,
            cueBallRadiusMeters = cueBall.radiusMeters,
            objectBallRadiusMeters = targetBall.radiusMeters,
            cueSpeedMps = safeSpeed,
            tip = tip,
            shaftType = shaftType,
            table = layout.table,
        ) ?: return null

        val traj = SpinPhysics.traceCueBallPathWithSpin(
            aim = geom,
            cueSpeedMps = safeSpeed,
            tip = tip,
            table = layout.table,
            ballRadiusMeters = cueBall.radiusMeters,
        )

        val strikeDurationSeconds = DEFAULT_STRIKE_DURATION_SECONDS
        val approachDurationSeconds =
            (geom.cueToGhostDistanceMeters / safeSpeed.coerceAtLeast(0.4)).coerceIn(0.12, 1.8)
        val objLaunchSpeed = (safeSpeed * cos(geom.cutAngleRadians).coerceAtLeast(0.25)).coerceAtLeast(0.35)
        val objectPocketDurationSeconds =
            (geom.objectToPocketDistanceMeters / objLaunchSpeed).coerceIn(0.15, 2.2)

        val cueLeaveLength = computeCueLeaveLength(comp.compensatedGhostBallCenter, traj.points)
        val cueLeaveDurationSeconds = if (cueLeaveLength <= 1e-4) {
            0.20
        } else {
            (cueLeaveLength / (safeSpeed * 0.42).coerceAtLeast(0.45)).coerceIn(0.20, 2.6)
        }

        val preImpactDuration = strikeDurationSeconds + approachDurationSeconds
        val postImpactDuration = maxOf(
            objectPocketDurationSeconds,
            cueLeaveDurationSeconds,
            preImpactDuration * 0.22,
        )
        val totalDurationSeconds = preImpactDuration + postImpactDuration

        return ShotAnimationTimeline(
            initialLayout = layout,
            targetBall = targetBall,
            pocket = pocket,
            compensatedAim = comp,
            cueBallTrajectory = traj,
            cueSpeedMps = safeSpeed,
            tip = tip,
            strikeDurationSeconds = strikeDurationSeconds,
            approachDurationSeconds = approachDurationSeconds,
            objectPocketDurationSeconds = objectPocketDurationSeconds,
            cueLeaveDurationSeconds = cueLeaveDurationSeconds,
            totalDurationSeconds = totalDurationSeconds,
        )
    }

    /**
     * Projects a 3D table-space point `(xMeters, yMeters, zMeters)` into normalized perspective view coordinates.
     *
     * Table center lies at `(table.lengthMeters * 0.5, table.widthMeters * 0.5, 0.0)`, with `+z` pointing
     * upward from the cloth bed (mapped to smaller [Projected3DPoint.normalizedY] values on screen).
     */
    fun projectTablePoint3D(
        xMeters: Double,
        yMeters: Double,
        zMeters: Double,
        table: TableSpec,
        camera: Camera3DOrbitSpec = Camera3DOrbitSpec(),
        sphereRadiusMeters: Double = table.ballRadiusMeters,
    ): Projected3DPoint {
        val cx = table.lengthMeters * 0.5
        val cy = table.widthMeters * 0.5
        val dx = xMeters - cx
        val dy = yMeters - cy
        val dz = zMeters

        val pitch = camera.pitchRadians.coerceIn(0.12, 1.53)
        val yaw = camera.yawRadians
        val dist = camera.distanceMeters.coerceAtLeast(0.25)

        val cosP = cos(pitch)
        val sinP = sin(pitch)
        val cosY = cos(yaw)
        val sinY = sin(yaw)

        val camX = -dist * cosP * cosY
        val camY = -dist * cosP * sinY
        val camZ = dist * sinP

        val relX = dx - camX
        val relY = dy - camY
        val relZ = dz - camZ

        val forwardX = cosP * cosY
        val forwardY = cosP * sinY
        val forwardZ = -sinP

        val rightX = -sinY
        val rightY = cosY

        val upX = sinP * cosY
        val upY = sinP * sinY
        val upZ = cosP

        val depth = (relX * forwardX + relY * forwardY + relZ * forwardZ).coerceAtLeast(0.15)
        val focal = 1.0 / tan(camera.fovVerticalRadians.coerceIn(0.3, 1.4) * 0.5)

        val normalizedX = (relX * rightX + relY * rightY) * focal / depth
        val normalizedY = -(relX * upX + relY * upY + relZ * upZ) * focal / depth
        val apparentRadiusNormalized = (sphereRadiusMeters.coerceAtLeast(0.0) * focal / depth).coerceAtLeast(0.002)

        return Projected3DPoint(
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            depthMeters = depth,
            apparentRadiusNormalized = apparentRadiusNormalized,
        )
    }

    private fun computeCueLeaveLength(ghostPos: Vec2, points: List<Vec2>): Double {
        if (points.size < 2) return 0.0
        var total = ghostPos.distanceTo(points[1])
        for (i in 1 until points.lastIndex) {
            total += points[i].distanceTo(points[i + 1])
        }
        return total
    }
}
