# ADR 0004: Snooker red-colour break-building & baulk-safety planner, acoustic break-speed estimation, and interactive tutorial layouts

- **Date:** 2026-11-17
- **Status:** Accepted

## Context

Milestone 5 (`docs/roadmap.md` and `docs/research/rules-and-content.md`) completes Runout's roadmap with four capabilities:
1. **Snooker strategy (break building and safeties):** Unlike pool where balls stay pocketed and any group ball can follow another, snooker alternates between reds (`1 pt`, removed when potted) and colours (`2–7 pts`, re-spotted onto fixed spots `[Yellow, Green, Brown, Blue, Pink, Black]` while reds remain, then cleared in ascending order `2..7`). Furthermore, when no high-percentage pot + position exists or when trailing by more than the points remaining on the table, **safety play** (returning the cue ball to baulk while leaving the opponent long or snookered) dominates snooker decision-making.
2. **Break speed from the microphone:** Timing the acoustic gap $\Delta t = t_{\text{rack}} - t_{\text{tip}}$ between the cue tip striking the cue ball and the cue ball striking the rack apex measures break speed without external radar hardware. However, acoustic propagation delay and sliding cloth deceleration during the flight must be accounted for to recover true launch speed $v_0$.
3. **Rule variants (UK 8-Ball Blackball, APA/BCAPL):** Players need searchable coverage of UK 8-ball (blackball: reds/yellows, free shot after a foul, baulk-zone ball in hand) alongside WPA, APA, BCAPL, and WPBSA rules.
4. **Interactive tutorials, drills, scorekeeper, and shot clock:** Each tutorial and practice drill must link directly into a playable `TableLayout` so players can test the concept immediately on the table canvas.

## Decision

We implement Milestone 5 across `:engine` (`com.runout.engine.snooker` and `com.runout.engine.tools`) and `:composeApp` (`com.runout.app.tools`, `com.runout.app.rules`, and `com.runout.app.ui`):

1. **`SnookerStrategyPlanner.kt` (`com.runout.engine.snooker`):**
   - Models the exact WPBSA spot coordinates (`yellowSpot`, `greenSpot`, `brownSpot`, `blueSpot`, `pinkSpot`, `blackSpot`, and `baulkLineX = 0.737 m`) on `TableSpec.fullSizeSnooker()`.
   - Implements `SnookerScoreState` (`pointsRemainingOnTable`, `scoreDifferential`, `snookersRequiredCount`, `isFrameBall`).
   - Implements `SnookerStrategyPlanner`:
     - **Break-Building Planner (`planBreakSequence`):** Alternates `RED -> COLOUR (re-spotted) -> RED -> COLOUR` (or endgame `2 -> 3 -> 4 -> 5 -> 6 -> 7`), weighting each candidate colour by both expected points (`value * P(make)`) and position value on the next red (`HeatZoneMap` / angle to the red pack).
     - **Safety Evaluator (`recommendSafetyShots`):** Scores containment/snooker safety shots by: (a) cue-ball final proximity to the baulk cushion (`x in [r, baulkLineX]`), (b) separation distance between cue ball and remaining reds, and (c) occlusion/snooker behind a colour ball (`opponentBestPotProbability`).
     - Recommends whether to **Attack (Break-Build)** or **Play Safe** based on current pot `P(make)` vs. safety containment value and frame score state.
2. **`BreakSpeedEstimator.kt` (`com.runout.engine.tools`):**
   - Detects the cue-strike transient $t_{\text{tip}}$ and rack-impact transient $t_{\text{rack}}$ from an envelope-thresholded PCM audio buffer (`detectBreakImpactsFromEnvelope`).
   - Corrects raw time-of-flight $\Delta t_{\text{audio}}$ for microphone acoustic travel-time differential $\Delta t_{\text{flight}} = \Delta t_{\text{audio}} - \frac{d_{\text{mic,rack}} - d_{\text{mic,cue}}}{c_{\text{sound}}}$ ($c_{\text{sound}} = 343.0\text{ m/s}$), and adds the sliding-friction speed loss $\frac{1}{2} \mu_s g \Delta t_{\text{flight}}$ over travel distance $D$ (`estimateBreakSpeed`):
     $$v_0 = \frac{D}{\Delta t_{\text{flight}}} + \frac{1}{2} \mu_s g \, \Delta t_{\text{flight}}$$
3. **`PracticeToolsContract.kt` (`com.runout.app.tools`), `TutorialsAndDrills.kt`, & `RulesContent.kt`:**
   - Adds `expect`/`actual` `PlatformBreakAudioController` (in-memory PCM only, zero audio file persistence), `MatchScorekeeperState` (Pool races + Snooker scoreboard), `ShotClockState` (with extension tracking), `PracticeLogStore`, interactive tutorials & drills that load directly into `Table & Planner`, and `RulesGame.BLACKBALL` (UK 8-Ball) + APA/BCAPL rule comparisons.

## Consequences

- **Complete Roadmap Coverage (Milestones 0–5):** Every milestone in `docs/roadmap.md` is implemented in pure KMP `:engine` and `:composeApp` with deterministic JVM unit tests.
- **Privacy & Offline Guarantee Preserved:** Microphone break-speed analysis operates strictly on transient PCM buffers in memory without recording or saving voice/audio files.
