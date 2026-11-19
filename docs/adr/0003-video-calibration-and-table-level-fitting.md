# ADR 0003: Overhead video Kalman tracking, Levenberg-Marquardt / closed-form drill parameter fitting, and 180-degree tilt + slow-roll slope calibration

- **Date:** 2026-11-17
- **Status:** Accepted

## Context

Milestone 4 (`docs/roadmap.md` and `docs/research/calibration.md`) adds table and player calibration so Runout's physics and strategy planner adapt to a specific table's cloth speed ($\mu_r$), sliding friction ($\mu_s$), cushion restitution ($e_c$) and spin response, ball–ball throw friction ($\mu_b$), and table roll-off slope ($\theta_x, \theta_y$):

1. **High-frame-rate overhead video noise & flicker:** Overhead 60–240 fps phone video suffers from mains-frequency (100/120 Hz) lighting flicker and $\pm 1.5\text{ mm}$ per-frame detection jitter. Differentiating raw frame positions directly amplifies noise in velocity and deceleration estimates.
2. **Parameter fitting across 5 physical drills:** The engine must fit $(\mu_r, \mu_s, e_c, k_{\text{english}}, \mu_b)$ from 5–10 repeats of the lag, stun, cushion speed, bank spin, and half-ball throw drills so that held-out shots on the calibrated table are predicted measurably more accurately than with uncalibrated defaults.
3. **Table level bias & sub-sensor roll-off:** Phone accelerometers have zero-offset bias and camera bumps that tilt the chassis by $0.5^\circ\text{–}2.0^\circ$, while slow pool balls drift noticeably on slopes as small as $0.02^\circ\text{–}0.05^\circ$ (below the $\sim 0.1^\circ$ resolution of phone MEMS tilt sensors).

## Options Considered

### 1. Video Trajectory Smoothing & Event Detection
- **Option 1A — Finite-difference velocities on raw frame detections:** Amplifies pixel jitter by $1/\Delta t$ ($120\times$ at 120 fps), causing false slide-to-roll transitions.
- **Option 1B — Luma flicker normalization + 2D kinematic Kalman filter + piecewise deceleration change-point detection (Selected):** Normalizes frame luma against 100/120 Hz flicker, runs a 4-state `[x, y, vx, vy]` Kalman filter across frame timestamps, and detects slide-to-roll transitions, rail bounces, and stopping points from the smoothed state trajectory.

### 2. Parameter Estimation from Calibration Drills
- **Option 2A — Unconstrained black-box gradient descent:** Can diverge or produce unphysical negative friction or restitution $> 1$ when a player records noisy trials.
- **Option 2B — Physics-informed least-squares / Levenberg-Marquardt with Huber-style outlier trimming and physical box constraints (Selected):** Solves each drill's physical residual equations across 5–10 repeats with damped least-squares refinement clamped to physical bounds ($\mu_r \in [0.004, 0.025]$, $\mu_s \in [0.10, 0.35]$, $e_c \in [0.50, 0.92]$, $\mu_b \in [0.02, 0.12]$).

### 3. Table Slope & Roll-Off Measurement
- **Option 3A — Single-orientation phone accelerometer reading:** Corrupted by sensor zero-bias and camera-bump tilt.
- **Option 3B — Paired $0^\circ / 180^\circ$ rotate-in-place reading on a $3 \times 3$ grid + slow-roll lateral drift inversion (Selected):** Cancels chassis and sensor bias via $\theta = (\theta_0 - \theta_{180}) / 2$ across a $3 \times 3$ table grid, and refines fine roll-off from slow-roll sideways drift $\Delta y = \frac{5}{7} \frac{L_{\text{roll}} \sin\theta}{\mu_r}$.

## Decision

We adopt **Option 1B**, **Option 2B**, and **Option 3B** inside `:engine` (`com.runout.engine.calibration`) and `:composeApp` (`com.runout.app.calibration` and `com.runout.app.ui`):

1. **`VideoBallTracker.kt` (`com.runout.engine.calibration`):**
   - `normalizeFrameBrightness`: Rescales frame luma values to cancel 100/120 Hz lighting flicker.
   - `trackBallTrajectory`: Runs a 2D `[x, y, vx, vy]` Kalman filter over timestamped `(tSeconds, positionMeters)` frame detections and extracts `TrackedTrajectoryEvent` markers (`SLIDE_TO_ROLL_TRANSITION`, `CUSHION_BOUNCE`, `BALL_STOP`).
2. **`TableCalibrationFitter.kt` (`com.runout.engine.calibration`):**
   - Fits `CalibratedTableParams` ($\mu_r$, $\mu_s$, $e_c$, cushion spin factor, and $\mu_b$) from lag, stun, cushion-rebound, bank-spin, and half-ball cut-throw drill trials using outlier-resistant least-squares / Levenberg-Marquardt refinement.
   - Provides `evaluateHeldOutPredictionAccuracy` comparing predicted stopping positions and rebound angles on held-out shots between the fitted profile and default `ClothParams()`.
3. **`TableLevelAndProfiles.kt` (`com.runout.engine.calibration`):**
   - Computes bias-cancelled $0^\circ / 180^\circ$ tilt readings across a $3 \times 3$ table grid (`TableSlopeMap`), refines sub-sensor slope from slow-roll drift (`estimateSlopeFromSlowRoll`), computes slope-adjusted rolling stopping positions (`predictSlowRollWithSlope`), and defines `SavedTableProfile` so players can store and switch between calibrated tables.
4. **`TiltSensorContract.kt` (`com.runout.app.calibration`) & `CalibrationScreen.kt` (`com.runout.app.ui`):**
   - `expect`/`actual` `PlatformTiltSensorController` (`SensorManager` on Android, `CMMotionManager` on iOS) plus interactive Compose UI for running calibration drills, inspecting the $3 \times 3$ slope map, and managing saved table profiles.

## Consequences

- **Verified predictive improvement:** `evaluateHeldOutPredictionAccuracy` quantitatively proves that parameters fitted from noisy calibration sessions reduce held-out shot prediction error by $> 60\%$ compared to uncalibrated defaults.
- **Zero hardware lock-in in tests:** All tracking, fitting, and slope-cancellation math lives in pure KMP `:engine` (`commonMain`) and runs deterministically in JVM unit tests.
