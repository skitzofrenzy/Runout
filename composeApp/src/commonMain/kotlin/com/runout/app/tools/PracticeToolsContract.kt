package com.runout.app.tools

import com.runout.engine.math.Vec2
import com.runout.engine.model.*
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import com.runout.engine.snooker.*
import com.runout.engine.tools.*
import kotlin.math.exp
import kotlin.math.sin

/**
 * Annotated stage in a 4-step visual walkthrough or interactive tutorial sequence.
 */
data class WalkthroughVisualStage(
    val stageNumber: Int,
    val stageTitle: String,
    val explanationText: String,
    val calloutBadge: String,
    val frameProgress: Double,
)

/**
 * Interactive cue-sports tutorial pairing plain-language instruction with a playable [TableLayout],
 * 4-stage visual diagram metadata, and recommended shot parameters for 2D/3D playback.
 */
data class InteractiveTutorial(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val keyTakeaways: List<String>,
    val practiceLayout: TableLayout,
    val practiceRulesState: GameRulesState,
    val visualStages: List<WalkthroughVisualStage> = emptyList(),
    val primaryTargetBallNumber: Int = 1,
    val primaryPocketId: PocketId = PocketId.FOOT_RIGHT,
    val recommendedTipOffset: TipOffset = TipOffset(),
    val recommendedSpeedScale: Int = 5,
)

/**
 * Step-by-step 8-Ball walkthrough pairing a rule explanation, common mistake to avoid, and
 * 4-stage visual sequence with a playable [TableLayout] and [GameRulesState].
 */
data class EightBallWalkthrough(
    val id: String,
    val title: String,
    val situationSummary: String,
    val ruleApplied: String,
    val stepByStepGuide: List<String>,
    val commonMistakeToAvoid: String,
    val layout: TableLayout,
    val rulesState: GameRulesState,
    val visualStages: List<WalkthroughVisualStage> = emptyList(),
    val primaryTargetBallNumber: Int = 1,
    val primaryPocketId: PocketId = PocketId.FOOT_RIGHT,
    val recommendedTipOffset: TipOffset = TipOffset(),
    val recommendedSpeedScale: Int = 5,
)

/**
 * Scored practice drill specification with target success criteria and playable table setup.
 */
data class PracticeDrillSpec(
    val id: String,
    val title: String,
    val targetSkill: String,
    val attemptsPerSet: Int,
    val targetSuccessCount: Int,
    val instructions: String,
    val drillLayout: TableLayout,
    val drillRulesState: GameRulesState,
)

/**
 * Historical practice drill session log entry for tracking completion percentage over time.
 */
data class PracticeLogEntry(
    val id: String,
    val drillId: String,
    val drillTitle: String,
    val dateIso: String,
    val successes: Int,
    val attempts: Int,
    val notes: String = "",
) {
    val successRateFraction: Double =
        if (attempts > 0) successes.toDouble() / attempts.toDouble() else 0.0
}

/**
 * Match race scorekeeper state for 8-Ball, 9-Ball, or 10-Ball races.
 */
data class PoolRaceScoreState(
    val playerOneName: String = "Player 1",
    val playerTwoName: String = "Player 2",
    val raceTargetRacks: Int = 7,
    val playerOneRacks: Int = 0,
    val playerTwoRacks: Int = 0,
    val gameLabel: String = "9-Ball",
) {
    val leaderName: String? = when {
        playerOneRacks > playerTwoRacks -> playerOneName
        playerTwoRacks > playerOneRacks -> playerTwoName
        else -> null
    }

    val isMatchComplete: Boolean =
        playerOneRacks >= raceTargetRacks || playerTwoRacks >= raceTargetRacks

    val hillStatusText: String = when {
        isMatchComplete -> "Match Complete"
        playerOneRacks == raceTargetRacks - 1 && playerTwoRacks == raceTargetRacks - 1 ->
            "Double Hill ($playerOneRacks\u2013$playerTwoRacks)"
        playerOneRacks == raceTargetRacks - 1 -> "$playerOneName on the Hill"
        playerTwoRacks == raceTargetRacks - 1 -> "$playerTwoName on the Hill"
        else -> "In Progress"
    }
}

/**
 * Tournament shot-clock timing and extension configuration in seconds.
 */
data class ShotClockConfig(
    val standardShotSeconds: Int = 35,
    val breakOrPushOutSeconds: Int = 60,
    val extensionBonusSeconds: Int = 30,
    val warningThresholdSeconds: Int = 10,
)

/**
 * Live countdown state and per-rack extension tracking for a match shot clock.
 */
data class ShotClockLiveState(
    val config: ShotClockConfig = ShotClockConfig(),
    val remainingSeconds: Int = 35,
    val isRunning: Boolean = false,
    val playerOneExtensionUsedInRack: Boolean = false,
    val playerTwoExtensionUsedInRack: Boolean = false,
) {
    val isInWarningZone: Boolean = remainingSeconds in 1..config.warningThresholdSeconds
    val isExpired: Boolean = remainingSeconds <= 0

    /**
     * Grants [ShotClockConfig.extensionBonusSeconds] once per rack for the requested player.
     */
    fun applyExtension(forPlayerOne: Boolean): ShotClockLiveState {
        if (forPlayerOne && playerOneExtensionUsedInRack) return this
        if (!forPlayerOne && playerTwoExtensionUsedInRack) return this
        return if (forPlayerOne) {
            copy(
                remainingSeconds = remainingSeconds + config.extensionBonusSeconds,
                playerOneExtensionUsedInRack = true,
            )
        } else {
            copy(
                remainingSeconds = remainingSeconds + config.extensionBonusSeconds,
                playerTwoExtensionUsedInRack = true,
            )
        }
    }

    /**
     * Advances the countdown by one second down to a floor of zero.
     */
    fun tickOneSecond(): ShotClockLiveState =
        copy(remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0))

    /**
     * Resets the clock for the next stroke (`breakOrPushOutSeconds` or `standardShotSeconds`)
     * while preserving per-rack extension usage flags.
     */
    fun resetForNextShot(isBreakOrPushOut: Boolean = false): ShotClockLiveState =
        copy(
            remainingSeconds = if (isBreakOrPushOut) {
                config.breakOrPushOutSeconds
            } else {
                config.standardShotSeconds
            },
            isRunning = false,
        )
}

/**
 * Acoustic break-speed measurement preset containing a downsampled transient amplitude envelope
 * and physical table/microphone geometry in SI units.
 */
