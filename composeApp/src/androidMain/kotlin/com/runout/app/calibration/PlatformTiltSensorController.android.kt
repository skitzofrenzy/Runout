package com.runout.app.calibration

import com.runout.engine.calibration.PairedTiltReading

/**
 * Android MEMS tilt-sensor bridge (SensorManager TYPE_GRAVITY / ACCELEROMETER) providing
 * paired 0/180-degree readings across a 3x3 table grid.
 */
actual class PlatformTiltSensorController actual constructor() {
    actual val sensorBackendName: String = "Android SensorManager (TYPE_GRAVITY)"
    actual val resolutionDegrees: Double = 0.08

    actual fun readPairedGridPreset(sessionId: String): List<PairedTiltReading> {
        val matched = SampleCalibrationSessions.allSessions().firstOrNull { it.id == sessionId }
            ?: SampleCalibrationSessions.fastTournamentNineFootSession()
        return matched.pairedTiltReadings
    }
}
