package com.runout.engine.calibration

import com.runout.engine.math.Vec2
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Single-frame ball position observation extracted from overhead calibration video in SI units.
 */
data class VideoFrameBallObservation(
    val frameIndex: Int,
    val timestampSeconds: Double,
    val rawPositionMeters: Vec2,
    val frameMeanLuma: Double = 128.0,
)

/**
 * Kalman-filtered ball kinematic state at a single video frame in SI units (`m`, `m/s`, `s`).
 */
data class FilteredTrackPoint(
    val frameIndex: Int,
    val timestampSeconds: Double,
    val positionMeters: Vec2,
    val velocityMps: Vec2,
    val speedMps: Double,
)

/**
 * Kinematic events detected along a tracked calibration trajectory.
 */
enum class TrajectoryEventType {
    SLIDE_TO_ROLL_TRANSITION,
    CUSHION_BOUNCE,
    BALL_STOP,
}

/**
 * Detected trajectory event at [timestampSeconds] and [positionMeters] with speeds before/after.
 */
data class TrackedTrajectoryEvent(
    val type: TrajectoryEventType,
    val timestampSeconds: Double,
    val positionMeters: Vec2,
    val speedBeforeMps: Double,
    val speedAfterMps: Double,
)

/**
 * Smoothed 2D trajectory, detected kinematic events, and flicker-normalized luma series.
 */
data class TrackedBallTrajectory(
    val points: List<FilteredTrackPoint>,
    val events: List<TrackedTrajectoryEvent>,
    val normalizedLumaSamples: DoubleArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackedBallTrajectory) return false
        return points == other.points &&
            events == other.events &&
            normalizedLumaSamples.contentEquals(other.normalizedLumaSamples)
    }

    override fun hashCode(): Int {
        var result = points.hashCode()
        result = 31 * result + events.hashCode()
        result = 31 * result + normalizedLumaSamples.contentHashCode()
        return result
    }
}

/**
 * Overhead video luma flicker normalization, 2D `[x, y, vx, vy]` Kalman tracking with
 * intra-segment Rauch-Tung-Striebel smoothing, and kinematic event detection.
 */
object VideoBallTracker {
    private const val TWO_PI = 2.0 * PI
    private const val MANEUVER_INNOVATION_SIGMA_SQ = 30.0
    private const val BOUNCE_COOLDOWN_SECONDS = 0.12
    private const val SLIDE_DECEL_THRESHOLD_MPS2 = 0.80
    private const val ROLL_DECEL_THRESHOLD_MPS2 = 0.45
    private const val SAME_DIR_DOT_THRESHOLD = 0.85
    private const val BOUNCE_ANGLE_THRESHOLD_RAD = 0.35
    private const val STOP_SPEED_THRESHOLD_MPS = 0.025
    private const val MOTION_START_SPEED_MPS = 0.08