data class BreakAudioPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val envelopeSampleRateHz: Int,
    val envelopeAmplitudes: DoubleArray,
    val breakDistanceMeters: Double,
    val micToCueBallDistanceMeters: Double,
    val micToRackDistanceMeters: Double,
    val slidingFrictionMuS: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BreakAudioPreset) return false
        return id == other.id &&
            title == other.title &&
            subtitle == other.subtitle &&
            envelopeSampleRateHz == other.envelopeSampleRateHz &&
            envelopeAmplitudes.contentEquals(other.envelopeAmplitudes) &&
            breakDistanceMeters == other.breakDistanceMeters &&
            micToCueBallDistanceMeters == other.micToCueBallDistanceMeters &&
            micToRackDistanceMeters == other.micToRackDistanceMeters &&
            slidingFrictionMuS == other.slidingFrictionMuS
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + subtitle.hashCode()
        result = 31 * result + envelopeSampleRateHz
        result = 31 * result + envelopeAmplitudes.contentHashCode()
        result = 31 * result + breakDistanceMeters.hashCode()
        result = 31 * result + micToCueBallDistanceMeters.hashCode()
        result = 31 * result + micToRackDistanceMeters.hashCode()
        result = 31 * result + slidingFrictionMuS.hashCode()
        return result
    }
}

/**
 * Source of break-sound envelopes for the break-speed meter. In this version both platforms return the bundled
 * sample envelopes; the microphone is not used and nothing is recorded.
 */
expect class PlatformBreakAudioController() {
    val audioBackendName: String
    val processesTransientPcmInMemoryOnly: Boolean
    fun defaultEnvelopeSampleRateHz(): Int
    fun captureBreakPreset(presetId: String): BreakAudioPreset
}

/**
 * Curated interactive tutorials, scored drills, snooker break setups, and break-audio presets.
 */
object TutorialsAndDrillsCatalog {
    private const val ENVELOPE_SAMPLE_RATE_HZ: Int = 2000
    private const val ENVELOPE_SAMPLE_COUNT: Int = 640

