package com.runout.app.camera

import com.runout.engine.vision.CameraIntrinsicsSpec

/**
 * iOS AVFoundation camera controller that processes captured frames strictly in memory
 * without persisting photos or EXIF/GPS tags to disk.
 */
actual class PlatformCameraController actual constructor() {
    actual val platformName: String = "iOS AVFoundation (In-Memory)"
    actual val stripsExifAndGpsInMemory: Boolean = true

    actual fun defaultIntrinsics(): CameraIntrinsicsSpec = SampleCapturedPhotos.defaultIntrinsics()

    actual fun capturePresetPhoto(presetId: String): CapturedTablePhoto =
        SampleCapturedPhotos.allPresets().firstOrNull { it.id == presetId }
            ?: SampleCapturedPhotos.angledNineFootNineBall()
}
