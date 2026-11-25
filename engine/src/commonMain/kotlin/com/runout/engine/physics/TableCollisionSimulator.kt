package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketKind
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.math.abs
import kotlin.math.min

/**
 * Path traced by an object ball that was struck and displaced during a cue-ball collision
 * or cluster scatter.
 *
 * [points] has a vertex at the start, at every contact with a ball or cushion, and at the end; between them a ball may
 * curve (a slide with spin), so [sampledPoints] is the path itself, sampled closely enough that every chord stays within
 * 1 mm of the curve. Draw [sampledPoints]; use [points] for the sequence of contacts.
 */
data class DisplacedBallPath(
    val ballNumber: Int,
    val points: List<Vec2>,
    val pocketedIn: PocketId? = null,
    val sampledPoints: List<Vec2> = points,
)

/**
 * Kind of discrete event recorded along the post-impact cue-ball path.
 */
enum class CueBallEventKind {
    CUSHION,
    BALL_CONTACT,
    SCRATCH,

    /** The cue ball stops slipping on the cloth and starts to roll; its path stops curving here. */
    SLIDE_ENDS,
}

/**
 * Discrete post-impact cue-ball event (cushion rebound, secondary ball contact, end of a slide, or scratch)
 * with speed (m/s) and the spin before and after the event.
 *
 * - [sideSpinBefore] / [sideSpinAfter] are the cue ball's english just before and after the event, written as the
 *   side offset of the tip (fraction of the ball radius, negative left) that would give its spin about the vertical
 *   axis to a ball moving at its speed then ([BallState.sideSpinAsTipOffset]): it shrinks as the spin wears off and
 *   grows as the ball slows. A cushion changes it ([CushionContact]); so does a ball contact, a little.
 * - [verticalSpinBefore] / [verticalSpinAfter] are vertical spin factors along the direction of travel
 *   (`k`, see [BallState.spinFactorAlongTravel]) just before and after the event. A ball usually leaves a cushion
 *   sliding, so the value after a cushion is the real one, not natural roll.
 * - [cushionIndex] counts the cue ball's cushion contacts from 1; [otherBallNumber] is the ball struck.
 */
data class CueBallPathEvent(
    val kind: CueBallEventKind,
    val position: Vec2,
    val speedInMps: Double,
    val speedOutMps: Double,
    val sideSpinBefore: Double = 0.0,
    val sideSpinAfter: Double = 0.0,
    val otherBallNumber: Int? = null,
    val cushionIndex: Int = 0,
    val verticalSpinBefore: Double = 0.0,
    val verticalSpinAfter: Double = 0.0,
)

/**
 * Full multi-ball result of simulating post-impact cue-ball motion against remaining object balls
 * and cushions, yielding the evolved [resultingLayout] and scattered [disturbedBalls] paths.
 *
 * [truncated] is true when the event budget ran out with balls still moving. The balls were then each run on to rest
 * against the cushions and pockets alone, without further contacts between balls, so [resultingLayout] holds rest
 * positions, but the later part of the shot is not the full simulation.
 */
