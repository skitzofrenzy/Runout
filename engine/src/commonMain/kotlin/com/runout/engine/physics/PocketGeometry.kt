package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.PocketKind
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.PocketSpec
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the geometry of a real pocket ([PocketSpec]: two jaw tips, the mouth between them, the hole behind the nose
 * line) says about a ball heading for it. Pure functions, SI units.
 *
 * A ball drops when its centre passes between the jaw tips keeping a clearance of `jawTolerance * R` from each
 * ([entryWindow]); the tolerance depends on how fast it arrives ([jawTolerance]). The cushion noses are straight and
 * end at the tips, so a straight path that clears both tips by `R` also clears both cushions.
 */
object PocketGeometry {
    /** Arrival speed assumed by callers that have no speed of their own (position maps, pocket counts), m/s. */
    const val NOMINAL_ARRIVAL_SPEED_MPS: Double = 1.5

    /**
     * The directions from a ball centre that carry it into a pocket.
     *
     * [limitA] and [limitB] are the inner tangents from the ball centre to circles of radius `jawTolerance * R`
     * around [PocketSpec.jawTipA] and [PocketSpec.jawTipB]; every direction between them passes both tips with at
     * least that clearance. [bisector] lies halfway between them and [halfAngleRadians] is half the angle they span.
     * [isOpen] is false when the limits cross (no direction clears both tips), when the ball is inside a clearance
     * circle, or when it is not in front of the mouth; [halfAngleRadians] is then `0.0`.
     */
    data class EntryWindow(
        val isOpen: Boolean,
        val limitA: Vec2,
        val limitB: Vec2,
        val bisector: Vec2,
        val halfAngleRadians: Double,
    )

    /** Window of directions from [ballCenter] into [pocket] for a ball of radius [ballRadius]; see [EntryWindow]. */
    fun entryWindow(
        ballCenter: Vec2,
        pocket: PocketSpec,
        ballRadius: Double,
        jawTolerance: Double = 1.0,
    ): EntryWindow {
        val toA = pocket.jawTipA - ballCenter
        val toB = pocket.jawTipB - ballCenter
        val dA = toA.length()
        val dB = toB.length()
        val towardMouth = (pocket.mouthCenter - ballCenter).normalized()
        val inFront = (ballCenter - pocket.mouthCenter).dot(pocket.axisIntoPocket) < 0.0
        if (dA < 1e-12 || dB < 1e-12) return EntryWindow(false, towardMouth, towardMouth, towardMouth, 0.0)
        val dirA = toA / dA
        val dirB = toB / dB
        val span = dirA.angleTo(dirB)
        val sign = if (span >= 0.0) 1.0 else -1.0
        val clearance = jawTolerance.coerceAtLeast(0.0) * ballRadius
        if (!inFront || clearance >= dA || clearance >= dB) {
            return EntryWindow(false, dirA, dirB, dirA.rotate(span * 0.5), 0.0)
        }
        val shrinkA = asin(clearance / dA)
        val shrinkB = asin(clearance / dB)
        val limitA = dirA.rotate(sign * shrinkA)
        val limitB = dirB.rotate(-sign * shrinkB)
        val width = abs(span) - shrinkA - shrinkB
        val bisector = limitA.rotate(sign * width * 0.5)
        return EntryWindow(
            isOpen = width > 0.0,
            limitA = limitA,
            limitB = limitB,
            bisector = bisector,
            halfAngleRadians = (width * 0.5).coerceAtLeast(0.0),
        )
    }

    /**
     * Where to send a ball at [ballCenter] into [pocket]: the point where the bisector of its [entryWindow] crosses the
     * mouth line. On the pocket's axis that is [PocketSpec.mouthCenter]; the shallower the approach, the farther it
     * moves from the near jaw. The window is taken at [jawTolerance] if it is open there, else at the pocket's slow
     * tolerance; when even that is closed the answer is [PocketSpec.mouthCenter].
     */
    fun aimPoint(
        ballCenter: Vec2,
        pocket: PocketSpec,
        ballRadius: Double,
        jawTolerance: Double = 1.0,
    ): Vec2 {
        var window = entryWindow(ballCenter, pocket, ballRadius, jawTolerance)
        if (!window.isOpen) window = entryWindow(ballCenter, pocket, ballRadius, pocket.jawToleranceSlow)
        if (!window.isOpen) return pocket.mouthCenter
        return rayLineIntersection(ballCenter, window.bisector, pocket.jawTipA, pocket.jawTipB) ?: pocket.mouthCenter
    }

