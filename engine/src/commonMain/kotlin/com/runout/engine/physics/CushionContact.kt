package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.ClothParams
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Ball-cushion contact: the one place that turns a ball's state on arrival at a cushion into its state as it leaves.
 *
 * The impulse model of Han (2005). The cushion nose touches the ball at a point `P` that sits `h - R` above the ball's
 * centre, `h` being the nose height ([ClothParams.cushionNoseHeightRatio] of the ball diameter), so the contact
 * normal is tilted down into the bed by `theta` with `sin(theta) = (h - R) / R`. In the frame of the cushion (`n` into
 * the cushion, `t` along it, `z` up) and with every spin written as a surface speed `W = R * omega`:
 *
 * - the contact point approaches along the contact normal at `c = v_n * cos(theta)` (spin moves `P` only across the
 *   normal), and the normal impulse is `J_r = (1 + e_c) * c` per unit mass;
 * - the contact point slips across the normal at `s_1 = -v_n * sin(theta) - W_t` (in the vertical plane through the
 *   normal) and `s_2 = v_t + W_z * cos(theta) - W_n * sin(theta)` (along the cushion);
 * - a tangential impulse `J_s` at `P` changes that slip by `7/2 * J_s` (a solid sphere, `1/m + R^2/I`), so the contact
 *   stops slipping during the impact if `2/7 * |s| <= f_c * J_r`, and then `J_s = -2/7 * s`; otherwise it slides
 *   throughout and `J_s = -f_c * J_r * s / |s|`;
 * - the impulse changes the centre velocity by `J` and the spin by `5/2 * (r_hat x J)`: `ΔW_n = -5/2 * J_2 * sin(theta)`,
 *   `ΔW_t = -5/2 * J_1`, `ΔW_z = 5/2 * J_2 * cos(theta)`.
 *
 * The vertical part of the impulse is taken by the bed, as in Han's model; friction between ball and bed during the
 * impact (part of the fuller model of Mathavan et al., 2010) is not included. The ball leaves in whatever state the
 * impulse gives, usually sliding, so the cloth then bends its path ([BallMotion]): that is where a following ball
 * comes off long and a drawing ball short, and where running english widens the angle and reverse english narrows it.
 *
 * A ball never leaves a contact still moving into the cushion: the velocity into it is exactly zero or reversed, so
 * a ball that touches a cushion line is not found striking it again at once. A ball resting against a cushion whose
 * spin drives it into the cushion is handled by [pressedAgainst].
 */
object CushionContact {
    /**
     * State of a ball leaving a cushion whose inward unit normal (pointing from the cushion into the table) is
     * [inwardNormal], having arrived in [state]. A ball not moving into the cushion is returned unchanged. A ball
     * arriving at no more than [MIN_APPROACH_SPEED_MPS] keeps everything but its velocity into the cushion, which
     * the cushion stops. When the ball would leave with less than 2 mm/s away from the cushion, that last bit is
     * dropped and it runs along the cushion instead of bouncing on it again and again.
     */
    fun rebound(state: BallState, inwardNormal: Vec2, cloth: ClothParams): BallState {
        val n = (-inwardNormal).normalized()
        val t = perpendicular(n)
        val vn = state.velocity.dot(n)
        if (vn <= 0.0) return state
        val vt = state.velocity.dot(t)
        if (vn <= MIN_APPROACH_SPEED_MPS) return state.copy(velocity = t * vt)
        // R * (omega_x, omega_y) from the surface-velocity form w = R * (omega_y, -omega_x).
        val horizontalSpin = perpendicular(state.spinVelocity)
        val wn = horizontalSpin.dot(n)
        val wt = horizontalSpin.dot(t)
        val wz = state.sideSpinSurfaceSpeedMps

        val ratio = cloth.cushionNoseHeightRatio.coerceIn(
            ClothParams.MIN_CUSHION_NOSE_HEIGHT_RATIO,
            ClothParams.MAX_CUSHION_NOSE_HEIGHT_RATIO,
        )
        val sinT = 2.0 * ratio - 1.0
        val cosT = sqrt(1.0 - sinT * sinT)
        val e = cloth.cushionRestitutionEc.coerceIn(0.0, 1.0)
        val f = cloth.cushionFrictionFc.coerceAtLeast(0.0)

        val normalImpulse = (1.0 + e) * vn * cosT
        val s1 = -vn * sinT - wt
        val s2 = vt + wz * cosT - wn * sinT
        val slip = hypot(s1, s2)
        val j1: Double
        val j2: Double
        if (STICK_IMPULSE_RATIO * slip <= f * normalImpulse) {
            j1 = -STICK_IMPULSE_RATIO * s1
            j2 = -STICK_IMPULSE_RATIO * s2
        } else {
            j1 = -f * normalImpulse * s1 / slip
            j2 = -f * normalImpulse * s2 / slip
        }

        var vnOut = vn - normalImpulse * cosT - j1 * sinT
        if (-vnOut < MIN_LEAVING_SPEED_MPS) vnOut = 0.0
        val vtOut = vt + j2
        val wnOut = wn - SPIN_PER_IMPULSE * j2 * sinT
        val wtOut = wt - SPIN_PER_IMPULSE * j1
        val wzOut = wz + SPIN_PER_IMPULSE * j2 * cosT

        val velocity = n * vnOut + t * vtOut
        val spin = -perpendicular(n * wnOut + t * wtOut)
        return BallState(state.position, velocity, spin, wzOut)
    }

