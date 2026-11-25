package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.planner.OneShotPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerAndPositionPlayTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    @Test
    fun playerProfileSpeedScaleMappingAndInverseAreExact() {
        val profile = PlayerProfile()
        assertEquals(profile.speedScaleMinMps, profile.speedForScale(1), 1e-12)
        assertEquals(profile.speedScaleMaxMps, profile.speedForScale(10), 1e-12)
        assertEquals(profile.speedScaleMinMps, profile.speedForScale(0), 1e-12)
        assertEquals(profile.speedScaleMaxMps, profile.speedForScale(15), 1e-12)

        for (scale in 1..10) {
            val speed = profile.speedForScale(scale)
            assertEquals(scale, profile.scaleForSpeed(speed))
        }
        assertEquals(1, profile.scaleForSpeed(0.1))
        assertEquals(10, profile.scaleForSpeed(9.5))

        assertFailsWith<IllegalArgumentException> {
            PlayerProfile(speedScaleMinMps = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PlayerProfile(speedScaleMinMps = 2.0, speedScaleMaxMps = 1.5)
        }
        assertFailsWith<IllegalArgumentException> {
            PlayerProfile(maxEffectiveDrawRatio = -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            PlayerProfile(aimErrorStdRad = -0.001)
        }
    }

    @Test
    fun playerProfileQuestionnaireCalibratesSkillAndShaft() {
        val beginner = PlayerProfile.fromQuestionnaire(
            experience = ExperienceLevel.BEGINNER,
            shaft = ShaftType.STANDARD,
            speedControlRating = 1,
            spinRating = 1,
        )
        val advancedStandard = PlayerProfile.fromQuestionnaire(
            experience = ExperienceLevel.ADVANCED,
            shaft = ShaftType.STANDARD,
            speedControlRating = 5,
            spinRating = 5,
        )
        val advancedLowDeflection = PlayerProfile.fromQuestionnaire(
            experience = ExperienceLevel.ADVANCED,
            shaft = ShaftType.LOW_DEFLECTION,
            speedControlRating = 5,
            spinRating = 5,
        )

        assertTrue(beginner.maxEffectiveDrawRatio < 0.8)
        assertTrue(advancedLowDeflection.maxEffectiveDrawRatio > 1.5)
        assertTrue(beginner.aimErrorStdRad > advancedStandard.aimErrorStdRad)
        assertTrue(advancedLowDeflection.aimErrorStdRad < advancedStandard.aimErrorStdRad)
        assertTrue(advancedLowDeflection.speedScaleMinMps < beginner.speedScaleMinMps)
        assertTrue(advancedLowDeflection.speedScaleMaxMps > beginner.speedScaleMaxMps)
    }

    @Test
    fun ballSetProfilePresetsAndColorSwapWorkCorrectly() {
        val standard = BallSetProfile.standardPool()
        assertEquals(BallPaletteColor.WHITE, standard.colorForBall(0))
        assertEquals(BallPaletteColor.YELLOW, standard.colorForBall(1))
        assertEquals(BallPaletteColor.PURPLE, standard.colorForBall(4))
        assertEquals(BallPaletteColor.MAROON, standard.colorForBall(7))
        assertEquals(BallPaletteColor.BLACK, standard.colorForBall(8))
        assertEquals(BallPaletteColor.PURPLE, standard.colorForBall(12))
        assertEquals(BallPaletteColor.MAROON, standard.colorForBall(15))

        val tv = BallSetProfile.tvTournamentPool()
        assertEquals(BallPaletteColor.PINK, tv.colorForBall(4))
        assertEquals(BallPaletteColor.BROWN, tv.colorForBall(7))
        assertEquals(BallPaletteColor.PINK, tv.colorForBall(12))
        assertEquals(BallPaletteColor.BROWN, tv.colorForBall(15))
        assertEquals(BallPaletteColor.YELLOW, tv.colorForBall(1))

        val swapped = standard.swapBallColors(1, 4)
        assertEquals(BallPaletteColor.PURPLE, swapped.colorForBall(1))
        assertEquals(BallPaletteColor.YELLOW, swapped.colorForBall(4))
        // Original profile remains immutable
        assertEquals(BallPaletteColor.YELLOW, standard.colorForBall(1))

        assertFailsWith<IllegalArgumentException> {
            standard.swapBallColors(0, 1)
        }
    }

    @Test
    fun nineBallPositionPlayPopulatesNextTargetLineForOneToTwo() {
        val cueBall = Ball(number = 0, position = Vec2(0.80, 0.55), radiusMeters = r)
        val ball1 = Ball(number = 1, position = Vec2(1.40, 0.85), radiusMeters = r)
        val ball2 = Ball(number = 2, position = Vec2(1.90, 0.50), radiusMeters = r)
        val ball9 = Ball(number = 9, position = Vec2(0.40, 0.30), radiusMeters = r)
        val layout = TableLayout(table = table, balls = listOf(cueBall, ball1, ball2, ball9))

        val ranked = OneShotPlanner.rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
        )
        assertTrue(ranked.isNotEmpty())

        val topShot = ranked.first()
        assertEquals(1, topShot.targetBall.number)
        assertEquals(2, topShot.nextTargetBallNumber)
        val positionLine = topShot.nextTargetPositionLine
        assertNotNull(positionLine)
        val chosenStop = topShot.cueBallPaths.getValue(topShot.recommendedSpin).points.last()
        assertEquals(chosenStop.x, positionLine.first.x, 1e-9)
        assertEquals(chosenStop.y, positionLine.first.y, 1e-9)
        assertTrue(table.isWithinPlayableBounds(positionLine.second, r))
        assertTrue(topShot.score in 0.0..1.0)
    }

    @Test
    fun eightBallLastSolidTransitionsNextTargetToEightBall() {
        val cueBall = Ball(number = 0, position = Vec2(0.80, 0.55), radiusMeters = r)
        val solid3 = Ball(number = 3, position = Vec2(1.45, 0.85), radiusMeters = r)
        val eight = Ball(number = 8, position = Vec2(1.90, 0.50), radiusMeters = r)
        val stripe11 = Ball(number = 11, position = Vec2(0.45, 0.95), radiusMeters = r)
        val layout = TableLayout(table = table, balls = listOf(cueBall, solid3, eight, stripe11))
        val solidsRules = GameRulesState(
            gameType = GameType.EIGHT_BALL,
            eightBallGroup = EightBallGroup.SOLIDS,
        )

        val ranked = OneShotPlanner.rankShots(layout = layout, rulesState = solidsRules)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.all { it.targetBall.number == 3 })
        val shotWithEightShape = ranked.firstOrNull {
            it.nextTargetBallNumber == 8 && it.nextTargetPositionLine != null
        }
        assertNotNull(shotWithEightShape, "Clearing the last solid must look ahead to the 8-ball")

        // When only the 8-ball remains, there is no next target after potting it.
        val eightOnlyLayout = TableLayout(table = table, balls = listOf(cueBall, eight))
        val eightShots = OneShotPlanner.rankShots(layout = eightOnlyLayout, rulesState = solidsRules)
        assertTrue(eightShots.isNotEmpty())
        assertTrue(eightShots.all { it.nextTargetBallNumber == null && it.nextTargetPositionLine == null })
    }

    @Test
    fun cueSpeedScaleOverrideIsRespectedByPlanner() {
        val profile = PlayerProfile.fromQuestionnaire(
            experience = ExperienceLevel.INTERMEDIATE,
            shaft = ShaftType.LOW_DEFLECTION,
            speedControlRating = 4,
            spinRating = 3,
        )
        val cueBall = Ball(number = 0, position = Vec2(0.80, 0.55), radiusMeters = r)
        val ball1 = Ball(number = 1, position = Vec2(1.40, 0.85), radiusMeters = r)
        val ball2 = Ball(number = 2, position = Vec2(1.90, 0.50), radiusMeters = r)
        val layout = TableLayout(table = table, balls = listOf(cueBall, ball1, ball2))

        val ranked = OneShotPlanner.rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
            playerProfile = profile,
            cueSpeedScaleOverride = 9,
        )
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.all { it.recommendedSpeedScale == 9 })
        val expectedSpeed = profile.speedForScale(9)
        assertTrue(ranked.all { abs(it.recommendedCueSpeedMps - expectedSpeed) < 1e-12 })

        val clamped = OneShotPlanner.rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
            playerProfile = profile,
            cueSpeedScaleOverride = 15,
        )
        assertTrue(clamped.isNotEmpty())
        assertTrue(clamped.all { it.recommendedSpeedScale == 10 })
    }

    @Test
    fun playerAimErrorAndLowDrawPenaltiesAffectScores() {
        val cueBall = Ball(number = 0, position = Vec2(0.45, 0.50), radiusMeters = r)
        val ball1 = Ball(number = 1, position = Vec2(1.95, 0.95), radiusMeters = r)
        val layout = TableLayout(table = table, balls = listOf(cueBall, ball1))

        val lowErrorProfile = PlayerProfile(aimErrorStdRad = 0.008)
        val highErrorProfile = PlayerProfile(aimErrorStdRad = 0.040)

        val lowErrorTop = OneShotPlanner.rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
            playerProfile = lowErrorProfile,
            cueSpeedScaleOverride = 5,
        ).first()
        val highErrorTop = OneShotPlanner.rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
            playerProfile = highErrorProfile,
            cueSpeedScaleOverride = 5,
        ).first()

        assertTrue(
            highErrorTop.score < lowErrorTop.score,
            "Higher aimErrorStdRad (${highErrorTop.score}) should score lower than lower aimErrorStdRad (${lowErrorTop.score})",
        )

        // Straight-in shot into FOOT_RIGHT where FOLLOW scratches and STUN stops at ghost ball:
        // Low-draw player profile must avoid DRAW.
        val straightCue = Ball(number = 0, position = Vec2(1.3017, 0.6508), radiusMeters = r)
        val straightBall = Ball(number = 1, position = Vec2(1.80, 0.90), radiusMeters = r)
        val straightLayout = TableLayout(table = table, balls = listOf(straightCue, straightBall))
        val lowDrawProfile = PlayerProfile(maxEffectiveDrawRatio = 0.4)
        val footRightShot = OneShotPlanner.rankShots(
            layout = straightLayout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
            playerProfile = lowDrawProfile,
            cueSpeedScaleOverride = 5,
        ).first { it.pocket.id == PocketId.FOOT_RIGHT }

        assertEquals(CueBallSpinPreset.STUN, footRightShot.recommendedSpin)
        assertNull(footRightShot.cueBallPaths.getValue(footRightShot.recommendedSpin).hitsScratchPocket)
    }
}
