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
| Spinning friction (vertical-axis spin decay) | μsp | about 0.013 (default 0.0635·μs); fitted by calibration |
| Ball–ball friction | μb | 0.03–0.08 |
| Ball–ball restitution | eb | 0.92–0.98 |
| Cushion restitution (along the contact normal) | ec | 0.6–0.9, depends on speed |
| Ball–cushion friction | fc | about 0.2 |
| Cushion nose height | h | 62.5–64.5 % of the ball diameter (WPA equipment specifications) |

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
- **Stun (centre-ball) hit:** a ball struck through its centre slides first, then rolls at 5/7 of its starting speed. It slides for *d = 12 v₀² / (49 μs g)*, so at 2 m/s on ordinary cloth (μs = 0.20) it is still sliding, without spin at the strike and with ever more forward roll, for the first half metre only. Beyond that it rolls. A cue ball that must arrive at the object ball *without* spin over a longer distance needs backspin from a tip below centre (see [Cue ball before contact](#cue-ball-before-contact)).
- **Vertical-axis spin:** decays linearly at *5 μsp g / (2R)* (about 11 rad/s² on ordinary cloth, so the surface speed *Rω_z* falls at about 0.31 m/s²) whatever the ball is doing, and never reverses. It does not bend the path on a level table.
- **Sliding in any direction (vector form):** write the spin about the two horizontal axes as a surface velocity *w* (so the point touching the cloth slips at *u = v − w*, and a rolling ball has *w = v*). Friction μs·g acts along −*û*, and its torque turns *w* the other way at 5/2 of that rate, so *û* stays fixed for the whole slide: *v(t) = v₀ − μs g t û*, *w(t) = w₀ + (5/2) μs g t û*. The path is a parabola. The slide ends after *t = 2|u₀| / (7 μs g)* with *v = w = (5 v₀ + 2 w₀)/7*; from then on the ball rolls in a straight line. When the spin is along the velocity (a stun, follow or draw ball moving straight) this is the one-dimensional law above; when it is not (the cue ball just after a cut) the ball curves.

### Cue ball before contact

One sliding-then-rolling law applies to every tip height, with the cloth's full sliding friction. Write *k = R·ω / v* (+1 natural roll, 0 no spin, negative backspin), and *b* for the tip height above the ball's centre:

- **Strike:** *k₀ = (5/2)(b/R)*. Natural roll at *b = 0.4 R*; the miscue limit *b = 0.5 R* gives *|k₀| = 1.25*.
- **Sliding (k ≠ 1):** friction μs·g acts against the slip. For *k₀ < 1*, *v² = v₀² − 2 μs g d* and *R·ω = k₀ v₀ + (5/2)(v₀ − v)*; for overspin (*k₀ > 1*) the signs flip and the ball speeds up.
- **Slip ends** at *v_roll = v₀ (5 + 2 k₀) / 7*, after *d_slide = |v₀² − v_roll²| / (2 μs g)*. For *k₀ = 0* that is *5/7 v₀* after *12 v₀² / (49 μs g)*.
- **Rolling:** *v² = v_roll² − 2 μr g (d − d_slide)*, the rolling law above.
- Along the aim line *v* stays positive while *k₀ > −2.5*, which the tip limit guarantees, so a draw shot loses its backspin before contact but never reverses.

Consequences for a stop shot (no spin at contact, *k = 0*): the strike spin must be *k₀ = −(5/2)(1 − v/v₀)* with *v* the speed at contact. The strongest tip (*k₀ = −1.25*) reaches *k = 0* exactly when *v = v₀/2*, after *d = 3 v₀² / (8 μs g)*; any longer shot at that stroke speed arrives with forward roll whatever the tip. That is about 0.76 m at 2 m/s, 1.7 m at 3 m/s and 3.4 m at 4.2 m/s on ordinary cloth. Within that range the tip needed is lower the longer the shot and higher the harder the stroke.

Between events, every ball moves along a path that is at most quadratic in time. That is what makes event-based simulation possible.

## Collisions

### Cue strike

Inputs: cue speed, tip offset (side *a* and vertical *b*), cue elevation and aim angle.

- The tip offset sets the initial spin, *k₀ = (5/2)(b/R)* for a level cue (see [Cue ball before contact](#cue-ball-before-contact)). Offsets beyond roughly half the ball radius risk a miscue, so advice never goes past that. Real tips transfer slightly less spin than this ideal near the miscue limit; the model uses the ideal law until a measured efficiency curve is available.
- **Squirt (cue ball deflection):** side spin pushes the cue ball off the aim line, away from the side that was hit. The amount depends mostly on the shaft's end mass; low-deflection shafts squirt less. It is stored per cue in the player profile.
- **Swerve:** with an elevated cue, side spin curves the cue ball's path. Squirt and swerve partly cancel each other at some distances and speeds, which is why aiming with side spin is hard.

### Ball–ball

- Nearly elastic, with restitution eb. For equal masses and a cut angle φ, the object ball takes (1 + eb)/2 · v cos φ along the line of centres and the cue ball keeps (1 − eb)/2 · v cos φ along it, plus all of v sin φ across it. The contact (a few hundred microseconds) does not change either ball's spin.
- **90° rule:** after a stun shot, the cue ball leaves along the tangent line, at 90° to the object ball's path. That is exact only for eb = 1: the speed it keeps along the line of centres puts the real stun line atan((1 − eb)/2 · cot φ) inside the tangent, about 3.5° at half-ball and 11° at a 10° cut for eb = 0.93. With no spin the cue ball then slides and rolls along that straight line.
- **30° rule:** a rolling cue ball leaves along the same line as a stun ball but still carries its forward roll, so the slide bends it forward onto (5 v′ + 2 w)/7: about 33.7° from its original line at half-ball for eb = 1 (31.5° for eb = 0.93), and close to 30° between roughly a quarter-ball and a three-quarter-ball.
- **Draw and follow:** the same slide law bends a drawing cue ball back behind the tangent line and a following one forward of it; the curve ends where the slip ends, 10–30 cm from the contact on a firm shot. Straight on, follow runs on at (5 · (1 − eb)/2 + 2)/7 of the arrival speed (2/7 for eb = 1) and draw comes back at (2|k| − 5 · (1 − eb)/2)/7; the restitution term makes a straight follow travel about a fifth farther, and a straight draw come back shorter, than the eb = 1 rule.
- **Throw:** friction between the balls pushes the object ball off the line of centres, toward the cue ball's line of travel. It follows the sliding speed at the contact point (Alciatore, technical proof A.14; see [references](references.md)). With $v$ the cue ball's speed at impact, $\phi$ the cut angle, $R\omega_x = k v$ its follow/draw spin at impact and $R\omega_z$ its side spin, the surfaces slide at $v_{\text{rel}} = \sqrt{(v\sin\phi - R\omega_z)^2 + (R\omega_x\cos\phi)^2}$, the friction coefficient falls with that speed, $\mu(v_{\text{rel}}) = a + b\,e^{-c\,v_{\text{rel}}}$ ($a \approx 0.00995$, $b \approx 0.108$, $c \approx 1.088$ s/m for typical phenolic balls), and the throw is $\theta = \arctan\!\left(\min\!\left(\frac{\mu v\cos\phi}{v_{\text{rel}}},\,\tfrac17\right)\frac{v\sin\phi - R\omega_z}{v\cos\phi}\right)$, where $1/7$ is the no-slip (gearing) limit. For a stun shot with no english this reduces to $\theta = \arctan\mu(v\sin\phi)$: about $4^\circ$ for a half-ball at 1 m/s, falling to about $1.8^\circ$ at 3 m/s, peaking for cuts between roughly $25^\circ$ and $45^\circ$ and shrinking again toward thin cuts. A rolling cue ball throws two to three times less, because the vertical sliding takes friction away from the lateral direction. English that closes the lateral sliding ($R\omega_z = v\sin\phi$, "outside" english) gears the throw to zero and beyond it reverses it. Opposite english adds throw only where the no-slip limit holds the plain throw down (slow shots, thinner cuts): a half-ball stun already throws close to the most the friction curve allows. A straight-in shot with side spin throws the object ball the way the cue ball's contact surface moves, away from the side of the english. Throw is a few degrees at most, but that is enough to miss a pocket on a long shot, and a player who aims at the geometric ghost ball misses to the thick side.
- **English handed on:** the sideways throw impulse acts at the balls' equators, so it turns both balls about the vertical axis the same way, *Δ(Rω_z) = 5/2 · j_t* each (*j_t* the sideways impulse per unit mass). This is the gearing that gives the object ball a little english; it reduces the sliding of the surfaces and never adds energy.
- Clusters are solved as sequences of pairwise collisions. That is good enough for planning, but not exact for frozen clusters.

### Cushion

- The cushion meets the ball above its centre, which affects how spin changes off the rail. This is why nose height is a model parameter.
- **Model in use: Han (2005).** One impulse at the contact point on the cushion nose, which sits *h − R* above the ball's centre, so the contact normal tilts down into the bed by θ with sin θ = (h − R)/R (0.27 for the default nose height). In the cushion frame (*n* into the cushion, *t* along it, spins as surface speeds *W = Rω*): the contact point approaches along the normal at *c = v_n cos θ* and the normal impulse is *(1 + e_c) c*; it slips at *s₁ = −v_n sin θ − W_t* and *s₂ = v_t + W_z cos θ − W_n sin θ*; a tangential impulse changes that slip by 7/2 of itself, so the contact sticks if *(2/7)|s| ≤ f_c (1 + e_c) c* and otherwise slides with friction *f_c (1 + e_c) c* against the slip. The impulse changes the velocity and all three spins; the bed takes the vertical part. The ball leaves in whatever state that gives, usually sliding, and the cloth then bends its path: that is where a following ball comes off long and a drawing ball short. With the defaults, at 1 m/s: a rolling ball 30° in rolls away at 45°, 45° in at 64° and 60° in at 78°; a stun ball 45° in leaves at 41.5° and a draw ball (k = −0.5) at 33°. The cushion also puts running english on a ball that had none, and takes most of the reverse english off.
- **Where Han's model falls short.** (1) It has no speed dependence: the rebound ratio is the same at 1 and 2 m/s, while real cushions get deader with speed. (2) There is no friction between ball and bed during the impact, which Mathavan et al. (2010) include, together with an impact integrated over the compression. (3) A ball that arrives rolling slides on the cushion throughout the impact for any cushion friction below about 0.25 (30°), 0.31 (45°) or 0.43 (60°); the friction it can take is then shared between its large vertical slip and its sideways slip, so english changes its rebound little once the cloth has finished the curve. At 1 m/s and 45° a rolling ball with 0.3 of running english rolls away at 64.0°, with none at 64.4° and with reverse at 67.3° (at 60°: 75.0°, 78.0°, 79.8°), against the common experience that running english lengthens a kick. Balls that arrive with little roll behave as expected (stun, 45°: 54.4°, 41.5°, 40.3°; draw, 45°: 49.2°, 33.3°, 26.0°). A bank drill on a real table fits a higher *f_c* if its english acts more strongly; a fuller impulse model is the proper fix.

### Pockets

- Each pocket is modelled by its mouth width, jaw (facing) angle and shelf depth.
- A ball is pocketed if its centre crosses the mouth inside an acceptance window. The window narrows with the approach angle and with speed: fast balls that touch a jaw rattle out more often.
- Pocket geometry differs a lot between bar tables, professional 9 ft tables and snooker tables, so it is part of the table profile.
- **Implemented (`PocketSpec`, `PocketPreset`, `PocketGeometry.kt`).** A pocket is an opening cut into the cushions, not a circle on the corner: two jaw tips where the cushion noses end (corner tips *m/√2* along each rail from the corner, side tips *m/2* either side of the middle), the mouth between them (inside the nose rectangle for a corner, on the nose line for a side pocket), the facings at the tips at the facing angle, and the hole behind the nose line at the shelf depth. Presets: WPA tournament (4.5 in corners, 5 in sides, 142° / 104° facings, 1.75 in / 0.19 in shelves), a tighter pro cut, a looser bar box, and an approximate snooker cut (real snooker pockets have rounded falls).
  - **Entry window:** from a ball centre, the directions that pass both tips with clearance *jawTolerance · R*, bounded by the inner tangents to circles of that radius around the tips. Straight on to a side pocket at distance *d* its half-angle is *atan(m/2d) − asin(R/√(d² + m²/4))*. It narrows toward the rail and with speed; from 1 m a side pocket closes at about 22° to the rail for a slow ball (28° fast), while a corner stays open for a ball running along the rail (1.36° half-angle slow, 0.72° fast).
  - **Aim point:** where the window's bisector crosses the mouth line, which gives equal angular room on both sides. It is the middle of the mouth on the pocket's axis and stays within about a millimetre of it at other angles: the near tip needs a larger clearance angle, but seen obliquely the angular middle of the mouth falls toward the near tip, and the two nearly cancel.
  - **Jaw tolerance:** a fraction of the ball radius that rises linearly with arrival speed between 1 and 4 m/s: corners 0.60 → 1.05, sides 0.85 → 1.10. Below 1 a slow ball that brushes a facing still drops; above 1 a fast one that only just clears a point rattles out. At medium speed it puts the effective size of a pocket near the mouth less about two inches, the usual rule of thumb, and it narrows a corner approached along the rail by close to two to one between soft and firm, in line with published measurements. These are modelling values; calibrating them is owed.
  - **Capture on the real path:** the simulation checks every ball's closed-form path against each pocket: a corner mouth crossed on the cloth, or a cushion line reached where the nose is cut away by a mouth (for a side pocket that is the mouth moved one radius toward the table). There the ball drops if its line clears both tips by the tolerance for its speed at that moment, and it goes to the hole; otherwise it is a jaw hit and comes off the facing of the tip it passes closest to through the cushion impulse (off the tip itself if it is not moving into the facing). A cushion line inside a mouth gives no cushion bounce. A ball that starts past a capture line, or over the hole, is in the pocket.

## Simulation method

- **Event-based:** work out the time of the next event (a collision, a change of motion state, a ball dropping) by solving polynomial equations, jump straight to it, and repeat. This is exact for the model and much faster than fixed time steps, which matters because the planner runs thousands of simulations.
- Ball–ball collision times need quartic root solving. Use a robust solver that handles near-double roots (grazing contacts) carefully. The engine avoids a polynomial solver: the separation of two balls with constant accelerations is a quadratic Bézier curve over any time interval, so it stays inside the triangle of its control points; an interval whose triangle keeps clear of the contact distance is discarded, otherwise it is halved, earlier half first, down to a nanosecond. That finds the first touch, grazing ones included, and rejects far-apart pairs with one triangle test.
- It lives in the pure Kotlin `engine` module. Validate it against analytic cases (the 5/7 rule, the stopping distance *v² / 2μr g*) and compare outcomes with pooltool for the same inputs.

## Uneven tables

A slope of angle θ adds a constant acceleration down the slope: (5/7)·g·sin θ for a rolling ball, and g·sin θ on a sliding ball (with friction acting separately). Within a region of constant slope the paths stay polynomial, so a table stored as a coarse grid of constant-slope cells (from the level check in [calibration](calibration.md#table-level)) only adds "cell boundary" events. This is an advanced option and off by default.

## Not modelled in the first version

- Jump and massé shots (airborne, 3D motion).
- Snooker cloth nap, which makes slow balls drift with the nap direction.
- Ball and cloth wear, humidity and temperature. Calibration captures these indirectly.

## Implemented in `:engine`

See [ADR-0002](../adr/0002-event-spin-physics-and-beam-planner.md) for the architectural trade-offs behind the Milestone 0–3 physics model in `engine/src/commonMain/kotlin/com/runout/engine/physics/`:

- **Analytic cloth motion (`MotionPhysics.kt`):** Implements exact SI formulas ($g = 9.81\,\text{m/s}^2$) for the $5/7$ stun rolling speed, slide-to-roll transition time $t = 2u_0 / (7\mu_s g)$, rolling stopping distance $d = v^2 / (2\mu_r g)$, inverse speed lookup $v = \sqrt{2\mu_r g d}$, and total stun stopping distance summing slide distance $12 v_{c0}^2 / (49\mu_s g)$ plus subsequent rolling distance. It also holds the shared sliding helpers of the cue-ball law: the speed at which slipping ends for a strike spin factor $k_0$ ($v_0 (5 + 2k_0)/7$), the sliding distance between two speeds, the speed after sliding a given distance (slowing for $k_0 < 1$, speeding up for overspin) and the speed after rolling a given distance.
- **Ghost-ball aiming (`GhostBallAiming.kt`):** Solves geometric contact points at separation $R_{\text{cue}} + R_{\text{ob}}$, checks playable cushion bounds and segment clearance against obstacle balls, and enforces the $80^\circ$ maximum cut limit.
- **Ball state and the slide after contact (`BallMotion.kt`, `TableCollisionSimulator.kt`):** Every ball carries a `BallState`: position, velocity, the spin about the horizontal axes as a surface velocity $w = R(\omega_y, -\omega_x)$, and $R\omega_z$. `BallMotion` gives the [vector sliding law](#ball-motion-states) and the rolling law in closed form (`positionAt`, `velocityAt`, `spinAt`, `timeToRoll`, `timeToStop`, `endOfPhase`) and the event times: a cushion line crossed is the root of a quadratic (`timeToReachCoordinate`), two balls touching the first root of a quartic, found by Bézier-hull subdivision (`contactTime`). `BallBallContact` applies the [contact law](#ballball) with both balls' real velocities, the throw of `computeThrowAnglesRadians` evaluated in the struck ball's frame (relative velocity, the sum of both balls' spins sliding at the contact), the equal and opposite tangential impulse on the striking ball, and both spins unchanged; momentum is conserved and energy never rises without side spin. At the first contact the line of centres is the one whose throw sends the object ball along the aimed pot line (the contact of a player who aims at the compensated ghost ball). `TableCollisionSimulator` moves every ball from event to event (a slide ending, a ball stopping, a cushion, a pocket, a contact), so the cue ball leaves along the line the contact law gives, curves while follow or draw takes, and rolls straight; a ball struck later leaves sliding with the spin it had. The 90° and 30° rules and draw angles are not coded anywhere; they come out of the law. Paths are reported twice: `points` has a vertex at the start, at each cushion and ball contact, and at the end (so `points.size - 2` still counts contacts), and `sampledPoints` adds samples along every curve so each chord stays within 1 mm of the true path. `CueBallPath.computePostImpactPaths` (a given arrival speed and preset spin), `computePostImpactPathsForStroke` (a stroke, with the tip and arrival state of each preset) and `SpinPhysics.traceCueBallPathWithSpin` all run this simulation for the cue ball alone.
- **Cushion contact (`CushionContact.kt`):** The single function from a ball's state on arrival at a cushion to its state leaving it, used for every ball and by the calibration fitter: the [Han (2005) impulse model](#cushion) on the full state (velocity, spin about both horizontal axes and about the vertical axis), with `ClothParams.cushionRestitutionEc`, `cushionFrictionFc` and `cushionNoseHeightRatio`. A ball that would leave with under 2 mm/s away from the cushion runs along it instead of bouncing again and again.
- **Cue ball before contact (`SpinPhysics.computePreImpactCueState`):** Applies the [sliding-then-rolling law](#cue-ball-before-contact) to every tip offset, with the cloth's full $\mu_s$ and no special case for centre-ball hits, so the result is continuous in tip height, speed and distance. `initialSpinFactorForTip` is $k_0 = 2.5\,(b/R)$. `verticalTipRatioForImpactSpin` inverts the law (tip height that gives a wanted $k$ at contact for a given speed and distance, or `null` when no tip within the miscue limit does) and `tipForImpactState` uses it to turn a `CueBallSpinPreset` (`STUN`, `FOLLOW`, `DRAW`, which name the state at the object ball) into the tip that produces it for that shot. `TipOffset.fromPreset` remains a fixed nominal tip for display and short shots.
- **2D tip offset & spin compensation (`SpinPhysics.kt`):**
  - Models `TipOffset(sideRatio, verticalRatio, elevationRadians)` radially clamped to the miscue-safe disk $\sqrt{(a/R)^2 + (b/R)^2} \le 0.50$.
  - **Squirt:** $\delta_{\text{squirt}} = -k_{\text{shaft}} (a/R)$, with $k_{\text{shaft}} = 0.044$ for `LOW_DEFLECTION` shafts and $0.076$ for `STANDARD` shafts (pushing the cue ball away from the struck side).
  - **Swerve:** $\delta_{\text{swerve}} = +0.18\,(a/R)\sin(\theta_e)\,\text{clamp}\!\left(\frac{d_{\text{cue}\to\text{ghost}}}{0.85\,v_0},\,0.15,\,1.4\right)$, curving the ball back toward the tip side under cue elevation $\theta_e$.
  - **Cut & spin throw:** `computeThrowAnglesRadians` implements the [throw law](#ballball) with the cue ball's speed and follow/draw spin at impact (from `computePreImpactCueState`, not the stroke) and the side spin left at contact, supplied by `sideSpinSurfaceSpeedAtImpactMps`: the strike's $R\omega_z = -\tfrac52 (a/R)\,v_0$ worn down at $\tfrac52 \mu_{sp} g$ for as long as the cue ball takes to reach the object ball. It returns `ThrowAngles`: the total throw, the cut-only part (the same law with $R\omega_z = 0$) and the remainder, which the UI shows as the cut and spin rows. Sign convention, defined once on `ThrowAngles`: the cut angle is signed by `aim.angleTo(lineOfCentres)`; a throw has the sign of its cut and moves the object ball toward the aim line, so the thrown direction is `lineOfCentres.rotate(-θ)` and left english is positive spin for a positive cut. `solveCompensatedAim` finds the ghost ball whose thrown direction is the geometric pot line: it iterates $n = n_{\text{pot}}.\text{rotate}(+\theta(n))$, re-evaluating the cut angle, the cue ball's distance, speed and spin at each step, so the compensated hit is thinner than the geometric one, and reports `netCueAimAdjustmentRadians = -(squirt + swerve)`. The planners launch the object ball along the geometric direction, which is right for a player who aims at the compensated ghost ball. The ball–ball friction is calibrated as $\mu_b$, the friction at 1 m/s of sliding speed (default $\approx 0.046$), which scales the whole $\mu(v_{\text{rel}})$ curve. Constants $a$, $b$ and $c$ are those quoted for the proof's fit; $c = 1.088$ is used, while another proof by the same author quotes $0.77$ for the same expression, so the exponent is the first parameter to revisit if measured throw disagrees.
  - **Cue-ball path after contact (`traceCueBallPathWithSpin`):** Runs the simulation above for the cue ball from the contact, through up to `maxBounces` cushions ([`CushionContact`](#implemented-in-engine)), with any obstacle balls free to move when struck, checking pocket capture with the acceptance-circle test along every sampled chord.
- **Path inspector waypoints (`ShotPathWaypoints.kt`, `SpinPhysics.preImpactRegimeBoundaries`, `CueBallPathEvent`):** The tappable points on a planned cue-ball line come from the same models that draw it. Before contact, `preImpactRegimeBoundaries` returns the distances at which `computePreImpactCueState` changes regime (draw → stun at `k = −0.08`, stun → roll at `k = 0.18`, roll settled at `k = 0.92`, slip ended at `k = 1`), with the speed and spin factor there; for a sliding strike `R·ω = k₀v₀ + 2.5(v₀ − v)` gives the speed at a threshold `k` and the slip ends at `v = (5 + 2k₀)/7 · v₀`, the 5/7 rule with the spin correction. At contact the waypoint reports arrival speed and spin, the speed handed to the object ball and the cue ball's physical launch speed and direction, from the contact law; contact does not change the spin, so the cue ball keeps its `k` (relative to its arrival speed). The post-contact transition sits where the simulation's first `SLIDE_ENDS` event puts it, at the end of the curve, with the rolling speed `|(5 v′ + 2 w)/7|` there; distances along the path are measured on `sampledPoints`. Cushion and ball events are recorded by `TableCollisionSimulator`: speed in and out, the vertical spin factor along the travel before and after, and the english before and after written as the tip offset that would give that much side spin at the ball's speed then (`BallState.sideSpinAsTipOffset`); all are the simulated values. A centre-ball strike therefore reports slip ending at `5/7·v₀` after `12·v₀² / (49·μs·g)`, like every other tip. The object ball is launched at the transferred speed and slides before it rolls, so its speed on the way to the pocket (`MotionPhysics.slideThenRollSpeedAfterDistanceMps`, also used for the pocket acceptance window in the uncertainty model) is the real one, not that of a ball rolling the same distance.
