# Changelog

All notable user-facing changes to Runout are documented here and bundled into the in-app **What's New** screen.

## [0.2.2] - 2026-11-25

### Fixed
- **Path Physics Callout Crash:** Fixed a Compose layout crash when opening the **Path physics** inspector (`measure()` was called more than once on the same callout node); the callout now probes sizes via intrinsic height and measures once cleanly across both standard and compact table sizes.
- **Table Canvas Initial Sizing:** Moved canvas size tracking from draw phase to layout (`onSizeChanged`) so step-zoom controls and the Path physics callout are ready immediately on the first frame.

## [0.2.1] - 2026-11-25

### Added
- **Swipe-to-Remove & Timed Undo:** Swipe left on any row in the photo-review ball list to remove it cleanly with a red trash reveal and haptic feedback, plus a 5-second floating **Undo** bar (supporting sequential undos and **Clear Balls** recovery).
- **Clear Balls Button:** Quickly clear all detected balls on the photo review screen before adding balls manually.

### Fixed
- **Bright Cool-Grey Cloth & 4K Detection:** Widened bright grey cloth segmentation and removed the fixed ball-radius ceiling so silver-blue broadcast tables no longer produce ghost balls, and added pale-ivory cue-ball classification under tinted lighting.
- **Tap-to-Rescan Accuracy:** Local tap rescans now reuse the detected cloth model, pick only the ball closest to your tap (or split a touching cluster), and prune hidden overflow candidates so deleted balls cannot reappear or block taps.

## [0.2.0] - 2026-11-25

### Added
- **App Versioning & What's New:** View the installed version and build number in the side menu and About & Legal, and browse release notes directly inside the app.
- **Real Pocket Geometry & Presets:** Pockets sit recessed behind the cushion nose lines with selectable pocket cuts (**Tournament**, **Pro-Cut**, **Bar Box**, **Snooker**) and speed-dependent jaw acceptance.
- **Interactive Path Inspector:** Tap markers along the cue-ball path to inspect spin transitions, object-ball contact transfer, cushion speed/spin losses, and resting points, with **Back / Next** navigation and a leader line that keeps the inspected point clear.
- **Rolling Run-Out Planning:** Accept shots step-by-step with **Next**, step back with **Prev**, edit tip, speed, or pocket on any focused shot, and keep a full look-ahead window as you move through the rack.
- **Fallback Advice:** Suggests a two-ball combination, one-rail kick, or basic safety when no direct pot is available or make probability is low.
- **First-Run Notice & Bundled Legal Docs:** Built-in Terms of Use, Privacy Policy, and Third-Party Notices under **About & Legal**.

### Improved
- **Unified Ball & Cushion Physics:** Every ball tracks full velocity and spin under one sliding-then-rolling cloth law with Han cushion rebounds, contact throw compensation, and accurate soft-stroke power transfer.
- **Make Probability:** Computed as aim-error probability mass inside the pocket entry window with blocker shadows.
- **Table & Photo Polish:** Cushion-nose table detection, photo framing guide, magnifying loupes, transparent zoom bar with fullscreen shortcut, and a fixed-height Cue Ball Sandbox card that never shifts under your finger.

### Fixed
- **9-Ball & 10-Ball Extra Drops:** Edited shots that legally pot the target ball and drop an extra ball can now be accepted and continued (or complete the rack when the game ball drops).
- **Onboarding & Navigation:** Table preset selection matches by table kind and nearest size rather than name, system **Back** works consistently across the welcome tour and every screen, and Android lint passes with zero errors.

## [0.1.0] - 2026-11-17

### Added
- **Initial Offline Trainer:** Top-down interactive table editor and shot planner for 8-Ball, 9-Ball, 10-Ball, UK 8-Ball, and Snooker.
- **Multi-Shot Strategy & Spin:** Expectimax run-out planner, position heat zones, problem-ball breakout detection, and a 2D cue-tip spin selector.
- **Photo Import:** Camera and gallery import with automatic table/ball detection and manual ball correction.
- **Calibration, Learning & Tools:** Cloth/rail/throw calibration sessions, level check, 2D/3D tutorials and drills, offline rules search, break-speed meter, and scorekeeper with shot clock.
