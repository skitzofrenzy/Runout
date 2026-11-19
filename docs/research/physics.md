# Physics model

The engine simulates ball motion so the planner can predict where the balls go and how spin changes the result. It follows the event-based approach described by Leckie and Greenspan and used by pooltool ([references](references.md)).

## Equipment defaults

Players can enter measured values; these are only defaults.

| Item | Value |
|---|---|
| Pool ball diameter | 57.15 mm (2¼ in) |
| Pool ball mass | 156–170 g (5½–6 oz) |
| Snooker ball diameter | 52.5 mm |
| 9 ft table, playing surface | 2.54 × 1.27 m (100 × 50 in) |
| 8 ft table, playing surface | 2.24 × 1.12 m (88 × 44 in) |
| 7 ft table, playing surface | about 1.98 × 0.99 m (78 × 39 in); varies by maker |
| Snooker table, playing surface | 3.569 × 1.778 m |

The playing surface is measured between the cushion noses. Bar tables and 8 ft tables vary, so table profiles always allow custom dimensions.

### Starting parameters

Rough ranges from the literature, used until a table is calibrated ([calibration](calibration.md)). Treat them as defaults, not truth.

| Parameter | Symbol | Typical range |
|---|---|---|
| Rolling resistance (cloth speed) | μr | 0.005–0.015 |
| Sliding friction, ball on cloth | μs | 0.15–0.25 |
| Spinning friction (vertical-axis spin decay) | μsp | fitted by calibration |
| Ball–ball friction | μb | 0.03–0.08 |
| Ball–ball restitution | eb | 0.92–0.98 |
| Cushion restitution | ec | 0.6–0.9, depends on speed |
| Cushion nose height | h | about 63–64% of the ball diameter |

## Ball motion states

| State | What happens |
|---|---|
| Sliding | The contact point slips on the cloth. Friction changes speed and spin until the ball rolls. |
| Rolling | No slip. The ball slows steadily because of rolling resistance. |
| Spinning | The ball has stopped moving across the cloth but still spins about the vertical axis. |
| Stationary | At rest. |
| Pocketed | Removed from play. |

Key results, with g = 9.81 m/s² and R the ball radius:

- **Rolling:** speed falls linearly at the rate μr·g. A ball rolling at speed *v* travels *d = v² / (2 μr g)* before it stops.
- **Sliding:** the slip speed at the contact point falls at the rate (7/2)·μs·g, so a ball with initial slip speed *u₀* starts rolling after *t = 2u₀ / (7 μs g)*.
- **Stun (centre-ball) hit:** a ball struck through its centre slides first, then rolls at 5/7 of its starting speed.
- **Vertical-axis spin:** decays at a constant rate proportional to μsp·g / R.

Between events, every ball moves along a path that is at most quadratic in time. That is what makes event-based simulation possible.

## Collisions

### Cue strike

Inputs: cue speed, tip offset (side *a* and vertical *b*), cue elevation and aim angle.

- The tip offset sets the initial spin. Offsets beyond roughly half the ball radius risk a miscue, so advice never goes past that.
- **Squirt (cue ball deflection):** side spin pushes the cue ball off the aim line, away from the side that was hit. The amount depends mostly on the shaft's end mass; low-deflection shafts squirt less. It is stored per cue in the player profile.
- **Swerve:** with an elevated cue, side spin curves the cue ball's path. Squirt and swerve partly cancel each other at some distances and speeds, which is why aiming with side spin is hard.

### Ball–ball

- Nearly elastic, with restitution eb.
- **90° rule:** after a stun shot, the cue ball leaves along the tangent line, at 90° to the object ball's path.
- **30° rule:** with natural roll, the cue ball deflects about 30° from its original line for hits between roughly a quarter-ball and a three-quarter-ball.
- **Throw:** friction between the balls pushes the object ball off the line of centres. Cut-induced throw is largest at slow speed with medium cut angles; side spin adds spin-induced throw. Throw is a few degrees at most, but that is enough to miss a pocket on a long shot.
- Clusters are solved as sequences of pairwise collisions. That is good enough for planning, but not exact for frozen clusters.

### Cushion

- Start with a simple model: restitution and friction on the normal and tangential velocity, with running or reverse english changing the rebound angle.
- Then adopt a full model: Han (2005), as used in pooltool, or Mathavan et al. (2010).
- The cushion meets the ball above its centre, which affects how spin changes off the rail. This is why nose height is a model parameter.

### Pockets

- Each pocket is modelled by its mouth width, jaw (facing) angle and shelf depth.
- A ball is pocketed if its centre crosses the mouth inside an acceptance window. The window narrows with the approach angle and with speed: fast balls that touch a jaw rattle out more often.
- Pocket geometry differs a lot between bar tables, professional 9 ft tables and snooker tables, so it is part of the table profile.

