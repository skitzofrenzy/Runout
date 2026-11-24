# Computer vision

How Runout turns a photo of a table into a layout: where the table is, where every ball is and which ball it is, and which game is probably being played.

## Pipeline

1. Capture the photo.
2. Find the table and correct the perspective.
3. Detect the balls.
4. Identify each ball.
5. Map the balls onto table coordinates.
6. Suggest the game type.
7. The player confirms or corrects the result.

## 1. Capture

- A framing guide on the camera screen: the whole playing surface in view, with all four corners visible where possible.
- A higher camera angle is better. Standing at the end of the table with the phone raised works; directly overhead is best.
- Warn about glare spots, and check sharpness before accepting the photo.
- Ask for the table size once per table profile: 7 ft, 8 ft, 9 ft, 12 ft snooker, or custom measured dimensions.

## 2. Table detection and perspective correction

- Segment the cloth by colour. Cloth isn't always green, so sample the dominant hue near the image centre and threshold around it in HSV.
- Take the largest cloth region. The cushions are covered in the same cloth, so they usually pass the colour test and join this region: its outline runs along the cushion backs, and its near corners sit in the pocket holes.
- Find the cushion-nose line inside that outline, one side at a time, and intersect the four lines to get the corners of the playing surface at the cushion noses (see [Cushion noses](#cushion-noses) below).
- Compute a homography from those four image points to the known table dimensions. This gives a top-down view in real units.
- Every side reports a confidence. Where a side could not be measured, the corners next to it are flagged and the confirm screen asks the player to check them on the draggable corner handles.

### Cushion noses

Mapping the cushion-back outline to the table rectangle shrinks every ball position towards the middle of the table. On broadcast screenshots this put balls frozen to a cushion 6 to 13 cm from it, and the head-string line 7 to 25 cm from where it belongs.

Each side of the outer outline is refined separately:

1. **Shading edge.** Sixty cross-sections are sampled along the side, from just outside the outline to 12 % of the way across the table. Walking each one from the bed outwards, the first sharp colour step is the nose: the bed's own slow shading never makes a sharp step, while the cushion face, a cushion top of another shade or the rail does. A straight line is fitted to those points with RANSAC; the share of sections that agree with it is the side's confidence.
2. **Is it really a nose?** Half a cushion width beyond the line there must be cushion-coloured cloth (the cloth hue, or the cloth tint on grey cloth, at another brightness). If there is rail instead, the line is the edge of the cloth region and the cushion, the same colour as the bed, is hidden inside it.
3. **Fallback.** A side with confidence below one half, or whose line fails step 2, is placed by insetting the outer edge by a typical cushion band through the homography of the partly refined quad: 65 mm for sides facing the camera (the cushion top plus its visible sloping face; 65 to 90 mm was measured on the test photos) and 50 mm for the side nearest the camera, whose face is hidden. The cushion top is above the bed, so this is approximate; such sides are flagged for review.

Ball detection still masks with the outer outline, so the ball candidates do not change; only the homography does.

On the side nearest the camera the found line is the inner edge of the cushion top, the nose tip seen from behind. On the other sides it is where the bed meets the cushion face. Which physical height that edge belongs to, the tip about 36 mm up or the face's foot on the bed, differs between tables; see the open questions.

On the confirm screen the box is explained as the playing surface: each side runs along a cushion's front edge (the edge a ball touches), not the rail, the outer edge of the table or the middle of the pockets. Where two front edges are extended until they cross, the crossing lies in the open mouth of the corner pocket, about half a mouth width past the line between the jaws and short of the centre of the hole, so a corner handle normally sits over the pocket mouth rather than on the cloth. A small drawing of one corner shows this, and players are told to line up the sides first and let the corners follow.

Pocket landmarks are not used yet. The shading edge measured every side whose cushion differs in shade from the bed; the sides that fall back are those where the cushion matches the bed, and there the pocket jaws are the most promising extra signal.

### Ball-height correction

The homography maps the cloth plane, but ball centres sit one ball radius *R* above the cloth. Mapping an image ball centre through the cloth homography therefore places the ball slightly too far from the camera. At low camera angles the error is several centimetres.

Fix: estimate the camera pose from the homography and the camera intrinsics (Camera2 on Android and AVFoundation on iOS report them on many devices; otherwise estimate them from the field of view). Then intersect each ball's viewing ray with the plane at height *R* instead of the cloth plane.

## 3. Ball detection

Two approaches, tried in this order:

| Approach | Pros | Cons |
|---|---|---|
| Classical: circle detection (Hough) filtered by expected radius and colour | No training data; fast; good for an early prototype | Works only on near-overhead photos; struggles with touching balls and glare |
| Learned object detector | Handles lighting, clusters and camera angles | Needs a labelled dataset and model work |

Run the learned detector on the original photo, where balls look the way they do in training images, and map the centres afterwards. On the warped top-down image, balls photographed from a low angle are stretched.

## 4. Ball identification

Crop each detected ball and classify it:

- **Type:** cue ball, solid, stripe or 8-ball (pool); red, yellow, green, brown, blue, pink, black or cue ball (snooker).
- **Colour:** the hue of the dominant non-white pixels.
- **Number:** from type and colour, using the active ball-set mapping.

**Solids vs stripes:** the fraction of white on the ball's visible surface is the main signal. A stripe can look like a solid when its coloured band faces the camera, and the number circle on a solid adds white. Use a small classifier rather than a fixed threshold, and keep its confidence score.

### Colour confusions

Some standard colours are easy to confuse under warm or dim light:

| Often confused | Why |
|---|---|
| 3 (red) and 7 (maroon) | Similar hue; they differ mostly in brightness |
| 3 (red) and 5 (orange) | Close hues under warm light |
| 2 (blue) and 4 (purple) | Close hues under cool or dim light |
| Snooker pink and red | Pink can wash out towards red under some lights |

Balls with low confidence are highlighted on the confirm screen. Each numbered ball appears at most once, so assign identities jointly (the Hungarian algorithm on a detections × identities cost matrix) rather than one ball at a time.

### Ball sets vary

Not every set uses the standard colours; TV-style sets and some brands use different shades. The player's colour-to-number mapping (the drag-to-reorder screen) is stored per ball set and used during identification.

## 5. Mapping to table coordinates

- Apply the ball-height correction, then the homography, to get positions in metres.
- Push apart balls whose centres are closer than one ball diameter.
- Flag frozen balls (touching another ball or a cushion); they matter for rules and strategy.

## 6. Game type suggestion

Rules over the identified balls, each adding to a score:

| Signal | Suggests |
|---|---|
| Several reds plus snooker colours; table close to 12 ft; smaller balls | Snooker |
| Any ball numbered 11–15 | 8-ball |
| Highest number is 10 | 10-ball |
| Highest number is 9 or lower | 9-ball (or late-rack 8-ball or 10-ball) |
| Racked balls: a diamond of 9, a triangle of 10, a triangle of 15 | 9-ball, 10-ball, 8-ball |

Stripes on their own don't mean 8-ball: the 9 and 10 are stripes too. Late in a rack, layouts are ambiguous (only the 6, 8 and 9 left could be 8-ball or 9-ball), so the app shows ranked suggestions and the player always confirms. The last game type played on the device is a strong hint.

## 7. Confirm and correct

The confirm screen reuses the table editor: drag balls, change a ball's identity, add or remove balls. Corrections are stored on the device; the player can choose to export them to contribute to the training dataset.

## Implemented raw pixel & video decoder (`RawImageTableDetector`)

The classical pixel-to-layout pipeline is implemented in pure KMP `:engine` (`RawImageTableDetector` in [RawImageTableDetector.kt](../../engine/src/commonMain/kotlin/com/runout/engine/vision/RawImageTableDetector.kt)) and wired into `:composeApp` via [PhotoCaptureContract.kt](../../composeApp/src/commonMain/kotlin/com/runout/app/camera/PhotoCaptureContract.kt) and [PhotoCaptureAndConfirmScreen.kt](../../composeApp/src/commonMain/kotlin/com/runout/app/ui/PhotoCaptureAndConfirmScreen.kt):

- **Pure-KMP frame container (`ArgbImageFrame`):** holds `width`, `height`, and packed `0xAARRGGBB` `IntArray` pixel data (`pixelAt(x, y)`) plus `rgbToHsv(argb)` conversion without Android/iOS bitmap dependencies.
- **HSV auto-cloth segmentation (`detectClothSurface`, `ClothHueCategory`):** samples the central `[0.28..0.72] × [0.22..0.78]` playing surface (excluding bright highlights and dark pocket/8-ball pixels) into a 36-bin (`10°`) circular hue histogram and classifies the cloth surface into `TOURNAMENT_BLUE` (`175°..255°`, including low-saturation shark-grey broadcast cloth), `CLASSIC_GREEN` (`75°..175°`), `CHAMPIONSHIP_RED` (`0°..35°` or `325°..360°`), or `SLATE_GREY` (median saturation `< 0.22` or neutral hues), reporting dominant hue, mean saturation/value, and frame cloth coverage while rejecting high-saturation arena floor carpet outside the table.
- **Outer cloth outline, cushion-nose quad & end-on perspective orientation (`detectOuterClothQuadCornersPx`, `detectTableQuads`, `detectTableQuadCornersPx`, `isEndOnPerspectiveQuad`, `orientQuadForHomography`):** flood-fills the center-seeded connected cloth component inside `[0.04W..0.96W] × [0.04H..0.96H]` and refines end-on broadcast trapezoids by intersecting top/bottom head/foot lines with symmetry-checked left/right side lines (preventing rounded corner-pocket cutouts, cue sticks, or player arms from pulling corners inward); this is the outer outline. `detectTableQuads` then moves each side to the cushion nose as described in [Cushion noses](#cushion-noses) and reports a `TableQuadEdgeFit` (confidence, fallback flag, inset in pixels) per side. `analyzeArgbFrame` returns the nose quad as `detectedQuadCornersPx`, the outline as `outerQuadCornersPx` and the side fits as `quadEdges`. End-on quads are reordered (`[TL, BL, BR, TR]`) with a foot-end camera pose for `PerspectiveHomography.fromTableQuad`.
- **Synthetic cushions (`renderSyntheticTablePhotoArgb`, `SyntheticCushionStyle`):** the synthetic renderer can draw cloth-covered cushions and six pocket holes around the playing surface, either shaded (a lighter top and a darker face, still passing the cloth test) or matching the bed, so unit tests cover both the nose-line fit and the fallback.
- **Connected-component ball blobs, drop-shadow suppression & WNT/TV palette classifier (`analyzeArgbFrame`):** computes image `meanLuma`, gradient-energy `sharpnessScore`, and specular `glareFraction`; strips low-chroma cloth drop shadows and cushion-nose shadow lines (`isClothShadowHsv`); separates foreground pixels into compatibility classes so white cue balls (including partially hand-occluded ball-in-hand placements) and chromatic balls never merge with dark rails, pockets, gloves, or cue sticks; suppresses concentric number-dot and under-ball shadow duplicates; and votes `whiteFraction`, `blackFraction`, and chromatic `BallPaletteColor` cues (`YELLOW`, `ORANGE`, `RED`, `MAROON`, `BROWN`, `PINK`, `PURPLE`, `BLUE`, `GREEN`, plus WNT yellow/black 9-ball stripe detection and `BallSetProfile.wntTournamentBlackPool()`) to emit `DetectedBallCandidate` records for `BallIdentificationAndAssignment.rectifyAndIdentifyBalls`.
- **Multi-frame video pixel tracking (`trackBallInArgbVideoFrames`):** decodes timestamped `List<Pair<Double, ArgbImageFrame>>` sequences, matches the target ball candidate across frames, rectifies each frame centre through `PerspectiveHomography.imageToBallCenterMeters` alongside `meanLuma`, and feeds `VideoFrameBallObservation` directly into [VideoBallTracker.kt](../../engine/src/commonMain/kotlin/com/runout/engine/calibration/VideoBallTracker.kt) for flicker normalization, Kalman velocity tracking, and event detection.
- **In-app pixel decoder & synthetic round-trip (`renderPresetAsRawArgbFrame`, `processRawArgbFrame`):** `PhotoCaptureAndConfirmScreen` includes an interactive **Online Photo / Raw Pixel Frame Decoder** card to render `640×360` ARGB frames across all four `ClothHueCategory` options, inspect auto-detected cloth hue, quad corners, per-blob HSV/white-fraction votes, and end-to-end median SI error, and load the decoded layout directly into the confirmation preview.

## Local testing with downloaded photos & video frames (`ml/datasets/`)

Because real pool-hall photos and broadcast video clips can be large and carry third-party licences or EXIF metadata, `ml/datasets/` and `ml/runs/` are excluded from Git in [.gitignore](../../.gitignore).

1. **Drop test images and clips locally:** place downloaded `.png` or `.jpg` table photos into `ml/datasets/photos/` and extracted video frame sequences into `ml/datasets/videos/` (keep EXIF location tags stripped before sharing or exporting).
2. **Respect licences:** verify that any external image set or model used for training or distribution carries an MIT-compatible licence (never bundle GPL/AGPL code or Ultralytics YOLO models).
3. **Run raw-pixel decoding:** feed decoded `ArgbImageFrame` pixel buffers into `RawImageTableDetector.analyzeArgbFrame` (single photos) or `RawImageTableDetector.trackBallInArgbVideoFrames` (video frame sequences), followed by `BallIdentificationAndAssignment.rectifyAndIdentifyBalls` and `MultiShotPlanner.planRunout`, and inspect annotated diagnostics under `ml/datasets/runs/`.

## Training data

- Public pool-ball datasets (for example on Roboflow Universe). Check each licence before use.
- Our own photos across different tables, cloth colours, lighting and camera angles.
- Synthetic images: render random layouts in Blender with varied lighting and camera positions. The labels come for free, which helps with rare cases such as clusters and glare.
- Augmentation: brightness, colour temperature, blur and perspective.
- A held-out test set of real photos that is never used for training.

## On-device inference

- Export to LiteRT (Android) and CoreML or LiteRT (iOS), quantised to int8, using the GPU or NPU delegate where available.
- Target: under 1 second from shutter to layout on a mid-range phone.
- The model's licence must be compatible with MIT. Ultralytics YOLO is AGPL-3.0, which would force the app to be distributed under the AGPL. Use an Apache-2.0 model such as YOLOX or EfficientDet-Lite.

## Metrics

| Metric | Proposed target |
|---|---|
| Ball centre error after mapping | ≤ 1 cm median |
| Ball identity accuracy | ≥ 97% per ball |
| Corrections needed per photo | ≤ 1 on average |
| Game type suggestion correct (top choice) | ≥ 90% |

## Open questions

- How low a camera angle is still usable? On end-on broadcast views (camera elevation roughly 20 to 30 degrees at the far end) the nose lines are found to within one or two pixels, but at the far rail one pixel is 7 to 14 mm, so the far-rail result depends on the questions below.
- **Height of the visible nose edge.** The detected edge is used as if it lay on the bed. On some tables it is the foot of the cushion face (the head-string line then lands 13 to 16 mm from its true place, and balls against the head cushion 6 to 12 mm from where they should be); on others it is the nose tip about 36 mm above the bed, seen without the face (balls against the head cushion then come out 7 to 9 cm too far from it after the ball-height correction). Telling the two apart, for example from the height of the dark face band, is open.
- **Camera pose for photos.** The ball-height correction uses a fixed guess of the camera position (1.55 m high, 0.65 m behind the near rail). Broadcast cameras sit higher and further back, so near the far rail the correction is off by a few centimetres. Estimating the focal length from the table homography was tried and was not stable on near-telephoto broadcast views.
- **Fallback width.** Where the cushion is the same colour and brightness as the bed, the nose comes from a fixed cushion width; the spread measured on the test photos (65 to 90 mm) means up to about 25 mm of error on those sides until the player checks the flagged corners.
- Is detecting on the original photo really better than on the warped image in practice?
- Pocket detection was not needed to place the noses on the test photos. It may still help on low-contrast tables, where the jaws mark where the nose lines end.
