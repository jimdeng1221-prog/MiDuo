package com.jake.duolauncher

import kotlin.math.*

/** GlassProjection 18489f3 (MIT): ProjectionMath + live optical/pyramid parameters.
 * MiDuo owns its content, so canonical coordinates cover one leaf, not a display mirror.
 * See assets/licenses/GlassProjection-MIT.txt for the upstream copyright notice. */
internal object GlassProjectionMath {
    const val LEAF_WIDTH = .073f
    const val HEIGHT = .16f
    const val EYE_Z = .6f
    const val CONTENT_SCALE = .88f
    const val LEVELS = 7
    const val PYRAMID_VARIANCE = 2.854f
    const val HINGE_DISTANCE = .18f
    const val BLUR_STRENGTH = 1.35f

    fun tiltDegrees(amount: Float): Float = if (amount.isFinite()) (amount.coerceIn(0f, 1f) * 85f) else 0f
    fun crop(amount: Float): Float = tiltDegrees(amount) * .004f
    private fun smooth(value: Float): Float { val t=value.coerceIn(0f,1f); return t*t*(3f-2f*t) }
    /** Undo MiDuo's existing progress ranges before applying the upstream optical angle.
     * This preserves the tested panel/arrival policy, without mistaking half progress for
     * half of an arbitrary 85-degree visual tween. Closure validity remains coordinator-owned. */
    fun opticalTilt(amount: Float, inner: Boolean): Float {
        if(!amount.isFinite())return 0f
        val a=amount.coerceIn(0f,1f)
        val fromEndpoint=a*174f*(if(inner)1f else .68f)
        val tilt=min(85f,fromEndpoint+if(inner)5f else 1f)
        return tilt*smooth(fromEndpoint/if(inner)10f else 8f)
    }
    fun followAngle(current: Float,target: Float,elapsedMs: Long,inner: Boolean,responsiveness: Float): Float {
        if(!target.isFinite())return current
        if(!current.isFinite())return target
        val onset=if(inner)180f-target else target
        val visible=smooth((onset-2f)/2f)*(1f-smooth((onset-9f)/5f))
        var constant=12f+12f*(1f-smooth((onset-10f)/4f))+48f*visible
        constant+=(12f-constant)*responsiveness.coerceIn(0f,1f)
        return current+(target-current)*(1f-exp(-max(0L,elapsedMs)/constant))
    }
    fun levelVariance(level: Int): Float = PYRAMID_VARIANCE * (4.0.pow(level.coerceIn(0, 6)).toFloat() - 1f) / 3f
    data class LevelMix(val low: Int, val high: Int, val weight: Float)
    fun levelMix(sigma: Float): LevelMix {
        val variance = if (sigma.isFinite()) max(0f, sigma).pow(2) else 0f
        val level = (.5f * log2(1f + 3f * variance / PYRAMID_VARIANCE)).coerceIn(0f, 6f)
        val low = floor(level).toInt(); val high = min(low + 1, 6)
        val weight = ((variance - levelVariance(low)) / max(levelVariance(high) - levelVariance(low), .0001f)).coerceIn(0f, 1f)
        return LevelMix(low, high, weight)
    }
    data class Ray(val x: Double, val y: Double, val distance: Double)
    fun paperPoint(x: Double, y: Double, amount: Float, inner: Boolean): Ray {
        val theta = Math.toRadians(tiltDegrees(amount).toDouble())
        val px = x * cos(theta); val pz = abs(x) * sin(theta)
        val eye = if (inner) 0.0 else LEAF_WIDTH * .5
        val k = EYE_Z / (EYE_Z - pz)
        val qx = eye + (px - eye) * k; val qy = y * k
        return Ray(qx, qy, sqrt((qx - px).pow(2) + (qy - y).pow(2) + pz.pow(2)))
    }
    /** Physical corner radii are evaluated in the ray-projected paper coordinates. */
    fun cornerInset(radius: Float, distanceFromSide: Float): Float {
        val r = max(radius, 0f); val dx = max(r - distanceFromSide, 0f)
        return r - sqrt(max(r * r - dx * dx, 0f))
    }
}

/** Upstream AdaptiveAngleFollow: fast deliberate runs shorten lag, alternating 1-degree
 * jitter does not. Frame-time based and clamped interpolation; never predicts a future pose. */
internal class GlassAngleFollow {
    private var frameAt=0L; private var changeAt=0L; private var runAt=0L; private var boostAt=0L
    private var target=Float.NaN; private var runDistance=0f; private var boost=0f; private var direction=0
    fun reset(now: Long,value: Float) {
        frameAt=now; changeAt=now; runAt=now; boostAt=now; target=value; runDistance=0f; boost=0f; direction=0
    }
    fun update(now: Long,current: Float,next: Float,inner: Boolean): Float {
        if(!next.isFinite())return current
        if(!target.isFinite()){reset(now,next); return next}
        val elapsed=max(0L,now-frameAt); val delta=next-target
        var strength=boost*max(0f,1f-(now-boostAt)/120f)
        if(delta!=0f) {
            val sign=if(delta>0f)1 else -1
            if(sign!=direction || now-changeAt>120L || now-runAt>120L){runAt=changeAt;runDistance=0f}
            runDistance+=abs(delta);direction=sign
            val speed=runDistance*1000f/max(16L,now-runAt)
            var measured=if(runDistance>=3f)((speed-30f)/90f).coerceIn(0f,1f) else 0f
            if(abs(delta)>=4f)measured=1f
            strength=max(strength,measured);boost=strength;boostAt=now;target=next;changeAt=now
        }
        frameAt=now
        return GlassProjectionMath.followAngle(current,next,elapsed,inner,strength)
    }
}
