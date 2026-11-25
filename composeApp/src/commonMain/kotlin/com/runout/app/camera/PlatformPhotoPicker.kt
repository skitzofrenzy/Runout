package com.runout.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.runout.engine.vision.ArgbImageFrame

/**
 * Decoded photo frame returned from the device camera or photo gallery, stripped of location metadata.
 */
data class ImportedPhotoFrame(
    val id: String,
    val title: String,
    val subtitle: String,
    val frame: ArgbImageFrame,
)

/**
 * Platform photo capture and gallery picker trigger handle.
 */
interface PlatformPhotoPickerState {
    val supportsCameraCapture: Boolean
    val supportsGalleryImport: Boolean
    fun launchCamera()
    fun launchGallery()
}

/**
 * Registers platform-native camera capture and photo library import launchers in Compose.
 */
@Composable
expect fun rememberPlatformPhotoPicker(
    onPhotoImported: (ImportedPhotoFrame) -> Unit,
    onError: (String) -> Unit,
): PlatformPhotoPickerState

/**
 * Converts a decoded [ArgbImageFrame] into a Compose [ImageBitmap] for preview overlay rendering.
 */
expect fun ArgbImageFrame.toComposeImageBitmap(): ImageBitmap?
