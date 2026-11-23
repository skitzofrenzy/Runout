package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Path traced by an object ball that was struck and displaced during a cue-ball collision
 * or cluster scatter.
 */
data class DisplacedBallPath(
    val ballNumber: Int,
    val points: List<Vec2>,
    val pocketedIn: PocketId? = null,
)

/**
 * Full multi-ball result of simulating post-impact cue-ball motion against remaining object balls
 * and cushions, yielding the evolved [resultingLayout] and scattered [disturbedBalls] paths.
 */
data class PostShotTableSimulation(
    val cueBallTrajectory: CueBallTrajectory,
    val disturbedBalls: List<DisplacedBallPath>,
    val resultingLayout: TableLayout,
)

/**
 * Event-driven rigid-body rolling & elastic-collision simulator for resolving secondary
 * cue-ball-to-cluster collisions, chain impacts, cushion rebounds, and evolved table layouts.
 */
object TableCollisionSimulator {
    private const val BALL_RESTITUTION: Double = 0.93
    private const val MIN_SPEED_MPS: Double = 1e-4
    private const val MIN_DISPLACEMENT_RECORD_METERS: Double = 0.002

    private class SimBall(
        val number: Int,
        val radius: Double,
        val initialPos: Vec2,
        var pos: Vec2,
        var vel: Vec2,
        var active: Boolean = true,
        var pocketedIn: PocketId? = null,
        var railBounces: Int = 0,
        val path: MutableList<Vec2> = mutableListOf(initialPos),
    )

    private sealed interface SimEvent {
        val time: Double

        data class RailHit(
            override val time: Double,
            val ballIndex: Int,
            val hitVertical: Boolean,
            val hitHorizontal: Boolean,
        ) : SimEvent

        data class PocketDrop(
            override val time: Double,
            val ballIndex: Int,
            val pocket: PocketSpec,
        ) : SimEvent

        data class BallCollision(
            override val time: Double,
            val indexA: Int,
            val indexB: Int,
        ) : SimEvent

        data class StopStep(
            override val time: Double,
        ) : SimEvent
    }

