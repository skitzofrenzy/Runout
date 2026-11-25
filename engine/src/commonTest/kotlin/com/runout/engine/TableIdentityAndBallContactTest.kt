package com.runout.engine

import com.runout.engine.calibration.CalibratedTableParams
import com.runout.engine.calibration.SavedTableProfile
import com.runout.engine.calibration.TableSlopeMap
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallContactParams
import com.runout.engine.model.ClothParams
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableKind
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a table says about itself (kind, pocket cut) and the ball-ball contact parameters it carries. */
class TableIdentityAndBallContactTest {

    private val nine = TableSpec.nineFootPool()
    private val r = nine.ballRadiusMeters

    private fun profile(base: TableSpec, muB: Double) = SavedTableProfile(
        id = "p",
        profileName = "Club table",
        baseTableSpec = base,
        calibratedParams = CalibratedTableParams(ClothParams(rollingResistanceMuR = 0.012), ballBallFrictionMuB = muB),
        slopeMap = TableSlopeMap(cells = emptyList()),
        calibratedDateIso = "2026-11-24",
    )

    @Test
    fun standardTablesKnowTheirKindAndPocketCut() {
        for (table in listOf(TableSpec.nineFootPool(), TableSpec.eightFootPool(), TableSpec.sevenFootPool())) {
            assertEquals(TableKind.POOL, table.kind)
            assertEquals(PocketPreset.TOURNAMENT, table.pocketPreset)
            assertEquals(TableSpec.defaultPockets(table.lengthMeters, table.widthMeters, r, PocketPreset.TOURNAMENT), table.pockets)
        }
        val snooker = TableSpec.fullSizeSnooker()
        assertEquals(TableKind.SNOOKER, snooker.kind)
        assertEquals(PocketPreset.SNOOKER, snooker.pocketPreset)
        assertEquals(TableSpec.SNOOKER_BALL_RADIUS_METERS, snooker.ballRadiusMeters)
        val proCut = TableSpec.nineFootPool(pocketPreset = PocketPreset.PRO_CUT)
        assertEquals(PocketPreset.PRO_CUT, proCut.pocketPreset)
        assertEquals(TableSpec.defaultPockets(2.54, 1.27, r, PocketPreset.PRO_CUT), proCut.pockets)
    }

    @Test
    fun theConstructorDescribesThePocketsItIsGiven() {
        val plain = TableSpec(2.54, 1.27)
        assertEquals(TableKind.POOL, plain.kind)
        assertEquals(PocketPreset.TOURNAMENT, plain.pocketPreset)
        assertEquals(nine.pockets, plain.pockets)
        val snookerBalls = TableSpec(3.569, 1.778, TableSpec.SNOOKER_BALL_RADIUS_METERS)
        assertEquals(TableKind.SNOOKER, snookerBalls.kind)
        assertEquals(PocketPreset.SNOOKER, snookerBalls.pocketPreset)
        val barBox = TableSpec(1.98, 0.99, pockets = TableSpec.defaultPockets(1.98, 0.99, r, PocketPreset.BAR_BOX))
        assertEquals(PocketPreset.BAR_BOX, barBox.pocketPreset)
        val custom = TableSpec(2.54, 1.27, pockets = nine.pockets.drop(1))
        assertNull(custom.pocketPreset)
    }

    @Test
    fun pocketCutAndSizeChangesRebuildThePockets() {
        val barBox = nine.withPocketPreset(PocketPreset.BAR_BOX)
        assertEquals(PocketPreset.BAR_BOX, barBox.pocketPreset)
        assertEquals(TableSpec.defaultPockets(2.54, 1.27, r, PocketPreset.BAR_BOX), barBox.pockets)
        assertEquals(nine.copy(pockets = barBox.pockets, pocketPreset = PocketPreset.BAR_BOX), barBox)

        val smaller = barBox.withSize(2.24, 1.12)
        assertEquals(2.24, smaller.lengthMeters)
        assertEquals(PocketPreset.BAR_BOX, smaller.pocketPreset)
        assertEquals(TableSpec.defaultPockets(2.24, 1.12, r, PocketPreset.BAR_BOX), smaller.pockets)
        assertEquals(TableKind.POOL, smaller.kind)
    }

