package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.BallBallContact
import com.runout.engine.physics.BallMotion
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What kind of shot a [FallbackAdvice] recommends. */
enum class FallbackKind(val label: String) {
    /** The legal ball is played into a second ball, which goes into a pocket. */
    COMBINATION("Combination"),

    /** The cue ball goes off one cushion to make a legal hit on a ball it cannot reach directly. */
    KICK("Kick"),

    /** The legal ball is hit but not potted, and the cue ball is left where the opponent has no direct pot. */
    SAFETY("Safety"),
}

/** Which ball a [FallbackLine] belongs to. */
enum class FallbackLineRole {
    /** The cue ball's path. */
    CUE_BALL,

    /** The legal ball's path after the cue ball meets it. */
    FIRST_OBJECT_BALL,

    /** A combination's second ball, from its position to the pocket. */
    SECOND_OBJECT_BALL,
}

/** One straight piece of a [FallbackAdvice] drawing, in table metres. */
data class FallbackLine(val from: Vec2, val to: Vec2, val role: FallbackLineRole)

/**
 * A single recommendation for a table where the legal ball has no direct pot.
 *
 * @property kind What kind of shot it is.
 * @property targetBallNumbers The ball the cue ball must hit first, then, for a combination, the ball it drives into
 * the pocket.
 * @property pocketId The pocket of a combination; `null` for a kick or a safety.
 * @property lines What to draw: the cue ball's path to contact (via the cushion point for a kick) and the object
 * balls' paths.
 * @property cushionPoint Where a kick meets the cushion (the cue ball's centre at contact); `null` otherwise.
 * @property speedUnits Stroke speed on the player's 20-point scale.
 * @property tip Where the tip meets the cue ball.
 * @property outcomeProbability Probability of the outcome [reason] states: the pot for a combination, a legal hit for
 * a kick, a leave with no direct pot for the opponent for a safety.
 * @property reason One plain-English sentence.
 * @property cueBallRest Where the cue ball comes to rest on a safety (nominal stroke); `null` otherwise.
 * @property resultingLayout The table a safety leaves (nominal stroke); `null` otherwise.
 */
data class FallbackAdvice(
    val kind: FallbackKind,
    val targetBallNumbers: List<Int>,
    val pocketId: PocketId?,
    val lines: List<FallbackLine>,
    val cushionPoint: Vec2?,
    val speedUnits: Double,
    val tip: TipOffset,
    val outcomeProbability: Double,
    val reason: String,
    val cueBallRest: Vec2? = null,
    val resultingLayout: TableLayout? = null,
)

/**
 * Advice for a table where the legal ball cannot be potted directly: one of a two-ball combination, a one-cushion
 * kick to make a legal hit, or a basic safety. It is a first step: one recommendation, chosen by a fixed rule, with no
 * model of the opponent beyond "has a direct pot or not" and no search over several shots.
 *
 * **Selection.** When the run-out planner has a direct pot whose make probability is at least [DIRECT_POT_FLOOR]
 * ([MultiShotPlanner.planRunout] returns a plan), there is no advice. Otherwise a combination whose make probability is
 * at least [COMBINATION_MAKE_THRESHOLD] is played; else, if the cue ball can hit the legal ball directly, the best
 * safety, or, when no safety is found, the best kick; else the best kick. A weaker combination is offered only when
 * neither a safety nor a kick is found. When a direct pot exists but is below the floor, the advice says so in its
 * reason.
 *
 * **Combination.** The legal ball `A` is sent at the ghost position of ball `B` for the pocket ([PocketGeometry.aimPoint]);
 * all three legs must be clear. Throw at both contacts is allowed for in the aim with
 * [BallBallContact.lineOfCentresForThrownDirection], the ball speeds come from [BallBallContact.resolve] and the cloth
 * ([BallMotion]). The cue-line error is propagated through both contacts with the geometric ghost-ball mapping (to
 * first order the second ball's direction turns by the cue-line error times `d1 / (2R cos(cut1))` times
 * `d2 / (2R cos(cut2))`, `d1` cue ball to the first contact and `d2` the first ball's travel to the second). The
 * throw-compensated cue line the player is told to aim is, by construction, the one that sends the second ball along
 * the intended line; the mapping is therefore centred on the cue line whose pure geometry gives that direction (the
 * line of the uncompensated ghost ball), so that no error reproduces the intended direction. The throw is treated as
 * a constant rotation over the error range (its change with the error is neglected), which is exact to first order.
 * The probability is the normal mass of cue-line angles whose second ball lies inside the pocket's entry window at its
 * arrival-speed jaw tolerance, averaged over the player's speed error.
 *
 * **Kick.** For each cushion, cue-ball directions around the mirror-image line are traced with the table simulation
 * (centre ball, [TableCollisionSimulator] with the real cushion rebound); a direction counts when the cue ball meets
 * the legal ball after exactly one cushion, touching no other ball first and not dropping in a pocket, and then
 * reaches a cushion again (the same "a ball reaches a cushion after the contact" rule as a safety; the legal ball's
 * own path is not traced, so only the cue ball can satisfy it). The chance of a legal hit is the normal mass of the
 * cue-line error inside the run of good directions around the aim, at each of three speeds of the player's speed
 * error.
 *
 * **Safety.** A small stroke set (seven lines of contact, three speeds, centre ball or draw) is simulated on the whole
 * table. A stroke is a candidate when the cue ball hits the legal ball first, nothing drops, and a ball reaches a
 * cushion after the contact. The leave is scored by the share of the opponent's (target ball, pocket) pairs that are
 * hidden from where the cue ball stops ([HeatZoneGenerator.isPointOccludedForPocket] on the resulting table); the
 * outcome probability is the share of nine perturbed strokes (speed and line of contact) that leave every pair hidden.
 */
