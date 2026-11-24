package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinPhysics
import kotlin.math.PI

/**
 * A 45° cut into the foot-left pocket whose stun line runs into the foot-right pocket.
 *
 * Seen from a ghost ball on the long centre line, the two foot pockets are 90° apart exactly at `x = 2.54 - 0.635`
 * (Thales), where the tangent line of a cut into one pocket points at the other. A real stun shot leaves a few degrees
 * inside the tangent line (restitution keeps part of the approach speed along the line of centres, and the throw turns
 * the line of centres), so the ghost ball has to sit a little farther from the foot cushion, where the pockets are
 * less than 90° apart. The position is found from the engine's own contact law by bisection along the centre line.
 */
internal object StunIntoCornerFixture {
    class Setup(val cueBall: Ball, val objectBall: Ball, val ghost: Vec2)

    fun build(
        table: TableSpec,
        objectBallNumber: Int,
        cueSpeedMps: Double,
        cueToGhostMeters: Double = 0.60,
    ): Setup {
        val r = table.ballRadiusMeters
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)

        fun setupAt(x: Double): Setup {
            val ghost = Vec2(x, table.widthMeters * 0.5)
            val toFootLeft = (footLeft.mouthCenter - ghost).normalized()
            val objectBall = Ball(number = objectBallNumber, position = ghost + toFootLeft * (2.0 * r), radiusMeters = r)
            val aimDir = toFootLeft.rotate(PI / 4.0)
            val cueBall = Ball(number = 0, position = ghost - aimDir * cueToGhostMeters, radiusMeters = r)
            return Setup(cueBall, objectBall, ghost)
        }

        // Signed angle from the stun line to the line from the ghost ball to the foot-right pocket.
        fun miss(x: Double): Double {
            val s = setupAt(x)
            val aim = GhostBallAiming.computeAimGeometry(s.cueBall, s.objectBall, footLeft, table = table)
            val tip = SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, cueSpeedMps, aim.cueToGhostDistanceMeters, table)
                ?: error("no stun tip at $cueSpeedMps m/s over ${aim.cueToGhostDistanceMeters} m")
            val path = SpinPhysics.traceCueBallPathWithSpin(aim, cueSpeedMps, tip, table, r)
            val stunLine = aim.aimDirection.rotate(path.initialDeflectionAngleRad)
            return stunLine.angleTo(footRight.mouthCenter - aim.ghostBallCenter)
        }

        var lo = table.lengthMeters - 0.635 - 0.40
        var hi = table.lengthMeters - 0.635
        val missLo = miss(lo)
        check(missLo * miss(hi) < 0.0) { "the stun line does not cross the pocket between the bracket ends" }
        repeat(50) {
            val mid = 0.5 * (lo + hi)
            if (miss(mid) * missLo > 0.0) lo = mid else hi = mid
        }
        return setupAt(0.5 * (lo + hi))
    }
}
