package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.Camera3DOrbitSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.ShotAnimationPhase
import com.runout.engine.physics.ShotTimelineAnimator
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShotTimelineAnd3DTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    private fun standardCutLayout(): TableLayout = TableLayout(
        table = table,
        balls = listOf(
            Ball(number = 0, position = Vec2(0.85, 0.78), radiusMeters = r),
            Ball(number = 1, position = Vec2(1.78, 0.86), radiusMeters = r),
            Ball(number = 8, position = Vec2(2.12, 0.42), radiusMeters = r),
        ),
    )

    @Test
    fun buildTimelineReturnsNullForMissingBallsOrImpossibleBackCutAndSucceedsForValidShot() {
        val noCueLayout = TableLayout(
            table = table,
            balls = listOf(Ball(number = 1, position = Vec2(1.78, 0.86), radiusMeters = r)),
        )
        assertNull(ShotTimelineAnimator.buildTimeline(noCueLayout, 1, PocketId.FOOT_RIGHT))

        val layout = standardCutLayout()
        assertNull(ShotTimelineAnimator.buildTimeline(layout, 9, PocketId.FOOT_RIGHT))
        assertNull(ShotTimelineAnimator.buildTimeline(layout, 0, PocketId.FOOT_RIGHT))

        val impossibleBackCutLayout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(2.40, 1.15), radiusMeters = r),
                Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r),
            ),
        )
        assertNull(ShotTimelineAnimator.buildTimeline(impossibleBackCutLayout, 1, PocketId.FOOT_RIGHT))

        val timeline = ShotTimelineAnimator.buildTimeline(
            layout = layout,
            targetBallNumber = 1,
            pocketId = PocketId.FOOT_RIGHT,
            cueSpeedMps = 2.0,
            tip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
            shaftType = ShaftType.LOW_DEFLECTION,
        )
        assertNotNull(timeline)
        assertTrue(timeline.strikeDurationSeconds > 0.0)
        assertTrue(timeline.approachDurationSeconds > 0.0)
        assertTrue(timeline.objectPocketDurationSeconds > 0.0)
        assertTrue(timeline.cueLeaveDurationSeconds > 0.0)
        assertTrue(timeline.totalDurationSeconds > timeline.strikeDurationSeconds + timeline.approachDurationSeconds)
        assertTrue(timeline.impactProgressFraction in 0.05..0.85)
    }

    @Test
    fun sampleAtProgressZeroShowsCueStickAndInitialBallState() {
        val layout = standardCutLayout()
        val timeline = ShotTimelineAnimator.buildTimeline(layout, 1, PocketId.FOOT_RIGHT, cueSpeedMps = 2.0)
        assertNotNull(timeline)

        val frame0 = timeline.sampleAtProgress(0.0)
        assertEquals(0.0, frame0.progressFraction, 1e-12)
        assertEquals(0.0, frame0.timestampSeconds, 1e-12)
        assertEquals(ShotAnimationPhase.CUE_ADDRESS_AND_STRIKE, frame0.phase)
        assertTrue(frame0.cueStickVisible, "Cue stick must be visible at progress 0.0")
        assertTrue(frame0.pocketedBallNumbers.isEmpty())
        assertEquals(0.0, frame0.cueBallSpeedMps, 1e-12)
        assertEquals(0.0, frame0.targetBallSpeedMps, 1e-12)

        val cuePos = Vec2(0.85, 0.78)
        val objPos = Vec2(1.78, 0.86)
        assertTrue(frame0.cueBallPositionMeters.distanceTo(cuePos) < 1e-9)
        val frame0TargetPos = assertNotNull(frame0.targetBallPositionMeters)
        assertTrue(frame0TargetPos.distanceTo(objPos) < 1e-9)

        val stickLen = frame0.cueStickTipMeters.distanceTo(frame0.cueStickButtMeters)
        assertEquals(1.45, stickLen, 1e-6)
        val tipBehindCue = frame0.cueBallPositionMeters.distanceTo(frame0.cueStickTipMeters)
        assertTrue(tipBehindCue in r * 0.99..0.18 + 1e-9)
    }

    @Test
    fun strikeBackswingAndCueBallApproachProgressSmoothlyWithoutCueStickInPhaseTwo() {
        val layout = standardCutLayout()
        val timeline = ShotTimelineAnimator.buildTimeline(layout, 1, PocketId.FOOT_RIGHT, cueSpeedMps = 2.0)
        assertNotNull(timeline)

        val addressFrame = timeline.sampleAtProgress(0.0)
        val peakBackswingProgress = (timeline.strikeDurationSeconds * 0.60) / timeline.totalDurationSeconds
        val backswingFrame = timeline.sampleAtProgress(peakBackswingProgress)
        val endStrikeProgress = (timeline.strikeDurationSeconds * 0.98) / timeline.totalDurationSeconds
        val forwardStrikeFrame = timeline.sampleAtProgress(endStrikeProgress)

        assertEquals(ShotAnimationPhase.CUE_ADDRESS_AND_STRIKE, backswingFrame.phase)
        assertTrue(backswingFrame.cueStickVisible)
        val addressDist = addressFrame.cueBallPositionMeters.distanceTo(addressFrame.cueStickTipMeters)
        val peakBackDist = backswingFrame.cueBallPositionMeters.distanceTo(backswingFrame.cueStickTipMeters)
        val strikeDist = forwardStrikeFrame.cueBallPositionMeters.distanceTo(forwardStrikeFrame.cueStickTipMeters)
        assertTrue(peakBackDist > addressDist, "Cue stick must pull back during backswing")
        assertEquals(0.18, peakBackDist, 1e-3)
        assertTrue(strikeDist < peakBackDist, "Cue stick must accelerate forward toward cue ball before contact")

        val midApproachProgress =
            (timeline.strikeDurationSeconds + timeline.approachDurationSeconds * 0.5) / timeline.totalDurationSeconds
        val approachFrame = timeline.sampleAtProgress(midApproachProgress)
        assertEquals(ShotAnimationPhase.CUE_BALL_APPROACH, approachFrame.phase)
        assertFalse(approachFrame.cueStickVisible, "Cue stick must hide once cue ball launches")
        assertTrue(approachFrame.cueBallSpeedMps > 0.0)
        assertEquals(0.0, approachFrame.targetBallSpeedMps, 1e-12)
        assertEquals(2, approachFrame.traveledCuePathPoints.size)
        val cueBallInList = approachFrame.balls.firstOrNull { it.isCueBall }
        assertNotNull(cueBallInList)
        assertTrue(cueBallInList.position.distanceTo(approachFrame.cueBallPositionMeters) < 1e-9)
    }

    @Test
    fun sampleAtImpactProgressPlacesCueBallAtCompensatedGhostBall() {
        val layout = standardCutLayout()
        val timeline = ShotTimelineAnimator.buildTimeline(
            layout = layout,
            targetBallNumber = 1,
            pocketId = PocketId.FOOT_RIGHT,
            cueSpeedMps = 2.2,
            tip = TipOffset(sideRatio = 0.25, verticalRatio = 0.20),
        )
        assertNotNull(timeline)

        val impactFrame = timeline.sampleAtProgress(timeline.impactProgressFraction)
        val ghostCenter = timeline.compensatedAim.compensatedGhostBallCenter
        assertTrue(
            impactFrame.cueBallPositionMeters.distanceTo(ghostCenter) < 1e-6,
            "At impactProgressFraction cue ball must reach compensatedGhostBallCenter",
        )
        assertTrue(impactFrame.pocketedBallNumbers.isEmpty())
        assertNotNull(impactFrame.targetBallPositionMeters)

        val justAfterImpactProgress =
            ((timeline.strikeDurationSeconds + timeline.approachDurationSeconds + 0.02) / timeline.totalDurationSeconds)
                .coerceIn(0.0, 1.0)
        val contactFrame = timeline.sampleAtProgress(justAfterImpactProgress)
        assertEquals(ShotAnimationPhase.CONTACT_AND_THROW, contactFrame.phase)
        assertTrue(contactFrame.targetBallSpeedMps > 0.0)
    }

    @Test
    fun postImpactRollDeceleratesLinearlyAndPocketsTargetBallAtCompletion() {
        val layout = standardCutLayout()
        val timeline = ShotTimelineAnimator.buildTimeline(
            layout = layout,
            targetBallNumber = 1,
            pocketId = PocketId.FOOT_RIGHT,
            cueSpeedMps = 2.0,
            tip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
        )
        assertNotNull(timeline)

        val impactTime = timeline.strikeDurationSeconds + timeline.approachDurationSeconds
        val u1Progress = (impactTime + timeline.cueLeaveDurationSeconds * 0.25) / timeline.totalDurationSeconds
        val u2Progress = (impactTime + timeline.cueLeaveDurationSeconds * 0.55) / timeline.totalDurationSeconds
        val u3Progress = (impactTime + timeline.cueLeaveDurationSeconds * 0.85) / timeline.totalDurationSeconds

        val f1 = timeline.sampleAtProgress(u1Progress)
        val f2 = timeline.sampleAtProgress(u2Progress)
        val f3 = timeline.sampleAtProgress(u3Progress)

        assertEquals(ShotAnimationPhase.POST_IMPACT_ROLL, f1.phase)
        assertEquals(ShotAnimationPhase.POST_IMPACT_ROLL, f2.phase)
        assertTrue(
            f1.cueBallSpeedMps > f2.cueBallSpeedMps && f2.cueBallSpeedMps > f3.cueBallSpeedMps && f3.cueBallSpeedMps > 0.0,
            "Cue ball speed must decrease linearly toward rest during POST_IMPACT_ROLL",
        )
        assertTrue(f2.traveledCuePathPoints.first().distanceTo(Vec2(0.85, 0.78)) < 1e-9)
        assertTrue(f2.traveledCuePathPoints.last().distanceTo(f2.cueBallPositionMeters) < 1e-9)

        val endFrame = timeline.sampleAtProgress(1.0)
        assertEquals(ShotAnimationPhase.BALLS_AT_REST, endFrame.phase)
        assertTrue(1 in endFrame.pocketedBallNumbers)
        assertFalse(0 in endFrame.pocketedBallNumbers)
        assertNull(endFrame.targetBallPositionMeters)
        assertTrue(endFrame.balls.none { it.number == 1 })
        assertEquals(0.0, endFrame.cueBallSpeedMps, 1e-12)
        assertEquals(0.0, endFrame.targetBallSpeedMps, 1e-12)
        assertFalse(endFrame.cueStickVisible)
        assertTrue(
            endFrame.cueBallPositionMeters.distanceTo(timeline.cueBallTrajectory.points.last()) < 1e-6,
        )
    }

    @Test
    fun scratchTrajectoryAddsCueBallToPocketedNumbersAtEndAndRemovesFromActiveBalls() {
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val ghost = Vec2(table.lengthMeters - 0.635, 0.635)
        val toFootLeft = (footLeft.center - ghost).normalized()
        val objectBall = Ball(number = 8, position = ghost + toFootLeft * (2.0 * r), radiusMeters = r)
        val tangentToFootRight = toFootLeft.rotate(PI / 2.0)
        val aimDir = (toFootLeft + tangentToFootRight).normalized()
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.60, radiusMeters = r)
        val scratchLayout = TableLayout(table, listOf(cueBall, objectBall))

        val timeline = ShotTimelineAnimator.buildTimeline(
            layout = scratchLayout,
            targetBallNumber = 8,
            pocketId = PocketId.FOOT_LEFT,
            cueSpeedMps = 2.5,
            tip = TipOffset(),
        )
        assertNotNull(timeline)
        assertEquals(PocketId.FOOT_RIGHT, timeline.cueBallTrajectory.hitsScratchPocket)

        val startFrame = timeline.sampleAtProgress(0.0)
        assertTrue(startFrame.pocketedBallNumbers.isEmpty())

        val endFrame = timeline.sampleAtProgress(1.0)
        assertEquals(ShotAnimationPhase.BALLS_AT_REST, endFrame.phase)
        assertTrue(8 in endFrame.pocketedBallNumbers)
        assertTrue(0 in endFrame.pocketedBallNumbers)
        assertTrue(endFrame.balls.none { it.number == 0 || it.number == 8 })
        assertEquals(0.0, endFrame.cueBallSpeedMps, 1e-12)
    }

    @Test
    fun sampleKeyframesAndClampingProduceMonotonicSequenceAcrossSnookerAndPoolTables() {
        val snookerTable = TableSpec.fullSizeSnooker()
        val snookerRadius = snookerTable.ballRadiusMeters
        val snookerLayout = TableLayout(
            table = snookerTable,
            balls = listOf(
                Ball(number = 0, position = Vec2(2.82, 0.98), radiusMeters = snookerRadius),
                Ball(number = 1, position = Vec2(3.05, 1.24), radiusMeters = snookerRadius),
            ),
        )
        val timeline = ShotTimelineAnimator.buildTimeline(snookerLayout, 1, PocketId.FOOT_RIGHT, cueSpeedMps = 1.8)
        assertNotNull(timeline)

        val keyframes = timeline.sampleKeyframes(21)
        assertEquals(21, keyframes.size)
        assertEquals(0.0, keyframes.first().progressFraction, 1e-12)
        assertEquals(1.0, keyframes.last().progressFraction, 1e-12)
        assertEquals(timeline.totalDurationSeconds, keyframes.last().timestampSeconds, 1e-9)

        for (i in 0 until keyframes.lastIndex) {
            assertTrue(keyframes[i].timestampSeconds <= keyframes[i + 1].timestampSeconds)
            assertTrue(keyframes[i].progressFraction < keyframes[i + 1].progressFraction)
        }

        val clampedLow = timeline.sampleAtProgress(-0.5)
        val clampedHigh = timeline.sampleAtProgress(1.5)
        assertEquals(0.0, clampedLow.progressFraction, 1e-12)
        assertEquals(1.0, clampedHigh.progressFraction, 1e-12)
        assertEquals(ShotAnimationPhase.BALLS_AT_REST, clampedHigh.phase)
    }

    @Test
    fun projectTablePoint3DCentersTableOriginAndProjectsPositiveZUpward() {
        val cx = table.lengthMeters * 0.5
        val cy = table.widthMeters * 0.5
        val presets = listOf(
            Camera3DOrbitSpec.cornerIsometricView(),
            Camera3DOrbitSpec.sideBroadcastView(),
            Camera3DOrbitSpec.overheadTopDownView(),
            Camera3DOrbitSpec.behindCueView(Vec2(1.0, 0.25)),
        )

        for (camera in presets) {
            val centerProj = ShotTimelineAnimator.projectTablePoint3D(cx, cy, 0.0, table, camera)
            assertEquals(0.0, centerProj.normalizedX, 1e-9)
            assertEquals(0.0, centerProj.normalizedY, 1e-9)
            assertTrue(centerProj.depthMeters > 0.15)
            assertTrue(centerProj.apparentRadiusNormalized >= 0.002)

            val elevatedProj = ShotTimelineAnimator.projectTablePoint3D(cx, cy, 0.12, table, camera)
            assertTrue(
                elevatedProj.normalizedY < centerProj.normalizedY,
                "Positive +z height must map to smaller normalizedY (higher on screen) for $camera",
            )
        }
    }

    @Test
    fun projectTablePoint3DCloserPointsHaveSmallerDepthAndLargerApparentRadius() {
        val cx = table.lengthMeters * 0.5
        val sideCamera = Camera3DOrbitSpec.sideBroadcastView()

        val nearPoint = ShotTimelineAnimator.projectTablePoint3D(
            xMeters = cx,
            yMeters = table.widthMeters * 0.90,
            zMeters = r,
            table = table,
            camera = sideCamera,
        )
        val farPoint = ShotTimelineAnimator.projectTablePoint3D(
            xMeters = cx,
            yMeters = table.widthMeters * 0.10,
            zMeters = r,
            table = table,
            camera = sideCamera,
        )

        assertTrue(nearPoint.depthMeters < farPoint.depthMeters)
        assertTrue(nearPoint.apparentRadiusNormalized > farPoint.apparentRadiusNormalized)
        assertTrue(nearPoint.normalizedY > farPoint.normalizedY, "Nearer edge on tilted table sits lower on screen")
        assertTrue(
            listOf(
                nearPoint.normalizedX,
                nearPoint.normalizedY,
                nearPoint.depthMeters,
                nearPoint.apparentRadiusNormalized,
                farPoint.normalizedX,
                farPoint.normalizedY,
                farPoint.depthMeters,
                farPoint.apparentRadiusNormalized,
            ).all { it.isFinite() },
        )
    }

    @Test
    fun camera3DOrbitPresetsAlignBehindCueLineCornerAndOverheadViews() {
        val cx = table.lengthMeters * 0.5
        val cy = table.widthMeters * 0.5

        val behindHeadToFoot = Camera3DOrbitSpec.behindCueView(Vec2(1.0, 0.0))
        val headProj = ShotTimelineAnimator.projectTablePoint3D(0.25, cy, 0.0, table, behindHeadToFoot)
        val footProj = ShotTimelineAnimator.projectTablePoint3D(table.lengthMeters - 0.25, cy, 0.0, table, behindHeadToFoot)
        val leftRailProj = ShotTimelineAnimator.projectTablePoint3D(cx, 0.15, 0.0, table, behindHeadToFoot)
        val rightRailProj = ShotTimelineAnimator.projectTablePoint3D(cx, table.widthMeters - 0.15, 0.0, table, behindHeadToFoot)

        assertTrue(headProj.depthMeters < footProj.depthMeters, "Behind-cue view must place head end closer than foot end")
        assertTrue(leftRailProj.normalizedX < 0.0 && rightRailProj.normalizedX > 0.0)
        assertTrue(abs(headProj.normalizedX) < 1e-9 && abs(footProj.normalizedX) < 1e-9)

        val behindAcrossTable = Camera3DOrbitSpec.behindCueView(Vec2(0.0, 1.0))
        val nearY = ShotTimelineAnimator.projectTablePoint3D(cx, 0.15, 0.0, table, behindAcrossTable)
        val farY = ShotTimelineAnimator.projectTablePoint3D(cx, table.widthMeters - 0.15, 0.0, table, behindAcrossTable)
        assertTrue(nearY.depthMeters < farY.depthMeters)

        val corner = Camera3DOrbitSpec.cornerIsometricView()
        val overhead = Camera3DOrbitSpec.overheadTopDownView()
        assertTrue(overhead.pitchRadians > corner.pitchRadians)
        val overheadHead = ShotTimelineAnimator.projectTablePoint3D(0.25, cy, 0.0, table, overhead)
        val overheadFoot = ShotTimelineAnimator.projectTablePoint3D(table.lengthMeters - 0.25, cy, 0.0, table, overhead)
        assertTrue(overheadHead.normalizedX < 0.0 && overheadFoot.normalizedX > 0.0)
    }
}
