# ADR 0001: Cross-platform architecture with Kotlin Multiplatform and Compose Multiplatform

- **Status:** Partially implemented — KMP, Compose Multiplatform, and pure `:engine` are **Accepted** (built); native camera/CV/ML/sensor/microphone layers and Room KMP storage remain **Proposed** target architecture (clarified 2026-11-25)
- **Date:** 2026-11-12 (updated 2026-11-25)

## Context

Runout is an open-source (MIT) pool and snooker trainer built and maintained by a single developer. The project now targets **both Android and iOS** from a single repository while staying 100% offline.

The architecture must satisfy five technical constraints across both mobile platforms:

1. **Deterministic, low-latency physics and multi-shot strategy simulation:** The event-based [physics engine](../research/physics.md) (quartic root solving for ball–ball collisions, sliding/rolling transitions, spin, throw, squirt, swerve, and cushion rebound models) is invoked by the [strategy engine](../research/strategy-engine.md) roughly 12,000 times per search level (10 target–pocket pairs × 40 speed/spin combinations × 30 Monte Carlo execution-error samples). To complete a one-level search in ~1 second on a single core (and 2–3 levels interactively across multiple cores), a single shot simulation must run in **under 0.1 ms** on a mid-range phone.
2. **Interactive 2D canvas and spatial overlays:** Both platforms need identical rendering and touch handling for the top-down table editor, ghost-ball and tangent/30° aiming guides, Monte Carlo cue-ball landing clouds, and 2–5 cm resolution position-value heat zones.
3. **Low-level camera, sensor, and computer vision access:**
   - [Table detection and ball-height correction](../research/computer-vision.md#ball-height-correction) require camera intrinsics (focal length and principal point from Camera2 on Android and `AVCaptureCalibrationData` / camera intrinsics on iOS) to intersect viewing rays with the plane one ball radius *R* above the cloth.
   - [Table calibration from overhead video](../research/calibration.md#table-calibration-from-video) requires 1080p capture at 60–240 fps with exposure/flicker normalisation.
   - Classical vision (HSV cloth segmentation, Hough/RANSAC rail line fitting, homography) uses OpenCV, and ball detection/classification uses an Apache-2.0 detector (such as YOLOX or EfficientDet-Lite) running on-device with hardware acceleration.
   - [Table level checks](../research/calibration.md#table-level) require raw accelerometer/tilt sensor readings, and the break-speed tool requires low-latency microphone capture.
4. **Offline relational storage and full-text search:** Player, table, and ball-set profiles, practice logs, and bundled Markdown [rules, FAQs, and tutorials](../research/rules-and-content.md) must be stored and queried locally with SQLite full-text search (FTS4/FTS5).
5. **Single-developer sustainability and MIT licensing:** Maintaining two divergent codebases for physics, strategy, or complex canvas UI is impractical for one developer. Every library, runtime, and object detector must be compatible with the MIT licence (no GPL/AGPL dependencies).

## Options considered

### 1. Kotlin Multiplatform (KMP) + Compose Multiplatform

Share the pure domain `:engine` module (`commonMain`) and the UI/presentation layer (`:composeApp` using Compose Multiplatform for Android and iOS), while isolating camera, OpenCV, ML inference, and sensor integrations behind `expect`/`actual` interfaces in `androidMain` and `iosMain`.

- **Pros:**
  - Single language (Kotlin) across domain logic, unit tests, state management, database, and UI.
  - `:engine` compiles to JVM bytecode on Android (JIT/AOT) and native ARM64 machine code via Kotlin/Native (LLVM) on iOS, meeting the `< 0.1 ms` per-simulation budget when written with allocation-free primitives and value classes.
  - Compose Multiplatform shares the 2D `Canvas` rendering (table geometry, heat zones, aiming lines, ball drag handles) and navigation across Android and iOS without duplicating complex coordinate transforms.
  - Direct interoperability with Android Java/Kotlin APIs (CameraX/Camera2, LiteRT, OpenCV) and iOS Objective-C/Swift APIs (AVFoundation, CoreML / LiteRT, OpenCV iOS) without a foreign-language bridge for platform APIs.
  - Room KMP (with BundledSQLiteDriver) supports FTS4/FTS5 offline search across both Android and iOS.
- **Cons:**
  - Compose for iOS renders via Skia/Metal rather than UIKit controls, though Runout's UI is dominated by custom 2D table canvases and cards where custom rendering is an advantage.
  - Building and testing the iOS target requires macOS with Xcode (locally or via CI runners).
  - If profiling shows Kotlin/Native is ever slower than JVM on inner quartic loops, critical numerical routines may need tighter unboxed array layouts or an optional C/C++ cinterop fast path.

### 2. Flutter (Dart UI + Dart or Rust/C++ FFI engine)

Build the app in Flutter (Dart) and implement the physics/strategy engine either in Dart or in Rust/C++ via `dart:ffi`.

- **Pros:**
  - Mature cross-platform canvas rendering (Impeller) and hot reload; Windows development works for Android targets.
- **Cons:**
  - Introduces Dart (and potentially Rust/C++) alongside Kotlin and Swift platform channels for Camera2/AVFoundation intrinsics, high-fps video frame pipelines, and OpenCV/LiteRT integration.
  - Passing high-frequency video frames, homography matrices, and large heat-zone grids across Flutter platform channels and FFI boundaries adds serialization boilerplate and memory-copy overhead.
  - Splits the repository across multiple toolchains and package ecosystems.

### 3. KMP shared `:engine` + separate native UIs (Jetpack Compose + SwiftUI)

Share only `:engine` (and optionally data persistence) via Kotlin Multiplatform, and build two separate UI layers: Jetpack Compose on Android and SwiftUI on iOS.

- **Pros:**
  - Native platform look-and-feel and native SwiftUI integration with AVFoundation on iOS.
- **Cons:**
  - Doubles the UI engineering effort for a single developer. Specifically, the interactive 2D table canvas, ghost-ball aiming geometry, draggable perspective-correction handles, heat-zone shaders/overlays, and drill workflows would have to be written and debugged twice (in Compose `Canvas` and SwiftUI `Canvas`/CoreGraphics).
  - Requires continuous SwiftUI maintenance on macOS alongside Android development.

### 4. Fully separate native apps (Kotlin + Swift)

Develop and maintain two independent codebases for Android and iOS.

- **Pros:**
  - Zero cross-platform tooling constraints on either platform.
- **Cons:**
  - Untenable for a single developer. Porting and keeping parity across quartic collision solvers, spin/throw/squirt physics, expectimax beam search, and calibration curve fitting in two languages invites subtle numerical drift and doubles testing effort.

## Decision

Adopt **Option 1: Kotlin Multiplatform (KMP) + Compose Multiplatform**.

Because this ADR was drafted before code existed and now spans both implemented foundations and unbuilt native/storage layers, its parts carry distinct statuses:

### Status split: Accepted (built) vs. Proposed (target)

| Layer | Status | Current implementation vs. target |
|---|---|---|
| KMP multi-module split (`:engine`, `:composeApp`, `iosApp/` Swift sources) | **Accepted (built)** | `:engine` and `:composeApp` compile and pass JVM/Android unit tests; `iosApp/` holds Swift entry sources, `Info.plist`, and `Config.xcconfig` (`.xcodeproj` generation and iOS builds require macOS). |
| Pure KMP `:engine` (`commonMain`, `commonTest`, `jvmTest`) | **Accepted (built)** | Zero UI/Android/iOS imports; SI units only; event-based physics, expectimax and rolling planners, calibration fitters, snooker strategy, break-speed estimator, and classical ARGB vision (`RawImageTableDetector`, `PerspectiveHomography`, `BallIdentificationAndAssignment`). |
| Shared Compose Multiplatform UI & 2D `Canvas` (`:composeApp` `commonMain`) | **Accepted (built)** | Shared screens, interactive 2D table canvas, navigation (`AppRoutes.kt`, `BackNavigation.kt`), single table-state owner (`TableSession`), and command-driven planner session (`RollingPlanSession`). Hoisted Compose state replaces the `ViewModel` classes sketched in the original diagram (see [ADR-0005](0005-rolling-runout-plan-state-machine-and-command-queue.md) and [ADR-0007](0007-single-table-session-and-pure-back-navigation.md)). |
| Setup & terms persistence (`SetupPreferencesStore`) and rules search (`RulesSearchEngine`) | **Accepted (built)** | Key-value storage (`SharedPreferences` on Android, `NSUserDefaults` on iOS) persists onboarding choices and accepted terms version; practice logs, saved table profiles, and rules search run in memory. |
| Android photo capture & gallery import (`rememberPlatformPhotoPicker`) | **Accepted (built)** | Uses Android system camera (`ActivityResultContracts.TakePicture`) and photo picker (`PickVisualMedia`) into `:engine` `RawImageTableDetector`. iOS import (`PlatformPhotoPicker.ios.kt`) is not implemented yet. |
| Room KMP + `BundledSQLiteDriver` (SQLite FTS4/FTS5) | **Proposed (target)** | Planned local database for persisted profiles, practice logs, and FTS rules search; no database dependency is in the build yet. |
| Native camera & high-fps video (CameraX/Camera2, AVFoundation) | **Proposed (target)** | Planned `expect`/`actual` camera intrinsics (`LENS_INTRINSIC_CALIBRATION`, `AVCaptureCalibrationData`) and 60–240 fps calibration video. `PlatformCameraController` currently returns bundled sample photos. |
| Native CV & on-device ML (OpenCV, LiteRT / CoreML Apache-2.0 detector) | **Proposed (target)** | Planned replacement/augmentation for pure-Kotlin classical vision; neither OpenCV nor an ML runtime/detector is bundled yet. |
| Live tilt sensors & microphone capture (`SensorManager`/`CoreMotion`, `AudioRecord`/`AVAudioEngine`) | **Proposed (target)** | Planned live `actual`s behind `PlatformTiltSensorController` and `PlatformBreakAudioController`; both currently return bundled sample data. |
| Device profiling & JMH / Kotlinx-benchmark suite | **Proposed (target)** | `PlannerBenchmarkTest` times the planner on the JVM; a dedicated `< 0.1 ms` microbenchmark suite and physical-device timings are not done. |

### Module boundaries

The repository is structured into two primary Gradle modules plus the thin iOS host directory (`iosApp/`, containing Swift entry sources, `Info.plist`, and `Config.xcconfig`, with `.xcodeproj` generation pending macOS):

#### 1. `:engine` (Pure Kotlin Multiplatform domain module — Accepted, built)

- **Source sets:**
  - `commonMain`: Table and pocket geometry, ball state machine, event-based physics simulator (Bézier-hull contact solver, sliding/rolling/spinning transitions, cue strike squirt/swerve, ball–ball throw, the Han cushion impulse model), execution-error model, expectimax and rolling strategy planners, heat-zone evaluator, game rule state machines (8-ball, 9-ball, 10-ball, snooker), classical ARGB photo detection (`RawImageTableDetector`, `PerspectiveHomography`, `BallIdentificationAndAssignment`), break-speed estimation, and calibration parameter fitting.
  - `commonTest`: Platform-agnostic unit and property tests (analytic physics invariants such as the 5/7 stun rule and stopping distance $v^2 / (2 \mu_r g)$, and planner regression layouts; comparison against the pooltool reference simulator is planned and not done).
  - `jvmTest`: Executes `commonTest` on the JVM via the `jvm()` target for fast desktop/CI feedback (`PlannerBenchmarkTest` times the planner and runs the self-play comparison with the greedy baseline). A JMH/Kotlinx-benchmark suite verifying the `< 0.1 ms` per-simulation budget, and timings on a phone, remain **Proposed**.
- **Constraints:**
  - **Zero UI, Android, or iOS imports.** Depend only on the Kotlin standard library and pure multiplatform math/coroutines utilities (`kotlinx.coroutines`).
  - **SI units only:** All values inside `:engine` use metres, seconds, radians, and kilograms ([docs/README.md](../README.md#conventions)). Conversion to feet/inches, degrees, or player speed scales (1–10) happens exclusively at the `:composeApp` presentation boundary.
  - **Performance discipline:** Hot simulation loops should avoid unnecessary heap allocations so garbage collection / Kotlin/Native memory management does not stall multi-shot searches.

#### 2. `:composeApp` (Shared UI, state, and platform integration module — Accepted UI/state; Proposed native/Room layers)

- **Source sets:**
  - `commonMain` (**Accepted, built** unless marked **Proposed**):
    - **UI (Compose Multiplatform):** Interactive 2D table `Canvas` (layout editor, shot paths, ghost ball, landing clouds, heat-zone overlays), photo-scan confirm UI, calibration drill screens, rules/FAQ reader, scorekeeper, shot clock, and legal screens.
    - **Presentation & state:** Compose state hoisted in `App.kt` with `TableSession` owning table state, `BackNavigation.kt` deciding Back, and `RollingPlanSession` running `:engine` searches sequentially on `Dispatchers.Default` with a cooperative stop hook. `ViewModel` classes from the initial sketch are **not used** (see [ADR-0005](0005-rolling-runout-plan-state-machine-and-command-queue.md) and [ADR-0007](0007-single-table-session-and-pure-back-navigation.md)).
    - **Storage:** Built: setup choices and accepted terms version persist through `SetupPreferencesStore` (`SharedPreferences` on Android, `NSUserDefaults` on iOS); saved table profiles and practice logs live in memory, and rules search uses `RulesSearchEngine` in memory. **Proposed:** Room KMP with `BundledSQLiteDriver` (Apache-2.0) and SQLite FTS4/FTS5 for persisted profiles, practice logs, and full-text rules search.
    - **Platform abstractions (`expect` declarations):** `PlatformCameraController`, `rememberPlatformPhotoPicker` (`PlatformPhotoPickerState`), `PlatformTiltSensorController`, `PlatformBreakAudioController`, `PlatformBackHandler`, and `rememberSetupPreferencesStore`.
  - `androidMain`:
    - **Built:** Android `Activity` host, `PlatformBackHandler`, `SetupPreferencesStore`, and `rememberPlatformPhotoPicker` using `ActivityResultContracts.TakePicture` and `PickVisualMedia` (passing decoded ARGB frames to `:engine`'s `RawImageTableDetector`).
    - **Proposed:** Live `actual` implementations backed by **CameraX / Camera2** (`LENS_INTRINSIC_CALIBRATION` for ball-height ray-plane correction, high-speed session for 60–240 fps overhead video), **OpenCV Android SDK** (Apache-2.0 / 3-clause BSD), **LiteRT** (Apache-2.0) with GPU/NNAPI delegate for an Apache-2.0 object detector, Android `SensorManager` (accelerometer/rotation vector), and `AudioRecord`. The current `PlatformCameraController`, `PlatformTiltSensorController`, and `PlatformBreakAudioController` actuals return bundled sample data.
  - `iosMain` (sources exist; not yet built or linked into `iosApp/` pending `.xcodeproj` generation on macOS):
    - **Written (unbuilt):** `MainViewController`, `PlatformBackHandler` (no-op), `SetupPreferencesStore` (`NSUserDefaults`), and sample-backed `PlatformCameraController`, `PlatformTiltSensorController`, and `PlatformBreakAudioController`; `PlatformPhotoPicker.ios.kt` reports import unavailable.
    - **Proposed:** Live `actual` implementations backed by **AVFoundation** (`AVCaptureSession`, `AVCapturePhotoOutput` with `cameraCalibrationData.intrinsicMatrix`, high-fps `AVCaptureVideoDataOutput`), **OpenCV iOS framework**, **LiteRT for iOS / CoreML**, **CoreMotion** (`CMMotionManager`), and **AVAudioEngine**.

```
+-----------------------------------------------------------------------+
|                              :composeApp                              |
|  commonMain (Built): Compose 2D Table Canvas, Heat Zones, Navigation, |
|    TableSession, RollingPlanSession, SetupPreferencesStore,           |
|    RulesSearchEngine, expect Platform APIs                            |
|  commonMain (Proposed): Room KMP + SQLite FTS4/FTS5                   |
+-----------------------------------+-----------------------------------+
|            androidMain            |              iosMain              |
| Built: Activity, PhotoPicker,     | Written (unbuilt): MainViewCtrl,  |
|   SharedPreferences, BackHandler  |   NSUserDefaults, BackHandler     |
| Proposed: CameraX/Camera2 +       | Proposed: AVFoundation +          |
|   Intrinsics, OpenCV + LiteRT,    |   Intrinsics, OpenCV + CoreML/    |
|   SensorManager + AudioRecord     |   LiteRT, CoreMotion, AVAudioEng. |
+-----------------------------------+-----------------------------------+
                                    |
                                    v  (plain Kotlin data in SI units)
+-----------------------------------------------------------------------+
|                           :engine (Built)                             |
|  commonMain: Table/Pocket/Ball Model, Event-Based Physics,            |
|    Expectimax & Rolling Planners, Heat Zones, Calibration Fitters,    |
|    Snooker Strategy, Break-Speed Estimator, Classical ARGB Vision     |
|  commonTest / jvmTest: Unit Tests; Proposed: JMH / Device Benchmarks  |
+-----------------------------------------------------------------------+
```

## Consequences

### Positive

- **100% shared physics, strategy, and 2D table UI:** A single implementation of the physics solver, expectimax planner, heat-zone renderer, and interactive table editor runs on both Android and iOS.
- **Fast desktop test feedback on any OS:** Because `:engine` has no platform dependencies and includes a `jvm()` target (with a dedicated `jvmTest` benchmark suite planned as the engine expands in Milestones 1–3), physics and planner unit tests run in milliseconds on Windows, Linux, or macOS without starting an emulator or simulator.
- **Full access to native camera and vision APIs:** Putting camera and CV behind `expect`/`actual` interfaces preserves direct access to Camera2 intrinsics on Android and `AVCaptureCalibrationData` on iOS, which are required for accurate ball-height perspective correction and 60–240 fps calibration tracking.
- **Consistent offline database across platforms:** Room KMP with `BundledSQLiteDriver` ensures identical SQLite version and FTS behaviour on both Android and iOS, eliminating platform SQLite version discrepancies.
- **MIT-clean dependency tree:** Kotlin Multiplatform, Compose Multiplatform, Room KMP, CameraX, OpenCV (4.5+ is Apache-2.0), and LiteRT are all Apache-2.0 / MIT compatible.

### Negative and mitigations

- **macOS / Xcode required to compile and sign `iosApp`:**
  - *Impact:* Developing on Windows allows building, testing, and running `:engine` (`jvmTest`) and the Android target (`:composeApp:assembleDebug`), but the iOS binary and `iosMain` cinterop bindings can only be linked on macOS with Xcode.
  - *Mitigation:* Keep all shared UI and domain logic in `commonMain` so day-to-day development and testing work on Windows; `.github/workflows/ci.yml` currently runs `:engine:jvmTest`, Android lint, and `:composeApp:assembleDebug` on `ubuntu-latest` for pushes to `main` / `feature/**` and pull requests, while a `macos-latest` CI job to build and test the iOS target is planned as the engine and UI expand in Milestones 1–3.
- **Kotlin/Native vs JVM numerical performance characteristics:**
  - *Impact:* Kotlin/Native (LLVM) on iOS has different inlining and memory-allocation characteristics than the Android Runtime (ART). High allocation rates inside the simulation loop could degrade multi-threaded search scaling on iOS.
  - *Mitigation:* Enforce zero-allocation inner loops in `:engine` (primitive arrays, `value class` wrappers, pre-allocated mutable scratch state per simulation coroutine) and benchmark both ART and Kotlin/Native ARM64 builds early on mid-range devices against the `< 0.1 ms` per-simulation budget. If ever required, a small C/C++ solver kernel can be called via JNI on Android and `cinterop` on iOS without changing `:engine`'s public Kotlin API.
- **OpenCV and LiteRT native binary packaging on two platforms:**
  - *Impact:* Wrapping OpenCV and the object detector for both `androidMain` (JNI/AAR) and `iosMain` (XCFramework / Objective-C cinterop) requires maintaining two thin platform wrappers.
  - *Mitigation:* Keep the boundary coarse-grained (`VisionPipeline.analyzeTablePhoto(imageBytes, intrinsics, tableSpec): VisionResult`). Move pure geometric post-processing—such as ray-plane intersection at height *R*, Hungarian algorithm identity assignment, frozen-ball flagging, and game-type scoring—into `commonMain` or `:engine` so the native CV wrapper only performs pixel-level operations and tensor inference.
