package com.runout.app.tools

/**
 * Break-sound source for the break-speed meter. This version does not use the microphone: it returns the
 * bundled sample recordings of the sound envelope, and nothing is recorded.
 */
actual class PlatformBreakAudioController actual constructor() {
    actual val audioBackendName: String = "Sample data only: live microphone is not available in this version"
    actual val processesTransientPcmInMemoryOnly: Boolean = true

    actual fun defaultEnvelopeSampleRateHz(): Int = 2000

    actual fun captureBreakPreset(presetId: String): BreakAudioPreset {
        val presets = TutorialsAndDrillsCatalog.sampleBreakAudioPresets()
        return presets.firstOrNull { it.id == presetId } ?: presets.first()
    }
}
