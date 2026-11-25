package com.runout.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.runout.engine.vision.ArgbImageFrame
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_DECODED_IMAGE_SIDE_PX: Int = 960

/** Whether the camera temp folder has been swept in this process yet. */
private var cameraTempSweptInProcess: Boolean = false

@Composable
actual fun rememberPlatformPhotoPicker(
    onPhotoImported: (ImportedPhotoFrame) -> Unit,
    onError: (String) -> Unit,
): PlatformPhotoPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnPhotoImported by rememberUpdatedState(onPhotoImported)
    val latestOnError by rememberUpdatedState(onError)

    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    // Once per process, before any capture result can arrive, remove what an earlier interrupted capture left
    // behind. A capture that is still pending after a restore is kept so its result can still be read.
    remember(context) {
        if (!cameraTempSweptInProcess) {
            cameraTempSweptInProcess = true
            sweepCameraTempDirectory(
                File(context.cacheDir, CAMERA_TEMP_DIR_NAME),
                keep = pendingCameraPath?.let { File(it) },
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                decodeUriToArgbFrame(context, uri)
            }
            result.fold(
                onSuccess = { frame ->
                    latestOnPhotoImported(
                        ImportedPhotoFrame(
                            id = "gallery_${System.currentTimeMillis()}",
                            title = "Gallery Photo",
                            subtitle = "Imported from phone (${frame.width}×${frame.height}) • location tags stripped",
                            frame = frame,
                        ),
                    )
                },
                onFailure = { err ->
                    latestOnError(err.message ?: "Could not load the selected photo.")
                },
            )
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingCameraPath
        pendingCameraPath = null
        val file = path?.let { File(it) }

        if (!success) {
            runCatching { file?.delete() }
            return@rememberLauncherForActivityResult
        }
        if (file == null || !file.exists() || file.length() == 0L) {
            runCatching { file?.delete() }
            latestOnError("Captured photo could not be read from camera storage.")
            return@rememberLauncherForActivityResult
        }

        // ATOMIC starts the body even if the scope is already cancelled, so the finally below always runs and
        // the full-size file is deleted whatever happens to the coroutine.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val result = try {
                withContext(Dispatchers.Default) { decodeFileToArgbFrame(file) }
            } finally {
                withContext(NonCancellable) { runCatching { file.delete() } }
            }
            result.fold(
                onSuccess = { frame ->
                    latestOnPhotoImported(
                        ImportedPhotoFrame(
                            id = "camera_${System.currentTimeMillis()}",
                            title = "Camera Photo",
                            subtitle = "Captured in memory (${frame.width}×${frame.height}) • location tags stripped",
                            frame = frame,
                        ),
                    )
                },
                onFailure = { err ->
                    latestOnError(err.message ?: "Could not read the captured photo.")
                },
            )
        }
    }

    return remember(context, galleryLauncher, cameraLauncher) {
        object : PlatformPhotoPickerState {
            override val supportsCameraCapture: Boolean = true
            override val supportsGalleryImport: Boolean = true

            override fun launchCamera() {
                runCatching {
                    val tempDir = File(context.cacheDir, CAMERA_TEMP_DIR_NAME).apply { mkdirs() }
                    val file = File(tempDir, "capture_${System.currentTimeMillis()}.jpg")
                    sweepCameraTempDirectory(tempDir, keep = file)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    pendingCameraPath = file.absolutePath
                    cameraLauncher.launch(uri)
                }.onFailure { err ->
                    pendingCameraPath?.let { path -> runCatching { File(path).delete() } }
                    pendingCameraPath = null
                    latestOnError(err.message ?: "Unable to open camera on this device.")
                }
            }

            override fun launchGallery() {
                runCatching {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.onFailure { err ->
                    latestOnError(err.message ?: "Unable to open photo library on this device.")
                }
            }
        }
    }
}