    /**
     * Rescales each frame's luma by removing 2-frame and 3-frame mains-flicker oscillations
     * while preserving slow brightness trends so the output has mean [targetMeanLuma] and
     * minimal frame-to-frame flicker variance.
     */
    fun normalizeFrameBrightness(
        frameLumas: DoubleArray,
        targetMeanLuma: Double = 128.0,
    ): DoubleArray {
        val n = frameLumas.size
        if (n == 0) return DoubleArray(0)
        if (n <= 3) {
            return DoubleArray(n) { targetMeanLuma }
        }

        // The symmetric 5-tap kernel [1, 3, 4, 3, 1] / 12 is the convolution of [0.25, 0.5, 0.25]
        // and [1/3, 1/3, 1/3], giving exact zeros at both 2-frame (omega = pi) and 3-frame
        // (omega = 2*pi/3) flicker frequencies while preserving constant and linear brightness trends.
        val filtered = DoubleArray(n)
        for (i in 2 until n - 2) {
            filtered[i] = (
                frameLumas[i - 2] +
                    3.0 * frameLumas[i - 1] +
                    4.0 * frameLumas[i] +
                    3.0 * frameLumas[i + 1] +
                    frameLumas[i + 2]
                ) / 12.0
        }

        if (n >= 6) {
            val leftSlope = if (n >= 7) filtered[3] - filtered[2] else 0.0
            applyBoundaryFlickerRemoval(
                raw = frameLumas,
                filtered = filtered,
                windowStart = 0,
                targetIndices = intArrayOf(0, 1),
                slopePerFrame = leftSlope,
            )

            val rightSlope = if (n >= 7) filtered[n - 3] - filtered[n - 4] else 0.0
            applyBoundaryFlickerRemoval(
                raw = frameLumas,
                filtered = filtered,
                windowStart = n - 6,
                targetIndices = intArrayOf(n - 2, n - 1),
                slopePerFrame = rightSlope,
            )
        } else {
            // n == 4 or n == 5: remove 2-frame oscillation smoothly at boundaries.
            filtered[0] = 0.5 * (frameLumas[0] + frameLumas[1])
            filtered[1] = 0.25 * frameLumas[0] + 0.5 * frameLumas[1] + 0.25 * frameLumas[2]
            if (n == 4) {
                filtered[2] = 0.25 * frameLumas[1] + 0.5 * frameLumas[2] + 0.25 * frameLumas[3]
            }
            filtered[n - 2] = 0.25 * frameLumas[n - 3] + 0.5 * frameLumas[n - 2] + 0.25 * frameLumas[n - 1]
            filtered[n - 1] = 0.5 * (frameLumas[n - 2] + frameLumas[n - 1])
        }

        val currentMean = filtered.average()
        if (abs(currentMean) > 1e-9) {
            val scale = targetMeanLuma / currentMean
            for (i in 0 until n) {
                filtered[i] *= scale
            }
        } else {
            val shift = targetMeanLuma - currentMean
            for (i in 0 until n) {
                filtered[i] += shift
            }
        }
        return filtered
    }

