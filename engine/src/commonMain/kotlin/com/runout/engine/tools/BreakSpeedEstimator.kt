package com.runout.engine.tools

import com.runout.engine.physics.MotionPhysics
import kotlin.math.max

/**
 * Detected cue-tip strike and rack-apex impact timestamps from an acoustic amplitude envelope.
 */
data class BreakAudioEnvelopeResult(
    val cueStrikeTimestampSeconds: Double,
    val rackImpactTimestampSeconds: Double,
    val rawDeltaTimeSeconds: Double,
    val peakCueStrikeAmplitude: Double,
    val peakRackImpactAmplitude: Double,
)

/**
 * Acoustic delay-corrected and sliding-friction-corrected break speed estimate in SI units (metres/second).
 */
data class BreakSpeedEstimate(
    val launchSpeedMps: Double,
    val impactSpeedMps: Double,
    val averageFlightSpeedMps: Double,
    val correctedFlightTimeSeconds: Double,
    val acousticDelayCorrectionSeconds: Double,
    val breakDistanceMeters: Double,
)

/**
 * Estimates pool break launch and rack-impact speeds from transient microphone envelopes,
 * accounting for sound propagation delay and cue-ball sliding friction across the cloth.
 */
object BreakSpeedEstimator {
    const val SPEED_OF_SOUND_MPS: Double = 343.0
    const val NINE_FOOT_DEFAULT_BREAK_DISTANCE_METERS: Double = 1.27

    private const val MIN_ABSOLUTE_THRESHOLD: Double = 0.15
    private const val RELATIVE_MAX_THRESHOLD_FRACTION: Double = 0.25
    private const val MIN_FLIGHT_TIME_SECONDS: Double = 0.025
    private const val MIN_IMPACT_SPEED_MPS: Double = 0.1
    private const val MAX_COALESCE_WINDOW_SECONDS: Double = 0.005

    private data class TransientPeak(
        val index: Int,
        val amplitude: Double,
    )

    /**
     * Scans [envelopeAmplitudes] sampled at [envelopeSampleRateHz] for the first prominent cue-strike
     * transient peak above `max(0.15, 0.25 * maxAmp)` and the strongest subsequent rack-impact peak
     * separated by `[minSeparationSeconds, maxSeparationSeconds]`. Returns `null` if no valid pair exists.
     */
    fun detectBreakImpactsFromEnvelope(
        envelopeAmplitudes: DoubleArray,
        envelopeSampleRateHz: Int,
        minSeparationSeconds: Double = 0.035,
        maxSeparationSeconds: Double = 0.45,
    ): BreakAudioEnvelopeResult? {
        require(envelopeSampleRateHz > 0) { "envelopeSampleRateHz must be positive." }
        require(minSeparationSeconds >= 0.0) { "minSeparationSeconds must be non-negative." }
        require(maxSeparationSeconds >= minSeparationSeconds) {
            "maxSeparationSeconds must be >= minSeparationSeconds."
        }
        if (envelopeAmplitudes.size < 2) return null

        var maxAmp = 0.0
        for (amp in envelopeAmplitudes) {
            if (amp > maxAmp) maxAmp = amp
        }
        val threshold = max(MIN_ABSOLUTE_THRESHOLD, RELATIVE_MAX_THRESHOLD_FRACTION * maxAmp)
        if (maxAmp < threshold) return null

        val rawPeaks = extractLocalPeaks(envelopeAmplitudes, threshold)
        if (rawPeaks.size < 2) return null

        val coalesceSamples = (MAX_COALESCE_WINDOW_SECONDS * envelopeSampleRateHz).toInt()
            .coerceAtMost((minSeparationSeconds * envelopeSampleRateHz * 0.25).toInt())
        val peaks = coalesceClosePeaks(rawPeaks, coalesceSamples)
        if (peaks.size < 2) return null

        for (i in 0 until peaks.lastIndex) {
            val cuePeak = peaks[i]
            val tCue = cuePeak.index.toDouble() / envelopeSampleRateHz.toDouble()
            var bestRackPeak: TransientPeak? = null
            for (j in (i + 1) until peaks.size) {
                val candidate = peaks[j]
                val tRack = candidate.index.toDouble() / envelopeSampleRateHz.toDouble()
                val dt = tRack - tCue
                if (dt < minSeparationSeconds - 1e-9) continue
                if (dt > maxSeparationSeconds + 1e-9) break
                val currentBest = bestRackPeak
                if (currentBest == null || candidate.amplitude > currentBest.amplitude) {
                    bestRackPeak = candidate
                }
            }
            if (bestRackPeak != null) {
                val tRack = bestRackPeak.index.toDouble() / envelopeSampleRateHz.toDouble()
                return BreakAudioEnvelopeResult(
                    cueStrikeTimestampSeconds = tCue,
                    rackImpactTimestampSeconds = tRack,
                    rawDeltaTimeSeconds = tRack - tCue,
                    peakCueStrikeAmplitude = cuePeak.amplitude,
                    peakRackImpactAmplitude = bestRackPeak.amplitude,
                )
            }
        }
        return null
    }

