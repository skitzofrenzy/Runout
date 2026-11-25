package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallTrajectory
import com.runout.engine.physics.GhostBallAim
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.PostShotTableSimulation
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Deterministic uncertainty evaluation for a candidate shot under a [PlayerProfile].
 *
 * [landingCloudScratched] says, point by point, whether the cue ball of that [landingCloud] point drops into a pocket
 * (empty when the cloud was not built). [targetBallPotted] is true when the nominal stroke reaches the target and pots
 * it in the intended pocket; a target that drops in another pocket does not count.
 */
data class ShotUncertaintyResult(
    val makeProbability: Double,
    val scratchProbability: Double,
    val nominalTrajectory: CueBallTrajectory,
    val landingCloud: List<Vec2>,
    val meanLandingPosition: Vec2,
    val landingSpreadRadiusMeters: Double,
    val postShotSimulation: PostShotTableSimulation? = null,
    val nominalLandingPosition: Vec2 = meanLandingPosition,
    val targetBallPotted: Boolean = true,
    val landingCloudScratched: List<Boolean> = emptyList(),
)

/**
 * The player's execution error and what it does to a shot.
 *
 * **Make probability.** The player's aim error is one number, [PlayerProfile.aimErrorStdRad]: the standard deviation
 * of the angle between the cue line the player meant and the one he played, normally distributed. Each cue line meets
 * the object ball at its own contact point, and the line of centres at contact is the direction the object ball leaves
 * in ([objectBallDirectionForCueLine]). To first order a cue-line error `delta` turns that direction by
 * `-delta * d / (2R cos(cut))` from its intended direction, `d` being the distance from the cue ball to the ghost ball
 * (measured from the played cue line instead, the turn is `d / (2R cos(cut)) + 1` times `delta`; written with the
 * distance `D` from the cue ball to the object ball, a straight shot gives `D / 2R - 1`). The ball drops when that
 * direction lies inside the pocket's [PocketGeometry.entryWindow] at the jaw tolerance of its arrival speed and neither
 * ball touches another ball on the way. Each edge of the window, and of each shadow another ball casts on it, is mapped
 * back exactly to the cue-line angle that sends the ball along it, so the make probability is the normal probability
 * mass of the cue-line angles that remain ([directionalMakeProbability]); the window need not be centred on the aimed
 * line. Speed error changes whether the ball reaches the pocket and how fast it arrives (and so the jaw tolerance); it
 * is integrated with a three-point Gauss-Hermite rule over the speed fraction.
 *
 * Side spin adds error: the player has to allow for squirt, swerve and spin-induced throw, and misjudges that
 * allowance by [SIDE_SPIN_JUDGEMENT_ERROR_FRACTION] of its size (one standard deviation), added in quadrature to the
 * aim error ([cueLineErrorStdRad]).
 *
 * **Landing cloud.** The cue ball's resting place, scratch chance and spread come from a deterministic 9-point
 * sigma-point stencil over (cue-line angle, speed fraction, side tip, vertical tip); each point is simulated with the
 * full table simulation from its own contact geometry.
 */
object ExecutionErrorModel {
    private const val MAX_PERTURBED_CUT_RAD: Double = 1.45

    /** Weights aligned 1:1 with the 9-point sigma ensemble in [ShotUncertaintyResult.landingCloud]. */
    val SIGMA_WEIGHTS: List<Double> = listOf(0.28, 0.12, 0.12, 0.12, 0.12, 0.06, 0.06, 0.06, 0.06)

    /**
     * Standard deviation of the player's misjudgement of the side-spin allowance (squirt, swerve and spin throw) as a
     * fraction of that allowance. A modelling value: no measurement of it is known.
     */
    const val SIDE_SPIN_JUDGEMENT_ERROR_FRACTION: Double = 0.25

    /** Largest cut, in radians, at which a cue line still counts as hitting the object ball. */
    private const val GRAZING_CUT_RAD: Double = 89.5 * PI / 180.0

    /** Gauss-Hermite nodes (in standard deviations) and weights for the speed-error integral. */
    private val SPEED_NODES: DoubleArray = doubleArrayOf(0.0, sqrt(3.0), -sqrt(3.0))
    private val SPEED_WEIGHTS: DoubleArray = doubleArrayOf(2.0 / 3.0, 1.0 / 6.0, 1.0 / 6.0)

    private data class SigmaOffset(
        val dAimRad: Double,
        val dSpeedFrac: Double,
        val dSideRatio: Double,
        val dVertRatio: Double,
        val weight: Double,
    )

