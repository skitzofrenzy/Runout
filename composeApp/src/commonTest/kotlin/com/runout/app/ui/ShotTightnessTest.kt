package com.runout.app.ui

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.planner.ShotRecommendation
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShotTightnessTest {

    private fun sampleShot(
        cutAngleDegrees: Double,
        score: Double,
        makeProbability: Double,
        spin: CueBallSpinPreset = CueBallSpinPreset.STUN,
    ): ShotRecommendation {
        val table = TableSpec.nineFootPool()
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val cuePos = Vec2(0.8, 0.6)
        val objectPos = Vec2(1.8, 0.9)
        val aim = AimGeometry(
            cueBallPos = cuePos,
            objectBallPos = objectPos,
            pocketAimPos = pocket.mouthCenter,
            ghostBallCenter = Vec2(1.75, 0.88),
            aimDirection = Vec2(1.0, 0.0),
            objectBallDirection = Vec2(1.0, 0.0),
            cueToGhostDistanceMeters = 1.0,
            objectToPocketDistanceMeters = 0.8,
            cutAngleRadians = cutAngleDegrees * (PI / 180.0),
            isCutPossible = true,
            isCueToGhostClear = true,
            isObjectToPocketClear = true,
        )
        return ShotRecommendation(
            targetBall = Ball(1, objectPos, radiusMeters = table.ballRadiusMeters),
            pocket = pocket,
            aim = aim,
            cueBallPaths = emptyMap(),
            recommendedSpin = spin,
            recommendedCueSpeedMps = 1.8,
            score = score,
            makeProbability = makeProbability,
        )
    }

    @Test
    fun singleShotTightnessUsesMakeProbabilityNotScore() {
        val easyPotGoodLeave = sampleShot(
            cutAngleDegrees = 0.0,
            score = 0.90,
            makeProbability = 0.95,
        )
        val easyPotAwkwardLeave = sampleShot(
            cutAngleDegrees = 0.0,
            score = 0.25,
            makeProbability = 0.95,
        )

        val tightnessGoodLeave = computeShotTightness(easyPotGoodLeave)
        val tightnessAwkwardLeave = computeShotTightness(easyPotAwkwardLeave)

        assertEquals(tightnessGoodLeave, tightnessAwkwardLeave, 1e-6f)
        assertEquals("Wide", shotTightnessLabel(tightnessAwkwardLeave))
    }

    @Test
    fun singleShotTightnessIncreasesAsMakeProbabilityDrops() {
        val highMake = computeShotTightness(
            sampleShot(cutAngleDegrees = 25.0, score = 0.50, makeProbability = 0.92),
        )
        val midMake = computeShotTightness(
            sampleShot(cutAngleDegrees = 25.0, score = 0.50, makeProbability = 0.65),
        )
        val lowMake = computeShotTightness(
            sampleShot(cutAngleDegrees = 25.0, score = 0.50, makeProbability = 0.35),
        )

        assertTrue(highMake < midMake, "Expected $highMake < $midMake")
        assertTrue(midMake < lowMake, "Expected $midMake < $lowMake")
    }
}
