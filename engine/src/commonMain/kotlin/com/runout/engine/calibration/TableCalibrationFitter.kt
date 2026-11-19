package com.runout.engine.calibration

import com.runout.engine.model.ClothParams
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Lag drill trial measuring stopping distance of a rolling ball from [initialRollingSpeedMps].
 */
data class LagDrillTrial(
    val initialRollingSpeedMps: Double,
    val measuredStoppingDistanceMeters: Double,
)

/**
 * Centre-ball stun drill trial measuring sliding phase duration and distance before pure roll.
 */
data class StunDrillTrial(
    val initialCueSpeedMps: Double,
    val measuredSlideDurationSeconds: Double,
    val measuredSlideDistanceMeters: Double,
)

/**
 * Straight head-on cushion rebound speed trial measuring normal restitution.
 */
data class CushionSpeedDrillTrial(
    val incomingNormalSpeedMps: Double,
    val reboundNormalSpeedMps: Double,
)

/**
 * Bank shot with english measuring rebound angle response as a function of side spin.
 */
data class BankSpinDrillTrial(
    val incomingSpeedMps: Double,
    val incomingAngleRad: Double,
    val sideSpinRatio: Double,
    val measuredReboundAngleRad: Double,
)

/**
 * Half-ball / cut shot trial measuring object-ball throw angle against cut angle and speed.
 */
data class CutThrowDrillTrial(
    val cutAngleSignedRad: Double,
    val cueSpeedMps: Double,
    val measuredThrowAngleRad: Double,
)

/**
 * Complete fitted table physics parameters in SI/dimensionless units.
 */
data class CalibratedTableParams(
    val cloth: ClothParams,
    val ballBallFrictionMuB: Double = 0.055,
    val cushionSpinCouplingFactor: Double = 0.55,
)

/**
 * Held-out shot ground-truth sample used to quantify calibration generalization error.
 */
data class HeldOutShotSample(
    val initialRollingSpeedMps: Double,
    val trueStoppingDistanceMeters: Double,
    val incomingCushionSpeedMps: Double,
    val trueReboundSpeedMps: Double,
    val cutAngleRad: Double,
    val trueThrowAngleRad: Double,
)

/**
 * Held-out RMS prediction comparison between calibrated parameters and uncalibrated defaults.
 */
data class CalibrationValidationReport(
    val fittedParams: CalibratedTableParams,
    val calibratedStoppingRmsErrorMeters: Double,
    val defaultStoppingRmsErrorMeters: Double,
    val calibratedReboundSpeedRmsErrorMps: Double,
    val defaultReboundSpeedRmsErrorMps: Double,
    val calibratedThrowRmsErrorRad: Double,
    val defaultThrowRmsErrorRad: Double,
    val relativeStoppingErrorReductionFraction: Double,
)

/**
 * Outlier-trimmed, physically bounded parameter fitter for cloth rolling resistance,
 * sliding friction, cushion restitution, cushion spin coupling, and ball-ball throw friction.
 */
object TableCalibrationFitter {
    private const val DEFAULT_MU_R = 0.010
    private const val DEFAULT_MU_S = 0.20
    private const val DEFAULT_EC = 0.75
    private const val DEFAULT_CUSHION_SPIN = 0.55
    private const val DEFAULT_MU_B = 0.055

    private const val MIN_MU_R = 0.004
    private const val MAX_MU_R = 0.025
    private const val MIN_MU_S = 0.10
    private const val MAX_MU_S = 0.35
    private const val MIN_EC = 0.50
    private const val MAX_EC = 0.92
    private const val MIN_CUSHION_SPIN = 0.20
    private const val MAX_CUSHION_SPIN = 1.00
    private const val MIN_MU_B = 0.02
    private const val MAX_MU_B = 0.12

    private const val GOLDEN_RATIO_CONJUGATE = 0.6180339887498949

    /**
     * Fits rolling resistance `mu_r` from lag trials using outlier-trimmed least-squares
     * `sum(v0^2 * d) / (2 * g * sum(d^2))`, clamped to `0.004..0.025` (default `0.010`).
     */
    fun fitRollingResistanceMuR(trials: List<LagDrillTrial>): Double {
        val g = MotionPhysics.GRAVITY_M_S2
        val valid = trials.filter {
            it.initialRollingSpeedMps > 0.0 && it.measuredStoppingDistanceMeters > 1e-4
        }
        if (valid.isEmpty()) return DEFAULT_MU_R

        val implied = valid.map { trial ->
            (trial.initialRollingSpeedMps * trial.initialRollingSpeedMps) /
                (2.0 * g * trial.measuredStoppingDistanceMeters)
        }
        val keepIndices = inlierIndices(implied, minRelativeScale = 0.25, minAbsoluteFloor = 0.0015)

        var num = 0.0
        var den = 0.0
        for (idx in keepIndices) {
            val t = valid[idx]
            num += t.initialRollingSpeedMps * t.initialRollingSpeedMps * t.measuredStoppingDistanceMeters
            den += t.measuredStoppingDistanceMeters * t.measuredStoppingDistanceMeters
        }
        if (den <= 1e-12) return DEFAULT_MU_R
        return (num / (2.0 * g * den)).coerceIn(MIN_MU_R, MAX_MU_R)
    }

