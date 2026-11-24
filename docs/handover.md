# Handover

**Last updated:** 2026-11-24

Where the project stands, what has been decided and why, and what to do next. Update this file whenever the state of the project changes.

## Status

All six roadmap milestones (0 Research & prototype, 1 MVP, 2 Photo detection, 3 Strategy, 4 Calibration, 5 Extras) are built in `:engine` and `:composeApp`, with `iosApp/` Swift entry sources, `Info.plist` and configuration (no `.xcodeproj` yet; it has to be generated on macOS). "Built" means the code and its tests exist; several device-facing parts run on sample data, as listed under [Not implemented](#not-implemented). Tests: 298 engine JVM tests in 25 suites (`:engine:jvmTest`) and 87 app unit tests in 15 suites, run on 2026-11-24. Nothing has been run on a physical device or on iOS.

### Engine (`:engine`, pure Kotlin, no platform imports)

- **Model:** SI table and ball geometry (`TableSpec`, `Ball`, `TableLayout`), `PlayerProfile` (1-10 speed scale mapped linearly to 0.6-4.2 m/s, questionnaire, one aim-error standard deviation per skill level), `BallSetProfile` (preset and colour-to-number swaps), `GameRulesState`.
- **Ball motion** ([physics note](research/physics.md)): every ball carries a velocity and spin (`BallState`, `BallMotion.kt`) and moves by one sliding-then-rolling law. `TableCollisionSimulator` steps from event to event (slide ends, stop, cushion, pocket, contact). Ball-ball contact has restitution and throw from the sliding-speed friction curve with the 1/7 no-slip limit. Cushions use the Han (2005) impulse on the full ball state (`CushionContact.kt`). The 90 degree and 30 degree rules and draw angles are not coded; they come out of the law.
- **Cue ball before contact:** `SpinPhysics.computePreImpactCueState` applies the same law to every tip height (strike spin k0 = 2.5 b/R, full sliding friction), so a centre-ball hit is still sliding for 12 v0^2 / (49 mu_s g) and a stop shot needs a tip below centre. `tipForImpactState` solves the tip for stun, roll or draw at the object ball. Squirt, swerve and the compensated aim (`solveCompensatedAim`, a thinner hit on cuts) are in `SpinPhysics.kt`.
- **Pockets** (`TableSpec.PocketSpec`, `PocketPreset`, `PocketGeometry.kt`): an opening between two jaw tips with the hole behind the nose line; entry window, aim point, jaw tolerance that grows with arrival speed, capture at the mouth, jaw hits rebound off the facing.
- **Path waypoints** (`ShotPathWaypoints.kt`): typed data for the tappable points on the cue-ball line.
- **Planner** ([strategy note](research/strategy-engine.md)): `ExecutionErrorModel` gives make probability as the probability mass of one cue-line aim error inside the pocket's entry window, mapped through the ghost-ball geometry; the 9-point sigma-point stencil remains only for the cue-ball landing cloud, scratch probability and spread. `OneShotPlanner` ranks shots with the same function. `MultiShotPlanner` is a beam search (1-5 shots, or `deepAnalysis` up to 6) with stroke refinement, a one-shot look-ahead leaf, an 8-ball rack term, one control-difficulty factor, and rejection of nominal scratches and wrong-pocket pots; its scenarios are in `PlannerScenarioTest`. `HeatZoneGenerator` builds the position grid; `ProblemBallAnalyzer` flags problem balls. `RollingRunoutPlanner` keeps accepted steps frozen and plans a full horizon from the table each accepted shot leaves. `FallbackShotAdvisor` gives one recommendation when no direct pot exists (two-ball combination, one-rail kick to hit, basic safety). `PlanningReadinessValidator` pauses planning until the cue ball, the game's last ball and (8-ball) the group are set. `evaluateAgainstGreedyBaseline` compares the planner with a greedy one-shot baseline.
- **Vision** ([vision note](research/computer-vision.md)): 8-DOF planar homography with a ball-height correction at z = R, Hungarian ball identification, a game-type suggester, and `RawImageTableDetector` working on packed ARGB frames: HSV cloth segmentation, the table quad fitted to the cushion noses per side (with a confidence per side and a cushion-width fallback), connected-component ball blobs and palette voting. This is classical image processing; there is no learned detector.
- **Calibration** ([calibration note](research/calibration.md)): `VideoBallTracker` (flicker normalisation, Kalman tracking, event detection), `TableCalibrationFitter` (five drills: rolling resistance, sliding friction, head-on cushion restitution, bank-english cushion friction, ball-ball throw friction; held-out validation), `TableLevelAndProfiles` (180 degree paired tilt readings, 3x3 slope map, slow-roll slope inversion, `SavedTableProfile`).
- **Snooker and tools:** `SnookerStrategyPlanner` (WPBSA spots, break building, baulk safety, tactical mode) and `BreakSpeedEstimator` (envelope peak detection, speed-of-sound and sliding-loss correction) in `engine/.../snooker` and `engine/.../tools`.
- **Shot timeline** (`ShotTimelineAnimator`): keyframes and a 3D projector for the tutorial and walkthrough players.

### App (`:composeApp`, Compose Multiplatform)

- **Navigation:** four bottom destinations (Home, Plan, Learn, Rules) and a side menu; focused flows (photo scan, calibration, snooker coach, break-speed meter, scorekeeper and shot clock, player profile and ball set, About & Legal, tutorial and walkthrough players) are full-screen routes. `Simple` and `Pro` display modes ([design guide](design_guide.md)).
- **First run:** a terms acknowledgement before the three setup steps, and an About & Legal screen. The legal documents under `docs/legal/` are bundled by a build task (`generateLegalDocuments`) and a test checks the bundle matches the source. The accepted terms version (`LegalConfig.TERMS_VERSION`) is stored with the setup choices; the screen shows again when the version changes.
- **Plan screen:** table canvas with drag-and-drop balls, aim line, ghost ball, curved cue-ball paths (the sampled curve is drawn), landing cloud, heat zones and problem-ball halos, 2D cue-tip selector, speed and spin readouts. Tapping a point on the cue-ball line opens a path inspector callout with Back and Next. The rolling plan (`planning/RollingPlanSession.kt`) runs on the Plan and fullscreen screens: Next accepts the focused shot and plans from the resulting table, Back reopens a step, any focused step can be edited, and changed steps are marked as updated (different ball or pocket) or refined (different stroke). The "No Direct Pot" card shows the fallback advice. Recessed pockets and cushion cut-outs are drawn from the pocket geometry (`ui/TableRendering.kt`); pocket-size chips choose a `PocketPreset` for the session only (not saved). The fullscreen landscape view is opened from the see-through zoom pill.
- **Photo scan:** on Android the camera (`TakePicture`) and gallery (`PickVisualMedia`) produce an ARGB frame that goes through `RawImageTableDetector`; the confirm screen has draggable corners, magnifying loupes, ball renumbering, an assumed-surface control, and a guide "What is the table box?" with a diagram and jaw-tip ticks. Only decoded pixels are kept, in memory. Cloth names include "Classic Green".
- **Learn and tools:** 8 tutorials and 6 eight-ball walkthroughs with a 2D stage viewer and a 3D orbit player, 5 scored drills, a practice log, Pool Race and Snooker scorekeepers, a shot clock, and an offline rules library of 20 original entries with synonym search.
- **Calibration screen:** three bundled sample sessions, the fitted-versus-default table, a held-out check card, the slope grid and one-tap profile apply.
- **Launcher icon:** two crossed cues behind a white cue ball drawn as the in-app hit guide (a 3-level green, amber and red hit zone at the top left, crosshairs, a dashed 0.5 R ring, F, D, L, R letters and a strike dot). Android adaptive and monochrome vector drawables plus legacy densities, and a 1024 px iOS `AppIcon.png`.

### Not implemented

- **Live microphone capture.** `PlatformBreakAudioController` (Android and iOS) returns bundled sample presets from `TutorialsAndDrillsCatalog.sampleBreakAudioPresets()` and never opens a microphone. The estimator and the break-speed meter screen work on that sample data.
- **Live camera and sensor runtime.** The common `PlatformCameraController` and `PlatformTiltSensorController` on both platforms return bundled sample photos and tilt readings; the Android photo picker is the one real capture path. Table calibration from video runs on bundled sample sessions, not recorded video.
- **iOS photo import.** `PlatformPhotoPicker.ios.kt` reports that camera and library import are unavailable and points to the built-in sample photos.
- **Persistence beyond setup.** Only the setup choices and the accepted terms version are stored (Android `SharedPreferences`, iOS `NSUserDefaults`). The practice log (seeded with sample entries) and saved table profiles (seeded with sample profiles) are held in memory and are lost on restart; the pocket preset is not saved either. There is no Room database; rules search is an in-memory search over bundled text.
- **On-device ML detector, OpenCV, and an iOS build.** None is in the project; no `.xcodeproj` exists.

## What's here

| Path | Contents |
|---|---|
| [README.md](../README.md) | Public project page: features, roadmap summary, planned stack |
| [docs/README.md](README.md) | Docs index and confirmed conventions (SI units, table coordinates) |
| [docs/design_guide.md](design_guide.md) | Mobile UX/UI architecture, onboarding, navigation, `Simple` vs. `Pro` progressive disclosure, visual tokens, and screen blueprints |
| [docs/roadmap.md](roadmap.md) | Milestones 0-5, what each delivers, what is not planned |
| [docs/adr/](adr/0001-cross-platform-kmp-compose.md) | Architecture decisions: [0001](adr/0001-cross-platform-kmp-compose.md) KMP + Compose Multiplatform, [0002](adr/0002-event-spin-physics-and-beam-planner.md) spin physics and beam planner (with amendments), [0003](adr/0003-video-calibration-and-table-level-fitting.md) video calibration and table level, [0004](adr/0004-snooker-break-safety-planner-and-acoustic-break-speed.md) snooker planner and break speed |
| [docs/research/](research/) | Feasibility, computer vision, physics, strategy engine, calibration, rules and content, references |
| [docs/legal/](legal/README.md) | Terms of use, privacy policy, third-party notices, store declarations checklist, licence texts |
| [engine/](../engine/build.gradle.kts) | Pure KMP module: physics, planners, vision geometry and pixel detection, calibration, snooker strategy, break-speed estimator, shot timeline (`commonMain`, `commonTest`) |
| [composeApp/](../composeApp/build.gradle.kts) | Compose Multiplatform app (`commonMain`, `androidMain`, `iosMain`, tests) |
| [iosApp/](../iosApp/Configuration/Config.xcconfig) | Swift entry sources, `Info.plist`, assets and configuration (no `.xcodeproj` yet) |
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | GitHub Actions CI workflow (JVM unit tests, lint, debug APK build) |
| [LICENSE](../LICENSE) | MIT |
| [.gitignore](../.gitignore) | Gradle, Android Studio and Xcode output, signing keys, IDE and OS files, ML training data, local working folders |

## Decisions so far

| Decision | Why |
|---|---|
| Name: Runout | A pool term for clearing the table, which is what the planner helps with. Short and easy to search for. |
| MIT licence | Simple and permissive. |
| Kotlin Multiplatform with Compose Multiplatform for Android and iOS ([ADR-0001](adr/0001-cross-platform-kmp-compose.md)) | One code base for the engine, table canvas and UI, with native access to camera and sensors behind `expect`/`actual`. |
| Physics and planner in a pure KMP `engine` module | Unit-testable on the JVM without a device, and compiles for both platforms. |
| Still photos, not a live AR overlay | Plain cloth gives AR tracking little to lock onto, so overlays drift. |
| Apache-2.0 detector (YOLOX or EfficientDet-Lite) planned, not Ultralytics YOLO | Ultralytics is AGPL-3.0, which would force the app to be distributed under the AGPL. No detector is bundled yet. |
| Table calibration from overhead video | Handheld, angled video is too inaccurate for fitting cloth and cushion parameters. |
| Spin inferred from ball paths | Phone cameras can't reliably see a ball rotate. |
| 8, 9 and 10-ball first; snooker strategy last | Safety play dominates snooker, which makes its strategy much harder. |
| Original rule summaries with synonym search | Official rulebooks are copyrighted; original summaries citing WPA and WPBSA stay clean, and synonyms (`BIH`, `kitchen`, `scratch`) make offline queries easy. |
| Fully offline | No accounts or servers, and it works in any pool hall. |
| `PlayerProfile` questionnaire and a linear 1-10 speed scale; `BallSetProfile` swaps | Turns subjective speed terms into SI speeds per skill and shaft class, and handles custom or TV ball palettes without changing the rules. The scale is linear in m/s, which a measured scale may replace. |
| Photo to layout with a 4-corner planar homography, a ball-height correction at z = R and Hungarian identification, with the user confirming | Corrects the parallax of an elevated ball near the table edge and gives one-to-one numbering; draggable corners are the fallback. Detection is classical pixel processing until an on-device model exists. |
| Table quad fitted to the cushion noses, per side, with a cushion-width fallback ([computer vision](research/computer-vision.md#cushion-noses)) | Ball positions are measured against the nose lines the rules use, and a flagged fallback side tells the player which corner to check. |
| EXIF and GPS are never kept; the game type is only a suggestion | Protects privacy and avoids misreading a partial rack. |
| One sliding-then-rolling law for every ball and every tip, with no shortcut for a centre-ball hit or a fixed 90 or 30 degree rule ([ADR-0002](adr/0002-event-spin-physics-and-beam-planner.md)) | An earlier model moved the cue ball along one straight line at an equivalent speed and was wrong by up to a third of a metre on draw shots; with real velocity and spin on every ball, later collisions and rail rebounds follow from the same code. |
| Han (2005) cushion impulse on the full ball state, with nose height, cushion friction and restitution as table parameters | Rebound depends on how the ball arrives (rolling, stun, draw) and the cloth bends it afterwards. Known limits are recorded in [physics](research/physics.md#cushion). |
| Throw from the sliding-speed friction curve with the 1/7 no-slip limit; aim compensation solves for the line of centres whose thrown direction is the pot line | Throw changes with speed, cut and spin; a player who allows for it hits the object ball thinner than the geometric ghost ball. |
| Pockets are openings between jaw tips with the hole behind the nose line, with a speed-dependent jaw tolerance | Corner and side pockets differ and a fast ball that touches a jaw rattles out; the tolerances are modelling values awaiting calibration. |
| Make probability is the probability mass of one cue-line aim error inside the pocket's entry window, through the ghost-ball geometry, with speed error by a 3-point Gauss-Hermite rule | A real probability that is the same in the search, the position maps and the one-shot ranking. This replaces the earlier sigma-point survival term; the 9-point stencil remains for the landing cloud only. |
| Skill-level aim-error defaults anchored to estimated drill percentages | A starting point until the player's own calibration replaces it. They are estimates, not measurements. |
| Beam search with stroke refinement, a one-shot look-ahead leaf and one control-difficulty factor; nominal scratches and wrong-pocket pots rejected | Keeps a plan's strokes reproducible at the first shot, and stops the plan counting on luck. About 80-160 ms for a 3-shot plan on a JVM (measured on a shared machine). |
| Rolling plan: pressing Next accepts the focused shot, accepted steps are frozen, and a full horizon is planned from the table that shot leaves | The user walks a run-out one shot at a time and always sees a full look-ahead; steps already seen are not reshuffled unless a new plan is at least 5 % better. |
| Fallback advice is one recommendation by a fixed rule: combination, kick to hit, or basic safety | A first step when no direct pot exists. There is no multi-shot search for it and no opponent model; banks, caroms, jumps and massé are not offered. |
| Bounded, outlier-trimmed fitting of five drills ([ADR-0003](adr/0003-video-calibration-and-table-level-fitting.md)) | Mis-tracked trials are rejected and parameters stay inside physical limits. The bank drill fits cushion friction, the head-on drill restitution. |
| Paired 0 and 180 degree tilt readings; slow-roll drift as a finer slope check | The difference cancels the phone's zero offset, and a slow ball shows slopes below the sensor's resolution. |
| Snooker: red-colour alternation with WPBSA re-spotting, baulk safety and a snooker-deficit mode ([ADR-0004](adr/0004-snooker-break-safety-planner-and-acoustic-break-speed.md)) | Snooker alternates reds and colours, and safety decides many frames. |
| Break speed from the gap between two sound peaks, corrected for sound travel and cloth loss; transient in-memory audio only | No radar needed and nothing is recorded. The estimator exists; live capture does not (see [Not implemented](#not-implemented)). |
| UK 8-ball and APA/BCAPL rule comparisons with "Try on Table Planner" layouts | Players switch between rule sets; original summaries stay clear of rulebook text. |
| Shot timeline and 3D projection in the engine, drawing in the app | Keeps the keyframes testable on the JVM and the Canvas code in `composeApp`. |
| Raw ARGB frames decoded in the engine, not platform bitmaps | The detector is testable on the JVM and shared by both platforms with no AGPL dependency. |
| Planning is debounced (150 ms, off the main thread) during drags | Keeps dragging at full frame rate. |
| Legal documents in `docs/legal/`, bundled in the app, with a first-run acknowledgement that stores the accepted terms version | The app says plainly that its output is an estimate and that nothing leaves the device. The documents name Nexova Digital as the publisher; the effective date and the proprietor's legal name are still placeholders (`LegalConfig`), so they are drafts until filled in. |

## Open questions

- How low a camera angle still gives usable detection? Far-rail accuracy on low, end-on views is limited (one pixel is 7 to 14 mm there), and the height of the visible nose edge and the camera pose are guesses ([computer vision](research/computer-vision.md#open-questions)).
- Where the real-table training photos come from, and under what MIT-compatible licence.
- English on a ball that arrives rolling changes the rebound speed more than the final angle at the default cushion friction; a fuller cushion model (speed dependence, bed friction during the impact) may be needed ([physics](research/physics.md#cushion)).
- Contact friction on follow and draw shots is not modelled beyond what the sliding law gives; check against measured shots.
- Jaw tolerances, the skill-level aim-error defaults and the speed-error link are estimates awaiting calibration against real play.
- The speed scale is linear in m/s; a measured scale may differ.
- The ball-ball friction exponent comes from one published fit; a second source quotes a different value ([physics](research/physics.md#implemented-in-engine)).
- Whether profiling on mid-range phones shows a bottleneck when ML detection, video tracking and `deepAnalysis` run together.

## Next steps

1. **Persistence:** store the practice log, saved table profiles and the pocket preset across restarts (Room KMP is the planned store).
2. **Real capture:** live microphone envelope capture for the break-speed meter, live camera and tilt sensors, and recorded-video calibration in place of the bundled sample sessions.
3. **iOS:** native photo import in `PlatformPhotoPicker.ios.kt`, generate the Xcode project on macOS and verify device builds.
4. **On-device detector:** bundle an Apache-2.0 ball detector (LiteRT on Android, CoreML or LiteRT on iOS) and OpenCV table rectification, then measure the metrics in the [computer vision note](research/computer-vision.md#metrics).
5. **Calibrate the estimates:** jaw tolerances, aim-error defaults and cushion friction against measured shots; replace Han's single impulse if the rolling-ball english limit matters.
6. **Release:** fill in the legal placeholders (`LegalConfig`, the documents) and follow the [store declarations](legal/store-declarations.md).

## Picking it up

- Install Android Studio (current stable) and use its bundled JDK (or JDK 17) to run `:engine:jvmTest`, `lint`, and `:composeApp:assembleDebug`.
- Read the [roadmap](roadmap.md), then the research note for the area you are working on.
- Keep the docs in step with the code: update the relevant research note or the roadmap in the same pull request, and update this file when the project's state changes.
- Check that every new dependency, model, dataset or asset has a licence compatible with MIT before adding it.
- Feature work goes on a branch and reaches `main` through a pull request.