    /**
     * Half-angle, in radians, of the directions from [objectBallPos] that carry the object ball into [pocket] when it
     * arrives at [arrivalSpeedMps]: the [PocketGeometry.entryWindow] at the [PocketGeometry.jawTolerance] for that
     * speed. It narrows with distance, with a shallower approach to the mouth and with speed, and is (nearly) zero
     * when the ball cannot pass between the points at all. A readout of the window's width for display and tests; the
     * make probability integrates the window itself ([directionalMakeProbability]) and does not use it.
     */
    fun pocketAcceptanceHalfAngleRadians(
        objectBallPos: Vec2,
        pocket: PocketSpec,
        objectBallRadiusMeters: Double,
        arrivalSpeedMps: Double,
    ): Double {
        val window = PocketGeometry.entryWindow(
            objectBallPos,
            pocket,
            objectBallRadiusMeters,
            PocketGeometry.jawTolerance(pocket, arrivalSpeedMps),
        )
        return window.halfAngleRadians.coerceAtLeast(MIN_HALF_ANGLE_RAD)
    }

    /** Smallest half-angle reported for a closed window, rad. */
    private const val MIN_HALF_ANGLE_RAD: Double = 1e-6

    /**
     * Standard deviation of the speed the player actually plays, as a fraction of the intended speed. Tied to the aim
     * error (a steadier stroke is steadier in both): about 6 % for an advanced, 7 % for an intermediate and 10 % for a
     * beginner profile. Like the aim error, these are estimates, not measurements.
     */
    fun speedErrorFraction(profile: PlayerProfile): Double =
        (0.045 + 9.2 * profile.aimErrorStdRad).coerceIn(0.04, 0.18)

    /**
     * Standard deviation, in radians, of the cue line for a stroke with [tip] at [cueSpeedMps] along [aim]: the
     * player's [PlayerProfile.aimErrorStdRad] and, with side spin, [SIDE_SPIN_JUDGEMENT_ERROR_FRACTION] of the
     * allowance he has to make for it (squirt and swerve, which turn the cue ball's path, and the spin part of the
     * throw, which turns the object ball's and is converted to the cue-line angle that has the same effect).
     */
    fun cueLineErrorStdRad(
        profile: PlayerProfile,
        aim: AimGeometry,
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
    ): Double {
        val base = profile.aimErrorStdRad.coerceAtLeast(MIN_AIM_ERROR_STD_RAD)
        if (abs(tip.clampedSideRatio) < 1e-9) return base
        val squirt = SpinPhysics.computeSquirtAngleRadians(tip, profile.shaftType)
        val swerve = SpinPhysics.computeSwerveAngleRadians(tip, cueSpeedMps, aim.cueToGhostDistanceMeters)
        val pre = SpinPhysics.computePreImpactCueState(cueSpeedMps, tip, aim.cueToGhostDistanceMeters, table)
        val sideSpin = SpinPhysics.sideSpinSurfaceSpeedAtImpactMps(tip, cueSpeedMps, aim.cueToGhostDistanceMeters, table)
        val spinThrow = SpinPhysics.computeThrowAnglesRadians(
            aim.cutAngleRadians, pre.impactSpeedMps, pre.impactSpinFactorK, sideSpin, table.ballContact.frictionMu,
        ).spinRadians
        val contact = aim.objectBallPos.distanceTo(aim.ghostBallCenter)
        val throwAsCueLine = abs(spinThrow) * contact * cos(aim.cutAngleRadians) /
            aim.cueToGhostDistanceMeters.coerceAtLeast(contact)
        val allowance = abs(squirt) + abs(swerve) + throwAsCueLine
        val judgement = SIDE_SPIN_JUDGEMENT_ERROR_FRACTION * allowance
        return sqrt(base * base + judgement * judgement)
    }

    /** Smallest aim error used, so that a zero-error profile still gives finite numbers, rad. */
    private const val MIN_AIM_ERROR_STD_RAD: Double = 1e-4

    /**
     * Direction the object ball at [objectBallPos] leaves in when the cue ball, starting at [cueBallPos], travels along
     * [cueLine] and meets it with centres [contactDistance] apart, or `null` if that line misses the object ball (a cut
     * beyond [GRAZING_CUT_RAD]). Also returns the cue ball's centre at contact.
     */
    internal fun objectBallDirectionForCueLine(
        cueBallPos: Vec2,
        objectBallPos: Vec2,
        cueLine: Vec2,
        contactDistance: Double,
    ): Pair<Vec2, Vec2>? {
        val u = cueLine.normalized()
        val toObject = objectBallPos - cueBallPos
        val along = toObject.dot(u)
        val lateralSq = toObject.lengthSquared() - along * along
        val maxLateral = contactDistance * kotlin.math.sin(GRAZING_CUT_RAD)
        if (along <= 0.0 || lateralSq > maxLateral * maxLateral) return null
        val t = along - sqrt((contactDistance * contactDistance - lateralSq).coerceAtLeast(0.0))
        val contactCentre = cueBallPos + u * t
        return (objectBallPos - contactCentre).normalized() to contactCentre
    }

