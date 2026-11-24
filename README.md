# Runout

**An open-source pool and snooker trainer for Android and iOS: photo table detection, shot physics, and multi-shot position planning.**

![Status: Milestones 0–5 complete](https://img.shields.io/badge/status-milestones%200%E2%80%935%20complete-brightgreen)
![Platform: Android | iOS](https://img.shields.io/badge/platform-Android%20%7C%20iOS-3DDC84)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)

Take a photo of the table and confirm the layout. Runout then shows which ball to play next, where to aim, how much speed and spin to use, and where the cue ball should stop to set up the next shots.

> **Status: Milestones 0–5 built, not yet released.** The engine (physics, planner, vision geometry, calibration math, snooker planner) and the Android and iOS app code for all six roadmap milestones exist and are unit-tested (298 engine and 87 app tests). Several device features still run on bundled sample data: the break-speed meter does not open the microphone, table calibration and the tilt check use sample sessions and readings, iOS photo import is not implemented, and only the setup choices persist across restarts (the practice log and saved table profiles are in memory). There is no on-device ML detector and no Xcode project yet. What is and is not done is listed in [`docs/handover.md`](docs/handover.md); the design notes are in [`docs/`](docs/README.md).

## Features

### Table and shot analysis

- **Photo to layout:** pick or take a photo (Android), and Runout fits the table box to the cushion noses and finds the balls with classical pixel processing; you confirm or correct the layout on screen. Accuracy on low camera angles is limited. iOS photo import is not implemented yet.
- **Game type detection:** suggests 8-ball, 9-ball, 10-ball or snooker from the balls on the table; you always confirm.
- **Ball setup:** choose solids or stripes, or drag to match your ball set's colours to numbers for 9-ball and 10-ball.
- **Shot lines:** aim line, ghost ball, cut angle, cue ball path and banks, adjusted for spin (throw, squirt and swerve). Every ball carries velocity and spin, so the cue ball's curve after contact, rail rebounds and pocket jaws come from one physics model. Tap a point on the cue-ball line to see what happens there.

### Strategy

- **Shot order:** plans 2–5 shots ahead, or a full run-out in deep-analysis mode, and walks through the plan one accepted shot at a time.
- **Make probability:** the chance that an aim error of your skill level still drops the ball, from the pocket's real opening. The skill-level defaults are estimates until you calibrate.
- **Heat zones:** shows where the cue ball should land to get position on the next 2–3 balls.
- **Speed and spin advice:** given in your own 1–10 speed scale, based on your stroke profile.
- **Problem balls:** spots clusters and blocked balls, and suggests how and when to break them up.
- **No direct pot:** when no ball can be potted, one recommendation: a two-ball combination, a one-rail kick to hit, or a basic safety.

### Calibration

- **Player profile:** a short questionnaire sets your skill level, cue type and speed scale.
- **Table calibration:** the fitting math for cloth speed, cushion rebound and throw from overhead video is implemented; the screen currently runs on bundled sample sessions, not recorded video.
- **Table level (advanced):** the tilt-sensor and slow-roll analysis is implemented; the screen uses sample readings, not the phone's sensor.

### Learning and tools

- **Rules and FAQ:** searchable, offline summaries (original text, not the official rules) for 8-ball (WPA/BCAPL, APA, and bar-rule comparisons), UK 8-ball (blackball), 9-ball, 10-ball, and snooker.
- **Tutorials and drills:** 8 guided tutorials (aiming, stance, speed control, spin/throw, 90°/30° rules, pattern play, safeties, break) and 5 scored drills that load directly onto the table planner.
- **Practice tools:** pool race and snooker scorekeeper, shot clock with per-rack extensions, and a practice log (kept in memory for the session). The break-speed meter has its estimator and screen, but runs on sample audio; microphone capture is not implemented.

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
| 4. Calibration *(done)* | Video table calibration and table level check: fitting and analysis in the engine, screens on sample data |
| 5. Extras *(done)* | Snooker break & safety strategy, 8 tutorials, 5 scored drills, practice log, scorekeeper, shot clock, break-speed estimator (sample audio only), UK 8-ball (blackball) & league variants |

Details are in [docs/roadmap.md](docs/roadmap.md).

## Tech stack

Items marked *planned* are not in the project yet.

| Area | Choice |
|---|---|
| App and UI | Kotlin Multiplatform (KMP), Compose Multiplatform (Android and iOS) |
| Camera | Android photo picker and camera capture; AVFoundation on iOS *(planned)* |
| Table and ball detection | Classical pixel processing in the engine (HSV cloth segmentation, cushion-nose fitting, ball blobs); OpenCV *(planned)* |
| Ball detection model | An Apache-2.0 object detector (YOLOX or EfficientDet-Lite) on LiteRT (Android) and CoreML / LiteRT (iOS) *(planned)* |
| Physics and planner | A pure Kotlin Multiplatform `engine` module (`commonMain`), unit-tested on the JVM; C++ only if profiling shows it's needed |
| Storage | Setup choices in `SharedPreferences` / `NSUserDefaults`; Room KMP (SQLite) for the rest *(planned)* |

See [ADR-0001](docs/adr/0001-cross-platform-kmp-compose.md) for why Kotlin Multiplatform and Compose Multiplatform were chosen over Flutter and separate native apps. Everything runs on the phone, with no account or internet connection needed.

## Documentation

- [Docs index](docs/README.md)
- [Handover](docs/handover.md): current state, decisions and next steps
- [Design guide](docs/design_guide.md): UX/UI flows, `Simple` vs. `Pro` progressive disclosure, and mobile design system
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

Terms of use, privacy policy and third-party notices: [docs/legal](docs/legal/README.md).