    /**
     * Clearance, as a fraction of the ball radius, that a ball arriving at [arrivalSpeedMps] must keep from each jaw
     * tip of [pocket] to drop: [PocketSpec.jawToleranceSlow] at or below [PocketPreset.SLOW_ARRIVAL_SPEED_MPS],
     * [PocketSpec.jawToleranceFast] at or above [PocketPreset.FAST_ARRIVAL_SPEED_MPS], linear in between.
     */
    fun jawTolerance(pocket: PocketSpec, arrivalSpeedMps: Double): Double {
        val slow = PocketPreset.SLOW_ARRIVAL_SPEED_MPS
        val fast = PocketPreset.FAST_ARRIVAL_SPEED_MPS
        val f = ((arrivalSpeedMps - slow) / (fast - slow)).coerceIn(0.0, 1.0)
        return pocket.jawToleranceSlow + f * (pocket.jawToleranceFast - pocket.jawToleranceSlow)
    }

    /**
     * The line a ball's centre crosses when it enters [pocket]: the mouth itself for a corner pocket (its middle part
     * lies on the cloth); for a side pocket the mouth moved toward the table by [ballRadius], because the centre of a
     * ball cannot get closer than `R` to the nose line except inside the mouth.
     */
    fun captureLine(pocket: PocketSpec, ballRadius: Double): Pair<Vec2, Vec2> = when (pocket.kind) {
        PocketKind.CORNER -> pocket.jawTipA to pocket.jawTipB
        PocketKind.SIDE -> {
            val shift = pocket.axisIntoPocket * -ballRadius
            (pocket.jawTipA + shift) to (pocket.jawTipB + shift)
        }
    }

    /** Distance from [point] to [tip] measured along the ray from [point] in [direction]: its closest approach. */
    fun rayClearance(point: Vec2, direction: Vec2, tip: Vec2): Double {
        val d = direction.normalized()
        val toTip = tip - point
        return if (toTip.dot(d) <= 0.0) toTip.length() else abs(toTip.cross(d))
    }

    /** Whether a ball at [point] moving along [direction] passes both jaw tips of [pocket] by `tolerance * R`. */
    fun clearsJaws(point: Vec2, direction: Vec2, pocket: PocketSpec, ballRadius: Double, tolerance: Double): Boolean {
        val needed = tolerance * ballRadius
        return rayClearance(point, direction, pocket.jawTipA) >= needed &&
            rayClearance(point, direction, pocket.jawTipB) >= needed
    }

    /** Result of [captureOnSegment]: the pocket entered, where the centre crossed its capture line, and whether it dropped. */
    data class Capture(
        val pocket: PocketSpec,
        val point: Vec2,
        val fraction: Double,
        val potted: Boolean,
    )

    /**
     * First pocket whose capture line ([captureLine]) a ball centre moving straight from [start] to [end] crosses on
     * its way in. The ball drops if its line clears both jaw tips by [jawTolerance] at [arrivalSpeedMps]; otherwise
     * it is a jaw hit ([Capture.potted] false). `null` if the segment enters no pocket.
     */
    fun captureOnSegment(
        start: Vec2,
        end: Vec2,
        pockets: List<PocketSpec>,
        ballRadius: Double,
        arrivalSpeedMps: Double = NOMINAL_ARRIVAL_SPEED_MPS,
    ): Capture? {
        val seg = end - start
        if (seg.lengthSquared() < 1e-24) {
            val inside = pockets.firstOrNull { containsRestingBall(start, it, ballRadius) } ?: return null
            return Capture(inside, start, 0.0, true)
        }
        var best: Capture? = null
        for (pocket in pockets) {
            if (seg.dot(pocket.axisIntoPocket) <= 0.0) continue
            val (a, b) = captureLine(pocket, ballRadius)
            val t = segmentIntersection(start, end, a, b) ?: continue
            if (best != null && t >= best.fraction) continue
            val point = start + seg * t
            val potted = clearsJaws(point, seg, pocket, ballRadius, jawTolerance(pocket, arrivalSpeedMps))
            best = Capture(pocket, point, t, potted)
        }
        return best
    }