    /**
     * Cue-line angle, relative to [aim]'s aim direction, that sends the object ball along [direction]; when no cue line
     * does (the cut would pass [GRAZING_CUT_RAD]) the angle of the grazing line on that side, beyond which the cue ball
     * misses the object ball.
     */
    private fun cueLineOffsetFor(aim: AimGeometry, direction: Vec2, contactDistance: Double): Double {
        val ghost = aim.objectBallPos - direction * contactDistance
        val toGhost = ghost - aim.cueBallPos
        val cut = toGhost.normalized().angleTo(direction)
        if (abs(cut) <= GRAZING_CUT_RAD && toGhost.dot(aim.objectBallPos - aim.cueBallPos) > 0.0) {
            return aim.aimDirection.angleTo(toGhost.normalized())
        }
        // The cut falls as the cue line turns counter-clockwise: a cut toward +90° lies at the clockwise graze.
        val toObject = aim.objectBallPos - aim.cueBallPos
        val centre = aim.aimDirection.angleTo(toObject.normalized())
        val half = asin((contactDistance * kotlin.math.sin(GRAZING_CUT_RAD) / toObject.length()).coerceIn(0.0, 1.0))
        return if (cut > 0.0) centre - half else centre + half
    }

    /**
     * Probability that the object ball of [aim] drops into [pocket] when the cue line is off by a normal error with
     * standard deviation [cueLineErrorStdRad]: the probability mass of the cue-line angles whose object-ball direction
     * lies inside [pocket]'s entry window at [jawTolerance] and whose two paths are clear of [otherBalls].
     *
     * 1. **Object ball.** The window is the arc of directions from the object ball between the window's two edges. A
     *    ball of radius `rO` at distance `L` from the object ball's centre removes the directions along which the object
     *    ball would touch it before its centre reaches the pocket's capture line ([PocketGeometry.captureLine]): the
     *    cone of half-angle `asin((R + rO) / L)` around the direction to it, cut back where the contact distance
     *    (`along - sqrt((R + rO)^2 - lateral^2)`) is no shorter than the distance to the capture line. What is left is a
     *    union of arcs.
     * 2. **Mapping.** The edges of each arc are mapped exactly through the ghost-ball geometry to the cue-line angles
     *    that send the object ball along them (an edge beyond a grazing hit maps to the graze). The map is monotonic,
     *    so each arc becomes an interval of cue-line angles.
     * 3. **Cue ball.** The same construction from the cue ball removes the cue-line angles along which the cue ball
     *    touches another ball before it reaches the object ball (the bound is the distance along that line to the
     *    contact with the object ball).
     * 4. The result is the normal probability mass of the intervals that remain.
     *
     * The edges of a cone that is only partly cut back are found by sampling it at [SHADOW_SAMPLES] points and bisecting
     * each change. Not covered: the cue ball after contact (it can still kiss a ball and change nothing here), balls
     * that move during the shot, and the cushions. [otherBalls] may contain the cue ball and the object ball; a cue ball
     * ([Ball.isCueBall]) and balls centred on [AimGeometry.cueBallPos] or [AimGeometry.objectBallPos] are ignored. With
     * no other ball near either path the result is the mass between the two window edges. `0` when the window is
     * closed.
     */
    fun directionalMakeProbability(
        aim: AimGeometry,
        pocket: PocketSpec,
        objectBallRadiusMeters: Double,
        cueBallRadiusMeters: Double,
        jawTolerance: Double,
        cueLineErrorStdRad: Double,
        otherBalls: List<Ball> = emptyList(),
    ): Double {
        if (aim.cueToGhostDistanceMeters <= 1e-6) return 0.0
        val window = PocketGeometry.entryWindow(aim.objectBallPos, pocket, objectBallRadiusMeters, jawTolerance)
        if (!window.isOpen) return 0.0
        val contact = cueBallRadiusMeters + objectBallRadiusMeters
        val sigma = cueLineErrorStdRad.coerceAtLeast(MIN_AIM_ERROR_STD_RAD)
        val obstacles = otherBalls.filter {
            !it.isCueBall &&
                it.position.distanceSquaredTo(aim.objectBallPos) > SAME_BALL_DISTANCE_SQ &&
                it.position.distanceSquaredTo(aim.cueBallPos) > SAME_BALL_DISTANCE_SQ
        }
        if (obstacles.isEmpty()) {
            val a = cueLineOffsetFor(aim, window.limitA, contact)
            val b = cueLineOffsetFor(aim, window.limitB, contact)
            return abs(normalCdf(b / sigma) - normalCdf(a / sigma)).coerceIn(0.0, 1.0)
        }

        // 1. Object-ball directions, as angles from limitA turning toward limitB.
        val origin = aim.objectBallPos
        val turn = if (window.limitA.cross(window.limitB) >= 0.0) 1.0 else -1.0
        val width = abs(window.limitA.angleTo(window.limitB))
        val direction = { t: Double -> window.limitA.rotate(turn * t) }
        val toCaptureLine = { u: Vec2 ->
            PocketGeometry.distanceToCaptureLine(origin, u, pocket, objectBallRadiusMeters) ?: Double.POSITIVE_INFINITY
        }
        val (lineA, lineB) = PocketGeometry.captureLine(pocket, objectBallRadiusMeters)
        val lineDir = lineB - lineA
        val nearestLine = abs(lineDir.cross(origin - lineA)) / lineDir.length().coerceAtLeast(1e-12)
        val farthestLine = maxOf(toCaptureLine(window.limitA), toCaptureLine(window.limitB))
        var arcs = listOf(Span(0.0, width))
        for (ball in obstacles) {
            val reach = objectBallRadiusMeters + ball.radiusMeters
            val toBall = ball.position - origin
            if (toBall.length() - reach >= farthestLine) continue
            val shadow = shadowOf(
                toObstacle = toBall,
                reach = reach,
                centre = turn * window.limitA.angleTo(toBall),
                domain = arcs,
                direction = direction,
                limit = toCaptureLine,
                limitLowerBound = nearestLine,
                limitUpperBound = farthestLine,
            )
            arcs = subtract(arcs, shadow)
            if (arcs.isEmpty()) return 0.0
        }

        // 2. Cue-line angles, relative to the aim line.
        var lines = arcs.mapNotNull { arc ->
            val a = cueLineOffsetFor(aim, direction(arc.lo), contact)
            val b = cueLineOffsetFor(aim, direction(arc.hi), contact)
            if (a == b) null else Span(minOf(a, b), maxOf(a, b))
        }.sortedBy { it.lo }

        // 3. The cue ball's own path to the object ball.
        val cueOrigin = aim.cueBallPos
        val toObject = aim.objectBallPos - cueOrigin
        val cueDirection = { d: Double -> aim.aimDirection.rotate(d) }
        val toObjectContact = { u: Vec2 ->
            objectBallDirectionForCueLine(cueOrigin, aim.objectBallPos, u, contact)?.second?.distanceTo(cueOrigin)
                ?: Double.POSITIVE_INFINITY
        }
        for (ball in obstacles) {
            if (lines.isEmpty()) return 0.0
            val reach = cueBallRadiusMeters + ball.radiusMeters
            val toBall = ball.position - cueOrigin
            if (toBall.length() - reach >= toObject.length()) continue
            val shadow = shadowOf(
                toObstacle = toBall,
                reach = reach,
                centre = aim.aimDirection.angleTo(toBall),
                domain = lines,
                direction = cueDirection,
                limit = toObjectContact,
                limitLowerBound = toObject.length() - contact,
                limitUpperBound = toObject.length(),
            )
            lines = subtract(lines, shadow)
        }

        // 4. Normal mass of what is left.
        var total = 0.0
        for (span in lines) total += normalCdf(span.hi / sigma) - normalCdf(span.lo / sigma)
        return total.coerceIn(0.0, 1.0)
    }