actual fun ArgbImageFrame.toComposeImageBitmap(): ImageBitmap? =
    runCatching {
        Bitmap.createBitmap(argbPixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()

private fun decodeUriToArgbFrame(
    context: Context,
    uri: Uri,
): Result<ArgbImageFrame> = runCatching {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val decodedViaImageDecoder = runCatching {
            val source = ImageDecoder.createSource(resolver, uri)
            decodeWithImageDecoder(source)
        }.getOrNull()
        if (decodedViaImageDecoder != null) {
            return@runCatching bitmapToArgbFrame(decodedViaImageDecoder)
        }
    }

    val bytes = resolver.openInputStream(uri)?.use { readAtMost(it) }
        ?: error("Unable to open image stream.")
    decodeBytesFallback(bytes)
}

private fun decodeFileToArgbFrame(file: File): Result<ArgbImageFrame> = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val decodedViaImageDecoder = runCatching {
            val source = ImageDecoder.createSource(file)
            decodeWithImageDecoder(source)
        }.getOrNull()
        if (decodedViaImageDecoder != null) {
            return@runCatching bitmapToArgbFrame(decodedViaImageDecoder)
        }
    }

    if (!file.exists() || exceedsFallbackReadLimit(file.length())) error("Unable to read captured photo file.")
    val bytes = file.readBytes()
    decodeBytesFallback(bytes)
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(source: ImageDecoder.Source): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val rawW = info.size.width
        val rawH = info.size.height
        val maxSide = max(rawW, rawH)
        if (maxSide > MAX_DECODED_IMAGE_SIDE_PX && rawW > 0 && rawH > 0) {
            val scale = MAX_DECODED_IMAGE_SIDE_PX.toDouble() / maxSide.toDouble()
            val targetW = (rawW * scale).roundToInt().coerceAtLeast(16)
            val targetH = (rawH * scale).roundToInt().coerceAtLeast(16)
            decoder.setTargetSize(targetW, targetH)
        }
    }

private fun decodeBytesFallback(bytes: ByteArray): ArgbImageFrame {
    require(bytes.isNotEmpty()) { "Selected image file is empty." }

    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    val rawW = boundsOptions.outWidth
    val rawH = boundsOptions.outHeight
    require(rawW > 0 && rawH > 0) { "Selected file is not a valid image." }

    var sampleSize = 1
    while (max(rawW / sampleSize, rawH / sampleSize) > MAX_DECODED_IMAGE_SIDE_PX * 2) {
        sampleSize *= 2
    }

    val orientation = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        ?: error("Failed to decode image pixels.")

    val orientedBitmap = applyExifOrientationOnly(decoded, orientation)
    if (orientedBitmap !== decoded) {
        decoded.recycle()
    }

    val maxSide = max(orientedBitmap.width, orientedBitmap.height)
    val scaledBitmap = if (maxSide > MAX_DECODED_IMAGE_SIDE_PX) {
        val scale = MAX_DECODED_IMAGE_SIDE_PX.toDouble() / maxSide.toDouble()
        val targetW = (orientedBitmap.width * scale).roundToInt().coerceAtLeast(16)
        val targetH = (orientedBitmap.height * scale).roundToInt().coerceAtLeast(16)
        val scaled = Bitmap.createScaledBitmap(orientedBitmap, targetW, targetH, true)
        if (scaled !== orientedBitmap) {
            orientedBitmap.recycle()
        }
        scaled
    } else {
        orientedBitmap
    }

    return bitmapToArgbFrame(scaledBitmap)
}

private fun bitmapToArgbFrame(bitmap: Bitmap): ArgbImageFrame {
    val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
    } else {
        val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Unable to convert image to ARGB_8888.")
        bitmap.recycle()
        converted
    }
    val width = argbBitmap.width
    val height = argbBitmap.height
    val argbPixels = IntArray(width * height)
    argbBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
    argbBitmap.recycle()
    return ArgbImageFrame(
        width = width,
        height = height,
        argbPixels = argbPixels,
    )
}

private fun applyExifOrientationOnly(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
