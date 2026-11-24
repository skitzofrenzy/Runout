# Rules and content

Planned content for the rules and FAQ section, the tutorials and the extra tools, and where it comes from.

## Rule sets

| Game | Primary source | Variants to cover |
|---|---|---|
| 8-ball | WPA World Standardized Rules | League rules (such as APA and BCAPL), common bar rules, UK eight-ball (blackball) |
| 9-ball | WPA World Standardized Rules | Three-foul rule, push-out, winner breaks or alternate break |
| 10-ball | WPA World Standardized Rules | Call-shot details |
| Snooker | WPBSA Official Rules of the Games of Snooker and English Billiards | – |

Rules change. Every entry records its source document, the version or date of that document, and when it was last checked.

## Licensing

Official rulebooks are copyrighted. Runout will:

- write original, plain-language summaries rather than copying rule text;
- link to the official source for each rule;
- include full official text only with written permission.

## Format

- One Markdown file per rule topic in the repository (for example `content/rules/9-ball/push-out.md`), with front matter for the game, section, tags, source, source version and last-checked date.
- The files are bundled into the app at build time and indexed with SQLite full-text search (Room supports FTS4), so search works offline.
- Search covers rules, FAQ and tutorials, with synonyms (for example "scratch" and "cue ball in the pocket").

## FAQ ideas

- What counts as a foul in each game? Where can I place the cue ball with ball in hand?
- What is a push-out, and when can I use it?
- What happens if the 8 goes in on the break?
- Snooker: what is a free ball? What is a miss? How are fouls scored?
- What happens after three fouls in a row?

## Tutorials

- Aiming basics: ghost ball, contact point, fractional aiming.
- Stance, bridge and stroke.
- Speed control.
- Spin: follow, draw, stun and side spin, and how squirt, swerve and throw work.
- Cue ball paths: the 90° and 30° rules.
- Pattern play and position zones (linked to the heat zones).
- Safety play and kicking.
- The break in each game.

Each tutorial links into the shot-line view, so players can try the idea on a sample layout.

## Other tools

| Tool | Notes |
|---|---|
| Scorekeeper | Races for 8, 9 and 10-ball; snooker scoreboard with points remaining |
| Shot clock | With extensions, set per rule set |
| Practice log | Drills, success rates and progress charts |
| Drill library | Standard practice drills with scoring |
| Break speed | The microphone times the gap between the cue strike and the cue ball hitting the rack; with the known (or entered) distance, that gives the break speed |
| Equipment notes | Cue, tip, shaft and ball set, per profile |

## Fair play

The app states clearly that it is a practice tool, and that most leagues and tournaments ban electronic aids during matches.

## Implemented in `:engine` and `:composeApp`

See [ADR-0004](../adr/0004-snooker-break-safety-planner-and-acoustic-break-speed.md) for the Milestone 5 architecture decisions implemented across `engine/src/commonMain/kotlin/com/runout/engine/snooker/`, `engine/src/commonMain/kotlin/com/runout/engine/tools/`, and `composeApp/src/commonMain/kotlin/com/runout/app/{rules,tools,ui}/`:

### 1. 20-Entry Offline Rule Repository & Synonym Search (`RulesContent.kt`, `RulesSearchEngine.kt`)

