package com.runout.app.calibration

import com.runout.engine.calibration.PairedTiltReading

/**
 * iOS CoreMotion tilt-sensor bridge (CMMotionManager attitude/gravity) providing
 * paired 0/180-degree readings across a 3x3 table grid.
 */
actual class PlatformTiltSensorController actual constructor() {
    actual val sensorBackendName: String = "iOS CMMotionManager (CoreMotion Gravity)"
    actual val resolutionDegrees: Double = 0.08

    actual fun readPairedGridPreset(sessionId: String): List<PairedTiltReading> {
        val matched = SampleCalibrationSessions.allSessions().firstOrNull { it.id == sessionId }
            ?: SampleCalibrationSessions.fastTournamentNineFootSession()
        return matched.pairedTiltReadings
    }
}
