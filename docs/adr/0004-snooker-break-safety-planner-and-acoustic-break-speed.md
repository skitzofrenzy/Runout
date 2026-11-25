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
   - `SnookerSpots` holds the WPBSA spot coordinates and the baulk line (`BAULK_LINE_X_METERS = 0.737 m`, `D_RADIUS_METERS`); `SnookerSpots.spotForColour` returns the spot of each colour on a table, scaled proportionally when the table is not the full-size `TableSpec.fullSizeSnooker()` surface.
   - Implements `SnookerScoreState` (`pointsRemainingOnTable`, `scoreDifferential`, `needsSnookers`, `snookersRequiredCount`, `isFrameBallAvailable`).
   - Implements `SnookerStrategyPlanner`, whose public entry point `evaluateSnookerPosition` returns a `SnookerTacticalAdvice`:
     - **Break-building search (private `planBreakSequence`):** a beam search over up to `horizonShots` shots (`1..5`, default 3) that follows the frame state: a red, then a colour that is re-spotted while reds remain, then the colours in ascending order (`2..7`) once the reds are gone. Each candidate is ranked by the expected points of the break so far (`P(make)` accumulated along the line times the point value) plus a leave bonus for the best next pot from the cue-ball stop position (`HeatZoneGenerator.shotQuality`).
     - **Safety evaluator (private `findBestSafety`):** scores baulk-return safety shots, returned as a `SnookerSafetyRecommendation`, by the opponent's estimated best pot probability after the shot (`opponentEstimatedPotProbability`), the cue ball's final proximity to the baulk line, how far the target ball is left from baulk and the cue-to-target separation, with penalties for a blocked return path, a scratch, an unintended pot and a target left close to a pocket.
     - **Tactical mode (`SnookerTacticalMode`):** `PLAY_FOR_SNOOKERS` when the deficit exceeds the points left on the table (`needsSnookers`); `ATTACK_BREAK_BUILD` when the first break step has a make probability of at least 0.52; otherwise `CONTAINMENT_SAFETY`.
2. **`BreakSpeedEstimator.kt` (`com.runout.engine.tools`):**
   - Detects the cue-strike transient $t_{\text{tip}}$ and rack-impact transient $t_{\text{rack}}$ from an envelope-thresholded PCM audio buffer (`BreakSpeedEstimator.detectBreakImpactsFromEnvelope`).
   - Corrects raw time-of-flight $\Delta t_{\text{audio}}$ for microphone acoustic travel-time differential $\Delta t_{\text{flight}} = \Delta t_{\text{audio}} - \frac{d_{\text{mic,rack}} - d_{\text{mic,cue}}}{c_{\text{sound}}}$ ($c_{\text{sound}} = 343.0\text{ m/s}$), and adds the sliding-friction speed loss $\frac{1}{2} \mu_s g \Delta t_{\text{flight}}$ over travel distance $D$ (`BreakSpeedEstimator.estimateBreakSpeed`, which also reports the speed at impact):
     $$v_0 = \frac{D}{\Delta t_{\text{flight}}} + \frac{1}{2} \mu_s g \, \Delta t_{\text{flight}}$$
3. **`PracticeToolsContract.kt` (`com.runout.app.tools`) and `RulesContent.kt` (`com.runout.app.rules`):**
   - Adds `expect`/`actual` `PlatformBreakAudioController` (designed for in-memory PCM only, with no audio files; the current implementations return bundled sample presets and do not open a microphone), `PoolRaceScoreState` (the pool race scoreboard; the snooker scoreboard uses the engine's `SnookerScoreState`), `ShotClockLiveState` with `ShotClockConfig` (extension tracking per rack), `PracticeLogEntry` (the drill log, held in memory for the session and seeded from `TutorialsAndDrillsCatalog.defaultPracticeLogs()`; it is not saved to storage), the `TutorialsAndDrillsCatalog` of interactive tutorials and drills that load directly into the Plan tab, and `RulesGame.BLACKBALL` (UK 8-Ball) alongside the APA and BCAPL rule comparisons.

## Consequences

- **Roadmap coverage (Milestones 0–5):** Every milestone in `docs/roadmap.md` is built in pure KMP `:engine` and `:composeApp` with deterministic unit tests. Built is not validated: the accuracy targets that need real photos or a physical device are listed in the handover and the roadmap.
- **Privacy & Offline Guarantee Preserved:** Microphone break-speed analysis is designed to operate strictly on transient PCM buffers in memory without recording or saving voice/audio files.