    /**
     * Fits sliding friction `mu_s` from stun trials by averaging time-based
     * `2*v0 / (7*g*t_slide)` and distance-based `12*v0^2 / (49*g*d_slide)` per trial,
     * trimming outliers, and clamping to `0.10..0.35` (default `0.20`).
     */
    fun fitSlidingFrictionMuS(trials: List<StunDrillTrial>): Double {
        val g = MotionPhysics.GRAVITY_M_S2
        val estimates = ArrayList<Double>(trials.size)
        for (trial in trials) {
            val v0 = trial.initialCueSpeedMps
            if (v0 <= 0.0) continue
            val hasTime = trial.measuredSlideDurationSeconds > 1e-4
            val hasDist = trial.measuredSlideDistanceMeters > 1e-4
            val est = when {
                hasTime && hasDist -> {
                    val muTime = (2.0 * v0) / (7.0 * g * trial.measuredSlideDurationSeconds)
                    val muDist = (12.0 * v0 * v0) / (49.0 * g * trial.measuredSlideDistanceMeters)
                    0.5 * (muTime + muDist)
                }
                hasTime -> (2.0 * v0) / (7.0 * g * trial.measuredSlideDurationSeconds)
                hasDist -> (12.0 * v0 * v0) / (49.0 * g * trial.measuredSlideDistanceMeters)
                else -> null
            }
            if (est != null && est.isFinite() && est > 0.0) {
                estimates += est
            }
        }
        if (estimates.isEmpty()) return DEFAULT_MU_S

        val keepIndices = inlierIndices(estimates, minRelativeScale = 0.25, minAbsoluteFloor = 0.025)
        var sum = 0.0
        for (idx in keepIndices) {
            sum += estimates[idx]
        }
        return (sum / keepIndices.size).coerceIn(MIN_MU_S, MAX_MU_S)
    }

    /**
     * Fits head-on cushion restitution `e_c = sum(vIn * vOut) / sum(vIn^2)` with outlier trimming,
     * clamped to `0.50..0.92` (default `0.75`).
     */
    fun fitCushionRestitutionEc(trials: List<CushionSpeedDrillTrial>): Double {
        val valid = trials.filter {
            it.incomingNormalSpeedMps > 1e-4 && it.reboundNormalSpeedMps > 0.0
        }
        if (valid.isEmpty()) return DEFAULT_EC

        val ratios = valid.map { it.reboundNormalSpeedMps / it.incomingNormalSpeedMps }
        val keepIndices = inlierIndices(ratios, minRelativeScale = 0.20, minAbsoluteFloor = 0.05)

        var num = 0.0
        var den = 0.0
        for (idx in keepIndices) {
            val t = valid[idx]
            num += t.incomingNormalSpeedMps * t.reboundNormalSpeedMps
            den += t.incomingNormalSpeedMps * t.incomingNormalSpeedMps
        }
        if (den <= 1e-12) return DEFAULT_EC
        return (num / den).coerceIn(MIN_EC, MAX_EC)
    }

