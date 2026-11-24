package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.TableSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Category of a tappable physics waypoint along a planned cue-ball path.
 */
enum class PathWaypointKind {
    /** The tip strike; carries the stroke speed and the spin the tip gives. */
    CUE_START,

    /** A change of vertical-spin regime before the object ball is reached (see [PathWaypoint.transition]). */
    SPIN_TRANSITION,

    /** Cue ball meets the object ball. */
    OBJECT_CONTACT,

    /** The object ball runs out of speed before reaching the pocket. */
    OBJECT_BALL_REST,

    /** The slide that follows the object-ball contact ends and the cue ball rolls. */
    POST_IMPACT_TRANSITION,

    /** The cue ball rebounds off a cushion. */
    CUSHION_CONTACT,

    /** The cue ball strikes a ball other than the target. */
    BALL_CONTACT,

    /** The cue ball runs out of speed before it reaches the object ball. */
    CUE_STOPS_SHORT,

    /** The cue ball comes to rest after the shot. */
    CUE_REST,

    /** The cue ball drops into a pocket. */
    SCRATCH,
}

/**
 * Tappable point on a planned shot (SI units) describing what the cue ball, or for [PathWaypointKind.OBJECT_BALL_REST]
 * the object ball, is doing there. The engine only carries numbers and enums; the UI chooses the wording and units.
 *
 * Unless stated otherwise a field is `null` when it does not apply to the [kind].
 *
 * - [distanceAlongPathMeters]: distance the cue ball has travelled from the tip strike, along its drawn path.
 *   Non-decreasing through a list from [ShotPathWaypoints.build]. The object-ball rest point uses the distance
 *   of the contact it follows.
 * - [speedInMps] / [speedOutMps]: cue-ball speed just before and just after the point. For points where nothing
 *   happens to the speed (spin transitions) both hold the speed at the point.
 * - [verticalSpinBefore] / [verticalSpinAfter]: vertical spin factor `k = R*omega/v` just before and after
 *   (-1 full draw, 0 stun, +1 natural roll; see [SpinPhysics.initialSpinFactorForTip]). At a cushion these are the
 *   simulated values (a ball usually leaves a cushion sliding); for [PathWaypointKind.POST_IMPACT_TRANSITION] the
 *   pair spans the whole slide (spin at contact, then rolling).
 * - [sideSpinBefore] / [sideSpinAfter]: signed english, as a tip side offset (fraction of the ball radius, negative
 *   left), just before and after a cushion or ball contact; see [CueBallPathEvent.sideSpinBefore].
 *   [PathWaypointKind.CUE_START] carries the strike value in [sideSpinAfter].
 * - [regimeBefore] / [regimeAfter]: [SpinPhysics.ImpactSpinState] on either side of a spin transition; at
 *   [PathWaypointKind.OBJECT_CONTACT] [regimeBefore] is the state on arrival.
 * - [objectBallSpeedMps]: speed given to the object ball at contact.
 * - [cueDeflectionRadians]: angle between the cue ball's path after contact and the aim line (absolute value).
 * - [cutAngleRadians]: absolute cut angle at contact.
 * - [shortfallMeters]: for [PathWaypointKind.CUE_STOPS_SHORT] how far the cue ball stops from contact; for
 *   [PathWaypointKind.OBJECT_BALL_REST] how far the object ball stops from the pocket.
 * - [travelMeters]: for rest points the distance the ball rolled after the strike (stops short) or after contact.
 * - [cushionCount]: cushions touched before a rest point.
 * - [speedUnits]: stroke speed on the player's 1..10 scale, at [PathWaypointKind.CUE_START].
 */
data class PathWaypoint(
    val kind: PathWaypointKind,
    val position: Vec2,
    val distanceAlongPathMeters: Double,
    val speedInMps: Double? = null,
    val speedOutMps: Double? = null,
    val verticalSpinBefore: Double? = null,
    val verticalSpinAfter: Double? = null,
    val sideSpinBefore: Double? = null,
    val sideSpinAfter: Double? = null,
    val regimeBefore: SpinPhysics.ImpactSpinState? = null,
    val regimeAfter: SpinPhysics.ImpactSpinState? = null,
    val transition: SpinPhysics.SpinRegimeChange? = null,
    val otherBallNumber: Int? = null,
    val cushionIndex: Int? = null,
    val objectBallSpeedMps: Double? = null,
    val cueDeflectionRadians: Double? = null,
    val cutAngleRadians: Double? = null,
    val shortfallMeters: Double? = null,
    val travelMeters: Double? = null,
    val cushionCount: Int? = null,
    val speedUnits: Double? = null,
)

/**
 * Builds ordered, tappable physics waypoints for a single shot from the same models that draw the shot:
 * [SpinPhysics.preImpactRegimeBoundaries] and [SpinPhysics.computePreImpactCueState] before contact,
 * [SpinPhysics.computePostImpactCueState] at contact, and the [PostShotTableSimulation] path and events afterwards.
 */
object ShotPathWaypoints {
    private const val MIN_TRANSITION_GAP_METERS = 0.03
    private const val VERTEX_MATCH_TOLERANCE_METERS = 2e-3

