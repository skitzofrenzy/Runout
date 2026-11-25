package com.runout.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.runout.engine.vision.ArgbImageFrame

@Composable
actual fun rememberPlatformPhotoPicker(
    onPhotoImported: (ImportedPhotoFrame) -> Unit,
    onError: (String) -> Unit,
): PlatformPhotoPickerState = remember(onError) {
    object : PlatformPhotoPickerState {
        override val supportsCameraCapture: Boolean = false
        override val supportsGalleryImport: Boolean = false

        override fun launchCamera() {
            onError("Taking a photo is not available on iOS yet. Use a built-in sample photo instead.")
        }

        override fun launchGallery() {
            onError("Photo import is not available on iOS yet. Use a built-in sample photo instead.")
        }
    }
}

actual fun ArgbImageFrame.toComposeImageBitmap(): ImageBitmap? = null