object FallbackShotAdvisor {
    /** Make probability at which a combination is preferred over a safety. A modelling choice. */
    const val COMBINATION_MAKE_THRESHOLD: Double = 0.40

    /**
     * Make probability below which a direct pot no longer counts as "a pot exists", so that a safety, kick or
     * combination is advised alongside it. One chance in five: a pot that misses four times in five usually leaves the
     * opponent an open table, so a controlled alternative is worth showing, while a pot above it is a shot the player
     * can reasonably take. It is deliberately lower than [COMBINATION_MAKE_THRESHOLD], which decides when a
     * combination is good enough to play in place of a safety. A modelling choice.
     */
    const val DIRECT_POT_FLOOR: Double = 0.20

    private const val MAX_FIRST_CUT_RAD: Double = 75.0 * PI / 180.0
    private const val MAX_SECOND_CUT_RAD: Double = 60.0 * PI / 180.0
    private val COMBINATION_SPEED_UNITS: List<Double> = listOf(3.0, 4.5, 6.0)
    private const val MEDIUM_SPEED_UNITS: Double = 4.5
    private const val NEAR_TIE_FRACTION: Double = 0.002

    private val KICK_SPEED_UNITS: List<Double> = listOf(4.0, 5.5)
    private const val KICK_SCAN_HALF_WIDTH_RAD: Double = 12.0 * PI / 180.0
    private const val KICK_SCAN_STEP_RAD: Double = 0.25 * PI / 180.0

    private val SAFETY_LINE_OFFSETS_DEG: List<Double> = listOf(0.0, 20.0, -20.0, 40.0, -40.0, 60.0, -60.0)
    private val SAFETY_SPEED_UNITS: List<Double> = listOf(2.0, 3.5, 5.0)
    private val SAFETY_TIPS: List<TipOffset> = listOf(TipOffset(), TipOffset(sideRatio = 0.0, verticalRatio = -0.30))
    private const val SAFETY_ROBUSTNESS_CHECKS: Int = 3

    /** Three-point Gauss-Hermite rule over the player's speed error, as in [ExecutionErrorModel.makeProbability]. */
    private val SPEED_NODES: DoubleArray = doubleArrayOf(0.0, sqrt(3.0), -sqrt(3.0))
    private val SPEED_WEIGHTS: DoubleArray = doubleArrayOf(2.0 / 3.0, 1.0 / 6.0, 1.0 / 6.0)

    private const val CONTACT_TOLERANCE_METERS: Double = 1e-3
    private const val CUSHION_TOLERANCE_METERS: Double = 2e-3

    /**
     * The fallback for [layout] under [rules] and [profile], or `null` when the run-out planner has a direct pot that
     * is at least [DIRECT_POT_FLOOR] likely to be made or no fallback of the three kinds is found. When a weaker direct
     * pot exists the advice's reason names its make probability.
     */
    fun advise(
        layout: TableLayout,
        rules: GameRulesState = GameRulesState(),
        profile: PlayerProfile = PlayerProfile(),
    ): FallbackAdvice? {
        val cue = layout.cueBall() ?: return null
        val legal = rules.legalTargetBalls(layout)
        if (legal.isEmpty()) return null
        val directPlans = MultiShotPlanner.planRunout(layout, rules, profile, horizonShots = 1)
        val bestDirect = directPlans.maxOfOrNull { it.steps.firstOrNull()?.uncertainty?.makeProbability ?: 0.0 }
        if (bestDirect != null && bestDirect >= DIRECT_POT_FLOOR) return null
        val weakPot = bestDirect?.let { percent(it) }

        val combination = if (layout.objectBalls().size >= 2) {
            bestCombination(layout, rules, profile, cue, legal, weakPot)
        } else {
            null
        }
        if (combination != null && combination.outcomeProbability >= COMBINATION_MAKE_THRESHOLD) return combination
        val directHit = legal.any { target -> safetyAims(layout, cue, target).isNotEmpty() }
        val safety = if (directHit) bestSafety(layout, rules, profile, cue, legal, weakPot) else null
        return safety ?: bestKick(layout, profile, cue, legal, weakPot) ?: combination
    }

    /** The opening words of a reason: [noPot] when there is no direct pot, else the make chance of the weak one. */
    private fun lead(noPot: String, weakPotPercent: Int?): String =
        if (weakPotPercent == null) noPot else "The direct pot is low-percentage (about $weakPotPercent% to make)"

    // ---------------------------------------------------------------------------------------------
    // Combination
    // ---------------------------------------------------------------------------------------------

