# Runout UX/UI Design Guide

Binding visual, structural, and interaction guide for `composeApp/` (`commonMain`, `androidMain`, `iosMain`). Built for Compose Multiplatform `1.7.3` + Material Design 3 across Android (API 26–35) and iOS.

> **Guiding Principle:** Keep **100%** of the physics, computer vision, and multi-shot strategy power under the hood; surface only the **20%** a player needs for the exact decision in front of them.

---

## 1. Purpose, Research Foundation & Baseline Audit

### Research Principles Used
1. **Material Design 3 Navigation & Ergonomics:** Bottom `NavigationBar` for **3–5 primary thumb-zone destinations**; `ModalNavigationDrawer` (Side Menu) for infrequent settings and secondary tools; `TabRow` strictly for sibling filters *within* a screen; `ModalBottomSheet` for temporary contextual choices (30%–70% height); explicit `WindowInsets.safeDrawing` inside `Scaffold`; minimum **48×48 dp** touch targets with `>= 8 dp` separation.
2. **Nielsen Norman Group Progressive Disclosure (2-Level Cap):** Always protect the default view for 80%–90% of routine actions, expose transparent paths to **Pro / Advanced** controls, use **tap-to-reveal** tooltips (never hover on touchscreens), and keep disclosure to **two levels maximum** (`Simple` -> `Pro` / sheet / accordion) so players never get lost.
3. **Hick's Law & Gestalt Perception:** Cut visible decision choices at any moment so scan time stays low; apply **Gestalt Proximity (3:1 ratio)** (`24 dp` between distinct sections vs. `8 dp` from a section header to its own controls) and **Figure-Ground** (hero table vs. dimmed modal sheets) instead of cluttering screens with divider lines.
4. **Sports-Coach & Camera-Analysis Continuity:** Minimize time-to-first-analysis (< 30 s onboarding), tie skill calibration directly to visible value (terminology & overlay density adapt immediately), and surface a smart **"Continue Last Table"** card rather than dropping returning players into an unexplained state.

### Baseline Audit of Current Prototype Problems

| Current Issue | Observed Where | Player Impact | Design Guide Fix |
|---|---|---|---|
| System status bar overlaps top tabs | `MainActivity.kt` (`enableEdgeToEdge()`) + `App.kt` (raw `ScrollableTabRow` without insets) | Clock and battery collide with tab text on modern phones. | Wrap app shell in `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + `TopAppBar`. |
| 6 crowded top tabs mix core play with utilities | `App.kt` (`AppTab`: `Table & Planner`, `Photo Scan`, `Calibration`, `Tools & Coach`, `Player & Ball Set`, `Rules & FAQ`) | First-time user has no idea where to start or what the app does. | **4 bottom nav tabs** (`Home`, `Plan`, `Learn`, `Rules`) + **Side Menu Drawer** (`☰`) for tools, calibration & profile. |
| No obvious way to take or pick a real photo | `PlatformCameraController.android.kt` only returns `SampleCapturedPhotos`; `PhotoCaptureAndConfirmScreen.kt` opens directly on preset chips | User taps Photo Scan expecting a camera/gallery picker and only sees synthetic presets. | Dedicated 3-step **Photo Wizard** with large **Take Photo** + **Import from Gallery** actions first (`Try a Sample` moved to a bottom helper link). |
| Pool table buried below 6 stacked control rows | `App.kt` (`RunoutPracticeScreen` stacks Presets, Table Size, Game & Group, Horizon, Overlays, Speed Scale, Object Balls `+ / -` above `PoolTableCanvas`) | User sees 20+ filter chips before even seeing the pool table or #1 shot. | Put **Table Canvas + #1 Shot Card above the fold**; move Game/Table, Object Ball Tray, and Overlays into **Modal Bottom Sheets** + **`Simple \| Pro`** toggle. |
| Raw physics jargon & metrics exposed by default | `App.kt`, `PhotoCaptureAndConfirmScreen.kt` (`μr`, `ec`, `EV`, `HSV H=196.2°`, `squirt`, `sigma`) | Everyday or beginner pool players feel intimidated and close the app. | **Skill-aware Simple Mode** translates physics into plain English + **tap `ⓘ` tooltips**; full telemetry moves to **Pro Mode** & **Collapsed Accordions**. |
| Flat 10–12 dp spacing everywhere | `App.kt`, `ExtrasAndToolsScreen.kt`, `CalibrationScreen.kt` (`Arrangement.spacedBy(10.dp)` / `12.dp`) | Section headers sit equidistant from the card above and controls below; hard to scan. | Enforce **8 dp spatial grid**: `24 dp` between sections, `8 dp` header-to-content, `12 dp` between cards, `16 dp` card padding. |
| Multiple stacked scroll containers & inline diagnostics | `PhotoCaptureAndConfirmScreen.kt`, `ExtrasAndToolsScreen.kt`, `RulesAndProfileScreens.kt` | Endless scrolling makes locating rules, drills, or 3D tutorials exhausting. | Collapsed accordion cards for Rules & diagnostics; dedicated full-screen viewer for 2D/3D walkthroughs & drills. |

---

## 2. User Personas & Skill-Aware Adaptation

> **"If I'm new to pool, should the app know?"**
> **Yes.** Asking skill level once at onboarding lets Runout automatically match wording, shot lookahead, visual overlays, and recommended starting guides to the player — and players can switch tiers anytime in **Side Menu -> Player Profile**.

Mapped directly to `ExperienceLevel` (`BEGINNER`, `INTERMEDIATE`, `ADVANCED`) in `:engine`:

| Dimension | **New to Pool** (`BEGINNER`) | **Casual / League** (`INTERMEDIATE`) | **Advanced / Competitive** (`ADVANCED`) |
|---|---|---|---|
| **Default UI Complexity** | **Simple Mode** (locked-clean unless user manually switches to `Pro`) | **Simple Mode** (1-tap `Simple \| Pro` switch in Top Bar) | **Pro Mode** unlocked immediately |
| **Shot Label Language** | Plain English only: *"Aim here • Medium speed • Soft follow"* | Plain English + `Speed 5/10` + key `Make %` | Full readout: `Speed 5/10 (2.4 m/s)`, `Cut 28°`, `Tip (-0.2R, +0.3R)`, `Squirt/Throw` |
| **Table Overlays Shown** | Primary aim line + ghost ball + cue-ball path; 1 step ahead | Primary shot + next-ball position zone (`2–3` shots ahead) | Full `2–5` / `Deep Run-Out` chain, **Heat Zone**, **Landing Cloud (95% ring)**, problem-ball halos |
| **Cue Spin Controls** | Hidden behind *"Add Basic Spin (Optional)"* pill (`Stun`, `Follow`, `Draw`) | Quick 4-preset spin chips (`Auto`, `Stun`, `Follow`, `Draw`) | Full interactive **2D `CueTipSelectorCanvas`** + squirt/swerve/throw breakdown |
| **Home Quick-Start Card** | **"Start Here: 8-Ball Basics"** (2D/3D walkthrough) + **"Rules Lookup"** | **"Resume Last Table"** / **"Scan Photo"** + **"Weekly Practice Drill"** | **"Scan Photo"** + **"Saved Tables"** + **"Table Calibration & Drills"** |
| **Learning Helpers** | `ⓘ` tap glossary chips visible on terms (`Ball in Hand`, `90° Stun`, `Kitchen`) | Contextual `ⓘ` tooltips on physics terms (`Heat Zone`, `Breakout`) | Quiet metrics; tooltips available on `ⓘ` icons when tapped |

---

## 3. First-Run Onboarding Flow ("What Does This App Do?")

**Before the tour: "Before you start" acknowledgement.** A full-screen notice (not a dialog) comes first, once per version of the Terms of Use. It states that shot lines and plans are estimates used at the person's own risk, with **I agree** (primary) and **Not now** (secondary, 48 dp). Nothing is pre-ticked, and tapping outside cannot dismiss it. **Not now** keeps the person on the screen with a short note that Runout cannot be used without agreeing. The names *Terms of Use* and *Privacy Policy* are links that open the documents in the app, and **Back** returns to this screen. The accepted version is stored with the setup preferences; people who finished setup before this screen existed see it once, and it returns whenever the terms version changes.

**About & Legal.** A Side Menu entry under *Settings & Help* opens a pushed full-screen route (`← Back` returns to the previous screen) with the plain-language summary and one row per bundled document: Terms of Use, Privacy Policy, Third-party notices and the Apache-2.0 and BSD 3-Clause licence texts. Everything is bundled and readable offline; the texts are generated from `docs/legal/` at build time, so the files there are the only source.

Shown **once** on fresh install (persisted flag) and replayable anytime via **Side Menu -> Replay Welcome Tour**. Strictly **3 screens**, under **30 seconds**, with a `Skip` action always visible in the top-right.

```
[Step 1: What Runout Does]  -->  [Step 2: Your Experience]  -->  [Step 3: Default Game & Table]  -->  [Home]
   (3 visual cards)                (3 skill cards)                (Game + Table size chips)
   [Get Started] / Skip            [Continue] / Back              [Start Practicing]
