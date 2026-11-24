# ADR 0002: Spin-effects physics, deterministic sigma-point error model, and expectimax beam-search planner

- **Date:** 2026-11-16
- **Status:** Accepted

## Context

Milestone 3 extends Runout from a 1–2 ball geometric planner into a multi-shot run-out strategy engine that accounts for side and vertical spin (`english`), throw, squirt, swerve, cushion spin rebound, player execution uncertainty, position heat zones, and problem-ball break-outs:

1. **Spin physics vs. runtime budget:** The strategy planner evaluates hundreds of candidate `(targetBall, pocket, speedScale, tipOffset)` combinations per rack state across 2–5 shots ahead. The design target for each candidate evaluation is well under `0.1 ms` on a single core (not measured on a phone; see the amendments for measured planner times) while faithfully capturing squirt, swerve, cut/spin-induced throw, and running/reverse english off the cushions.
2. **Uncertainty integration:** Estimating make probability `P(make)` and the cue-ball landing spread requires propagating player execution errors in aim ($\sigma_\phi$), speed ($\sigma_v$), and tip contact ($\sigma_a, \sigma_b$). Unseeded random Monte Carlo introduces test non-determinism and requires 30–100 trials per candidate to converge.
3. **Multi-shot lookahead & explainability:** Looking 2–5 balls ahead over continuous table coordinates branches exponentially unless pruned, cached on spatial position grids, and paired with explicit problem-ball / insurance-ball detection to produce human-readable tactical explanations.

## Options Considered

### 1. Physics & Cushion Model
- **Option 1A — Fixed-timestep numerical integration ($dt = 1\text{ ms}$):** Simple to write, but ~50–100× slower than event-based analytic evaluation and misses grazing contacts unless $dt$ is tiny.
- **Option 1B — Event-based analytic trajectory with throw/squirt/swerve compensation and Han (2005) impulse cushion rebound (Selected):** Evaluates cue-strike squirt and swerve, ball-ball cut/spin throw, sliding-to-rolling transition, and impulse-based cushion rebound (coupling tangential friction and vertical-axis side spin $\omega_z$ with a normal restitution $e_c$) in closed form per segment.

### 2. Execution-Error Propagation
- **Option 2A — Pure random Monte Carlo (30–50 random samples per candidate):** Non-deterministic across runs and computationally expensive at depth $\ge 3$.
- **Option 2B — Deterministic stratified sigma-point ensemble + optional seeded Monte Carlo for rack self-play (Selected):** Uses a symmetric 9-point Gauss-Hermite / sigma-point stencil over $(\Delta\phi, \Delta v, \Delta a, \Delta b)$ scaled by the player's `PlayerProfile` standard deviations. Produces deterministic landing clouds and scratch probabilities in a fraction of the cost (P(make) itself moved to a closed form; see the amendments), while `evaluateAgainstGreedyBaseline` compares multi-rack run-out results with a greedy one-shot baseline.

### 3. Multi-Shot Search & Heat Zones
- **Option 3A — Unpruned depth-first expectimax:** Intractable beyond 2 balls when evaluating multiple speeds and 2D tip offsets.
- **Option 3B — Expectimax with beam pruning, cached spatial Heat-Zone grids, and problem-ball breakout scoring (Selected):** Precomputes `HeatZoneMap` position values on a coarse table grid for upcoming targets, prunes each search ply to `beamWidth` candidates, awards tactical bonuses when a cue-ball path breaks open a clustered/rail-frozen problem ball while an open insurance ball is available, and emits plain-English tactical explanations.

## Decision

We adopt **Option 1B**, **Option 2B**, and **Option 3B** inside the pure Kotlin Multiplatform `:engine` module (`com.runout.engine.physics` and `com.runout.engine.planner`):

1. **`SpinPhysics.kt` (`com.runout.engine.physics`):**
   - Models 2D cue-tip offset `TipOffset(sideRatio: Double, verticalRatio: Double, elevationDegrees: Double = 3.0)` clamped to the miscue-safe disk $\sqrt{(a/R)^2 + (b/R)^2} \le 0.50$.
   - Computes shaft-dependent **squirt** angle, elevation-dependent **swerve** angle, and speed/friction/spin-dependent **throw** angle (cut-induced + spin-induced), returning a `SpinCompensatedAim` that shifts the ghost-ball aim point so the object ball enters the pocket center after throw and squirt/swerve.
   - Applies the Han (2005) cushion impulse to the ball's full state (velocity, spin about both horizontal axes and about the vertical axis), with cushion restitution, ball-cushion friction and nose height as table parameters; the ball leaves the cushion usually sliding, and the slide on the cloth then sets the line it rolls along (see the amendments below).
2. **`ExecutionErrorModel.kt` (`com.runout.engine.planner`):**
   - Evaluates a candidate shot across a deterministic sigma-point perturbation ensemble scaled by `PlayerProfile` errors ($\sigma_\phi$, $\sigma_v$, $\sigma_a$, $\sigma_b$) and pocket acceptance windows (narrowed by steep approach angles and high arrival speeds).
   - Outputs `ShotUncertaintyResult(makeProbability: Double, scratchProbability: Double, landingCloud: List<Vec2>, meanLandingPosition: Vec2, landingSpreadRadiusMeters: Double)`.