    /**
     * Whether a ball centred at [point] is already in [pocket]: past its capture line within the width of the mouth,
     * or over the hole.
     */
    fun containsRestingBall(point: Vec2, pocket: PocketSpec, ballRadius: Double): Boolean {
        if (point.distanceTo(pocket.holeCenter) <= pocket.holeRadiusMeters) return true
        val (a, b) = captureLine(pocket, ballRadius)
        val mid = (a + b) * 0.5
        val across = (b - a).normalized()
        val past = (point - mid).dot(pocket.axisIntoPocket) > 1e-9
        val lateral = abs((point - mid).dot(across)) <= pocket.mouthWidthMeters * 0.5
        return past && lateral
    }

    /**
     * Whether [contactPoint], a point on the cushion-nose rectangle, lies in the mouth of [pocket], where there is no
     * cushion: between the jaw tips and on the pocket side of the mouth line.
     */
    fun isInMouth(contactPoint: Vec2, pocket: PocketSpec): Boolean {
        val ab = pocket.jawTipB - pocket.jawTipA
        val t = (contactPoint - pocket.jawTipA).dot(ab) / ab.lengthSquared()
        if (t < -1e-9 || t > 1.0 + 1e-9) return false
        return (contactPoint - pocket.mouthCenter).dot(pocket.axisIntoPocket) >= -1e-9
    }

    /**
     * Distance a ball centre at [from] travels along [direction] before it reaches the capture line of [pocket], or
     * `null` if it never does.
     */
    fun distanceToCaptureLine(from: Vec2, direction: Vec2, pocket: PocketSpec, ballRadius: Double): Double? {
        val (a, b) = captureLine(pocket, ballRadius)
        val d = direction.normalized()
        val p = rayLineIntersection(from, d, a, b) ?: return null
        return (p - from).dot(d).takeIf { it >= 0.0 }
    }

    /**
     * Unit normal of the facing that starts at [tip] (one of [pocket]'s jaw tips), pointing out of the cushion into
     * the mouth. The facing leaves the tip at [PocketSpec.facingAngleRad] from the cushion nose, measured through the
     * cushion.
     */
    fun facingNormal(pocket: PocketSpec, tip: Vec2): Vec2 {
        val noseAway = when (pocket.kind) {
            PocketKind.SIDE -> (tip - pocket.mouthCenter).normalized()
            PocketKind.CORNER -> {
                val corner = pocket.mouthCenter + pocket.axisIntoPocket * (pocket.mouthWidthMeters * 0.5)
                (tip - corner).normalized()
            }
        }
        var outward = Vec2(-noseAway.y, noseAway.x)
        if (outward.dot(pocket.axisIntoPocket) < 0.0) outward = -outward
        val f = pocket.facingAngleRad
        val facing = noseAway * cos(f) + outward * sin(f)
        var normal = Vec2(-facing.y, facing.x)
        if (normal.dot(pocket.mouthCenter - tip) < 0.0) normal = -normal
        return normal
    }

    /** Parameter in `[0, 1]` along [p0]-[p1] where it crosses segment [q0]-[q1], or `null`. */
    private fun segmentIntersection(p0: Vec2, p1: Vec2, q0: Vec2, q1: Vec2): Double? {
        val r = p1 - p0
        val s = q1 - q0
        val denom = r.cross(s)
        if (abs(denom) < 1e-18) return null
        val qp = q0 - p0
        val t = qp.cross(s) / denom
        val u = qp.cross(r) / denom
        return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
    }

    /** Point where the ray from [origin] along [direction] meets the infinite line through [a] and [b], or `null`. */
    private fun rayLineIntersection(origin: Vec2, direction: Vec2, a: Vec2, b: Vec2): Vec2? {
        val s = b - a
        val denom = direction.cross(s)
        if (abs(denom) < 1e-18) return null
        val t = (a - origin).cross(s) / denom
        return if (t < 0.0) null else origin + direction * t
    }
}