    /**
     * Converts raw acoustic time delta [rawDeltaTimeSeconds] over [breakDistanceMeters] into true
     * launch and impact cue-ball speeds by removing microphone travel-time differential and adding
     * sliding-cloth deceleration loss (`0.5 * slidingFrictionMuS * g * flightTime`).
     */
    fun estimateBreakSpeed(
        rawDeltaTimeSeconds: Double,
        breakDistanceMeters: Double = NINE_FOOT_DEFAULT_BREAK_DISTANCE_METERS,
        micToCueBallDistanceMeters: Double = 0.60,
        micToRackDistanceMeters: Double = 1.65,
        slidingFrictionMuS: Double = 0.20,
    ): BreakSpeedEstimate {
        require(rawDeltaTimeSeconds > 0.0) { "rawDeltaTimeSeconds must be positive." }
        require(breakDistanceMeters > 0.0) { "breakDistanceMeters must be positive." }
        require(micToCueBallDistanceMeters >= 0.0) { "micToCueBallDistanceMeters must be non-negative." }
        require(micToRackDistanceMeters >= 0.0) { "micToRackDistanceMeters must be non-negative." }
        require(slidingFrictionMuS >= 0.0) { "slidingFrictionMuS must be non-negative." }

        val acousticDelay = (micToRackDistanceMeters - micToCueBallDistanceMeters) / SPEED_OF_SOUND_MPS
        val flightTime = (rawDeltaTimeSeconds - acousticDelay).coerceAtLeast(MIN_FLIGHT_TIME_SECONDS)
        val avgSpeed = breakDistanceMeters / flightTime
        val decelLoss = 0.5 * slidingFrictionMuS * MotionPhysics.GRAVITY_M_S2 * flightTime
        val launchSpeed = avgSpeed + decelLoss
        val impactSpeed = (avgSpeed - decelLoss).coerceAtLeast(MIN_IMPACT_SPEED_MPS)

        return BreakSpeedEstimate(
            launchSpeedMps = launchSpeed,
            impactSpeedMps = impactSpeed,
            averageFlightSpeedMps = avgSpeed,
            correctedFlightTimeSeconds = flightTime,
            acousticDelayCorrectionSeconds = acousticDelay,
            breakDistanceMeters = breakDistanceMeters,
        )
    }

    private fun extractLocalPeaks(
        amplitudes: DoubleArray,
        threshold: Double,
    ): List<TransientPeak> {
        val peaks = mutableListOf<TransientPeak>()
        var i = 0
        val last = amplitudes.lastIndex
        while (i <= last) {
            val amp = amplitudes[i]
            if (amp < threshold) {
                i++
                continue
            }
            var plateauEnd = i
            while (plateauEnd < last && amplitudes[plateauEnd + 1] == amp) {
                plateauEnd++
            }
            val prev = if (i > 0) amplitudes[i - 1] else Double.NEGATIVE_INFINITY
            val next = if (plateauEnd < last) amplitudes[plateauEnd + 1] else Double.NEGATIVE_INFINITY
            if (amp > prev && amp > next) {
                peaks += TransientPeak(index = (i + plateauEnd) / 2, amplitude = amp)
            }
            i = plateauEnd + 1
        }
        return peaks
    }

    private fun coalesceClosePeaks(
        peaks: List<TransientPeak>,
        coalesceSamples: Int,
    ): List<TransientPeak> {
        if (coalesceSamples <= 0 || peaks.size <= 1) return peaks
        val merged = mutableListOf<TransientPeak>()
        for (peak in peaks) {
            val prev = merged.lastOrNull()
            if (prev != null && peak.index - prev.index <= coalesceSamples) {
                if (peak.amplitude > prev.amplitude) {
                    merged[merged.lastIndex] = peak
                }
            } else {
                merged += peak
            }
        }
        return merged
    }
}