    /**
     * Simulates post-contact cue-ball roll from [aim.ghostBallCenter] while colliding elastically
     * with any stationary or scattered balls remaining on [layout] after [targetBall] is potted.
     */
    fun simulatePostImpact(
        layout: TableLayout,
        targetBall: Ball,
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        maxEvents: Int = 20,
    ): PostShotTableSimulation {
        val table = layout.table
        val preset = tip.toSpinPreset()
        val postState = SpinPhysics.computePostImpactCueState(aim, cueSpeedMps, tip, table)
        val cueRadius = layout.cueBall()?.radiusMeters ?: table.ballRadiusMeters

        val remaining = layout.balls.filter { !it.isCueBall && it.number != targetBall.number }
        val startCuePos = clampToBounds(aim.ghostBallCenter, table, cueRadius)
        val simBalls = ArrayList<SimBall>(remaining.size + 1)
        simBalls += SimBall(
            number = 0,
            radius = cueRadius,
            initialPos = startCuePos,
            pos = startCuePos,
            vel = postState.initialVelocity,
        )
        for (ball in remaining) {
            simBalls += SimBall(
                number = ball.number,
                radius = ball.radiusMeters,
                initialPos = ball.position,
                pos = ball.position,
                vel = Vec2.ZERO,
            )
        }

        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val decel = muR * MotionPhysics.GRAVITY_M_S2
        var cueScratchPocket: PocketId? = detectPocketAtPoint(startCuePos, table.pockets)?.id

        if (cueScratchPocket == null && postState.effectiveInitialSpeedMps > MIN_SPEED_MPS) {
            for (step in 0 until maxEvents.coerceAtLeast(1)) {
                val movingIndices = simBalls.indices.filter { idx ->
                    simBalls[idx].active && simBalls[idx].vel.length() > MIN_SPEED_MPS
                }
                if (movingIndices.isEmpty()) break

                var earliest: SimEvent? = null
                fun consider(candidate: SimEvent) {
                    if (candidate.time.isFinite() && candidate.time > 1e-7) {
                        val cur = earliest
                        if (cur == null || candidate.time < cur.time) {
                            earliest = candidate
                        }
                    }
                }

                // 1. Earliest stop time among moving balls
                var earliestStop = Double.POSITIVE_INFINITY
                for (idx in movingIndices) {
                    val speed = simBalls[idx].vel.length()
                    earliestStop = min(earliestStop, speed / decel)
                }
                if (earliestStop.isFinite()) {
                    consider(SimEvent.StopStep(earliestStop))
                }

                // 2. Pocket drops & rail hits for each moving ball
                for (idx in movingIndices) {
                    val sb = simBalls[idx]
                    val speed = sb.vel.length()
                    val dir = sb.vel / speed
                    val stopDist = (speed * speed) / (2.0 * decel)

                    val railHit = intersectRails(sb.pos, dir, table, sb.radius)
                    val maxTravel = min(stopDist, railHit.distance)

                    val pocketDrop = findEarliestPocketOnRay(sb.pos, dir, maxTravel, table.pockets)
                    if (pocketDrop != null) {
                        val tDrop = timeToTravelDistance(speed, pocketDrop.second, decel)
                        consider(SimEvent.PocketDrop(tDrop, idx, pocketDrop.first))
                    } else if (railHit.distance <= stopDist && railHit.distance > 1e-6) {
                        val tRail = timeToTravelDistance(speed, railHit.distance, decel)
                        consider(SimEvent.RailHit(tRail, idx, railHit.hitVertical, railHit.hitHorizontal))
                    }
                }

                // 3. Ball-Ball collisions
                for (i in movingIndices) {
                    val ballA = simBalls[i]
                    for (j in simBalls.indices) {
                        if (j <= i) {
                            // Ensure pairs involving stationary j or moving j < i aren't duplicated
                            if (j == i || simBalls[j].vel.length() > MIN_SPEED_MPS) continue
                        }
                        val ballB = simBalls[j]
                        if (!ballB.active) continue
                        val tColl = findBallCollisionTime(ballA, ballB, decel)
                        if (tColl != null) {
                            consider(SimEvent.BallCollision(tColl, i, j))
                        }
                    }
                }

                val event = earliest ?: break
                val dt = event.time
                if (dt <= 0.0 || !dt.isFinite()) break

                // Advance all moving balls by dt
                for (idx in movingIndices) {
                    val sb = simBalls[idx]
                    val speed = sb.vel.length()
                    val dir = sb.vel / speed
                    val newSpeed = (speed - decel * dt).coerceAtLeast(0.0)
                    val avgDist = ((speed + newSpeed) * 0.5) * dt
                    sb.pos = clampToBounds(sb.pos + dir * avgDist, table, sb.radius)
                    sb.vel = if (newSpeed > MIN_SPEED_MPS) dir * newSpeed else Vec2.ZERO
                }

                when (event) {
                    is SimEvent.StopStep -> {
                        for (idx in movingIndices) {
                            val sb = simBalls[idx]
                            if (sb.vel.length() <= MIN_SPEED_MPS) {
                                sb.vel = Vec2.ZERO
                                appendPathPoint(sb.path, sb.pos)
                            }
                        }
                    }

                    is SimEvent.PocketDrop -> {
                        val sb = simBalls[event.ballIndex]
                        sb.active = false
                        sb.vel = Vec2.ZERO
                        sb.pocketedIn = event.pocket.id
                        sb.pos = event.pocket.center
                        appendPathPoint(sb.path, event.pocket.center)
                        if (sb.number == 0) {
                            cueScratchPocket = event.pocket.id
                            break
                        }
                    }

                    is SimEvent.RailHit -> {
                        val sb = simBalls[event.ballIndex]
                        appendPathPoint(sb.path, sb.pos)
                        val speed = sb.vel.length()
                        if (speed > MIN_SPEED_MPS) {
                            val normal = inwardNormalForHit(event.hitVertical, event.hitHorizontal, table, sb.pos)
                            val vNormalMag = abs(sb.vel.dot(normal))
                            if (vNormalMag > MIN_SPEED_MPS) {
                                val tangent = Vec2(normal.y, -normal.x)
                                val ecEffective = (table.cloth.cushionRestitutionEc - 0.035 * vNormalMag)
                                    .coerceIn(0.55, 0.90)
                                val vNormalOut = vNormalMag * ecEffective
                                val vTanIn = sb.vel.dot(tangent)
                                val vTanOut = if (sb.number == 0) {
                                    val sideRatio = tip.clampedSideRatio * (0.72.pow(sb.railBounces))
                                    vTanIn * 0.82 + sideRatio * vNormalMag * 0.55
                                } else {
                                    vTanIn * 0.86
                                }
                                sb.vel = normal * vNormalOut + tangent * vTanOut
                                sb.railBounces++
                            } else {
                                sb.vel = Vec2.ZERO
                            }
                        }
                    }

                    is SimEvent.BallCollision -> {
                        val ballA = simBalls[event.indexA]
                        val ballB = simBalls[event.indexB]
                        val delta = ballB.pos - ballA.pos
                        val dist = delta.length()
                        val normal = if (dist > 1e-9) delta / dist else Vec2.UNIT_X
                        val minSep = ballA.radius + ballB.radius + 1e-4
                        if (dist < minSep) {
                            val push = (minSep - dist) * 0.5
                            ballA.pos = clampToBounds(ballA.pos - normal * push, table, ballA.radius)
                            ballB.pos = clampToBounds(ballB.pos + normal * push, table, ballB.radius)
                        }
                        appendPathPoint(ballA.path, ballA.pos)
                        appendPathPoint(ballB.path, ballB.pos)

                        val relVel = ballA.vel - ballB.vel
                        val vn = relVel.dot(normal)
                        if (vn > 0.0) {
                            val impulse = 0.5 * (1.0 + BALL_RESTITUTION) * vn
                            ballA.vel -= normal * impulse
                            ballB.vel += normal * impulse
                            if (ballA.vel.length() <= MIN_SPEED_MPS) ballA.vel = Vec2.ZERO
                            if (ballB.vel.length() <= MIN_SPEED_MPS) ballB.vel = Vec2.ZERO
                        }
                    }
                }
            }
        }

        // Append final resting coordinates cleanly
        for (sb in simBalls) {
            if (sb.active) {
                appendPathPoint(sb.path, sb.pos)
            }
        }

        // Guarantee non-overlapping surviving balls
        val surviving = simBalls.filter { it.number == 0 || it.active }
        repeat(4) {
            var anyOverlap = false
            for (i in surviving.indices) {
                for (j in i + 1 until surviving.size) {
                    val a = surviving[i]
                    val b = surviving[j]
                    val minSep = a.radius + b.radius + 1e-4
                    val delta = b.pos - a.pos
                    val d = delta.length()
                    if (d < minSep) {
                        anyOverlap = true
                        val n = if (d > 1e-9) delta / d else Vec2.UNIT_X
                        val push = (minSep - d) * 0.5
                        a.pos = clampToBounds(a.pos - n * push, table, a.radius)
                        b.pos = clampToBounds(b.pos + n * push, table, b.radius)
                    }
                }
            }
            if (!anyOverlap) return@repeat
        }

        val cueSim = simBalls.first()
        val cueTrajectory = CueBallTrajectory(
            spin = preset,
            points = cueSim.path.toList(),
            initialDeflectionAngleRad = postState.initialDeflectionAngleRad,
            hitsScratchPocket = cueScratchPocket,
        )

        val disturbed = simBalls.drop(1).mapNotNull { sb ->
            val displaced = sb.pos.distanceTo(sb.initialPos) > MIN_DISPLACEMENT_RECORD_METERS
            if (!displaced && sb.pocketedIn == null) {
                null
            } else {
                DisplacedBallPath(
                    ballNumber = sb.number,
                    points = sb.path.toList(),
                    pocketedIn = sb.pocketedIn,
                )
            }
        }

        val evolvedBalls = surviving.map { sb ->
            Ball(
                number = sb.number,
                position = sb.pos,
                radiusMeters = sb.radius,
            )
        }

        return PostShotTableSimulation(
            cueBallTrajectory = cueTrajectory,
            disturbedBalls = disturbed,
            resultingLayout = TableLayout(table = table, balls = evolvedBalls),
        )
    }

