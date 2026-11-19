# Strategy engine

Chooses which ball to play, how to play it, and where the cue ball should end up, looking several shots ahead.

## The problem

- **State:** ball positions, which balls are legal targets, and the rules state (open table, groups, ball in hand, fouls).
- **Action:** target ball, pocket, aim angle, speed, tip offset (side and vertical) and cue elevation.
- **Outcome:** uncertain, because nobody executes perfectly.

The planner maximises the chance of winning the rack (or, with a shorter horizon, of running the next *N* balls) for this player's skill.

## Execution-error model

Each player has a standard deviation for:

- aim angle (σφ);
- speed, as a fraction of the intended speed (σv);
- tip offset (σa, σb).

Defaults come from the player profile ([calibration](calibration.md#player-profile)), and calibration drills refine them. Simulating each candidate shot many times with random errors gives:

- **P(make):** the fraction of simulations that pocket the target with a legal hit;
- **Cue ball landing spread:** where the cue ball stops, as a cloud of points.

## Candidate shots

For each legal target ball and each pocket:

1. Skip it if the path is blocked or the cut angle is more than about 80°.
2. Aim at the ghost ball, then sample speeds (6–8 levels) and spins (3–5 vertical × 3 side).
3. Later milestones add banks, kicks, combinations and caroms.

## Position value and heat zones

For a cue ball position *p* and the next target *t*, the shot value is the best P(make) over all pockets and shot parameters. Computed over a grid of cue ball positions (cells of 2–5 cm), this is a **heat zone**: green where the next shot is easy, red where it is hard or blocked.

Looking 2–3 balls ahead chains these values:

> value_k(p) = max over shots s of [ P(make s) × expected value_k+1(landing position of s) ]

The heat zone for "where should the cue ball land now?" is value_k drawn over the table. Overlaying the recommended shot's landing cloud shows how forgiving its speed is.

## Search

- Depth-limited expectimax over shots, with beam pruning: keep only the best few candidates at each level.
- Iterative deepening under a time budget. Two or three shots ahead returns quickly; deep analysis keeps going towards a full run-out.
- Cache position-value grids. The value of landing at *p* for the next target doesn't depend on how the cue ball got there.
- **Misses:** a miss hands the table to the opponent. Start with a fixed penalty; later, estimate the opponent's chance of running out from the layout left behind. That also makes safety play possible.

### Back-of-envelope cost

About 10 target–pocket pairs × 40 speed and spin combinations × 30 noisy samples is roughly 12,000 simulations per level. At 0.1 ms per simulation that is about 1 second per level on one core. Deeper searches therefore need beam pruning, caching and several cores. Measure this on a real mid-range phone early.

## Game-specific rules

| Game | What changes |
|---|---|
| 8-ball | Choose a group while the table is open; choose the order; plan the key ball before the 8; the 8 must be last |
| 9-ball | Must hit the lowest-numbered ball first; any legal pocketed ball counts; combinations and caroms are common; push-out on the shot after the break |
| 10-ball | Same lowest-ball rule as 9-ball, but call shot: a ball that goes in by luck doesn't count |
| Snooker | Alternate a red and a colour; colours are re-spotted until the reds are gone, then potted in order; scoring by ball value; safety play is central |

## Problem balls

A ball is a problem when it has:

- **No pocket:** its position-value grid is close to zero everywhere the cue ball can reasonably reach;
- **Neighbours:** it touches, or is within about one ball width of, other balls that block its pocket paths;
- **A rail:** it is frozen to a cushion with only one realistic pocket.

### Breaking them up

- Look for shots that pocket a ball and send the cue ball into the cluster on its way to position (secondary break-outs), as well as direct break-out shots.
- Simulate each break-out many times to estimate how often it actually opens the cluster, and where the cue ball ends up.
- **When:** break out early, while an easy *insurance ball* is still available to shoot next. Avoid break-outs that leave no shot. The planner compares "break out now" with "play on and break out later" through the same search.

## Explaining the advice

Every recommendation comes with a short reason, for example: "Play the 3 first: it clears the path to the 5, and the 6 is your insurance ball." The search keeps enough information to generate this.

## Validation

- **Known patterns:** compare against patterns from instruction books and experienced players.
- **Self-play:** simulate many racks with error models for different skill levels. Better plans should run out more often than a greedy "easiest shot next" baseline.
- **Player feedback:** let players mark advice as good or bad.

## Prior work

Computer pool players built for the Computer Olympiad pool events in the mid-2000s, notably PickPocket (Smith, 2007) and CueCard (Archibald et al., 2009), used similar ideas: sampling noisy shots, simulating them with physics, and searching a few shots ahead. See the [references](references.md).

## Implemented in `:engine`

See [ADR-0002](../adr/0002-event-spin-physics-and-beam-planner.md) for the design rationale behind the Milestone 1–3 strategy modules in `engine/src/commonMain/kotlin/com/runout/engine/planner/`:

- **One-shot & 2-ball baseline (`OneShotPlanner.kt`):** Ranks legal object-ball and pocket combinations across player speed scales `(3, 5, 7)` (or explicit override) and vertical spin presets (`STUN`, `FOLLOW`, `DRAW`), combining cut angle, distance weighted by `PlayerProfile.aimErrorStdRad`, pocket mouth width, next-ball position bonus (`+0.25`), and a `-0.15` draw penalty when `maxEffectiveDrawRatio < 0.8`.
- **Execution-error & pocket acceptance model (`ExecutionErrorModel.kt`):**
  - `pocketAcceptanceHalfAngleRadians` models jaw narrowing from target-to-pocket distance, effective ball-pocket clearance, and arrival speed (faster shots rattle jaws more easily).
  - `evaluateShotUncertainty` propagates `PlayerProfile` aim error ($\sigma_\phi$) and speed/tip uncertainty through a **deterministic 9-point sigma-point stencil** over $(\Delta\phi, \Delta v/v, \Delta a, \Delta b)$ combined with analytical Gaussian pocket-capture survival (`gaussianMakeProb * weightedReachFrac * (0.55 + 0.45 * weightedEnsembleMake)`, clamped to `0.02..0.99`). This yields reproducible `makeProbability`, `scratchProbability`, a 9-point `landingCloud`, `meanLandingPosition`, and a 95% `landingSpreadRadiusMeters` ($1.96 \times \text{RMS}$ spread) without Monte Carlo variance in tests.
- **Cached spatial position grids (`HeatZoneMap.kt`):** `HeatZoneGenerator.computeForNextBalls` builds a spatial table grid (`16 × 8` default, `24 × 12` inside `MultiShotPlanner`) scoring each playable cue-ball cell `(x, y)` in `0.0..1.0` for the primary next target ball (or a `0.75 / 0.25` blend when a secondary target is present), checking $2.05R$ ball-overlap exclusion, line-of-sight clearance, cut angle, distance, and player aim error. `HeatZoneMap.valueAt` provides $O(1)$ spatial queries during search and powers the cloth heat-zone overlay in the UI.
- **Problem-ball & breakout detection (`ProblemBallAnalyzer.kt`):** Scans legal targets (and the 8-ball in 8-ball) to flag `NO_CLEAR_POCKET` (zero unobstructed pockets), `CLUSTERED_NEIGHBOURS` (neighbours within $2.8 R$), and `FROZEN_TO_RAIL` (within $R + 3\,\text{mm}$ of a cushion with $\le 1$ open pocket). Simultaneously picks the cleanest open **insurance ball** ($\ge 2$ clear pockets, zero neighbours within $2.8 R$) and detects whether a candidate cue-ball polyline passes within $2.3 R$ of a problem ball (`checkTrajectoryBreakout`).
- **Multi-shot expectimax beam planner (`MultiShotPlanner.kt`):**
  - Searches `1..5` shots ahead (`beamWidth = 5`) or full-rack `deepAnalysis` (`1..6` shots, `beamWidth = 8`), correctly transitioning an 8-ball player to the `8` ball once their group clears.
  - Evaluates candidate `(speedScale, TipOffset)` pairs with spin-compensated aiming (`SpinPhysics.solveCompensatedAim`), uncertainty scoring, future heat-zone value, and breakout bonuses when an insurance ball is available, skipping shots with scratch probability $\ge 0.45$.
  - Produces ranked `RunoutPlan` sequences with cumulative run-out probability, `nextBallHeatZone`, `problemBalls`, and plain-English `explanationReasons`.
  - Includes `evaluateAgainstGreedyBaseline` to verify across multi-ball racks that beam-search planning with 2D spin and speed control achieves a strictly higher run-out rate than a fixed-speed, zero-english greedy 1-shot baseline.
