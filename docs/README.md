# Runout documentation

Design notes and research for Runout. Nothing here is final: the notes change as the prototype answers open questions.

## Start here

- [Handover](handover.md): where the project stands, what has been decided and why, and what to do next.
- [Design guide](design_guide.md): UX/UI architecture, onboarding, navigation, progressive disclosure (`Simple` vs. `Pro`), visual tokens, and screen blueprints.
- [Roadmap](roadmap.md): milestones, what each one delivers, and what is not planned.
- [ADR-0001: Cross-platform KMP and Compose Multiplatform](adr/0001-cross-platform-kmp-compose.md): why Runout uses Kotlin Multiplatform and Compose Multiplatform for Android and iOS.

## Research

| Note | Covers |
|---|---|
| [Feasibility](research/feasibility.md) | How hard each feature is, the main risks, and how to reduce them |
| [Computer vision](research/computer-vision.md) | Table detection, ball detection and identification, game type suggestion |
| [Physics](research/physics.md) | Ball, cloth and cushion model; spin effects; simulation method |
| [Strategy engine](research/strategy-engine.md) | Shot search, heat zones, problem balls |
| [Calibration](research/calibration.md) | Player profile, table calibration drills, table level |
| [Rules and content](research/rules-and-content.md) | Rule sources and licensing, FAQ, tutorials, extra tools |
| [References](research/references.md) | Books, papers and open-source projects |

## Conventions

These conventions are confirmed and implemented in `:engine`.

- **Units:** SI inside the engine (metres, seconds, radians, kilograms). The UI converts to player-friendly units.
- **Table coordinates:** origin at the head-end left corner of the playing surface, measured at the cushion noses. The head end is the end you break from. *x* runs along the long side towards the foot end; *y* runs across the table.
- **Ball numbering:** pool balls use their numbers (0 for the cue ball). Snooker balls use their colour names.
- **Docs follow the code:** a change that alters a design described here updates the note in the same pull request.
