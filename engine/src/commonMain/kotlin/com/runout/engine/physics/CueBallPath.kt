package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec

/**
 * Vertical-plane spin preset for post-impact cue-ball trajectory prediction. Each value names the spin state of the
 * cue ball at the moment it meets the object ball, not a tip height: the tip that produces that state depends on
 * the shot's speed and distance, see [SpinPhysics.tipForImpactState].
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
 * @property points Polyline starting at `ghostBallCenter` and ending at the stopping point or pocket, with a vertex at
 *   every cushion and ball contact in between (so `points.size - 2` counts the contacts). Between two vertices the
 *   ball curves while a slide with follow or draw lasts, so these segments are chords; draw [sampledPoints].
 * @property initialDeflectionAngleRad Signed angle in radians from `aimDirection` to the cue ball's velocity just
 *   after the contact (`0.0` if the cue ball stops at impact).
 * @property hitsScratchPocket Non-null if the trajectory enters a pocket's acceptance radius.
 * @property sampledPoints The path itself: [points] plus samples along every curve, close enough that each chord stays
 *   within 1 mm of the true path. This is the line to draw and to measure distances along.
 * @property rollingDeflectionAngleRad Signed angle from `aimDirection` to the line the cue ball rolls along once the
 *   slide after contact is over, on open cloth: the angle of the 90° and 30° rules.
 * @property cushionContacts Cushions the cue ball touched.
 * @property ballContacts Balls the cue ball touched after the object ball.
 */
data class TrajectoryPath(
    val spin: CueBallSpinPreset,
    val points: List<Vec2>,
    val initialDeflectionAngleRad: Double,
    val hitsScratchPocket: PocketId?,
    val sampledPoints: List<Vec2> = points,
    val rollingDeflectionAngleRad: Double = initialDeflectionAngleRad,
    val cushionContacts: Int = 0,
    val ballContacts: Int = 0,
)

/**
 * Cue-ball paths after contact for the three spin presets, on an otherwise empty table. They come from the same
 * contact law and ball motion as [TableCollisionSimulator]; see [SpinPhysics.computePostImpactCueState].
 */
object CueBallPath {
    /**
     * Paths after contact for a cue ball that arrives at [aim]'s ghost ball at [initialCueSpeedMps] with the spin each
     * preset names: no spin for [CueBallSpinPreset.STUN], natural roll for [CueBallSpinPreset.FOLLOW] and
     * [SpinPhysics.DRAW_PRESET_IMPACT_K] for [CueBallSpinPreset.DRAW]. The 90° rule (stun), the 30° rule
     * (natural roll) and the draw angles follow from the contact law and the slide that comes after it; the
     * restitution term keeps the stun line a few degrees inside the tangent line.
     *
     * To plan from a stroke speed instead, where the speed and spin at the object ball depend on the distance, use
     * [computePostImpactPathsForStroke].
     */
    fun computePostImpactPaths(
        aim: AimGeometry,
        table: TableSpec,
        initialCueSpeedMps: Double = 2.0,
    ): Map<CueBallSpinPreset, TrajectoryPath> = CueBallSpinPreset.entries.associateWith { preset ->
        val k = when (preset) {
            CueBallSpinPreset.STUN -> 0.0
            CueBallSpinPreset.FOLLOW -> 1.0
            CueBallSpinPreset.DRAW -> SpinPhysics.DRAW_PRESET_IMPACT_K
        }
        SpinPhysics.tracePathFromContact(
            aim = aim,
            impactSpeedMps = initialCueSpeedMps,
            impactSpinFactorK = k,
            sideSpinSurfaceSpeedMps = 0.0,
            spin = preset,
            table = table,
            ballRadiusMeters = table.ballRadiusMeters,
        )
    }

    /**
     * Paths after contact when the cue ball is struck at [cueSpeedMps] along [aim]: for each preset the tip that gives
     * that spin state at the object ball ([SpinPhysics.tipForImpactState], or [TipOffset.fromPreset] when no tip
     * does), the speed and spin the ball then has at contact ([SpinPhysics.computePreImpactCueState]), and the path
     * from there. A cue ball that stops short ends where it stops.
     */
    fun computePostImpactPathsForStroke(
        aim: AimGeometry,
        table: TableSpec,
        cueSpeedMps: Double,
    ): Map<CueBallSpinPreset, TrajectoryPath> = CueBallSpinPreset.entries.associateWith { preset ->
        val tip = SpinPhysics.tipForImpactState(
            preset = preset,
            cueSpeedMps = cueSpeedMps,
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = table,
        ) ?: TipOffset.fromPreset(preset)
        SpinPhysics.traceCueBallPathWithSpin(
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            table = table,
            ballRadiusMeters = table.ballRadiusMeters,
            maxBounces = Int.MAX_VALUE,
        ).copy(spin = preset)
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
