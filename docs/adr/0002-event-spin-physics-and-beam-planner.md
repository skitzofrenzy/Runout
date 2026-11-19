# ADR 0002: Spin-effects physics, deterministic sigma-point error model, and expectimax beam-search planner

- **Date:** 2026-11-16
- **Status:** Accepted

## Context

Milestone 3 extends Runout from a 1–2 ball geometric planner into a multi-shot run-out strategy engine that accounts for side and vertical spin (`english`), throw, squirt, swerve, cushion spin rebound, player execution uncertainty, position heat zones, and problem-ball break-outs:

1. **Spin physics vs. runtime budget:** The strategy planner evaluates hundreds of candidate `(targetBall, pocket, speedScale, tipOffset)` combinations per rack state across 2–5 shots ahead. Each candidate evaluation must remain well under `0.1 ms` on a single core while faithfully capturing squirt, swerve, cut/spin-induced throw, and running/reverse english off the cushions.
2. **Uncertainty integration:** Estimating make probability `P(make)` and the cue-ball landing spread requires propagating player execution errors in aim ($\sigma_\phi$), speed ($\sigma_v$), and tip contact ($\sigma_a, \sigma_b$). Unseeded random Monte Carlo introduces test non-determinism and requires 30–100 trials per candidate to converge.
3. **Multi-shot lookahead & explainability:** Looking 2–5 balls ahead over continuous table coordinates branches exponentially unless pruned, cached on spatial position grids, and paired with explicit problem-ball / insurance-ball detection to produce human-readable tactical explanations.

## Options Considered

### 1. Physics & Cushion Model
- **Option 1A — Fixed-timestep numerical integration ($dt = 1\text{ ms}$):** Simple to write, but ~50–100× slower than event-based analytic evaluation and misses grazing contacts unless $dt$ is tiny.
- **Option 1B — Event-based analytic trajectory with throw/squirt/swerve compensation and Han (2005)-inspired spin cushion rebound (Selected):** Evaluates cue-strike squirt and swerve, ball-ball cut/spin throw, sliding-to-rolling transition, and impulse-based cushion rebound (coupling tangential friction and vertical-axis side spin $\omega_z$ with speed-dependent normal restitution $e_c$) in closed form per segment.

### 2. Execution-Error Propagation
- **Option 2A — Pure random Monte Carlo (30–50 random samples per candidate):** Non-deterministic across runs and computationally expensive at depth $\ge 3$.
- **Option 2B — Deterministic stratified sigma-point ensemble + optional seeded Monte Carlo for rack self-play (Selected):** Uses a symmetric 9-point (or 17-point in deep mode) Gauss-Hermite / sigma-point stencil over $(\Delta\phi, \Delta v, \Delta a, \Delta b)$ scaled by the player's `PlayerProfile` standard deviations. Produces deterministic, variance-accurate `P(make)` and landing clouds in a fraction of the cost, while `SelfPlayEvaluator` uses a deterministic PRNG (`XorWow` / LCG) to simulate multi-rack run-out percentages against the greedy baseline.

### 3. Multi-Shot Search & Heat Zones
- **Option 3A — Unpruned depth-first expectimax:** Intractable beyond 2 balls when evaluating multiple speeds and 2D tip offsets.
- **Option 3B — Expectimax with beam pruning, cached spatial Heat-Zone grids, and problem-ball breakout scoring (Selected):** Precomputes `HeatZoneMap` position values on a coarse table grid for upcoming targets, prunes each search ply to `beamWidth` candidates, awards tactical bonuses when a cue-ball path breaks open a clustered/rail-frozen problem ball while an open insurance ball is available, and emits plain-English tactical explanations.

## Decision

We adopt **Option 1B**, **Option 2B**, and **Option 3B** inside the pure Kotlin Multiplatform `:engine` module (`com.runout.engine.physics` and `com.runout.engine.planner`):

1. **`SpinPhysics.kt` (`com.runout.engine.physics`):**
   - Models 2D cue-tip offset `TipOffset(sideRatio: Double, verticalRatio: Double, elevationDegrees: Double = 3.0)` clamped to the miscue-safe disk $\sqrt{(a/R)^2 + (b/R)^2} \le 0.50$.
   - Computes shaft-dependent **squirt** angle, elevation-dependent **swerve** angle, and speed/friction/spin-dependent **throw** angle (cut-induced + spin-induced), returning a `SpinCompensatedAim` that shifts the ghost-ball aim point so the object ball enters the pocket center after throw and squirt/swerve.
   - Implements Han (2005)-inspired cushion rebound where vertical-axis english ($a/R$) modifies post-cushion tangential velocity (running english widens and speeds up the rebound; check/reverse english steepens and slows the rebound) and normal speed slightly reduces $e_c$.
2. **`ExecutionErrorModel.kt` (`com.runout.engine.planner`):**
   - Evaluates a candidate shot across a deterministic sigma-point perturbation ensemble scaled by `PlayerProfile` errors ($\sigma_\phi$, $\sigma_v$, $\sigma_a$, $\sigma_b$) and pocket acceptance windows (narrowed by steep approach angles and high arrival speeds).
   - Outputs `ShotUncertaintyResult(makeProbability: Double, scratchProbability: Double, landingCloud: List<Vec2>, meanLandingPosition: Vec2, landingSpreadRadiusMeters: Double)`.
3. **`HeatZoneMap.kt`, `ProblemBallAnalyzer.kt`, and `MultiShotPlanner.kt` (`com.runout.engine.planner`):**
   - `HeatZoneMap`: Computes a `cols × rows` spatial grid of position values (`0.0..1.0`) for the next 1–3 legal target balls so both the beam search and the Compose `PoolTableCanvas` can query or render heat zones directly.
   - `ProblemBallAnalyzer`: Flags `NO_CLEAR_POCKET`, `CLUSTERED_NEIGHBOURS`, and `FROZEN_TO_RAIL` problem balls, identifies the best open **insurance ball**, and detects candidate shots whose cue-ball path breaks out a problem ball.
   - `MultiShotPlanner`: Performs expectimax beam search `2..5` shots ahead (plus `deepAnalysis` full-rack mode), produces multi-step `RunoutPlan` sequences with player-scale speed (`1–10`), tip-offset advice, landing clouds, heat zones, and plain-English tactical explanations, and includes `SelfPlayEvaluator` to verify higher rack run-out rates than a greedy 1-shot baseline.

## Consequences

- **Pure KMP & `< 0.1 ms` performance:** All spin physics, sigma-point uncertainty, heat-zone grids, and beam-search planning remain pure `commonMain` Kotlin in SI units with zero allocations in inner math helpers.
- **Deterministic tests & self-play verification:** Every unit test in `:engine` and `:composeApp` is 100% deterministic, and `SelfPlayEvaluator` quantitatively verifies that multi-shot planning outperforms greedy 1-shot selection across held-out racks.