    /**
     * Fits the cushion english coupling constant `k_spin` from bank trials with `|sideSpinRatio| > 0.05`
     * via `tan(thetaOut) = (0.82 * vt + k_spin * sideSpinRatio * vn) / (fittedEc * vn)`,
     * clamped to `0.20..1.00` (default `0.55`).
     */
    fun fitCushionSpinCoupling(
        trials: List<BankSpinDrillTrial>,
        fittedEc: Double,
    ): Double {
        if (fittedEc <= 1e-4) return DEFAULT_CUSHION_SPIN

        data class LinearTerm(val a: Double, val b: Double, val impliedK: Double)

        val terms = ArrayList<LinearTerm>()
        for (trial in trials) {
            if (trial.incomingSpeedMps <= 1e-4 || abs(trial.sideSpinRatio) <= 0.05) continue
            val cosIn = cos(trial.incomingAngleRad)
            val cosOut = cos(trial.measuredReboundAngleRad)
            if (cosIn <= 1e-4 || abs(cosOut) <= 1e-4) continue

            val vn = trial.incomingSpeedMps * cosIn
            val vt = trial.incomingSpeedMps * sin(trial.incomingAngleRad)
            val vnOut = fittedEc * vn
            val vtOutMeasured = vnOut * tan(trial.measuredReboundAngleRad)

            val a = trial.sideSpinRatio * vn
            val b = vtOutMeasured - 0.82 * vt
            if (abs(a) > 1e-6 && b.isFinite()) {
                terms += LinearTerm(a = a, b = b, impliedK = b / a)
            }
        }
        if (terms.isEmpty()) return DEFAULT_CUSHION_SPIN

        val keepIndices = inlierIndices(
            terms.map { it.impliedK },
            minRelativeScale = 0.30,
            minAbsoluteFloor = 0.10,
        )

        var num = 0.0
        var den = 0.0
        for (idx in keepIndices) {
            val term = terms[idx]
            num += term.a * term.b
            den += term.a * term.a
        }
        if (den <= 1e-12) return DEFAULT_CUSHION_SPIN
        return (num / den).coerceIn(MIN_CUSHION_SPIN, MAX_CUSHION_SPIN)
    }

    /**
     * Fits ball-ball friction `mu_b` in `0.02..0.12` (default `0.055`) by inverting
     * [SpinPhysics.computeThrowAnglesRadians] via outlier-trimmed golden-section search.
     */
    fun fitBallBallFrictionMuB(trials: List<CutThrowDrillTrial>): Double {
        val valid = trials.filter {
            abs(it.cutAngleSignedRad) > 1e-3 && it.cueSpeedMps > 0.0
        }
        if (valid.isEmpty()) return DEFAULT_MU_B

        val centerTip = TipOffset()
        val perTrialMu = valid.map { trial ->
            goldenSectionMinimize(0.005, 0.25) { mu ->
                val pred = SpinPhysics.computeThrowAnglesRadians(
                    cutAngleSignedRadians = trial.cutAngleSignedRad,
                    cueSpeedMps = trial.cueSpeedMps,
                    tip = centerTip,
                    ballBallFrictionMu = mu,
                ).first
                val err = pred - trial.measuredThrowAngleRad
                err * err
            }
        }
        val keepIndices = inlierIndices(perTrialMu, minRelativeScale = 0.35, minAbsoluteFloor = 0.012)

        return goldenSectionMinimize(MIN_MU_B, MAX_MU_B) { mu ->
            var sse = 0.0
            for (idx in keepIndices) {
                val trial = valid[idx]
                val pred = SpinPhysics.computeThrowAnglesRadians(
                    cutAngleSignedRadians = trial.cutAngleSignedRad,
                    cueSpeedMps = trial.cueSpeedMps,
                    tip = centerTip,
                    ballBallFrictionMu = mu,
                ).first
                val err = pred - trial.measuredThrowAngleRad
                sse += err * err
            }
            sse
        }.coerceIn(MIN_MU_B, MAX_MU_B)
    }

    /**
     * Runs all drill fitters together and returns the consolidated [CalibratedTableParams].
     */
    fun fitAllDrills(
        lagTrials: List<LagDrillTrial>,
        stunTrials: List<StunDrillTrial>,
        cushionTrials: List<CushionSpeedDrillTrial>,
        bankSpinTrials: List<BankSpinDrillTrial> = emptyList(),
        cutThrowTrials: List<CutThrowDrillTrial> = emptyList(),
    ): CalibratedTableParams {
        val muR = fitRollingResistanceMuR(lagTrials)
        val muS = fitSlidingFrictionMuS(stunTrials)
        val ec = fitCushionRestitutionEc(cushionTrials)
        val spinCoupling = fitCushionSpinCoupling(bankSpinTrials, ec)
        val muB = fitBallBallFrictionMuB(cutThrowTrials)
        return CalibratedTableParams(
            cloth = ClothParams(
                rollingResistanceMuR = muR,
                slidingFrictionMuS = muS,
                cushionRestitutionEc = ec,
            ),
            ballBallFrictionMuB = muB,
            cushionSpinCouplingFactor = spinCoupling,
        )
    }

