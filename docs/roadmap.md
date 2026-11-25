# Roadmap

Milestones are ordered so that each one ships something usable on its own. Sizes are relative (S, M, L, XL) rather than dates, because this is a part-time project.

**Where it stands:** Milestones 0 to 5 are built: the code and its unit tests exist (398 engine and 145 app tests pass). Built is not validated: nothing has been run on a physical device since the latest changes, iOS has not been built, and Milestone 2's accuracy target has not been measured on real photos. The [handover](handover.md) lists what runs on sample data.

## Milestone 0: Research and engine prototype *(done)* · M

Goal: prove the core maths before building UI.

- Research notes in [research/](research/) and architecture decision in [adr/](adr/0001-cross-platform-kmp-compose.md).
- Kotlin Multiplatform Gradle project with a `composeApp` module (Compose Multiplatform for Android and iOS, plus `iosApp` Swift entry sources, `Info.plist`, and configuration; `.xcodeproj` generation pending macOS) and a pure KMP `engine` module (`commonMain`) that has no platform or UI dependencies, so physics and planning can be unit-tested on the JVM.
- Continuous integration (GitHub Actions): build, unit tests and lint on pushes to `main` and `feature/**` and on pull requests.
- Engine: table and ball model, ghost-ball aiming, cut angle, cue ball paths for stun, follow and draw (90° and 30° rules).
- A one-shot-ahead planner that ranks the legal shots on a layout.
- A minimal screen that draws a layout and the recommended shot lines.

**Done when:** the engine takes a table layout and returns a ranked list of shots with aim lines, covered by unit tests that run in CI.

## Milestone 1: MVP *(built)* · L

- Table editor: choose a table size and drag balls into place.
- Game setup: 8-ball (solids or stripes), 9-ball and 10-ball; drag to match ball colours to numbers.
- Shot lines: aim line, ghost ball, cut angle, and the cue ball path for stun, follow and draw.
- Basic position play: recommend the next shot and show where the cue ball goes.
- Player profile: a questionnaire for speed and spin scales.
- Rules and FAQ: searchable and offline.

**Done when:** a player can set up a layout by hand and get a sensible shot recommendation with lines.

## Milestone 2: Photo detection *(built; accuracy target not yet measured)* · L

- Capture screen with a framing guide.
- Table detection and perspective correction, with draggable corners as a fallback.
- Ball detection and identification.
- Game type suggestion, always confirmed by the player.
- Confirm/correct screen that reuses the Milestone 1 table editor.

**Done when** (proposed targets): on a held-out set of real table photos, ball centres are within 1 cm of the true position (median) and photos need no more than one correction on average.

**Status:** the capture, detection and confirm screens are built and unit-tested on synthetic frames, but this target has not been measured, because no held-out set of real photos exists yet. Far-rail positions from low-angle photos can be 7–9 cm out. The milestone is therefore built, not met.

## Milestone 3: Strategy *(built)* · XL

- Event-based physics with spin effects: throw, squirt, swerve and english off the cushion.
- An execution-error model built from the player profile.
- Multi-shot planner: 1–5 shots ahead (3 by default; beyond the horizon a one-shot look-ahead values the cue ball's stop), plus a deep-analysis mode of as many shots as there are balls, at most six.
- Heat zones for the next ball, with the one after it weighted at a quarter.
- Problem-ball detection and break-out suggestions.
- Speed and spin advice in the player's own scale.

**Done when:** in simulated racks with a fixed skill level, the planner runs out measurably more often than a greedy "easiest shot next" baseline.

## Milestone 4: Calibration *(built)* · L

- Overhead video ball tracking and fitting in the engine (the screens run on bundled sample sessions).
- Calibration drills for cloth speed, cushion rebound, cushion angle and throw.
- Table level check with the tilt sensor, and a slow-roll test.
- Saved table profiles (held in memory for now).

**Done when:** parameters fitted from a calibration session predict held-out shots on the same table better than the defaults.

## Milestone 5: Extras *(built)*

- Snooker strategy: red-to-colour break building with WPBSA colour re-spotting, endgame colour clearance, and baulk-return safety / snooker-deficit scoring.
- Interactive tutorials and scored drills linked directly into the table planner.
- Tools: pool race and snooker scorekeeper, shot clock with per-rack extensions, practice log, and an acoustic break-speed estimator (the screen runs on sample audio; live microphone capture is not implemented).
- Rule variants: UK eight-ball (blackball) and WPA/BCAPL vs. APA/bar-rule comparisons.

**Done when:** a player can evaluate snooker break and safety lines, launch interactive tutorials or scored drills directly on the table planner, track scores, shot clock, and drill logs offline, and estimate break speed from audio envelopes (sample audio for now).

## Not planned for now

| Idea | Why not |
|---|---|
| Live AR overlay | Plain cloth gives AR tracking little texture to lock onto, so the lines drift. Still photos are more accurate. Worth revisiting if tracking improves. |
| Detecting spin from video | Phone cameras can't reliably see a ball rotate. Spin is inferred from the ball's path instead. |
| Jump and massé shots | They need airborne, 3D physics. Out of scope until the basic model is solid. |
| Online features | The app is planned to work fully offline. |
