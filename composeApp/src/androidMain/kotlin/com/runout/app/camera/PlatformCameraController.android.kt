package com.runout.app.camera

import com.runout.engine.vision.CameraIntrinsicsSpec

/**
 * Source of the built-in sample table photos. This controller does not open the camera; it returns the bundled
 * synthetic photos, which carry no EXIF or location metadata, so there is nothing to strip.
 */
actual class PlatformCameraController actual constructor() {
    actual val platformName: String = "Built-in sample photos"
    actual val stripsExifAndGpsInMemory: Boolean = true

    actual fun defaultIntrinsics(): CameraIntrinsicsSpec = SampleCapturedPhotos.defaultIntrinsics()

    actual fun capturePresetPhoto(presetId: String): CapturedTablePhoto =
        SampleCapturedPhotos.allPresets().firstOrNull { it.id == presetId }
            ?: SampleCapturedPhotos.angledNineFootNineBall()
}
