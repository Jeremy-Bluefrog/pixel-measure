package com.example.logic.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Allocation-free 1€ Filter (Casiez et al., CHI 2012) tuned for high-frequency AR telemetry.
 * - Eliminates hand tremor & depth jitter when stationary via low [minCutoff] Hz.
 * - Adapts cutoff dynamically proportional to derivative speed via [beta], eliminating lag during fast sweeps.
 * - Frame-rate invariant across 30Hz / 60Hz / 90Hz / 120Hz displays using exact [dtSec].
 */
class OneEuroFilterScalar(
    private val minCutoff: Double = 1.6,
    private val beta: Double = 24.0,
    private val dCutoff: Double = 1.0
) {
    private var initialized = false
    private var xPrev = 0.0
    private var dxPrev = 0.0

    fun filter(value: Double, dtSec: Double): Double {
        if (!value.isFinite()) {
            return if (initialized) xPrev else 0.0
        }
        if (!initialized) {
            xPrev = value
            dxPrev = 0.0
            initialized = true
            return value
        }
        if (!dtSec.isFinite() || dtSec <= 1e-6) {
            return xPrev
        }
        val dt = dtSec.coerceIn(1e-4, 0.25)
        val dx = (value - xPrev) / dt
        val alphaD = smoothingAlpha(dCutoff, dt)
        val edx = dxPrev + alphaD * (dx - dxPrev)
        val cutoff = (minCutoff + beta * abs(edx)).coerceIn(0.1, 120.0)
        val alpha = smoothingAlpha(cutoff, dt)
        val xHat = xPrev + alpha * (value - xPrev)
        xPrev = xHat
        dxPrev = edx
        return xHat
    }

    fun reset() {
        initialized = false
        xPrev = 0.0
        dxPrev = 0.0
    }

    companion object {
        fun smoothingAlpha(cutoffHz: Double, dtSec: Double): Double {
            val safeCutoff = cutoffHz.coerceAtLeast(0.01)
            val safeDt = dtSec.coerceIn(1e-4, 0.25)
            val tau = 1.0 / (2.0 * PI * safeCutoff)
            return 1.0 / (1.0 + tau / safeDt)
        }
    }
}

/**
 * Isotropic zero-allocation 3D 1€ Filter.
 * Uses Euclidean 3D speed magnitude rather than independent per-axis cutoffs so diagonal camera pans
 * remain completely free of directional skew or axis lag.
 */
class OneEuroFilter3D(
    private val minCutoff: Double = 1.6,
    private val beta: Double = 24.0,
    private val dCutoff: Double = 1.0,
    private val jumpResetDistance: Double = 0.50
) {
    private var initialized = false
    var x: Double = 0.0
        private set
    var y: Double = 0.0
        private set
    var z: Double = 0.0
        private set

    private var dxPrev = 0.0
    private var dyPrev = 0.0
    private var dzPrev = 0.0

    fun filterInto(
        targetX: Double,
        targetY: Double,
        targetZ: Double,
        dtSec: Double,
        outXYZ: DoubleArray
    ): Boolean {
        if (outXYZ.size < 3) return false
        if (!targetX.isFinite() || !targetY.isFinite() || !targetZ.isFinite()) {
            if (!initialized) return false
            outXYZ[0] = x; outXYZ[1] = y; outXYZ[2] = z
            return true
        }
        if (!initialized) {
            x = targetX; y = targetY; z = targetZ
            dxPrev = 0.0; dyPrev = 0.0; dzPrev = 0.0
            initialized = true
            outXYZ[0] = x; outXYZ[1] = y; outXYZ[2] = z
            return true
        }

        val jumpDx = targetX - x
        val jumpDy = targetY - y
        val jumpDz = targetZ - z
        val distSq = jumpDx * jumpDx + jumpDy * jumpDy + jumpDz * jumpDz
        if (jumpResetDistance > 0.0 && distSq > jumpResetDistance * jumpResetDistance) {
            x = targetX; y = targetY; z = targetZ
            dxPrev = 0.0; dyPrev = 0.0; dzPrev = 0.0
            outXYZ[0] = x; outXYZ[1] = y; outXYZ[2] = z
            return true
        }

        if (!dtSec.isFinite() || dtSec <= 1e-6) {
            outXYZ[0] = x; outXYZ[1] = y; outXYZ[2] = z
            return true
        }

        val dt = dtSec.coerceIn(1e-4, 0.25)
        val invDt = 1.0 / dt
        val vx = jumpDx * invDt
        val vy = jumpDy * invDt
        val vz = jumpDz * invDt

        val alphaD = OneEuroFilterScalar.smoothingAlpha(dCutoff, dt)
        val edx = dxPrev + alphaD * (vx - dxPrev)
        val edy = dyPrev + alphaD * (vy - dyPrev)
        val edz = dzPrev + alphaD * (vz - dzPrev)

        val speed3D = sqrt(edx * edx + edy * edy + edz * edz)
        val cutoff = (minCutoff + beta * speed3D).coerceIn(0.1, 120.0)
        val alpha = OneEuroFilterScalar.smoothingAlpha(cutoff, dt)

        x += alpha * jumpDx
        y += alpha * jumpDy
        z += alpha * jumpDz
        dxPrev = edx
        dyPrev = edy
        dzPrev = edz

        outXYZ[0] = x
        outXYZ[1] = y
        outXYZ[2] = z
        return true
    }

    fun reset() {
        initialized = false
        x = 0.0; y = 0.0; z = 0.0
        dxPrev = 0.0; dyPrev = 0.0; dzPrev = 0.0
    }
}