    /** Squared distance below which a ball in the other-balls list is taken to be the cue ball or the object ball, m². */
    private const val SAME_BALL_DISTANCE_SQ: Double = 1e-12

    /** Points at which a cone that is only partly cut back by the distance limit is sampled. */
    private const val SHADOW_SAMPLES: Int = 24

    /** Bisection steps for an edge of a partly cut-back cone (the cone is at most pi wide, so 2^-40 of it). */
    private const val SHADOW_BISECTIONS: Int = 40

    /** A closed interval of angles, radians. */
    private class Span(val lo: Double, val hi: Double)

    /**
     * The angles in [domain] (sorted, disjoint spans of a parameter `t`; [direction] turns `t` into a unit vector, `t`
     * being the signed angle from a base direction) along which a ball starting at the origin touches a ball at
     * [toObstacle] (centres [reach] apart at contact) before travelling [limit] of that direction. [centre] is the
     * parameter of the direction to the obstacle. [limitLowerBound] and [limitUpperBound] bound [limit] over the
     * domain: a cone whose nearest contact is beyond the upper bound casts no shadow, one whose farthest contact is
     * inside the lower bound is shadowed whole.
     */
    private fun shadowOf(
        toObstacle: Vec2,
        reach: Double,
        centre: Double,
        domain: List<Span>,
        direction: (Double) -> Vec2,
        limit: (Vec2) -> Double,
        limitLowerBound: Double,
        limitUpperBound: Double,
    ): List<Span> {
        val distance = toObstacle.length()
        if (distance < 1e-12) return emptyList()
        val nearestContact = distance - reach
        if (nearestContact >= limitUpperBound) return emptyList()
        // A ball closer than the contact distance shadows every direction with a forward component toward it.
        val half = if (distance <= reach) PI / 2.0 else asin(reach / distance)
        val farthestContact = if (distance <= reach) 0.0 else sqrt(distance * distance - reach * reach)
        val wholeCone = farthestContact < limitLowerBound
        fun blocked(t: Double): Boolean {
            val u = direction(t)
            val along = toObstacle.dot(u)
            if (along <= 0.0) return false
            val lateralSq = toObstacle.lengthSquared() - along * along
            if (lateralSq >= reach * reach) return false
            return along - sqrt(reach * reach - lateralSq) < limit(u)
        }
        val result = ArrayList<Span>()
        // The cone may wrap past +-pi relative to the base direction.
        for (shift in doubleArrayOf(0.0, 2.0 * PI, -2.0 * PI)) {
            val coneLo = centre + shift - half
            val coneHi = centre + shift + half
            for (span in domain) {
                val lo = maxOf(coneLo, span.lo)
                val hi = minOf(coneHi, span.hi)
                if (hi <= lo) continue
                if (wholeCone) {
                    result += Span(lo, hi)
                    continue
                }
                var runStart = if (blocked(lo)) lo else Double.NaN
                var previous = lo
                var previousBlocked = !runStart.isNaN()
                for (i in 1..SHADOW_SAMPLES) {
                    val t = lo + (hi - lo) * i / SHADOW_SAMPLES
                    val isBlocked = blocked(t)
                    if (isBlocked != previousBlocked) {
                        val edge = bisectEdge(previous, t, previousBlocked, ::blocked)
                        if (isBlocked) runStart = edge else result += Span(runStart, edge)
                    }
                    previous = t
                    previousBlocked = isBlocked
                }
                if (previousBlocked) result += Span(runStart, hi)
            }
        }
        return result
    }

