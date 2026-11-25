package com.runout.app.camera

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Name of the cache sub-folder that holds the full-size picture while the camera app writes it. */
internal const val CAMERA_TEMP_DIR_NAME: String = "camera_temp"

/** Largest picture file, in bytes, that is read whole when the streaming decoder is not available. */
internal const val MAX_FALLBACK_IMAGE_BYTES: Long = 64L * 1024L * 1024L

/**
 * Picks the files in [existing] that a sweep of the camera temp folder should delete: every regular file except
 * [keep] (the file about to be written, or a capture still pending after the process was restored).
 */
internal fun selectCameraTempFilesToSweep(existing: List<File>, keep: File?): List<File> {
    val keepPath = keep?.absoluteFile?.normalize()
    return existing.filter { candidate ->
        candidate.isFile && (keepPath == null || candidate.absoluteFile.normalize() != keepPath)
    }
}

/** Deletes everything in [dir] except [keep]; failures are ignored because the next sweep will retry. */
internal fun sweepCameraTempDirectory(dir: File, keep: File? = null) {
    val existing = dir.listFiles()?.toList().orEmpty()
    for (stale in selectCameraTempFilesToSweep(existing, keep)) {
        runCatching { stale.delete() }
    }
}

/** True when a file of [lengthBytes] is too large to read whole into memory. */
internal fun exceedsFallbackReadLimit(lengthBytes: Long, limitBytes: Long = MAX_FALLBACK_IMAGE_BYTES): Boolean =
    lengthBytes > limitBytes

/**
 * Reads [stream] to the end, failing with an [IOException] as soon as more than [limitBytes] bytes have been
 * read, so an oversized or endless source cannot exhaust memory.
 */
internal fun readAtMost(stream: InputStream, limitBytes: Long = MAX_FALLBACK_IMAGE_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > limitBytes) throw IOException("Image is larger than $limitBytes bytes.")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
