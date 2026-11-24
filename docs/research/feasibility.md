# Feasibility

How hard each feature is, the main risk, and how to reduce it.

**Difficulty scale:**

- **Easy:** standard app development.
- **Moderate:** known techniques that need careful work.
- **Hard:** real engineering effort, where quality depends on tuning.
- **Research-level:** no reliable method on a phone today. Avoid it or find a workaround.

| Feature | Difficulty | Main risk | Mitigation |
|---|---|---|---|
| Rules and FAQ (searchable) | Easy | Official rulebooks are copyrighted | Write original summaries and link to the sources ([rules and content](rules-and-content.md)) |
| Player profile, speed and spin questionnaire | Easy | Self-reported values are inaccurate | Offer calibration drills to refine them ([calibration](calibration.md#player-profile)) |
| Manual table editor, colour-to-number reorder | Easy | – | Also the fallback when detection is wrong |
| Shot lines: aim, cut angle, cue ball path | Easy–Moderate | – | Ghost-ball geometry, 90° and 30° rules |
| Spin effects on the lines | Moderate | Effects vary with cue, cloth and balls | Physics model with parameters that can be calibrated ([physics](physics.md)) |
| Ball detection from a photo | Moderate | Lighting, glare, occlusion, low camera angles; solids vs stripes | Trained detector, framing guide, confirm/correct screen ([computer vision](computer-vision.md)) |
| Game type suggestion | Moderate | Mid-game layouts are ambiguous | Ranked suggestions; the player always confirms |
| Strategy 1–5 shots ahead (3 by default), heat zones | Hard | Compute time on a phone | Pruned search and cached position maps ([strategy engine](strategy-engine.md)) |
| Strategy 5+ shots ahead or a full run-out | Hard | The search grows exponentially | Beam search with a time budget in a deep-analysis mode |
| Problem balls and break-outs | Hard | Cluster outcomes are chaotic | Simulate break-outs many times; heuristics for timing |
| Snooker strategy | Very hard | Safety play dominates; many balls | Last milestone; shot lines and rules come first |
| Table level with the tilt sensor | Easy | Sensor bias, camera bump | Rotate-180° method; slow-roll test for small slopes ([calibration](calibration.md#table-level)) |
| Cloth speed and cushion rebound from practice shots | Hard | Needs good video; handheld is poor | Overhead tripod mode, fixed drills, parameter fitting |
| Detecting spin from video | Research-level | Rotation isn't visible at phone resolution | Infer spin from the ball's path |
| Live AR overlay | Hard and unreliable | Featureless cloth breaks AR tracking, so lines drift | Use still photos; not planned for now |

## The real constraint is the player

Accurate lines don't help if the player can't execute the shot. The planner models each player's execution error (aim, speed, tip position), so it prefers shots that player is likely to make over shots that are only geometrically perfect. Getting that model right matters more than millimetre-perfect geometry.

## Licensing risks

- **Detector models:** Ultralytics YOLO is AGPL-3.0, which would force the app to be distributed under the AGPL. Use Apache-2.0 alternatives instead ([computer vision](computer-vision.md#on-device-inference)).
- **Datasets:** public pool-ball datasets have mixed licences. Check each one before training on it.
- **Rule texts:** official rulebooks are copyrighted ([rules and content](rules-and-content.md#licensing)).

## Rough size

For one experienced developer working full-time, the full feature set is roughly 9–15 months of work. A useful first version (Milestone 1 in the [roadmap](../roadmap.md)) is roughly 2–3 months. These are planning guesses, not measurements.
