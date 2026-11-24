package com.runout.app.planning

import com.runout.app.ui.SampleLayouts
import com.runout.app.ui.SampleScenario
import com.runout.engine.calibration.SavedTableProfile
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.ClothParams
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.TableKind
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.math.abs

/**
 * The table the person is working on: the balls, the rules, the table itself (size, pockets, cloth) and which
 * saved calibration profile, if any, the table was last set from. Every change to any of these goes through one of
 * the operations below, so the same rules hold wherever the change comes from. Nothing here is stored on the device;
 * the pocket size in particular lasts for the session.
 *
 * The rules, whichever operation is used:
 * - The table's [TableSpec.kind] and ball size never change except by choosing a table of another kind
 *   ([resizeTable]) or loading a layout that brings its own table ([loadLayout]); a calibration profile for the
 *   other kind is refused ([applyProfile]).
 * - The pocket size ([TableSpec.pocketPreset]) survives a calibration profile, a change of table size, a starting
 *   layout, a reset and a photo scan.
 * - The cloth and the ball-ball contact of the table in use survive a photo scan, which replaces the balls (and the
 *   table size, only when the scanned table is another one), unless the person set another cloth on the scan screen.
 * - A calibration profile stays active until the table stops being the one it describes: a size change, an edit of
 *   the cloth, or a layout that brings its own table.
 *
 * @property presetIndex Index of the sample layout the balls came from, or `-1` for balls placed another way.
 */