data class PostShotTableSimulation(
    val cueBallTrajectory: CueBallTrajectory,
    val disturbedBalls: List<DisplacedBallPath>,
    val resultingLayout: TableLayout,
    val targetBallTrajectory: DisplacedBallPath? = null,
    val targetBallPotted: Boolean,
    val cueBallStoppedBeforeImpact: Boolean = false,
    val cueBallEvents: List<CueBallPathEvent> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * Event-based simulation of every ball on the table after the cue ball meets the object ball.
 *
 * Each ball carries a [BallState] (velocity and spin) and moves in closed form ([BallMotion]): a slide along a
 * parabola until the slip is gone, then a straight roll to rest. The simulation jumps from event to event: the end
 * of a slide, a ball coming to rest, a cushion (the root of a quadratic), a pocket, or two balls touching
 * ([BallMotion.contactTime]). Ball-ball contacts use [BallBallContact] with both balls' real velocities and spins and
 * the table's ball-ball friction and restitution ([TableSpec.ballContact]), and leave both sliding with their spin;
 * cushions use [CushionContact].
 *
 * **Balls on a cushion line.** A ball whose centre is on a cushion line (within
 * [BallMotion.BOUND_CONTACT_TOLERANCE_METERS]), such as a ball frozen to a cushion, strikes the cushion at once when
 * it moves into it, or drops if that point of the cushion line lies in a pocket mouth. Every cushion contact leaves the
 * ball with no velocity into the cushion, so such a contact happens once. A ball resting on a cushion line whose slide
 * pushes it into the cushion is resolved at once by [CushionContact.pressedAgainst] and then slides along the
 * cushion. When a ball touches two cushion lines at once the two contacts are applied one after the other.
 *
 * **Event budget.** Each slide end, stop, cushion, contact and pocket drop is one event; the default budget grows with
 * the number of balls ([defaultMaxEvents]). If it runs out with balls still moving, each ball is run on to rest
 * against the cushions and pockets alone, and the result is marked truncated.
 */
object TableCollisionSimulator {
    private const val MIN_DISPLACEMENT_RECORD_METERS: Double = 0.002
    private const val PATH_POINT_DEDUP_METERS: Double = 1e-4

    /** Gap left between two balls after a contact so the pair is not found touching again at once, metres. */
    private const val CONTACT_SEPARATION_METERS: Double = 1e-6

    /**
     * Events allowed per moving ball, once the budget has run out, to run each ball on to rest against the cushions
     * and pockets alone.
     */
    private const val FINISH_EVENTS_PER_BALL: Int = 48

    private class SimBall(
        val number: Int,
        val radius: Double,
        val initialPos: Vec2,
        var state: BallState,
        val massKg: Double = Ball.DEFAULT_POOL_BALL_MASS_KG,
        var active: Boolean = true,
        var pocketedIn: PocketId? = null,
        var railBounces: Int = 0,
        var ballCollisions: Int = 0,
    ) {
        val samples: MutableList<Vec2> = mutableListOf(initialPos)
        val vertices: MutableList<Vec2> = mutableListOf(initialPos)
    }

    /** What the event loop reports besides the balls' own states. */
    private class RunOutcome(val cueScratchPocket: PocketId?, val truncated: Boolean)

    private sealed interface SimEvent {
        val time: Double

        data class PhaseEnd(override val time: Double) : SimEvent

        /**
         * The ball meets the cushion line on the side given by [inwardX] (`+1` the head cushion `x = R`, `-1` the foot
         * cushion, `0` neither) and [inwardY] (`+1` the left cushion `y = R`, `-1` the right one, `0` neither): the
         * sign is that of the cushion's inward normal. [pressed] marks a ball at rest against the cushion that its
         * slide pushes into it ([CushionContact.pressedAgainst]) rather than one moving into it.
         */
        data class RailHit(
            override val time: Double,
            val ballIndex: Int,
            val inwardX: Int,
            val inwardY: Int,
            val pressed: Boolean = false,
        ) : SimEvent

        /** The ball's centre reaches [pocket]'s capture line or the mouth gap in a cushion line. */
        data class PocketEntry(
            override val time: Double,
            val ballIndex: Int,
            val pocket: PocketSpec,
        ) : SimEvent

        data class BallCollision(
            override val time: Double,
            val indexA: Int,
            val indexB: Int,
        ) : SimEvent
    }

    /**
     * Simulates the table after the cue ball, struck at [cueSpeedMps] with [tip] along [aim], meets [targetBall]:
     * the contact ([SpinPhysics.computePostImpactCueState]) launches the object ball along
     * [AimGeometry.objectBallDirection] and sends the cue ball off from [AimGeometry.ghostBallCenter] with its speed and
     * spin, and every ball left on [layout] then moves, collides and comes to rest. An object ball that already lies in
     * a pocket ([PocketGeometry.containsRestingBall]) drops at the start, as the cue ball does.
     */
    fun simulatePostImpact(
        layout: TableLayout,
        targetBall: Ball,
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        maxEvents: Int = defaultMaxEvents(layout.balls.size),
    ): PostShotTableSimulation {
        val table = layout.table
        val preset = tip.toSpinPreset()
        val cueRadius = layout.cueBall()?.radiusMeters ?: table.ballRadiusMeters
        val preImpact = SpinPhysics.computePreImpactCueState(
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = table,
        )
        if (preImpact.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT) {
            val cueStop = clampToBounds(
                pos = aim.cueBallPos + aim.aimDirection * preImpact.travelledDistanceMeters,
                table = table,
                radius = cueRadius,
            )
            val scratchAtStop = detectPocketAtPoint(cueStop, table.pockets, cueRadius)?.id
            val cueTrajectory = CueBallTrajectory(
                spin = preset,
                points = listOf(aim.cueBallPos, cueStop),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = scratchAtStop,
            )
            val evolvedBalls = layout.balls.map { ball ->
                if (ball.isCueBall) ball.copy(position = cueStop) else ball
            }
            return PostShotTableSimulation(
                cueBallTrajectory = cueTrajectory,
                disturbedBalls = emptyList(),
                resultingLayout = TableLayout(table = table, balls = evolvedBalls),
                targetBallTrajectory = DisplacedBallPath(
                    ballNumber = targetBall.number,
                    points = listOf(targetBall.position),
                    pocketedIn = null,
                ),
                targetBallPotted = false,
                cueBallStoppedBeforeImpact = true,
            )
        }

        val post = SpinPhysics.computePostImpactCueState(aim, cueSpeedMps, tip, table)

        val remaining = layout.balls.filter { !it.isCueBall && it.number != targetBall.number }
        val startCuePos = clampToBounds(aim.ghostBallCenter, table, cueRadius)
        val simBalls = ArrayList<SimBall>(remaining.size + 2)
        simBalls += SimBall(
            number = 0,
            radius = cueRadius,
            initialPos = startCuePos,
            state = post.cue.copy(position = startCuePos),
            massKg = layout.cueBall()?.massKg ?: Ball.DEFAULT_POOL_BALL_MASS_KG,
        )
        simBalls += SimBall(
            number = targetBall.number,
            radius = targetBall.radiusMeters,
            initialPos = targetBall.position,
            state = post.objectBall.copy(position = targetBall.position),
            massKg = targetBall.massKg,
        )
        for (ball in remaining) {
            simBalls += SimBall(
                number = ball.number,
                radius = ball.radiusMeters,
                initialPos = ball.position,
                state = BallState(ball.position),
                massKg = ball.massKg,
            )
        }
        for (index in 1 until simBalls.size) dropIfInPocket(simBalls[index], table)

        val cueEvents = ArrayList<CueBallPathEvent>()
        var cueScratchPocket: PocketId? = detectPocketAtPoint(startCuePos, table.pockets, cueRadius)?.id
        var truncated = false
        if (cueScratchPocket == null) {
            val outcome = run(
                table = table,
                simBalls = simBalls,
                maxCueCushions = Int.MAX_VALUE,
                maxEvents = maxEvents,
                cueEvents = cueEvents,
            )
            cueScratchPocket = outcome.cueScratchPocket
            truncated = outcome.truncated
        }

        val surviving = simBalls.filter { it.number == 0 || it.active }
        separateRestingBalls(surviving, table)

        val cueSim = simBalls[0]
        val targetSim = simBalls[1]
        val cueTrajectory = CueBallTrajectory(
            spin = preset,
            points = cueSim.vertices.toList(),
            initialDeflectionAngleRad = post.initialDeflectionAngleRad,
            hitsScratchPocket = cueScratchPocket,
            sampledPoints = cueSim.samples.toList(),
            rollingDeflectionAngleRad = post.rollingDeflectionAngleRad,
            cushionContacts = cueSim.railBounces,
            ballContacts = cueSim.ballCollisions,
            truncated = truncated,
        )
        val targetTrajectory = DisplacedBallPath(
            ballNumber = targetSim.number,
            points = targetSim.vertices.toList(),
            pocketedIn = targetSim.pocketedIn,
            sampledPoints = targetSim.samples.toList(),
        )

        val disturbed = simBalls.drop(2).mapNotNull { sb ->
            val displaced = sb.state.position.distanceTo(sb.initialPos) > MIN_DISPLACEMENT_RECORD_METERS
            if (!displaced && sb.pocketedIn == null) {
                null
            } else {
                DisplacedBallPath(
                    ballNumber = sb.number,
                    points = sb.vertices.toList(),
                    pocketedIn = sb.pocketedIn,
                    sampledPoints = sb.samples.toList(),
                )
            }
        }

        val evolvedBalls = surviving.map { sb ->
            val layoutPos = if (sb.number == 0 && !sb.active) {
                clampToBounds(sb.state.position, table, sb.radius)
            } else {
                sb.state.position
            }
            Ball(
                number = sb.number,
                position = layoutPos,
                radiusMeters = sb.radius,
                massKg = sb.massKg,
            )
        }

        return PostShotTableSimulation(
            cueBallTrajectory = cueTrajectory,
            disturbedBalls = disturbed,
            resultingLayout = TableLayout(table = table, balls = evolvedBalls),
            targetBallTrajectory = targetTrajectory,
            targetBallPotted = targetSim.pocketedIn != null,
            cueBallStoppedBeforeImpact = false,
            cueBallEvents = cueEvents.toList(),
            truncated = truncated,
        )
    }

    /**
     * Path of the cue ball alone from the moment it leaves the object ball in [cueState] (at the ghost-ball centre),
     * among [obstacleBalls] that it may strike and move, until it comes to rest, drops into a pocket, or reaches
     * a cushion after [maxCushions] cushion contacts (it is then stopped there).
     */
    internal fun traceCueBall(
        table: TableSpec,
        cueState: BallState,
        cueRadius: Double,
        spin: CueBallSpinPreset,
        initialDeflectionAngleRad: Double,
        rollingDeflectionAngleRad: Double,
        obstacleBalls: List<Ball> = emptyList(),
        maxCushions: Int = Int.MAX_VALUE,
        maxEvents: Int = defaultMaxEvents(obstacleBalls.size + 1),
    ): CueBallTrajectory {
        val start = cueState.position
        val cue = SimBall(0, cueRadius, start, cueState)
        val balls = ArrayList<SimBall>(obstacleBalls.size + 1)
        balls += cue
        for (ball in obstacleBalls) {
            val obstacle = SimBall(ball.number, ball.radiusMeters, ball.position, BallState(ball.position), ball.massKg)
            dropIfInPocket(obstacle, table)
            balls += obstacle
        }
        var scratch = detectPocketAtPoint(start, table.pockets, cueRadius)?.id
        var truncated = false
        if (scratch == null) {
            val outcome = run(
                table = table,
                simBalls = balls,
                maxCueCushions = maxCushions.coerceAtLeast(0),
                maxEvents = maxEvents,
                cueEvents = ArrayList(),
            )
            scratch = outcome.cueScratchPocket
            truncated = outcome.truncated
        }
        if (cue.active) closePath(cue, cue.state.position)
        return CueBallTrajectory(
            spin = spin,
            points = cue.vertices.toList(),
            initialDeflectionAngleRad = initialDeflectionAngleRad,
            hitsScratchPocket = scratch,
            sampledPoints = cue.samples.toList(),
            rollingDeflectionAngleRad = rollingDeflectionAngleRad,
            cushionContacts = cue.railBounces,
            ballContacts = cue.ballCollisions,
            truncated = truncated,
        )
    }

    /** Events allowed for a shot with no balls; [defaultMaxEvents] adds [MAX_EVENTS_PER_BALL] per ball. */
    const val DEFAULT_MAX_EVENTS: Int = 64

    /** Events the default budget allows per ball on the table. */
    const val MAX_EVENTS_PER_BALL: Int = 16

    /**
     * Default event budget for a shot with [ballCount] balls on the table, cue ball included:
     * [DEFAULT_MAX_EVENTS] plus [MAX_EVENTS_PER_BALL] per ball.
     */
    fun defaultMaxEvents(ballCount: Int): Int = DEFAULT_MAX_EVENTS + MAX_EVENTS_PER_BALL * ballCount.coerceAtLeast(1)

    /** Takes [sb] off the table into the pocket it already lies in, if any. */
    private fun dropIfInPocket(sb: SimBall, table: TableSpec) {
        val pocket = detectPocketAtPoint(sb.state.position, table.pockets, sb.radius) ?: return
        sb.active = false
        sb.pocketedIn = pocket.id
        sb.state = BallState(pocket.holeCenter)
        closePath(sb, pocket.holeCenter)
    }

    /**
     * Runs the event loop over [simBalls] (index 0 is the cue ball) and returns the pocket the cue ball drops into,
     * if any, and whether the budget of [maxEvents] ran out with balls still moving. In that case the balls are run
     * on without contacts between them, against the cushions and pockets, until they rest.
     */
    private fun run(
        table: TableSpec,
        simBalls: List<SimBall>,
        maxCueCushions: Int,
        maxEvents: Int,
        cueEvents: MutableList<CueBallPathEvent>,
    ): RunOutcome {
        val motion = BallMotion(table.cloth)
        var cueScratchPocket: PocketId? = null
        val n = simBalls.size
        val phaseEnd = DoubleArray(n)
        val accel = arrayOfNulls<Vec2>(n)
        val moving = BooleanArray(n)
        val movingIndices = IntArray(n)
        val budget = maxEvents.coerceAtLeast(1)
        var eventsUsed = 0
        var ballContacts = true
        var finishEventsLeft = 0

        while (true) {
            var movingCount = 0
            for (idx in 0 until n) {
                val sb = simBalls[idx]
                val isMoving = sb.active && motion.phaseOf(sb.state) != BallMotionPhase.STATIONARY
                moving[idx] = isMoving
                if (isMoving) {
                    movingIndices[movingCount++] = idx
                    phaseEnd[idx] = motion.phaseDuration(sb.state)
                    accel[idx] = motion.acceleration(sb.state)
                } else {
                    phaseEnd[idx] = Double.POSITIVE_INFINITY
                    accel[idx] = Vec2.ZERO
                }
            }
            if (movingCount == 0) break
            if (ballContacts && eventsUsed >= budget) {
                ballContacts = false
                finishEventsLeft = FINISH_EVENTS_PER_BALL * movingCount
            }
            if (ballContacts) {
                eventsUsed++
            } else {
                if (finishEventsLeft <= 0) break
                finishEventsLeft--
            }

            var earliest: SimEvent? = null
            fun consider(candidate: SimEvent) {
                if (candidate.time.isFinite() && candidate.time >= 0.0) {
                    val cur = earliest
                    if (cur == null || candidate.time < cur.time) earliest = candidate
                }
            }

            // 1. Phase ends (a slide becoming a roll, a roll coming to rest).
            for (k in 0 until movingCount) consider(SimEvent.PhaseEnd(phaseEnd[movingIndices[k]]))

            // 2. Pockets and cushions of each moving ball within its current phase. A cushion line counts only where the
            // nose is not cut away by a pocket mouth.
            for (k in 0 until movingCount) {
                val idx = movingIndices[k]
                val sb = simBalls[idx]
                val tPhase = phaseEnd[idx]
                val a = accel[idx] ?: Vec2.ZERO
                val rail = earliestRailHit(motion, sb.state, a, tPhase, table, sb.radius)
                val entry = earliestPocketEntry(motion, sb.state, a, tPhase, rail, table, sb.radius, idx)
                if (entry != null && (rail == null || entry.time <= rail.time)) {
                    consider(entry)
                } else if (rail != null) {
                    consider(rail.copy(ballIndex = idx))
                }
            }

            // 3. Ball-ball contacts before the earliest event found so far.
            for (k in 0 until (if (ballContacts) movingCount else 0)) {
                val i = movingIndices[k]
                val ballA = simBalls[i]
                for (j in 0 until n) {
                    if (j == i) continue
                    val ballB = simBalls[j]
                    if (!ballB.active) continue
                    if (moving[j] && j < i) continue
                    val horizon = earliest?.time ?: min(phaseEnd[i], phaseEnd[j])
                    if (!(horizon > 0.0)) continue
                    val t = BallMotion.contactTime(
                        positionA = ballA.state.position,
                        velocityA = ballA.state.velocity,
                        accelerationA = accel[i] ?: Vec2.ZERO,
                        positionB = ballB.state.position,
                        velocityB = ballB.state.velocity,
                        accelerationB = accel[j] ?: Vec2.ZERO,
                        contactDistance = ballA.radius + ballB.radius,
                        horizon = horizon,
                    ) ?: continue
                    consider(SimEvent.BallCollision(t, i, j))
                }
            }

            val event = earliest ?: break
            val dt = event.time
            if (!dt.isFinite()) break

            // Balls at rest keep spinning down about the vertical axis.
            for (idx in 0 until n) {
                val sb = simBalls[idx]
                if (!moving[idx] && sb.active && sb.state.sideSpinSurfaceSpeedMps != 0.0) {
                    sb.state = sb.state.copy(sideSpinSurfaceSpeedMps = motion.sideSpinAt(sb.state, dt))
                }
            }

            // Advance every moving ball by dt along its closed-form path, recording the path as it goes.
            for (k in 0 until movingCount) {
                val idx = movingIndices[k]
                val sb = simBalls[idx]
                val before = sb.state
                val tPhase = phaseEnd[idx]
                val endsPhase = dt >= tPhase - 1e-12
                val span = if (endsPhase) tPhase else dt
                recordSamples(motion, sb, before, span)
                val after = if (endsPhase) motion.endOfPhase(before) else motion.stateAt(before, dt)
                sb.state = after.copy(position = clampToBounds(after.position, table, sb.radius))
                if (endsPhase) {
                    val wasSliding = motion.phaseOf(before) == BallMotionPhase.SLIDING
                    val nowStopped = motion.phaseOf(sb.state) == BallMotionPhase.STATIONARY
                    if (nowStopped) {
                        closePath(sb, sb.state.position)
                    } else {
                        addSample(sb, sb.state.position)
                    }
                    if (idx == 0 && wasSliding) {
                        val speed = sb.state.velocity.length()
                        cueEvents += CueBallPathEvent(
                            kind = CueBallEventKind.SLIDE_ENDS,
                            position = sb.state.position,
                            speedInMps = speed,
                            speedOutMps = speed,
                            sideSpinBefore = sb.state.sideSpinAsTipOffset(),
                            sideSpinAfter = sb.state.sideSpinAsTipOffset(),
                            verticalSpinBefore = sb.state.spinFactorAlongTravel(),
                            verticalSpinAfter = sb.state.spinFactorAlongTravel(),
                        )
                    }
                }
            }

            when (event) {
                is SimEvent.PhaseEnd -> Unit

                is SimEvent.PocketEntry -> {
                    val sb = simBalls[event.ballIndex]
                    val arriving = sb.state
                    val speed = arriving.velocity.length()
                    val pocket = event.pocket
                    addVertex(sb, arriving.position)
                    // It drops if its line clears both points of the pocket by the jaw tolerance for its speed;
                    // otherwise it strikes a jaw and comes back off it like off a cushion. A ball with no speed left
                    // at the mouth (one its spin pulls over the edge) has nothing to carry it onto a point.
                    val clears = speed <= BallMotion.SPEED_EPSILON_MPS || PocketGeometry.clearsJaws(
                        arriving.position,
                        arriving.velocity,
                        pocket,
                        sb.radius,
                        PocketGeometry.jawTolerance(pocket, speed),
                    )
                    val jaw = if (clears) null else jawNormal(arriving, pocket)
                    if (jaw == null) {
                        sb.active = false
                        sb.pocketedIn = pocket.id
                        sb.state = BallState(pocket.holeCenter)
                        closePath(sb, pocket.holeCenter)
                        if (event.ballIndex == 0) {
                            cueScratchPocket = pocket.id
                            cueEvents += CueBallPathEvent(
                                kind = CueBallEventKind.SCRATCH,
                                position = pocket.holeCenter,
                                speedInMps = speed,
                                speedOutMps = 0.0,
                            )
                        }
                        continue
                    }
                    if (event.ballIndex == 0 && sb.railBounces >= maxCueCushions) {
                        sb.state = BallState(arriving.position, sideSpinSurfaceSpeedMps = arriving.sideSpinSurfaceSpeedMps)
                        continue
                    }
                    sb.state = CushionContact.rebound(arriving, jaw, table.cloth)
                    if (motion.phaseOf(sb.state) == BallMotionPhase.STATIONARY) continue
                    sb.railBounces++
                    if (event.ballIndex == 0) {
                        cueEvents += CueBallPathEvent(
                            kind = CueBallEventKind.CUSHION,
                            position = arriving.position,
                            speedInMps = speed,
                            speedOutMps = sb.state.velocity.length(),
                            sideSpinBefore = arriving.sideSpinAsTipOffset(),
                            sideSpinAfter = sb.state.sideSpinAsTipOffset(),
                            cushionIndex = sb.railBounces,
                            verticalSpinBefore = arriving.spinFactorAlongTravel(),
                            verticalSpinAfter = sb.state.spinFactorAlongTravel(),
                        )
                    }
                }

                is SimEvent.RailHit -> {
                    val sb = simBalls[event.ballIndex]
                    val arriving = sb.state
                    val normals = buildList {
                        if (event.inwardX != 0) add(Vec2(event.inwardX.toDouble(), 0.0))
                        if (event.inwardY != 0) add(Vec2(0.0, event.inwardY.toDouble()))
                    }
                    if (event.pressed) {
                        var held = arriving
                        for (normal in normals) held = CushionContact.pressedAgainst(held, normal)
                        sb.state = held
                        continue
                    }
                    var leaving = arriving
                    if (normals.all { -arriving.velocity.dot(it) <= CushionContact.MIN_APPROACH_SPEED_MPS }) {
                        // A touch with no measurable impulse: the cushion only stops the drift into it.
                        for (normal in normals) leaving = CushionContact.rebound(leaving, normal, table.cloth)
                        sb.state = leaving
                        continue
                    }
                    addVertex(sb, arriving.position)
                    if (event.ballIndex == 0 && sb.railBounces >= maxCueCushions) {
                        sb.state = BallState(arriving.position, sideSpinSurfaceSpeedMps = arriving.sideSpinSurfaceSpeedMps)
                        continue
                    }
                    // Two cushions met at once (a ball in a corner) give two contacts, one after the other.
                    for (normal in normals) leaving = CushionContact.rebound(leaving, normal, table.cloth)
                    sb.state = leaving
                    if (motion.phaseOf(sb.state) == BallMotionPhase.STATIONARY) continue
                    sb.railBounces++
                    if (event.ballIndex == 0) {
                        cueEvents += CueBallPathEvent(
                            kind = CueBallEventKind.CUSHION,
                            position = arriving.position,
                            speedInMps = arriving.velocity.length(),
                            speedOutMps = sb.state.velocity.length(),
                            sideSpinBefore = arriving.sideSpinAsTipOffset(),
                            sideSpinAfter = sb.state.sideSpinAsTipOffset(),
                            cushionIndex = sb.railBounces,
                            verticalSpinBefore = arriving.spinFactorAlongTravel(),
                            verticalSpinAfter = sb.state.spinFactorAlongTravel(),
                        )
                    }
                }

                is SimEvent.BallCollision -> {
                    val ballA = simBalls[event.indexA]
                    val ballB = simBalls[event.indexB]
                    val cueInvolved = event.indexA == 0 || event.indexB == 0
                    val cueBefore = simBalls[0].state
                    var delta = ballB.state.position - ballA.state.position
                    var dist = delta.length()
                    val normal = if (dist > 1e-9) delta / dist else (ballA.state.velocity - ballB.state.velocity).normalized()
                    val minSep = ballA.radius + ballB.radius + CONTACT_SEPARATION_METERS
                    if (dist < minSep) {
                        val push = (minSep - dist) * 0.5
                        ballA.state = ballA.state.copy(position = clampToBounds(ballA.state.position - normal * push, table, ballA.radius))
                        ballB.state = ballB.state.copy(position = clampToBounds(ballB.state.position + normal * push, table, ballB.radius))
                        delta = ballB.state.position - ballA.state.position
                        dist = delta.length()
                    }
                    addVertex(ballA, ballA.state.position)
                    addVertex(ballB, ballB.state.position)
                    val outcome = BallBallContact.resolve(
                        striking = ballA.state,
                        struck = ballB.state,
                        lineOfCentres = normal,
                        restitution = table.ballContact.restitution,
                        ballBallFrictionMu = table.ballContact.frictionMu,
                    )
                    ballA.state = outcome.striking
                    ballB.state = outcome.struck
                    ballA.ballCollisions++
                    ballB.ballCollisions++
                    if (cueInvolved) {
                        val other = if (event.indexA == 0) ballB else ballA
                        val cueAfter = simBalls[0].state
                        cueEvents += CueBallPathEvent(
                            kind = CueBallEventKind.BALL_CONTACT,
                            position = cueAfter.position,
                            speedInMps = cueBefore.velocity.length(),
                            speedOutMps = cueAfter.velocity.length(),
                            otherBallNumber = other.number,
                            sideSpinBefore = cueBefore.sideSpinAsTipOffset(),
                            sideSpinAfter = cueAfter.sideSpinAsTipOffset(),
                            verticalSpinBefore = cueBefore.spinFactorAlongTravel(),
                            verticalSpinAfter = cueAfter.spinFactorAlongTravel(),
                        )
                    }
                }
            }
        }

        // A ball still moving when even the run-on budget is spent stays where the last event left it.
        for (sb in simBalls) {
            if (sb.active) closePath(sb, sb.state.position)
        }
        return RunOutcome(cueScratchPocket, truncated = !ballContacts)
    }

    /**
     * Adds samples of [state]'s path over the next [span] seconds of its current phase, enough that every chord is
     * within [BallMotion.PATH_SAMPLE_TOLERANCE_METERS] of the curve; the apex of a slide that reverses is always
     * sampled. The end point itself is added by the caller.
     */
    private fun recordSamples(motion: BallMotion, sb: SimBall, state: BallState, span: Double) {
        if (span <= 0.0 || motion.phaseOf(state) != BallMotionPhase.SLIDING) return
        val a = motion.acceleration(state)
        val aSq = a.lengthSquared()
        val apex = if (aSq > 1e-18) -state.velocity.dot(a) / aSq else -1.0
        val pieces = motion.chordPieces(state, span)
        var apexDone = !(apex > 0.0 && apex < span)
        for (k in 1 until pieces) {
            val t = span * k / pieces
            if (!apexDone && apex < t) {
                addSample(sb, motion.positionAt(state, apex))
                apexDone = true
            }
            addSample(sb, motion.positionAt(state, t))
        }
        if (!apexDone) addSample(sb, motion.positionAt(state, apex))
    }

    private fun addSample(sb: SimBall, p: Vec2) {
        val last = sb.samples.last()
        if (last.distanceTo(p) > PATH_POINT_DEDUP_METERS) sb.samples += p
    }

    /** Records a contact, a pocket drop or the rest point: a vertex of [SimBall.vertices] and a sample of the path. */
    private fun addVertex(sb: SimBall, p: Vec2) {
        addSample(sb, p)
        val last = sb.vertices.last()
        if (last.distanceTo(p) > PATH_POINT_DEDUP_METERS) sb.vertices += p
    }

    private fun closePath(sb: SimBall, p: Vec2) = addVertex(sb, p)

    /** Pushes apart resting balls that ended up overlapping and moves the ends of their paths with them. */
    private fun separateRestingBalls(surviving: List<SimBall>, table: TableSpec) {
        val before = surviving.map { it.state.position }
        for (pass in 0 until 4) {
            var anyOverlap = false
            for (i in surviving.indices) {
                for (j in i + 1 until surviving.size) {
                    val a = surviving[i]
                    val b = surviving[j]
                    val minSep = a.radius + b.radius + 1e-4
                    val delta = b.state.position - a.state.position
                    val d = delta.length()
                    if (d < minSep) {
                        anyOverlap = true
                        val n = if (d > 1e-9) delta / d else Vec2.UNIT_X
                        val push = (minSep - d) * 0.5
                        a.state = a.state.copy(position = clampToBounds(a.state.position - n * push, table, a.radius))
                        b.state = b.state.copy(position = clampToBounds(b.state.position + n * push, table, b.radius))
                    }
                }
            }
            if (!anyOverlap) break
        }
        for ((index, sb) in surviving.withIndex()) {
            if (!sb.active) continue
            val old = before[index]
            if (sb.state.position.distanceTo(old) > 1e-6) {
                movePathEnd(sb.vertices, old, sb.state.position)
                movePathEnd(sb.samples, old, sb.state.position)
            }
        }
    }

    private fun movePathEnd(path: MutableList<Vec2>, old: Vec2, new: Vec2) {
        val lastIdx = path.lastIndex
        if (lastIdx >= 1 && path[lastIdx].distanceTo(old) <= 1e-4 && path[lastIdx].distanceTo(path[lastIdx - 1]) > 1e-3) {
            path[lastIdx] = new
        } else if (path.last().distanceTo(new) > PATH_POINT_DEDUP_METERS) {
            path += new
        }
    }

    /**
     * Earliest contact of [state] with a cushion line (`R` from the cushion nose) within `[0, tMax]` of its current
     * phase ([BallMotion.timeToContactBound]): at once for a ball on the line moving into it, otherwise where it
     * crosses the line moving outward. With no contact at once, a sliding ball resting on a line (no velocity across
     * it) whose slide pushes it into the cushion gives a pressed contact at once.
     */
    private fun earliestRailHit(
        motion: BallMotion,
        state: BallState,
        a: Vec2,
        tMax: Double,
        table: TableSpec,
        radius: Double,
    ): SimEvent.RailHit? {
        val minX = radius
        val maxX = (table.lengthMeters - radius).coerceAtLeast(radius)
        val minY = radius
        val maxY = (table.widthMeters - radius).coerceAtLeast(radius)
        val p = state.position
        val v = state.velocity
        fun contact(p0: Double, v0: Double, a0: Double, bound: Double, direction: Int): Double =
            BallMotion.timeToContactBound(p0, v0, a0, bound, direction, tMax) ?: Double.POSITIVE_INFINITY
        val txHead = contact(p.x, v.x, a.x, minX, -1)
        val txFoot = contact(p.x, v.x, a.x, maxX, +1)
        val tyLeft = contact(p.y, v.y, a.y, minY, -1)
        val tyRight = contact(p.y, v.y, a.y, maxY, +1)
        val t = minOf(minOf(txHead, txFoot), minOf(tyLeft, tyRight))

        if (!(t <= 0.0) && motion.phaseOf(state) == BallMotionPhase.SLIDING) {
            fun pressed(p0: Double, v0: Double, a0: Double, bound: Double, direction: Int): Boolean =
                (bound - p0) * direction <= BallMotion.BOUND_CONTACT_TOLERANCE_METERS && v0 == 0.0 && a0 * direction > 0.0
            val inwardX = if (pressed(p.x, v.x, a.x, minX, -1)) 1 else if (pressed(p.x, v.x, a.x, maxX, +1)) -1 else 0
            val inwardY = if (pressed(p.y, v.y, a.y, minY, -1)) 1 else if (pressed(p.y, v.y, a.y, maxY, +1)) -1 else 0
            if (inwardX != 0 || inwardY != 0) return SimEvent.RailHit(0.0, -1, inwardX, inwardY, pressed = true)
        }
        if (!t.isFinite()) return null
        fun at(time: Double): Boolean = abs(time - t) <= SIMULTANEOUS_CONTACT_SECONDS
        return SimEvent.RailHit(
            time = t,
            ballIndex = -1,
            inwardX = if (at(txHead)) 1 else if (at(txFoot)) -1 else 0,
            inwardY = if (at(tyLeft)) 1 else if (at(tyRight)) -1 else 0,
        )
    }

    /** Two cushion contacts closer together than this are taken as one contact with both cushions, seconds. */
    private const val SIMULTANEOUS_CONTACT_SECONDS: Double = 1e-9

    /**
     * First moment within `[0, tMax]` of [state]'s current phase at which the ball's centre enters a pocket: it
     * crosses a corner pocket's mouth on the cloth, or it meets a cushion line (`R` from the nose) where the nose is
     * cut away by a mouth ([PocketGeometry.isInMouth]; for a side pocket that is its capture line). [rail] is the
     * ball's earliest cushion-line contact, if any. A ball already on a mouth line and moving into the pocket, or
     * pressed toward it on a cushion line inside a mouth, enters at once.
     */
    private fun earliestPocketEntry(
        motion: BallMotion,
        state: BallState,
        a: Vec2,
        tMax: Double,
        rail: SimEvent.RailHit?,
        table: TableSpec,
        radius: Double,
        ballIndex: Int,
    ): SimEvent.PocketEntry? {
        var best: SimEvent.PocketEntry? = null
        if (rail != null) {
            val at = motion.positionAt(state, rail.time)
            val contacts = buildList {
                if (rail.inwardX != 0) add(Vec2(if (rail.inwardX > 0) 0.0 else table.lengthMeters, at.y))
                if (rail.inwardY != 0) add(Vec2(at.x, if (rail.inwardY > 0) 0.0 else table.widthMeters))
            }
            val pocket = table.pockets.firstOrNull { p -> contacts.any { PocketGeometry.isInMouth(it, p) } }
            if (pocket != null) best = SimEvent.PocketEntry(rail.time, ballIndex, pocket)
        }
        for (pocket in table.pockets) {
            if (pocket.kind != PocketKind.CORNER) continue
            val axis = pocket.axisIntoPocket
            val mid = pocket.mouthCenter
            val t = BallMotion.timeToContactBound(
                p0 = (state.position - mid).dot(axis),
                v0 = state.velocity.dot(axis),
                a = a.dot(axis),
                bound = 0.0,
                direction = +1,
                tMax = tMax,
            ) ?: continue
            if (best != null && t >= best.time) continue
            val across = (pocket.jawTipB - pocket.jawTipA).normalized()
            val lateral = abs((motion.positionAt(state, t) - mid).dot(across))
            if (lateral <= pocket.mouthWidthMeters * 0.5) best = SimEvent.PocketEntry(t, ballIndex, pocket)
        }
        return best
    }

    /**
     * Normal of the jaw a ball at [state] strikes when it enters [pocket] without clearing the points: the facing at
     * the tip it passes closest to, if the ball is moving into that facing; else the line from the tip to the ball's
     * centre, if it is moving toward the tip; `null` if it is moving away from both (it has already passed the point).
     * Moving into a jaw means faster than [CushionContact.MIN_APPROACH_SPEED_MPS], the least approach a cushion contact
     * rebounds from, so a ball just sent off a jaw is not found striking it again.
     */
    private fun jawNormal(state: BallState, pocket: PocketSpec): Vec2? {
        val dir = state.velocity
        val pos = state.position
        val tip = if (PocketGeometry.rayClearance(pos, dir, pocket.jawTipA) <= PocketGeometry.rayClearance(pos, dir, pocket.jawTipB)) {
            pocket.jawTipA
        } else {
            pocket.jawTipB
        }
        val facing = PocketGeometry.facingNormal(pocket, tip)
        if (dir.dot(facing) < -CushionContact.MIN_APPROACH_SPEED_MPS) return facing
        val toBall = (pos - tip).normalized()
        if (dir.dot(toBall) < -CushionContact.MIN_APPROACH_SPEED_MPS) return toBall
        return null
    }

    private fun detectPocketAtPoint(point: Vec2, pockets: List<PocketSpec>, radius: Double): PocketSpec? =
        pockets.firstOrNull { PocketGeometry.containsRestingBall(point, it, radius) }

    private fun clampToBounds(pos: Vec2, table: TableSpec, radius: Double): Vec2 {
        val minX = radius.coerceAtMost(table.lengthMeters * 0.5)
        val maxX = (table.lengthMeters - radius).coerceAtLeast(minX)
        val minY = radius.coerceAtMost(table.widthMeters * 0.5)
        val maxY = (table.widthMeters - radius).coerceAtLeast(minY)
        return Vec2(
            x = pos.x.coerceIn(minX, maxX),
            y = pos.y.coerceIn(minY, maxY),
        )
    }
}