    private fun appendPathPoint(path: MutableList<Vec2>, pt: Vec2) {
        val last = path.lastOrNull()
        if (last == null || last.distanceTo(pt) > 1e-4) {
            path += pt
        }
    }

    private fun timeToTravelDistance(speed: Double, dist: Double, decel: Double): Double {
        if (dist <= 0.0) return 0.0
        val disc = max(0.0, speed * speed - 2.0 * decel * dist)
        return (speed - sqrt(disc)) / decel
    }

    private fun findBallCollisionTime(a: SimBall, b: SimBall, decel: Double): Double? {
        val sumR = a.radius + b.radius - 1e-4
        if (sumR <= 0.0) return null
        val dp = b.pos - a.pos
        val speedA = a.vel.length()
        val speedB = b.vel.length()

        if (speedB <= MIN_SPEED_MPS) {
            // Exact straight-ray circle hit from moving A to stationary B
            if (speedA <= MIN_SPEED_MPS) return null
            val dir = a.vel / speedA
            val proj = dp.dot(dir)
            if (proj <= 0.0) return null
            val perpSq = dp.lengthSquared() - proj * proj
            val sumRSq = sumR * sumR
            if (perpSq >= sumRSq) return null
            val hitDist = proj - sqrt((sumRSq - perpSq).coerceAtLeast(0.0))
            if (hitDist <= 1e-4) return null
            val stopDist = (speedA * speedA) / (2.0 * decel)
            if (hitDist > stopDist) return null
            return timeToTravelDistance(speedA, hitDist, decel)
        }

        // Both moving: common cloth deceleration cancels in relative position (dp - dv * t), since a_A - a_B != 0
        // only when speeds differ; approximate exact over the short moving window using dv = velB - velA.
        val dv = b.vel - a.vel
        val dvSq = dv.lengthSquared()
        if (dvSq <= 1e-9) return null
        val bCoeff = dp.dot(dv)
        if (bCoeff >= 0.0) return null
        val cCoeff = dp.lengthSquared() - sumR * sumR
        if (cCoeff <= 0.0) return 1e-4
        val disc = bCoeff * bCoeff - dvSq * cCoeff
        if (disc < 0.0) return null
        val tHit = (-bCoeff - sqrt(disc)) / dvSq
        val tMax = min(speedA / decel, speedB / decel)
        return if (tHit in 1e-5..tMax) tHit else null
    }

