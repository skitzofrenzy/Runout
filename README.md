# Runout

**An open-source pool and snooker trainer for Android and iOS: photo table detection, shot physics, and multi-shot position planning.**

![Status: Milestones 0–5 complete](https://img.shields.io/badge/status-milestones%200%E2%80%935%20complete-brightgreen)
![Platform: Android | iOS](https://img.shields.io/badge/platform-Android%20%7C%20iOS-3DDC84)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)

Take a photo of the table and confirm the layout. Runout then shows which ball to play next, where to aim, how much speed and spin to use, and where the cue ball should stop to set up the next shots.

> **Status: Milestones 0–5 complete.** All six roadmap milestones are built in `:engine` and `:composeApp`: SI shot geometry, analytic cue-ball physics, closed-form spin physics (squirt, swerve, cut/spin throw, and english cushion rebound), deterministic sigma-point execution-error modelling, multi-shot expectimax beam-search planning (2–5 shots + deep run-out mode), spatial position heat zones, problem-ball and breakout analysis, an interactive table editor with 2D cue-tip control and landing-cloud overlays, photo rectification with 3D ball-height correction, player/ball-set profiles, an offline searchable rules & FAQ library (including UK 8-Ball Blackball and APA/BCAPL rule comparisons), overhead video luma normalization and 2D Kalman ball tracking, 5-drill table parameter fitting ($\mu_r, \mu_s, e_c, k_{\text{spin}}, \mu_b$) with held-out shot validation, $0^\circ / 180^\circ$ bias-cancelled tilt-sensor and slow-roll level mapping, saved table profiles, WPBSA Snooker break-building and baulk-return safety planning, transient acoustic break-speed estimation, interactive tutorials and scored practice drills with a session log, and a match scorekeeper and shot clock. See [`docs/`](docs/README.md), [`ADR-0002`](docs/adr/0002-event-spin-physics-and-beam-planner.md), [`ADR-0003`](docs/adr/0003-video-calibration-and-table-level-fitting.md), [`ADR-0004`](docs/adr/0004-snooker-break-safety-planner-and-acoustic-break-speed.md), and [`docs/handover.md`](docs/handover.md).

## Planned features

### Table and shot analysis

- **Photo to layout:** detect the table and balls from a photo, then confirm or correct them on screen.
- **Game type detection:** suggests 8-ball, 9-ball, 10-ball or snooker from the balls on the table; you always confirm.
- **Ball setup:** choose solids or stripes, or drag to match your ball set's colours to numbers for 9-ball and 10-ball.
- **Shot lines:** aim line, cut angle, cue ball path and banks, adjusted for spin (throw, squirt and swerve).

### Strategy

- **Shot order:** plans 2–5 shots ahead, or a full run-out in deep-analysis mode.
- **Heat zones:** shows where the cue ball should land to get position on the next 2–3 balls.
- **Speed and spin advice:** given in your own scale, based on your stroke profile.
- **Problem balls:** spots clusters and blocked balls, and suggests how and when to break them up.

### Calibration

- **Player profile:** a short questionnaire or calibration drills set your personal speed and spin scales.
- **Table calibration:** record practice shots on video and Runout estimates cloth speed and cushion rebound.
- **Table level (advanced):** check for an uneven table with the phone's tilt sensor and slow-roll tests.

### Learning and tools

- **Rules and FAQ:** searchable, offline rules for 8-ball (WPA/BCAPL, APA, and bar-rule comparisons), UK 8-ball (blackball), 9-ball, 10-ball, and snooker.
- **Tutorials and drills:** 8 guided tutorials (aiming, stance, speed control, spin/throw, 90°/30° rules, pattern play, safeties, break) and 5 scored drills that load directly onto the table planner.
- **Practice tools:** pool race and snooker scorekeeper, shot clock with per-rack extensions, practice log, and transient in-memory acoustic break-speed meter.

## Games

- **8-ball, 9-ball and 10-ball** come first, for every feature.
- **Snooker** gets shot lines and rules early. Its strategy comes last, because snooker is dominated by safety play and is much harder to plan.

## Roadmap

| Milestone | Delivers |
|---|---|
| 0. Research and engine prototype *(done)* | Design notes, project skeleton, shot geometry and a one-shot planner with tests |
| 1. MVP *(done)* | Manual table editor, shot lines, basic position play, rules and FAQ, player profile |
| 2. Photo detection *(done)* | Table and ball detection, game type suggestion, confirm/correct screen |
| 3. Strategy *(done)* | Spin physics, execution-error model, multi-shot beam planner, heat zones, problem balls |
| 4. Calibration *(done)* | Video table calibration, table level check |
| 5. Extras *(done)* | Snooker break & safety strategy, 8 tutorials, 5 scored drills, practice log, scorekeeper, shot clock, acoustic break-speed meter, UK 8-ball (blackball) & league variants |

Details are in [docs/roadmap.md](docs/roadmap.md).

## Planned tech stack

| Area | Choice |
|---|---|
| App and UI | Kotlin Multiplatform (KMP), Compose Multiplatform (Android and iOS) |
| Camera | CameraX / Camera2 (Android), AVFoundation (iOS) |
| Table detection | OpenCV (Android and iOS) |
| Ball detection | An Apache-2.0 object detector (YOLOX or EfficientDet-Lite) running on the phone with LiteRT (Android) and CoreML / LiteRT (iOS) |
| Physics and planner | A pure Kotlin Multiplatform `engine` module (`commonMain`), unit-tested on the JVM; C++ only if profiling shows it's needed |
| Storage | Room KMP (SQLite), with full-text search for the rules |

See [ADR-0001](docs/adr/0001-cross-platform-kmp-compose.md) for why Kotlin Multiplatform and Compose Multiplatform were chosen over Flutter and separate native apps. Everything is planned to run on the phone, with no account or internet connection needed.

## Documentation

- [Docs index](docs/README.md)
- [Handover](docs/handover.md): current state, decisions and next steps
- [Roadmap](docs/roadmap.md)
- Research: [feasibility](docs/research/feasibility.md), [computer vision](docs/research/computer-vision.md), [physics](docs/research/physics.md), [strategy engine](docs/research/strategy-engine.md), [calibration](docs/research/calibration.md), [rules and content](docs/research/rules-and-content.md), [references](docs/research/references.md)

## Fair play

Runout is for practice and training. Most league and tournament rules ban electronic aids during a match, so don't use it there.

## Contributing

The project is at an early stage. Ideas, research links and bug reports are welcome: open an issue. Table photos for the detection dataset are welcome too, if you took them yourself, no people are in the frame, and you're happy to share them under CC BY 4.0.

For code, please open an issue to discuss the change before sending a large pull request.

## Acknowledgements

The physics notes build on published work by Dr. Dave Alciatore and others, and on the open-source [pooltool](https://github.com/ekiefl/pooltool) simulator. See the [references](docs/research/references.md).

## License

[MIT](LICENSE) © 2026 skitzofrenzy