    /**
     * Evaluates held-out stopping distance, cushion rebound speed, and cut-throw angle RMS errors
     * comparing [fittedParams] against [defaultParams].
     */
    fun evaluateHeldOutPredictionAccuracy(
        fittedParams: CalibratedTableParams,
        heldOutSamples: List<HeldOutShotSample>,
        defaultParams: CalibratedTableParams = CalibratedTableParams(ClothParams()),
    ): CalibrationValidationReport {
        if (heldOutSamples.isEmpty()) {
            return CalibrationValidationReport(
                fittedParams = fittedParams,
                calibratedStoppingRmsErrorMeters = 0.0,
                defaultStoppingRmsErrorMeters = 0.0,
                calibratedReboundSpeedRmsErrorMps = 0.0,
                defaultReboundSpeedRmsErrorMps = 0.0,
                calibratedThrowRmsErrorRad = 0.0,
                defaultThrowRmsErrorRad = 0.0,
                relativeStoppingErrorReductionFraction = 0.0,
            )
        }

        val calErrors = computeRmsErrors(fittedParams, heldOutSamples)
        val defErrors = computeRmsErrors(defaultParams, heldOutSamples)
        val reduction = ((defErrors.stoppingRms - calErrors.stoppingRms) /
            defErrors.stoppingRms.coerceAtLeast(1e-9)).coerceIn(-1.0, 1.0)

        return CalibrationValidationReport(
            fittedParams = fittedParams,
            calibratedStoppingRmsErrorMeters = calErrors.stoppingRms,
            defaultStoppingRmsErrorMeters = defErrors.stoppingRms,
            calibratedReboundSpeedRmsErrorMps = calErrors.reboundRms,
            defaultReboundSpeedRmsErrorMps = defErrors.reboundRms,
            calibratedThrowRmsErrorRad = calErrors.throwRms,
            defaultThrowRmsErrorRad = defErrors.throwRms,
            relativeStoppingErrorReductionFraction = reduction,
        )
    }

    private data class RmsTriplet(
        val stoppingRms: Double,
        val reboundRms: Double,
        val throwRms: Double,
    )

    private fun computeRmsErrors(
        params: CalibratedTableParams,
        samples: List<HeldOutShotSample>,
    ): RmsTriplet {
        val centerTip = TipOffset()
        var stopSq = 0.0
        var reboundSq = 0.0
        var throwSq = 0.0

        for (sample in samples) {
            val predStop = MotionPhysics.rollingStoppingDistanceMeters(
                rollingSpeedMps = sample.initialRollingSpeedMps,
                muR = params.cloth.rollingResistanceMuR,
            )
            val stopErr = predStop - sample.trueStoppingDistanceMeters
            stopSq += stopErr * stopErr

            val predRebound = sample.incomingCushionSpeedMps * params.cloth.cushionRestitutionEc
            val reboundErr = predRebound - sample.trueReboundSpeedMps
            reboundSq += reboundErr * reboundErr

            val predThrow = SpinPhysics.computeThrowAnglesRadians(
                cutAngleSignedRadians = sample.cutAngleRad,
                cueSpeedMps = sample.initialRollingSpeedMps,
                tip = centerTip,
                ballBallFrictionMu = params.ballBallFrictionMuB,
            ).first
            val throwErr = predThrow - sample.trueThrowAngleRad
            throwSq += throwErr * throwErr
        }

        val count = samples.size.toDouble()
        return RmsTriplet(
            stoppingRms = sqrt(stopSq / count),
            reboundRms = sqrt(reboundSq / count),
            throwRms = sqrt(throwSq / count),
        )
    }

    private fun inlierIndices(
        values: List<Double>,
        minRelativeScale: Double,
        minAbsoluteFloor: Double,
    ): List<Int> {
        if (values.size <= 2) return values.indices.toList()

        val sortedVals = values.sorted()
        val median = medianOfSorted(sortedVals)
        val absDevs = values.map { abs(it - median) }.sorted()
        val mad = medianOfSorted(absDevs)

        val fence = max(
            3.5 * 1.4826 * mad,
            max(minRelativeScale * abs(median), minAbsoluteFloor),
        )

        val kept = ArrayList<Int>(values.size)
        for (i in values.indices) {
            if (abs(values[i] - median) <= fence) {
                kept += i
            }
        }
        return if (kept.isEmpty()) values.indices.toList() else kept
    }

    private fun medianOfSorted(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
        }
    }

    private inline fun goldenSectionMinimize(
        minX: Double,
        maxX: Double,
        iterations: Int = 60,
        objective: (Double) -> Double,
    ): Double {
        var a = minX
        var b = maxX
        var c = b - GOLDEN_RATIO_CONJUGATE * (b - a)
        var d = a + GOLDEN_RATIO_CONJUGATE * (b - a)
        var fc = objective(c)
        var fd = objective(d)

        repeat(iterations) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - GOLDEN_RATIO_CONJUGATE * (b - a)
                fc = objective(c)
            } else {
                a = c
                c = d
                fc = fd
                d = a + GOLDEN_RATIO_CONJUGATE * (b - a)
                fd = objective(d)
            }
        }
        return 0.5 * (a + b)
    }
}
