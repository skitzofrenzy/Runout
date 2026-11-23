package com.runout.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.runout.engine.vision.ArgbImageFrame
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_DECODED_IMAGE_SIDE_PX: Int = 960

@Composable
actual fun rememberPlatformPhotoPicker(
    onPhotoImported: (ImportedPhotoFrame) -> Unit,
    onError: (String) -> Unit,
): PlatformPhotoPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnPhotoImported by rememberUpdatedState(onPhotoImported)
    val latestOnError by rememberUpdatedState(onError)

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

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
        val file = pendingCameraFile
        val uri = pendingCameraUri
        pendingCameraFile = null
        pendingCameraUri = null

        if (!success || uri == null) {
            runCatching { file?.delete() }
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    decodeUriToArgbFrame(context, uri)
                } finally {
                    runCatching { file?.delete() }
                }
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
                    val tempDir = File(context.cacheDir, "camera_temp").apply { mkdirs() }
                    val file = File(tempDir, "capture_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    pendingCameraFile = file
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }.onFailure { err ->
                    runCatching { pendingCameraFile?.delete() }
                    pendingCameraFile = null
                    pendingCameraUri = null
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

    // Read image bounds without loading full bitmap into memory
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, boundsOptions)
    } ?: error("Unable to open image stream.")

    val rawW = boundsOptions.outWidth
    val rawH = boundsOptions.outHeight
    require(rawW > 0 && rawH > 0) { "Selected file is not a valid image." }

    var sampleSize = 1
    while (max(rawW / sampleSize, rawH / sampleSize) > MAX_DECODED_IMAGE_SIDE_PX * 2) {
        sampleSize *= 2
    }

    // Read only EXIF orientation so rotated photos stand upright; GPS/EXIF tags are never copied
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOptions)
    } ?: error("Failed to decode image pixels.")

    val orientedBitmap = applyExifOrientationOnly(decoded, orientation)
    if (orientedBitmap !== decoded) {
        decoded.recycle()
    }

    val maxSide = max(orientedBitmap.width, orientedBitmap.height)
    val finalBitmap = if (maxSide > MAX_DECODED_IMAGE_SIDE_PX) {
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

    val width = finalBitmap.width
    val height = finalBitmap.height
    val argbPixels = IntArray(width * height)
    finalBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
    finalBitmap.recycle()

    ArgbImageFrame(
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