    /** Balls that [first] may be played into under [rules]: never an opponent's ball, and the 8 only as the target. */
    private fun allowedSecondBalls(layout: TableLayout, rules: GameRulesState, first: Ball): List<Ball> {
        val others = layout.objectBalls().filter { it.number != first.number }
        return when (rules.gameType) {
            GameType.NINE_BALL, GameType.TEN_BALL -> others
            GameType.EIGHT_BALL -> when (rules.eightBallGroup) {
                EightBallGroup.SOLIDS -> others.filter { it.isSolid }
                EightBallGroup.STRIPES -> others.filter { it.isStripe }
                EightBallGroup.OPEN -> others.filter { (first.isSolid && it.isSolid) || (first.isStripe && it.isStripe) }
                EightBallGroup.EIGHT_BALL, EightBallGroup.UNSET -> emptyList()
            }
        }
    }

    /** Geometry of a combination: the ghost positions and the directions of the two object balls. */
    internal class CombinationGeometry(
        val cueGhost: Vec2,
        val firstGhost: Vec2,
        val firstDirection: Vec2,
        val secondDirection: Vec2,
        val secondAimPoint: Vec2,
        val firstCutRadians: Double,
        val secondCutRadians: Double,
    )

    /**
     * Ghost-ball geometry of playing [first] into [second] into [pocket] from [cuePos], without throw: [second] goes at
     * its aim point, [first] at the ghost position that sends it there, and the cue ball at the ghost position that
     * sends [first] there. `null` when a cut is beyond the limits or a ghost ball is off the table; clearance is not
     * checked here.
     */
    internal fun combinationGeometry(
        layout: TableLayout,
        cuePos: Vec2,
        cueRadius: Double,
        first: Ball,
        second: Ball,
        pocket: PocketSpec,
    ): CombinationGeometry? {
        val table = layout.table
        val aimPoint = PocketGeometry.aimPoint(second.position, pocket, second.radiusMeters)
        val secondDir = (aimPoint - second.position).normalized()
        val firstGhost = second.position - secondDir * (first.radiusMeters + second.radiusMeters)
        val firstLeg = firstGhost - first.position
        if (firstLeg.length() < 1e-6) return null
        val firstDir = firstLeg.normalized()
        val secondCut = firstDir.angleTo(secondDir)
        if (abs(secondCut) > MAX_SECOND_CUT_RAD) return null
        val cueGhost = first.position - firstDir * (cueRadius + first.radiusMeters)
        val cueLeg = cueGhost - cuePos
        if (cueLeg.length() < 1e-6) return null
        val firstCut = cueLeg.normalized().angleTo(firstDir)
        if (abs(firstCut) > MAX_FIRST_CUT_RAD) return null
        if (!table.isWithinPlayableBounds(firstGhost, first.radiusMeters) || !table.isWithinPlayableBounds(cueGhost, cueRadius)) {
            return null
        }
        return CombinationGeometry(cueGhost, firstGhost, firstDir, secondDir, aimPoint, firstCut, secondCut)
    }

    private fun bestCombination(
        layout: TableLayout,
        rules: GameRulesState,
        profile: PlayerProfile,
        cue: Ball,
        legal: List<Ball>,
        weakPotPercent: Int?,
    ): FallbackAdvice? {
        var best: FallbackAdvice? = null
        for (first in legal) {
            for (second in allowedSecondBalls(layout, rules, first)) {
                for (pocket in layout.table.pockets) {
                    val advice = evaluateCombination(layout, profile, cue, first, second, pocket, weakPotPercent) ?: continue
                    if (best == null || advice.outcomeProbability > best.outcomeProbability + 1e-12) best = advice
                }
            }
        }
        return best
    }

    private fun evaluateCombination(
        layout: TableLayout,
        profile: PlayerProfile,
        cue: Ball,
        first: Ball,
        second: Ball,
        pocket: PocketSpec,
        weakPotPercent: Int?,
    ): FallbackAdvice? {
        if (first.number == second.number) return null
        val table = layout.table
        if (!PocketGeometry.entryWindow(second.position, pocket, second.radiusMeters, pocket.jawToleranceSlow).isOpen) return null
        val geometry = combinationGeometry(layout, cue.position, cue.radiusMeters, first, second, pocket) ?: return null
        val others = layout.objectBalls()
        val clear = GhostBallAiming.isSegmentClear(second.position, geometry.secondAimPoint, second.radiusMeters,
            others.filter { it.number != second.number && it.number != first.number }) &&
            GhostBallAiming.isSegmentClear(first.position, geometry.firstGhost, first.radiusMeters,
                others.filter { it.number != first.number && it.number != second.number }) &&
            GhostBallAiming.isSegmentClear(cue.position, geometry.cueGhost, cue.radiusMeters,
                others.filter { it.number != first.number })
        if (!clear) return null

        val sigma = profile.aimErrorStdRad.coerceAtLeast(1e-4)
        var bestP = 0.0
        var bestSpeed = COMBINATION_SPEED_UNITS.first()
        var bestAim = geometry
        for (speedUnits in COMBINATION_SPEED_UNITS) {
            val speed = profile.speedForUnits(speedUnits)
            val compensated = compensateThrow(table, cue, first, second, geometry, speed) ?: continue
            val p = combinationProbability(table, profile, cue, first, second, pocket, geometry, compensated, speed, sigma)
            // Equal chances (within 0.2 %): the pace closer to medium.
            val clearlyBetter = p > bestP * (1.0 + NEAR_TIE_FRACTION) + 1e-12
            val asGoodAndCalmer = p >= bestP * (1.0 - NEAR_TIE_FRACTION) &&
                abs(speedUnits - MEDIUM_SPEED_UNITS) < abs(bestSpeed - MEDIUM_SPEED_UNITS)
            if (p > 0.0 && (clearlyBetter || asGoodAndCalmer)) {
                bestP = p
                bestSpeed = speedUnits
                bestAim = compensated
            }
        }
        if (bestP <= 0.0) return null
        val pct = percent(bestP)
        return FallbackAdvice(
            kind = FallbackKind.COMBINATION,
            targetBallNumbers = listOf(first.number, second.number),
            pocketId = pocket.id,
            lines = listOf(
                FallbackLine(cue.position, bestAim.cueGhost, FallbackLineRole.CUE_BALL),
                FallbackLine(first.position, bestAim.firstGhost, FallbackLineRole.FIRST_OBJECT_BALL),
                FallbackLine(second.position, bestAim.secondAimPoint, FallbackLineRole.SECOND_OBJECT_BALL),
            ),
            cushionPoint = null,
            speedUnits = bestSpeed,
            tip = TipOffset(),
            outcomeProbability = bestP,
            reason = "${lead("No direct pot", weakPotPercent)}: play ball #${first.number} into ball #${second.number} " +
                "and send #${second.number} into the ${pocket.id.displayName} pocket ($pct% to make).",
        )
    }

