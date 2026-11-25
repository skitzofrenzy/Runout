package com.runout.app.ui

import com.runout.engine.model.PocketPreset
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.PocketGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableRenderingTest {
    private fun tables(): List<TableSpec> = listOf(
        TableSpec.sevenFootPool(),
        TableSpec.eightFootPool(),
        TableSpec.nineFootPool(),
        TableSpec.fullSizeSnooker(),
    )

    private fun everyPreset(table: TableSpec): List<TableSpec> {
        val presets = if (table.ballRadiusMeters < 0.0275) {
            listOf(PocketPreset.SNOOKER)
        } else {
            listOf(PocketPreset.TOURNAMENT, PocketPreset.PRO_CUT, PocketPreset.BAR_BOX)
        }
        return presets.map { preset ->
            table.copy(
                pockets = TableSpec.defaultPockets(table.lengthMeters, table.widthMeters, table.ballRadiusMeters, preset),
            )
        }
    }

    @Test
    fun railBandHoldsEveryHoleWithWoodToSpare() {
        for (base in tables()) {
            for (table in everyPreset(base)) {
                val band = requiredRailBandMeters(table)
                for (pocket in table.pockets) {
                    val reach = holeReachBeyondNoseMeters(table, pocket)
                    assertTrue(
                        reach + RAIL_WOOD_LIP_METERS <= band + 1e-9,
                        "${table.name} ${pocket.id}: hole reaches $reach m past the nose, band is $band m",
                    )
                }
                assertTrue(band >= CUSHION_DEPTH_METERS)
            }
        }
    }

    @Test
    fun sixCushionSegmentsEndAtJawTipsAndStayOutsideThePlayingSurface() {
        for (base in tables()) {
            for (table in everyPreset(base)) {
                val segments = cushionSegments(table)
                assertEquals(6, segments.size, table.name)
                val tips = table.pockets.flatMap { listOf(it.jawTipA, it.jawTipB) }
                for (segment in segments) {
                    assertTrue(tips.any { it.distanceTo(segment.noseStart) < 1e-9 }, "start is a jaw tip")
                    assertTrue(tips.any { it.distanceTo(segment.noseEnd) < 1e-9 }, "end is a jaw tip")
                    for (back in listOf(segment.backStart, segment.backEnd)) {
                        val outsideX = back.x <= 1e-9 || back.x >= table.lengthMeters - 1e-9
                        val outsideY = back.y <= 1e-9 || back.y >= table.widthMeters - 1e-9
                        assertTrue(outsideX || outsideY, "${table.name}: back edge point $back is on the cloth")
                    }
                }
            }
        }
    }

    @Test
    fun facingsLeanTowardTheHoleNotAwayFromIt() {
        val table = TableSpec.nineFootPool()
        for (pocket in table.pockets) {
            for (tip in listOf(pocket.jawTipA, pocket.jawTipB)) {
                val back = facingBackPoint(table, pocket, tip)
                val normal = PocketGeometry.facingNormal(pocket, tip)
                // The facing runs along the cushion end, so it is perpendicular to the engine's facing normal.
                assertEquals(0.0, (back - tip).normalized().dot(normal), 1e-9)
                // Going back along the facing brings the point nearer the hole than the tip is.
                assertTrue(back.distanceTo(pocket.holeCenter) < tip.distanceTo(pocket.holeCenter))
            }
        }
    }

    @Test
    fun pocketTapPointLiesBetweenMouthAndHole() {
        for (pocket in TableSpec.nineFootPool().pockets) {
            val tap = pocketTapPoint(pocket)
            val expected = (pocket.mouthCenter + pocket.holeCenter) * 0.5
            assertEquals(0.0, tap.distanceTo(expected), 1e-12)
        }
    }

    @Test
    fun railBandKeepsTheTableOnScreenAndTheSameShapeOnEverySize() {
        for (table in tables()) {
            val aspect = (table.lengthMeters / table.widthMeters).toFloat()
            val height = 300f
            val geom = computeTableCanvasGeometry(height * aspect, height, table)
            assertTrue(geom.playLeft - geom.railPaddingPx >= -0.01f, "${table.name}: rail starts left of the canvas")
            assertTrue(geom.playTop - geom.railPaddingPx >= -0.01f, "${table.name}: rail starts above the canvas")
            assertTrue(geom.playTop + geom.playHeight + geom.railPaddingPx <= height + 0.01f)
            val band = geom.railPaddingPx / geom.pxPerMeter
            assertTrue(band + 1e-4 >= requiredRailBandMeters(table), "${table.name}: band $band m")
            assertEquals(table.lengthMeters / table.widthMeters, (geom.playWidth / geom.playHeight).toDouble(), 1e-3)
        }
    }

    @Test
    fun cushionShadeIsDarkerThanTheCloth() {
        val cloth = androidx.compose.ui.graphics.Color(0xFF137547)
        val shade = cushionShadeOf(cloth)
        assertTrue(shade.green < cloth.green && shade.red <= cloth.red && shade.blue <= cloth.blue)
    }
}