    /**
     * State of a ball that rests against a cushion (inward unit normal [inwardNormal]) with no velocity into or away
     * from it while the cloth's friction pushes it into the cushion: its spin rolls it toward the cushion, so the slip
     * of its contact with the bed has a part pointing away from the cushion.
     *
     * The cushion's reaction passes through the ball's centre, so it cancels the push without turning the ball, and
     * friction at the bed and the cushion wears down the spin that drives the ball into the cushion. That spin is
     * taken off here at once, as an impulse at the contact: the ball keeps its velocity and the rest of its spin, and
     * its slip then lies along the cushion, so the cloth no longer pushes it into the cushion and it slides along it in
     * closed form. The time the cushion would take to wear that spin down (about `2 |u_n| / (5 * muS * g)` for a
     * normal slip `u_n`, a few hundredths of a second for ordinary shots) is not modelled; during it the ball would
     * lose a little less speed along the cushion than it does here. No energy is created.
     *
     * A ball that is not pressed into the cushion is returned unchanged. For a cushion along a table axis the
     * components are set exactly, so the ball is not found pressed again.
     */
    fun pressedAgainst(state: BallState, inwardNormal: Vec2): BallState {
        val nIn = inwardNormal.normalized()
        if (state.velocity.dot(nIn) < 0.0) return state
        val slipIn = state.slipVelocity.dot(nIn)
        if (slipIn <= 0.0) return state
        val velocity = state.velocity - nIn * state.velocity.dot(nIn)
        val spin = state.spinVelocity - nIn * state.spinVelocity.dot(nIn)
        return state.copy(velocity = velocity, spinVelocity = spin)
    }

    /** Sin of the contact normal's tilt below the horizontal for [cloth]'s nose height: `2 * ratio - 1`. */
    fun contactTiltSin(cloth: ClothParams): Double = 2.0 * cloth.cushionNoseHeightRatio.coerceIn(
        ClothParams.MIN_CUSHION_NOSE_HEIGHT_RATIO,
        ClothParams.MAX_CUSHION_NOSE_HEIGHT_RATIO,
    ) - 1.0

    /** [v] turned a quarter turn in the positive sense, exactly (no rounding from a rotation). */
    private fun perpendicular(v: Vec2): Vec2 = Vec2(-v.y, v.x)

    /** Tangential impulse per unit of slip that stops a solid sphere's contact point slipping: `1 / (7/2)`. */
    private const val STICK_IMPULSE_RATIO: Double = 2.0 / 7.0

    /** Change of spin surface speed per unit of tangential impulse at the surface: `R^2 m / I = 5/2`. */
    private const val SPIN_PER_IMPULSE: Double = 2.5

    /**
     * Approach speed at or below which a contact only stops the ball's motion into the cushion, m/s. Such a contact
     * carries no measurable impulse.
     */
    const val MIN_APPROACH_SPEED_MPS: Double = 1e-4

    /** Below this speed away from the cushion the ball is left rolling along it rather than bouncing again. */
    private const val MIN_LEAVING_SPEED_MPS: Double = 2e-3
}
