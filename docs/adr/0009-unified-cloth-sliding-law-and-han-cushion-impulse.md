# ADR 0009: Unified sliding-then-rolling cloth law and Han (2005) cushion impulse

- **Status:** Accepted *(supersedes the straight-line equivalent-speed cue-ball path and empirical cushion rebound multipliers originally adopted in [ADR-0002](0002-event-spin-physics-and-beam-planner.md); the cushion model may be superseded by a fuller impulse model in the future)*
- **Date:** 2026-11-25

## Context

The initial physics implementation under [ADR-0002](0002-event-spin-physics-and-beam-planner.md) moved the cue ball after impact along one straight line at an equivalent rolling speed, treated centre-ball strikes as a shortcut separate from spin strikes, and rebounded balls off cushions with fixed tangential and side-spin multipliers that always sent the ball off rolling. That produced large positional errors on draw and curved cut shots, gave wrong secondary collision deflections, and prevented cushion friction calibration from sharing a physical model with the simulator.

At the same time, the planner evaluates thousands of shots per search and requires closed-form, event-based motion between discrete contacts.

## Options Considered

- **Option A — Empirical 90°/30° departure rules and fixed cushion rebound percentages:** Fast, but inaccurate on curved post-impact slides, multi-ball secondary contacts, and spin-dependent cushion angles.
- **Option B — Full time-stepped or compression-integrated cushion dynamics (such as Mathavan et al., 2010) immediately:** Models bed friction during cushion compression and speed-dependent restitution, at higher implementation and calibration complexity before measured shot validation determines whether it is needed.
- **Option C — One closed-form sliding-then-rolling cloth law for every ball and tip height, paired with the Han (2005) cushion impulse behind a replaceable `CushionContact` boundary (Selected):** Captures curved slides, emergent 90°/30°/draw behaviour, and full-state cushion rebounds in closed form while isolating the cushion model for future replacement.

## Decision

### 1. Single sliding-then-rolling cloth law for every tip and every ball

- **Pre-impact cue ball (`SpinPhysics.computePreImpactCueState`):** One law governs every tip height `b/R` using the ideal strike spin factor `k0 = (5/2)(b/R)` (`SpinPhysics.initialSpinFactorForTip`) and the cloth's full sliding friction `muS`. While `k != 1`, friction `muS * g` opposes the slip until `v_roll = v0 (5 + 2 k0) / 7` (`MotionPhysics.slidingEndSpeedMps`), after which the ball rolls under `muR` (`MotionPhysics.rollingSpeedAfterDistanceMps`). A centre-ball strike (`k0 = 0`) is simply the `5/7` case (`d_slide = 12 v0^2 / (49 muS g)`), not a special case; `SpinPhysics.verticalTipRatioForImpactSpin` and `SpinPhysics.tipForImpactState` invert this law to compute the tip required for stun, follow, or draw at the object ball.
- **Vector cloth motion (`BallState`, `BallMotion`, `BallBallContact`, `TableCollisionSimulator`):**
  - Every ball carries a full `BallState`: `position`, `velocity`, horizontal spin as a surface velocity `spinVelocity` (`w = R * (omegaY, -omegaX)`), and vertical-axis spin `sideSpinSurfaceSpeedMps` (`R * omegaZ`).
  - While `slipVelocity = velocity - spinVelocity` exceeds `SLIP_EPSILON_MPS`, `BallMotion` evolves the ball along an exact parabola under constant deceleration `muS * g` against the slip direction until `t = 2|u0| / (7 muS g)` with `v_f = w_f = (5 v0 + 2 w0) / 7`, then transitions to straight rolling under `muR * g`. Vertical-axis spin decays linearly at `5/2 * muSp * g` (`BallMotion.sideSpinAt`).
  - The 90° stun rule, ~30° natural-roll rule, and draw/follow curves emerge directly from `BallMotion` and `BallBallContact.resolve` rather than being hardcoded.
  - Event times stay closed-form or bracketed: quadratics for cushions (`BallMotion.timeToReachCoordinate` / `timeToContactBound`) and Bézier-hull subdivision for two-ball contacts (`BallMotion.contactTime`). `TableCollisionSimulator` steps event-to-event within a per-simulation event budget (flagging `PostShotTableSimulation.truncated` if exhausted), and `BallMotion.chordPieces` samples curved slides within `1 mm` (`PATH_SAMPLE_TOLERANCE_METERS`) for rendering.