    /** The point between [a] (where [blocked] is [blockedAtA]) and [b] (where it is not) at which it changes. */
    private fun bisectEdge(a: Double, b: Double, blockedAtA: Boolean, blocked: (Double) -> Boolean): Double {
        var lo = a
        var hi = b
        repeat(SHADOW_BISECTIONS) {
            val mid = 0.5 * (lo + hi)
            if (blocked(mid) == blockedAtA) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /** [spans] (sorted, disjoint) with every interval of [cuts] removed. */
    private fun subtract(spans: List<Span>, cuts: List<Span>): List<Span> {
        if (cuts.isEmpty()) return spans
        var current = spans
        for (cut in cuts) {
            val next = ArrayList<Span>(current.size + 1)
            for (span in current) {
                if (cut.hi <= span.lo || cut.lo >= span.hi) {
                    next += span
                    continue
                }
                if (cut.lo > span.lo) next += Span(span.lo, cut.lo)
                if (cut.hi < span.hi) next += Span(cut.hi, span.hi)
            }
            current = next
        }
        return current
    }

    /**
     * An upper bound on [makeProbability] of [aim] into [pocket] for any stroke, speed and tip of a player whose aim
     * error is [aimErrorStdRad]. [makeProbability] is a weighted mean of [directionalMakeProbability] values, each the
     * normal mass of cue-line angles inside one interval (or several, when other balls cut the window) whose span is
     * nested in the span of the window at the more lenient of the pocket's two jaw tolerances. The normal mass of an
     * interval of width `w` is largest when it is centred, `2 Phi(w / 2 sigma) - 1`, and falls as `sigma` grows; the
     * cue-line error is never below the aim error. So that value, at the lenient tolerance and the aim error, bounds
     * the make probability whatever the aim's centre (a throw-compensated aim included) and whatever the other balls.
     * [BOUND_SLACK] is added so that the bound also holds for the computed values, whose [normalCdf] terms each carry
     * an error of up to 1.5e-7.
     */
    fun makeProbabilityUpperBound(
        aim: AimGeometry,
        pocket: PocketSpec,
        objectBallRadiusMeters: Double,
        cueBallRadiusMeters: Double,
        aimErrorStdRad: Double,
    ): Double {
        val lenient = minOf(pocket.jawToleranceSlow, pocket.jawToleranceFast)
        val window = PocketGeometry.entryWindow(aim.objectBallPos, pocket, objectBallRadiusMeters, lenient)
        if (!window.isOpen) return 0.0
        val contact = cueBallRadiusMeters + objectBallRadiusMeters
        val width = abs(cueLineOffsetFor(aim, window.limitB, contact) - cueLineOffsetFor(aim, window.limitA, contact))
        val sigma = aimErrorStdRad.coerceAtLeast(MIN_AIM_ERROR_STD_RAD)
        return (2.0 * normalCdf(0.5 * width / sigma) - 1.0 + BOUND_SLACK).coerceIn(0.0, 1.0)
    }

    /** Added to [makeProbabilityUpperBound]: far above the rounding of a sum of a few [normalCdf] differences. */
    private const val BOUND_SLACK: Double = 1e-5

    /** Standard normal cumulative distribution (Abramowitz and Stegun 7.1.26 for erf, absolute error below 1.5e-7). */
    internal fun normalCdf(x: Double): Double {
        val z = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1.0 - poly * exp(-z * z)
        return if (x >= 0.0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /**
     * Make probability of [aim] into [pocket] for a stroke at [cueSpeedMps] with [tip]: [directionalMakeProbability]
     * at the cue-line error of [cueLineErrorStdRad], averaged over the player's speed error (three-point Gauss-Hermite
     * rule) with each speed's reach (the object ball must get to the pocket's capture line) and its arrival speed's jaw
     * tolerance. [otherBalls] are the balls on the table, passed on to [directionalMakeProbability] so that a ball beside
     * either path narrows the window.
     */
    fun makeProbability(
        table: TableSpec,
        targetBall: Ball,
        cueBallRadiusMeters: Double,
        pocket: PocketSpec,
        aim: AimGeometry,
        cueSpeedMps: Double,
        tip: TipOffset,
        playerProfile: PlayerProfile,
        otherBalls: List<Ball> = emptyList(),
    ): Double {
        if (!aim.isShotPlayable) return 0.0
        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2
        val dropDist = (
            PocketGeometry.distanceToCaptureLine(aim.objectBallPos, aim.objectBallDirection, pocket, targetBall.radiusMeters)
                ?: aim.objectToPocketDistanceMeters
            ).coerceAtLeast(0.02)
        val sigmaCue = cueLineErrorStdRad(playerProfile, aim, cueSpeedMps, tip, table)
        val sigmaSpeed = speedErrorFraction(playerProfile)
        var total = 0.0
        for (i in SPEED_NODES.indices) {
            val speed = (cueSpeedMps * (1.0 + SPEED_NODES[i] * sigmaSpeed)).coerceAtLeast(0.05)
            val pre = SpinPhysics.computePreImpactCueState(speed, tip, aim.cueToGhostDistanceMeters, table)
            if (pre.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT || pre.impactSpeedMps <= 0.03) continue
            val vObject = MotionPhysics.transferredObjectBallInitialSpeedMps(
                pre.impactSpeedMps, aim.cutAngleRadians, table.ballContact.restitution,
            )
            if (MotionPhysics.objectBallStoppingDistanceMeters(vObject, muS, muR, g) < dropDist) continue
            val arrival = MotionPhysics.slideThenRollSpeedAfterDistanceMps(vObject, dropDist, muS, muR, g = g)
            total += SPEED_WEIGHTS[i] * directionalMakeProbability(
                aim = aim,
                pocket = pocket,
                objectBallRadiusMeters = targetBall.radiusMeters,
                cueBallRadiusMeters = cueBallRadiusMeters,
                jawTolerance = PocketGeometry.jawTolerance(pocket, arrival),
                cueLineErrorStdRad = sigmaCue,
                otherBalls = otherBalls,
            )
        }
        return total.coerceIn(0.0, 1.0)
    }

    /**
     * Evaluates make probability ([makeProbability], with the other balls of [layout] narrowing the window), scratch
     * probability and the cue-ball landing cloud for [aim] executed at [cueSpeedMps] with [tip] under [playerProfile]'s
     * errors. [ShotUncertaintyResult.targetBallPotted] is true only when the nominal stroke reaches the target and pots
     * it in [pocket].
     */
    fun evaluateShotUncertainty(
        layout: TableLayout,
        targetBall: Ball,
        pocket: PocketSpec,
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        playerProfile: PlayerProfile = PlayerProfile(),
    ): ShotUncertaintyResult = evaluateShotUncertaintyIf(
        layout, targetBall, pocket, aim, cueSpeedMps, tip, playerProfile,
        keepNominal = { true },
        maxScratchProbability = Double.POSITIVE_INFINITY,
    ) ?: error("Unreachable: no filter rejects the shot")

    /**
     * [evaluateShotUncertainty], stopping early with `null` when the nominal simulation fails [keepNominal] (checked
     * before the eight perturbed simulations are run) or once the scratch weight of the landing cloud reaches
     * [maxScratchProbability]. Every result it returns is the one [evaluateShotUncertainty] would return.
     */
    internal fun evaluateShotUncertaintyIf(
        layout: TableLayout,
        targetBall: Ball,
        pocket: PocketSpec,
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        playerProfile: PlayerProfile,
        keepNominal: (PostShotTableSimulation) -> Boolean,
        maxScratchProbability: Double,
    ): ShotUncertaintyResult? {
        val table = layout.table
        val cueBallRadius = layout.cueBall()?.radiusMeters ?: table.ballRadiusMeters
        val sigmaAim = cueLineErrorStdRad(playerProfile, aim, cueSpeedMps, tip, table)
        val sigmaSpeedFrac = speedErrorFraction(playerProfile)

        val stencil = listOf(
            SigmaOffset(0.0, 0.0, 0.0, 0.0, SIGMA_WEIGHTS[0]),
            SigmaOffset(1.35 * sigmaAim, 0.0, 0.0, 0.0, SIGMA_WEIGHTS[1]),
            SigmaOffset(-1.35 * sigmaAim, 0.0, 0.0, 0.0, SIGMA_WEIGHTS[2]),
            SigmaOffset(0.0, 1.35 * sigmaSpeedFrac, 0.0, 0.0, SIGMA_WEIGHTS[3]),
            SigmaOffset(0.0, -1.35 * sigmaSpeedFrac, 0.0, 0.0, SIGMA_WEIGHTS[4]),
            SigmaOffset(0.85 * sigmaAim, 0.85 * sigmaSpeedFrac, 0.05, 0.0, SIGMA_WEIGHTS[5]),
            SigmaOffset(-0.85 * sigmaAim, -0.85 * sigmaSpeedFrac, -0.05, 0.0, SIGMA_WEIGHTS[6]),
            SigmaOffset(-0.85 * sigmaAim, 0.85 * sigmaSpeedFrac, 0.0, 0.05, SIGMA_WEIGHTS[7]),
            SigmaOffset(0.85 * sigmaAim, -0.85 * sigmaSpeedFrac, 0.0, -0.05, SIGMA_WEIGHTS[8]),
        )

        val contactDist = cueBallRadius + targetBall.radiusMeters
        val nominalSquirt = SpinPhysics.computeSquirtAngleRadians(tip, playerProfile.shaftType)
        val nominalSimulation = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = targetBall,
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
        )
        if (!keepNominal(nominalSimulation)) return null

        val landingCloud = ArrayList<Vec2>(stencil.size)
        val scratchedAtPoint = ArrayList<Boolean>(stencil.size)
        var nominalTrajectory: CueBallTrajectory? = null
        var nominalStop = aim.ghostBallCenter
        var weightedScratchProb = 0.0
        var meanX = 0.0
        var meanY = 0.0
        var nonScratchWeightSum = 0.0
        var nonScratchX = 0.0
        var nonScratchY = 0.0
        val maxStopX = (table.lengthMeters - cueBallRadius).coerceAtLeast(cueBallRadius)
        val maxStopY = (table.widthMeters - cueBallRadius).coerceAtLeast(cueBallRadius)

        for ((index, point) in stencil.withIndex()) {
            val perturbedSpeed = (cueSpeedMps * (1.0 + point.dSpeedFrac)).coerceAtLeast(0.30)
            val perturbedTip = TipOffset(
                sideRatio = tip.sideRatio + point.dSideRatio,
                verticalRatio = tip.verticalRatio + point.dVertRatio,
                elevationRadians = tip.elevationRadians,
            )
            val perturbedAim = if (index == 0) {
                aim
            } else {
                // A different side tip squirts the cue ball by a different amount: that, too, turns the cue line.
                val squirtChange = SpinPhysics.computeSquirtAngleRadians(perturbedTip, playerProfile.shaftType) - nominalSquirt
                perturbedAimFor(aim, point.dAimRad + squirtChange, contactDist)
            }

            val trajectory = when {
                index == 0 -> nominalSimulation.cueBallTrajectory
                else -> TableCollisionSimulator.simulatePostImpact(
                    layout = layout,
                    targetBall = targetBall,
                    aim = perturbedAim,
                    cueSpeedMps = perturbedSpeed,
                    tip = perturbedTip,
                ).cueBallTrajectory
            }
            if (index == 0) {
                nominalTrajectory = trajectory
            }

            val rawStop = if (index == 0) {
                nominalSimulation.resultingLayout.cueBall()?.position
                    ?: trajectory.points.lastOrNull()
                    ?: perturbedAim.ghostBallCenter
            } else {
                trajectory.points.lastOrNull() ?: perturbedAim.ghostBallCenter
            }
            val stopPos = Vec2(
                x = rawStop.x.coerceIn(cueBallRadius, maxStopX),
                y = rawStop.y.coerceIn(cueBallRadius, maxStopY),
            )
            if (index == 0) {
                nominalStop = stopPos
            }
            landingCloud += stopPos
            meanX += point.weight * stopPos.x
            meanY += point.weight * stopPos.y

            val didScratch = trajectory.hitsScratchPocket != null
            scratchedAtPoint += didScratch
            if (didScratch) {
                weightedScratchProb += point.weight
                if (weightedScratchProb >= maxScratchProbability) return null
            } else {
                nonScratchWeightSum += point.weight
                nonScratchX += point.weight * stopPos.x
                nonScratchY += point.weight * stopPos.y
            }
        }

        val useNonScratchOnly = nonScratchWeightSum > 1e-6
        val resolvedMeanX = if (useNonScratchOnly) nonScratchX / nonScratchWeightSum else meanX
        val resolvedMeanY = if (useNonScratchOnly) nonScratchY / nonScratchWeightSum else meanY
        val meanLanding = Vec2(
            x = resolvedMeanX.coerceIn(cueBallRadius, maxStopX),
            y = resolvedMeanY.coerceIn(cueBallRadius, maxStopY),
        )
        var meanSqDist = 0.0
        var spreadWeightSum = 0.0
        for (i in stencil.indices) {
            if (useNonScratchOnly && scratchedAtPoint[i]) continue
            val w = stencil[i].weight
            meanSqDist += w * landingCloud[i].distanceSquaredTo(meanLanding)
            spreadWeightSum += w
        }
        val rmsSq = if (spreadWeightSum > 1e-6) meanSqDist / spreadWeightSum else meanSqDist
        val spreadRadius = max(1.96 * sqrt(rmsSq), meanLanding.distanceTo(nominalStop))

        val targetPottedNominally = pottedAsIntended(nominalSimulation, pocket)

        val makeProbability = makeProbability(
            table = table,
            targetBall = targetBall,
            cueBallRadiusMeters = cueBallRadius,
            pocket = pocket,
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            playerProfile = playerProfile,
            otherBalls = layout.balls,
        )

        val resolvedNominal = nominalTrajectory ?: SpinPhysics.traceCueBallPathWithSpin(
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            table = table,
            ballRadiusMeters = cueBallRadius,
        )

        return ShotUncertaintyResult(
            makeProbability = makeProbability,
            scratchProbability = weightedScratchProb.coerceIn(0.0, 1.0),
            nominalTrajectory = resolvedNominal,
            landingCloud = landingCloud,
            meanLandingPosition = meanLanding,
            landingSpreadRadiusMeters = spreadRadius,
            postShotSimulation = nominalSimulation,
            nominalLandingPosition = nominalStop,
            targetBallPotted = targetPottedNominally,
            landingCloudScratched = scratchedAtPoint,
        )
    }

    /**
     * Whether [simulation] has the cue ball reach the target and the target drop in [pocket] (not in another pocket).
     */
    internal fun pottedAsIntended(simulation: PostShotTableSimulation, pocket: PocketSpec): Boolean =
        simulation.targetBallPotted &&
            !simulation.cueBallStoppedBeforeImpact &&
            (simulation.targetBallTrajectory?.pocketedIn ?: pocket.id) == pocket.id

    /**
     * [aim] with the cue line turned by [cueLineOffsetRad]: the cue ball meets the object ball where that line first
     * comes within a contact distance of it, and the object ball leaves along the line of centres there. A line that
     * would miss is held at the grazing cut.
     */
    private fun perturbedAimFor(aim: GhostBallAim, cueLineOffsetRad: Double, contactDist: Double): GhostBallAim {
        val line = aim.aimDirection.rotate(cueLineOffsetRad)
        val hit = objectBallDirectionForCueLine(aim.cueBallPos, aim.objectBallPos, line, contactDist)
        val (objectDir, ghost) = hit ?: run {
            // Missed: keep the grazing contact on the side the line turned to.
            val toObject = (aim.objectBallPos - aim.cueBallPos)
            val half = asin((contactDist * kotlin.math.sin(GRAZING_CUT_RAD) / toObject.length()).coerceIn(0.0, 1.0))
            val centre = aim.aimDirection.angleTo(toObject.normalized())
            val clamped = if (cueLineOffsetRad > centre) centre + half * 0.999 else centre - half * 0.999
            objectBallDirectionForCueLine(aim.cueBallPos, aim.objectBallPos, aim.aimDirection.rotate(clamped), contactDist)
                ?: (aim.objectBallDirection to aim.ghostBallCenter)
        }
        val toGhost = ghost - aim.cueBallPos
        val aimDir = toGhost.normalized()
        return aim.copy(
            ghostBallCenter = ghost,
            aimDirection = aimDir,
            objectBallDirection = objectDir,
            cutAngleRadians = aimDir.angleTo(objectDir).coerceIn(-MAX_PERTURBED_CUT_RAD, MAX_PERTURBED_CUT_RAD),
            cueToGhostDistanceMeters = toGhost.length(),
        )
    }
}
