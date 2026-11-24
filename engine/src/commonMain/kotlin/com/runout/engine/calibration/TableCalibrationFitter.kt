package com.runout.engine.calibration

import com.runout.engine.math.Vec2
import com.runout.engine.model.ClothParams
import com.runout.engine.physics.BallMotion
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CushionContact
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

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
 * Head-on cushion trial: a ball rolling straight into a cushion, with its speed just before and just after the contact.
 */
data class CushionSpeedDrillTrial(
    val incomingNormalSpeedMps: Double,
    val reboundNormalSpeedMps: Double,
)

/**
 * Bank trial: a ball rolling into a cushion at [incomingSpeedMps] and [incomingAngleRad] from the normal (positive: moving
 * to the player's right while facing the cushion), struck with [sideSpinRatio] of english (tip side offset over the
 * radius, positive right, so positive is running english for a positive angle), and the measured angle from the normal
 * of the line it rolls along once the slide after the cushion is over. See
 * [TableCalibrationFitter.predictBankReboundAngleRad].
 */
data class BankSpinDrillTrial(
    val incomingSpeedMps: Double,
    val incomingAngleRad: Double,
    val sideSpinRatio: Double,
    val measuredReboundAngleRad: Double,
)

/**
 * Half-ball / cut shot trial measuring object-ball throw angle against cut angle and speed.
 *
 * The drill is a centre-ball, no-english shot. [cueSpeedMps] is the cue-ball speed *at contact* and
 * [impactSpinFactorK] its follow/draw spin factor `R * omega / v` there (`0.0`, a stun shot, is the drill as
 * described to the player). The sign convention for [cutAngleSignedRad] and [measuredThrowAngleRad] is the one
 * of [com.runout.engine.physics.ThrowAngles].
 */
data class CutThrowDrillTrial(
    val cutAngleSignedRad: Double,
    val cueSpeedMps: Double,
    val measuredThrowAngleRad: Double,
    val impactSpinFactorK: Double = 0.0,
)

/**
 * Complete fitted table physics parameters in SI/dimensionless units.
 *
 * [ballBallFrictionMuB] is the ball-ball friction at 1 m/s of sliding speed
 * ([SpinPhysics.BALL_BALL_FRICTION_REFERENCE_SPEED_MPS]); the throw law scales its whole speed-dependent
 * friction curve by `ballBallFrictionMuB / SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU`. The cushion restitution and
 * friction are in [cloth] ([ClothParams.cushionRestitutionEc], [ClothParams.cushionFrictionFc]).
 */
data class CalibratedTableParams(
    val cloth: ClothParams,
    val ballBallFrictionMuB: Double = SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU,
)

/**
 * Held-out shot ground-truth sample used to quantify calibration generalization error.
 * The throw is evaluated as a centre-ball stun contact at [initialRollingSpeedMps], like the cut-throw drill.
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
 * Outlier-trimmed, physically bounded parameter fitter for cloth rolling resistance, sliding friction, cushion
 * restitution and friction, and ball-ball throw friction.
 */
object TableCalibrationFitter {
    private const val DEFAULT_MU_R = 0.010
    private const val DEFAULT_MU_S = 0.20
    private const val DEFAULT_EC = 0.75
    private const val DEFAULT_FC = ClothParams.DEFAULT_CUSHION_FRICTION_FC
    private val DEFAULT_MU_B = SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU

    private const val MIN_MU_R = 0.004
    private const val MAX_MU_R = 0.025
    private const val MIN_MU_S = 0.10
    private const val MAX_MU_S = 0.35
    private const val MIN_EC = ClothParams.MIN_CUSHION_RESTITUTION_EC
    private const val MAX_EC = ClothParams.MAX_CUSHION_RESTITUTION_EC
    private const val MIN_FC = ClothParams.MIN_CUSHION_FRICTION_FC
    private const val MAX_FC = ClothParams.MAX_CUSHION_FRICTION_FC
    private const val MIN_MU_B = 0.02
    private const val MAX_MU_B = 0.12