    private fun findEarliestPocketOnRay(
        origin: Vec2,
        dir: Vec2,
        maxTravel: Double,
        pockets: List<PocketSpec>,
    ): Pair<PocketSpec, Double>? {
        if (maxTravel <= 1e-6) return null
        var best: Pair<PocketSpec, Double>? = null
        var bestDist = maxTravel
        for (pocket in pockets) {
            val toPocket = pocket.center - origin
            val proj = toPocket.dot(dir)
            val clampedProj = proj.coerceIn(0.0, maxTravel)
            val closest = origin + dir * clampedProj
            if (pocket.center.distanceTo(closest) <= pocket.acceptanceRadiusMeters) {
                if (clampedProj < bestDist) {
                    bestDist = clampedProj
                    best = pocket to clampedProj
                }
            }
        }
        return best
    }

    private fun detectPocketAtPoint(point: Vec2, pockets: List<PocketSpec>): PocketSpec? =
        pockets.firstOrNull { it.center.distanceTo(point) <= it.acceptanceRadiusMeters }

    private data class RailHit(
        val distance: Double,
        val hitVertical: Boolean,
        val hitHorizontal: Boolean,
    )

    private fun intersectRails(
        origin: Vec2,
        dir: Vec2,
        table: TableSpec,
        radius: Double,
    ): RailHit {
        val minX = radius
        val maxX = (table.lengthMeters - radius).coerceAtLeast(radius)
        val minY = radius
        val maxY = (table.widthMeters - radius).coerceAtLeast(radius)

        val tx = when {
            dir.x > 1e-9 -> ((maxX - origin.x) / dir.x).coerceAtLeast(0.0)
            dir.x < -1e-9 -> ((minX - origin.x) / dir.x).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }
        val ty = when {
            dir.y > 1e-9 -> ((maxY - origin.y) / dir.y).coerceAtLeast(0.0)
            dir.y < -1e-9 -> ((minY - origin.y) / dir.y).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }
        val minDist = min(tx, ty)
        return RailHit(
            distance = minDist,
            hitVertical = abs(tx - minDist) <= 1e-6,
            hitHorizontal = abs(ty - minDist) <= 1e-6,
        )
    }

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
