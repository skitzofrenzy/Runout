# ADR 0006: Jaw-tip pocket geometry and typed table identity on TableSpec

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

Modelling pockets as circular capture disks centred on the table corners treats corner and side pockets identically, ignores approach angle and arrival speed (fast balls rattle jaws that soft balls drop past), and leaves table rendering with no real jaw-tip or facing geometry for cushion cut-outs.

At the same time, determining whether a table is a pool or snooker table, or which pocket cut it uses, by inspecting display strings (`TableSpec.name`) or ad-hoc ball-radius checks breaks whenever a table is renamed or a calibration profile (`SavedTableProfile`) is applied. Ball–ball contact friction and restitution also belong to the equipment on the table so calibration can update throw friction without mutating global constants.

## Options Considered

- **Option A — Circular pocket radii plus name-based table identification:** Simple to construct, but cannot model shallow side-pocket rejection, speed-dependent jaw tightening, jaw-facing rebounds, or safe renaming of calibrated tables.
- **Option B — Explicit jaw-tip pocket geometry (`PocketSpec`, `PocketPreset`, `PocketGeometry`) and typed table identity and contact parameters (`TableKind`, `pocketPreset`, `BallContactParams`) on `TableSpec` (Selected):** Shares one physical representation across simulation, make probability, calibration, and canvas rendering, and separates display names from table identity.

## Decision

### 1. Pocket geometry (`PocketSpec`, `PocketKind`, `PocketPreset`, `PocketGeometry`)

- Each `PocketSpec` describes an opening cut into the cushion-nose rectangle in SI metres: two jaw tips (`jawTipA`, `jawTipB`) whose segment is the mouth (`mouthWidthMeters`, `mouthCenter`), an outward unit `axisIntoPocket`, a `facingAngleRad`, a `shelfDepthMeters`, a `holeRadiusMeters` (`holeCenter` behind the nose line), and speed-dependent clearances `jawToleranceSlow` and `jawToleranceFast`.
- `PocketPreset` defines four cuts (`TOURNAMENT` matching WPA 4.5 in corners / 5.0 in sides with 142° / 104° facings, `PRO_CUT`, `BAR_BOX`, and an approximate straight-facing `SNOOKER` cut) with `SLOW_ARRIVAL_SPEED_MPS = 1.0` and `FAST_ARRIVAL_SPEED_MPS = 4.0`.
- `PocketGeometry` provides the single set of geometric queries shared by physics, planning, and UI rendering:
  - `jawTolerance(pocket, arrivalSpeedMps)` linearly interpolates clearance (in multiples of ball radius `R`) between slow and fast arrival speeds.
  - `entryWindow(ballCenter, pocket, ballRadius, jawTolerance)` computes `EntryWindow` (`isOpen`, `limitA`, `limitB`, `bisector`, `halfAngleRadians`) from the inner tangents to circles of radius `jawTolerance * R` around `jawTipA` and `jawTipB`.
  - `aimPoint` intersects the window bisector with the mouth line; `captureLine` returns the mouth segment for `PocketKind.CORNER` and the mouth shifted inward by `R` for `PocketKind.SIDE`; `captureOnSegment`, `clearsJaws`, `containsRestingBall`, `isInMouth`, `distanceToCaptureLine`, and `facingNormal` govern simulation capture, jaw-facing rebounds, and recessed pocket/cushion rendering (`cushionSegments` in `TableRendering.kt`).

### 2. Typed table identity and ball contact on `TableSpec`

- `TableKind` (`POOL`, `SNOOKER`) defines `defaultBallRadiusMeters` (`TableSpec.POOL_BALL_RADIUS_METERS = 0.028575 m`, `TableSpec.SNOOKER_BALL_RADIUS_METERS = 0.02625 m`) and `defaultPocketPreset` (`TOURNAMENT`, `SNOOKER`). `TableSpec.name` is display-only; callers inspect `TableSpec.kind` instead of parsing names (for example `SavedTableProfile.toEffectiveTableSpec`, `TableSession.canApply`, and `TableSizePreset.fromTableSpec`).
- `TableSpec.pocketPreset: PocketPreset?` records which preset built `pockets` (`null` for custom pocket lists). `TableSpec.withPocketPreset` and `TableSpec.withSize` rebuild all six pockets via `TableSpec.defaultPockets` and keep `pocketPreset` synchronized; `withClothParams` and `withBallContact` return updated copies.
- `TableSpec.ballContact: BallContactParams` carries per-table `frictionMu` (`0.02..0.12`, default `SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU`) and `restitution` (`0.85..0.98`, default `MotionPhysics.BALL_BALL_RESTITUTION`), populated by `SavedTableProfile.toEffectiveTableSpec` and consumed directly by `TableCollisionSimulator`, `BallBallContact`, and `SpinPhysics`.

## Consequences

- **Positive:** Corner and side pockets behave asymmetrically and tighten realistically with arrival speed across simulation, make probability, and canvas drawing from one geometry definition; renaming or calibrating a table preserves its kind, ball size, and pocket cut.
- **Known limits:** `jawToleranceSlow` and `jawToleranceFast` are modelling values awaiting physical measurement; `PocketPreset.SNOOKER` uses straight facings rather than rounded snooker cushion falls; callers must use `withSize` / `withPocketPreset` rather than raw `TableSpec.copy` on dimensions or `pockets` so `pocketPreset` stays accurate.

## Code References

- `engine/src/commonMain/kotlin/com/runout/engine/model/TableSpec.kt` (`TableSpec`, `TableKind`, `PocketSpec`, `PocketKind`, `PocketPreset`, `PocketId`, `BallContactParams`)
- `engine/src/commonMain/kotlin/com/runout/engine/physics/PocketGeometry.kt` (`PocketGeometry`, `PocketGeometry.EntryWindow`, `PocketGeometry.Capture`)
- `engine/src/commonMain/kotlin/com/runout/engine/calibration/TableLevelAndProfiles.kt` (`SavedTableProfile.toEffectiveTableSpec`)
- `composeApp/src/commonMain/kotlin/com/runout/app/ui/TableRendering.kt` (`cushionSegments`, `facingBackPoint`)
- `composeApp/src/commonMain/kotlin/com/runout/app/storage/SetupPreferencesStore.kt` (`TableSizePreset.fromTableSpec`)
