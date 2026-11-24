# Calibration

Two kinds of calibration: the **player** (how hard and how accurately they hit) and the **table** (how the cloth, cushions and slope behave). Both are stored as profiles, so one player can use several tables.

## Player profile

### Questionnaire (quick start)

- Experience level and how often they play.
- Their cue, and whether it has a low-deflection shaft (this affects squirt).
- Self-rated speed control and spin ability.

The answers give starting values for the execution-error model and a default speed scale.

### Speed scale

Players describe speed in their own terms, so Runout maps a 1–10 scale to real ball speed.

- **Drill:** from behind the head string, hit the cue ball up the table at "speed 3", "speed 5" and "speed 8", and mark where it stops (or count the rails it reaches).
- With the table's cloth speed known, each stopping distance converts to a starting ball speed.
- Advice is then given in the player's own scale, for example "speed 4, one tip of draw".

### Spin ability

- **Draw drill:** a straight-in shot from a fixed distance with as much draw as possible. How far the cue ball comes back gives the player's largest effective tip offset at that speed.
- **Side spin:** banks with running english from a marked spot, compared with the rebound without spin.

### Accuracy

- **Straight-in drill:** 20 shots at each of 2–3 distances. The success rate gives the aim error σφ.
- **Stop-shot drill:** where the cue ball stops relative to the contact point shows how consistent speed and tip offset are.
- Profiles keep updating from practice-mode results.

## Table calibration from video

### Recording setup

- The phone above the table, on a tripod with a boom arm or on a ceiling mount, with the whole playing surface in frame.
- At least 1080p at 60 fps; 120–240 fps slow motion is better for collisions.
- At 1080p over a 9 ft table, one pixel covers about 1.4 mm and a ball is about 40 pixels wide, which is enough to track.
- LED and fluorescent lights flicker at twice the mains frequency (100 Hz or 120 Hz). At high frame rates this shows up as frames getting brighter and darker, so normalise frame brightness before tracking.
- Handheld video at an angle is only good for rough estimates.

### Tracking

- Find the table corners once, as for photos, then detect the balls in every frame.
- Link detections into tracks with a Kalman filter, which smooths positions and estimates velocity.
- Detect events: cushion contacts, ball–ball collisions, and the slide-to-roll transition (where the deceleration rate changes).

### Drills and what each one measures

| Drill | Measures |
|---|---|
| Lag: roll the cue ball up and down the table and let it stop | Rolling resistance μr (cloth speed), from the steady deceleration |
| Firm centre-ball stun shot | Sliding friction μs, from how long the ball slides before it rolls |
| Straight into the end cushion at several speeds | Cushion restitution, and how it changes with speed |
| Banks at several angles, with and without side spin | Cushion friction, and how spin changes the rebound angle |
| Half-ball cuts at slow and medium speed | Ball–ball throw (μb) |
| Slow rolls along the length and across the width | Roll-off from an uneven table |

