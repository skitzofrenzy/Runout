package com.runout.app.calibration

import com.runout.engine.calibration.PairedTiltReading

/**
 * Tilt-reading source for the level check. This version does not read the device's motion sensors: it returns
 * the bundled sample sessions, so the readings never come from the phone.
 */
actual class PlatformTiltSensorController actual constructor() {
    actual val sensorBackendName: String = "Sample data only: live tilt sensor is not available in this version"
    actual val resolutionDegrees: Double = 0.08

    actual fun readPairedGridPreset(sessionId: String): List<PairedTiltReading> {
        val matched = SampleCalibrationSessions.allSessions().firstOrNull { it.id == sessionId }
            ?: SampleCalibrationSessions.fastTournamentNineFootSession()
        return matched.pairedTiltReadings
    }
}