    /** The combination advice for one choice of balls and pocket, or `null` when it cannot be played (clear legs, cuts). */
    internal fun combinationAdvice(
        layout: TableLayout,
        profile: PlayerProfile,
        first: Ball,
        second: Ball,
        pocket: PocketSpec,
    ): FallbackAdvice? {
        val cue = layout.cueBall() ?: return null
        return evaluateCombination(layout, profile, cue, first, second, pocket, weakPotPercent = null)
    }

    /**
     * Make probability of the combination of [first] into [second] into [pocket] from the cue ball of [layout] at
     * [speedUnits] (the model behind [FallbackAdvice.outcomeProbability] for one speed), or `0` when the combination
     * cannot be set up at that speed.
     */
    internal fun combinationProbabilityAt(
        layout: TableLayout,
        profile: PlayerProfile,
        first: Ball,
        second: Ball,
        pocket: PocketSpec,
        speedUnits: Double,
    ): Double {
        val cue = layout.cueBall() ?: return 0.0
        val geometry = combinationGeometry(layout, cue.position, cue.radiusMeters, first, second, pocket) ?: return 0.0
        val speed = profile.speedForUnits(speedUnits)
        val compensated = compensateThrow(layout.table, cue, first, second, geometry, speed) ?: return 0.0
        val sigma = profile.aimErrorStdRad.coerceAtLeast(1e-4)
        return combinationProbability(layout.table, profile, cue, first, second, pocket, geometry, compensated, speed, sigma)
    }

    /**
     * Direction the second ball leaves in when the cue line is turned by [offset] radians from the line of
     * [nominal], the geometry of [combinationGeometry] without throw: the geometric ghost-ball mapping through both
     * contacts. Offset 0 is the intended second-ball direction ([CombinationGeometry.secondDirection]). `null` when
     * the turned line misses a ball.
     */
    internal fun modelledSecondDirection(
        cue: Ball,
        first: Ball,
        second: Ball,
        nominal: CombinationGeometry,
        offset: Double,
    ): Vec2? {
        val cueLine = (nominal.cueGhost - cue.position).normalized().rotate(offset)
        val (firstDir, _) = ExecutionErrorModel.objectBallDirectionForCueLine(
            cue.position, first.position, cueLine, cue.radiusMeters + first.radiusMeters,
        ) ?: return null
        return ExecutionErrorModel.objectBallDirectionForCueLine(
            first.position, second.position, firstDir, first.radiusMeters + second.radiusMeters,
        )?.first
    }

    /** Ball state of a centre-ball stroke at [cueSpeedMps] reaching [ghost] from [cuePos], or `null` if it stops short. */
    private fun cueStateAtContact(table: TableSpec, cuePos: Vec2, ghost: Vec2, cueSpeedMps: Double): BallState? {
        val leg = ghost - cuePos
        val pre = SpinPhysics.computePreImpactCueState(cueSpeedMps, TipOffset(), leg.length(), table)
        if (pre.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT || pre.impactSpeedMps <= 0.03) return null
        val v = leg.normalized() * pre.impactSpeedMps
        return BallState(ghost, v, v * pre.impactSpinFactorK)
    }

    /**
     * [geometry] with both contacts moved so that the throw ([BallBallContact.lineOfCentresForThrownDirection]) sends
     * each struck ball along the geometric direction; `null` if a ball does not reach the next contact.
     */
    private fun compensateThrow(
        table: TableSpec,
        cue: Ball,
        first: Ball,
        second: Ball,
        geometry: CombinationGeometry,
        cueSpeedMps: Double,
    ): CombinationGeometry? {
        val firstArrival = firstBallArrival(table, cue, first, geometry, cueSpeedMps) ?: return null
        val secondLine = BallBallContact.lineOfCentresForThrownDirection(firstArrival, geometry.secondDirection)
        val firstGhost = second.position - secondLine * (first.radiusMeters + second.radiusMeters)
        val firstDir = (firstGhost - first.position).normalized()
        val cueState = cueStateAtContact(table, cue.position, geometry.cueGhost, cueSpeedMps) ?: return null
        val firstLine = BallBallContact.lineOfCentresForThrownDirection(cueState, firstDir)
        val cueGhost = first.position - firstLine * (cue.radiusMeters + first.radiusMeters)
        return CombinationGeometry(
            cueGhost = cueGhost,
            firstGhost = firstGhost,
            firstDirection = firstDir,
            secondDirection = geometry.secondDirection,
            secondAimPoint = geometry.secondAimPoint,
            firstCutRadians = (cueGhost - cue.position).normalized().angleTo(firstDir),
            secondCutRadians = firstDir.angleTo(geometry.secondDirection),
        )
    }