### 2. Han (2005) cushion impulse (`CushionContact`)

- `CushionContact.rebound(state, inwardNormal, cloth)` is the single replaceable state-to-state cushion function. It takes `ClothParams.cushionRestitutionEc`, `cushionFrictionFc`, and `cushionNoseHeightRatio` (contact normal tilt `sin(theta) = 2 * ratio - 1`, `CushionContact.contactTiltSin`).
- Using Han's (2005) rigid-body impulse equations, it applies normal impulse `(1 + e_c) v_n cos(theta)` and stick-or-slide tangential impulse (sticking when `(2/7)|s| <= f_c * J_r`, otherwise sliding at `f_c * J_r`), updating centre velocity and all three spin components (`wn`, `wt`, `wz`). Balls normally leave the cushion sliding, and `BallMotion` subsequently bends the path on the cloth (following balls come off long; drawing balls come off short).
- `CushionContact.pressedAgainst` handles balls resting on a cushion line whose spin pushes them into the rail, and `MIN_APPROACH_SPEED_MPS` (`1e-4 m/s`) plus `MIN_LEAVING_SPEED_MPS` (`2e-3 m/s`) prevent zero-time re-trigger loops.
- `TableCalibrationFitter` fits `cushionRestitutionEc` (head-on drill) and `cushionFrictionFc` (bank-spin drill) through the same `CushionContact` model.

## Consequences

- **Positive:** Pre-impact spin evolution, ball–ball throw and deflections, curved slides, cushion rebounds, and drill calibration all obey one consistent physical model in closed form; isolating cushions in `CushionContact.rebound` allows swapping the cushion law without modifying `BallMotion`, `TableCollisionSimulator`, or the planners.
- **Known limits and future supersession:** As recorded in [docs/research/physics.md § Cushion](../research/physics.md#cushion), Han's single-impulse model omits speed-dependent restitution and ball–bed friction during the cushion impact, and at the default `f_c = 0.20` a ball arriving rolling slides throughout the contact so side spin barely changes its final rebound angle. A fuller cushion model (such as Mathavan et al., 2010) may supersede `CushionContact.rebound` once measured shots quantify the impact of these limits.

## Code References

- `engine/src/commonMain/kotlin/com/runout/engine/physics/SpinPhysics.kt` (`SpinPhysics.initialSpinFactorForTip`, `computePreImpactCueState`, `verticalTipRatioForImpactSpin`, `tipForImpactState`, `sideSpinSurfaceSpeedAtImpactMps`, `computeThrowAnglesRadians`, `solveCompensatedAim`)
- `engine/src/commonMain/kotlin/com/runout/engine/physics/MotionPhysics.kt` (`MotionPhysics.slidingEndSpeedMps`, `slidingDistanceBetweenSpeedsMeters`, `slidingSpeedAfterDistanceMps`, `rollingSpeedAfterDistanceMps`, `slideThenRollSpeedAfterDistanceMps`)
- `engine/src/commonMain/kotlin/com/runout/engine/physics/BallMotion.kt` (`BallState`, `BallMotion`, `BallMotionPhase`, `BallBallContact`)
- `engine/src/commonMain/kotlin/com/runout/engine/physics/CushionContact.kt` (`CushionContact.rebound`, `CushionContact.pressedAgainst`, `CushionContact.contactTiltSin`, `MIN_APPROACH_SPEED_MPS`)
- `engine/src/commonMain/kotlin/com/runout/engine/physics/TableCollisionSimulator.kt` (`TableCollisionSimulator`, `PostShotTableSimulation`)
- `engine/src/commonMain/kotlin/com/runout/engine/model/TableSpec.kt` (`ClothParams`, `BallContactParams`)
- `engine/src/commonMain/kotlin/com/runout/engine/calibration/TableCalibrationFitter.kt` (`TableCalibrationFitter`)
