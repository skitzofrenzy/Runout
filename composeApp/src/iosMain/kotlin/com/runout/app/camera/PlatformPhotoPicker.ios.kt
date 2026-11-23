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
            onError("Native iOS camera capture is enabled in the Xcode build; use a built-in sample photo for preview.")
        }

        override fun launchGallery() {
            onError("Native iOS photo library import is enabled in the Xcode build; use a built-in sample photo for preview.")
        }
    }
}

actual fun ArgbImageFrame.toComposeImageBitmap(): ImageBitmap? = null