/**
 * Zero-allocation 1€-driven geodesic Spherical Linear Interpolation (SLERP) for 3D unit surface normals.
 * Combines 1€ angular-velocity cutoff adaptation with true spherical shortest-arc rotation so tilted
 * rings maintain constant angular speed and unit magnitude across 30/60/90/120Hz displays.
 */
class OneEuroNormalSlerp(
    private val minCutoff: Double = 2.4,
    private val beta: Double = 6.5,
    private val dCutoff: Double = 1.2
) {
    private var initialized = false
    val currentNormal = FloatArray(3).apply { this[1] = 1f }
    private val targetScratch = FloatArray(3)
    private var omegaPrev = 0.0

    fun isInitialized(): Boolean = initialized

    fun update(
        targetNx: Float,
        targetNy: Float,
        targetNz: Float,
        dtSec: Double,
        stabilizeHorizontal: Boolean = true,
        outNormal: FloatArray = currentNormal
    ): Boolean {
        if (outNormal.size < 3) return false
        var tx = targetNx.toDouble()
        var ty = targetNy.toDouble()
        var tz = targetNz.toDouble()
        val len = sqrt(tx * tx + ty * ty + tz * tz)
        if (!len.isFinite() || len < 1e-5) {
            if (!initialized) return false
            outNormal[0] = currentNormal[0]
            outNormal[1] = currentNormal[1]
            outNormal[2] = currentNormal[2]
            return true
        }
        tx /= len; ty /= len; tz /= len

        if (stabilizeHorizontal) {
            ArMath.stabilizeHorizontalNormal(tx, ty, tz, targetScratch)
            tx = targetScratch[0].toDouble()
            ty = targetScratch[1].toDouble()
            tz = targetScratch[2].toDouble()
        }

        if (!initialized) {
            currentNormal[0] = tx.toFloat()
            currentNormal[1] = ty.toFloat()
            currentNormal[2] = tz.toFloat()
            omegaPrev = 0.0
            initialized = true
            outNormal[0] = currentNormal[0]
            outNormal[1] = currentNormal[1]
            outNormal[2] = currentNormal[2]
            return true
        }

        if (!dtSec.isFinite() || dtSec <= 1e-6) {
            outNormal[0] = currentNormal[0]
            outNormal[1] = currentNormal[1]
            outNormal[2] = currentNormal[2]
            return true
        }

        val dt = dtSec.coerceIn(1e-4, 0.25)
        val dotRaw = (currentNormal[0] * tx + currentNormal[1] * ty + currentNormal[2] * tz).coerceIn(-1.0, 1.0)
        // Align to shortest hemisphere before angle computation
        val absDot = abs(dotRaw).coerceIn(0.0, 1.0)
        val theta = acos(absDot)
        val rawOmega = theta / dt
        val alphaD = OneEuroFilterScalar.smoothingAlpha(dCutoff, dt)
        omegaPrev += alphaD * (rawOmega - omegaPrev)

        val cutoff = (minCutoff + beta * omegaPrev).coerceIn(0.2, 90.0)
        val slerpT = OneEuroFilterScalar.smoothingAlpha(cutoff, dt).toFloat()

        ArMath.slerpUnitNormal(
            cnx = currentNormal[0],
            cny = currentNormal[1],
            cnz = currentNormal[2],
            tnx = tx.toFloat(),
            tny = ty.toFloat(),
            tnz = tz.toFloat(),
            t = slerpT,
            outNormal = currentNormal
        )
        outNormal[0] = currentNormal[0]
        outNormal[1] = currentNormal[1]
        outNormal[2] = currentNormal[2]
        return true
    }

    fun reset() {
        initialized = false
        currentNormal[0] = 0f
        currentNormal[1] = 1f
        currentNormal[2] = 0f
        omegaPrev = 0.0
    }
}
