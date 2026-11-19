package com.runout.app.tools

import com.runout.app.rules.RulesRepository
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.Camera3DOrbitSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.ShotTimelineAnimator
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.OneShotPlanner
import com.runout.engine.snooker.SnookerBallType
import com.runout.engine.snooker.SnookerScoreState
import com.runout.engine.snooker.SnookerStrategyPlanner
import com.runout.engine.snooker.SnookerTacticalMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeToolsAndTutorialsTest {
    @Test
    fun allTutorialsCoverEightTopicsWithValidNonOverlappingPlayableLayouts() {
        val tutorials = TutorialsAndDrillsCatalog.allTutorials()
        assertEquals(8, tutorials.size, "Expected 8 interactive tutorials")
        assertEquals(tutorials.size, tutorials.map { it.id }.distinct().size, "Tutorial IDs must be unique")

        val expectedTitles = listOf(
            "Aiming Basics & Ghost Ball",
            "Stance, Bridge & Stroke Alignment",
            "Cue-Ball Speed Control",
            "Spin, Squirt, Swerve & Throw",
            "The 90\u00b0 and 30\u00b0 Tangent Rules",
            "Pattern Play & Position Heat Zones",
            "Safety Play & Two-Way Shots",
            "High-Energy Break Technique",
        )
        assertEquals(expectedTitles, tutorials.map { it.title })

        for (tutorial in tutorials) {
            assertTrue(tutorial.category.isNotBlank(), "${tutorial.id} category must not be blank")
            assertTrue(tutorial.summary.isNotBlank(), "${tutorial.id} summary must not be blank")
            assertTrue(tutorial.keyTakeaways.size >= 2, "${tutorial.id} must include key takeaways")

            val layout = tutorial.practiceLayout
            assertNotNull(layout.cueBall(), "${tutorial.id} layout must include a cue ball")
            assertEquals(
                layout.balls.size,
                layout.balls.map { it.number }.distinct().size,
                "${tutorial.id} ball numbers must be distinct",
            )
            assertFalse(layout.hasOverlappingBalls(), "${tutorial.id} must not have overlapping balls")
            for (ball in layout.balls) {
                assertTrue(
                    layout.table.isWithinPlayableBounds(ball.position, ball.radiusMeters),
                    "${tutorial.id} ball ${ball.number} must lie within playable bounds",
                )
            }

            val shots = OneShotPlanner.rankShots(layout, tutorial.practiceRulesState)
            assertTrue(shots.isNotEmpty(), "${tutorial.id} should offer at least one playable shot")
        }
    }

    @Test
    fun allDrillsProvideFiveScoredDrillsWithValidTargetsAndLayouts() {
        val drills = TutorialsAndDrillsCatalog.allDrills()
        assertEquals(5, drills.size, "Expected 5 scored drills")
        assertEquals(drills.size, drills.map { it.id }.distinct().size, "Drill IDs must be unique")

        val expectedTitles = listOf(
            "Lag Speed Control",
            "Stop-Shot Precision",
            "Half-Ball Cut Accuracy",
            "9-Ball 4-Ball Ghost Runout",
            "Snooker Line-Up Break Builder",
        )
        assertEquals(expectedTitles, drills.map { it.title })

        for (drill in drills) {
            assertTrue(drill.targetSkill.isNotBlank())
            assertTrue(drill.attemptsPerSet > 0)
            assertTrue(drill.targetSuccessCount in 1..drill.attemptsPerSet)
            assertTrue(drill.instructions.isNotBlank())

            val layout = drill.drillLayout
            assertNotNull(layout.cueBall(), "${drill.id} must include cue ball")
            assertFalse(layout.hasOverlappingBalls(), "${drill.id} must not overlap balls")
            for (ball in layout.balls) {
                assertTrue(
                    layout.table.isWithinPlayableBounds(ball.position, ball.radiusMeters),
                    "${drill.id} ball ${ball.number} must lie within playable bounds",
                )
            }
        }
    }

    @Test
    fun sampleSnookerBreakLayoutEvaluatesBreakSequenceAndSnookersMode() {
        val snookerLayout = TutorialsAndDrillsCatalog.sampleSnookerBreakLayout()
        assertEquals(TableSpec.fullSizeSnooker().lengthMeters, snookerLayout.table.lengthMeters, 1e-6)
        assertEquals(TableSpec.SNOOKER_BALL_RADIUS_METERS, snookerLayout.table.ballRadiusMeters, 1e-6)
        assertEquals(10, snookerLayout.balls.size, "Expected cue + 3 reds + 6 colours")
        assertFalse(snookerLayout.hasOverlappingBalls(), "Snooker break layout must have no overlapping balls")
        for (ball in snookerLayout.balls) {
            assertTrue(snookerLayout.table.isWithinPlayableBounds(ball.position, ball.radiusMeters))
        }

        val attackAdvice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = snookerLayout,
            scoreState = SnookerScoreState(
                playerScore = 12,
                opponentScore = 10,
                redsRemaining = 3,
                onColourAfterRed = false,
            ),
            playerProfile = PlayerProfile(),
            horizonShots = 3,
        )
        assertEquals(SnookerTacticalMode.ATTACK_BREAK_BUILD, attackAdvice.recommendedMode)
        assertTrue(attackAdvice.breakSequence.isNotEmpty(), "Expected a playable red break sequence")
        assertEquals(SnookerBallType.RED, attackAdvice.breakSequence.first().ballType)
        assertTrue(
            attackAdvice.breakSequence.first().makeProbability >= 0.52,
            "Opening red pot should be high percentage, got ${attackAdvice.breakSequence.first().makeProbability}",
        )
        if (attackAdvice.breakSequence.size >= 2) {
            val colourStep = attackAdvice.breakSequence[1]
            assertTrue(colourStep.ballType != SnookerBallType.RED)
            assertNotNull(colourStep.respottedToSpotMeters, "Colour potted while reds remain must respot")
        }

        val snookerRequiredAdvice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = snookerLayout,
            scoreState = SnookerScoreState(
                playerScore = 0,
                opponentScore = 90,
                redsRemaining = 3,
                onColourAfterRed = false,
            ),
            playerProfile = PlayerProfile(),
            horizonShots = 3,
        )
        assertEquals(SnookerTacticalMode.PLAY_FOR_SNOOKERS, snookerRequiredAdvice.recommendedMode)
        assertTrue(snookerRequiredAdvice.scoreState.needsSnookers)
        assertTrue(snookerRequiredAdvice.scoreState.snookersRequiredCount > 0)
    }

    @Test
    fun analyzeBreakPresetDetectsTwoImpactsAndOrdersSpeedAcrossAllThreePresets() {
        val presets = TutorialsAndDrillsCatalog.sampleBreakAudioPresets()
        assertEquals(3, presets.size)
        assertEquals(
            listOf("power_break_9ft", "controlled_9ball_break", "soft_8ball_break"),
            presets.map { it.id },
        )

        val estimates = presets.map { preset ->
            val result = TutorialsAndDrillsCatalog.analyzeBreakPreset(preset)
            assertNotNull(result, "Preset ${preset.id} must yield valid impact detection and break speed")
            val (envelope, speed) = result
            assertTrue(envelope.rackImpactTimestampSeconds > envelope.cueStrikeTimestampSeconds)
            assertTrue(envelope.rawDeltaTimeSeconds in 0.05..0.35)
            assertTrue(speed.acousticDelayCorrectionSeconds > 0.0)
            assertTrue(speed.correctedFlightTimeSeconds < envelope.rawDeltaTimeSeconds)
            assertTrue(speed.launchSpeedMps > speed.impactSpeedMps)
            assertEquals(preset.breakDistanceMeters, speed.breakDistanceMeters, 1e-9)
            speed
        }

        assertTrue(
            estimates[0].launchSpeedMps > estimates[1].launchSpeedMps,
            "Power break (${estimates[0].launchSpeedMps}) should be faster than controlled (${estimates[1].launchSpeedMps})",
        )
        assertTrue(
            estimates[1].launchSpeedMps > estimates[2].launchSpeedMps,
            "Controlled break (${estimates[1].launchSpeedMps}) should be faster than soft (${estimates[2].launchSpeedMps})",
        )
        assertTrue(estimates[0].launchSpeedMps > 9.5)
        assertTrue(estimates[2].launchSpeedMps in 5.0..7.0)
    }

    @Test
    fun poolRaceScoreStateTracksLeaderDoubleHillAndMatchWin() {
        val tiedEarly = PoolRaceScoreState(
            playerOneName = "Alex",
            playerTwoName = "Sam",
            raceTargetRacks = 7,
            playerOneRacks = 3,
            playerTwoRacks = 3,
        )
        assertNull(tiedEarly.leaderName)
        assertFalse(tiedEarly.isMatchComplete)
        assertEquals("In Progress", tiedEarly.hillStatusText)

        val playerOneHill = tiedEarly.copy(playerOneRacks = 6, playerTwoRacks = 4)
        assertEquals("Alex", playerOneHill.leaderName)
        assertFalse(playerOneHill.isMatchComplete)
        assertEquals("Alex on the Hill", playerOneHill.hillStatusText)

        val doubleHill = tiedEarly.copy(playerOneRacks = 6, playerTwoRacks = 6)
        assertNull(doubleHill.leaderName)
        assertFalse(doubleHill.isMatchComplete)
        assertEquals("Double Hill (6\u20136)", doubleHill.hillStatusText)

        val matchWon = doubleHill.copy(playerTwoRacks = 7)
        assertEquals("Sam", matchWon.leaderName)
        assertTrue(matchWon.isMatchComplete)
        assertEquals("Match Complete", matchWon.hillStatusText)
    }

    @Test
    fun shotClockLiveStateHandlesTicksWarningZoneAndOncePerRackExtension() {
        val initial = ShotClockLiveState()
        assertEquals(35, initial.remainingSeconds)
        assertFalse(initial.isInWarningZone)
        assertFalse(initial.isExpired)

        // Ticking down to 10s enters warning zone
        var clock = initial
        repeat(25) {
            clock = clock.tickOneSecond()
        }
        assertEquals(10, clock.remainingSeconds)
        assertTrue(clock.isInWarningZone)
        assertFalse(clock.isExpired)

        // Applying Player 1 extension once adds 30s and exits warning zone
        val extended = clock.applyExtension(forPlayerOne = true)
        assertEquals(40, extended.remainingSeconds)
        assertTrue(extended.playerOneExtensionUsedInRack)
        assertFalse(extended.playerTwoExtensionUsedInRack)
        assertFalse(extended.isInWarningZone)

        // Applying Player 1 extension a second time in the same rack is idempotent
        val secondAttempt = extended.applyExtension(forPlayerOne = true)
        assertEquals(extended, secondAttempt)

        // Player 2 still retains their independent rack extension
        val p2Extended = secondAttempt.applyExtension(forPlayerOne = false)
        assertEquals(70, p2Extended.remainingSeconds)
        assertTrue(p2Extended.playerTwoExtensionUsedInRack)

        // Resetting for next shot restores 35s (or 60s for break/push-out) while preserving rack flags
        val nextShot = p2Extended.resetForNextShot(isBreakOrPushOut = false)
        assertEquals(35, nextShot.remainingSeconds)
        assertTrue(nextShot.playerOneExtensionUsedInRack)
        assertTrue(nextShot.playerTwoExtensionUsedInRack)

        val nextBreak = nextShot.resetForNextShot(isBreakOrPushOut = true)
        assertEquals(60, nextBreak.remainingSeconds)

        // Ticking down to 0 marks expired and floors at 0
        var expiring = ShotClockLiveState(remainingSeconds = 2)
        expiring = expiring.tickOneSecond()
        assertEquals(1, expiring.remainingSeconds)
        assertTrue(expiring.isInWarningZone)
        assertFalse(expiring.isExpired)

        expiring = expiring.tickOneSecond()
        assertEquals(0, expiring.remainingSeconds)
        assertFalse(expiring.isInWarningZone)
        assertTrue(expiring.isExpired)

        expiring = expiring.tickOneSecond()
        assertEquals(0, expiring.remainingSeconds)
        assertTrue(expiring.isExpired)
    }

    @Test
    fun platformBreakAudioControllerProcessesInMemoryAndFallsBackSafely() {
        val controller = PlatformBreakAudioController()
        assertTrue(controller.audioBackendName.isNotBlank())
        assertTrue(controller.processesTransientPcmInMemoryOnly)
        assertEquals(2000, controller.defaultEnvelopeSampleRateHz())

        val controlled = controller.captureBreakPreset("controlled_9ball_break")
        assertEquals("controlled_9ball_break", controlled.id)
        assertNotNull(TutorialsAndDrillsCatalog.analyzeBreakPreset(controlled))

        val fallback = controller.captureBreakPreset("non_existent_preset")
        assertEquals("power_break_9ft", fallback.id)
        assertNotNull(TutorialsAndDrillsCatalog.analyzeBreakPreset(fallback))
    }

    @Test
    fun defaultPracticeLogsComputeSuccessRateFractionSafely() {
        val logs = TutorialsAndDrillsCatalog.defaultPracticeLogs()
        assertTrue(logs.isNotEmpty())
        assertEquals(logs.size, logs.map { it.id }.distinct().size)
        for (entry in logs) {
            assertTrue(entry.attempts > 0)
            assertEquals(
                entry.successes.toDouble() / entry.attempts.toDouble(),
                entry.successRateFraction,
                1e-9,
            )
        }
        val zeroAttempts = PracticeLogEntry(
            id = "log-zero",
            drillId = "drill-lag-speed",
            drillTitle = "Lag Speed Control",
            dateIso = "2026-11-17",
            successes = 0,
            attempts = 0,
        )
        assertEquals(0.0, zeroAttempts.successRateFraction, 1e-9)
    }

    @Test
    fun eightBallWalkthroughsHaveValidPlayableLayoutsAndStepGuides() {
        val walkthroughs = TutorialsAndDrillsCatalog.eightBallWalkthroughs()
        assertEquals(6, walkthroughs.size, "Expected 6 interactive 8-Ball walkthroughs")
        assertEquals(walkthroughs.size, walkthroughs.map { it.id }.distinct().size, "Walkthrough IDs must be unique")

        val expectedIds = listOf(
            "walkthrough-8ball-open-table-choice",
            "walkthrough-8ball-key-ball-planning",
            "walkthrough-8ball-cluster-breakout-insurance",
            "walkthrough-8ball-legal-safety-rail-rule",
            "walkthrough-8ball-ball-in-hand-placement",
            "walkthrough-8ball-final-eight-avoid-scratch",
        )
        assertEquals(expectedIds, walkthroughs.map { it.id })

        val expectedTitles = listOf(
            "1. Post-Break Open Table: Choosing Solids vs. Stripes",
            "2. Working Backward from the 8-Ball: Selecting the Key Ball",
            "3. Breaking Out a Tied-Up Ball Using an Insurance Ball",
            "4. Legal 8-Ball Safety: First Contact + Cushion Rule",
            "5. Ball-in-Hand After a Foul: Manufacturing the Stop-Shot Line",
            "6. Pocketing the 8-Ball Without Scratching on the Tangent Line",
        )
        assertEquals(expectedTitles, walkthroughs.map { it.title })

        for (walkthrough in walkthroughs) {
            assertTrue(walkthrough.situationSummary.isNotBlank(), "${walkthrough.id} situationSummary must not be blank")
            assertTrue(walkthrough.ruleApplied.isNotBlank(), "${walkthrough.id} ruleApplied must not be blank")
            assertTrue(
                walkthrough.commonMistakeToAvoid.isNotBlank(),
                "${walkthrough.id} commonMistakeToAvoid must not be blank",
            )
            assertTrue(walkthrough.stepByStepGuide.size >= 3, "${walkthrough.id} must provide >= 3 steps")
            assertTrue(walkthrough.stepByStepGuide.all { it.isNotBlank() }, "${walkthrough.id} steps must not be blank")
            assertEquals(GameType.EIGHT_BALL, walkthrough.rulesState.gameType)

            val layout = walkthrough.layout
            assertNotNull(layout.cueBall(), "${walkthrough.id} must include cue ball")
            assertEquals(
                layout.balls.size,
                layout.balls.map { it.number }.distinct().size,
                "${walkthrough.id} ball numbers must be distinct",
            )
            assertFalse(layout.hasOverlappingBalls(), "${walkthrough.id} must not overlap balls")
            for (ball in layout.balls) {
                assertTrue(
                    layout.table.isWithinPlayableBounds(ball.position, ball.radiusMeters),
                    "${walkthrough.id} ball ${ball.number} must lie within playable bounds",
                )
            }

            val shots = OneShotPlanner.rankShots(layout, walkthrough.rulesState)
            assertTrue(shots.isNotEmpty(), "${walkthrough.id} must offer at least one playable shot")
        }

        assertEquals(EightBallGroup.OPEN, walkthroughs[0].rulesState.eightBallGroup)
        assertEquals(EightBallGroup.STRIPES, walkthroughs[3].rulesState.eightBallGroup)

        val finalEightWalkthrough = walkthroughs[5]
        assertNotNull(finalEightWalkthrough.layout.ballByNumber(8))
        val eightShots = OneShotPlanner.rankShots(finalEightWalkthrough.layout, finalEightWalkthrough.rulesState)
        val footLeftEight = eightShots.firstOrNull {
            it.targetBall.number == 8 && it.pocket.id == PocketId.FOOT_LEFT
        }
        assertNotNull(footLeftEight, "8-ball into Foot Left should be playable in final walkthrough")
        assertEquals(
            PocketId.FOOT_RIGHT,
            footLeftEight.cueBallPaths.getValue(CueBallSpinPreset.STUN).hitsScratchPocket,
        )
        assertNotEquals(
            CueBallSpinPreset.STUN,
            footLeftEight.recommendedSpin,
            "Planner should avoid STUN when it scratches on the 8-ball tangent line",
        )

        val allRuleEntries = RulesRepository.allEntries
        assertEquals(20, allRuleEntries.size, "Expected 20 rule entries in RulesRepository")
        for (entry in allRuleEntries) {
            assertTrue(
                entry.exampleScenario.isNotBlank(),
                "${entry.id} exampleScenario must not be blank",
            )
        }
    }

    @Test
    fun walkthroughsAndTutorialsHaveVisualStagesAndValid3DTimelines() {
        val profile = PlayerProfile()
        val walkthroughs = TutorialsAndDrillsCatalog.eightBallWalkthroughs()
        val tutorials = TutorialsAndDrillsCatalog.allTutorials()
        assertEquals(6, walkthroughs.size)
        assertEquals(8, tutorials.size)

        val cameras = listOf(
            Camera3DOrbitSpec.cornerIsometricView(),
            Camera3DOrbitSpec.sideBroadcastView(),
            Camera3DOrbitSpec.overheadTopDownView(),
        )

        fun verifyScenario(
            id: String,
            layout: com.runout.engine.model.TableLayout,
            stages: List<WalkthroughVisualStage>,
            targetBallNumber: Int,
            pocketId: PocketId,
            tipOffset: TipOffset,
            speedScale: Int,
        ) {
            assertEquals(4, stages.size, "$id must define 4 visualStages")
            assertEquals(listOf(1, 2, 3, 4), stages.map { it.stageNumber }, "$id stage numbers must be 1..4")
            for (stage in stages) {
                assertTrue(stage.stageTitle.isNotBlank(), "$id stage ${stage.stageNumber} title must not be blank")
                assertTrue(stage.explanationText.isNotBlank(), "$id stage ${stage.stageNumber} text must not be blank")
                assertTrue(stage.calloutBadge.isNotBlank(), "$id stage ${stage.stageNumber} badge must not be blank")
                assertTrue(stage.frameProgress in 0.0..1.0, "$id stage ${stage.stageNumber} frameProgress out of bounds")
            }

            val cueSpeedMps = profile.speedForScale(speedScale)
            val timeline = ShotTimelineAnimator.buildTimeline(
                layout = layout,
                targetBallNumber = targetBallNumber,
                pocketId = pocketId,
                cueSpeedMps = cueSpeedMps,
                tip = tipOffset,
            )
            assertNotNull(timeline, "$id must produce a valid ShotAnimationTimeline")
            assertTrue(timeline.totalDurationSeconds > 0.0)

            val behindCue = Camera3DOrbitSpec.behindCueView(timeline.compensatedAim.geometricAim.aimDirection)
            val allCameras = cameras + behindCue

            val sampleProgresses = stages.map { it.frameProgress } + timeline.impactProgressFraction
            for (progress in sampleProgresses) {
                val frame = timeline.sampleAtProgress(progress)
                assertTrue(frame.timestampSeconds.isFinite())
                assertTrue(frame.cueBallSpeedMps.isFinite())
                assertTrue(frame.cueBallPositionMeters.x.isFinite() && frame.cueBallPositionMeters.y.isFinite())
                for (cam in allCameras) {
                    for (ball in frame.balls) {
                        val projected = ShotTimelineAnimator.projectTablePoint3D(
                            xMeters = ball.position.x,
                            yMeters = ball.position.y,
                            zMeters = ball.radiusMeters,
                            table = layout.table,
                            camera = cam,
                            sphereRadiusMeters = ball.radiusMeters,
                        )
                        assertTrue(projected.normalizedX.isFinite(), "$id ball ${ball.number} normalizedX must be finite")
                        assertTrue(projected.normalizedY.isFinite(), "$id ball ${ball.number} normalizedY must be finite")
                        assertTrue(
                            projected.depthMeters.isFinite() && projected.depthMeters >= 0.15,
                            "$id ball ${ball.number} depthMeters invalid: ${projected.depthMeters}",
                        )
                        assertTrue(
                            projected.apparentRadiusNormalized.isFinite() && projected.apparentRadiusNormalized > 0.0,
                            "$id ball ${ball.number} apparentRadiusNormalized invalid",
                        )
                    }
                    if (frame.cueStickVisible) {
                        val tipProj = ShotTimelineAnimator.projectTablePoint3D(
                            xMeters = frame.cueStickTipMeters.x,
                            yMeters = frame.cueStickTipMeters.y,
                            zMeters = layout.table.ballRadiusMeters,
                            table = layout.table,
                            camera = cam,
                        )
                        val buttProj = ShotTimelineAnimator.projectTablePoint3D(
                            xMeters = frame.cueStickButtMeters.x,
                            yMeters = frame.cueStickButtMeters.y,
                            zMeters = layout.table.ballRadiusMeters + 0.08,
                            table = layout.table,
                            camera = cam,
                        )
                        assertTrue(tipProj.normalizedX.isFinite() && tipProj.normalizedY.isFinite())
                        assertTrue(buttProj.normalizedX.isFinite() && buttProj.normalizedY.isFinite())
                    }
                }
            }

            val startFrame = timeline.sampleAtProgress(0.0)
            assertTrue(startFrame.cueStickVisible, "$id frame 0.0 should show cue stick")
            val endFrame = timeline.sampleAtProgress(1.0)
            assertTrue(
                targetBallNumber in endFrame.pocketedBallNumbers,
                "$id frame 1.0 should pocket target ball #$targetBallNumber",
            )
            assertEquals(0.0, endFrame.cueBallSpeedMps, 1e-6)
        }

        for (w in walkthroughs) {
            verifyScenario(
                id = w.id,
                layout = w.layout,
                stages = w.visualStages,
                targetBallNumber = w.primaryTargetBallNumber,
                pocketId = w.primaryPocketId,
                tipOffset = w.recommendedTipOffset,
                speedScale = w.recommendedSpeedScale,
            )
        }
        for (t in tutorials) {
            verifyScenario(
                id = t.id,
                layout = t.practiceLayout,
                stages = t.visualStages,
                targetBallNumber = t.primaryTargetBallNumber,
                pocketId = t.primaryPocketId,
                tipOffset = t.recommendedTipOffset,
                speedScale = t.recommendedSpeedScale,
            )
        }
    }
}
