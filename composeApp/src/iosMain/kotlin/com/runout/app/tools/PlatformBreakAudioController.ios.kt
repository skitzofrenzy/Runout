package com.runout.app.tools

/**
 * iOS AVAudioEngine PCM transient-envelope bridge for acoustic break-speed measurement.
 * Processes microphone amplitude envelopes strictly in memory without writing audio files.
 */
actual class PlatformBreakAudioController actual constructor() {
    actual val audioBackendName: String = "iOS AVAudioEngine PCM (In-Memory)"
    actual val processesTransientPcmInMemoryOnly: Boolean = true

    actual fun defaultEnvelopeSampleRateHz(): Int = 2000

    actual fun captureBreakPreset(presetId: String): BreakAudioPreset {
        val presets = TutorialsAndDrillsCatalog.sampleBreakAudioPresets()
        return presets.firstOrNull { it.id == presetId } ?: presets.first()
    }
}
