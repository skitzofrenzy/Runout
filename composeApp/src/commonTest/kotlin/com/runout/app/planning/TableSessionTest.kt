package com.runout.app.planning

import com.runout.app.calibration.SampleCalibrationSessions
import com.runout.app.ui.SampleLayouts
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallContactParams
import com.runout.engine.model.ClothParams
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.TableKind
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableSessionTest {
    private val scenarios = SampleLayouts.all
    private val poolProfiles = SampleCalibrationSessions.defaultSavedProfiles()
    private val poolProfile = poolProfiles.first()
    private val snookerProfile = poolProfile.copy(id = "snooker", baseTableSpec = TableSpec.fullSizeSnooker())

    private fun poolSession(): TableSession =
        TableSession.initial(0, scenarios[0], scenarios[0].rulesState, TableSpec.nineFootPool())

    private fun snookerSession(): TableSession =
        TableSession.initial(0, scenarios[0], scenarios[0].rulesState, TableSpec.fullSizeSnooker())

    private fun assertSnooker(session: TableSession, where: String) {
        assertEquals(TableKind.SNOOKER, session.table.kind, "$where: kind")
        assertEquals(TableSpec.SNOOKER_BALL_RADIUS_METERS, session.table.ballRadiusMeters, "$where: table ball size")
        assertTrue(
            session.layout.balls.all { it.radiusMeters == TableSpec.SNOOKER_BALL_RADIUS_METERS },
            "$where: ball sizes ${session.layout.balls.map { it.radiusMeters }}",
        )
        assertEquals(PocketPreset.SNOOKER, session.table.pocketPreset, "$where: pockets")
    }

    @Test
    fun theInitialSnookerTableHasSnookerBallsAndPockets() {
        assertSnooker(snookerSession(), "initial")
    }

    @Test
    fun theChosenPocketSizeSurvivesAProfileASizeChangeAndTheProfileBeingCleared() {
        var session = poolSession().choosePocketPreset(PocketPreset.BAR_BOX)
        assertEquals(PocketPreset.BAR_BOX, session.table.pocketPreset)

        session = session.applyProfile(poolProfile)
        assertEquals(poolProfile.id, session.activeProfileId)
        assertEquals(PocketPreset.BAR_BOX, session.table.pocketPreset, "after the profile")
        assertEquals(poolProfile.toEffectiveTableSpec().cloth, session.table.cloth)

        session = session.resizeTable(TableSpec.sevenFootPool())
        assertNull(session.activeProfileId, "a size change ends the profile")
        assertEquals(PocketPreset.BAR_BOX, session.table.pocketPreset, "after the size change")
        assertEquals(TableSpec.sevenFootPool().lengthMeters, session.table.lengthMeters)

        session = session.applyProfile(poolProfiles[1]).clearProfile()
        assertNull(session.activeProfileId)
        assertEquals(PocketPreset.BAR_BOX, session.table.pocketPreset, "after the profile is cleared")
        assertEquals(
            session.table.pockets,
            TableSpec.defaultPockets(
                session.table.lengthMeters,
                session.table.widthMeters,
                session.table.ballRadiusMeters,
                PocketPreset.BAR_BOX,
            ),
        )
    }

    @Test
    fun applyingAProfileKeepsTheKindAndBallSizeAndRefusesAProfileOfTheOtherKind() {
        val pool = poolSession().applyProfile(poolProfile)
        assertEquals(TableKind.POOL, pool.table.kind)
        assertEquals(TableSpec.POOL_BALL_RADIUS_METERS, pool.table.ballRadiusMeters)
        assertTrue(pool.layout.balls.all { it.radiusMeters == TableSpec.POOL_BALL_RADIUS_METERS })

        val snooker = snookerSession()
        assertTrue(!snooker.canApply(poolProfile))
        assertEquals(snooker, snooker.applyProfile(poolProfile), "a pool profile leaves a snooker table alone")
        assertSnooker(snooker.applyProfile(poolProfile), "after a pool profile")

        assertTrue(poolSession().canApply(poolProfile))
        assertTrue(!poolSession().canApply(snookerProfile))
        assertEquals(poolSession(), poolSession().applyProfile(snookerProfile))
    }

    @Test
    fun aProfileSetsTheCalibratedClothAndBallFrictionAndIsNamedAfterTheProfile() {
        val applied = poolSession().applyProfile(poolProfile)
        val expected = poolProfile.toEffectiveTableSpec()
        assertEquals(expected.cloth, applied.table.cloth)
        assertEquals(expected.ballContact.frictionMu, applied.table.ballContact.frictionMu)
        assertEquals(poolProfile.profileName, applied.table.name)
    }

    @Test
    fun aPhotoScanKeepsTheProfileTheClothAndTheContactAndReplacesTheBalls() {
        val calibrated = poolSession().choosePocketPreset(PocketPreset.PRO_CUT).applyProfile(poolProfile)
        val scannedBalls = listOf(
            Ball(0, Vec2(0.6, 0.5), TableSpec.POOL_BALL_RADIUS_METERS),
            Ball(8, Vec2(1.9, 0.7), TableSpec.POOL_BALL_RADIUS_METERS),
        )
        // The scan screen hands back its own table: same size, but a default cloth.
        val scanned = TableLayout(calibrated.table.copy(cloth = ClothParams(), ballContact = BallContactParams()), scannedBalls)
        val rules = GameRulesState(GameType.EIGHT_BALL)

        val after = calibrated.confirmScan(scanned, rules)

        assertEquals(calibrated.activeProfileId, after.activeProfileId)
        assertEquals(calibrated.table, after.table, "cloth, contact, pockets and name are those of the calibrated table")
        assertEquals(scannedBalls, after.layout.balls)
        assertEquals(rules, after.rules)
        assertEquals(-1, after.presetIndex)
    }

    @Test
    fun aClothSetOnTheScanScreenReplacesTheTablesClothAndEndsTheProfile() {
        val calibrated = poolSession().choosePocketPreset(PocketPreset.PRO_CUT).applyProfile(poolProfile)
        val scanned = TableLayout(
            calibrated.table,
            listOf(Ball(0, Vec2(0.6, 0.5), TableSpec.POOL_BALL_RADIUS_METERS)),
        )
        val chosen = calibrated.table.cloth.copy(rollingResistanceMuR = calibrated.table.cloth.rollingResistanceMuR * 1.5)

        val changed = calibrated.confirmScan(scanned, calibrated.rules, chosenCloth = chosen)

        assertEquals(chosen, changed.table.cloth)
        assertEquals(PocketPreset.PRO_CUT, changed.table.pocketPreset)
        assertEquals(calibrated.table.ballContact, changed.table.ballContact)
        assertNull(changed.activeProfileId, "the table is no longer the calibrated one")

        // Confirming with the cloth the table already has is not a change.
        val same = calibrated.confirmScan(scanned, calibrated.rules, chosenCloth = calibrated.table.cloth)
        assertEquals(calibrated.activeProfileId, same.activeProfileId)
        assertEquals(calibrated.table, same.table)
    }

    @Test
    fun aPhotoScanOfAnotherSizeTakesTheSizeKeepsClothAndPocketsAndEndsTheProfile() {
        val calibrated = poolSession().choosePocketPreset(PocketPreset.PRO_CUT).applyProfile(poolProfile)
        val other = TableSpec.sevenFootPool()
        val scanned = TableLayout(other, listOf(Ball(0, Vec2(0.5, 0.4), other.ballRadiusMeters)))

        val after = calibrated.confirmScan(scanned, calibrated.rules)

        assertEquals(other.lengthMeters, after.table.lengthMeters)
        assertEquals(other.widthMeters, after.table.widthMeters)
        assertEquals(calibrated.table.cloth, after.table.cloth)
        assertEquals(PocketPreset.PRO_CUT, after.table.pocketPreset)
        assertNull(after.activeProfileId)
    }

    @Test
    fun aSnookerTableStaysSnookerThroughEveryOperation() {
        var session = snookerSession()
        assertSnooker(session, "start")

        session = session.moveBall(
            ballNumber = 0,
            position = Vec2(session.table.lengthMeters * 0.4, session.table.widthMeters * 0.4),
        )
        assertSnooker(session, "move a ball")

        session = session.choosePocketPreset(PocketPreset.TOURNAMENT)
        assertSnooker(session, "a pool pocket cut is refused")

        session = session.applyProfile(poolProfile)
        assertSnooker(session, "a pool profile is refused")

        session = session.applyProfile(snookerProfile)
        assertSnooker(session, "a snooker profile")

        session = session.resizeTable(TableSpec.fullSizeSnooker())
        assertSnooker(session, "resize to the same snooker table")

        session = session.loadPreset(1, scenarios[1])
        assertSnooker(session, "starting layout")

        session = session.blankTable()
        assertSnooker(session, "blank table")

        session = session.withCloth(ClothParams())
        assertSnooker(session, "cloth")

        val scanned = TableLayout(
            TableSpec.fullSizeSnooker(),
            listOf(Ball(0, Vec2(0.9, 0.9), TableSpec.SNOOKER_BALL_RADIUS_METERS)),
        )
        session = session.confirmScan(scanned, session.rules)
        assertSnooker(session, "photo scan")

        session = session.editBalls { SampleLayouts.addMissingBall(it, 3) }
        assertSnooker(session, "add a ball")

        session = session.reset(scenarios[0])
        assertSnooker(session, "reset")
    }

    @Test
    fun aBlankTableUsesTheBallSizeOfTheTable() {
        val snooker = snookerSession().blankTable()
        assertEquals(setOf(0, snooker.rules.gameType.requiredEndBallNumber), snooker.layout.balls.map { it.number }.toSet())
        assertTrue(snooker.layout.balls.all { it.radiusMeters == TableSpec.SNOOKER_BALL_RADIUS_METERS })

        val pool = poolSession().blankTable()
        assertTrue(pool.layout.balls.all { it.radiusMeters == TableSpec.POOL_BALL_RADIUS_METERS })
        assertEquals(-1, pool.presetIndex)
    }

    @Test
    fun resizingKeepsTheClothAndChoosingAnotherKindTakesItsOwnPocketsAndBalls() {
        val cloth = ClothParams(rollingResistanceMuR = 0.02)
        val worn = poolSession().withCloth(cloth)
        val resized = worn.resizeTable(TableSpec.eightFootPool())
        assertEquals(cloth, resized.table.cloth)
        assertEquals(TableSpec.eightFootPool().lengthMeters, resized.table.lengthMeters)
        assertEquals("8 ft Pool", resized.table.name)

        val snooker = worn.resizeTable(TableSpec.fullSizeSnooker())
        assertSnooker(snooker, "pool to snooker")
        assertEquals(cloth, snooker.table.cloth)

        val back = snooker.resizeTable(TableSpec.nineFootPool())
        assertEquals(TableKind.POOL, back.table.kind)
        assertEquals(TableSpec.POOL_BALL_RADIUS_METERS, back.table.ballRadiusMeters)
        assertTrue(back.layout.balls.all { it.radiusMeters == TableSpec.POOL_BALL_RADIUS_METERS })
    }

    @Test
    fun aClothEditEndsTheProfileButAPocketChoiceDoesNot() {
        val applied = poolSession().applyProfile(poolProfile)
        assertEquals(poolProfile.id, applied.choosePocketPreset(PocketPreset.TOURNAMENT).activeProfileId)
        assertNull(applied.withCloth(ClothParams()).activeProfileId)
    }

    @Test
    fun aStartingLayoutAndAResetKeepTheTableTheProfileAndThePockets() {
        val applied = poolSession().choosePocketPreset(PocketPreset.BAR_BOX).applyProfile(poolProfile)
        val loaded = applied.loadPreset(2, scenarios[2])
        assertEquals(applied.table, loaded.table)
        assertEquals(applied.activeProfileId, loaded.activeProfileId)
        assertEquals(2, loaded.presetIndex)
        assertEquals(scenarios[2].rulesState, loaded.rules)

        val reset = loaded.reset(scenarios[0])
        assertEquals(applied.table, reset.table)
        assertEquals(applied.activeProfileId, reset.activeProfileId)
        assertEquals(0, reset.presetIndex)
        assertEquals(scenarios[0].rulesState, reset.rules)
    }

    @Test
    fun aLayoutFromADrillBringsItsOwnTableAndEndsTheProfile() {
        val applied = poolSession().applyProfile(poolProfile)
        val drill = TableLayout(TableSpec.eightFootPool(), listOf(Ball(0, Vec2(0.5, 0.5))))
        val loaded = applied.loadLayout(drill, GameRulesState(GameType.NINE_BALL))
        assertEquals(drill, loaded.layout)
        assertNull(loaded.activeProfileId)
        assertEquals(-1, loaded.presetIndex)
    }

    @Test
    fun movingABallUsesTheTableAndRulesOfTheFocusedShot() {
        val session = poolSession()
        val ball = session.layout.balls.first { it.number == 1 }
        val target = Vec2(ball.position.x + 0.05, ball.position.y)
        val rules = GameRulesState(GameType.NINE_BALL)
        val moved = session.moveBall(1, target, from = session.layout, rulesAtFrom = rules)
        assertEquals(target, moved.layout.ballByNumber(1)?.position)
        assertEquals(rules, moved.rules)
        assertEquals(session.table, moved.table)
        assertNotEquals(session.layout, moved.layout)
    }

    @Test
    fun theWelcomeSetupKeepsTheClothAndPocketSizeOfAKindAndTakesTheChosenSize() {
        val cloth = ClothParams(rollingResistanceMuR = 0.02)
        val before = poolSession().withCloth(cloth).choosePocketPreset(PocketPreset.PRO_CUT)
        val rules = GameRulesState(GameType.TEN_BALL)
        val after = before.startSetup(2, scenarios[2], TableSpec.sevenFootPool(), rules)
        assertEquals(TableSpec.sevenFootPool().lengthMeters, after.table.lengthMeters)
        assertEquals(cloth, after.table.cloth)
        assertEquals(PocketPreset.PRO_CUT, after.table.pocketPreset)
        assertEquals(rules, after.rules)
        assertEquals(2, after.presetIndex)
    }
}