    /**
     * Waypoints in path order: the strike first; then any spin transitions, the object-ball contact (or the point
     * where the cue ball stops short of it), the object ball's rest point when it misses the pocket, the post-contact
     * slide end, cushion and ball contacts, and finally the scratch or the rest of the cue ball.
     *
     * [simulation] may be `null`, in which case only the strike, the pre-contact transitions and the contact are
     * returned. [speedUnits] is the stroke speed on the player's scale, if known.
     */
    fun build(
        aim: AimGeometry,
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
        simulation: PostShotTableSimulation?,
        targetBallNumber: Int,
        speedUnits: Double? = null,
    ): List<PathWaypoint> {
        val out = ArrayList<PathWaypoint>()
        val pre = SpinPhysics.computePreImpactCueState(cueSpeedMps, tip, aim.cueToGhostDistanceMeters, table)
        out += PathWaypoint(
            kind = PathWaypointKind.CUE_START,
            position = aim.cueBallPos,
            distanceAlongPathMeters = 0.0,
            speedOutMps = cueSpeedMps,
            verticalSpinAfter = pre.initialSpinFactorK,
            sideSpinAfter = tip.clampedSideRatio,
            speedUnits = speedUnits,
        )

        val stoppedShort = pre.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT
        val travelLimit = if (stoppedShort) pre.travelledDistanceMeters else aim.cueToGhostDistanceMeters
        var lastDistance = 0.0
        for (b in SpinPhysics.preImpactRegimeBoundaries(cueSpeedMps, tip, table)) {
            // "Roll settles" is the same stretch as the slide ending a moment later; one marker is enough.
            if (b.change == SpinPhysics.SpinRegimeChange.ROLL_SETTLES) continue
            if (b.distanceMeters - lastDistance < MIN_TRANSITION_GAP_METERS) continue
            if (b.distanceMeters >= travelLimit - MIN_TRANSITION_GAP_METERS) continue
            lastDistance = b.distanceMeters
            out += PathWaypoint(
                kind = PathWaypointKind.SPIN_TRANSITION,
                position = aim.cueBallPos + aim.aimDirection * b.distanceMeters,
                distanceAlongPathMeters = b.distanceMeters,
                speedInMps = b.speedMps,
                speedOutMps = b.speedMps,
                verticalSpinBefore = b.spinFactorK,
                verticalSpinAfter = b.spinFactorK,
                regimeBefore = b.before,
                regimeAfter = b.after,
                transition = b.change,
            )
        }

        if (stoppedShort) {
            out += PathWaypoint(
                kind = PathWaypointKind.CUE_STOPS_SHORT,
                position = aim.cueBallPos + aim.aimDirection * pre.travelledDistanceMeters,
                distanceAlongPathMeters = pre.travelledDistanceMeters,
                speedInMps = 0.0,
                speedOutMps = 0.0,
                otherBallNumber = targetBallNumber,
                shortfallMeters = (aim.cueToGhostDistanceMeters - pre.travelledDistanceMeters).coerceAtLeast(0.0),
                travelMeters = pre.travelledDistanceMeters,
            )
            return out
        }

        val contactDistance = aim.cueToGhostDistanceMeters
        val post = SpinPhysics.computePostImpactCueState(aim, cueSpeedMps, tip, table)
        out += PathWaypoint(
            kind = PathWaypointKind.OBJECT_CONTACT,
            position = aim.ghostBallCenter,
            distanceAlongPathMeters = contactDistance,
            speedInMps = pre.impactSpeedMps,
            speedOutMps = post.launchSpeedMps,
            verticalSpinBefore = pre.impactSpinFactorK,
            verticalSpinAfter = post.impactSpinFactorK,
            regimeBefore = pre.state,
            otherBallNumber = targetBallNumber,
            objectBallSpeedMps = post.objectBallSpeedMps,
            cueDeflectionRadians = abs(post.initialDeflectionAngleRad),
            cutAngleRadians = abs(aim.cutAngleRadians),
        )

        // Distances along the cue ball's path are measured on the densely sampled path, which follows the curves.
        val cuePts = simulation?.cueBallTrajectory?.sampledPoints.orEmpty()
        if (simulation != null && !simulation.targetBallPotted) {
            val obPts = simulation.targetBallTrajectory?.sampledPoints.orEmpty()
            val obEnd = obPts.lastOrNull()
            if (obEnd != null && obEnd.distanceTo(aim.objectBallPos) > 0.01) {
                out += PathWaypoint(
                    kind = PathWaypointKind.OBJECT_BALL_REST,
                    position = obEnd,
                    distanceAlongPathMeters = contactDistance,
                    speedOutMps = 0.0,
                    otherBallNumber = targetBallNumber,
                    shortfallMeters = obEnd.distanceTo(aim.pocketAimPos),
                    travelMeters = polylineLength(obPts),
                )
            }
        }

        val events = simulation?.cueBallEvents.orEmpty()
        val cursor = PolylineCursor(cuePts)
        val totalAfterContact = polylineLength(cuePts)
        var scratchEmitted = false
        for ((index, ev) in events.withIndex()) {
            val afterContact = cursor.distanceAt(ev.position)
            val along = contactDistance + afterContact
            when (ev.kind) {
                // The slide that follows the contact: where the curve ends and the cue ball rolls straight on. A later
                // slide (after another ball contact) belongs to that contact and gets no marker of its own.
                CueBallEventKind.SLIDE_ENDS -> if (
                    index == 0 &&
                    afterContact > MIN_TRANSITION_GAP_METERS &&
                    afterContact < totalAfterContact - MIN_TRANSITION_GAP_METERS
                ) {
                    out += PathWaypoint(
                        kind = PathWaypointKind.POST_IMPACT_TRANSITION,
                        position = ev.position,
                        distanceAlongPathMeters = along,
                        speedInMps = ev.speedInMps,
                        speedOutMps = ev.speedOutMps,
                        verticalSpinBefore = post.impactSpinFactorK,
                        verticalSpinAfter = ev.verticalSpinAfter,
                        cutAngleRadians = abs(aim.cutAngleRadians),
                    )
                }

                CueBallEventKind.CUSHION -> out += PathWaypoint(
                    kind = PathWaypointKind.CUSHION_CONTACT,
                    position = ev.position,
                    distanceAlongPathMeters = along,
                    speedInMps = ev.speedInMps,
                    speedOutMps = ev.speedOutMps,
                    verticalSpinBefore = ev.verticalSpinBefore,
                    verticalSpinAfter = ev.verticalSpinAfter,
                    sideSpinBefore = ev.sideSpinBefore,
                    sideSpinAfter = ev.sideSpinAfter,
                    cushionIndex = ev.cushionIndex,
                )

                CueBallEventKind.BALL_CONTACT -> out += PathWaypoint(
                    kind = PathWaypointKind.BALL_CONTACT,
                    position = ev.position,
                    distanceAlongPathMeters = along,
                    speedInMps = ev.speedInMps,
                    speedOutMps = ev.speedOutMps,
                    verticalSpinBefore = ev.verticalSpinBefore,
                    verticalSpinAfter = ev.verticalSpinAfter,
                    sideSpinBefore = ev.sideSpinBefore,
                    sideSpinAfter = ev.sideSpinAfter,
                    otherBallNumber = ev.otherBallNumber,
                )

                CueBallEventKind.SCRATCH -> {
                    scratchEmitted = true
                    out += PathWaypoint(
                        kind = PathWaypointKind.SCRATCH,
                        position = ev.position,
                        distanceAlongPathMeters = along,
                        speedInMps = ev.speedInMps,
                        speedOutMps = 0.0,
                    )
                }
            }
        }

        val restPosition = cuePts.lastOrNull()
        val scratched = scratchEmitted || simulation?.cueBallTrajectory?.hitsScratchPocket != null
        if (restPosition != null) {
            if (scratched) {
                if (!scratchEmitted) {
                    out += PathWaypoint(
                        kind = PathWaypointKind.SCRATCH,
                        position = restPosition,
                        distanceAlongPathMeters = contactDistance + totalAfterContact,
                        speedOutMps = 0.0,
                    )
                }
            } else {
                out += PathWaypoint(
                    kind = PathWaypointKind.CUE_REST,
                    position = restPosition,
                    distanceAlongPathMeters = contactDistance + totalAfterContact,
                    speedInMps = 0.0,
                    speedOutMps = 0.0,
                    travelMeters = totalAfterContact,
                    cushionCount = events.count { it.kind == CueBallEventKind.CUSHION },
                )
            }
        }
        return out
    }