Each drill needs 5–10 repeats. The engine then fits the parameters by minimising the difference between simulated and tracked paths (the implemented fitter inverts one physical relation per drill, below; a joint nonlinear fit is not implemented), starting from the defaults in [physics](physics.md#starting-parameters).

## Table level

### Tilt-sensor check (quick)

- Lay the phone on the cloth on a straightedge or a thin, flat book; otherwise the camera bump tilts it.
- Take a reading, rotate the phone 180° in place, and take another. The table's slope is half the difference between the two readings: the sensor's own bias and the camera bump cancel out.
- Repeat at a 3 × 3 grid of points and show a slope map.
- Phone sensors resolve roughly 0.1° (about 1.7 mm over a metre). That catches a clearly unlevel table, but not the very small slopes that still make a slow ball drift.

### Slow-roll test (fine)

- With the overhead video setup, roll a ball slowly along several lines and track how far it drifts sideways.
- Slopes too small for the tilt sensor show up clearly as drift on slow rolls.

### Using the result

The slope map is saved in the table profile, and the physics engine adds the slope to the equations of motion. This is an advanced option and off by default. See [physics](physics.md#uneven-tables).

## Profiles

| Profile | Holds |
|---|---|
| Player | Speed scale, spin ability, execution-error model, cue |
| Table | Dimensions, pocket geometry, cloth and cushion parameters, slope map, date calibrated |
| Ball set | Colour-to-number mapping, ball size |

## Implemented in `:engine` and `:composeApp`

See [ADR-0003](../adr/0003-video-calibration-and-table-level-fitting.md) for the Milestone 4 architecture decisions implemented in `engine/src/commonMain/kotlin/com/runout/engine/calibration/` and `composeApp/src/commonMain/kotlin/com/runout/app/calibration/`:

### 1. Flicker Normalization & 2D Kalman Ball Tracker (`VideoBallTracker.kt`)

- **100/120 Hz luma normalization (`normalizeFrameBrightness`):**
  - Interior frames pass through the symmetric 5-tap FIR filter $\frac{1}{12}[1, 3, 4, 3, 1]$, which equals the convolution of $\frac{1}{4}[1, 2, 1]$ and $\frac{1}{3}[1, 1, 1]$ and therefore places exact spectral zeros at both 2-frame ($\omega = \pi$) and 3-frame ($\omega = 2\pi/3$) mains-flicker beats while preserving linear brightness trends.
  - Boundary frames (`0, 1` and `n-2, n-1`) project a detrended 6-frame window onto the orthogonal harmonic basis $\{1, (-1)^j, \cos(2\pi j/3), \sin(2\pi j/3)\}$ to subtract 2- and 3-frame oscillations before rescaling the sequence to `targetMeanLuma` (`128.0`).
- **2D kinematic Kalman state model (`trackBallTrajectory`):**
  - Runs independent constant-velocity filters on $x$ and $y$ with state vector $\mathbf{x}_k = [p_k, v_k]^\top$, $2 \times 2$ covariance $\mathbf{P}_k = \begin{bmatrix} p_{00} & p_{01} \\ p_{10} & p_{11} \end{bmatrix}$, transition matrix $\mathbf{F}(\Delta t) = \begin{bmatrix} 1 & \Delta t \\ 0 & 1 \end{bmatrix}$, and continuous white-noise acceleration process covariance:
    $$\mathbf{Q}(\Delta t) = q \begin{bmatrix} \Delta t^4 / 4 & \Delta t^3 / 2 \\ \Delta t^3 / 2 & \Delta t^2 \end{bmatrix}, \quad q = \sigma_a^2 \; (\text{default } \sigma_a = 0.85\,\text{m/s}^2), \quad r = \sigma_{\text{pos}}^2 \; (\text{default } \sigma_{\text{pos}} = 0.0018\,\text{m})$$
  - **Cushion maneuver gating & RTS smoothing:** When innovation exceeds $\nu^2 > 30\,(\mathbf{P}_{00,\text{pred}} + r)$, the filter marks a discontinuity and resets segment velocity so a backward Rauch–Tung–Striebel (RTS) pass smooths positions and velocities within each free-rolling segment without smearing across cushion impacts.
- **Event detection (`TrackedTrajectoryEvent`):**
  - `SLIDE_TO_ROLL_TRANSITION`: detects high sliding deceleration ($> 0.80\,\text{m/s}^2$) dropping to gentle rolling deceleration ($< 0.45\,\text{m/s}^2$) along the same heading ($\hat{\mathbf{v}}_{\text{pre}} \cdot \hat{\mathbf{v}}_{\text{post}} > 0.85$) away from bounces.
  - `CUSHION_BOUNCE`: detects sharp heading changes ($|\Delta\theta| > 0.35\,\text{rad}$) or normal velocity sign reversals within $2.5 R$ of any cushion rail, clustered with a $0.12\,\text{s}$ cooldown.
  - `BALL_STOP`: marks the first point after motion ($v > 0.08\,\text{m/s}$) where speed drops to $\le 0.025\,\text{m/s}$ (ignoring transient cushion reversals).

### 2. Bounded Drill Estimators & Held-Out Validation (`TableCalibrationFitter.kt`)

Every drill estimator applies a median absolute deviation (MAD) inlier fence $\max(3.5 \times 1.4826\,\text{MAD},\,\max(s_{\text{rel}}|\text{median}|, \epsilon_{\text{abs}}))$ before solving bounded physical parameters (with $g = 9.81\,\text{m/s}^2$ from `MotionPhysics.GRAVITY_M_S2`):

- **Lag drill ($\mu_r$, `fitRollingResistanceMuR`):** From $d = v_0^2 / (2\mu_r g)$, computes outlier-trimmed least-squares:
  $$\hat{\mu}_r = \operatorname{clamp}\!\left(\frac{\sum_i v_{0,i}^2\,d_i}{2\,g \sum_i d_i^2},\; 0.004,\; 0.025\right) \quad (\text{default } 0.010)$$
- **Stun drill ($\mu_s$, `fitSlidingFrictionMuS`):** Averages the per-trial time and distance estimators $\mu_{s,t} = \frac{2 v_0}{7 g t_{\text{slide}}}$ and $\mu_{s,d} = \frac{12 v_0^2}{49 g d_{\text{slide}}}$, trims outliers, and clamps to $[0.10, 0.35]$ (default $0.20$).
- **Head-on cushion drill ($e_c$, `fitCushionRestitutionEc`):** A ball rolled straight into a cushion, with its speed measured just before and just after the contact. The outlier-trimmed ratio $\rho = \sum_i v_{\text{in},i} v_{\text{out},i} / \sum_i v_{\text{in},i}^2$ is inverted through the cushion model of [physics](physics.md#cushion) (`predictHeadOnReboundRatio`, which does not depend on speed and rises with $e_c$) at the current cushion friction and nose height, by bisection, and clamped to $[0.50, 0.92]$ (default $0.75$). The head-on ratio sits a little below $e_c$, because the contact normal tilts down into the bed and friction acts during the impact.
- **Bank english drill ($f_c$, `fitCushionFrictionFc`):** A ball rolled into a cushion at an angle $\theta_{\text{in}}$ with english $a/R$ (side spin $R\omega_z = -\tfrac52 (a/R)\,v$), and the angle of the line it rolls along once the slide after the cushion is over. Each trial is predicted with the cushion impulse and the slide on the cloth that follows (`predictBankReboundAngleRad`); trials whose own best $f_c$ is an outlier are dropped, and $f_c$ minimises the squared angle error over the rest (a 100-step scan, then a golden-section search), clamped to $[0.05, 0.40]$ (default $0.20$). `fitAllDrills` alternates the two cushion fits three times, because the head-on ratio depends a little on $f_c$ and the bank angles on $e_c$.
- **Half-ball cut-throw drill ($\mu_b$, `fitBallBallFrictionMuB`):** $\mu_b$ is the ball–ball friction at 1 m/s of sliding speed between the surfaces (default $\approx 0.046$); it scales the whole speed-dependent friction curve $\mu(v_{\text{rel}}) = a + b\,e^{-c\,v_{\text{rel}}}$ of `SpinPhysics.computeThrowAnglesRadians`. Each trial (cut angle, cue-ball speed and spin factor at contact, measured throw) is one equation in $\mu_b$; it is inverted by bisection to reject outlier trials, and the rest are fitted by least squares on the throw angle over $\mu_b \in [0.02, 0.12]$ (a coarse scan to find the basin, because trials at the no-slip limit make the error flat, then a 60-step golden-section search).
- **Spinning friction ($\mu_{sp}$):** not fitted by a drill yet. It defaults to $0.0635\,\mu_s$, the friction disc of a uniform-pressure contact patch about 2.7 mm in radius, which spins a ball down at about 11 rad/s² on ordinary cloth.
- **Held-out generalization (`evaluateHeldOutPredictionAccuracy`):** Evaluates fitted vs. default `ClothParams()` on unseen `HeldOutShotSample` stopping distances, head-on cushion rebound speeds (through the cushion model) and cut-throw angles, computing `relativeStoppingErrorReductionFraction`.

### 3. Table Level Cancellation, Slow-Roll Inversion & Profiles (`TableLevelAndProfiles.kt`, `:composeApp`)

- **Paired $0^\circ / 180^\circ$ bias cancellation (`cancelSensorBias180`):** Under $\theta_0 = \theta_{\text{table}} + \theta_{\text{bias}}$ and $\theta_{180} = -\theta_{\text{table}} + \theta_{\text{bias}}$, rotating the phone $180^\circ$ in place cancels accelerometer zero-offset bias and camera-bump tilt identically:
  $$\theta_{\text{table}} = \frac{\theta_0 - \theta_{180}}{2}$$
  Aggregated across a $3 \times 3$ table grid into `TableSlopeMap` with `UNLEVEL_WARNING_THRESHOLD_RAD = 0.0017453292519943296` ($0.1^\circ \approx 1.75\,\text{mm/m}$).
- **Slow-roll lateral drift inversion (`estimateSlopeFromSlowRoll`):** Combining rolling stopping time $t_{\text{stop}} = \sqrt{\frac{2 L_{\text{roll}}}{\mu_r g}}$ with downslope rolling acceleration $a_{\text{slope}} = \frac{5}{7} g \sin\theta$ gives sideways drift $\Delta y = \frac{1}{2} a_{\text{slope}} t_{\text{stop}}^2 = \frac{5 L_{\text{roll}} \sin\theta}{7 \mu_r}$, which inverts cleanly to:
  $$\theta = \arcsin\!\left(\operatorname{clamp}\!\left(\frac{7\,\mu_r\,\Delta y}{5\,L_{\text{roll}}},\; -0.15,\; 0.15\right)\right)$$
  Companion `predictSlowRollWithSlope` adds $\frac{1}{2} \cdot \frac{5}{7} g \sin\theta_{x,y} \cdot t_{\text{stop}}^2$ to nominal rolling stopping coordinates and clamps to playable cushion bounds.
- **Platform sensor bridge & Calibration UI:** `TiltSensorContract.kt` defines `expect class PlatformTiltSensorController` (the Android and iOS actuals are named for `SensorManager` and `CMMotionManager` but currently return the paired readings of the bundled sample sessions; live sensor reads are not implemented), `SampleCalibrationSessions` (`fastTournamentNineFootSession`, `slowBarBoxSevenFootSession`, `unlevelHomeEightFootSession`), and `CalibrationScreen.kt` (3rd tab in `App.kt`) lets players switch or calibrate `SavedTableProfile` instances and apply `SavedTableProfile.toEffectiveTableSpec()` directly to the planner.
