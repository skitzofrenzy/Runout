package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.RunoutPlan
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.roundToInt

/**
 * Typed-in table layouts for the planner scenarios. Coordinates are table metres on a 9 ft table: `x` from the head
 * cushion (0) to the foot cushion (2.54), `y` from the left long cushion (0) to the right one (1.27).
 */
internal object PlannerScenarios {
    val table: TableSpec = TableSpec.nineFootPool()
    val r: Double = table.ballRadiusMeters

    val nineBall = GameRulesState(GameType.NINE_BALL)
    val solids = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)

    val intermediate: PlayerProfile = PlayerProfile.fromQuestionnaire(ExperienceLevel.INTERMEDIATE, ShaftType.STANDARD, 3, 3)
    val beginner: PlayerProfile = PlayerProfile.fromQuestionnaire(ExperienceLevel.BEGINNER, ShaftType.STANDARD, 3, 3)
    val advanced: PlayerProfile = PlayerProfile.fromQuestionnaire(ExperienceLevel.ADVANCED, ShaftType.STANDARD, 3, 3)

    fun layout(vararg balls: Pair<Int, Pair<Double, Double>>): TableLayout = TableLayout(
        table,
        balls.map { (n, xy) -> Ball(number = n, position = Vec2(xy.first, xy.second), radiusMeters = r) },
    )

    private infix fun Double.at(y: Double): Pair<Double, Double> = this to y

    /**
     * S1: two balls left. Ball 1 is straight in to the foot-left corner; ball 2 is a short, nearly straight shot into
     * the foot-right corner from the contact point.
     */
    val stopShot: TableLayout = layout(
        0 to (1.90 at 0.64),
        1 to (2.20 at 0.34),
        2 to (2.30 at 0.78),
    )

    /**
     * S2: ball 2 goes into the foot-right corner and ball 3 waits down-table near the left side pocket. From below and
     * to the right of ball 2's pot line the cue ball rolls off one cushion toward the 3; from above it, the cue ball
     * runs to the foot end and needs two cushions to come back.
     */
    val rightSide: TableLayout = layout(
        0 to (1.75 at 0.75),
        1 to (2.30 at 0.45),
        2 to (2.15 at 0.95),
        3 to (1.25 at 0.45),
        9 to (0.55 at 0.95),
    )

    /** S3: an open three-ball pattern where plain follow, stun or draw all reach good position. */
    val naturalAngle: TableLayout = layout(
        0 to (1.40 at 0.70),
        1 to (2.00 at 0.90),
        2 to (1.75 at 0.35),
        9 to (0.80 at 0.90),
    )

    /** S4: ball 1 sits 0.36 m from the left side pocket on a line 28° off the cushion; the cue ball is nearly straight behind it. */
    val shallowSide: TableLayout = layout(
        0 to (0.56 at 0.38),
        1 to (0.95 at 0.17),
        2 to (1.80 at 0.80),
        9 to (0.70 at 1.00),
    )

    /** S5: ball 1 a few millimetres off the left long cushion, cue ball behind it, straight down the rail to the foot-left corner. */
    val downTheRail: TableLayout = layout(
        0 to (1.30 at 0.06),
        1 to (1.90 at r + 0.006),
        2 to (1.50 at 0.80),
        9 to (0.70 at 0.90),
    )

    /** S6: two balls left; ball 1 hangs in the mouth of the foot-left corner and ball 2 is up-table near the left side pocket. */
    val hanger: TableLayout = layout(
        0 to (1.80 at 0.60),
        1 to (2.47 at 0.07),
        2 to (1.50 at 0.30),
    )

    /**
     * S7 (solids): ball 2 is tied up with stripes 10 and 12 near the foot end; ball 1 is the open insurance ball in the
     * middle; ball 3 is a cut into the foot-left corner whose stun line runs into the cluster.
     */
    val clusterWithInsurance: TableLayout = layout(
        0 to (1.70 at 0.55),
        1 to (1.20 at 0.70),
        2 to (2.29 at 0.62),
        3 to (2.15 at 0.40),
        8 to (0.50 at 0.95),
        9 to (0.40 at 0.55),
        10 to (2.33 at 0.6615),
        12 to (2.33 at 0.5785),
        11 to (1.00 at 1.05),
    )

    /**
     * S8 (solids): the 8 is close to the foot-left corner and ball 5 sits where a stop shot into the left side pocket
     * leaves a straight-in 8. Balls 2, 3 and 4 are spread over the head half.
     */
    val keyBall: TableLayout = layout(
        0 to (1.50 at 0.70),
        2 to (0.50 at 0.90),
        3 to (0.90 at 0.30),
        4 to (1.30 at 1.05),
        5 to (1.75 at 0.45),
        8 to (2.10 at 0.30),
        9 to (0.25 at 0.45),
        10 to (2.30 at 1.05),
        11 to (1.00 at 0.65),
    )

    /**
     * S9 (solids): balls 2 and 3 are easy pots into the foot corners. Ball 4 sits up-table with the 8 between it and
     * the head-left corner and stripe 10 screening the head-right one; from the start and from anywhere at the foot end
     * it has no pot. Potting 2 and 3 with the natural stop or stun at the foot leaves nothing: the second of them has
     * to send the cue ball up-table.
     */
    val easyFirstTrap: TableLayout = layout(
        0 to (1.85 at 0.62),
        2 to (2.20 at 0.35),
        3 to (2.25 at 0.92),
        4 to (0.95 at 0.40),
        8 to (0.70 at 0.33),
        10 to (0.62 at 0.70),
        11 to (1.30 at 1.15),
    )

    /** S10: three balls with open pockets and short routes. */
    val openThree: TableLayout = layout(
        0 to (1.10 at 0.60),
        1 to (1.55 at 0.45),
        2 to (2.05 at 0.85),
        3 to (1.70 at 1.00),
    )

    /** S11: nine-ball layout typed from a phone photo of a real table (cue ball, then balls 2-9). */
    val photoOne: TableLayout = layout(
        0 to (0.8005 at 0.2273),
        2 to (1.9519 at 1.0783),
        3 to (1.7577 at 1.0327),
        4 to (0.4600 at 0.9054),
        5 to (2.3454 at 0.4950),
        6 to (0.3282 at 0.8427),
        7 to (2.3123 at 0.8613),
        8 to (0.5134 at 1.1010),
        9 to (1.9450 at 0.6526),
    )

    /** S12: nine-ball layout typed from a second photo (ball in hand already placed near the left cushion). */
    val photoTwo: TableLayout = layout(
        0 to (0.4264 at 0.0466),
        2 to (0.1978 at 0.0364),
        3 to (1.6057 at 0.0749),
        4 to (2.0848 at 0.6700),
        5 to (2.1586 at 0.3898),
        6 to (0.0404 at 0.4006),
        7 to (1.7870 at 1.1486),
        8 to (0.5738 at 0.9751),
        9 to (1.6338 at 0.9840),
    )

    /** S13: nine-ball layout typed from a third photo (balls 3-9 left). */
    val photoThree: TableLayout = layout(
        0 to (2.2816 at 0.2928),
        3 to (2.0093 at 0.3650),
        4 to (2.0498 at 0.8921),
        5 to (0.8576 at 0.0725),
        6 to (0.2927 at 0.4793),
        7 to (0.0344 at 1.1289),
        8 to (1.9222 at 0.8200),
        9 to (1.7344 at 0.6363),
    )

    // ---------------------------------------------------------------------------------------------
    // Reading a plan
    // ---------------------------------------------------------------------------------------------

    fun landing(step: PlannedShotStep): Vec2 =
        step.resultingLayout?.cueBall()?.position ?: step.uncertainty.nominalLandingPosition

    fun cushions(step: PlannedShotStep): Int = step.uncertainty.nominalTrajectory.cushionContacts

    fun usesSide(step: PlannedShotStep): Boolean = abs(step.recommendedTip.clampedSideRatio) >= 0.12

    fun usesDrawWithSide(step: PlannedShotStep): Boolean =
        step.recommendedTip.clampedVerticalRatio < -0.12 && usesSide(step)

    /** Angle between the object ball's line to its pocket and the cushion that holds the pocket, degrees. */
    fun sidePocketApproachDegrees(step: PlannedShotStep): Double {
        val dir = step.compensatedAim.geometricAim.objectBallDirection
        return asin(abs(dir.y).coerceIn(0.0, 1.0)) * 180.0 / kotlin.math.PI
    }

    fun f2(v: Double): String = ((v * 100.0).roundToInt() / 100.0).toString()

    fun describe(step: PlannedShotStep): String {
        val tip = step.recommendedTip
        val land = landing(step)
        val pos = step.positionSuccessProbability?.let { f2(it) } ?: "-"
        return "#${step.targetBall.number}->${step.pocketId} u=${step.recommendedSpeedUnits} " +
            "tip=(${f2(tip.sideRatio)},${f2(tip.verticalRatio)}) P=${f2(step.uncertainty.makeProbability)} " +
            "pos=$pos cush=${cushions(step)} land=(${f2(land.x)},${f2(land.y)})"
    }

    fun describe(plan: RunoutPlan?): String = if (plan == null) {
        "no plan"
    } else {
        plan.steps.joinToString(" | ") { describe(it) } + " | cum=${f2(plan.cumulativeRunoutProbability)}"
    }
}
