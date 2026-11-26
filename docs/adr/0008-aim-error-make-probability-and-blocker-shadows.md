# ADR 0008: Make probability as aim-error probability mass inside the pocket entry window with blocker shadows

- **Status:** Accepted *(formalizes and supersedes the sigma-point survival make-probability heuristic in [ADR-0002](0002-event-spin-physics-and-beam-planner.md))*
- **Date:** 2026-11-25

## Context

The original uncertainty model in [ADR-0002](0002-event-spin-physics-and-beam-planner.md) estimated `P(make)` by multiplying a closed-form Gaussian term by an ensemble survival fraction taken from the same 9-point sigma-point stencil. That double-counted aim error, produced values that were not calibrated probabilities, and diverged from the score used by one-shot ranking and position heat zones.

In addition, checking only whether the nominal centre line was clear missed neighbouring balls beside either the object ball's path to the pocket or the cue ball's path to the object ball: a ball sitting millimetres off the centre line leaves a narrow margin of error even when the nominal line is unobstructed.

## Options Considered

- **Option A — Random Monte Carlo sampling of aim and speed perturbations:** Accurate in the limit, but non-deterministic and too slow (30–100 trials per candidate) inside a multi-shot beam search.
- **Option B — Sigma-point ensemble survival fraction:** Deterministic and fast, but coarsely discretizes narrow pocket entry windows and double-counts error when combined with analytic formulas.
- **Option C — Exact normal probability mass of a single cue-line aim error mapped through ghost-ball geometry into the pocket entry window, subtracting blocker shadows on both paths and integrating speed error via 3-point Gauss-Hermite quadrature (Selected):** Deterministic, smooth, physically grounded, and shared across every planner and UI readout.

## Decision

### 1. Aim-error probability mass with blocker shadows (`ExecutionErrorModel.directionalMakeProbability`)

- The player's execution error in aim is one normally distributed cue-line angle error with standard deviation `PlayerProfile.aimErrorStdRad` (floored at `1e-4 rad`). When side spin is applied (`abs(tip.clampedSideRatio) >= 1e-9`), `ExecutionErrorModel.cueLineErrorStdRad` adds `SIDE_SPIN_JUDGEMENT_ERROR_FRACTION` (`0.25`) of the side-spin allowance (`|squirt| + |swerve| + throwAsCueLine`) in quadrature.
- Given `PocketGeometry.entryWindow` at the arrival-speed `jawTolerance`, `directionalMakeProbability` computes the surviving probability mass in four steps:
  1. **Object-ball shadows:** Starting from the open arc `[limitA, limitB]` of object-ball directions, each other non-cue ball on the table removes the angular cone of directions along which the object ball would contact it before reaching `PocketGeometry.captureLine`. Cones lying wholly before the capture line are subtracted analytically; partly cut-back cones are sampled at 24 points and refined with 40 bisection steps per transition edge.
  2. **Exact ghost-ball mapping:** Each surviving object-ball arc edge is mapped back to the cue-line angle relative to `AimGeometry.aimDirection` through the exact ghost-ball geometry (`objectBallDirectionForCueLine`, clamping beyond the `89.5°` grazing cut). Because the map is monotonic, each arc becomes an interval of cue-line angles.
  3. **Cue-ball shadows:** The same shadow subtraction removes cue-line angles along which the cue ball would contact another ball before reaching the object ball.
  4. **Normal mass:** The make probability is the sum of `normalCdf(hi / sigma) - normalCdf(lo / sigma)` across the surviving disjoint intervals (Abramowitz and Stegun 7.1.26 approximation, absolute error `< 1.5e-7`).

### 2. Speed-error quadrature (`ExecutionErrorModel.makeProbability`)

