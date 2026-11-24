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
 */
data class PostShotTableSimulation(
    val cueBallTrajectory: CueBallTrajectory,
    val disturbedBalls: List<DisplacedBallPath>,
    val resultingLayout: TableLayout,
    val targetBallTrajectory: DisplacedBallPath? = null,
    val targetBallPotted: Boolean = true,
    val cueBallStoppedBeforeImpact: Boolean = false,
    val cueBallEvents: List<CueBallPathEvent> = emptyList(),
)

/**
 * Event-based simulation of every ball on the table after the cue ball meets the object ball.
 *
 * Each ball carries a [BallState] (velocity and spin) and moves in closed form ([BallMotion]): a slide along a
 * parabola until the slip is gone, then a straight roll to rest. The simulation jumps from event to event: the end
 * of a slide, a ball coming to rest, a cushion (the root of a quadratic), a pocket, or two balls touching
 * ([BallMotion.contactTime]). Ball-ball contacts use [BallBallContact] with both balls' real velocities and spins and
 * leave both sliding with their spin; cushions use [CushionContact].
 */
object TableCollisionSimulator {
    private const val MIN_DISPLACEMENT_RECORD_METERS: Double = 0.002
    private const val PATH_POINT_DEDUP_METERS: Double = 1e-4

    /** Gap left between two balls after a contact so the pair is not found touching again at once, metres. */
    private const val CONTACT_SEPARATION_METERS: Double = 1e-6

    private class SimBall(
        val number: Int,
        val radius: Double,
        val initialPos: Vec2,
        var state: BallState,
        var active: Boolean = true,
        var pocketedIn: PocketId? = null,
        var railBounces: Int = 0,
        var ballCollisions: Int = 0,
    ) {
        val samples: MutableList<Vec2> = mutableListOf(initialPos)
        val vertices: MutableList<Vec2> = mutableListOf(initialPos)
    }

    private sealed interface SimEvent {
        val time: Double

        data class PhaseEnd(override val time: Double) : SimEvent