    /** State of [first] when it reaches its ghost position, struck along [geometry] at [cueSpeedMps]; `null` if it stops. */
    private fun firstBallArrival(
        table: TableSpec,
        cue: Ball,
        first: Ball,
        geometry: CombinationGeometry,
        cueSpeedMps: Double,
    ): BallState? {
        val cueState = cueStateAtContact(table, cue.position, geometry.cueGhost, cueSpeedMps) ?: return null
        val line = (first.position - geometry.cueGhost).normalized()
        val struck = BallBallContact.resolve(cueState, BallState(first.position), line).struck
        return travel(BallMotion(table.cloth), struck, first.position.distanceTo(geometry.firstGhost))
    }

    /** State of a ball moving straight from [start] after [distance] metres, or `null` if it stops first. */
    private fun travel(motion: BallMotion, start: BallState, distance: Double): BallState? {
        var state = start
        var remaining = distance
        repeat(4) {
            if (state.velocity.length() <= 1e-4) return null
            val t = motion.timeToTravelStraight(state, remaining)
            if (t != null) return motion.stateAt(state, t)
            val end = motion.endOfPhase(state)
            remaining -= end.position.distanceTo(state.position)
            state = end
        }
        return null
    }

    /**
     * Make probability of a combination at [cueSpeedMps]: for each speed of the player's speed error, whether the
     * second ball reaches the pocket's capture line, and the normal mass of cue-line angles (standard deviation
     * [sigma]) whose second-ball direction lies inside the entry window at its arrival-speed tolerance.
     *
     * [nominal] is the geometry without throw and [compensated] the one the player aims (see [compensateThrow]). The
     * ball speeds come from the compensated contacts. The cue-line error is measured around the cue line of
     * [nominal]: through the geometric mapping of [modelledSecondDirection] that line sends the second ball along the
     * intended direction, which is what the compensated line does in the real contacts.
     */
    private fun combinationProbability(
        table: TableSpec,
        profile: PlayerProfile,
        cue: Ball,
        first: Ball,
        second: Ball,
        pocket: PocketSpec,
        nominal: CombinationGeometry,
        compensated: CombinationGeometry,
        cueSpeedMps: Double,
        sigma: Double,
    ): Double {
        val motion = BallMotion(table.cloth)
        val secondLine = (second.position - compensated.firstGhost).normalized()
        val drop = PocketGeometry.distanceToCaptureLine(second.position, nominal.secondDirection, pocket, second.radiusMeters)
            ?: second.position.distanceTo(nominal.secondAimPoint)
        val speedError = ExecutionErrorModel.speedErrorFraction(profile)
        var total = 0.0
        for (i in SPEED_NODES.indices) {
            val speed = cueSpeedMps * (1.0 + SPEED_NODES[i] * speedError)
            val firstArrival = firstBallArrival(table, cue, first, compensated, speed) ?: continue
            val struck = BallBallContact.resolve(firstArrival, BallState(second.position), secondLine).struck
            val arrival = travel(motion, struck, drop) ?: continue
            val window = PocketGeometry.entryWindow(
                second.position, pocket, second.radiusMeters, PocketGeometry.jawTolerance(pocket, arrival.velocity.length()),
            )
            if (!window.isOpen) continue
            total += SPEED_WEIGHTS[i] * massInside(sigma) { offset ->
                val d = modelledSecondDirection(cue, first, second, nominal, offset)
                d != null && insideWindow(d, window)
            }
        }
        return total.coerceIn(0.0, 1.0)
    }

    private fun insideWindow(direction: Vec2, window: PocketGeometry.EntryWindow): Boolean {
        val span = window.limitA.cross(window.limitB)
        return window.limitA.cross(direction) * span >= 0.0 && direction.cross(window.limitB) * span >= 0.0
    }