    /**
     * Runs an independent-axis 2D constant-velocity Kalman filter `[pos, vel]` with 2x2 covariance
     * `[p00, p01, p10, p11]` and intra-segment RTS smoothing over time-sorted observations,
     * then detects slide-to-roll transitions, cushion bounces, and ball stops.
     */
    fun trackBallTrajectory(
        observations: List<VideoFrameBallObservation>,
        table: TableSpec,
        processAccelNoiseStdMps2: Double = 0.85,
        measurementPositionNoiseStdMeters: Double = 0.0018,
    ): TrackedBallTrajectory {
        if (observations.isEmpty()) {
            return TrackedBallTrajectory(
                points = emptyList(),
                events = emptyList(),
                normalizedLumaSamples = DoubleArray(0),
            )
        }

        val sorted = observations.sortedWith(
            compareBy<VideoFrameBallObservation> { it.timestampSeconds }.thenBy { it.frameIndex },
        )
        val normalizedLumas = normalizeFrameBrightness(
            DoubleArray(sorted.size) { sorted[it].frameMeanLuma },
        )

        val n = sorted.size
        if (n == 1) {
            val obs = sorted[0]
            return TrackedBallTrajectory(
                points = listOf(
                    FilteredTrackPoint(
                        frameIndex = obs.frameIndex,
                        timestampSeconds = obs.timestampSeconds,
                        positionMeters = obs.rawPositionMeters,
                        velocityMps = Vec2.ZERO,
                        speedMps = 0.0,
                    ),
                ),
                events = emptyList(),
                normalizedLumaSamples = normalizedLumas,
            )
        }

        val r = (measurementPositionNoiseStdMeters * measurementPositionNoiseStdMeters).coerceAtLeast(1e-12)
        val q = (processAccelNoiseStdMps2 * processAccelNoiseStdMps2).coerceAtLeast(1e-9)

        val xRun = AxisFilterHistory(n)
        val yRun = AxisFilterHistory(n)
        val dtStep = DoubleArray(n)
        val discontinuity = BooleanArray(n)

        val initIdx = min(2, n - 1)
        val initDt = sorted[initIdx].timestampSeconds - sorted[0].timestampSeconds
        val initVx = if (initDt > 1e-6) {
            (sorted[initIdx].rawPositionMeters.x - sorted[0].rawPositionMeters.x) / initDt
        } else {
            0.0
        }
        val initVy = if (initDt > 1e-6) {
            (sorted[initIdx].rawPositionMeters.y - sorted[0].rawPositionMeters.y) / initDt
        } else {
            0.0
        }

        xRun.initAtZero(sorted[0].rawPositionMeters.x, initVx, r, initVelVar = 2.0)
        yRun.initAtZero(sorted[0].rawPositionMeters.y, initVy, r, initVelVar = 2.0)

        for (k in 1 until n) {
            val dt = sorted[k].timestampSeconds - sorted[k - 1].timestampSeconds
            dtStep[k] = dt

            val jumpX = xRun.predictAndCheckManeuver(k, dt, sorted[k].rawPositionMeters.x, q, r)
            val jumpY = yRun.predictAndCheckManeuver(k, dt, sorted[k].rawPositionMeters.y, q, r)
            if (jumpX || jumpY) {
                discontinuity[k] = true
                // Start a fresh velocity estimate at the post-bounce segment so the backward
                // RTS pass refines the post-impact velocity without smearing across the bounce.
                val nextDt = if (k + 1 < n) sorted[k + 1].timestampSeconds - sorted[k].timestampSeconds else 0.0
                val segVx = if (nextDt > 1e-6) {
                    (sorted[k + 1].rawPositionMeters.x - sorted[k].rawPositionMeters.x) / nextDt
                } else if (dt > 1e-6) {
                    (sorted[k].rawPositionMeters.x - xRun.posFilt[k - 1]) / dt
                } else {
                    xRun.velFilt[k - 1]
                }
                val segVy = if (nextDt > 1e-6) {
                    (sorted[k + 1].rawPositionMeters.y - sorted[k].rawPositionMeters.y) / nextDt
                } else if (dt > 1e-6) {
                    (sorted[k].rawPositionMeters.y - yRun.posFilt[k - 1]) / dt
                } else {
                    yRun.velFilt[k - 1]
                }
                xRun.resetForSegmentStart(k, sorted[k].rawPositionMeters.x, segVx, r, segVelVar = 4.0)
                yRun.resetForSegmentStart(k, sorted[k].rawPositionMeters.y, segVy, r, segVelVar = 4.0)
            } else {
                xRun.update(k, sorted[k].rawPositionMeters.x, r)
                yRun.update(k, sorted[k].rawPositionMeters.y, r)
            }
        }

        val xSmooth = xRun.smoothBackward(dtStep, discontinuity)
        val ySmooth = yRun.smoothBackward(dtStep, discontinuity)

        val points = ArrayList<FilteredTrackPoint>(n)
        for (k in 0 until n) {
            val obs = sorted[k]
            val pos = Vec2(xSmooth.pos[k], ySmooth.pos[k])
            val vel = Vec2(xSmooth.vel[k], ySmooth.vel[k])
            points += FilteredTrackPoint(
                frameIndex = obs.frameIndex,
                timestampSeconds = obs.timestampSeconds,
                positionMeters = pos,
                velocityMps = vel,
                speedMps = vel.length(),
            )
        }

        val events = detectEvents(points, table)
        return TrackedBallTrajectory(
            points = points,
            events = events,
            normalizedLumaSamples = normalizedLumas,
        )
    }

    /**
     * Removes exact 2-frame and 3-frame harmonic components on a 6-frame boundary window
     * `[windowStart .. windowStart + 5]` where the 4 basis functions
     * `{1, (-1)^j, cos(2*pi*j/3), sin(2*pi*j/3)}` are mutually orthogonal.
     */
    private fun applyBoundaryFlickerRemoval(
        raw: DoubleArray,
        filtered: DoubleArray,
        windowStart: Int,
        targetIndices: IntArray,
        slopePerFrame: Double,
    ) {
        var c2 = 0.0
        var c3Cos = 0.0
        var c3Sin = 0.0
        for (j in 0 until 6) {
            val idx = windowStart + j
            val detrended = raw[idx] - slopePerFrame * j
            val alt = if (j % 2 == 0) 1.0 else -1.0
            val phase = TWO_PI * j / 3.0
            c2 += detrended * alt
            c3Cos += detrended * cos(phase)
            c3Sin += detrended * sin(phase)
        }
        c2 /= 6.0
        c3Cos /= 3.0
        c3Sin /= 3.0

        for (idx in targetIndices) {
            val j = idx - windowStart
            val alt = if (j % 2 == 0) 1.0 else -1.0
            val phase = TWO_PI * j / 3.0
            val flicker = c2 * alt + c3Cos * cos(phase) + c3Sin * sin(phase)
            filtered[idx] = raw[idx] - flicker
        }
    }