- Speed error (`ExecutionErrorModel.speedErrorFraction(playerProfile)`) is integrated using a 3-point Gauss-Hermite rule over standard deviations `{0, +sqrt(3), -sqrt(3)}` with weights `{2/3, 1/6, 1/6}`.
- At each speed node, `makeProbability` evaluates `SpinPhysics.computePreImpactCueState` (discarding nodes that stop short), `MotionPhysics.transferredObjectBallInitialSpeedMps` and `MotionPhysics.objectBallStoppingDistanceMeters` (discarding nodes that do not reach `PocketGeometry.distanceToCaptureLine`), and the arrival speed from `MotionPhysics.slideThenRollSpeedAfterDistanceMps` to set `PocketGeometry.jawTolerance`, then weights `directionalMakeProbability`.

### 3. Unified use across planners, heat zones, and pruning

- `ExecutionErrorModel.makeProbability` is the single make-probability function used by `MultiShotPlanner`, `OneShotPlanner` (reported as `ShotRecommendation.makeProbability`), and the UI "Make %" readouts.
- `HeatZoneGenerator.shotQuality` calls `ExecutionErrorModel.directionalMakeProbability` at `PocketGeometry.NOMINAL_ARRIVAL_SPEED_MPS` (`1.5 m/s`) for stroke-free position maps and look-ahead leaves.
- `ExecutionErrorModel.makeProbabilityUpperBound` bounds `makeProbability` at the more lenient of `jawToleranceSlow` and `jawToleranceFast` using the base `aimErrorStdRad` (plus `1e-5` slack), enabling exact branch pruning in `MultiShotPlanner` before running full simulations.
- The deterministic 9-point sigma-point stencil (`ExecutionErrorModel.SIGMA_WEIGHTS` in `evaluateShotUncertainty` and `evaluateShotUncertaintyIf`) is retained solely for the cue-ball `landingCloud`, `scratchProbability`, `meanLandingPosition`, and `landingSpreadRadiusMeters`.

## Consequences

- **Positive:** Make probabilities are true, deterministic probabilities in `[0, 1]` that agree across multi-shot planning, one-shot ranking, position maps, and UI cards; shots threaded close to neighbouring balls are penalized continuously; admissible upper-bound pruning speeds up `MultiShotPlanner` without changing results.
- **Known limits:** Default `PlayerProfile.aimErrorStdRad` values, `speedErrorFraction`, and `SIDE_SPIN_JUDGEMENT_ERROR_FRACTION` are estimates awaiting calibration against real play; shadow occlusion accounts for static neighbouring balls before object-ball contact and before the pocket capture line, not moving balls or post-impact cue-ball kisses.

## Code References

- `engine/src/commonMain/kotlin/com/runout/engine/planner/ExecutionErrorModel.kt` (`ExecutionErrorModel`, `ShotUncertaintyResult`, `directionalMakeProbability`, `makeProbability`, `makeProbabilityUpperBound`, `cueLineErrorStdRad`, `speedErrorFraction`, `evaluateShotUncertainty`, `evaluateShotUncertaintyIf`, `SIGMA_WEIGHTS`, `SIDE_SPIN_JUDGEMENT_ERROR_FRACTION`)
- `engine/src/commonMain/kotlin/com/runout/engine/physics/PocketGeometry.kt` (`PocketGeometry.entryWindow`, `PocketGeometry.jawTolerance`, `PocketGeometry.captureLine`, `PocketGeometry.distanceToCaptureLine`, `PocketGeometry.NOMINAL_ARRIVAL_SPEED_MPS`)
- `engine/src/commonMain/kotlin/com/runout/engine/model/PlayerProfile.kt` (`PlayerProfile.aimErrorStdRad`)
- `engine/src/commonMain/kotlin/com/runout/engine/planner/HeatZoneMap.kt` (`HeatZoneGenerator.shotQuality`)
- `engine/src/commonMain/kotlin/com/runout/engine/planner/OneShotPlanner.kt` (`OneShotPlanner.rankShots`)
- `engine/src/commonMain/kotlin/com/runout/engine/planner/MultiShotPlanner.kt` (`MultiShotPlanner`, `RunoutPlan.cumulativeRunoutProbability`)
