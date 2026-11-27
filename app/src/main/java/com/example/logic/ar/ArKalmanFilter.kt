package com.example.logic.ar

import com.example.ui.viewmodel.Point3D
import kotlin.math.sqrt

/**
 * High-performance 3D Kalman Filter for AR coordinate smoothing,
 * eliminating jitter, tremor, and flickering during rapid camera movement.
 */
class ArKalmanFilter3D(
    private var processNoise: Float = 0.0015f,
    private var measurementNoise: Float = 0.008f
) {
    private var x = 0.0 // position X
    private var y = 0.0 // position Y
    private var z = 0.0 // position Z
    private var vx = 0.0 // velocity X
    private var vy = 0.0 // velocity Y
    private var vz = 0.0 // velocity Z

    // Error covariance matrix diagonal elements
    private var pX = 0.05
    private var pY = 0.05
    private var pZ = 0.05
    private var pVx = 0.5
    private var pVy = 0.5
    private var pVz = 0.5

    private var isInitialized = false
    private var consecutiveLargeInnovations = 0
    private var lastInnovDirX = 0.0
    private var lastInnovDirY = 0.0
    private var lastInnovDirZ = 0.0

    fun reset() {
        isInitialized = false
        consecutiveLargeInnovations = 0
        lastInnovDirX = 0.0
        lastInnovDirY = 0.0
        lastInnovDirZ = 0.0
    }

    fun update(measurement: Point3D, dt: Float = 0.016f): Point3D {
        if (!isInitialized) {
            x = measurement.x
            y = measurement.y
            z = measurement.z
            vx = 0.0
            vy = 0.0
            vz = 0.0
            pX = 0.02
            pY = 0.02
            pZ = 0.02
            pVx = 0.5
            pVy = 0.5
            pVz = 0.5
            consecutiveLargeInnovations = 0
            lastInnovDirX = 0.0
            lastInnovDirY = 0.0
            lastInnovDirZ = 0.0
            isInitialized = true
            return measurement
        }

        val safeDt = dt.coerceIn(0.004f, 0.1f)
        val dtScale = (safeDt / 0.016f).toDouble()
        val scaledQ = processNoise * dtScale

        // 1. Predict step
        val predX = x + vx * safeDt
        val predY = y + vy * safeDt
        val predZ = z + vz * safeDt
        val predVx = vx
        val predVy = vy
        val predVz = vz

        pX += scaledQ + safeDt * safeDt * pVx
        pY += scaledQ + safeDt * safeDt * pVy
        pZ += scaledQ + safeDt * safeDt * pVz
        pVx = (pVx + scaledQ).coerceIn(0.01, 2.0)
        pVy = (pVy + scaledQ).coerceIn(0.01, 2.0)
        pVz = (pVz + scaledQ).coerceIn(0.01, 2.0)

        // Directional innovation gating: damp isolated single-frame depth/hit spikes,
        // while confirming consistent multi-frame pans for fast convergence.
        val dx = measurement.x - predX
        val dy = measurement.y - predY
        val dz = measurement.z - predZ
        val innovationDist = sqrt(dx * dx + dy * dy + dz * dz)

        val adaptiveR = if (innovationDist > 0.06) {
            val invDist = 1.0 / innovationDist
            val dirX = dx * invDist
            val dirY = dy * invDist
            val dirZ = dz * invDist
            val dot = dirX * lastInnovDirX + dirY * lastInnovDirY + dirZ * lastInnovDirZ

            if (consecutiveLargeInnovations == 0 || dot < 0.5) {
                consecutiveLargeInnovations = 1
                lastInnovDirX = dirX
                lastInnovDirY = dirY
                lastInnovDirZ = dirZ
                measurementNoise * 20.0 // Heavy outlier damping on unconfirmed jump
            } else {
                consecutiveLargeInnovations++
                lastInnovDirX = dirX
                lastInnovDirY = dirY
                lastInnovDirZ = dirZ
                if (consecutiveLargeInnovations >= 2) {
                    pX = maxOf(pX, 0.03)
                    pY = maxOf(pY, 0.03)
                    pZ = maxOf(pZ, 0.03)
                    measurementNoise * 0.20 // Confirmed sustained motion: track immediately
                } else {
                    measurementNoise * 20.0
                }
            }
        } else {
            consecutiveLargeInnovations = 0
            if (innovationDist > 0.02) {
                measurementNoise * 0.40
            } else {
                measurementNoise * 0.90
            }
        }

        // 2. Update step (Kalman gain for position and cross-covariance gain for velocity)
        val sX = pX + adaptiveR
        val sY = pY + adaptiveR
        val sZ = pZ + adaptiveR
        val kX = pX / sX
        val kY = pY / sY
        val kZ = pZ / sZ
        val kVx = (safeDt * pVx) / sX
        val kVy = (safeDt * pVy) / sY
        val kVz = (safeDt * pVz) / sZ

        x = predX + kX * dx
        y = predY + kY * dy
        z = predZ + kZ * dz

        // Frame-rate consistent velocity update with mild damping to prevent overshoot
        vx = ((predVx + kVx * dx) * 0.85).coerceIn(-5.0, 5.0)
        vy = ((predVy + kVy * dy) * 0.85).coerceIn(-5.0, 5.0)
        vz = ((predVz + kVz * dz) * 0.85).coerceIn(-5.0, 5.0)

        pX = ((1.0 - kX) * pX).coerceAtLeast(0.0002)
        pY = ((1.0 - kY) * pY).coerceAtLeast(0.0002)
        pZ = ((1.0 - kZ) * pZ).coerceAtLeast(0.0002)
        pVx = ((1.0 - kVx * safeDt) * pVx).coerceAtLeast(0.01)
        pVy = ((1.0 - kVy * safeDt) * pVy).coerceAtLeast(0.01)
        pVz = ((1.0 - kVz * safeDt) * pVz).coerceAtLeast(0.01)

        return measurement.copy(
            x = x,
            y = y,
            z = z
        )
    }
}