    /**
     * Returns all 8 interactive tutorials covering aiming, fundamentals, speed, spin, tangent
     * rules, heat-zone pattern play, safety play, and break technique.
     */
    fun allTutorials(): List<InteractiveTutorial> {
        val table = TableSpec.nineFootPool()
        return listOf(
            InteractiveTutorial(
                id = "tutorial-aiming-ghost-ball",
                title = "Aiming Basics & Ghost Ball",
                category = "Aiming",
                summary = "Visualize the phantom cue-ball contact position directly behind the object ball on the pocket line.",
                keyTakeaways = listOf(
                    "Extend the pocket-to-object-ball centre line backwards by one ball diameter (2R = 57.15 mm).",
                    "Lock onto the ghost-ball centre before stepping down into your stance.",
                    "Check fractional ball overlap (full, 3/4, 1/2, 1/4) as a cross-check for cut angles.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.85, 0.78)),
                        Ball(number = 1, position = Vec2(1.58, 0.56)),
                        Ball(number = 2, position = Vec2(2.05, 0.92)),
                        Ball(number = 9, position = Vec2(2.24, 0.34)),
                    ),
                ),
                practiceRulesState = GameRulesState(gameType = GameType.NINE_BALL),
                visualStages = buildVisualStages(
                    stage1Badge = "2R = 57.15 mm Extension",
                    stage1Text = "Extend the Foot Left pocket line backward through the #1 ball by one ball diameter (57.15 mm) to locate the phantom ghost-ball target.",
                    stage2Badge = "~13° Cut Aim Line",
                    stage2Text = "Step straight down the cue-to-ghost line at medium speed (4/10) with a center-ball stroke so the cue ball travels cleanly to the contact ring.",
                    stage3Badge = "Ghost-Ball Contact",
                    stage3Text = "At contact, the cue ball fills the ghost-ball ring and sends the #1 directly down the Foot Left pot line with minimal throw.",
                    stage4Badge = "Center-Table Leave",
                    stage4Text = "#1 drops cleanly in Foot Left while the cue ball drifts across the lower-foot cloth into position for the #2.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 4,
            ),
            InteractiveTutorial(
                id = "tutorial-stance-stroke",
                title = "Stance, Bridge & Stroke Alignment",
                category = "Fundamentals",
                summary = "Align your vision centre, bridge hand, and pendulum forearm strictly down the shot line.",
                keyTakeaways = listOf(
                    "Step straight into the shot line so your grip hand drops naturally under your shoulder.",
                    "Keep a quiet head and stable 15–20 cm closed or open bridge through impact.",
                    "Finish with a straight follow-through of 10–15 cm; straight long pots expose side-swipe immediately.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.64, 0.42)),
                        Ball(number = 1, position = Vec2(1.62, 0.22)),
                        Ball(number = 8, position = Vec2(1.90, 0.64)),
                    ),
                ),
                practiceRulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.OPEN,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "Vision & Pendulum Setup",
                    stage1Text = "Address the long straight-ish #1 into Foot Left (~2° cut): place your bridge 15–20 cm behind the cue ball strictly along the shot axis.",
                    stage2Badge = "Straight Delivery (5/10)",
                    stage2Text = "Accelerate smoothly with a level cue through center ball without wrist twist; long straight shots immediately punish lateral cue steer.",
                    stage3Badge = "Square Ball-Ball Hit",
                    stage3Text = "Centered impact drives #1 straight down the Foot Left rail corridor while keeping cue-ball deflection near zero.",
                    stage4Badge = "Controlled Follow-Through",
                    stage4Text = "#1 pockets cleanly in Foot Left and the cue ball holds a quiet position around the foot half of the table.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 5,
            ),
            InteractiveTutorial(
                id = "tutorial-speed-control",
                title = "Cue-Ball Speed Control",
                category = "Speed Control",
                summary = "Calibrate pocket-weight to firm-speed strokes using rolling stopping distance (d proportional to v squared).",
                keyTakeaways = listOf(
                    "Pocket-speed shots effectively widen pocket acceptance angles and hold tight position.",
                    "Rolling distance scales with speed squared, so a 40% faster stroke travels nearly twice as far.",
                    "Rehearse your 1–10 speed scale during pre-shot practice swings rather than steering at impact.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.76, 0.35)),
                        Ball(number = 1, position = Vec2(1.68, 0.24)),
                        Ball(number = 2, position = Vec2(1.82, 0.92)),
                        Ball(number = 9, position = Vec2(2.22, 0.64)),
                    ),
                ),
                practiceRulesState = GameRulesState(gameType = GameType.NINE_BALL),
                visualStages = buildVisualStages(
                    stage1Badge = "Speed-Squared Planning",
                    stage1Text = "Assess the 9° cut on #1 into Foot Left: with #2 waiting at (1.82, 0.92), pocket speed 4/10 with a rolling cue ball glides gently into shape.",
                    stage2Badge = "Smooth 4/10 Rolling Stroke",
                    stage2Text = "Strike through the center of the cue ball at 4/10 speed: a ball struck at center slides for only about half a metre, so it is rolling when it meets #1, and its rolling distance d = v^2 / (2 mu_r g) stays well short of the end rail.",
                    stage3Badge = "Low-Speed Pocket Acceptance",
                    stage3Text = "Gentle contact minimizes cut-induced throw and widens the effective acceptance mouth of Foot Left as #1 heads toward the corner.",
                    stage4Badge = "Ideal Leave on #2",
                    stage4Text = "#1 drops at pocket speed and natural follow carries the cue ball forward into the open lane toward #2.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
                recommendedSpeedScale = 4,
            ).teaching(CueBallSpinPreset.FOLLOW),
            InteractiveTutorial(
                id = "tutorial-spin-physics",
                title = "Spin, Squirt, Swerve & Throw",
                category = "Cue Ball Spin",
                summary = "Understand how off-centre tip contact creates cue deflection (squirt), cloth curve (swerve), and collision throw.",
                keyTakeaways = listOf(
                    "Keep tip offsets within half the ball radius (0.50 R) to avoid miscues while accessing full spin range.",
                    "Side spin deflects the cue ball away from the tip side on launch, then curves back gently over distance.",
                    "Outside english counters cut-induced throw; inside english adds to cut throw and alters cushion rebound angles.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.92, 0.88)),
                        Ball(number = 1, position = Vec2(1.86, 0.36)),
                        Ball(number = 2, position = Vec2(1.12, 0.30)),
                        Ball(number = 10, position = Vec2(2.18, 0.96)),
                    ),
                ),
                practiceRulesState = GameRulesState(gameType = GameType.TEN_BALL),
                visualStages = buildVisualStages(
                    stage1Badge = "Right English (+0.28 R)",
                    stage1Text = "Inspect the ~1° shot on #1 into Foot Left: applying +0.28 R right english stays safely inside the 0.50 R miscue limit.",
                    stage2Badge = "Squirt & Swerve Aim",
                    stage2Text = "Tip-right contact squirts the cue ball slightly left off the stick before elevated-cue swerve bends it back toward the compensated ghost ball.",
                    stage3Badge = "Spin-Induced Collision Throw",
                    stage3Text = "At impact, right english imparts slight leftward friction throw on #1 while carrying active side spin onto the cue ball.",
                    stage4Badge = "Running Cushion Rebound",
                    stage4Text = "#1 pockets in Foot Left and the spinning cue ball widens its rebound off the foot cushion across toward #2.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.28, verticalRatio = 0.12),
                recommendedSpeedScale = 5,
            ),
            InteractiveTutorial(
                id = "tutorial-tangent-rules",
                title = "The 90\u00b0 and 30\u00b0 Tangent Rules",
                category = "Cue Ball Paths",
                summary = "Predict where the cue ball goes after contact: 90 degrees when it arrives with no spin (stun), ~30 degrees forward when it arrives rolling.",
                keyTakeaways = listOf(
                    "A cue ball that arrives sliding with no spin (stun) leaves close to the tangent line, about 90 degrees to the object ball's path and a few degrees inside it, immediately after impact. A center-ball hit is already rolling after about half a metre, so a true stun needs a tip below center that suits the distance and speed.",
                    "A naturally rolling cue ball deflects roughly 30 degrees forward along a wide range of medium cut angles.",
                    "Toggle follow, stun, and draw on this layout to steer clear of the Foot Right scratch pocket.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(1.10, 0.635)),
                        Ball(number = 1, position = Vec2(1.94, 0.59)),
                        Ball(number = 2, position = Vec2(2.12, 0.98)),
                        Ball(number = 9, position = Vec2(1.55, 1.06)),
                    ),
                ),
                practiceRulesState = GameRulesState(gameType = GameType.NINE_BALL),
                visualStages = buildVisualStages(
                    stage1Badge = "Scratch Danger on 90° Line",
                    stage1Text = "Notice how the stun line (close to the 90° tangent) off #1 toward Foot Left aims directly across the table into the Foot Right scratch pocket.",
                    stage2Badge = "Draw Selection (Below Center)",
                    stage2Text = "Strike well below center (Draw) at 6/10 speed so backspin is still on the cue ball when it reaches the ~44° cut ghost ball; at a softer speed it would wear off before contact.",
                    stage3Badge = "Bent Off the Tangent",
                    stage3Text = "After sending #1 into Foot Left, backspin curves the cue ball behind the stun line, away from Foot Right.",
                    stage4Badge = "Safe Shape on #2",
                    stage4Text = "#1 drops cleanly while the cue ball settles safely mid-table without scratching, leaving an open shot on #2.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset.fromPreset(CueBallSpinPreset.DRAW),
                recommendedSpeedScale = 6,
            ).teaching(CueBallSpinPreset.DRAW),
            InteractiveTutorial(
                id = "tutorial-pattern-heat-zones",
                title = "Pattern Play & Position Heat Zones",
                category = "Strategy",
                summary = "Plan 3+ shots backward from the 8-ball key ball and steer into the largest green position heat zone.",
                keyTakeaways = listOf(
                    "Work backward from the 8-ball to pick your key ball and insurance ball before clearing open shots.",
                    "Land in the centre of a wide heat zone where slight speed errors still leave a natural cut angle.",
                    "Remove problem balls or clusters early while you still have extra cue-ball routes available.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.68, 0.48)),
                        Ball(number = 2, position = Vec2(1.22, 0.28)),
                        Ball(number = 4, position = Vec2(1.78, 0.44)),
                        Ball(number = 6, position = Vec2(2.04, 0.86)),
                        Ball(number = 8, position = Vec2(2.24, 0.30)),
                        Ball(number = 12, position = Vec2(1.48, 0.92)),
                    ),
                ),
                practiceRulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.SOLIDS,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "3-Ball Heat-Zone Plan",
                    stage1Text = "Map #2 -> #4 -> #6 -> #8 backward: cutting #2 into Side Left lets the natural tangent drift straight into the green zone behind #4.",
                    stage2Badge = "Center Ball (4/10)",
                    stage2Text = "Strike center ball at 4/10 speed into the #2 ghost ball so post-impact speed lands softly in the widest heat-zone window.",
                    stage3Badge = "Tangent Leave Activation",
                    stage3Text = "As #2 travels into Side Left, the cue ball slides cleanly off the contact point toward the center-foot corridor.",
                    stage4Badge = "Center-Zone Landing on #4",
                    stage4Text = "#2 pockets and the cue ball stops right inside the target zone behind #4, preserving simple forward angles to #6 and the #8.",
                ),
                primaryTargetBallNumber = 2,
                primaryPocketId = PocketId.SIDE_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 4,
            ),
            InteractiveTutorial(
                id = "tutorial-safety-two-way",
                title = "Safety Play & Two-Way Shots",
                category = "Defense",
                summary = "When a clear runout is low-percentage, separate cue ball and object ball behind blockers or play two-way.",
                keyTakeaways = listOf(
                    "Measure safety quality by whether the opponent can see both edges of the legal ball on.",
                    "Prefer two-way shots that attempt a low-risk pot while sending the cue ball safely behind cover if missed.",
                    "Control object-ball speed just enough to reach a rail or open table without leaving an easy bank.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.66, 0.84)),
                        Ball(number = 1, position = Vec2(1.74, 0.26)),
                        Ball(number = 5, position = Vec2(1.18, 0.42)),
                        Ball(number = 9, position = Vec2(2.06, 0.62)),
                        Ball(number = 2, position = Vec2(2.18, 0.94)),
                    ),
                ),
                practiceRulesState = GameRulesState(gameType = GameType.NINE_BALL),
                visualStages = buildVisualStages(
                    stage1Badge = "Two-Way Risk Check",
                    stage1Text = "Evaluate #1 into Foot Left with #5 and #9 in mid-table: at pocket speed 3/10, even a tight miss sends #1 to a rail safely.",
                    stage2Badge = "Soft 3/10 Two-Way Touch",
                    stage2Text = "Play a soft center-ball stroke at 3/10 so the cue ball slows and settles near the foot-left quadrant without running wild.",
                    stage3Badge = "Controlled Contact & Separation",
                    stage3Text = "Soft ball-ball transfer sends #1 toward Foot Left while limiting post-impact cue-ball rebound speed.",
                    stage4Badge = "Safe Result or Clean Pot",
                    stage4Text = "#1 drops cleanly in Foot Left (or parks near the cushion if played thin), keeping distance and cover between balls.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 3,
            ),
            InteractiveTutorial(
                id = "tutorial-break-technique",
                title = "High-Energy Break Technique",
                category = "Break",
                summary = "Transfer clean square energy into the head ball while keeping the cue ball controlled near centre table.",
                keyTakeaways = listOf(
                    "Strike the cue ball slightly above or dead centre so it squats near the middle rather than flying off-table.",
                    "Prioritize square head-ball contact accuracy over raw swing speed that causes mis-hits.",
                    "Track your acoustic break speed in Tools & Coach to pair consistent speed with controlled cue-ball squat.",
                ),
                practiceLayout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.635, 0.46)),
                        Ball(number = 1, position = Vec2(1.905, 0.635)),
                        Ball(number = 2, position = Vec2(2.04, 0.48)),
                        Ball(number = 3, position = Vec2(2.16, 0.72)),
                        Ball(number = 9, position = Vec2(2.24, 0.56)),
                    ),
                ),
                practiceRulesState = GameRulesState(gameType = GameType.NINE_BALL),
                visualStages = buildVisualStages(
                    stage1Badge = "Head-Ball Alignment",
                    stage1Text = "Position behind the head string and lock onto the #1 lead ball; aim cleanly so energy drives into the target pocket line.",
                    stage2Badge = "High-Speed Squat Stroke (9/10)",
                    stage2Text = "Deliver a firm 9/10 stroke slightly above center (+0.10 R) to maintain balance and keep the cue ball on the cloth.",
                    stage3Badge = "Peak Energy Transfer",
                    stage3Text = "High-velocity contact launches #1 toward Foot Right while the cue ball rebounds off the ~37° cut.",
                    stage4Badge = "Post-Impact Cushion Rebound",
                    stage4Text = "#1 drops in Foot Right and the cue ball rebounds off the right cushion back toward mid-table opening shape.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_RIGHT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.10),
                recommendedSpeedScale = 9,
            ),
        )
    }

    /**
     * Returns 6 step-by-step interactive 8-Ball walkthrough scenarios covering open-table group
     * selection, key-ball backward planning, cluster breakouts, legal safeties, ball-in-hand
     * placement, and avoiding a tangent-line scratch on the 8-ball.
     */
    fun eightBallWalkthroughs(): List<EightBallWalkthrough> {
        val table = TableSpec.nineFootPool()
        return listOf(
            EightBallWalkthrough(
                id = "walkthrough-8ball-open-table-choice",
                title = "1. Post-Break Open Table: Choosing Solids vs. Stripes",
                situationSummary = "A stripe dropped on the break, leaving solids (#1, #2, #3) wide open with clear pocket lanes while stripes (#9, #10, #12, #14) are clustered near the side rail.",
                ruleApplied = "WPA Open Table Rule: Making a ball on the break does NOT lock groups. You may shoot any solid or stripe (even comboing across groups) until you legally make a called post-break pot.",
                stepByStepGuide = listOf(
                    "Scan both groups before aiming: notice solids #1, #2, and #3 all have open pockets whereas the stripes cluster needs a breakout.",
                    "Even though a stripe fell on the break, treat the table as Open and step onto solid #1.",
                    "Call \"#1 in Foot Right\" clearly and strike at medium speed (4/10) with a light follow stroke so the cue ball stays mid-table.",
                    "Once #1 drops legally into Foot Right, Solids are locked in and you have a natural next angle onto #2 or #3.",
                ),
                commonMistakeToAvoid = "Assuming you must shoot Stripes simply because a stripe dropped on the break—or forgetting to call the pocket in call-pocket leagues (WPA/BCAPL).",
                layout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.82, 0.56)),
                        Ball(number = 1, position = Vec2(1.78, 0.86)),
                        Ball(number = 2, position = Vec2(1.62, 0.34)),
                        Ball(number = 3, position = Vec2(2.05, 0.36)),
                        Ball(number = 8, position = Vec2(2.22, 0.64)),
                        Ball(number = 9, position = Vec2(1.30, 1.06)),
                        Ball(number = 10, position = Vec2(1.365, 1.05)),
                        Ball(number = 12, position = Vec2(1.335, 1.12)),
                        Ball(number = 14, position = Vec2(1.42, 1.11)),
                    ),
                ),
                rulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.OPEN,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "Open Table: Pick Solids",
                    stage1Text = "Stripes #9–#14 are tied up on the right rail while Solids #1–#3 are wide open. Call \"#1 in Foot Right\" to claim Solids.",
                    stage2Badge = "Light Follow (4/10)",
                    stage2Text = "Cue slightly above center (+0.10 R) at 4/10 speed down the ~11° cut line toward #1's Foot Right ghost ball.",
                    stage3Badge = "Pocket Call Locked on Pot",
                    stage3Text = "Solid contact sends #1 straight into Foot Right; once it drops legally, Solids become your locked group.",
                    stage4Badge = "Next-Ball Shape on #2/#3",
                    stage4Text = "With #1 pocketed, light topspin holds the cue ball in the lower-foot area for clean follow-up shots on #2 and #3.",
                ),
                primaryTargetBallNumber = 1,
                primaryPocketId = PocketId.FOOT_RIGHT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.10),
                recommendedSpeedScale = 4,
            ),
            EightBallWalkthrough(
                id = "walkthrough-8ball-key-ball-planning",
                title = "2. Working Backward from the 8-Ball: Selecting the Key Ball",
                situationSummary = "Solids #2, #4, and #6 remain alongside the #8 near the foot spot. Pot them in reverse-planned order (#2 -> #4 -> #6 -> #8) rather than grabbing whichever ball looks closest first.",
                ruleApplied = "Pattern Play & Call-Shot Sequencing: You must clear all seven group balls before calling and pocketing the #8 on a separate stroke.",
                stepByStepGuide = listOf(
                    "Start at the #8 (near the foot spot, aligned to Foot Right) and note the comfortable ~20° approach window around center-right cloth.",
                    "Pick #6 near Foot Left as your Key Ball: pocketing #6 with a gentle rolling stroke drifts the cue ball straight into that ideal ~20° angle on the #8.",
                    "Work backward one more step: #4 plays cleanly into Side Left and routes onto #6, while #2 plays into Side Right to feed #4.",
                    "Execute #2 -> #4 -> #6 -> #8 in sequence using controlled pocket speed (4–5/10) instead of forcing hard draw recoveries.",
                ),
                commonMistakeToAvoid = "Potting the easiest ball (#6) first without a bridge plan to the #8, leaving yourself forced into a blind rail-frozen cut on the last solid.",
                layout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.58, 0.48)),
                        Ball(number = 2, position = Vec2(0.84, 0.72)),
                        Ball(number = 4, position = Vec2(1.18, 0.36)),
                        Ball(number = 6, position = Vec2(1.74, 0.34)),
                        Ball(number = 8, position = Vec2(1.92, 0.64)),
                        Ball(number = 11, position = Vec2(0.52, 1.06)),
                        Ball(number = 14, position = Vec2(2.26, 0.42)),
                    ),
                ),
                rulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.SOLIDS,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "Key Ball = #6 -> #8",
                    stage1Text = "Work backward from #8 -> #6 -> #4 -> #2. Start with #2 into Side Right so the cue ball stays in mid-table for #4.",
                    stage2Badge = "Center Ball (4/10)",
                    stage2Text = "Align down the ~9° cut on #2 into Side Right with a calm 4/10 center-ball stroke so position stays under control.",
                    stage3Badge = "9° Ghost-Ball Impact",
                    stage3Text = "Crisp contact drops #2 smoothly toward Side Right while directing the cue ball off the right side pocket line.",
                    stage4Badge = "Route to #4 & #6 Key Ball",
                    stage4Text = "#2 pockets in Side Right and the cue ball rebounds off the right rail into open table to continue #4 -> #6 -> #8.",
                ),
                primaryTargetBallNumber = 2,
                primaryPocketId = PocketId.SIDE_RIGHT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 4,
            ),
            EightBallWalkthrough(
                id = "walkthrough-8ball-cluster-breakout-insurance",
                title = "3. Breaking Out a Tied-Up Ball Using an Insurance Ball",
                situationSummary = "Solid #3 sits tied up against opponent stripe #14. Solid #2 points its natural 90° tangent line directly into the cluster while wide-open solid #5 waits as your insurance ball.",
                ruleApplied = "Legal Contact & Early Breakout Timing: You only need to contact a solid first and pot or reach a rail; using an open insurance ball protects your inning if the breakout scatters awkwardly.",
                stepByStepGuide = listOf(
                    "Identify the problem ball (#3 frozen beside #14) and verify that #5 is wide open as an insurance ball—do NOT pot #5 before solving #3.",
                    "Call #2 into Foot Left and check the 90° stun tangent path from #2's ghost ball straight into the #3/#14 pair.",
                    "Strike #2 with a touch of low (below center) at 6/10 so the cue ball arrives with no spin (stun), #2 drops in Foot Left and the cue ball separates #3 from #14 cleanly.",
                    "If #3 lands hidden after the breakout, switch straight to your open insurance ball #5 to regain shape on #3 and then the #8.",
                ),
                commonMistakeToAvoid = "Potting your wide-open insurance ball (#5) first, which leaves no fallback shot if the #3 breakout kisses a stripe or stops on a rail.",
                layout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.92, 0.42)),
                        Ball(number = 2, position = Vec2(1.42, 0.56)),
                        Ball(number = 3, position = Vec2(1.54, 0.92)),
                        Ball(number = 5, position = Vec2(0.88, 0.92)),
                        Ball(number = 8, position = Vec2(2.14, 0.72)),
                        Ball(number = 10, position = Vec2(0.56, 0.28)),
                        Ball(number = 14, position = Vec2(1.60, 0.90)),
                    ),
                ),
                rulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.SOLIDS,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "Keep #5 as Insurance",
                    stage1Text = "#3 and #14 are frozen together. Leave #5 untouched as your safety net and use #2 into Foot Left to send the cue ball into the cluster.",
                    stage2Badge = "Firm Low-Tip Stun (6/10)",
                    stage2Text = "Strike just below center at 6/10 speed into #2's ghost ball (~42° cut) so the cue ball arrives with no spin and the 90° stun line aims squarely at #3/#14.",
                    stage3Badge = "90° Tangent Toward Cluster",
                    stage3Text = "At impact, #2 accelerates into Foot Left while the cue ball launches along the perpendicular tangent straight toward #3/#14.",
                    stage4Badge = "Breakout + Insurance Fallback",
                    stage4Text = "#2 pockets and the cue ball drives through the #3/#14 zone; even if #3 lands awkward, #5 remains wide open.",
                ),
                primaryTargetBallNumber = 2,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 6,
            ).teaching(CueBallSpinPreset.STUN),
            EightBallWalkthrough(
                id = "walkthrough-8ball-legal-safety-rail-rule",
                title = "4. Legal 8-Ball Safety: First Contact + Cushion Rule",
                situationSummary = "Your last stripe #12 has its natural pockets (Foot Right and Side Right) blocked by solids #3 and #5, leaving only a risky ~72° thin cut toward Foot Left.",
                ruleApplied = "WPA Legal Shot & Safety Rule: Hit your group (#12) first, and after contact at least one ball (object ball or cue ball) must pocket or touch a cushion—failing to reach a rail awards ball in hand.",
                stepByStepGuide = listOf(
                    "Recognize that forcing the thin ~72° cut on #12 into Foot Left carries high miss risk and could hand your opponent an easy runout.",
                    "Declare \"Safety\" aloud so your opponent knows you are playing defensively (even if #12 accidentally falls, your inning ends).",
                    "Strike #12 on its right edge at soft-medium speed (3/10) so #12 contacts the nearby foot/right cushion while the cue ball rolls behind mid-table blockers #2 and #4.",
                    "Confirm that either #12 or the cue ball hits a rail after impact so the safety is 100% legal and denies your opponent ball in hand.",
                ),
                commonMistakeToAvoid = "Softly tapping #12 and tucking the cue ball behind #2/#4 without sending any ball to a cushion—a common ball-in-hand foul in 8-Ball.",
                layout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.76, 0.86)),
                        Ball(number = 2, position = Vec2(1.38, 0.56)),
                        Ball(number = 3, position = Vec2(2.24, 1.16)),
                        Ball(number = 4, position = Vec2(1.44, 0.64)),
                        Ball(number = 5, position = Vec2(1.62, 1.15)),
                        Ball(number = 8, position = Vec2(0.96, 0.28)),
                        Ball(number = 12, position = Vec2(1.96, 1.04)),
                    ),
                ),
                rulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.STRIPES,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "Cushion Rule Required",
                    stage1Text = "Solids #3 and #5 guard Foot Right and Side Right for #12. Playing the ~69° thin cut into Foot Left safely requires hitting #12 first plus a rail.",
                    stage2Badge = "Soft 3/10 Safety Speed",
                    stage2Text = "Declare \"Safety\" and stroke softly (3/10) with center cue toward #12's thin edge—never under-hit short of a cushion.",
                    stage3Badge = "Thin ~69° Edge Contact",
                    stage3Text = "Cue ball grazes #12 first (legal group contact) and sends #12 toward the foot cushion while deflecting gently off the cut.",
                    stage4Badge = "Legal Rail & Distance Leave",
                    stage4Text = "Contact reaches the cushion cleanly, satisfying the WPA legal-shot rule without handing over ball in hand.",
                ),
                primaryTargetBallNumber = 12,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 3,
            ),
            EightBallWalkthrough(
                id = "walkthrough-8ball-ball-in-hand-placement",
                title = "5. Ball-in-Hand After a Foul: Manufacturing the Stop-Shot Line",
                situationSummary = "Your opponent fouled and left you ball in hand anywhere on the table with only solid #7 and the #8 remaining.",
                ruleApplied = "Post-Break Ball-in-Hand Rule: After any non-break foul in WPA/BCAPL/APA, place the cue ball anywhere on the playing surface (touching only with hand or cue shaft within legal placement rules).",
                stepByStepGuide = listOf(
                    "Look at the #8 first: its cleanest pocket is Foot Right, so imagine the straight-in extension line backward from Foot Right through #8 toward center table.",
                    "Rather than placing the cue ball directly behind #7 (which would force a long follow or awkward draw across the table), place the cue ball at (1.18, 0.32) m.",
                    "From that ball-in-hand angle (~28° half-ball cut on #7 into Foot Left), a clean stun stroke (a low-center tip, so no spin is left at contact) sends the cue ball along the 90° tangent straight onto the #8's Foot Right line.",
                    "Pot #7 at speed 4/10 with a low-center tip (stun at the ball) to land on a virtually straight-in #8 for the rack.",
                ),
                commonMistakeToAvoid = "Placing the cue ball dead straight behind #7 out of habit, which creates unnecessary follow/draw speed guesswork for position on the #8.",
                layout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(1.18, 0.32)),
                        Ball(number = 7, position = Vec2(1.74, 0.34)),
                        Ball(number = 8, position = Vec2(2.08, 0.86)),
                        Ball(number = 11, position = Vec2(0.64, 0.98)),
                        Ball(number = 15, position = Vec2(1.36, 1.06)),
                    ),
                ),
                rulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.SOLIDS,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "Place at ~25° Angle",
                    stage1Text = "With ball in hand, avoid placing dead straight behind #7; instead place at (1.18, 0.32) m so the natural stun line, close to 90°, points at the #8.",
                    stage2Badge = "Low-Center Stun (4/10)",
                    stage2Text = "Strike below center at 4/10 speed into #7's Foot Left ghost ball so the cue ball reaches it sliding with no spin (stun).",
                    stage3Badge = "90° Tangent Onto #8 Line",
                    stage3Text = "#7 heads cleanly into Foot Left while the cue ball peels off at 90° straight up toward the #8-to-Foot-Right line.",
                    stage4Badge = "Easy Straight-In #8",
                    stage4Text = "#7 drops and the cue ball settles directly behind the #8, turning the game ball into a routine pot into Foot Right.",
                ),
                primaryTargetBallNumber = 7,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
                recommendedSpeedScale = 4,
            ).teaching(CueBallSpinPreset.STUN),
            EightBallWalkthrough(
                id = "walkthrough-8ball-final-eight-avoid-scratch",
                title = "6. Pocketing the 8-Ball Without Scratching on the Tangent Line",
                situationSummary = "Only the #8 (plus two opponent stripes) remains. Cutting the #8 at 45° into Foot Left puts the stun line, close to the 90° tangent, into the opposite Foot Right corner pocket.",
                ruleApplied = "8-Ball Scratch Loss Rule: Pocketing the #8 while scratching the cue ball is an immediate loss of rack (whereas missing the #8 and scratching while it stays up is only a standard foul).",
                stepByStepGuide = listOf(
                    "Verify the call: clearly designate \"#8 in Foot Left\" before stepping into your stance.",
                    "Trace the stun path (close to the 90° tangent) before choosing spin: here a pure stun hit travels directly from the #8's ghost ball into Foot Right—an instant loss of game!",
                    "Select DRAW (backspin, tip well below center) at firm speed 6/10 to bend the cue ball backward off the tangent line toward center-right cloth.",
                    "Preview the Draw trajectory in the Table & Planner to confirm the cue ball clears Foot Right cleanly while the #8 drops into Foot Left.",
                ),
                commonMistakeToAvoid = "Hitting a 45° 8-ball cut with a spin-free cue ball without checking where the near-90° tangent line points—scratching on the made 8-ball loses the rack immediately.",
                layout = TableLayout(
                    table = table,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(1.10, 0.635)),
                        Ball(number = 8, position = Vec2(1.88, 0.57)),
                        Ball(number = 10, position = Vec2(0.62, 0.34)),
                        Ball(number = 13, position = Vec2(0.74, 1.04)),
                    ),
                ),
                rulesState = GameRulesState(
                    gameType = GameType.EIGHT_BALL,
                    eightBallGroup = EightBallGroup.EIGHT_BALL,
                ),
                visualStages = buildVisualStages(
                    stage1Badge = "No Spin = Foot Right Scratch!",
                    stage1Text = "Before shooting #8 into Foot Left, check the stun line (close to the 90° tangent): a cue ball that arrives with no spin walks straight into Foot Right, an instant rack loss.",
                    stage2Badge = "Draw (Below Center) at 6/10",
                    stage2Text = "Drop the tip well below center (Draw) at 6/10 speed and lock onto the Foot Left ghost ball so backspin activates at contact.",
                    stage3Badge = "Backspin Clears Scratch Pocket",
                    stage3Text = "At impact, #8 drives cleanly toward Foot Left while backspin pulls the cue ball sharply behind the 90° scratch line.",
                    stage4Badge = "Rack Won Safely",
                    stage4Text = "The #8 drops into Foot Left and the cue ball comes to rest safely in open mid-table well clear of Foot Right.",
                ),
                primaryTargetBallNumber = 8,
                primaryPocketId = PocketId.FOOT_LEFT,
                recommendedTipOffset = TipOffset.fromPreset(CueBallSpinPreset.DRAW),
                recommendedSpeedScale = 6,
            ).teaching(CueBallSpinPreset.DRAW),
        )
    }

    /**
     * The tip that gives the cue ball the spin state [state] names when it reaches the object ball, for this
     * layout's shot at its recommended speed; the setup's own tip if no tip within the miscue limit does. A
     * centre-ball hit is rolling after about half a metre, so a stun or draw lesson needs a tip below centre that
     * depends on the distance and the speed.
     */
    private fun impactStateTip(
        layout: TableLayout,
        targetBallNumber: Int,
        pocketId: PocketId,
        speedScale: Int,
        state: CueBallSpinPreset,
        fallback: TipOffset,
    ): TipOffset {
        val cue = layout.cueBall() ?: return fallback
        val target = layout.ballByNumber(targetBallNumber) ?: return fallback
        val aim = GhostBallAiming.computeAimGeometry(
            cueBall = cue,
            objectBall = target,
            pocket = layout.table.pocketById(pocketId),
            otherBalls = layout.balls.filter { it.number != cue.number && it.number != target.number },
            table = layout.table,
        )
        return SpinPhysics.tipForImpactState(
            preset = state,
            cueSpeedMps = PlayerProfile().speedForScale(speedScale),
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = layout.table,
        ) ?: fallback
    }

    private fun InteractiveTutorial.teaching(state: CueBallSpinPreset): InteractiveTutorial = copy(
        recommendedTipOffset = impactStateTip(
            practiceLayout,
            primaryTargetBallNumber,
            primaryPocketId,
            recommendedSpeedScale,
            state,
            recommendedTipOffset,
        ),
    )

    private fun EightBallWalkthrough.teaching(state: CueBallSpinPreset): EightBallWalkthrough = copy(
        recommendedTipOffset = impactStateTip(
            layout,
            primaryTargetBallNumber,
            primaryPocketId,
            recommendedSpeedScale,
            state,
            recommendedTipOffset,
        ),
    )

    private fun buildVisualStages(
        stage1Badge: String,
        stage1Text: String,
        stage2Badge: String,
        stage2Text: String,
        stage3Badge: String,
        stage3Text: String,
        stage4Badge: String,
        stage4Text: String,
    ): List<WalkthroughVisualStage> = listOf(
        WalkthroughVisualStage(
            stageNumber = 1,
            stageTitle = "Stage 1: Table Setup & Assessment",
            explanationText = stage1Text,
            calloutBadge = stage1Badge,
            frameProgress = 0.0,
        ),
        WalkthroughVisualStage(
            stageNumber = 2,
            stageTitle = "Stage 2: Aim Line & Cue Strike",
            explanationText = stage2Text,
            calloutBadge = stage2Badge,
            frameProgress = 0.20,
        ),
        WalkthroughVisualStage(
            stageNumber = 3,
            stageTitle = "Stage 3: Ghost-Ball Impact & Throw",
            explanationText = stage3Text,
            calloutBadge = stage3Badge,
            frameProgress = 0.45,
        ),
        WalkthroughVisualStage(
            stageNumber = 4,
            stageTitle = "Stage 4: Cushion Rebound & Next-Ball Shape",
            explanationText = stage4Text,
            calloutBadge = stage4Badge,
            frameProgress = 1.0,
        ),
    )

    /**
     * Returns 5 scored practice drills with attempt targets and playable table layouts.
     */
    fun allDrills(): List<PracticeDrillSpec> {
        val poolTable = TableSpec.nineFootPool()
        return listOf(
            PracticeDrillSpec(
                id = "drill-lag-speed",
                title = "Lag Speed Control",
                targetSkill = "Speed Control",
                attemptsPerSet = 10,
                targetSuccessCount = 7,
                instructions = "Lag or pocket-speed stroke the cue ball from behind the head string to finish within one ball width of the foot cushion or target zone.",
                drillLayout = TableLayout(
                    table = poolTable,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.635, 0.46)),
                        Ball(number = 1, position = Vec2(1.82, 0.72)),
                        Ball(number = 9, position = Vec2(2.24, 0.56)),
                    ),
                ),
                drillRulesState = GameRulesState(gameType = GameType.NINE_BALL),
            ),
            PracticeDrillSpec(
                id = "drill-stop-shot",
                title = "Stop-Shot Precision",
                targetSkill = "Stun & Cue Control",
                attemptsPerSet = 10,
                targetSuccessCount = 8,
                instructions = "Pot the straight-in 1-ball into Foot Right and freeze the cue ball within one ball radius of the 1-ball's starting spot.",
                drillLayout = TableLayout(
                    table = poolTable,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(1.10, 0.45)),
                        Ball(number = 1, position = Vec2(1.82, 0.86)),
                        Ball(number = 2, position = Vec2(1.45, 1.02)),
                    ),
                ),
                drillRulesState = GameRulesState(gameType = GameType.NINE_BALL),
            ),
            PracticeDrillSpec(
                id = "drill-half-ball-cut",
                title = "Half-Ball Cut Accuracy",
                targetSkill = "30\u00b0 Cut Aiming",
                attemptsPerSet = 10,
                targetSuccessCount = 7,
                instructions = "Pocket the medium-cut 1-ball cleanly into Foot Left while using natural roll to hold position in the lower half for the 9-ball.",
                drillLayout = TableLayout(
                    table = poolTable,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.85, 0.76)),
                        Ball(number = 1, position = Vec2(1.78, 0.74)),
                        Ball(number = 9, position = Vec2(2.16, 0.36)),
                    ),
                ),
                drillRulesState = GameRulesState(gameType = GameType.NINE_BALL),
            ),
            PracticeDrillSpec(
                id = "drill-4ball-ghost",
                title = "9-Ball 4-Ball Ghost Runout",
                targetSkill = "Multi-Ball Pattern Play",
                attemptsPerSet = 5,
                targetSuccessCount = 3,
                instructions = "Run balls 1, 2, 3, and 9 in rotation from ball in hand without scratching or touching another ball out of sequence.",
                drillLayout = TableLayout(
                    table = poolTable,
                    balls = listOf(
                        Ball(number = 0, position = Vec2(0.66, 0.42)),
                        Ball(number = 1, position = Vec2(1.18, 0.78)),
                        Ball(number = 2, position = Vec2(1.72, 0.34)),
                        Ball(number = 3, position = Vec2(2.04, 0.92)),
                        Ball(number = 9, position = Vec2(2.28, 0.54)),
                    ),
                ),
                drillRulesState = GameRulesState(gameType = GameType.NINE_BALL),
            ),
            PracticeDrillSpec(
                id = "drill-snooker-lineup",
                title = "Snooker Line-Up Break Builder",
                targetSkill = "Red-Colour Alternation",
                attemptsPerSet = 5,
                targetSuccessCount = 3,
                instructions = "Clear alternating reds and re-spotted high-value colours (Black/Pink) on the full-size 12-ft snooker layout without losing position.",
                drillLayout = sampleSnookerBreakLayout(),
                drillRulesState = GameRulesState(gameType = GameType.EIGHT_BALL),
            ),
        )
    }

    /**
     * Returns starter historical practice log entries for display in the Practice Log card.
     */
    fun defaultPracticeLogs(): List<PracticeLogEntry> = listOf(
        PracticeLogEntry(
            id = "log-1",
            drillId = "drill-stop-shot",
            drillTitle = "Stop-Shot Precision",
            dateIso = "2026-11-10",
            successes = 7,
            attempts = 10,
            notes = "Clean stun at medium speed; slight drift on longer pots.",
        ),
        PracticeLogEntry(
            id = "log-2",
            drillId = "drill-half-ball-cut",
            drillTitle = "Half-Ball Cut Accuracy",
            dateIso = "2026-11-12",
            successes = 6,
            attempts = 10,
            notes = "Focused on ghost-ball alignment before stepping down.",
        ),
        PracticeLogEntry(
            id = "log-3",
            drillId = "drill-lag-speed",
            drillTitle = "Lag Speed Control",
            dateIso = "2026-11-15",
            successes = 8,
            attempts = 10,
            notes = "Consistent roll-up pace on fast worsted cloth.",
        ),
        PracticeLogEntry(
            id = "log-4",
            drillId = "drill-4ball-ghost",
            drillTitle = "9-Ball 4-Ball Ghost Runout",
            dateIso = "2026-11-17",
            successes = 4,
            attempts = 5,
            notes = "Used centre-follow angle from 2 to 3.",
        ),
    )

    /**
     * Returns a full-size 12-ft snooker layout (`TableSpec.fullSizeSnooker()`) with cue ball (`0`),
     * 3 reds (`1`, `8`, `9`) near the pyramid/pink-black zone, and all 6 colours (`2..7`) on their
     * official WPBSA spots with a high-percentage red pot open into `FOOT_RIGHT`.
     */
    fun sampleSnookerBreakLayout(): TableLayout {
        val table = TableSpec.fullSizeSnooker()
        val r = table.ballRadiusMeters
        return TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(2.82, 0.98), radiusMeters = r),
                Ball(number = 1, position = Vec2(3.05, 1.24), radiusMeters = r),
                Ball(number = 8, position = Vec2(2.84, 0.72), radiusMeters = r),
                Ball(number = 9, position = Vec2(3.12, 0.54), radiusMeters = r),
                Ball(number = 2, position = SnookerSpots.spotForColour(SnookerBallType.YELLOW, table), radiusMeters = r),
                Ball(number = 3, position = SnookerSpots.spotForColour(SnookerBallType.GREEN, table), radiusMeters = r),
                Ball(number = 4, position = SnookerSpots.spotForColour(SnookerBallType.BROWN, table), radiusMeters = r),
                Ball(number = 5, position = SnookerSpots.spotForColour(SnookerBallType.BLUE, table), radiusMeters = r),
                Ball(number = 6, position = SnookerSpots.spotForColour(SnookerBallType.PINK, table), radiusMeters = r),
                Ball(number = 7, position = SnookerSpots.spotForColour(SnookerBallType.BLACK, table), radiusMeters = r),
            ),
        )
    }

    /**
     * Returns 3 preset break-audio amplitude envelopes at 2000 Hz representing a power break,
     * a controlled 9-ball break, and a soft 8-ball break.
     */
    fun sampleBreakAudioPresets(): List<BreakAudioPreset> = listOf(
        BreakAudioPreset(
            id = "power_break_9ft",
            title = "Power Break (9-ft)",
            subtitle = "High-energy 9-ball break (~10.4 m/s / 23.4 mph) • Δt ≈ 126 ms",
            envelopeSampleRateHz = ENVELOPE_SAMPLE_RATE_HZ,
            envelopeAmplitudes = buildTwoSpikeEnvelope(
                cueStrikeIndex = 80,
                rackImpactIndex = 332,
                cuePeak = 0.88,
                rackPeak = 0.96,
            ),
            breakDistanceMeters = BreakSpeedEstimator.NINE_FOOT_DEFAULT_BREAK_DISTANCE_METERS,
            micToCueBallDistanceMeters = 0.60,
            micToRackDistanceMeters = 1.65,
            slidingFrictionMuS = 0.20,
        ),
        BreakAudioPreset(
            id = "controlled_9ball_break",
            title = "Controlled 9-Ball Break",
            subtitle = "Square squat break (~8.2 m/s / 18.4 mph) • Δt ≈ 160 ms",
            envelopeSampleRateHz = ENVELOPE_SAMPLE_RATE_HZ,
            envelopeAmplitudes = buildTwoSpikeEnvelope(
                cueStrikeIndex = 80,
                rackImpactIndex = 400,
                cuePeak = 0.82,
                rackPeak = 0.92,
            ),
            breakDistanceMeters = BreakSpeedEstimator.NINE_FOOT_DEFAULT_BREAK_DISTANCE_METERS,
            micToCueBallDistanceMeters = 0.55,
            micToRackDistanceMeters = 1.60,
            slidingFrictionMuS = 0.18,
        ),
        BreakAudioPreset(
            id = "soft_8ball_break",
            title = "Soft 8-Ball Break",
            subtitle = "Controlled second-ball / soft break (~6.1 m/s / 13.6 mph) • Δt ≈ 220 ms",
            envelopeSampleRateHz = ENVELOPE_SAMPLE_RATE_HZ,
            envelopeAmplitudes = buildTwoSpikeEnvelope(
                cueStrikeIndex = 80,
                rackImpactIndex = 520,
                cuePeak = 0.74,
                rackPeak = 0.86,
            ),
            breakDistanceMeters = BreakSpeedEstimator.NINE_FOOT_DEFAULT_BREAK_DISTANCE_METERS,
            micToCueBallDistanceMeters = 0.65,
            micToRackDistanceMeters = 1.70,
            slidingFrictionMuS = 0.20,
        ),
    )

    /**
     * Runs transient impact detection and acoustic/friction break-speed estimation on [preset].
     */
    fun analyzeBreakPreset(
        preset: BreakAudioPreset,
    ): Pair<BreakAudioEnvelopeResult, BreakSpeedEstimate>? {
        val envelopeResult = BreakSpeedEstimator.detectBreakImpactsFromEnvelope(
            envelopeAmplitudes = preset.envelopeAmplitudes,
            envelopeSampleRateHz = preset.envelopeSampleRateHz,
        ) ?: return null
        val speedEstimate = BreakSpeedEstimator.estimateBreakSpeed(
            rawDeltaTimeSeconds = envelopeResult.rawDeltaTimeSeconds,
            breakDistanceMeters = preset.breakDistanceMeters,
            micToCueBallDistanceMeters = preset.micToCueBallDistanceMeters,
            micToRackDistanceMeters = preset.micToRackDistanceMeters,
            slidingFrictionMuS = preset.slidingFrictionMuS,
        )
        return envelopeResult to speedEstimate
    }

    private fun buildTwoSpikeEnvelope(
        cueStrikeIndex: Int,
        rackImpactIndex: Int,
        cuePeak: Double,
        rackPeak: Double,
    ): DoubleArray {
        val amplitudes = DoubleArray(ENVELOPE_SAMPLE_COUNT) { idx ->
            0.010 + 0.004 * sin(idx * 0.37)
        }
        stampTransient(amplitudes, cueStrikeIndex, cuePeak, decaySamples = 4.2)
        stampTransient(amplitudes, rackImpactIndex, rackPeak, decaySamples = 5.0)
        return amplitudes
    }

    private fun stampTransient(
        buffer: DoubleArray,
        peakIndex: Int,
        peakAmplitude: Double,
        decaySamples: Double,
    ) {
        if (peakIndex - 1 in buffer.indices) {
            buffer[peakIndex - 1] = (peakAmplitude * 0.09).coerceAtLeast(buffer[peakIndex - 1])
        }
        for (offset in 0 until 20) {
            val idx = peakIndex + offset
            if (idx !in buffer.indices) break
            val value = peakAmplitude * exp(-offset.toDouble() / decaySamples)
            if (value > buffer[idx]) {
                buffer[idx] = value
            }
        }
    }
}