    /**
     * Normal probability mass (standard deviation [sigma]) of the interval of cue-line offsets around zero for which
     * [inside] holds, found by doubling steps outward and bisecting each edge. `0` when zero itself is outside.
     */
    private fun massInside(sigma: Double, inside: (Double) -> Boolean): Double {
        if (!inside(0.0)) return 0.0
        fun edge(sign: Double): Double {
            var good = 0.0
            var step = 1e-5
            var bad = Double.NaN
            while (step < 0.5) {
                val probe = sign * step
                if (inside(probe)) good = probe else {
                    bad = probe
                    break
                }
                step *= 2.0
            }
            if (bad.isNaN()) return sign * 0.5
            repeat(30) {
                val mid = 0.5 * (good + bad)
                if (inside(mid)) good = mid else bad = mid
            }
            return good
        }
        val hi = edge(1.0)
        val lo = edge(-1.0)
        return (ExecutionErrorModel.normalCdf(hi / sigma) - ExecutionErrorModel.normalCdf(lo / sigma)).coerceIn(0.0, 1.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Kick
    // ---------------------------------------------------------------------------------------------

    private enum class Cushion(val label: String) {
        HEAD("head"),
        FOOT("foot"),
        LEFT("left long"),
        RIGHT("right long"),
    }

    /** Point [p] mirrored in the line the cue ball's centre follows along [cushion]. */
    private fun mirror(p: Vec2, cushion: Cushion, layout: TableLayout, r: Double): Vec2 {
        val table = layout.table
        return when (cushion) {
            Cushion.HEAD -> Vec2(2.0 * r - p.x, p.y)
            Cushion.FOOT -> Vec2(2.0 * (table.lengthMeters - r) - p.x, p.y)
            Cushion.LEFT -> Vec2(p.x, 2.0 * r - p.y)
            Cushion.RIGHT -> Vec2(p.x, 2.0 * (table.widthMeters - r) - p.y)
        }
    }

    private fun onCushion(p: Vec2, cushion: Cushion, layout: TableLayout, r: Double): Boolean {
        val table = layout.table
        return when (cushion) {
            Cushion.HEAD -> p.x <= r + CUSHION_TOLERANCE_METERS
            Cushion.FOOT -> p.x >= table.lengthMeters - r - CUSHION_TOLERANCE_METERS
            Cushion.LEFT -> p.y <= r + CUSHION_TOLERANCE_METERS
            Cushion.RIGHT -> p.y >= table.widthMeters - r - CUSHION_TOLERANCE_METERS
        }
    }

    private fun touchesCushion(p: Vec2, layout: TableLayout, r: Double): Boolean =
        Cushion.entries.any { onCushion(p, it, layout, r) }

    /** Where a kick along [direction] at [speedMps] meets the cushion and then [target], or `null` if it does not. */
    private class KickTrace(val cushionPoint: Vec2, val contactPoint: Vec2)

    private fun traceKick(
        layout: TableLayout,
        cue: Ball,
        target: Ball,
        cushion: Cushion,
        direction: Vec2,
        speedMps: Double,
    ): KickTrace? {
        val path = TableCollisionSimulator.traceCueBall(
            table = layout.table,
            cueState = BallState(cue.position, direction * speedMps),
            cueRadius = cue.radiusMeters,
            spin = CueBallSpinPreset.STUN,
            initialDeflectionAngleRad = 0.0,
            rollingDeflectionAngleRad = 0.0,
            obstacleBalls = listOf(target),
            maxCushions = 1,
        )
        if (path.hitsScratchPocket != null || path.ballContacts < 1 || path.cushionContacts < 1) return null
        val vertices = path.points
        val contactIndex = vertices.indexOfFirst {
            it.distanceTo(target.position) <= cue.radiusMeters + target.radiusMeters + CONTACT_TOLERANCE_METERS
        }
        if (contactIndex <= 0) return null
        val cushionIndex = vertices.indexOfFirst { onCushion(it, cushion, layout, cue.radiusMeters) }
        if (cushionIndex <= 0 || cushionIndex >= contactIndex) return null
        if ((1 until contactIndex).count { i -> touchesCushion(vertices[i], layout, cue.radiusMeters) } != 1) {
            return null
        }
        // As for a safety, a ball must reach a cushion after the contact. Only the cue ball's path is traced here, so
        // it is the cue ball that has to.
        if ((contactIndex + 1 until vertices.size).none { i -> touchesCushion(vertices[i], layout, cue.radiusMeters) }) {
            return null
        }
        // The cue ball must touch no other ball on the way to the contact.
        val contact = vertices[contactIndex]
        val others = layout.objectBalls().filter { it.number != target.number }
        val samples = path.sampledPoints
        val lastSample = samples.indexOfFirst { it.distanceTo(contact) < 1e-6 }.takeIf { it > 0 } ?: samples.size - 1
        for (i in 1..lastSample) {
            if (!GhostBallAiming.isSegmentClear(samples[i - 1], samples[i], cue.radiusMeters, others)) return null
        }
        return KickTrace(vertices[cushionIndex], contact)
    }

    private fun bestKick(
        layout: TableLayout,
        profile: PlayerProfile,
        cue: Ball,
        legal: List<Ball>,
        weakPotPercent: Int?,
    ): FallbackAdvice? {
        val sigma = profile.aimErrorStdRad.coerceAtLeast(1e-4)
        val speedError = ExecutionErrorModel.speedErrorFraction(profile)
        val steps = (KICK_SCAN_HALF_WIDTH_RAD / KICK_SCAN_STEP_RAD).roundToInt()
        var best: FallbackAdvice? = null
        for (target in legal) {
            for (cushion in Cushion.entries) {
                val mirrored = mirror(target.position, cushion, layout, cue.radiusMeters)
                val centre = (mirrored - cue.position).normalized()
                for (speedUnits in KICK_SPEED_UNITS) {
                    val speed = profile.speedForUnits(speedUnits)
                    // Which scan directions make a legal hit, at the nominal speed and at the two speed-error nodes.
                    val hits = Array(SPEED_NODES.size) { node ->
                        val s = speed * (1.0 + SPEED_NODES[node] * speedError)
                        BooleanArray(2 * steps + 1) { k ->
                            traceKick(layout, cue, target, cushion, centre.rotate((k - steps) * KICK_SCAN_STEP_RAD), s) != null
                        }
                    }
                    val nominal = hits[0]
                    // Aim at the middle of the longest run of good directions at the nominal speed.
                    var runStart = -1
                    var bestStart = -1
                    var bestLength = 0
                    for (k in 0..nominal.size) {
                        val ok = k < nominal.size && nominal[k]
                        if (ok && runStart < 0) runStart = k
                        if (!ok && runStart >= 0) {
                            if (k - runStart > bestLength) {
                                bestLength = k - runStart
                                bestStart = runStart
                            }
                            runStart = -1
                        }
                    }
                    if (bestLength == 0) continue
                    val aimIndex = bestStart + (bestLength - 1) / 2
                    val aimAngle = (aimIndex - steps) * KICK_SCAN_STEP_RAD
                    var p = 0.0
                    for (node in SPEED_NODES.indices) {
                        val row = hits[node]
                        if (!row[aimIndex]) continue
                        var lo = aimIndex
                        while (lo > 0 && row[lo - 1]) lo--
                        var hi = aimIndex
                        while (hi < row.size - 1 && row[hi + 1]) hi++
                        val loEdge = (lo - steps - 0.5) * KICK_SCAN_STEP_RAD - aimAngle
                        val hiEdge = (hi - steps + 0.5) * KICK_SCAN_STEP_RAD - aimAngle
                        p += SPEED_WEIGHTS[node] *
                            (ExecutionErrorModel.normalCdf(hiEdge / sigma) - ExecutionErrorModel.normalCdf(loEdge / sigma))
                    }
                    if (best != null && p <= best.outcomeProbability + 1e-12) continue
                    val trace = traceKick(layout, cue, target, cushion, centre.rotate(aimAngle), speed) ?: continue
                    best = FallbackAdvice(
                        kind = FallbackKind.KICK,
                        targetBallNumbers = listOf(target.number),
                        pocketId = null,
                        lines = listOf(
                            FallbackLine(cue.position, trace.cushionPoint, FallbackLineRole.CUE_BALL),
                            FallbackLine(trace.cushionPoint, trace.contactPoint, FallbackLineRole.CUE_BALL),
                        ),
                        cushionPoint = trace.cushionPoint,
                        speedUnits = speedUnits,
                        tip = TipOffset(),
                        outcomeProbability = p.coerceIn(0.0, 1.0),
                        reason = "${lead("Ball #${target.number} is hidden", weakPotPercent)}: kick off the ${cushion.label} cushion at the point shown, " +
                            "centre ball at speed ${PlayerProfile.formatSpeedUnits(speedUnits)}, for a legal hit " +
                            "(${percent(p)}% chance).",
                    )
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------------------

    /** Aims at [target] along the safety lines of contact whose cue-ball path is clear, with the offset in degrees. */
    private fun safetyAims(layout: TableLayout, cue: Ball, target: Ball): List<Pair<Double, AimGeometry>> {
        val toTarget = (target.position - cue.position).normalized()
        return SAFETY_LINE_OFFSETS_DEG.mapNotNull { offset ->
            val line = toTarget.rotate(offset * PI / 180.0)
            val aim = GhostBallAiming.computeAimGeometryToPoint(
                cueBall = cue,
                objectBall = target,
                targetPoint = target.position + line,
                otherBalls = layout.balls,
                table = layout.table,
            )
            if (aim.isCutPossible && aim.isCueToGhostClear) offset to aim else null
        }
    }

    /** Balls the opponent may play at next on [after]. */
    private fun opponentTargets(after: TableLayout, rules: GameRulesState): List<Ball> {
        val balls = after.objectBalls()
        return when (rules.gameType) {
            GameType.NINE_BALL, GameType.TEN_BALL -> rules.legalTargetBalls(after)
            GameType.EIGHT_BALL -> {
                val theirs = when (rules.eightBallGroup) {
                    EightBallGroup.SOLIDS -> balls.filter { it.isStripe }
                    EightBallGroup.STRIPES -> balls.filter { it.isSolid }
                    else -> balls.filter { !it.isEightBall }
                }
                theirs.ifEmpty { balls.filter { it.isEightBall } }
            }
        }
    }

    /** Share of the opponent's (target, pocket) pairs with no direct pot from where the cue ball rests on [after]. */
    internal fun hiddenShare(after: TableLayout, rules: GameRulesState): Double {
        val cue = after.cueBall() ?: return 0.0
        val targets = opponentTargets(after, rules)
        if (targets.isEmpty()) return 0.0
        var hidden = 0
        var total = 0
        for (target in targets) {
            for (pocket in after.table.pockets) {
                total++
                val region = HeatZoneGenerator.computePocketVisibility(after, target, pocket, cue.radiusMeters)
                if (HeatZoneGenerator.isPointOccludedForPocket(cue.position, region)) hidden++
            }
        }
        return hidden.toDouble() / total
    }

    /** The table a safety stroke leaves, or `null` when the stroke is not a legal safety. */
    private fun safetyResult(
        layout: TableLayout,
        target: Ball,
        aim: AimGeometry,
        speedMps: Double,
        tip: TipOffset,
    ): TableLayout? {
        val sim = TableCollisionSimulator.simulatePostImpact(layout, target, aim, speedMps, tip)
        if (sim.cueBallStoppedBeforeImpact || sim.cueBallTrajectory.hitsScratchPocket != null) return null
        if (sim.targetBallPotted || sim.disturbedBalls.any { it.pocketedIn != null }) return null
        // After the contact some ball must reach a cushion.
        val r = layout.table.ballRadiusMeters
        val railTouched = sim.cueBallTrajectory.cushionContacts > 0 ||
            (listOfNotNull(sim.targetBallTrajectory) + sim.disturbedBalls).any { path ->
                path.points.drop(1).any { p -> touchesCushion(p, layout, r) }
            }
        return if (railTouched) sim.resultingLayout else null
    }

    private fun bestSafety(
        layout: TableLayout,
        rules: GameRulesState,
        profile: PlayerProfile,
        cue: Ball,
        legal: List<Ball>,
        weakPotPercent: Int?,
    ): FallbackAdvice? {
        class Candidate(
            val target: Ball,
            val offset: Double,
            val aim: AimGeometry,
            val speedUnits: Double,
            val tip: TipOffset,
            val after: TableLayout,
            val hidden: Double,
        )
        val candidates = ArrayList<Candidate>()
        for (target in legal) {
            for ((offset, aim) in safetyAims(layout, cue, target)) {
                for (speedUnits in SAFETY_SPEED_UNITS) {
                    for (tip in SAFETY_TIPS) {
                        val after = safetyResult(layout, target, aim, profile.speedForUnits(speedUnits), tip) ?: continue
                        candidates += Candidate(target, offset, aim, speedUnits, tip, after, hiddenShare(after, rules))
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        candidates.sortWith(
            compareByDescending<Candidate> { it.hidden }
                .thenBy { abs(it.speedUnits - 3.5) }
                .thenBy { abs(it.offset) }
                .thenBy { it.tip.magnitudeRatio },
        )
        val sigma = profile.aimErrorStdRad.coerceAtLeast(1e-4)
        val speedError = ExecutionErrorModel.speedErrorFraction(profile)
        var chosen: Candidate? = null
        var chosenP = -1.0
        for (candidate in candidates.take(SAFETY_ROBUSTNESS_CHECKS)) {
            // Nine perturbed strokes: speed error and an error in the line of contact (as a cue-line error).
            var p = 0.0
            for (i in SPEED_NODES.indices) for (j in SPEED_NODES.indices) {
                val speed = profile.speedForUnits(candidate.speedUnits) * (1.0 + SPEED_NODES[i] * speedError)
                val aim = perturbedAim(candidate.aim, SPEED_NODES[j] * sigma)
                val after = safetyResult(layout, candidate.target, aim, speed, candidate.tip) ?: continue
                if (hiddenShare(after, rules) >= 1.0 - 1e-9) p += SPEED_WEIGHTS[i] * SPEED_WEIGHTS[j]
            }
            if (p > chosenP + 1e-12) {
                chosen = candidate
                chosenP = p
            }
        }
        val pick = chosen ?: return null
        val rest = pick.after.cueBall()?.position
        val targetRest = pick.after.ballByNumber(pick.target.number)?.position
        val hit = when {
            abs(pick.offset) < 10.0 -> "full"
            abs(pick.offset) < 50.0 -> "half-ball"
            else -> "thin"
        }
        val sentence = if (pick.hidden >= 1.0 - 1e-9) {
            "${lead("Ball #${pick.target.number} has no pot", weakPotPercent)}: hit it $hit at speed ${PlayerProfile.formatSpeedUnits(pick.speedUnits)} " +
                "and leave your opponent no direct pot (${percent(chosenP)}% chance)."
        } else {
            "${lead("Ball #${pick.target.number} has no pot", weakPotPercent)}: hit it $hit at speed ${PlayerProfile.formatSpeedUnits(pick.speedUnits)} " +
                "to leave your opponent as little as possible (${percent(pick.hidden)}% of the pots blocked)."
        }
        return FallbackAdvice(
            kind = FallbackKind.SAFETY,
            targetBallNumbers = listOf(pick.target.number),
            pocketId = null,
            lines = listOfNotNull(
                FallbackLine(cue.position, pick.aim.ghostBallCenter, FallbackLineRole.CUE_BALL),
                rest?.let { FallbackLine(pick.aim.ghostBallCenter, it, FallbackLineRole.CUE_BALL) },
                targetRest?.let { FallbackLine(pick.target.position, it, FallbackLineRole.FIRST_OBJECT_BALL) },
            ),
            cushionPoint = null,
            speedUnits = pick.speedUnits,
            tip = pick.tip,
            outcomeProbability = chosenP.coerceIn(0.0, 1.0),
            reason = sentence,
            cueBallRest = rest,
            resultingLayout = pick.after,
        )
    }

    /** [aim] with the cue line turned by [offset] radians, contact geometry recomputed exactly. */
    private fun perturbedAim(aim: AimGeometry, offset: Double): AimGeometry {
        if (offset == 0.0) return aim
        val contact = aim.objectBallPos.distanceTo(aim.ghostBallCenter)
        val (objectDir, ghost) = ExecutionErrorModel.objectBallDirectionForCueLine(
            aim.cueBallPos, aim.objectBallPos, aim.aimDirection.rotate(offset), contact,
        ) ?: return aim
        val toGhost = ghost - aim.cueBallPos
        return aim.copy(
            ghostBallCenter = ghost,
            aimDirection = toGhost.normalized(),
            objectBallDirection = objectDir,
            cutAngleRadians = toGhost.normalized().angleTo(objectDir),
            cueToGhostDistanceMeters = toGhost.length(),
        )
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt().coerceIn(0, 100)
}