## Simulation method

- **Event-based:** work out the time of the next event (a collision, a change of motion state, a ball dropping) by solving polynomial equations, jump straight to it, and repeat. This is exact for the model and much faster than fixed time steps, which matters because the planner runs thousands of simulations.
- Ball–ball collision times need quartic root solving. Use a robust solver that handles near-double roots (grazing contacts) carefully.
- It lives in the pure Kotlin `engine` module. Validate it against analytic cases (the 5/7 rule, the stopping distance *v² / 2μr g*) and compare outcomes with pooltool for the same inputs.

## Uneven tables

A slope of angle θ adds a constant acceleration down the slope: (5/7)·g·sin θ for a rolling ball, and g·sin θ on a sliding ball (with friction acting separately). Within a region of constant slope the paths stay polynomial, so a table stored as a coarse grid of constant-slope cells (from the level check in [calibration](calibration.md#table-level)) only adds "cell boundary" events. This is an advanced option and off by default.

## Not modelled in the first version

- Jump and massé shots (airborne, 3D motion).
- Snooker cloth nap, which makes slow balls drift with the nap direction.
- Ball and cloth wear, humidity and temperature. Calibration captures these indirectly.

## Implemented in `:engine`

See [ADR-0002](../adr/0002-event-spin-physics-and-beam-planner.md) for the architectural trade-offs behind the Milestone 0–3 physics model in `engine/src/commonMain/kotlin/com/runout/engine/physics/`:

- **Analytic cloth motion (`MotionPhysics.kt`):** Implements exact SI formulas ($g = 9.81\,\text{m/s}^2$) for the $5/7$ stun rolling speed, slide-to-roll transition time $t = 2u_0 / (7\mu_s g)$, rolling stopping distance $d = v^2 / (2\mu_r g)$, inverse speed lookup $v = \sqrt{2\mu_r g d}$, and total stun stopping distance summing slide distance $12 v_{c0}^2 / (49\mu_s g)$ plus subsequent rolling distance.
- **Ghost-ball aiming (`GhostBallAiming.kt`):** Solves geometric contact points at separation $R_{\text{cue}} + R_{\text{ob}}$, checks playable cushion bounds and segment clearance against obstacle balls, and enforces the $80^\circ$ maximum cut limit.
- **Post-impact spin paths (`CueBallPath.kt`):** Evaluates the 90° stun tangent rule, 30° natural-roll follow rule, and backspin draw paths with mirror cushion bounce and scratch-pocket capture checks.
- **2D tip offset & spin compensation (`SpinPhysics.kt`):**
  - Models `TipOffset(sideRatio, verticalRatio, elevationRadians)` radially clamped to the miscue-safe disk $\sqrt{(a/R)^2 + (b/R)^2} \le 0.50$.
  - **Squirt:** $\delta_{\text{squirt}} = -k_{\text{shaft}} (a/R)$, with $k_{\text{shaft}} = 0.044$ for `LOW_DEFLECTION` shafts and $0.076$ for `STANDARD` shafts (pushing the cue ball away from the struck side).
  - **Swerve:** $\delta_{\text{swerve}} = +0.18\,(a/R)\sin(\theta_e)\,\text{clamp}\!\left(\frac{d_{\text{cue}\to\text{ghost}}}{0.85\,v_0},\,0.15,\,1.4\right)$, curving the ball back toward the tip side under cue elevation $\theta_e$.
  - **Cut & spin throw:** Using ball–ball friction $\mu_b = 0.055$ and speed factor $f_v = \text{clamp}(1.6 / (v_0 + 0.8), 0.35, 1.35)$, computes cut-induced throw $\arctan\!\left(\frac{\mu_b \sin\phi}{\cos\phi + \mu_b |\sin\phi|}\right) f_v$ and spin-induced (gearing) throw $-0.85\,\mu_b\,(a/R)\,f_v$. `solveCompensatedAim` shifts the ghost-ball impact vector by $-\theta_{\text{throw}}$ and reports `netCueAimAdjustmentRadians = -(squirt + swerve)`.
  - **Han (2005)-inspired spin cushion rebound (`traceCueBallPathWithSpin`):** Simulates up to 3 cushion bounces using speed-dependent normal restitution $e_{c,\text{eff}} = \text{clamp}(e_c - 0.035\,|v_n|,\,0.55,\,0.90)$ and english-coupled tangential rebound $v_{t,\text{out}} = 0.82\,v_{t,\text{in}} + 0.55\,(a/R)\,0.72^{k}\,|v_n|$ (decaying vertical-axis spin by $0.72$ per bounce $k$), widening running-english angles and steepening check-english rebounds while checking pocket scratch capture on every segment.