    /** Rounds of alternating the cushion restitution and friction fits. */
    private const val CUSHION_FIT_ROUNDS = 3

    /** Search range for the per-trial friction used only to reject outlier trials. */
    private const val PER_TRIAL_MIN_MU_B = 0.005
    private const val PER_TRIAL_MAX_MU_B = 0.25
    private const val BISECTION_STEPS = 50
    private const val SCAN_INTERVALS = 100

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
     * Fits the cushion restitution `e_c` from head-on trials: a rolling ball driven straight into a cushion, its
     * speed measured just before and just after the contact. The ratio of the two is outlier-trimmed and
     * least-squares averaged (`sum(vIn * vOut) / sum(vIn^2)`), then turned into `e_c` by inverting the cushion model
     * ([predictHeadOnReboundRatio], which is speed-independent and rises monotonically with `e_c`) at the cushion
     * friction [cushionFrictionFc] and nose height [cushionNoseHeightRatio]. Clamped to `0.50..0.92` (default `0.75`).
     */
    fun fitCushionRestitutionEc(
        trials: List<CushionSpeedDrillTrial>,
        cushionFrictionFc: Double = ClothParams.DEFAULT_CUSHION_FRICTION_FC,
        cushionNoseHeightRatio: Double = ClothParams.DEFAULT_CUSHION_NOSE_HEIGHT_RATIO,
    ): Double {
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
        val measuredRatio = num / den
        val cloth = ClothParams(cushionFrictionFc = cushionFrictionFc, cushionNoseHeightRatio = cushionNoseHeightRatio)
        var lo = 0.0
        var hi = 1.0
        repeat(BISECTION_STEPS) {
            val mid = 0.5 * (lo + hi)
            if (predictHeadOnReboundRatio(cloth.copy(cushionRestitutionEc = mid)) < measuredRatio) lo = mid else hi = mid
        }
        return (0.5 * (lo + hi)).coerceIn(MIN_EC, MAX_EC)
    }

    /**
     * Fits the ball-cushion friction `f_c` from bank trials: a rolling ball with english sent into a cushion at an
     * angle, the angle of the line it rolls along afterwards measured (see [BankSpinDrillTrial]). Each trial's angle
     * is predicted with the cushion model and the slide on the cloth after it ([predictBankReboundAngleRad]) at the
     * fitted [fittedEc]; trials whose own best `f_c` is an outlier are dropped, and `f_c` minimises the squared angle
     * error over the rest (a coarse scan, then a golden-section search). Clamped to `0.05..0.40` (default `0.20`).
     */
    fun fitCushionFrictionFc(
        trials: List<BankSpinDrillTrial>,
        fittedEc: Double,
        cushionNoseHeightRatio: Double = ClothParams.DEFAULT_CUSHION_NOSE_HEIGHT_RATIO,
        slidingFrictionMuS: Double = DEFAULT_MU_S,
    ): Double {
        val valid = trials.filter { it.incomingSpeedMps > 1e-4 && cos(it.incomingAngleRad) > 1e-3 }
        if (valid.isEmpty()) return DEFAULT_FC
        val base = ClothParams(
            slidingFrictionMuS = slidingFrictionMuS,
            cushionRestitutionEc = fittedEc,
            cushionNoseHeightRatio = cushionNoseHeightRatio,
        )

        fun error(trial: BankSpinDrillTrial, fc: Double): Double {
            val predicted = predictBankReboundAngleRad(
                trial.incomingSpeedMps,
                trial.incomingAngleRad,
                trial.sideSpinRatio,
                base.copy(cushionFrictionFc = fc),
            )
            return if (predicted.isNaN()) 0.0 else predicted - trial.measuredReboundAngleRad
        }

        fun bestFor(indices: List<Int>): Double {
            val sse = { fc: Double ->
                var s = 0.0
                for (idx in indices) {
                    val err = error(valid[idx], fc)
                    s += err * err
                }
                s
            }
            var bestIndex = 0
            var bestValue = Double.POSITIVE_INFINITY
            val step = (MAX_FC - MIN_FC) / SCAN_INTERVALS
            for (i in 0..SCAN_INTERVALS) {
                val value = sse(MIN_FC + i * step)
                if (value < bestValue - 1e-18) {
                    bestValue = value
                    bestIndex = i
                }
            }
            val lo = (MIN_FC + (bestIndex - 1) * step).coerceAtLeast(MIN_FC)
            val hi = (MIN_FC + (bestIndex + 1) * step).coerceAtMost(MAX_FC)
            return goldenSectionMinimize(lo, hi, objective = sse)
        }

        val perTrial = valid.indices.map { bestFor(listOf(it)) }
        val keepIndices = inlierIndices(perTrial, minRelativeScale = 0.35, minAbsoluteFloor = 0.03)
        return bestFor(keepIndices).coerceIn(MIN_FC, MAX_FC)
    }