        data class RailHit(
            override val time: Double,
            val ballIndex: Int,
            val hitVertical: Boolean,
            val hitHorizontal: Boolean,
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
     * spin, and every ball left on [layout] then moves, collides and comes to rest.
     */
    fun simulatePostImpact(
        layout: TableLayout,
        targetBall: Ball,
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        maxEvents: Int = DEFAULT_MAX_EVENTS,
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
        )
        simBalls += SimBall(
            number = targetBall.number,
            radius = targetBall.radiusMeters,
            initialPos = targetBall.position,
            state = post.objectBall.copy(position = targetBall.position),
        )
        for (ball in remaining) {
            simBalls += SimBall(
                number = ball.number,
                radius = ball.radiusMeters,
                initialPos = ball.position,
                state = BallState(ball.position),
            )
        }

        val cueEvents = ArrayList<CueBallPathEvent>()
        var cueScratchPocket: PocketId? = detectPocketAtPoint(startCuePos, table.pockets, cueRadius)?.id
        if (cueScratchPocket == null) {
            cueScratchPocket = run(
                table = table,
                simBalls = simBalls,
                maxCueCushions = Int.MAX_VALUE,
                maxEvents = maxEvents,
                cueEvents = cueEvents,
            )
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
        maxEvents: Int = DEFAULT_MAX_EVENTS,
    ): CueBallTrajectory {
        val start = cueState.position
        val cue = SimBall(0, cueRadius, start, cueState)
        val balls = ArrayList<SimBall>(obstacleBalls.size + 1)
        balls += cue
        for (ball in obstacleBalls) {
            balls += SimBall(ball.number, ball.radiusMeters, ball.position, BallState(ball.position))
        }
        var scratch = detectPocketAtPoint(start, table.pockets, cueRadius)?.id
        if (scratch == null) {
            scratch = run(
                table = table,
                simBalls = balls,
                maxCueCushions = maxCushions.coerceAtLeast(0),
                maxEvents = maxEvents,
                cueEvents = ArrayList(),
            )
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
        )
    }

    /** Default cap on simulated events per shot; each slide end, stop, cushion, contact and pocket drop is one. */
    const val DEFAULT_MAX_EVENTS: Int = 64

    /**
     * Runs the event loop over [simBalls] (index 0 is the cue ball) and returns the pocket the cue ball drops into,
     * if any.
     */
    private fun run(
        table: TableSpec,
        simBalls: List<SimBall>,
        maxCueCushions: Int,
        maxEvents: Int,
        cueEvents: MutableList<CueBallPathEvent>,
    ): PocketId? {
        val motion = BallMotion(table.cloth)
        var cueScratchPocket: PocketId? = null
        val n = simBalls.size
        val phaseEnd = DoubleArray(n)
        val accel = arrayOfNulls<Vec2>(n)
        val moving = BooleanArray(n)
        val movingIndices = IntArray(n)

        for (step in 0 until maxEvents.coerceAtLeast(1)) {
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
                val rail = earliestRailHit(sb.state, a, tPhase, table, sb.radius)
                val entry = earliestPocketEntry(motion, sb.state, a, tPhase, rail, table, sb.radius, idx)
                if (entry != null && (rail == null || entry.time <= rail.time)) {
                    consider(entry)
                } else if (rail != null) {
                    consider(rail.copy(ballIndex = idx))
                }
            }

            // 3. Ball-ball contacts before the earliest event found so far.
            for (k in 0 until movingCount) {
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
                    // otherwise it strikes a jaw and comes back off it like off a cushion.
                    val clears = PocketGeometry.clearsJaws(
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
                    addVertex(sb, arriving.position)
                    if (event.ballIndex == 0 && sb.railBounces >= maxCueCushions) {
                        sb.state = BallState(arriving.position, sideSpinSurfaceSpeedMps = arriving.sideSpinSurfaceSpeedMps)
                        continue
                    }
                    val normal = inwardNormalForHit(event.hitVertical, event.hitHorizontal, table, arriving.position)
                    sb.state = CushionContact.rebound(arriving, normal, table.cloth)
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
                    val outcome = BallBallContact.resolve(ballA.state, ballB.state, normal)
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

        // Balls still moving when the event budget runs out stay where the last event left them.
        for (sb in simBalls) {
            if (sb.active) closePath(sb, sb.state.position)
        }
        return cueScratchPocket
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
        repeat(4) {
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
            if (!anyOverlap) return@repeat
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
     * Earliest crossing of a cushion line (`R` from the cushion nose) by [state] within `(0, tMax]` of its current
     * phase, moving outward.
     */
    private fun earliestRailHit(state: BallState, a: Vec2, tMax: Double, table: TableSpec, radius: Double): SimEvent.RailHit? {
        val minX = radius
        val maxX = (table.lengthMeters - radius).coerceAtLeast(radius)
        val minY = radius
        val maxY = (table.widthMeters - radius).coerceAtLeast(radius)
        val p = state.position
        val v = state.velocity
        val tx = minOf(
            BallMotion.timeToReachCoordinate(p.x, v.x, a.x, maxX, +1, tMax) ?: Double.POSITIVE_INFINITY,
            BallMotion.timeToReachCoordinate(p.x, v.x, a.x, minX, -1, tMax) ?: Double.POSITIVE_INFINITY,
        )
        val ty = minOf(
            BallMotion.timeToReachCoordinate(p.y, v.y, a.y, maxY, +1, tMax) ?: Double.POSITIVE_INFINITY,
            BallMotion.timeToReachCoordinate(p.y, v.y, a.y, minY, -1, tMax) ?: Double.POSITIVE_INFINITY,
        )
        val t = min(tx, ty)
        if (!t.isFinite()) return null
        return SimEvent.RailHit(
            time = t,
            ballIndex = -1,
            hitVertical = abs(tx - t) <= 1e-9,
            hitHorizontal = abs(ty - t) <= 1e-9,
        )
    }

    /**
     * First moment within `(0, tMax]` of [state]'s current phase at which the ball's centre enters a pocket: it
     * crosses a corner pocket's mouth on the cloth, or it reaches a cushion line (`R` from the nose) where the nose is
     * cut away by a mouth ([PocketGeometry.isInMouth]; for a side pocket that is its capture line). [rail] is the
     * ball's earliest cushion-line crossing, if any.
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
                if (rail.hitVertical) add(Vec2(if (at.x <= table.lengthMeters * 0.5) 0.0 else table.lengthMeters, at.y))
                if (rail.hitHorizontal) add(Vec2(at.x, if (at.y <= table.widthMeters * 0.5) 0.0 else table.widthMeters))
            }
            val pocket = table.pockets.firstOrNull { p -> contacts.any { PocketGeometry.isInMouth(it, p) } }
            if (pocket != null) best = SimEvent.PocketEntry(rail.time, ballIndex, pocket)
        }
        for (pocket in table.pockets) {
            if (pocket.kind != PocketKind.CORNER) continue
            val axis = pocket.axisIntoPocket
            val mid = pocket.mouthCenter
            val t = BallMotion.timeToReachCoordinate(
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
        if (dir.dot(facing) < 0.0) return facing
        val toBall = (pos - tip).normalized()
        if (dir.dot(toBall) < 0.0) return toBall
        return null
    }

    private fun detectPocketAtPoint(point: Vec2, pockets: List<PocketSpec>, radius: Double): PocketSpec? =
        pockets.firstOrNull { PocketGeometry.containsRestingBall(point, it, radius) }

    private fun inwardNormalForHit(
        hitVertical: Boolean,
        hitHorizontal: Boolean,
        table: TableSpec,
        pos: Vec2,
    ): Vec2 {
        val nx = if (hitVertical) {
            if (pos.x <= table.lengthMeters * 0.5) 1.0 else -1.0
        } else {
            0.0
        }
        val ny = if (hitHorizontal) {
            if (pos.y <= table.widthMeters * 0.5) 1.0 else -1.0
        } else {
            0.0
        }
        val raw = Vec2(nx, ny)
        return if (raw.lengthSquared() < 1e-12) Vec2(1.0, 0.0) else raw.normalized()
    }

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
