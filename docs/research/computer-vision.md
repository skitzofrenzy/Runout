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
- Take the largest cloth region, fit straight lines to its edges (Hough transform or RANSAC line fitting), and intersect them to get the four corners of the playing surface at the cushion noses.
- Compute a homography from those four image points to the known table dimensions. This gives a top-down view in real units.
- If confidence is low, show draggable corner handles. Detecting the pockets can refine the corners.

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
- **HSV auto-cloth segmentation (`detectClothSurface`, `ClothHueCategory`):** samples the central `[0.20..0.80] × [0.20..0.80]` region (excluding bright highlights and dark pocket/8-ball pixels) into a 36-bin (`10°`) circular hue histogram and classifies the cloth surface into `TOURNAMENT_BLUE` (`175°..255°`), `SIMONIS_GREEN` (`75°..175°`), `CHAMPIONSHIP_RED` (`0°..35°` or `325°..360°`), or `SLATE_GREY` (median saturation `< 0.22` or neutral hues), reporting dominant hue, mean saturation/value, and frame cloth coverage.
- **4-corner quad extraction (`detectTableQuadCornersPx`):** scans the cloth HSV mask inside `[0.04W..0.96W] × [0.04H..0.96H]` and extracts the 4 extreme corners `[headLeftPx, footLeftPx, footRightPx, headRightPx]` for `PerspectiveHomography.fromTableQuad`, with a safe inset fallback when cloth visibility is too low.
- **Connected-component ball blobs & HSV/white-fraction crop classifier (`analyzeArgbFrame`):** computes image `meanLuma`, gradient-energy `sharpnessScore`, and specular `glareFraction`; runs connected-component flood-fill on non-cloth foreground regions inside the inset table quad; filters blobs by equivalent radius (`expectedBallRadiusPxRange`) and aspect ratio (`0.55..1.80`); and votes `whiteFraction`, `blackFraction`, and chromatic `BallPaletteColor` cues (`YELLOW`, `ORANGE`, `RED`, `MAROON`, `BROWN`, `PINK`, `PURPLE`, `BLUE`, `GREEN`) to emit `DetectedBallCandidate` records for `BallIdentificationAndAssignment.rectifyAndIdentifyBalls`.
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

- How low a camera angle is still usable?
- Is detecting on the original photo really better than on the warped image in practice?
- Can pockets be detected reliably enough to refine the corners?
