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

- **One-shot & 2-ball baseline (`OneShotPlanner.kt`):** Ranks legal object-ball and pocket combinations across candidate speeds (or an explicit override) and vertical spin presets (`STUN`, `FOLLOW`, `DRAW`). The base score of a pot is its make probability for this player (the same function as the run-out planner, below); on top come a next-ball position bonus (`+0.25`) and a `-0.15` draw penalty when `maxEffectiveDrawRatio < 0.8`.
- **Execution error and make probability (`ExecutionErrorModel.kt`):**
  - **The aim error is one number.** `PlayerProfile.aimErrorStdRad` is the standard deviation of the angle between the cue line the player meant and the one he played. The defaults are not measured: they are anchored to drill percentages estimated for each level (straight-in pots into a tournament corner pocket of a 9 ft table at pocket speed, `directionalMakeProbability` at the slow-ball jaw tolerance), one size per level fitted by least squares to two drills, and the player's own calibration replaces them:

    | Straight-in, cue ball to object ball / object ball to pocket | Beginner (`0.0059` rad, 0.34°) | Intermediate (`0.0025` rad, 0.14°) | Advanced (`0.00155` rad, 0.09°) |
    |---|---|---|---|
    | 1.0 m / 1.0 m (target about 1 in 3, 2 in 3, 9 in 10) | 0.32 | 0.67 | 0.88 |
    | 1.2 m / 1.2 m, the long straight-in drill (target 1 in 5, 1 in 2, 7 in 10) | 0.22 | 0.49 | 0.72 |
    | 0.5 m / 0.5 m (not fitted) | 0.92 | 1.00 | 1.00 |

    With the full model (speed error, arrival-speed tolerance) the same sizes give, for beginner / intermediate / advanced: a 30° cut over 1 m and 1 m 0.26 / 0.57 / 0.80; a 60° cut with 1.5 m to the ghost ball and 1 m to the pocket 0.09 / 0.20 / 0.31; a side-pocket straight-in over 0.5 m and 0.5 m 0.87 / 1.00 / 1.00; a side-pocket pot 30° off the rail over 0.4 m and 0.4 m 0.34 / 0.70 / 0.90 (`MakeProbabilityTest` prints the table).
  - **P(make) is a probability mass.** Each cue line meets the object ball at its own contact point, and the object ball leaves along the line of centres there (to first order its direction turns by the cue-line error times `d / (2R cos(cut))`, `d` from cue ball to ghost ball). The object ball drops when that direction lies inside the pocket's entry window (`PocketGeometry.entryWindow`) at the jaw tolerance of its arrival speed. Each edge of the window is mapped back exactly, through the ghost-ball geometry, to the cue-line angle that sends the ball along it (an edge beyond a grazing hit maps to the graze), and P(make) is the normal probability mass between those two angles (`directionalMakeProbability`). The window is in general not centred on the aimed line; the integral takes each side as it is. Speed error decides whether the object ball reaches the pocket's capture line and how fast it arrives (and so the jaw tolerance); it is integrated with a three-point Gauss-Hermite rule over the speed fraction (`makeProbability`). The speed error is tied to the aim error (`0.045 + 9.2 x aimErrorStdRad`): about 10 %, 7 % and 6 % of the stroke speed for the beginner, intermediate and advanced profiles, also estimates.
  - **Side spin costs accuracy.** A player using side has to allow for squirt, swerve and the spin part of the throw, and misjudges that allowance by 25 % of its size (one standard deviation, a modelling value), added to the aim error in quadrature (`cueLineErrorStdRad`).
  - **One function everywhere.** The run-out search, the position maps (`HeatZoneGenerator.shotQuality`, which has no stroke and takes the window at the nominal arrival speed) and `OneShotPlanner` all use `directionalMakeProbability`.
  - **Landing cloud.** `evaluateShotUncertainty` still runs the deterministic 9-point sigma-point stencil over (cue-line angle, speed fraction, side tip, vertical tip); every point is simulated with the full table simulation from its own exact contact geometry (a different side tip also changes the squirt). It gives `scratchProbability`, the 9-point `landingCloud` (and which of its points scratch), `meanLandingPosition` and a 95 % `landingSpreadRadiusMeters`. It no longer feeds P(make).
  - `pocketAcceptanceHalfAngleRadians` is the half-angle of that entry window at a given arrival speed. It narrows with distance, with a shallower approach to the mouth and with speed, and closes altogether when the ball cannot pass between the points (a side pocket from 1 m closes at about 22° to the rail for a slow ball and 28° for a fast one; a corner pocket stays open down to a ball running along the rail).