    private fun detectEvents(
        points: List<FilteredTrackPoint>,
        table: TableSpec,
    ): List<TrackedTrajectoryEvent> {
        if (points.size < 2) return emptyList()

        val bounces = detectCushionBounces(points, table)
        val slideToRoll = detectSlideToRollTransition(points, bounces)
        val ballStop = detectBallStop(points)

        val allEvents = ArrayList<TrackedTrajectoryEvent>(bounces.size + 2)
        if (slideToRoll != null) allEvents += slideToRoll
        allEvents += bounces
        if (ballStop != null) allEvents += ballStop
        return allEvents.sortedBy { it.timestampSeconds }
    }

    private fun detectCushionBounces(
        points: List<FilteredTrackPoint>,
        table: TableSpec,
    ): List<TrackedTrajectoryEvent> {
        val margin = 2.5 * table.ballRadiusMeters
        val last = points.lastIndex
        val candidates = ArrayList<Int>()

        for (i in 1..last) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[(i + 1).coerceAtMost(last)]
            val preAnchor = points[(i - 2).coerceAtLeast(0)]

            val nearHead = isNearX(prev.positionMeters.x, curr.positionMeters.x, next.positionMeters.x, 0.0, margin)
            val nearFoot = isNearX(prev.positionMeters.x, curr.positionMeters.x, next.positionMeters.x, table.lengthMeters, margin)
            val nearLeft = isNearY(prev.positionMeters.y, curr.positionMeters.y, next.positionMeters.y, 0.0, margin)
            val nearRight = isNearY(prev.positionMeters.y, curr.positionMeters.y, next.positionMeters.y, table.widthMeters, margin)
            if (!nearHead && !nearFoot && !nearLeft && !nearRight) continue

            val oneStepSharp = isBounceTransition(prev.velocityMps, curr.velocityMps, nearHead, nearFoot, nearLeft, nearRight)
            val twoStepSharp = isBounceTransition(preAnchor.velocityMps, next.velocityMps, nearHead, nearFoot, nearLeft, nearRight)
            if (oneStepSharp || twoStepSharp) {
                candidates += i
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val events = ArrayList<TrackedTrajectoryEvent>()
        var clusterStartPos = 0
        while (clusterStartPos < candidates.size) {
            var clusterEndPos = clusterStartPos
            while (
                clusterEndPos + 1 < candidates.size &&
                points[candidates[clusterEndPos + 1]].timestampSeconds - points[candidates[clusterEndPos]].timestampSeconds <= BOUNCE_COOLDOWN_SECONDS
            ) {
                clusterEndPos++
            }

            val firstIdx = candidates[clusterStartPos]
            val lastIdx = candidates[clusterEndPos]
            val beforeIdx = (firstIdx - 2).coerceAtLeast(0)
            val afterIdx = (lastIdx + 1).coerceAtMost(last)

            var apexIdx = firstIdx
            var bestRailDist = Double.POSITIVE_INFINITY
            val searchStart = (firstIdx - 1).coerceAtLeast(0)
            val searchEnd = lastIdx.coerceAtMost(last)
            for (idx in searchStart..searchEnd) {
                val dist = minDistanceToAnyRail(points[idx].positionMeters, table)
                if (dist < bestRailDist) {
                    bestRailDist = dist
                    apexIdx = idx
                }
            }

            val apex = points[apexIdx]
            events += TrackedTrajectoryEvent(
                type = TrajectoryEventType.CUSHION_BOUNCE,
                timestampSeconds = apex.timestampSeconds,
                positionMeters = apex.positionMeters,
                speedBeforeMps = points[beforeIdx].speedMps,
                speedAfterMps = points[afterIdx].speedMps,
            )

            clusterStartPos = clusterEndPos + 1
        }
        return events
    }

    private fun isBounceTransition(
        vBefore: Vec2,
        vAfter: Vec2,
        nearHead: Boolean,
        nearFoot: Boolean,
        nearLeft: Boolean,
        nearRight: Boolean,
    ): Boolean {
        if (vBefore.length() <= 0.08 || vAfter.length() <= 0.08) return false
        if (abs(vBefore.angleTo(vAfter)) > BOUNCE_ANGLE_THRESHOLD_RAD) return true
        val headBounce = nearHead && vBefore.x < -0.05 && vAfter.x > 0.05
        val footBounce = nearFoot && vBefore.x > 0.05 && vAfter.x < -0.05
        val leftBounce = nearLeft && vBefore.y < -0.05 && vAfter.y > 0.05
        val rightBounce = nearRight && vBefore.y > 0.05 && vAfter.y < -0.05
        return headBounce || footBounce || leftBounce || rightBounce
    }

    private fun detectSlideToRollTransition(
        points: List<FilteredTrackPoint>,
        bounces: List<TrackedTrajectoryEvent>,
    ): TrackedTrajectoryEvent? {
        val n = points.size
        if (n < 6) return null

        val windowDuration = 0.09
        var bestIdx = -1
        var bestContrast = Double.NEGATIVE_INFINITY
        var firstCrossingIdx = -1

        for (i in 2 until n - 2) {
            val tCenter = points[i].timestampSeconds
            if (points[i].speedMps <= 0.08) continue

            var preStart = i
            while (preStart > 0 && tCenter - points[preStart].timestampSeconds < windowDuration) {
                preStart--
            }
            var postEnd = i
            while (postEnd < n - 1 && points[postEnd].timestampSeconds - tCenter < windowDuration) {
                postEnd++
            }
            if (i - preStart < 2 || postEnd - i < 2) continue

            val tPre = points[preStart].timestampSeconds
            val tPost = points[postEnd].timestampSeconds
            if (bounces.any { it.timestampSeconds in (tPre - 0.02)..(tPost + 0.02) }) continue

            val dirPre = points[preStart].velocityMps.normalized()
            val dirPost = points[postEnd].velocityMps.normalized()
            if (dirPre.lengthSquared() < 1e-12 || dirPost.lengthSquared() < 1e-12) continue
            if (dirPre.dot(dirPost) <= SAME_DIR_DOT_THRESHOLD) continue

            val preDecel = estimateSpeedDeceleration(points, preStart, i)
            val postDecel = estimateSpeedDeceleration(points, i, postEnd)

            if (preDecel > SLIDE_DECEL_THRESHOLD_MPS2 && postDecel < ROLL_DECEL_THRESHOLD_MPS2) {
                if (firstCrossingIdx < 0) {
                    firstCrossingIdx = i
                }
                if (points[i].timestampSeconds - points[firstCrossingIdx].timestampSeconds <= 0.12) {
                    val contrast = preDecel - postDecel
                    if (contrast > bestContrast) {
                        bestContrast = contrast
                        bestIdx = i
                    }
                }
            } else if (firstCrossingIdx >= 0 && points[i].timestampSeconds - points[firstCrossingIdx].timestampSeconds > 0.12) {
                break
            }
        }

        if (bestIdx < 0) return null
        val transitionPoint = points[bestIdx]
        val speedBefore = points[(bestIdx - 1).coerceAtLeast(0)].speedMps
        val speedAfter = points[(bestIdx + 1).coerceAtMost(n - 1)].speedMps
        return TrackedTrajectoryEvent(
            type = TrajectoryEventType.SLIDE_TO_ROLL_TRANSITION,
            timestampSeconds = transitionPoint.timestampSeconds,
            positionMeters = transitionPoint.positionMeters,
            speedBeforeMps = speedBefore,
            speedAfterMps = speedAfter,
        )
    }

    private fun estimateSpeedDeceleration(
        points: List<FilteredTrackPoint>,
        startIdx: Int,
        endIdx: Int,
    ): Double {
        val count = endIdx - startIdx + 1
        if (count < 2) return 0.0

        var sumT = 0.0
        var sumV = 0.0
        for (k in startIdx..endIdx) {
            sumT += points[k].timestampSeconds
            sumV += points[k].speedMps
        }
        val meanT = sumT / count
        val meanV = sumV / count

        var num = 0.0
        var den = 0.0
        for (k in startIdx..endIdx) {
            val dt = points[k].timestampSeconds - meanT
            num += dt * (points[k].speedMps - meanV)
            den += dt * dt
        }
        if (den <= 1e-12) return 0.0
        return -(num / den)
    }

    private fun detectBallStop(points: List<FilteredTrackPoint>): TrackedTrajectoryEvent? {
        var hasMoved = false
        for (i in points.indices) {
            val p = points[i]
            if (p.speedMps > MOTION_START_SPEED_MPS) {
                hasMoved = true
            }
            if (hasMoved && p.speedMps <= STOP_SPEED_THRESHOLD_MPS) {
                // Skip a transient speed dip caused by a head-on cushion reversal.
                val resumesFast = (i + 1 <= points.lastIndex && points[i + 1].speedMps > 0.15) ||
                    (i + 2 <= points.lastIndex && points[i + 2].speedMps > 0.15)
                if (!resumesFast) {
                    val beforeSpeed = if (i > 0) points[i - 1].speedMps else p.speedMps
                    return TrackedTrajectoryEvent(
                        type = TrajectoryEventType.BALL_STOP,
                        timestampSeconds = p.timestampSeconds,
                        positionMeters = p.positionMeters,
                        speedBeforeMps = beforeSpeed,
                        speedAfterMps = p.speedMps,
                    )
                }
            }
        }
        return null
    }

    private fun isNearX(x0: Double, x1: Double, x2: Double, targetX: Double, margin: Double): Boolean {
        return abs(x0 - targetX) <= margin || abs(x1 - targetX) <= margin || abs(x2 - targetX) <= margin
    }

    private fun isNearY(y0: Double, y1: Double, y2: Double, targetY: Double, margin: Double): Boolean {
        return abs(y0 - targetY) <= margin || abs(y1 - targetY) <= margin || abs(y2 - targetY) <= margin
    }

    private fun minDistanceToAnyRail(pos: Vec2, table: TableSpec): Double {
        val dHead = abs(pos.x)
        val dFoot = abs(table.lengthMeters - pos.x)
        val dLeft = abs(pos.y)
        val dRight = abs(table.widthMeters - pos.y)
        return min(min(dHead, dFoot), min(dLeft, dRight))
    }

    private data class SmoothedAxis(
        val pos: DoubleArray,
        val vel: DoubleArray,
    )

    private class AxisFilterHistory(size: Int) {
        val posFilt = DoubleArray(size)
        val velFilt = DoubleArray(size)
        private val p00Filt = DoubleArray(size)
        private val p01Filt = DoubleArray(size)
        private val p10Filt = DoubleArray(size)
        private val p11Filt = DoubleArray(size)

        private val posPred = DoubleArray(size)
        private val velPred = DoubleArray(size)
        private val p00Pred = DoubleArray(size)
        private val p01Pred = DoubleArray(size)
        private val p10Pred = DoubleArray(size)
        private val p11Pred = DoubleArray(size)

        fun initAtZero(pos0: Double, vel0: Double, r: Double, initVelVar: Double) {
            posFilt[0] = pos0
            velFilt[0] = vel0
            p00Filt[0] = r
            p01Filt[0] = 0.0
            p10Filt[0] = 0.0
            p11Filt[0] = initVelVar
        }

        fun predictAndCheckManeuver(
            k: Int,
            dt: Double,
            measurement: Double,
            q: Double,
            r: Double,
        ): Boolean {
            if (dt <= 1e-6) {
                posPred[k] = posFilt[k - 1]
                velPred[k] = velFilt[k - 1]
                p00Pred[k] = p00Filt[k - 1]
                p01Pred[k] = p01Filt[k - 1]
                p10Pred[k] = p10Filt[k - 1]
                p11Pred[k] = p11Filt[k - 1]
                return false
            }

            val dt2 = dt * dt
            val dt3 = dt2 * dt
            val dt4 = dt2 * dt2

            val pPred = posFilt[k - 1] + velFilt[k - 1] * dt
            val vPred = velFilt[k - 1]

            val c00 = p00Filt[k - 1] + dt * (p01Filt[k - 1] + p10Filt[k - 1]) + dt2 * p11Filt[k - 1] + 0.25 * q * dt4
            val c01 = p01Filt[k - 1] + dt * p11Filt[k - 1] + 0.5 * q * dt3
            val c10 = p10Filt[k - 1] + dt * p11Filt[k - 1] + 0.5 * q * dt3
            val c11 = p11Filt[k - 1] + q * dt2

            posPred[k] = pPred
            velPred[k] = vPred
            p00Pred[k] = c00
            p01Pred[k] = c01
            p10Pred[k] = c10
            p11Pred[k] = c11

            val innov = measurement - pPred
            val s = (c00 + r).coerceAtLeast(1e-12)
            return innov * innov > MANEUVER_INNOVATION_SIGMA_SQ * s
        }

        fun resetForSegmentStart(
            k: Int,
            pos: Double,
            vel: Double,
            r: Double,
            segVelVar: Double,
        ) {
            posFilt[k] = pos
            velFilt[k] = vel
            p00Filt[k] = r
            p01Filt[k] = 0.0
            p10Filt[k] = 0.0
            p11Filt[k] = segVelVar
        }

        fun update(k: Int, measurement: Double, r: Double) {
            val s = (p00Pred[k] + r).coerceAtLeast(1e-12)
            val k0 = p00Pred[k] / s
            val k1 = p10Pred[k] / s
            val innov = measurement - posPred[k]

            posFilt[k] = posPred[k] + k0 * innov
            velFilt[k] = velPred[k] + k1 * innov

            val newP00 = ((1.0 - k0) * p00Pred[k]).coerceAtLeast(1e-12)
            val newP01 = (1.0 - k0) * p01Pred[k]
            val newP11 = (p11Pred[k] - k1 * p01Pred[k]).coerceAtLeast(1e-12)

            p00Filt[k] = newP00
            p01Filt[k] = newP01
            p10Filt[k] = newP01
            p11Filt[k] = newP11
        }

        fun smoothBackward(dtStep: DoubleArray, discontinuity: BooleanArray): SmoothedAxis {
            val n = posFilt.size
            val posOut = DoubleArray(n)
            val velOut = DoubleArray(n)
            posOut[n - 1] = posFilt[n - 1]
            velOut[n - 1] = velFilt[n - 1]

            for (k in n - 2 downTo 0) {
                val dt = dtStep[k + 1]
                if (discontinuity[k + 1] || dt <= 1e-6) {
                    posOut[k] = posFilt[k]
                    velOut[k] = velFilt[k]
                    continue
                }

                val a = p00Pred[k + 1]
                val b = p01Pred[k + 1]
                val d = p11Pred[k + 1]
                val det = a * d - b * b
                if (det <= 1e-18) {
                    posOut[k] = posFilt[k]
                    velOut[k] = velFilt[k]
                    continue
                }

                val m00 = p00Filt[k] + dt * p01Filt[k]
                val m01 = p01Filt[k]
                val m10 = p10Filt[k] + dt * p11Filt[k]
                val m11 = p11Filt[k]

                val invDet = 1.0 / det
                val c00 = (m00 * d - m01 * b) * invDet
                val c01 = (-m00 * b + m01 * a) * invDet
                val c10 = (m10 * d - m11 * b) * invDet
                val c11 = (-m10 * b + m11 * a) * invDet

                val dx = posOut[k + 1] - posPred[k + 1]
                val dv = velOut[k + 1] - velPred[k + 1]
                posOut[k] = posFilt[k] + c00 * dx + c01 * dv
                velOut[k] = velFilt[k] + c10 * dx + c11 * dv
            }

            return SmoothedAxis(posOut, velOut)
        }
    }
}