    /**
     * Speed just after a head-on contact over speed just before, for a ball that arrives rolling, under the cushion
     * model of [CushionContact] with [cloth]'s `e_c`, `f_c` and nose height. Independent of speed.
     */
    fun predictHeadOnReboundRatio(cloth: ClothParams): Double {
        val inward = Vec2(0.0, 1.0)
        val arriving = BallState.rolling(Vec2.ZERO, Vec2(0.0, -1.0))
        return CushionContact.rebound(arriving, inward, cloth).velocity.length()
    }

    /**
     * Angle from the cushion normal of the line a ball rolls along after a cushion, the bank drill's measurement. The
     * ball arrives rolling at [incomingSpeedMps], [incomingAngleRad] from the normal (positive: moving to the player's
     * right while facing the cushion), with the side spin a level tip [sideSpinRatio] of the radius off centre gives a
     * ball at that speed (`R * omegaZ = -5/2 * sideSpinRatio * v`, positive to the right, so positive is running english
     * for a positive angle). The [CushionContact] impulse sends it off, and the slide on the cloth that follows
     * ([BallMotion]) sets the line it finally rolls along; the result is positive on the side the ball was moving to.
     * `NaN` if the ball comes to rest at the cushion.
     */
    fun predictBankReboundAngleRad(
        incomingSpeedMps: Double,
        incomingAngleRad: Double,
        sideSpinRatio: Double,
        cloth: ClothParams,
    ): Double {
        val inward = Vec2(0.0, 1.0)
        val along = Vec2(1.0, 0.0)
        val v = incomingSpeedMps
        val velocity = along * (v * sin(incomingAngleRad)) - inward * (v * cos(incomingAngleRad))
        val arriving = BallState.rolling(Vec2.ZERO, velocity, -2.5 * sideSpinRatio * v)
        val leaving = CushionContact.rebound(arriving, inward, cloth)
        val motion = BallMotion(cloth)
        val rolling = if (motion.timeToRoll(leaving) > 0.0) motion.endOfPhase(leaving) else leaving
        if (rolling.velocity.length() <= 1e-6) return Double.NaN
        return atan2(rolling.velocity.dot(along), rolling.velocity.dot(inward))
    }