- **Position maps (`HeatZoneMap.kt`):** `HeatZoneGenerator.computeForNextBalls` builds a grid (`16 × 8` by default) scoring each playable cue-ball cell with the best make probability on the next target ball (a `0.75 / 0.25` blend when a second target is given), with $2.05R$ ball-overlap exclusion and line-of-sight shadows. It draws the heat-zone overlay in the UI; the search evaluates its leaves lazily instead (below).
- **Problem-ball & breakout detection (`ProblemBallAnalyzer.kt`):** Scans legal targets (and the 8-ball in 8-ball) to flag `NO_CLEAR_POCKET` (zero unobstructed pockets), `CLUSTERED_NEIGHBOURS` (neighbours within $2.8 R$), and `FROZEN_TO_RAIL` (within $R + 3\,\text{mm}$ of a cushion with $\le 1$ open pocket). Picks the cleanest open **insurance ball** ($\ge 2$ clear pockets, zero neighbours within $2.8 R$) and detects whether a cue-ball path passes within $2.3 R$ of a problem ball (`checkTrajectoryBreakout`, measured along the sampled curve of the path rather than its chords).
- **Multi-shot expectimax beam planner (`MultiShotPlanner.kt`):**
  - Searches `1..5` shots ahead (`beamWidth = 6`) or full-rack `deepAnalysis` (`1..6` shots, `beamWidth = 9`), moving an 8-ball player to the `8` once the group is cleared.
  - **The value of a plan** is the product of P(make and no scratch) over its shots, times the product of control and hold over its shots, times the leave:
    - *Control* is one factor per stroke, each cause counted once: a cue-ball route of two cushions (×0.90) or three (×0.80, then −0.05 per cushion, floor 0.60); a tip offset beyond `0.25 R` toward the miscue limit (down to ×0.80 at the limit); a speed above 6.5 (−5 % per unit) or below 2.0 (−4 % per unit). The game-winning shot needs no position, so its cushions cost nothing. Cut, distance and side spin are not in it: they are in P(make).
    - *Hold* is the share of the next shot's make probability that survives the player's error: the next shot's best make probability averaged over the non-scratching points of the landing cloud, divided by its value at the nominal landing (at most 1).
    - *Leave*, inside the horizon, is the next shot's best make probability from the landing. At the end of the horizon it is a **one-shot look ahead** (`leafValue`): for the two best next pots from the landing, the make probability times the best leave it gives on the ball after, over six strokes (centre, follow and draw at speeds 3 and 5, each traced among the other balls for up to two cushions and charged for cushions like *control*). Rotation games look at the next ball in order, 8-ball at the best remaining ball. When the next ball is the last one, the leave is its make probability. Next-shot and leaf values are cached lazily per table (object balls to 5 mm) and per 5 cm cell.
    - *8-ball rack term:* each ball of the player's group (or the 8) still flagged by the problem-ball analysis on the table a shot leaves multiplies the leave by `0.85`, so a break-out played now, while other balls of the group remain to be played after it, beats one put off past the horizon. The explanations name the ball that is still tied up.
  - **Strokes.** A coarse grid first (7 speeds × 16 tips on the first shot, 3 speeds × 8 tips deeper; the deeper tips are the first shot's centre-line and mild follow-side tips, so a planned step can be reproduced when it becomes the first shot), then **refinement** of the best stroke of each target-pocket pair (and of the distinct-route alternative kept on the first shot): the speed moves in 0.5 steps in each direction while the plan improves (up to six steps, within 1 to 9.5), then one 0.09 step of the vertical tip either way. A pinned speed or tip is not refined. When two strokes give plans within 0.2 % of each other, the one at a pace closer to 4 (a medium speed) and then closer to centre ball is played. Before the last ply, two further cue-ball routes per target-pocket pair (one on later plies) are kept besides the best, each landing at least 25 cm from the others: when the next pot is easy from all of them, only the plies after can tell them apart.
  - **Rejected shots.** A shot is only searched if its nominal simulation pots the target in the intended pocket, drops no other ball (no plan counts on a carom, and in 8-ball an early 8 loses), and the nominal cue ball does not scratch, and if its scratch probability stays below 0.45. When the caller allows a first shot that fails these (`allowHighScratchFirstShot`, used to show the result of a user's edit), it ends the plan with the leave floor and always ranks below every plan that pots.
  - Produces ranked `RunoutPlan` sequences with cumulative run-out probability, `nextBallHeatZone`, `problemBalls`, and plain-English `explanationReasons`, including what the one-shot look ahead saw at the end of the plan. `positionSuccessProbability` on a step is the probability of not scratching and then making the next shot from where the cue ball stops, averaged over the landing cloud. An optional `firstStepNumber` numbers the steps from a later shot.
  - **Measured** on the JVM (`PlannerBenchmarkTest`, median of nine runs after warm-up): a 3-shot plan on a full nine-ball rack takes 84-155 ms over four runs (53-80 ms before the changes above, over two runs), on a nine-ball photo layout 74-83 ms (58-62 ms), on an 8-ball table with five solids left about 160 ms (about 260 ms). The runs shared the machine with other builds, so the spread is wide. Each stroke candidate costs nine full table simulations (its landing cloud); the leaf adds up to twelve single-ball traces for each cache cell it has not seen. The split of the time between them was not profiled.
  - Includes `evaluateAgainstGreedyBaseline` to verify across multi-ball racks that beam-search planning with 2D spin and speed control achieves a strictly higher run-out rate than a fixed-speed, zero-english greedy 1-shot baseline.
  - **Scenarios** (`PlannerScenarioTest`) are typed-in layouts with assertions a pool player would agree with: a stop shot when the next ball is close; the right side of the ball; no draw with side when a natural stroke gets there; a shallow side-pocket pot rated Hard; a down-the-rail pot rated lower at speed; a hanger; an 8-ball break-out while the insurance ball is still on the table; the key ball for the 8; bringing the cue ball up-table when the easy balls are at the foot; skill ordering; and three nine-ball layouts typed from photos of real tables.

### No direct pot (`FallbackShotAdvisor.kt`)

When the legal ball cannot be potted directly the run-out planner returns no plan. `FallbackShotAdvisor.advise` then gives one recommendation of one of three kinds. This is a first step: one recommendation chosen by a fixed rule, no search over several shots, and no model of the opponent beyond "has a direct pot or not" (the fuller version would estimate the opponent's chance of running out from the leave). No banks, caroms, jumps or massé.

- **Two-ball combination.** The legal ball goes at the ghost position of a second ball for a pocket (`PocketGeometry.aimPoint`), with all three legs clear, the first contact cut at most 75° and the second at most 60°. Rotation games allow any second ball; 8-ball only the shooter's own group (never an opponent's ball, never the 8 early). Throw at both contacts is allowed for in the aim (`BallBallContact.lineOfCentresForThrownDirection`), and the ball speeds come from the contact law and the cloth. The make probability propagates the cue-line error exactly through both contacts: to first order the second ball's direction turns by the error times `d1 / (2R cos(cut1))` times `d2 / (2R cos(cut2))`, so a combination is much less forgiving than either pot alone. The change of throw with the error is neglected. The probability is the normal mass of cue-line angles that keep the second ball inside the pocket's entry window at its arrival-speed jaw tolerance, averaged over the player's speed error; three speeds are tried.
- **Kick to hit.** Only when no line of contact on the legal ball is clear. For each cushion, centre-ball directions within 12° of the mirror-image line are traced every 0.25° with the table simulation (the real cushion rebound and cloth), at two speeds. A direction counts when the cue ball meets the legal ball after exactly one cushion, touches no other ball on the way and does not drop in a pocket. The aim is the middle of the longest run of good directions; the chance of a legal hit is the normal mass of the cue-line error over the run around the aim, at the three speeds of the speed error.
- **Basic safety.** When the legal ball can be hit but not potted: seven lines of contact (full, ±20°, ±40°, ±60°), three speeds and centre ball or draw are simulated on the whole table. A stroke qualifies when the cue ball hits the legal ball first, nothing drops and a ball reaches a cushion after the contact. The leave is scored by the share of the opponent's (target ball, pocket) pairs hidden from where the cue ball stops (`HeatZoneGenerator.computePocketVisibility` and `isPointOccludedForPocket` on the resulting table). For the three best, nine perturbed strokes (speed and line of contact) give the chance that every pair is hidden.
- **Selection.** A combination at or above a 40 % make chance (a modelling choice) beats a safety; a safety beats a kick; a kick is offered only when there is no direct hit. A weaker combination is offered only when neither a safety nor a kick is found. When the run-out planner has a pot, there is no advice.
- The app shows the advice as one card in place of the recommended shot and draws its lines on the table; the rolling plan simply ends where no pot exists.

### Rolling plan (`RollingRunoutPlanner.kt`)

The search above plans a fixed horizon from one table. `RollingRunoutPlanner` lets the user walk that plan shot by shot without losing the look-ahead. It is a pure `state -> state` layer over `MultiShotPlanner.planRunout`; it adds no new search.

- **Window.** `RollingPlanState` holds the frozen `acceptedSteps` and a `window` of the next `horizon` shots from the focus (`focus = acceptedSteps.size`). On a 9-ball rack with a 3-shot horizon the window is balls 1-3; accepting shot 1 plans a full horizon from the table that shot leaves, so the window becomes 2-4, then 3-5. Near the end of the rack the window is shorter; after the game ball it is `null` and `isRackComplete` is true. `PlannedShotStep.stepNumber` counts from the base table (accepted plus window), and "Step n" mentions in the explanations are shifted to match.
- **Accepted steps are frozen.** They are never re-planned; the table and rules at the focus come from the last accepted step's `resultingLayout` and `rulesAfterPotting` folded over the accepted targets, so accepting a solid on an open 8-ball table assigns solids for the rest of the window.
- **Overrides.** `setOverride` pins any of tip, speed, pocket and target ball on the focused step (the planner's `customFirstShot*` arguments) and re-plans, so later steps re-fit around it. An edit that pots nothing still produces a window so the user can see the result, but `acceptAndAdvance` refuses it with a reason (ball not potted, cue ball stops short, cue ball scratches).
- **Stepping back.** `back` reopens the previous step with exactly the view that was shown before it was accepted and loads its choices as the override; accepting again without an edit restores the later view unchanged.
- **Stability.** Steps the user has already seen are not reshuffled by every advance. After accepting, the fresh best plan is compared with the best plan whose first shot is the previously shown next shot (same target, pocket, tip and speed, obtained by passing it as the custom first shot). The fresh plan replaces it only when its `expectedValueScore` is at least 5 % higher. Whatever then differs from what was shown is reported in two sets: `updatedStepNumbers` for a different target ball or pocket (a route change) and `refinedStepNumbers` for the same target and pocket with a different tip or speed (a stroke refinement). Both are stored with the view, so stepping back and forward restores them, and the same split is reported when an edit re-fits the later steps.
- **User edits.** `userEditedStepNumbers` (numbers counted from the base) records the steps the user explicitly overrode, so the UI can mark them; accepted steps keep their mark. It is separate from `focusOverride`, which `back` also fills with the reopened step's own choices, and `isFocusEdited` tells the two apart. Clearing an override after `back` drops the pinned choices and returns to the planner's own choice for that step. Stepping back leaves the marks unchanged; editing an earlier step discards the restored future and prunes the marks of steps that no longer exist; `start` and `rebase` clear them.
- **Moving a ball.** `rebase` clears the accepted steps and makes the edited table the new base.
- **Cost.** One planner call per action, two when the fresh plan and the shown continuation disagree. Results are memoised in an immutable cache carried in the state, keyed by table, rules, player profile, horizon and override.