    private fun polylineLength(points: List<Vec2>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += points[i - 1].distanceTo(points[i])
        return total
    }

    /**
     * Maps points that lie on a polyline to the distance along it, scanning forward only so a path that doubles
     * back over itself (a ball returning along its own line) still yields increasing distances.
     */
    private class PolylineCursor(private val points: List<Vec2>) {
        private val cumulative = DoubleArray(points.size)
        private var index = 0

        init {
            for (i in 1 until points.size) {
                cumulative[i] = cumulative[i - 1] + points[i - 1].distanceTo(points[i])
            }
        }

        fun distanceAt(p: Vec2): Double {
            if (points.isEmpty()) return 0.0
            for (i in index until points.size) {
                if (points[i].distanceTo(p) <= VERTEX_MATCH_TOLERANCE_METERS) {
                    index = i
                    return cumulative[i]
                }
            }
            var bestGap = Double.MAX_VALUE
            var bestDistance = cumulative[index]
            var bestIndex = index
            for (i in index until points.size - 1) {
                val a = points[i]
                val ab = points[i + 1] - a
                val lengthSq = ab.lengthSquared()
                val t = if (lengthSq < 1e-12) 0.0 else min(1.0, max(0.0, (p - a).dot(ab) / lengthSq))
                val gap = (a + ab * t).distanceTo(p)
                if (gap < bestGap) {
                    bestGap = gap
                    bestIndex = i
                    bestDistance = cumulative[i] + sqrt(lengthSq) * t
                }
            }
            index = bestIndex
            return bestDistance
        }
    }
}