    /**
     * Fits the ball-ball friction `mu_b` (friction at 1 m/s of sliding speed) in `0.02..0.12`
     * (default [SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU], about `0.046`) from the half-ball throw drill.
     *
     * `mu_b` scales the whole speed-dependent friction curve of [SpinPhysics.computeThrowAnglesRadians], so each
     * trial is one equation in one unknown. The predicted throw rises monotonically with `mu_b` until the no-slip
     * limit caps it, so each trial is inverted by bisection (a trial at the cap only bounds `mu_b` from below),
     * trials whose implied `mu_b` is an outlier are dropped, and the rest are fitted by least squares on the
     * throw angle: a coarse scan locates the basin (the capped region is flat, which a plain golden-section search
     * handles badly), then a golden-section search refines it.
     */
    fun fitBallBallFrictionMuB(trials: List<CutThrowDrillTrial>): Double {
        val valid = trials.filter {
            abs(it.cutAngleSignedRad) > 1e-3 && it.cueSpeedMps > 0.0
        }
        if (valid.isEmpty()) return DEFAULT_MU_B

        fun predictedThrow(trial: CutThrowDrillTrial, mu: Double): Double =
            SpinPhysics.computeThrowAnglesRadians(
                cutAngleSignedRadians = trial.cutAngleSignedRad,
                impactSpeedMps = trial.cueSpeedMps,
                impactSpinFactorK = trial.impactSpinFactorK,
                ballBallFrictionMu = mu,
            ).totalRadians

        val perTrialMu = valid.map { trial ->
            // Compare magnitudes with the sign of the cut: a measured throw on the wrong side of the line of
            // centres is noise, and implies no friction.
            val target = trial.measuredThrowAngleRad * sign(trial.cutAngleSignedRad)
            var lo = PER_TRIAL_MIN_MU_B
            var hi = PER_TRIAL_MAX_MU_B
            repeat(BISECTION_STEPS) {
                val mid = 0.5 * (lo + hi)
                if (abs(predictedThrow(trial, mid)) < target) lo = mid else hi = mid
            }
            0.5 * (lo + hi)
        }
        val keepIndices = inlierIndices(perTrialMu, minRelativeScale = 0.35, minAbsoluteFloor = 0.012)

        val sumOfSquares = { mu: Double ->
            var sse = 0.0
            for (idx in keepIndices) {
                val trial = valid[idx]
                val err = predictedThrow(trial, mu) - trial.measuredThrowAngleRad
                sse += err * err
            }
            sse
        }

        var bestIndex = 0
        var bestValue = Double.POSITIVE_INFINITY
        val step = (MAX_MU_B - MIN_MU_B) / SCAN_INTERVALS
        for (i in 0..SCAN_INTERVALS) {
            val value = sumOfSquares(MIN_MU_B + i * step)
            if (value < bestValue - 1e-18) {
                bestValue = value
                bestIndex = i
            }
        }
        val bracketLo = (MIN_MU_B + (bestIndex - 1) * step).coerceAtLeast(MIN_MU_B)
        val bracketHi = (MIN_MU_B + (bestIndex + 1) * step).coerceAtMost(MAX_MU_B)
        return goldenSectionMinimize(bracketLo, bracketHi, objective = sumOfSquares)
            .coerceIn(MIN_MU_B, MAX_MU_B)
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
        // The head-on rebound depends a little on f_c and the bank angles on e_c: alternate the two fits.
        var fc = DEFAULT_FC
        var ec = fitCushionRestitutionEc(cushionTrials, fc)
        if (bankSpinTrials.isNotEmpty()) {
            repeat(CUSHION_FIT_ROUNDS) {
                fc = fitCushionFrictionFc(bankSpinTrials, ec, slidingFrictionMuS = muS)
                ec = fitCushionRestitutionEc(cushionTrials, fc)
            }
        }
        val muB = fitBallBallFrictionMuB(cutThrowTrials)
        return CalibratedTableParams(
            cloth = ClothParams(
                rollingResistanceMuR = muR,
                slidingFrictionMuS = muS,
                cushionRestitutionEc = ec,
                cushionFrictionFc = fc,
            ),
            ballBallFrictionMuB = muB,
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

            val predRebound = sample.incomingCushionSpeedMps * predictHeadOnReboundRatio(params.cloth)
            val reboundErr = predRebound - sample.trueReboundSpeedMps
            reboundSq += reboundErr * reboundErr

            val predThrow = SpinPhysics.computeThrowAnglesRadians(
                cutAngleSignedRadians = sample.cutAngleRad,
                impactSpeedMps = sample.initialRollingSpeedMps,
                ballBallFrictionMu = params.ballBallFrictionMuB,
            ).totalRadians
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