- **Coverage across 5 `RulesGame` sets (`RulesRepository.allEntries`, 20 entries):**
  - `EIGHT_BALL` (`8-Ball`, 7 entries): WPA break, open table, legal shot/safety, ball-in-hand fouls, 8-ball call/loss rules, 8-on-the-break, plus `eight-ball-apa-vs-bcapl-variants` comparing WPA/BCAPL call-pocket and ball-in-hand rules against APA (only calling the 8-ball, clean 8-on-the-break wins) and informal bar rules.
  - `BLACKBALL` (`UK 8-Ball (Blackball)`, 3 entries): `blackball-groups-and-break` (reds vs. yellows, baulk break, open table until legal post-break nomination/pot), `blackball-free-shot-on-foul` (free shot + regular visit, baulk cue-ball placement after in-off), and `blackball-8ball-and-snookers` (non-call-pocket black pot, cushion/pot legality, touching-ball escape), attributing `WPA Blackball Rules` (`https://wpapool.com/rules/`, `lastCheckedDate = "2026-11-12"`).
  - `NINE_BALL` (`9-Ball`, 4 entries), `TEN_BALL` (`10-Ball`, 3 entries), and `SNOOKER` (`Snooker`, 3 entries) with original plain-language summaries and WPA / WPBSA links.