```

### Step 1 — Welcome & Capabilities
- **Headline:** *"Your offline pool coach & shot planner."*
- **3 compact cards answering "What can I do here?":**
  1. **Snap or Build a Table** — Take a table photo, import from gallery, or drag balls manually.
  2. **See Your Best Shot** — Get clear aim lines, speed guidance, and 2–5 shot position plans.
  3. **Learn Rules & Drills** — Search offline rules and scrub interactive 2D/3D shot breakdowns.
- **Footer:** Filled CTA **`Get Started`**, Text CTA **`Skip & Explore`** (defaults to `Casual / League`, `8-Ball`, `9-ft Pool`).

### Step 2 — Skill Level Selection
- **Headline:** *"How familiar are you with pool?"*
- **Subtext (explains why we ask):** *"This adjusts how much physics detail and coaching appears on your table."*
- **3 tappable radio cards (48 dp+ height, clear 1-line summary):**
  - **`New to Pool`** — *"Clear aim lines, plain language, and step-by-step 8-Ball guides."*
  - **`Casual / League`** — *"Run-out order (2–3 shots ahead), speed scale, and quick spin tips."* *(Recommended default)*
  - **`Advanced / Competitive`** — *"Full 2D English, heat zones, landing clouds, and cloth/rail tuning."*

### Step 3 — Default Game & Table Size
- **Headline:** *"What do you usually play?"*
- **Group 1 — Favorite Game (single-select chips):** `8-Ball`, `UK 8-Ball (Blackball)`, `9-Ball`, `10-Ball`, `Snooker`
- **Group 2 — Typical Table Size (single-select chips):** `7-ft Bar Box`, `8-ft Home`, `9-ft Pro`, `12-ft Snooker`
- **Footer:** Filled CTA **`Enter Runout`** -> lands directly on **Home**.

---

## 4. Landing Screen (`Home`) & Session Continuity

> **"Should the user continue last saved or reset?"**
> **Auto-save the active rack silently, show a clear `"Continue Last Table"` card on `Home`, and never force a silent overwrite.** Launching directly into an old or random preset rack without context confuses both beginners and returning players.

### Continuity Rules
1. **Returning with an active/modified rack:** Top of `Home` displays the **Continue Last Table Card** showing game, 8-Ball group (or *"Group needed"*), ball count, and timestamp (e.g., *"8-Ball • Solids • 7 balls left • Today, 12:10"*).
   - **Primary CTA:** **`Continue`** -> jumps straight to `Plan` with exact ball positions, cloth settings, and shot selection intact.
   - **Secondary action:** **`Discard / Reset`** -> triggers a lightweight confirmation dialog (*"Clear your unsaved table layout?"* `[Keep]` / `[Reset]`).
2. **Fresh install or after explicit Reset:** No Resume card is shown; `Home` centers on **Scan or Import Table Photo** and **New Table**.
3. **Reset inside `Plan`:** Never place a raw `Reset` button right next to primary shot controls where an accidental thumb tap wipes dragged balls. Put **`Reset Rack`** inside the Top App Bar `⋮` overflow menu with a confirmation dialog if balls were moved, plus a brief snackbar **`Table reset` `[Undo]`**.

### `Home` Screen Card Order (Top to Bottom)
1. **Top App Bar:** `☰` Side Menu button | **`Runout`** title | Mode pill (`Simple` or `Pro`)
2. **Continue Last Table Card** *(conditional — only when a modified/saved session exists)*
3. **Primary Capture Card (Hero CTA):** **`Scan or Import Table Photo`** (*"Take a photo or choose one from your phone"* -> opens Photo Wizard Step 1)
4. **Build Manually Row (2 equal cards side-by-side):**
   - **`New Table`** (*Opens `Game & Table Setup` bottom sheet with presets + blank rack*)
   - **`Saved Tables`** (*Quick-pick a calibrated venue/home table profile*)
5. **Tailored For You Section** *(changes by skill level)*:
   - `BEGINNER`: **`8-Ball Basics Walkthrough`** + **`Search Pool Rules`**
   - `INTERMEDIATE` / `ADVANCED`: **`Resume Last Drill`** / **`Interactive Tutorials (2D & 3D)`** + **`Offline Tools (Break Meter & Clock)`**

---

## 5. Navigation Architecture: Bottom Bar, Side Menu & Dedicated Views

Replace the 6-tab top `ScrollableTabRow` with a **2-tier Material 3 navigation architecture**:

### Tier 1 — Primary Bottom `NavigationBar` (4 Destinations, Always Thumb-Reachable)

| Tab | Icon (Outlined / Filled) | Owns | Top Bar Right Actions |
|---|---|---|---|
| **Home** | Home | Session resume, Scan Photo hero CTA, New/Saved Table, skill-based quick starts | Profile / Skill badge pill |
| **Plan** | Sports / GridView | Interactive pool table hero, active shot / run-out card, quick action bar | **`Simple \| Pro`** toggle pill + **`⋮`** menu (`Scan Photo`, `Reset`, `Save Profile`) |
| **Learn** | School / PlayCircle | 3 internal sub-tabs (`Walkthroughs`, `Tutorials`, `Drills`) + Practice Log summary | Filter by Game (`All / 8-Ball / 9-Ball / Snooker`) |
| **Rules** | MenuBook / Search | Instant offline search bar, game filter chips, collapsible rule cards | Clear query button inside search field |

*Bottom Bar Rules:* Always visible on the 4 top-level tabs; **hidden** inside full-screen wizards and players (`Onboarding`, `Photo Scan Wizard`, `2D/3D Interactive Player`, `Calibration`, `Tools`) so horizontal sliders and edge drags never collide with tab switches.

### Tier 2 — Side Menu (`ModalNavigationDrawer`, `☰` on Top-Level Screens)

```
+------------------------------------------+
|  [Header - 16dp padding, SurfaceMid]     |
|  Alex • Casual / League                  |
|  Table: Standard 9-ft Pool               |
|  Mode:  [ Simple | Pro ]                 |
+------------------------------------------+
|  TABLE & CAPTURE                         |  <- 12dp labelSmall, muted
|   [Camera]  Scan or Import Photo         |
|   [Table]   Saved Table Profiles (3)     |
|   [Tune]    Table Calibration & Level    |
+------------------------------------------+  <- 1dp subtle HorizontalDivider
|  MATCH & PRACTICE TOOLS                  |
|   [Snooker] Snooker Break & Safety Coach |
|   [Speed]   Break-Speed Meter            |
|   [Timer]   Scorekeeper & Shot Clock     |
|   [History] Practice Drill Log           |
+------------------------------------------+  <- 1dp subtle HorizontalDivider
|  SETTINGS & HELP                         |
|   [Person]  Player Profile & Ball Set    |
|   [Tour]    Replay Welcome Tour          |
|   [Shield]  Offline & Photo Privacy Info |
+------------------------------------------+
```

### Top App Bar Rules (`Scaffold` + `TopAppBar`)
- Always pass `contentWindowInsets = WindowInsets.safeDrawing` to `Scaffold` so content **never collides with the phone status bar, camera cutout, or gesture pill**.
- Top-level screens show **`☰`** on the left; dedicated sub-screens show **`← Back`** on the left and omit the Bottom Bar.
- Maximum **2 actions** on the right of `TopAppBar`.

---

## 6. Photo Capture & Import Flow ("How Do I Take or Add a Photo?")

Photo entry must be **unmistakable** and reachable in **1 tap** from `Home` and **1 tap** from `Plan` (`Camera` button / `⋮` -> `Scan Photo`), opening a focused **3-Step Dedicated Screen**:

```
Step 1: Choose Photo Source        Step 2: Frame & Fix Balls         Step 3: Confirm Game & Group
+-----------------------------+    +----------------------------+    +----------------------------+
| <-  Scan or Import Photo    |    | <-  Step 2 of 3: Check     |    | <-  Step 3 of 3: Confirm   |
|     Step 1 of 3             |    |     [Quality: Good (Green)]|    |                            |
|                             |    +----------------------------+    | Suggested: 8-Ball (94%)    |
|  [ Camera Icon ]            |    |  [Photo w/ 4 Corner Handles|    | (•) 8-Ball   ( ) 9-Ball    |
|  ( Take Table Photo )       |    |   + Ball #Badges Overlay]  |    |                            |
|  Uses camera in memory;     |    +----------------------------+    | Choose Your Group (Req.):  |
|  no location saved.         |    | Tap any ball to fix number |    | [Solids] [Stripes] [Open]  |
|                             |    | v Table Speed & Rails      |    |                            |
|  ( Import From Gallery )    |    |   (Collapsed: Standard)    |    | [! Planning Paused Card    |
|  Pick PNG/JPG from phone.   |    | v Detection Diagnostics    |    |  if #8/#9/#10/#0 missing]  |
|                             |    |   (Collapsed - Pro)        |    |                            |
|  -- Quick Framing Tips --   |    |                            |    | CTA:                       |
|  • Show all 4 rail corners  |    |                            |    | [ Plan My Run-Out ]        |
|  • Avoid harsh overhead glare    | [ Back ]   [ Next: Confirm ] |    | or [ Edit Table (Paused) ] |
|                             |    +----------------------------+    +----------------------------+
|  Try a built-in sample v    |
+-----------------------------+
```

### Android & iOS Platform Rules for Photo Entry
1. **Take Photo:**
   - *Android:* Use `ActivityResultContracts.TakePicture` writing to a `rememberSaveable` file path inside `FileProvider` `cacheDir/camera_temp/`, decoded cleanly off the UI thread via Android P+ single-pass `ImageDecoder.decodeBitmap` (`ALLOCATOR_SOFTWARE` + target-size downscale) or single-read `ByteArray` `BitmapFactory` + `ExifInterface` fallback into `ArgbImageFrame` with temp files deleted and EXIF/GPS tags stripped before returning to `commonMain`. Request `CAMERA` permission *only* if needed when the user taps **Take Photo**.
   - *iOS:* `UIImagePickerController` / `AVFoundation` with `CGImage` extraction and metadata removal.
2. **Import from Gallery:**
   - *Android:* Use `ActivityResultContracts.PickVisualMedia(ImageOnly)` (Android Photo Picker — works on Android 13+ with **zero `READ_MEDIA_IMAGES` storage permissions** and falls back cleanly on API 26–32), decoded single-pass (`ImageDecoder` / single `ByteArray` read — never reopening one-shot provider streams) off the UI thread on `Dispatchers.Default`.
   - *iOS:* `PHPickerViewController` (`filter = .images`).
3. **Sample Photos Tucked Out of the Way:** Built-in `SampleCapturedPhotos` stay accessible via the subtle **`Try a built-in sample`** link at the bottom of Step 1 so offline testers without a table nearby can still demo the pipeline without cluttering real users.
4. **Hard Readiness Guardrail on Step 3 (`PlanningReadinessValidator`):**
   - Never silently assume **Solids** or **Stripes** (or **Yellows**/**Reds** in UK Blackball) when 8-Ball is detected; require an explicit tap.
   - If the required winning ball (`#8` for 8-Ball, `#9` for 9-Ball, `#10` for 10-Ball) or cue ball (`#0`) wasn't detected in the photo, display the amber **"Planning Paused"** warning card and change the primary button to **`Open in Table Editor (Paused)`** so the user can drop the missing ball onto the felt rather than getting bogus shots.

---

## 7. Progressive Disclosure System: Simple vs. Pro & Component Matrix

### Simple vs. Pro Mode Feature Allocation

| Feature / Control | **Simple Mode** *(Beginner & Casual Default)* | **Pro Mode** *(Toggle / Advanced Default)* | Container Pattern |
|---|---|---|---|
| **Pool Table Canvas & Aim Lines** | **Always Hero #1** (above the fold) | **Always Hero #1** + Landing Cloud + Heat Zone overlays | Main Canvas |
| **Game, Group & Table Size** | Single summary pill row (`8-Ball • Solids • 9-ft` — tap to change) | Same compact pill row (saves vertical space!) | **`ModalBottomSheet`** (`Game & Table Setup`) |
| **Add / Remove / Reset Balls (`0..15`)** | **`Edit Balls`** button below table (shows count badge) | **`Edit Balls`** button below table | **`ModalBottomSheet`** (`Edit Balls Tray`) |
| **#1 Shot Advice Card** | Plain English: Target ball -> Pocket, `Speed` word + `1..10`, paired **`Make` % (`Easy/Med/Hard`)** + **`Position` % (`Easy/Med/Hard` or `Last ball`)** | Above + Cut `°`, `±cm` spread, Run-Out %, paired **`Make` + `Position`** badges, problem-ball badge | Hero Result Card (paired difficulty row + tooltip) |
| **Why This Shot? / Next Shape Explanation** | **Collapsed accordion** (`Show why this shot ->`) | Expanded by default inside Plan card | Inline Accordion |
| **Alternative Run-Out Plans (#2, #3...)** | **Collapsed accordion** (`See 2 other routes`) | Visible cards (`Plan 1`, `Plan 2`, `Plan 3`) | Inline Accordion |
| **Spin / Cue-Tip Control** | Hidden behind **`Add Spin (Optional)`** pill -> 4 basic presets (`Auto`, `Stun`, `Follow`, `Draw`) | Full **2D `CueTipSelectorCanvas`** + Squirt/Swerve/Throw metrics | **`ModalBottomSheet`** (Simple) / Expandable Card (Pro) |
| **Planning Horizon & Speed Override** | Auto (`1–2` shots Beginner, `3` shots Casual) | `1-Shot`..`Deep Run-Out` + `Speed 1..10` inside `Shot Options` sheet | **`ModalBottomSheet`** (`Shot Options`) |
| **Cloth Roll (`μr`) & Rail Bounce (`ec`)** | Hidden unless user opens `Table Surface` from `⋮` or `Pro` | **Collapsed accordion** (`Surface: Standard -> Customize μr / ec`) | Inline Accordion (`Pro`) / Sheet |
| **Problem Balls & Breakouts Card** | Plain 1-line warning pill (*"Ball 7 is blocked — plan breakout"*) | Full `ProblemBallsCard` with insurance ball & breakout trigger | Card (`Pro` only) |
| **Ranked One-Shot Geometric Reference** | **Hidden** (redundant with Run-Out Plan for beginners) | **Collapsed accordion** (`All Legal Pots (6)`) | Inline Accordion (`Pro` only) |
| **Raw Vision Telemetry (HSV, Sharpness, Pixel Matrix)** | **Hidden** | **Collapsed accordion** (`Detection Diagnostics`) on Photo Step 2 | Inline Accordion (`Pro` only) |

### Cognitive-Load Budgets ("What Is Too Much Information?")
1. **Above-the-Fold Hero Rule:** On a `360 × 800 dp` phone screen, opening `Plan` MUST show the **Table Canvas** and the **#1 Recommended Shot Card** completely without scrolling.
2. **One Chip Row Cap:** Never display more than **1 horizontal chip strip** directly visible above the table canvas (`Game/Size summary + Mode + Edit Balls + Shot Options` fits in 1 compact action strip).
3. **Three-Metric Card Cap:** In **Simple Mode**, no card may display more than **3 primary data points** (`Ball -> Pocket`, `Speed`, `Confidence %`). Extra decimals go into a collapsed `Details` section.
4. **Two-Badge Cap:** A card header may contain at most **2 status pills/badges** (e.g., `8-Ball` + `Paused`).
5. **No Raw Greek Physics Without Translation:** Never render bare `μr`, `ec`, `φ`, `EV`, `mm`, or `m/s` in **Simple Mode** without a plain-English label and an optional tap `ⓘ` tooltip.

---

### Strict Component Decision Matrix (Tooltip vs. Modal vs. Accordion vs. Dedicated Screen)

| Need | Use This Component | Compose Implementation Rule | Exact Runout Examples | Anti-Pattern (Never Do) |
|---|---|---|---|---|
| **Short definition (< 25 words) of an icon or pool/physics term** | **Tap Tooltip (`ⓘ`)** | `TooltipBox` + `RichTooltip` triggered by explicit tap (`48×48dp` target), dismissed on outside tap. **Never hover.** No action buttons inside. | `90° Stun`, `30° Follow`, `Heat Zone`, `Landing Cloud`, `Cloth Roll (μr)`, `Rail Bounce (ec)`, `Squirt & Throw`, `Insurance Ball`, `Ball in Hand`. | Multi-paragraph explanations inside a tooltip, or requiring tooltips to understand basic buttons. |
| **Explain what the cue ball does at one point of the planned shot (spin changes, cushion and ball contacts, rest)** | **Path Inspector Callout** (`PathWaypointCallout` inside `PoolTableCanvas`) | A card **anchored to a point on the table**: one title + up to **3 short lines** + **`‹ Back` / `Next ›` / `Close`** buttons, each with a `48 × 48 dp` touch target (the visible pill may be smaller). Opened by tapping a waypoint marker (`>= 24 dp` tap radius) or the `ⓘ Path physics` chip; dismissed by `Close` or by tapping empty cloth. Selection resets when the shot changes. **Simple:** plain English, no `m/s` / `cm` / `°`. **Pro:** adds `m/s`, `%`, `cm`, `°`. | `Backspin gone`, `Roll begins`, `Contact #7`, `Cushion 1` (speed and english kept), `Cue ball rests`. | Using it for glossary definitions (use the `ⓘ` tap tooltip, which has no buttons), showing more than 3 lines, or opening it while a ball is being dragged. |
| **Quick contextual setup, multi-option picker, or ball tray while keeping table visible** | **Modal Bottom Sheet** | `ModalBottomSheet` (30%–70% height, top drag handle, header divider, `.navigationBarsPadding()`, live-applies changes). | **1.** `Game & Table Setup` (game, 8-ball group, table size, presets)<br>**2.** `Edit Balls Tray` (`0..15` add/remove + clear cue ball)<br>**3.** `Shot & Overlay Options` (horizon, speed override, heat zone, cloud)<br>**4.** `Quick Cue Spin` (Simple mode presets). | Deep multi-step wizards, long scrollable forms, or calibration inside a bottom sheet. |
| **High-stakes / destructive confirmation or OS permission rationale** | **Center Alert Dialog** | `AlertDialog` with explicit neutral/destructive button labels (never vague `"OK"`/`"Yes"`). | `Reset Rack?`, `Delete Saved Table Profile?`, `Camera Permission Needed to Scan Table`. | Info popups, upsells, or frequent workflow tips. |
| **Optional secondary data, alternative plans, pro tuning, or scannable FAQ items** | **Collapsed Accordion** | Card header (`48dp+` min height) showing **title + current summary value + chevron**, `AnimatedVisibility` expand/collapse. **Collapsed by default.** | `Why This Shot?` (Simple), `Alternative Run-Out Plans`, `Surface Tuning (μr/ec)`, `All Ranked Shots (Pro)`, `Photo Diagnostics (Pro)`, every `RuleEntryCard` on `Rules`, `Walkthrough` stage details. | Nesting accordions inside accordions, or expanding all 20 rule cards at once on load. |
| **Focused multi-step workflow, full-viewport 2D/3D interaction, or sensor tool** | **Dedicated Full-Screen View** | Pushed route (`← Back` TopAppBar, Bottom Nav hidden, `WindowInsets.safeDrawing`). | `First-Run Onboarding`, `Photo Scan 3-Step Wizard`, `Interactive 2D/3D Walkthrough & Tutorial Player`, `Table Calibration & Level`, `Snooker Coach`, `Break-Speed Meter`, `Scorekeeper & Shot Clock`, `Player Profile & Ball Set`. | Embedding a full 3D orbit canvas inside a scrollable tab full of other unrelated tools. |

---

## 8. Visual Design Tokens: Spacing, Typography, Headers, Dividers & Contrast

### 8 dp Spatial Grid & Layout Spacing Tokens

| Token | Value | Where Applied | Rule & Why |
|---|---|---|---|
| `SpaceMicro` | `4 dp` | Icon-to-text inline gap; tightly paired title + 1-line subtitle | Keeps tightly coupled labels fused visually. |
| `SpaceElement` | `8 dp` | **Section Header -> its Content**; gap between adjacent chips/buttons | **3:1 Gestalt Proximity Rule** + minimum `8 dp` touch-target gap to stop mis-taps. |
| `SpaceCard` | `12 dp` | Gap between sibling cards inside the same section; list items | Distinguishes individual items belonging to one group. |
| `SpaceMargin` | `16 dp` | **Screen horizontal edges**; **standard Card internal padding** | Comfortable thumb margin on 360–430 dp phones (`20 dp` on tablets >= 600 dp). |
| `SpaceSection` | `24 dp` | **Gap between distinct vertical sections** (e.g., Hero Card -> Surface Card) | **3× larger than `SpaceElement (8 dp)`** so users immediately see which header owns which block without needing divider lines. |
| `SpaceHero` | `32 dp` | Onboarding vertical anchors & empty-state breathing room | Prevents cramped marketing/welcome screens. |

**Mandatory Edge-to-Edge Window Insets (Compose Multiplatform):**
- App root: `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` and pass `innerPadding` into the screen scroll `contentPadding` + `.consumeWindowInsets(innerPadding)`.
- `ModalDrawerSheet`: pad inner column with `.safeDrawingPadding()` so the background extends edge-to-edge while text clears the status bar and rounded corners.
- `ModalBottomSheet`: pad inner column with `.navigationBarsPadding()` + `16 dp` bottom margin so action buttons clear the gesture bar.
- `AndroidManifest.xml`: include `android:windowSoftInputMode="adjustResize"` on `MainActivity` so keyboard search in `Rules` never obscures results.

### 48×48 dp Touch Target & Ergonomics Standard
- Every interactive chip, `ⓘ` tooltip icon, `←` / `☰` button, ball toggle pill, and `- / +` stepper **must** satisfy `Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)` (or outer clickable wrapper `>= 48.dp` around a `24.dp`–`32.dp` visual pill).
- Separate adjacent clickable controls by **at least `8 dp`** (`SpaceElement`).
- Primary CTAs (`Continue`, `Take Photo`, `Plan My Run-Out`) sit in the **lower thumb zone** whenever possible and use full-width `52 dp` height `Button`.

### Typography & Clear 4-Tier Header Hierarchy

| Tier | Material 3 Role | Size / Weight | Color | Writing Rule (Plain-English Clarity) |
|---|---|---|---|---|
| **Tier 1 — Screen Title** | `titleLarge` | `22 sp` / SemiBold | `#F1F5F9` (`onBackground`) | Short noun/action (1–3 words max): **`Plan`**, **`Photo Scan`**, **`Rules & FAQ`**. Put the title in `TopAppBar`; don't repeat giant banners inside scroll content. |
| **Tier 2 — Section Header** | `titleMedium` | `16 sp` / SemiBold | `#F1F5F9` (`onBackground`) | Clear task/group name: **`Recommended Shot`**, **`Your Table Setup`**, **`Other Run-Out Routes (2)`**. Maximum 1 optional right-aligned count or action link (`See all`). |
| **Tier 3 — Card Header** | `titleSmall` | `14 sp` / SemiBold | `#F1F5F9` (`onSurface`) | Concise title (`1` line, `maxLines = 1, Ellipsis`). |
| **Tier 4 — Body & Micro Copy** | `bodyMedium` / `bodySmall` / `labelSmall` | `14 sp` (20 sp LH) / `12 sp` / `11 sp` | `#E2E8F0` / `#94A3B8` (`onSurfaceVariant`) | Use active voice (`"Tap a corner handle to adjust"`), max ~55 chars per line where possible. |

### Divider Discipline ("Should a Divider Be Here?")

> **Default answer: NO.** Use **`24 dp` of empty space** (`SpaceSection`) or subtle card container tonal shifts to separate sections. Lines everywhere make dark mobile UIs look noisy and dated.

| Use a `HorizontalDivider` (`1 dp`, `#1E293B` / `outlineVariant.copy(0.45f)`) | **Never** Use a Divider |
|---|---|
| **1. Side Menu (`ModalNavigationDrawer`)** — between Navigation Groups (`Table & Capture` \| `Match & Practice` \| `Settings`). | **Never** between bordered `Card`s (`BorderStroke(1.dp, ...)` already creates enclosure). |
| **2. Bottom Sheet Header** — directly under the sheet title before scrollable choices. | **Never** right beneath a `SectionHeader` text label (underlines break Gestalt proximity). |
| **3. Inside One Single Large Card** — when a card holds two logically distinct sub-rows (e.g. `#1 Shot Header` \| `Position Explanation`), inset `16 dp` horizontally. | **Never** around every single item in a chip row or compact badge group. |
| **4. Unboxed Flat Settings List** — between unbordered list rows inside `Settings` / `Player Profile` (`16 dp` start inset). | **Never** when whitespace (`24 dp`) already visually separates the groups cleanly. |

### Pool-Hall Dark-First Color Palette & Contrast Rules

| Token | Hex | Role & WCAG AA Notes |
|---|---|---|
| `Background` | `#0B1120` | Deep navy canvas; minimizes glare in dimly lit pool halls. |
| `SurfaceLow` | `#111827` | Primary cards, Bottom Bar container, Drawer background. |
| `SurfaceMid` | `#172033` | Elevated panels, Drawer header, Expanded accordion interiors. |
| `SurfaceHigh` | `#1E293B` | Chip backgrounds, Borders (`1.dp`), Dividers (`0.45f` alpha). |
| `TextPrimary` | `#F1F5F9` | Primary headings & shot callouts (`> 15:1` contrast vs `#0B1120`). |
| `TextSecondary` | `#94A3B8` | Subtitles, metadata (`6.4:1` contrast vs `#111827` — passes AA). Never go darker than `#94A3B8` for readable text. |
| `PrimaryCyan` | `#38BDF8` | Primary buttons (`onPrimary = #082F49`), active nav pill, focus highlights. |
| `SuccessGreen` | `#4ADE80` | Ready state, high make probability (`>= 65%`), passing quality badge (`> 9:1` vs `#111827`). |
| `WarningAmber` | `#FBBF24` | **Planning Paused** alerts, medium difficulty (`35%–64%`), lighting glare warnings. |
| `DangerCoral` | `#F87171` | Scratch risk, invalid corner quad, destructive `Reset` confirmation. |

*Accessibility Rule:* Never convey status by color alone — pair every green/amber/red state with an icon or explicit word (`Good`, `Paused`, `Scratch Risk`).

### Table Rendering (Top-Down Table View)

- **Playing surface = nose rectangle.** The cloth is the rectangle `0..length x 0..width` measured at the cushion noses (`docs/README.md` § Conventions). Balls, lines and heat zones live there; nothing dark is drawn on it.
- **Cushions end at the jaw tips.** Each cushion is a straight strip behind its nose line, six in all (two per long rail, one per short rail). A strip stops at the pocket's jaw tip and its end follows the pocket facing, leaning back toward the hole. Cushions use a slightly darker shade of the cloth colour.
- **Holes sit in the rail band.** Each hole is drawn behind the nose line at the pocket's hole centre and radius, with the dark throat between the facings leading to it. The rail band is 7.5 % of the short canvas side and is widened (the table shrinks to match) on any table where a hole would not fit; the `2:1` canvas aspect is unchanged. Rail diamonds sit on the wood beside the cushions, clear of the holes.
- **Mouth highlight.** The target pocket and selectable pockets are marked by a stroke across the mouth (jaw tip to jaw tip) and a ring on the hole rim, never by a ring on the cloth. A pocket is selected by tapping between its mouth and its hole (24 dp radius, a 48 dp target). Pot lines end at the aim point on the mouth and continue faintly to the hole.
- **Photo confirm overlay.** The six pocket mouths are drawn over the box as a bright tick at each jaw tip and a thin line across the mouth, so the box can be checked against the ends of the cushions in the photo.

---

## 9. Screen-by-Screen Wireframes

### A. `Home` (Landing Hub)
```
+---------------------------------------+
|  [=]  Runout             [Casual v]   |  <- TopAppBar (safeDrawing)
+---------------------------------------+
|                                       |
|  +- Continue Last Table ------------+ |
|  | 8-Ball • Solids • 6 balls left   | |  <- Only shown when session exists
|  | Last edited 12 min ago           | |
|  | [     Continue     ]   [ Reset ] | |
|  +----------------------------------+ |
|               (24 dp)                 |
|  +- Scan or Import Table Photo -----+ |
|  | [Camera]  Snap Table or Gallery  | |  <- Hero Primary Action (always 1 tap)
|  | Detect balls, choose game, plan  | |
|  +----------------------------------+ |
|               (12 dp)                 |
|  +- New Table -----+ +- Saved ------+ |
|  | Pick preset or  | | Home 8-ft    | |  <- 2 equal quick cards
|  | blank rack      | | Hall 9-ft    | |
|  +-----------------+ +--------------+ |
|               (24 dp)                 |
|  For You (New to Pool / Casual)       |  <- Tier 2 Header (8 dp to card below)
|  +- 8-Ball Basics Walkthrough ------+ |
|  | Interactive 2D/3D shot examples  | |
|  +----------------------------------+ |
|  +- Search Rules Offline -----------+ |
|  | "Scratch", "Ball in Hand", UK 8  | |
|  +----------------------------------+ |
+---------------------------------------+
|  [Home]    [Plan]   [Learn]  [Rules]  |  <- 4-item Bottom NavigationBar
+---------------------------------------+
```

### B. `Plan` — **Simple Mode** vs. **Pro Mode**
```
SIMPLE MODE (Above the fold = Table + #1 Shot!)   PRO MODE (Same Hero Table + Rich Strategy)
+---------------------------------------+         +---------------------------------------+
| [=] Plan     [Simple|Pro]  [Cam] [:]  |         | [=] Plan     [Simple|Pro]  [Cam] [:]  |
+---------------------------------------+         +---------------------------------------+
| [8-Ball • Solids v]  [Edit Balls (7)] |         | [8-Ball • Solids v] [Edit (7)] [Opts] |
|   (Single compact action strip)       |         |   (Single strip: Game/Balls/Overlays) |
|                                       |         +---------------------------------------+
| +-----------------------------------+ |         | +-----------------------------------+ |
| |        HERO POOL TABLE            | |         | |        HERO POOL TABLE            | |
| |   (Cue -> #3 -> Pocket + Next)    | |         | |  (Heat Zone + Cloud + Aim Chain)  | |
| +-----------------------------------+ |         | +-----------------------------------+ |
|   Drag any ball to adjust             |         |   Drag any ball • Heat Zone: ON       |
|               (12 dp)                 |         |               (12 dp)                 |
| [! Planning Paused card only if       |         | [! Planning Paused card if needed]    |
|    #8/#9/#10/#0 missing or unset]     |         |                                       |
| +-----------------------------------+ |         | +-----------------------------------+ |
| | RECOMMENDED SHOT                  | |         | | PLAN #1 (3-Ball • 74% Runout)     | |
| | #3 into Top-Right Corner          | |         | | 1. #3 -> Foot Left  | Speed 5/10  | |
| | Speed: Medium (5/10)  |  Make: 82%| |         | |    Cut 24° • 2.1 m/s • Top-Right    | |
| | [Optional: Add Basic Spin v]      | |         | | 2. #5 -> Side Left  • 3. #8 -> BR   | |
| +-----------------------------------+ |         | +-----------------------------------+ |
|   (8 dp -> collapsed accordions)      |         |               (24 dp)                 |
| > Why this shot? (Simple breakdown)   |         | +- Problem Balls (1 Blocked) -------+ |
| > See 2 alternative shot plans        |         | | #6 blocked — Break via #3 (91% safe)| |
|                                       |         | +-----------------------------------+ |
|                                       |         | > Cue Tip & English Control (0.0, +0.2|
|                                       |         | > Table Surface (Standard • μr 0.012) |
|                                       |         | > Ranked Legal Pots (6 shots)         |
+---------------------------------------+         +---------------------------------------+
|  [Home]    [Plan]   [Learn]  [Rules]  |         |  [Home]    [Plan]   [Learn]  [Rules]  |
+---------------------------------------+         +---------------------------------------+
```

- **Debounced Ball Dragging (`PoolTableCanvas`):** Moving balls suppresses trajectory overlays while dragging (`60 fps`) and plans again `150 ms` after finger release (an edit to the focused shot waits `60 ms`) on `Dispatchers.Default`. Dragging a ball while a later shot is shown makes that table the new start of the plan: accepted shots are dropped and the plan restarts from the moved table.
- **Interactive Cue-Ball Sandbox (`CueBallSandboxPanel` + `onTapCueBall`):** Tapping the white cue ball (`#0`) or the Spin control opens a non-blocking sandbox panel (directly below the table in portrait, side-by-side in landscape) that edits the **focused shot**, whatever its number: live 2D spin placement (`CueTipSelectorCanvas` showing the green default marker when overridden), 1-tap spin chips (including `Stop / Roll / Draw at ball`, the tips that give that spin state at the object ball for this shot's distance and speed; a state no tip can give there is shown greyed out), a `1..10` speed `Slider`, high-scratch preview, and 1-tap **`Reset to Plan Defaults`**. A centre-ball hit is rolling after about half a metre, so a stop shot needs a tip below centre; the chips and labels never imply that centre ball is a stun.
- **Cue-Ball Hit Guide & 3-Level Tightness Heat Zones (`CueBallHitGuide` + `drawLandingCloudOverlay`):** Shot recommendation cards and the playback bar show a live front-view cue-ball contact diagram (`TipOffset`) with Green / Amber / Red tolerance zones clipped cleanly inside the cue ball; table heat zones and 3-tier landing zones center strictly on `nominalLandingPosition` (synced with evolved table physics and trajectory endpoints while displaying optional skewed ensemble mean markers), clip inside playable felt bounds (`playLeft..playLeft+playWidth, playTop..playTop+playHeight`), and carve out spreading angular shadow cones (`ClipOp.Difference`) behind any blocking non-playable or friendly balls.
- **Make Difficulty + Position Difficulty Pairing (`ShotDifficultyPairRow` + `StepPlaybackControlBar`):** Every planned shot displays **Make** probability/tier (`Easy` >= 65%, `Medium` 35%–64%, `Hard` < 35% for potting the current ball) cleanly paired beside **Position** probability/tier (`positionSuccessProbability` from landing-cloud heat coverage, occlusion checks, spin/rail/travel control, and spread, or `Last ball` on terminal shots), with a tap `ⓘ` glossary tooltip clarifying both metrics.
- **Rolling Plan and Step Bar (`StepPlaybackControlBar`, `RollingRunoutPlanner`):** The plan rolls forward one ball at a time. With a 3-shot horizon on a 1–9 rack the first window is balls 1–3; **Next** accepts the focused shot and plans balls 2–4, then 3–5, and so on. The bar reads `[< Prev] Shot k · balls a–b [Next >]` with `[Step | All]` (All draws the whole window from the focused shot) and shows `Make X% Tier • Pos Y% Tier` inline; no extra numbers are added in Simple mode. **Prev** reopens the previous shot. Both buttons stay disabled while the planner is working (no double accept), and Next also while the focused shot cannot be accepted (a miss, a scratch, a cue ball that stops short): the reason is printed under the bar. Under the controls a strip lists the accepted shots with a lock mark, the focused shot and the shots planned after it. A shot whose ball or pocket changed on the last re-plan carries a strong amber **updated** mark; one that kept its ball and pocket but got a different stroke carries a subtle **refined** mark; a shot the player changed reads **edited**, and the focused one offers **`Reset step`** (a 48 dp target) to go back to the planner's choice. After the last ball the bar reads **Rack complete** and Next is disabled. Pro mode labels the combined success `Next N shots`. Moving a ball, changing the horizon, game, table, photo or preset plans again from the new start.
- **Interactive Pocket Switching (`PocketSelectorStrip` + canvas tap):** Tap any highlighted pocket directly on the table or use the `Auto Pocket` / pocket strip to test alternate routes for the focused shot; the rest of the plan re-fits to the pocket chosen. Shot options (speed) and the alternative routes list also act on the focused shot.
- **No Direct Pot Card (`FallbackAdviceCard`, `FallbackShotAdvisor`):** When the legal ball has no direct pot and the rack is not complete, one card takes the place of the recommended shot (the Planning Paused card is not used: "no pot" is not a setup problem). Title names the kind and the balls (`Combination: #2 into #3 -> Foot Right`, `Kick: one cushion to #1`, `Safety: hit #1, leave no pot`), then one plain-English sentence and three data points: Speed (word and `/10`), Spin, and the chance of the stated outcome (`Make`, `Legal hit` or `No pot left`). Pro mode adds one line of numbers (the chance to one decimal, the tip offsets, the kick's cushion point or the safety's cue-ball rest). The table draws the cue ball's path with a ghost ball at contact, each object ball's leg in its own colour, and a ring at a kick's cushion point; the step bar and the fullscreen table show the title and the chance. Existing card tokens only (14 dp corners, `RunoutSpacing.Margin`, primary outline).
- **Cue-Ball Path Inspector (`PathWaypointCallout` + markers in `PoolTableCanvas`):** The planned cue-ball line carries small tappable markers at every point where something changes: the draw to stun to forward-roll transitions before contact, the object-ball contact (arrival spin, speed handed to the object ball, spin the cue ball keeps), the slide ending after contact, each cushion (speed and english before and after), other-ball contacts, a scratch, and the final rest. Tapping a marker (or the `ⓘ Path physics` chip) opens one anchored callout with `‹ Back` / `Next ›` to walk the whole shot in path order and `Close`; tapping empty cloth also dismisses it. Tap priority is cue ball, then waypoint, then pocket. It follows the focused step in Step mode, step 1 in All mode, the sandbox tip and speed, and works in `FullscreenTableScreen`. Wording follows the display mode: **Simple** uses plain English without units (*"Backspin has worn off here. From this point the ball only slides."*); **Pro** adds `m/s`, `%`, `cm` and `°`. After a cushion the callout states the model's assumption (natural roll), because the simulation does not track draw or follow past the first contact.
- **Fullscreen Landscape Table (`FullscreenTableScreen`):** Entered via the TopAppBar/strip `Fullscreen` action, the small fullscreen button next to the table's `−  1.0x  +` zoom pill (same size as `−` / `+`, labelled "Enter fullscreen" / "Exit fullscreen" for accessibility, and drawn only where a screen supplies the action; the pill stays see-through) or wide-landscape rotation (`maxWidth > maxHeight && maxWidth >= 560.dp`); maximizes the `2:1` table alongside the top pocket HUD, floating `[< Prev] / [Next >]` playback bar with the same rolling-plan behaviour as the Plan screen, and `92 dp` vertical right action rail (`Exit`, `Mode`, `Balls`, `Spin`, `Game`, `Options`, `Scan`). Rotating mid-plan keeps the plan state.

### C. `Learn` & `Rules`
- **`Learn`:** Top secondary `TabRow` with **`Walkthroughs`**, **`Tutorials`**, **`Drills`**. Tapping any card pushes a **Dedicated Full-Screen Player** (hidden bottom nav) featuring clean `Phase 1 (Text + Diagrams)` / `Phase 2 (3D Orbit + Frame Scrubber)` toggle and **`Try on Table Planner`** button.
- **`Rules`:** `OutlinedTextField` search bar + single horizontal Game filter chip strip (`All`, `8-Ball`, `Blackball`, `9-Ball`, `10-Ball`, `Snooker`). Each rule renders as a **collapsed accordion card** (Title + Game pill + 1-line summary + chevron) that expands in-place to show Full Rule, Concrete On-Table Example, and Official Citation (`WPA`/`WPBSA`).

---

## 10. Phased Implementation Roadmap & UX Acceptance Checklist

### Recommended Execution Order
1. **Phase A — Core Shell, Edge-to-Edge & Real Photo Entry:**
   - Wrap app in `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + `TopAppBar` + 4-item Bottom `NavigationBar` + Side Menu `ModalNavigationDrawer`.
   - Build `Home` landing screen with auto-saved `Continue Last Table` vs. `Reset` confirmation dialog.
   - Implement real Android Camera (`TakePicture`) + Photo Picker (`PickVisualMedia`) in `PlatformCameraController.android.kt` (matching `commonMain` contract cleanly alongside iOS) and refactor `PhotoCaptureAndConfirmScreen.kt` into the 3-step Photo Wizard (`Try a Sample` moved to bottom link).
2. **Phase B — Planner Declutter & Progressive Disclosure:**
   - Split `Plan` into **`Simple`** and **`Pro`** modes wired to `PlayerProfile.experienceLevel`.
   - Keep Hero Table + #1 Shot visible above the fold; move `Game & Table Setup`, `Object Ball Tray (0..15)`, `Shot & Overlay Options`, and `Quick Cue Spin` into `ModalBottomSheet`s.
   - Add tap `ⓘ` glossary tooltips and convert secondary cards (`Alternative Plans`, `Surface μr/ec`, `Ranked Pots`, `Why This Shot?`) into collapsed accordions adhering to the 8 dp / 24 dp spatial grid and divider discipline.
3. **Phase C — First-Run Onboarding & Secondary Screen Polish:**
   - Build the 3-step First-Run Onboarding (welcome -> skill level -> default game/table).
   - Extract 2D/3D Walkthroughs & Tutorials into dedicated full-screen players launched from `Learn`.
   - Convert `Rules & FAQ` to default-collapsed accordion cards and route `Calibration`, `Snooker Coach`, `Break Meter`, `Scorekeeper/Clock`, and `Player Profile` cleanly from the Side Menu.

### Mandatory 12-Point UX Acceptance Checklist (For Every Future UI PR)
1. Content renders cleanly below status bar, camera cutouts, and above gesture bar (`WindowInsets.safeDrawing`).
2. `Plan` shows both the Pool Table canvas and #1 Shot Recommendation without scrolling on `360 × 800 dp` phones.
3. Every interactive control meets `>= 48 × 48 dp` touch target with `>= 8 dp` gap.
4. Spatial hierarchy follows `24 dp` section gap vs. `8 dp` header-to-content gap (`3:1` Gestalt ratio).
5. Zero unnecessary `HorizontalDivider` lines between bordered cards or directly under headers.
6. Every screen uses **at most 1 visible horizontal chip row** outside a Bottom Sheet.
7. Simple Mode cards show **<= 3 primary metrics** with plain-English wording (no unexplained `μr`, `ec`, `EV`, `φ`).
8. All `ⓘ` tooltips open on tap (`TooltipBox`), dismiss on outside tap, and stay under ~25 words.
9. Real `Take Photo` and `Import from Gallery` are reachable within **1 tap** from `Home` and `Plan`.
10. Photo Step 3 + `Plan` obey `PlanningReadinessValidator` (pause when `#8`/`#9`/`#10`/`#0` missing or 8-Ball group unset).
11. `Reset Rack` or discarding unsaved work requires confirmation (and/or Undo) and never fires accidentally.
12. All `commonMain` Compose primitives compile for both Android and iOS targets cleanly.