3. **`HeatZoneMap.kt`, `ProblemBallAnalyzer.kt`, and `MultiShotPlanner.kt` (`com.runout.engine.planner`):**
   - `HeatZoneMap`: Computes a `cols × rows` spatial grid of position values (`0.0..1.0`) for the next 1–3 legal target balls so both the beam search and the Compose `PoolTableCanvas` can query or render heat zones directly.
   - `ProblemBallAnalyzer`: Flags `NO_CLEAR_POCKET`, `CLUSTERED_NEIGHBOURS`, and `FROZEN_TO_RAIL` problem balls, identifies the best open **insurance ball**, and detects candidate shots whose cue-ball path breaks out a problem ball.
   - `MultiShotPlanner`: Performs expectimax beam search `2..5` shots ahead (plus `deepAnalysis` full-rack mode), produces multi-step `RunoutPlan` sequences with player-scale speed (`1–10`), tip-offset advice, landing clouds, heat zones, and plain-English tactical explanations, and includes `evaluateAgainstGreedyBaseline` to verify higher rack run-out rates than a greedy 1-shot baseline.

## Consequences

- **Pure KMP:** All spin physics, sigma-point uncertainty, heat-zone grids, and beam-search planning remain pure `commonMain` Kotlin in SI units.
- **Deterministic tests & self-play verification:** Every unit test in `:engine` and `:composeApp` is 100% deterministic, and `evaluateAgainstGreedyBaseline` compares multi-shot planning with greedy 1-shot selection across racks.

## Amendment (2026-11-24): every ball carries its velocity and spin

The first implementation of Option 1B moved the cue ball after contact along one straight line, the direction it would finally roll in, at an "equivalent rolling" speed chosen to give the right stopping distance; the object ball was launched the same way, the cue ball kept nothing along the line of centres, and later collisions were a frictionless impulse on velocity alone. Paths, scratch checks and kisses were therefore evaluated along lines the balls never travel (a drawn cue ball ran up to a third of a metre off its real path at 3 m/s, and a rolling cue ball meeting a second ball deflected 60° instead of about 34°).

The closed-form segments of Option 1B are now the two phases of the [vector sliding law](../research/physics.md#ball-motion-states): a slide along a parabola, with the slip direction fixed, until `t = 2|u0| / (7 μs g)`, then a straight roll. Every ball carries a `BallState` (velocity, spin about the horizontal axes, spin about the vertical axis); ball–ball contacts apply the restitution law with both balls' real velocities, the existing throw law and unchanged spins; the cushion response is one replaceable function from state to state. Event times stay closed-form or bracketed: a quadratic for each cushion line, and Bézier-hull subdivision for the first root of the contact quartic. The 90° and 30° rules and draw angles follow from the law rather than being coded. The planner-facing results keep their shapes; paths gain a densely sampled polyline for drawing the curves. Measured on a 3-shot nine-ball plan on the JVM, the planner's run time rose by roughly 10–15 %.

## Amendment (2026-11-24): the cushion impulse and spin about the vertical axis

The cushion response was a restitution-and-friction rule on the centre velocity (`0.82`/`0.86` of the tangential speed, `0.55` of the english per unit of normal speed, english kept at `0.72` per cushion) that sent every ball off rolling. It is now the impulse model of Han (2005) acting on the full ball state, with cushion restitution `e_c`, ball-cushion friction `f_c` and cushion nose height on `ClothParams`, so rebound angles depend on how the ball arrives (rolling, stun or draw) and the cloth bends them afterwards. Spin about the vertical axis is part of the ball state: it wears off on the cloth through spinning friction `μsp`, changes at every cushion, is handed on by the sideways throw impulse at ball–ball contacts, and what is left at the object ball feeds the throw. Calibration fits `e_c` from the head-on drill and `f_c` from the bank drill through the same model. Han's single impulse has known limits (no speed dependence, no bed friction during the impact, and little effect of english on a ball that arrives rolling); they are listed in the [physics note](../research/physics.md#cushion) and Mathavan et al. (2010) is the fuller model to adopt if they matter.

## Amendment (2026-11-24): make probability, stroke refinement and the leaf of the search

Option 2B used the sigma-point ensemble for everything, including P(make): a closed form `exp(-½(σ/h)²)` multiplied by an ensemble survival term built from the same aim error, with the aim error scaled by an unexplained factor on the way. That was neither a probability nor consistent with the cue-ball paths of the same ensemble. Three changes, all deterministic:

1. **P(make) is computed, not sampled.** The player's aim error is one standard deviation of the cue-line angle. Each edge of the pocket's entry window is mapped exactly, through the ghost-ball geometry, to the cue-line angle that produces it, and P(make) is the normal probability mass between them; speed error enters through a three-point Gauss-Hermite rule (reach and arrival-speed jaw tolerance). The same function serves the search, the position maps and the one-shot ranking. The 9-point sigma-point stencil stays for what it is good at: the cue ball's landing cloud, scratch probability and spread, each point simulated from its own exact contact.
2. **Strokes are refined.** The speed × tip grid is now the coarse pass. The best stroke of every target-pocket pair is hill-climbed in half speed steps and one vertical tip step while the plan's value improves, at every ply, so a step planned deep in the search carries a stroke that the first ply can reproduce.
3. **The leaf looks one shot further.** The value of the last landing of a plan is no longer a static position map but the best next pot times the leave it gives on the ball after it, from a handful of traced strokes, cached per table and cell; in 8-ball problem balls still tied up lower it. Inside the horizon the next ply's real evaluation replaces it, as before.

The beam search itself is unchanged: same plies, same beam widths, same pruning. Measured on the JVM, a 3-shot nine-ball plan takes 84-155 ms against 53-80 ms before on a full rack (runs on a shared machine, wide spread) and an 8-ball plan less (about 160 ms against about 260 ms); the leaf evaluates only the cells the search visits. Before the last ply the search also keeps up to two further cue-ball routes per target-pocket pair, because with near-certain pots a route can only be judged by the shots after it.