- **Concrete `"On-Table Example"` callouts (`RuleEntry.exampleScenario`):** Every one of the 20 `RuleEntry` items includes a vivid, table-concrete `exampleScenario` rendered inside a highlighted `"On-Table Example"` box in `RuleEntryCard` (covering cases such as dropping the #11 stripe on an open 8-ball break, uncalled pockets under WPA vs. APA, safeties failing to reach a cushion, and scratching while pocketing the 8-ball vs. missing it).
- **Offline bidirectional synonym search (`RulesSearchEngine.kt`):** Expands 15 normalized synonym groups alongside field-weighted scoring (`title` / `keywords` > `summary` > `plainAnswer`), including `{"blackball", "uk 8 ball", "free shot", "two visits"}` and `{"apa", "bcapl", "bar rules"}`.

### 2. Interactive 8-Ball Walkthroughs, Tutorials, Scored Drills, Match Tools & Practice Log (`PracticeToolsContract.kt`, `RulesAndProfileScreens.kt`, `ExtrasAndToolsScreen.kt`)

- **6 interactive 8-Ball walkthroughs (`EightBallWalkthrough`, `TutorialsAndDrillsCatalog.eightBallWalkthroughs()`):** Pairs rule application, numbered step-by-step tactical guidance, common pitfalls to avoid, and playable non-overlapping `TableLayout` + `GameRulesState` presets across six core 8-Ball situations:
  1. `walkthrough-8ball-open-table-choice` (`EightBallGroup.OPEN`): Post-break open table group choice when a stripe drops but solids `#1–#3` are wide open.
  2. `walkthrough-8ball-key-ball-planning` (`EightBallGroup.SOLIDS`): Working backward `#8 -> #6 (Key Ball) -> #4 -> #2` to leave a natural ~20° angle onto the 8-ball.
  3. `walkthrough-8ball-cluster-breakout-insurance` (`EightBallGroup.SOLIDS`): Breaking out tied-up `#3` along the 90° tangent off `#2` while keeping `#5` open as an insurance ball.
  4. `walkthrough-8ball-legal-safety-rail-rule` (`EightBallGroup.STRIPES`): Calling a legal safety on blocked stripe `#12`, hitting `#12` first, reaching a cushion, and tucking behind blockers `#2`/`#4`.
  5. `walkthrough-8ball-ball-in-hand-placement` (`EightBallGroup.SOLIDS`): Using ball-in-hand placement to manufacture a ~28° stun line on `#7` onto a straight-in `#8`.
  6. `walkthrough-8ball-final-eight-avoid-scratch` (`EightBallGroup.EIGHT_BALL`): Recognizing a 90° stun scratch on the `#8` into `FOOT_RIGHT` and switching to `DRAW` at controlled speed `4/10` to hold center-table safely.
  - Displayed via the shared `EightBallWalkthroughCard` inside both `RulesAndFaqScreen` (**"8-Ball Walkthroughs & Interactive Examples"** with `"Load Example on Table"` when viewing All, 8-Ball, or Blackball) and `ExtrasAndToolsScreen` (**"8-Ball Walkthroughs"** filter chip/card with `"Load Walkthrough on Table"`).
- **8 interactive tutorials (`TutorialsAndDrillsCatalog.allTutorials()`):** Covers *Aiming Basics & Ghost Ball*, *Stance, Bridge & Stroke Alignment*, *Cue-Ball Speed Control*, *Spin, Squirt, Swerve & Throw*, *The 90° and 30° Tangent Rules*, *Pattern Play & Position Heat Zones*, *Safety Play & Two-Way Shots*, and *High-Energy Break Technique*. Every tutorial carries a valid non-overlapping `TableLayout` and `GameRulesState` that loads directly into the `Table & Planner` workspace via **"Try on Table Planner"**.
- **5 scored drills & Practice Log (`TutorialsAndDrillsCatalog.allDrills()`, `PracticeLogEntry`):** *Lag Speed Control*, *Stop-Shot Precision*, *Half-Ball Cut Accuracy*, *9-Ball 4-Ball Ghost Runout*, and *Snooker Line-Up Break Builder*, paired with an interactive Practice Log computing `successRateFraction` and overall historical completion percentage across logged sessions.
- **Scorekeeper, Shot Clock & Transient Audio Bridge:** `PoolRaceScoreState` tracks races to 5/7/9 with leader and hill/double-hill detection; the Snooker frame & break counter tracks `+1 Red`..`+7 Black` and `+4 Foul`; `ShotClockLiveState` implements `35 s` standard / `60 s` break-or-push-out countdowns (`10 s` warning threshold) with once-per-rack `+30 s` P1/P2 extensions; and `expect class PlatformBreakAudioController` (`Android AudioRecord PCM (In-Memory)` / `iOS AVAudioEngine PCM (In-Memory)`) is designed to work strictly in memory (`processesTransientPcmInMemoryOnly = true`); both implementations currently return bundled sample presets and do not open a microphone.

### 3. Snooker Break-Building & Baulk-Return Safety Planner (`SnookerStrategyPlanner.kt`)

- **WPBSA Spots & Score Math (`SnookerSpots`, `SnookerScoreState`):** Places all 6 colours (`Yellow = 2`, `Green = 3`, `Brown = 4`, `Blue = 5`, `Pink = 6`, `Black = 7`) using WPBSA coordinates (`BAULK_LINE_X_METERS = 0.737 m`, `D_RADIUS_METERS = 0.292 m`, proportionally scaled to `TableSpec`), computes `pointsRemainingOnTable` (`147` maximum with 15 reds + 27 endgame colours), `needsSnookers`, `snookersRequiredCount = ((deficitPoints - pointsRemainingOnTable + 3) / 4)`, and `isFrameBallAvailable`.
- **Break-building beam search (`planBreakSequence`):** Searches `horizonShots = 1..5` (`BEAM_WIDTH = 8`), alternating `RED` (removed from layout) and nominated `COLOUR` (re-spotted to its WPBSA spot or nearest clear centre-line position via `findRespotPosition`) while reds remain, then clearing colours strictly in ascending order `2..7`, weighting expected points `pointValue * makeProbability` plus next-ball leave quality.
- **Baulk-return safety & tactical mode (`findBestSafety`, `SnookerTacticalMode`):** Evaluates candidate safety deflections and cushion-escape fallbacks across cut angles, speeds, and spin presets using `SpinPhysics.traceCueBallPathWithSpin`, scoring containment (`1 - opponentEstimatedPotProbability`), baulk proximity (`x <= BAULK_LINE_X_METERS`), target stay-back, and separation. Recommends `PLAY_FOR_SNOOKERS` when `needsSnookers` is true, `ATTACK_BREAK_BUILD` when a break shot has `makeProbability >= 0.52`, and `CONTAINMENT_SAFETY` otherwise.

### 4. Acoustic Break-Speed Estimator (`BreakSpeedEstimator.kt`)

- **Dual-peak envelope detection (`detectBreakImpactsFromEnvelope`):** Scans an amplitude envelope sampled at `envelopeSampleRateHz` (`2000 Hz` default) for the first prominent cue-strike transient above $\max(0.15,\; 0.25 \cdot A_{\max})$ and the strongest rack-impact peak separated by $[0.035\,\text{s}, 0.45\,\text{s}]$.
- **Acoustic delay & cloth sliding-friction compensation (`estimateBreakSpeed`):** Subtracts the microphone sound propagation differential at $c_{\text{sound}} = 343.0\,\text{m/s}$ to obtain true cue-ball flight time $\Delta t_{\text{flight}}$, then recovers launch speed $v_0$ and rack-impact speed $v_{\text{impact}}$ over break distance $D$ under sliding cloth deceleration $\mu_s g$:
  $$\Delta t_{\text{flight}} = \max\!\left(\Delta t_{\text{audio}} - \frac{d_{\text{mic,rack}} - d_{\text{mic,cue}}}{c_{\text{sound}}},\; 0.025\,\text{s}\right), \qquad v_{\text{avg}} = \frac{D}{\Delta t_{\text{flight}}}$$
  $$v_0 = v_{\text{avg}} + \frac{1}{2}\mu_s g\,\Delta t_{\text{flight}}, \qquad v_{\text{impact}} = \max\!\left(v_{\text{avg}} - \frac{1}{2}\mu_s g\,\Delta t_{\text{flight}},\; 0.1\,\text{m/s}\right)$$

### 5. Assumed Cloth Roll & Rail Density Presets (`TableSpec.kt`, `PhotoCaptureAndConfirmScreen.kt`, `App.kt`)

- **Four `AssumedSurfacePreset` profiles (`TableSpec.kt`):** Provides realistic assumed cloth-roll ($\mu_r$), sliding-friction ($\mu_s$), and rail-bounce ($e_c$) baselines when working from online photos/videos or uncalibrated tables:
  - `PRO_BROADCAST_FAST` ($\mu_r = 0.0068$, $\mu_s = 0.165$, $e_c = 0.82$): Fast tournament worsted cloth and lively K55 rails.
  - `STANDARD_POOL_HALL` ($\mu_r = 0.0100$, $\mu_s = 0.200$, $e_c = 0.75$): Balanced commercial hall cloth and standard rails.
  - `SLOW_BAR_BOX_HEAVY` ($\mu_r = 0.0145$, $\mu_s = 0.240$, $e_c = 0.66$): Slow napped bar cloth and softer/dead rails.
  - `SNOOKER_DIRECTIONAL` ($\mu_r = 0.0082$, $\mu_s = 0.180$, $e_c = 0.78$): Fine directional snooker cloth and steel-block rails.
- **Bounded fine-tuning (`ClothParams.withAdjustedRollAndRail`, `TableSpec.withClothParams`):** Clamps user roll adjustments to $\mu_r \in [0.004, 0.025]$ and rail restitution to $e_c \in [0.50, 0.92]$, coherently deriving sliding friction $\mu_s = \text{clamp}(0.12 + 8\,\mu_r,\; 0.10, 0.35)$.
- **Live UI controls (`AssumedSurfaceControlCard`):** Exposed in both `PhotoCaptureAndConfirmScreen` (*"Assumed Cloth Roll & Rail Density (Online Photo / Video)"*, applied to the rectified `TableLayout` on confirm) and `App.kt` on the `Table & Planner` screen (*"Surface: Assumed Cloth Roll & Rail Density"*) with preset chips and steppers (`±0.001` on $\mu_r$ with `Fast`/`Medium`/`Slow` descriptors; `±0.02` on $e_c$ with `Soft/Dead`/`Standard`/`Lively` descriptors).

### 6. Two-Phase Interactive Tutorial & Walkthrough Architecture (`ShotTimelineAnimator.kt`, `PracticeToolsContract.kt`, `Interactive3DTutorialPlayer.kt`)

All 6 `eightBallWalkthroughs()` cards (in both `RulesAndFaqScreen` and `ExtrasAndToolsScreen`) and all 8 `allTutorials()` cards (in `ExtrasAndToolsScreen`) embed `PhasedInteractiveTutorialViewer`, providing a mode toggle between two complementary visual learning phases:

- **Engine timeline & 3D perspective projector (`ShotTimelineAnimator.kt`):**
  - `ShotTimelineAnimator.buildTimeline(...)` synthesizes a continuous `0.0..1.0` kinematic timeline (`ShotAnimationTimeline`) across five `ShotAnimationPhase` stages: `CUE_ADDRESS_AND_STRIKE` (cue pull-back and forward acceleration), `CUE_BALL_APPROACH` (cue-to-ghost flight), `CONTACT_AND_THROW` (ball-ball impact window), `POST_IMPACT_ROLL` (eased deceleration along `SpinPhysics.traceCueBallPathWithSpin` and object-ball pot segment), and `BALLS_AT_REST` (pocketed ball removal and zero velocities).
  - `ShotTimelineAnimator.projectTablePoint3D(...)` projects table-space SI coordinates $(x, y, z)$ through a 3D orbit camera (`Camera3DOrbitSpec` with presets `behindCueView`, `cornerIsometricView`, `sideBroadcastView`, and `overheadTopDownView`) to normalized perspective coordinates, depth (`depthMeters`), and apparent sphere radius (`apparentRadiusNormalized`).
- **Four annotated stages & shot defaults (`WalkthroughVisualStage` in `PracticeToolsContract.kt`):**
  - Every walkthrough and tutorial defines 4 `WalkthroughVisualStage` entries (`Stage 1: Table Setup & Assessment` at `0.0`, `Stage 2: Aim Line & Cue Strike` at `0.20`, `Stage 3: Ghost-Ball Impact & Throw` at `0.45`, and `Stage 4: Cushion Rebound & Next-Ball Shape` at `1.0`) paired with `primaryTargetBallNumber`, `primaryPocketId`, `recommendedTipOffset`, and `recommendedSpeedScale`.
- **Phase 1 — Multi-Stage Text & Visual Diagrams (`WalkthroughTextAndDiagramViewer`):**
  - Renders `Stage 1..Stage 4` selector chips, a highlighted `calloutBadge`, stage-specific explanation text, and a 2D illustrated table `Canvas` sampled via `timeline.sampleAtProgress(selectedStage.frameProgress)` displaying the cue stick (on Stage 1), ghost-ball contact ring, traveled cue-ball and object-ball paths, and impact marker.
- **Phase 2 — Interactive 3D Table & Frame Scrubber Slider (`Interactive3DTableWithFrameSlider`):**
  - Renders a draggable 3D perspective `Canvas` (`pointerInput` orbit of `yawRadians` and `pitchRadians`) with quick camera preset chips (`Behind Cue 3D`, `Corner 3D`, `Side Broadcast 3D`, `Overhead`), projected 3D wooden rail aprons (`z = 0.038 m`), cushion walls (`z = 0.036 m`), cloth bed (`z = 0.0 m`), 6 perspective pocket mouths, 3D ghost-ball contact ring (`z = R`), traveled 3D path ribbons (`z = R * 0.25`), tapered 3D cue stick (`z = R + 0.08` to `z = R`), and Painter's-algorithm depth-sorted shaded 3D spheres with cloth shadows.
  - Provides a continuous `0%..100%` Material3 `Slider`, quick-jump step buttons (`|< Strike (0%)`, `-5% Frame`, `Impact`, `+5% Frame`, `End (100%) >|`), live phase/time/speed telemetry, and interactive Spin (`Stun`, `Follow`, `Draw`, `Left English`, `Right English`) and Speed (`3/10`, `5/10`, `8/10`) chips that recompute the 3D timeline in real time.
