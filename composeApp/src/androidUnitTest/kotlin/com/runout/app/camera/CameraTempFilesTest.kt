package com.runout.app.camera

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraTempFilesTest {
    private fun tempDirectory(): File =
        kotlin.io.path.createTempDirectory("camera_temp_test").toFile().also { it.deleteOnExit() }

    @Test
    fun sweepSelectionTakesEveryFileExceptTheOneToKeep() {
        val dir = tempDirectory()
        val old = File(dir, "capture_1.jpg").apply { writeText("x") }
        val other = File(dir, "capture_2.jpg").apply { writeText("x") }
        val keep = File(dir, "capture_3.jpg").apply { writeText("x") }
        val selected = selectCameraTempFilesToSweep(listOf(old, other, keep), keep)
        assertEquals(setOf(old, other), selected.toSet())
        dir.deleteRecursively()
    }

    @Test
    fun sweepSelectionIgnoresFoldersAndMissingFiles() {
        val dir = tempDirectory()
        val sub = File(dir, "sub").apply { mkdirs() }
        val ghost = File(dir, "gone.jpg")
        assertTrue(selectCameraTempFilesToSweep(listOf(sub, ghost), null).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun sweepSelectionRecognisesTheKeptFileByItsNormalisedPath() {
        val dir = tempDirectory()
        val keep = File(dir, "capture_1.jpg").apply { writeText("x") }
        val sameFileOtherSpelling = File(File(dir, "sub/.."), "capture_1.jpg")
        assertTrue(selectCameraTempFilesToSweep(listOf(keep), sameFileOtherSpelling).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun sweepDeletesStaleFilesAndKeepsTheOneToBeWritten() {
        val dir = tempDirectory()
        val old = File(dir, "capture_1.jpg").apply { writeText("x") }
        val keep = File(dir, "capture_2.jpg").apply { writeText("x") }
        sweepCameraTempDirectory(dir, keep)
        assertFalse(old.exists())
        assertTrue(keep.exists())
        sweepCameraTempDirectory(dir, null)
        assertFalse(keep.exists())
        dir.deleteRecursively()
    }

    @Test
    fun sweepOfAMissingFolderDoesNothing() {
        val dir = tempDirectory()
        sweepCameraTempDirectory(File(dir, "absent"))
        dir.deleteRecursively()
    }

    @Test
    fun readLimitRejectsOnlyFilesAboveTheLimit() {
        assertFalse(exceedsFallbackReadLimit(MAX_FALLBACK_IMAGE_BYTES))
        assertTrue(exceedsFallbackReadLimit(MAX_FALLBACK_IMAGE_BYTES + 1))
    }

    @Test
    fun boundedReadReturnsTheBytesWithinTheLimit() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertContentEquals(data, readAtMost(ByteArrayInputStream(data), limitBytes = data.size.toLong()))
    }

    @Test
    fun boundedReadFailsWhenTheSourceIsLargerThanTheLimit() {
        val data = ByteArray(1_000)
        assertFailsWith<IOException> { readAtMost(ByteArrayInputStream(data), limitBytes = 999L) }
    }
}