    @Test
    fun aCalibrationProfileKeepsKindBallsAndThePocketCutOfTheTableItIsAppliedTo() {
        val profile = profile(TableSpec.nineFootPool(), muB = 0.07)
        val effective = profile.toEffectiveTableSpec()
        assertEquals(TableKind.POOL, effective.kind)
        assertEquals(r, effective.ballRadiusMeters)
        assertEquals(PocketPreset.TOURNAMENT, effective.pocketPreset)
        assertEquals(0.012, effective.cloth.rollingResistanceMuR)
        assertEquals(0.07, effective.ballContact.frictionMu)
        assertEquals("Club table", effective.name)

        val applied = profile.toEffectiveTableSpec(TableSpec.nineFootPool(pocketPreset = PocketPreset.PRO_CUT))
        assertEquals(PocketPreset.PRO_CUT, applied.pocketPreset)
        assertEquals(TableSpec.defaultPockets(2.54, 1.27, r, PocketPreset.PRO_CUT), applied.pockets)
        assertEquals(0.012, applied.cloth.rollingResistanceMuR)
        assertEquals(0.07, applied.ballContact.frictionMu)

        val fromSmallerTable = profile.toEffectiveTableSpec(TableSpec.sevenFootPool(pocketPreset = PocketPreset.BAR_BOX))
        assertEquals(2.54, fromSmallerTable.lengthMeters, "the profile's own size")
        assertEquals(PocketPreset.BAR_BOX, fromSmallerTable.pocketPreset)

        val onSnooker = profile.toEffectiveTableSpec(TableSpec.fullSizeSnooker())
        assertEquals(TableKind.POOL, onSnooker.kind)
        assertEquals(PocketPreset.TOURNAMENT, onSnooker.pocketPreset, "a snooker cut does not carry onto a pool table")

        val snookerProfile = profile(TableSpec.fullSizeSnooker(), muB = 0.05).toEffectiveTableSpec(TableSpec.fullSizeSnooker())
        assertEquals(TableKind.SNOOKER, snookerProfile.kind)
        assertEquals(TableSpec.SNOOKER_BALL_RADIUS_METERS, snookerProfile.ballRadiusMeters)
    }

    @Test
    fun theDefaultTableUsesTheStandardContactParameters() {
        assertEquals(BallContactParams(), nine.ballContact)
        assertEquals(SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU, nine.ballContact.frictionMu, 0.0)
        assertEquals(MotionPhysics.BALL_BALL_RESTITUTION, nine.ballContact.restitution, 0.0)
        val geometric = halfBallAim()
        val withTable = assertNotNull(SpinPhysics.solveCompensatedAim(geometric, r, r, 1.5, TipOffset(), ShaftType.STANDARD, nine))
        val withoutTable = assertNotNull(SpinPhysics.solveCompensatedAim(geometric, r, r, 1.5, TipOffset(), ShaftType.STANDARD, null))
        assertEquals(withoutTable.totalThrowAngleRadians, withTable.totalThrowAngleRadians, 0.0)
    }

    @Test
    fun calibratedBallFrictionChangesTheThrowAndTheCompensatedAim() {
        val geometric = halfBallAim()
        val grippy = profile(nine, muB = 0.09).toEffectiveTableSpec()
        val slick = profile(nine, muB = 0.025).toEffectiveTableSpec()
        val default = assertNotNull(SpinPhysics.solveCompensatedAim(geometric, r, r, 1.5, TipOffset(), ShaftType.STANDARD, nine))
        val high = assertNotNull(SpinPhysics.solveCompensatedAim(geometric, r, r, 1.5, TipOffset(), ShaftType.STANDARD, grippy))
        val low = assertNotNull(SpinPhysics.solveCompensatedAim(geometric, r, r, 1.5, TipOffset(), ShaftType.STANDARD, slick))
        assertTrue(abs(high.totalThrowAngleRadians) > abs(default.totalThrowAngleRadians) + 1e-4)
        assertTrue(abs(low.totalThrowAngleRadians) < abs(default.totalThrowAngleRadians) - 1e-4)
        assertNotEquals(default.compensatedGhostBallCenter, high.compensatedGhostBallCenter)

        // The contact played in the simulator uses the same friction.
        val pre = SpinPhysics.computePreImpactCueState(1.5, TipOffset(), geometric.cueToGhostDistanceMeters, nine)
        val postDefault = SpinPhysics.postImpactStateFor(geometric, pre.impactSpeedMps, pre.impactSpinFactorK, 0.0, nine)
        val postHigh = SpinPhysics.postImpactStateFor(geometric, pre.impactSpeedMps, pre.impactSpinFactorK, 0.0, grippy)
        assertTrue(abs(postHigh.throwRadians) > abs(postDefault.throwRadians) + 1e-4)
        // Either way the object ball leaves along the pot line.
        assertTrue(abs(postHigh.objectBall.velocity.angleTo(geometric.objectBallDirection)) < 1e-9)
    }

    @Test
    fun ballRestitutionComesFromTheTable() {
        val objectAt = Vec2(1.4, 0.635)
        val straight = GhostBallAiming.computeAimGeometryToPoint(
            Ball(0, objectAt - Vec2(0.6, 0.0)), Ball(1, objectAt), objectAt + Vec2(0.8, 0.0), table = nine,
        )
        val v = 1.2
        for (e in listOf(0.90, 0.98)) {
            val post = SpinPhysics.postImpactStateFor(straight, v, 0.0, 0.0, nine.withBallContact(BallContactParams(restitution = e)))
            assertEquals(0.5 * (1.0 + e) * v, post.objectBallSpeedMps, 1e-12)
            assertEquals(0.5 * (1.0 - e) * v, post.launchSpeedMps, 1e-12)
        }
    }

    /** A 30-degree cut on open cloth, pot line +x. */
    private fun halfBallAim(): AimGeometry {
        val objectAt = Vec2(1.4, 0.635)
        val cue = Ball(0, objectAt - Vec2(1.0, 0.0).rotate(PI / 6.0) * 0.6)
        return GhostBallAiming.computeAimGeometryToPoint(cue, Ball(1, objectAt), objectAt + Vec2(0.8, 0.0), table = nine)
    }
}