internal data class TableSession(
    val layout: TableLayout,
    val rules: GameRulesState,
    val activeProfileId: String? = null,
    val presetIndex: Int = 0,
) {
    /** The table in use. */
    val table: TableSpec
        get() = layout.table

    /** Same table and balls with other [rules] (a game or group change). */
    fun withRules(rules: GameRulesState): TableSession = copy(rules = rules)

    /** The balls changed by [edit] (add, remove or toggle a ball); the table is kept as it is. */
    fun editBalls(edit: (TableLayout) -> TableLayout): TableSession = copy(layout = edit(layout).copy(table = table))

    /** The table's cloth set to [cloth]. The table no longer matches a calibration profile, so none stays active. */
    fun withCloth(cloth: ClothParams): TableSession =
        copy(layout = layout.copy(table = table.withClothParams(cloth)), activeProfileId = null)

    /**
     * The table resized to [spec], a standard table of one of the sizes on offer. Within one kind the cloth, the ball
     * contact and the pocket size are kept and the balls are scaled onto the new size; choosing a table of the other
     * kind (pool to snooker or back) takes the cut and ball size of that kind and keeps the cloth and ball contact.
     * The active profile is cleared: the table is no longer the one it describes.
     */
    fun resizeTable(spec: TableSpec): TableSession {
        val current = table
        val resized = if (spec.kind == current.kind) {
            current.withSize(spec.lengthMeters, spec.widthMeters).copy(name = spec.name)
        } else {
            spec.withClothParams(current.cloth).withBallContact(current.ballContact)
        }
        return copy(layout = SampleLayouts.resizeTableLayout(layout, resized), activeProfileId = null)
    }

    /**
     * The pocket cut set to [preset]. Pool tables take any pool cut and snooker tables only their own; any other
     * choice leaves the table as it is. A calibration profile stays active: the pocket size is not part of one.
     */
    fun choosePocketPreset(preset: PocketPreset): TableSession {
        val fits = (preset == PocketPreset.SNOOKER) == (table.kind == TableKind.SNOOKER)
        return if (fits) copy(layout = layout.copy(table = table.withPocketPreset(preset))) else this
    }

    /** True when [profile] was calibrated on a table of the same kind as the one in use. */
    fun canApply(profile: SavedTableProfile): Boolean = profile.baseTableSpec.kind == table.kind

    /**
     * The table set from [profile]: its size, calibrated cloth and ball friction, with the pocket size kept
     * ([SavedTableProfile.toEffectiveTableSpec]). A profile for the other kind of table is refused and the session is
     * returned as it is ([canApply] says in advance), so the table keeps its kind and ball size.
     */
    fun applyProfile(profile: SavedTableProfile): TableSession {
        if (!canApply(profile)) return this
        val effective = profile.toEffectiveTableSpec(table)
        return copy(layout = SampleLayouts.resizeTableLayout(layout, effective), activeProfileId = profile.id)
    }

    /** The table forgets which calibration profile it came from. Its cloth, ball contact and pockets stay as they are. */
    fun clearProfile(): TableSession = copy(activeProfileId = null)

    /** One of the sample layouts: its balls and rules on the table in use, with the profile and pocket size kept. */
    fun loadPreset(index: Int, scenario: SampleScenario): TableSession = copy(
        layout = SampleLayouts.resizeTableLayout(scenario.layout, table),
        rules = scenario.rulesState,
        presetIndex = index,
    )

    /**
     * The first sample layout on the table in use, as at the start. The profile and pocket size are kept, so a reset
     * of the balls is not a reset of the table.
     */
    fun reset(scenario: SampleScenario): TableSession = loadPreset(0, scenario)

    /**
     * A layout from a drill, tutorial, walkthrough or break setup. It brings its own table, which replaces the one in
     * use, so no calibration profile stays active.
     */
    fun loadLayout(layout: TableLayout, rules: GameRulesState): TableSession =
        copy(layout = layout, rules = rules, activeProfileId = null, presetIndex = -1)

    /**
     * A confirmed photo scan: [scanned] holds the detected balls on the table the scan screen chose. The table in use
     * keeps its calibration profile, cloth, ball contact and pocket size; only the balls are replaced. When the scanned
     * table is another size of the same kind, the table takes that size (the cut, cloth and contact are kept, and the
     * profile no longer describes it, so it is cleared); another kind of table is taken as scanned. [chosenCloth] is the
     * cloth the person set on the scan screen, or `null` when they left it alone: a chosen cloth replaces the table's
     * and, like any cloth edit, ends the profile.
     */
    fun confirmScan(scanned: TableLayout, rules: GameRulesState, chosenCloth: ClothParams? = null): TableSession {
        val current = table
        val photographed = scanned.table
        val sameKind = photographed.kind == current.kind
        val sameSize = sameKind && sameSize(photographed, current)
        val sized = when {
            sameSize -> current
            sameKind -> current.withSize(photographed.lengthMeters, photographed.widthMeters).copy(name = photographed.name)
            else -> photographed
        }
        val clothChanged = chosenCloth != null && chosenCloth != sized.cloth
        val nextTable = if (chosenCloth != null) sized.withClothParams(chosenCloth) else sized
        return copy(
            layout = scanned.copy(table = nextTable),
            rules = rules,
            activeProfileId = if (sameSize && !clothChanged) activeProfileId else null,
            presetIndex = -1,
        )
    }

    /** A table with only a cue ball on the head spot and the ball that ends [rules]' game on the foot spot, sized for the table in use. */
    fun blankTable(): TableSession {
        val spec = table
        val radius = spec.ballRadiusMeters
        val endBall = rules.gameType.requiredEndBallNumber
        return copy(
            layout = TableLayout(
                table = spec,
                balls = listOf(
                    Ball(0, Vec2(spec.lengthMeters * 0.25, spec.widthMeters * 0.5), radiusMeters = radius),
                    Ball(endBall, spec.footSpot(), radiusMeters = radius),
                ),
            ),
            presetIndex = -1,
        )
    }

    /**
     * Ball [ballNumber] moved to [position], kept on the cloth and clear of other balls. [from] is the table the ball
     * was dragged on (a later shot of a plan, whose balls are where earlier shots left them) and [rulesAtFrom] the
     * rules there, which an accepted 8-ball shot may have changed; both default to the session's own.
     */
    fun moveBall(
        ballNumber: Int,
        position: Vec2,
        from: TableLayout = layout,
        rulesAtFrom: GameRulesState = rules,
    ): TableSession = copy(
        layout = SampleLayouts.moveBallSafely(from.copy(table = table), ballNumber, position),
        rules = rulesAtFrom,
    )

    /**
     * The setup chosen on the welcome screens: the sample layout [scenario] at [index] on a table of [spec]'s size and
     * kind (as [resizeTable] does) under [rules].
     */
    fun startSetup(index: Int, scenario: SampleScenario, spec: TableSpec, rules: GameRulesState): TableSession {
        val sized = resizeTable(spec)
        return sized.copy(
            layout = SampleLayouts.resizeTableLayout(scenario.layout, sized.table),
            rules = rules,
            presetIndex = index,
        )
    }

    companion object {
        private const val SIZE_TOLERANCE_METERS = 1e-6

        /** The session at the start: [scenario] on [spec], or on the scenario's own table when [spec] is `null`. */
        fun initial(index: Int, scenario: SampleScenario, rules: GameRulesState, spec: TableSpec?): TableSession =
            TableSession(
                layout = if (spec != null) SampleLayouts.resizeTableLayout(scenario.layout, spec) else scenario.layout,
                rules = rules,
                presetIndex = index,
            )

        private fun sameSize(a: TableSpec, b: TableSpec): Boolean =
            abs(a.lengthMeters - b.lengthMeters) < SIZE_TOLERANCE_METERS &&
                abs(a.widthMeters - b.widthMeters) < SIZE_TOLERANCE_METERS
    }
}
