package com.example.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.logic.ar.ArMath
import com.example.logic.ar.OneEuroFilter3D
import com.example.logic.ar.OneEuroFilterScalar
import com.example.logic.ar.OneEuroNormalSlerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Zero-allocation state & scratch buffer holder for 60/90/120Hz 3D circle and reticle rendering.
 * Combines dt-compensated 1€ filters, geodesic unit-normal SLERP, and camera-space depth buffers.
 */
class ArCircle3DRenderScratch {
    var lastDrawTimeNs: Long = 0L

    // 1€ filters & SLERP orienters (tuned in physical meters & rad/s)
    val reticlePosFilter = OneEuroFilter3D(minCutoff = 3.2, beta = 42.0, dCutoff = 1.2, jumpResetDistance = 0.35)
    val reticleNormalSlerp = OneEuroNormalSlerp(minCutoff = 2.6, beta = 7.0, dCutoff = 1.2)
    val reticleRadiusFilter = OneEuroFilterScalar(minCutoff = 2.6, beta = 36.0, dCutoff = 1.0)

    val circleCenterFilter = OneEuroFilter3D(minCutoff = 1.8, beta = 28.0, dCutoff = 1.0, jumpResetDistance = 0.60)
    val circleRadiusFilter = OneEuroFilterScalar(minCutoff = 2.0, beta = 30.0, dCutoff = 1.0)
    val circleNormalSlerp = OneEuroNormalSlerp(minCutoff = 2.4, beta = 6.5, dCutoff = 1.2)

    val reticlePosOut = DoubleArray(3)
    val circleCenterOut = DoubleArray(3)
    val circleNormalScratch = FloatArray(3)

    // Pre-allocated projection & geometry buffers for 3D reticle (36 segments)
    val reticleBasis = DoubleArray(9)
    val reticleOuterX = FloatArray(36)
    val reticleOuterY = FloatArray(36)
    val reticleOuterDepth = FloatArray(36)
    val reticleShadowX = FloatArray(36)
    val reticleShadowY = FloatArray(36)
    val reticleInnerX = FloatArray(36)
    val reticleInnerY = FloatArray(36)
    val reticleInnerDepth = FloatArray(36)
    val reticleOuterDepthBounds = FloatArray(2)
    val reticleInnerDepthBounds = FloatArray(2)
    val reticleOuterPath = Path()
    val reticleShadowPath = Path()
    val reticleInnerPath = Path()
    val reticleSweepPath = Path()

    // Pre-allocated projection & geometry buffers for SubMode-4 3D measurement circle (48 segments)
    val circleBasis = DoubleArray(9)
    val circleX = FloatArray(48)
    val circleY = FloatArray(48)
    val circleDepth = FloatArray(48)
    val circleShadowX = FloatArray(48)
    val circleShadowY = FloatArray(48)
    val circleDepthBounds = FloatArray(2)
    val circlePath = Path()
    val circleShadowPath = Path()
    val circleBloomPath = Path()

    // Shared coordinate projection pairs (zero allocation)
    val projA = FloatArray(2)
    val projB = FloatArray(2)
    val projC = FloatArray(2)

    fun stepDtSec(): Double {
        val nowNs = System.nanoTime()
        val prevNs = lastDrawTimeNs
        lastDrawTimeNs = nowNs
        if (prevNs <= 0L || nowNs <= prevNs) return 0.016
        return ((nowNs - prevNs) / 1_000_000_000.0).coerceIn(0.004, 0.050)
    }

    fun resetCircleTracking() {
        circleCenterFilter.reset()
        circleRadiusFilter.reset()
        circleNormalSlerp.reset()
    }

    fun resetReticleTracking() {
        reticlePosFilter.reset()
        reticleNormalSlerp.reset()
        reticleRadiusFilter.reset()
    }
}

/**
 * Renders a perspective depth-modulated 3D perimeter loop where near camera-facing segments gain
 * thickness, luminance, and a crisp specular rim crest while receding rear arcs attenuate naturally.
 */
internal fun DrawScope.drawDepthModulated3DRing(
    xs: FloatArray,
    ys: FloatArray,
    clipW: FloatArray,
    depthBounds: FloatArray,
    count: Int,
    baseColor: Color,
    baseStrokePx: Float,
    bloomAlpha: Float = 0.24f,
    coreAlpha: Float = 0.96f,
    specularAlpha: Float = 0.55f
) {
    if (count < 3) return
    val minW = depthBounds[0]
    val spanW = depthBounds[1] - minW
    val invSpan = if (spanW > 0.003f && spanW.isFinite()) 1f / spanW else 0f

    for (i in 0 until count) {
        val next = if (i + 1 < count) i + 1 else 0
        val segDepth = (clipW[i] + clipW[next]) * 0.5f
        val dNorm = if (invSpan > 0f) ((segDepth - minW) * invSpan).coerceIn(0f, 1f) else 0.25f

        val widthScale = 1.24f - 0.50f * dNorm // Near: 1.24x, Far: 0.74x
        val depthAlpha = 1.00f - 0.44f * dNorm // Near: 1.00x, Far: 0.56x

        val pStart = Offset(xs[i], ys[i])
        val pEnd = Offset(xs[next], ys[next])

        if (bloomAlpha > 0.01f) {
            drawLine(
                color = baseColor.copy(alpha = (bloomAlpha * depthAlpha).coerceIn(0f, 1f)),
                start = pStart,
                end = pEnd,
                strokeWidth = baseStrokePx * 2.15f * widthScale,
                cap = StrokeCap.Round
            )
        }

        drawLine(
            color = baseColor.copy(alpha = (coreAlpha * depthAlpha).coerceIn(0f, 1f)),
            start = pStart,
            end = pEnd,
            strokeWidth = baseStrokePx * widthScale,
            cap = StrokeCap.Round
        )

        // Subtle front-arc specular rim crest facing camera
        if (specularAlpha > 0.01f && dNorm < 0.42f) {
            val specStrength = ((0.42f - dNorm) / 0.42f)
            drawLine(
                color = Color.White.copy(alpha = (specularAlpha * specStrength).coerceIn(0f, 1f)),
                start = pStart,
                end = pEnd,
                strokeWidth = (baseStrokePx * 0.42f * widthScale).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Projects and renders an authentic 3D surface-aligned reticle for ALL 4 styles
 * (`DOUBLE_RING`, `PRECISION_CROSSHAIR`, `HOLOGRAPHIC_RADAR`, `MINIMAL_DOT`) with:
 * - Soft contact shadow ring anchored beneath the plane
 * - Depth-modulated 3D torus rim & front-arc specular highlight
 * - Elevated gimbal inner ring + vertical 3D surface-normal parallax pin
 */
internal fun DrawScope.draw3DSurfaceReticle(
    scratch: ArCircle3DRenderScratch,
    centerX: Double,
    centerY: Double,
    centerZ: Double,
    outerRadiusM: Double,
    style: String,
    primaryColor: Color,
    accentColor: Color,
    isSnapped: Boolean,
    dashPhase: Float,
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray,
    screenW: Int,
    screenH: Int,
    isHorizontallyMirrored: Boolean
): Boolean {
    val nx = scratch.reticleBasis[0]
    val ny = scratch.reticleBasis[1]
    val nz = scratch.reticleBasis[2]
    val ux = scratch.reticleBasis[3]
    val uy = scratch.reticleBasis[4]
    val uz = scratch.reticleBasis[5]
    val vx = scratch.reticleBasis[6]
    val vy = scratch.reticleBasis[7]
    val vz = scratch.reticleBasis[8]

    val activeColor = if (isSnapped) accentColor else primaryColor
    val segs = 36
    val strokePx = 2.4.dp.toPx()

    // 1. Project main grounded outer 3D ring with per-vertex camera depth
    val outerCnt = ArMath.projectCircle3DWithDepthToScreen(
        cx = centerX,
        cy = centerY,
        cz = centerZ,
        radiusMeters = outerRadiusM,
        basis = scratch.reticleBasis,
        segments = segs,
        viewMatrix = viewMatrix,
        projectionMatrix = projectionMatrix,
        screenWidth = screenW,
        screenHeight = screenH,
        outScreenX = scratch.reticleOuterX,
        outScreenY = scratch.reticleOuterY,
        outClipW = scratch.reticleOuterDepth,
        outDepthBounds = scratch.reticleOuterDepthBounds,
        isHorizontallyMirrored = isHorizontallyMirrored
    )
    if (outerCnt < 3) return false

    // Build outer closed loop for soft translucent disc fill
    scratch.reticleOuterPath.reset()
    scratch.reticleOuterPath.moveTo(scratch.reticleOuterX[0], scratch.reticleOuterY[0])
    for (i in 1 until outerCnt) {
        scratch.reticleOuterPath.lineTo(scratch.reticleOuterX[i], scratch.reticleOuterY[i])
    }
    scratch.reticleOuterPath.close()

    // 2. Grounded Contact Shadow Offset (-N * 3.5% of radius) anchors ring onto real physical surface
    val shadowDropM = (outerRadiusM * 0.038).coerceIn(0.001, 0.008)
    val shadowCnt = ArMath.projectCircle3DToScreen(
        cx = centerX - nx * shadowDropM,
        cy = centerY - ny * shadowDropM,
        cz = centerZ - nz * shadowDropM,
        radiusMeters = outerRadiusM * 1.025,
        basis = scratch.reticleBasis,
        segments = segs,
        viewMatrix = viewMatrix,
        projectionMatrix = projectionMatrix,
        screenWidth = screenW,
        screenHeight = screenH,
        outScreenX = scratch.reticleShadowX,
        outScreenY = scratch.reticleShadowY,
        scratchXY = scratch.projA,
        isHorizontallyMirrored = isHorizontallyMirrored
    )
    if (shadowCnt >= 3) {
        scratch.reticleShadowPath.reset()
        scratch.reticleShadowPath.moveTo(scratch.reticleShadowX[0], scratch.reticleShadowY[0])
        for (i in 1 until shadowCnt) {
            scratch.reticleShadowPath.lineTo(scratch.reticleShadowX[i], scratch.reticleShadowY[i])
        }
        scratch.reticleShadowPath.close()
        drawPath(
            path = scratch.reticleShadowPath,
            color = Color.Black.copy(alpha = 0.24f),
            style = Stroke(width = strokePx * 2.4f, cap = StrokeCap.Round)
        )
    }

    // 3. Project grounded surface point P_base & elevated normal-axis parallax jewel P_top
    val hasBase = ArMath.projectCoordsToScreen(
        centerX, centerY, centerZ,
        viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored
    )
    val pinHeightM = (outerRadiusM * (if (isSnapped) 0.28 else 0.20)).coerceIn(0.003, 0.040)
    val hasTop = ArMath.projectCoordsToScreen(
        centerX + nx * pinHeightM, centerY + ny * pinHeightM, centerZ + nz * pinHeightM,
        viewMatrix, projectionMatrix, screenW, screenH, scratch.projB, isHorizontallyMirrored
    )
    if (!hasBase) return false
    val basePos = Offset(scratch.projA[0], scratch.projA[1])
    val topPos = if (hasTop) Offset(scratch.projB[0], scratch.projB[1]) else basePos

    // 4. Style-specific 3D surface geometry
    when (style) {
        "PRECISION_CROSSHAIR" -> {
            // Subtle horizon reference halo on surface
            drawPath(
                path = scratch.reticleOuterPath,
                color = activeColor.copy(alpha = if (isSnapped) 0.12f else 0.05f)
            )
            drawDepthModulated3DRing(
                xs = scratch.reticleOuterX,
                ys = scratch.reticleOuterY,
                clipW = scratch.reticleOuterDepth,
                depthBounds = scratch.reticleOuterDepthBounds,
                count = outerCnt,
                baseColor = activeColor,
                baseStrokePx = 1.4.dp.toPx(),
                bloomAlpha = 0.08f,
                coreAlpha = if (isSnapped) 0.65f else 0.34f,
                specularAlpha = 0.30f
            )

            // 4 tangent-plane arms along ±U and ±V with sub-millimeter ticks
            val gapM = outerRadiusM * 0.28
            val armM = outerRadiusM * 1.18
            val tickDistM = outerRadiusM * 0.65
            val tickHalfM = outerRadiusM * 0.16
            val armStroke = if (isSnapped) 2.2.dp.toPx() else 1.8.dp.toPx()

            for (axis in 0 until 2) {
                val ax = if (axis == 0) ux else vx
                val ay = if (axis == 0) uy else vy
                val az = if (axis == 0) uz else vz
                val px = if (axis == 0) vx else ux
                val py = if (axis == 0) vy else uy
                val pz = if (axis == 0) vz else uz

                for (s in -1..1 step 2) {
                    val sign = s.toDouble()
                    if (ArMath.projectCoordsToScreen(
                            centerX + sign * ax * gapM, centerY + sign * ay * gapM, centerZ + sign * az * gapM,
                            viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored
                        ) &&
                        ArMath.projectCoordsToScreen(
                            centerX + sign * ax * armM, centerY + sign * ay * armM, centerZ + sign * az * armM,
                            viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored
                        )
                    ) {
                        drawLine(
                            color = activeColor,
                            start = Offset(scratch.projA[0], scratch.projA[1]),
                            end = Offset(scratch.projC[0], scratch.projC[1]),
                            strokeWidth = armStroke,
                            cap = StrokeCap.Round
                        )
                    }

                    // Perpendicular sub-millimeter graduation tick
                    val tx = centerX + sign * ax * tickDistM
                    val ty = centerY + sign * ay * tickDistM
                    val tz = centerZ + sign * az * tickDistM
                    if (ArMath.projectCoordsToScreen(
                            tx - px * tickHalfM, ty - py * tickHalfM, tz - pz * tickHalfM,
                            viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored
                        ) &&
                        ArMath.projectCoordsToScreen(
                            tx + px * tickHalfM, ty + py * tickHalfM, tz + pz * tickHalfM,
                            viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored
                        )
                    ) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.85f),
                            start = Offset(scratch.projA[0], scratch.projA[1]),
                            end = Offset(scratch.projC[0], scratch.projC[1]),
                            strokeWidth = 1.4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // 3D tactical lock brackets when snapped
            if (isSnapped) {
                val bDist = outerRadiusM * 0.96
                val bLen = outerRadiusM * 0.34
                for (su in -1..1 step 2) {
                    for (sv in -1..1 step 2) {
                        val cxCorner = centerX + su * bDist * ux + sv * bDist * vx
                        val cyCorner = centerY + su * bDist * uy + sv * bDist * vy
                        val czCorner = centerZ + su * bDist * uz + sv * bDist * vz
                        if (ArMath.projectCoordsToScreen(cxCorner, cyCorner, czCorner, viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored) &&
                            ArMath.projectCoordsToScreen(cxCorner - su * bLen * ux, cyCorner - su * bLen * uy, czCorner - su * bLen * uz, viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored)
                        ) {
                            drawLine(activeColor, Offset(scratch.projA[0], scratch.projA[1]), Offset(scratch.projC[0], scratch.projC[1]), 2.0.dp.toPx(), StrokeCap.Round)
                        }
                        if (ArMath.projectCoordsToScreen(cxCorner - sv * bLen * vx, cyCorner - sv * bLen * vy, czCorner - sv * bLen * vz, viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored)) {
                            drawLine(activeColor, Offset(scratch.projA[0], scratch.projA[1]), Offset(scratch.projC[0], scratch.projC[1]), 2.0.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
        }

        "HOLOGRAPHIC_RADAR" -> {
            drawPath(
                path = scratch.reticleOuterPath,
                color = activeColor.copy(alpha = if (isSnapped) 0.16f else 0.08f)
            )
            // Elevated holographic inner ring (+8% R along normal for camera parallax)
            val gimbalLiftM = outerRadiusM * 0.09
            val innerCnt = ArMath.projectCircle3DWithDepthToScreen(
                cx = centerX + nx * gimbalLiftM,
                cy = centerY + ny * gimbalLiftM,
                cz = centerZ + nz * gimbalLiftM,
                radiusMeters = outerRadiusM * 0.62,
                basis = scratch.reticleBasis,
                segments = segs,
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix,
                screenWidth = screenW,
                screenHeight = screenH,
                outScreenX = scratch.reticleInnerX,
                outScreenY = scratch.reticleInnerY,
                outClipW = scratch.reticleInnerDepth,
                outDepthBounds = scratch.reticleInnerDepthBounds,
                isHorizontallyMirrored = isHorizontallyMirrored
            )
            if (innerCnt >= 3) {
                drawDepthModulated3DRing(
                    xs = scratch.reticleInnerX,
                    ys = scratch.reticleInnerY,
                    clipW = scratch.reticleInnerDepth,
                    depthBounds = scratch.reticleInnerDepthBounds,
                    count = innerCnt,
                    baseColor = activeColor,
                    baseStrokePx = 1.6.dp.toPx(),
                    bloomAlpha = 0.14f,
                    coreAlpha = 0.65f,
                    specularAlpha = 0.42f
                )
            }

            // Two opposing rotating 3D tangent-space radar arcs
            val sweepStepRad = (2.0 * PI) / segs
            val basePhaseRad = (dashPhase * 0.14).toDouble()
            for (arcIdx in 0 until 2) {
                val startTheta = basePhaseRad + arcIdx * PI
                var valid = true
                scratch.reticleSweepPath.reset()
                for (si in 0..8) {
                    val theta = startTheta + si * sweepStepRad
                    val c = cos(theta) * outerRadiusM
                    val s = sin(theta) * outerRadiusM
                    if (!ArMath.projectCoordsToScreen(
                            centerX + c * ux + s * vx,
                            centerY + c * uy + s * vy,
                            centerZ + c * uz + s * vz,
                            viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored
                        )
                    ) {
                        valid = false
                        break
                    }
                    if (si == 0) scratch.reticleSweepPath.moveTo(scratch.projC[0], scratch.projC[1])
                    else scratch.reticleSweepPath.lineTo(scratch.projC[0], scratch.projC[1])
                }
                if (valid) {
                    drawPath(
                        path = scratch.reticleSweepPath,
                        color = if (arcIdx == 0) activeColor else Color.White.copy(alpha = 0.72f),
                        style = Stroke(width = if (arcIdx == 0) 2.6.dp.toPx() else 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 4 corner 3D surface brackets
            val bDist = outerRadiusM * 1.12
            val bLen = outerRadiusM * 0.30
            for (su in -1..1 step 2) {
                for (sv in -1..1 step 2) {
                    val cxCorner = centerX + su * bDist * ux + sv * bDist * vx
                    val cyCorner = centerY + su * bDist * uy + sv * bDist * vy
                    val czCorner = centerZ + su * bDist * uz + sv * bDist * vz
                    if (ArMath.projectCoordsToScreen(cxCorner, cyCorner, czCorner, viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored) &&
                        ArMath.projectCoordsToScreen(cxCorner - su * bLen * ux, cyCorner - su * bLen * uy, czCorner - su * bLen * uz, viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored)
                    ) {
                        drawLine(activeColor, Offset(scratch.projA[0], scratch.projA[1]), Offset(scratch.projC[0], scratch.projC[1]), 1.9.dp.toPx(), StrokeCap.Round)
                    }
                    if (ArMath.projectCoordsToScreen(cxCorner - sv * bLen * vx, cyCorner - sv * bLen * vy, czCorner - sv * bLen * vz, viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored)) {
                        drawLine(activeColor, Offset(scratch.projA[0], scratch.projA[1]), Offset(scratch.projC[0], scratch.projC[1]), 1.9.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }

        "MINIMAL_DOT" -> {
            val haloCnt = ArMath.projectCircle3DWithDepthToScreen(
                cx = centerX,
                cy = centerY,
                cz = centerZ,
                radiusMeters = outerRadiusM * 0.74,
                basis = scratch.reticleBasis,
                segments = segs,
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix,
                screenWidth = screenW,
                screenHeight = screenH,
                outScreenX = scratch.reticleInnerX,
                outScreenY = scratch.reticleInnerY,
                outClipW = scratch.reticleInnerDepth,
                outDepthBounds = scratch.reticleInnerDepthBounds,
                isHorizontallyMirrored = isHorizontallyMirrored
            )
            if (haloCnt >= 3) {
                drawDepthModulated3DRing(
                    xs = scratch.reticleInnerX,
                    ys = scratch.reticleInnerY,
                    clipW = scratch.reticleInnerDepth,
                    depthBounds = scratch.reticleInnerDepthBounds,
                    count = haloCnt,
                    baseColor = activeColor,
                    baseStrokePx = 1.7.dp.toPx(),
                    bloomAlpha = if (isSnapped) 0.28f else 0.14f,
                    coreAlpha = if (isSnapped) 0.85f else 0.50f,
                    specularAlpha = 0.45f
                )
            }
        }

        else -> { // "DOUBLE_RING"
            // Translucent inner volumetric surface tint
            drawPath(
                path = scratch.reticleOuterPath,
                color = activeColor.copy(alpha = if (isSnapped) 0.18f else 0.08f)
            )

            // Outer 3D perspective depth-modulated ring with specular crest
            drawDepthModulated3DRing(
                xs = scratch.reticleOuterX,
                ys = scratch.reticleOuterY,
                clipW = scratch.reticleOuterDepth,
                depthBounds = scratch.reticleOuterDepthBounds,
                count = outerCnt,
                baseColor = activeColor,
                baseStrokePx = strokePx,
                bloomAlpha = if (isSnapped) 0.34f else 0.20f,
                coreAlpha = 0.96f,
                specularAlpha = if (isSnapped) 0.65f else 0.48f
            )

            // Elevated hovering inner gimbal ring (+7% outerRadius along surface normal for true 3D parallax!)
            val gimbalLiftM = outerRadiusM * 0.075
            val innerCnt = ArMath.projectCircle3DWithDepthToScreen(
                cx = centerX + nx * gimbalLiftM,
                cy = centerY + ny * gimbalLiftM,
                cz = centerZ + nz * gimbalLiftM,
                radiusMeters = outerRadiusM * 0.56,
                basis = scratch.reticleBasis,
                segments = segs,
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix,
                screenWidth = screenW,
                screenHeight = screenH,
                outScreenX = scratch.reticleInnerX,
                outScreenY = scratch.reticleInnerY,
                outClipW = scratch.reticleInnerDepth,
                outDepthBounds = scratch.reticleInnerDepthBounds,
                isHorizontallyMirrored = isHorizontallyMirrored
            )
            if (innerCnt >= 3) {
                drawDepthModulated3DRing(
                    xs = scratch.reticleInnerX,
                    ys = scratch.reticleInnerY,
                    clipW = scratch.reticleInnerDepth,
                    depthBounds = scratch.reticleInnerDepthBounds,
                    count = innerCnt,
                    baseColor = activeColor,
                    baseStrokePx = 1.5.dp.toPx(),
                    bloomAlpha = 0.10f,
                    coreAlpha = if (isSnapped) 0.92f else 0.62f,
                    specularAlpha = 0.40f
                )
            }

            // Smooth rotating 3D tangent-space scan sweep arc (~70 deg)
            val sweepStepRad = (2.0 * PI) / segs
            val sweepPhaseRad = (dashPhase * 0.10).toDouble()
            val sweepRadiusM = outerRadiusM * 1.14
            var sweepValid = true
            scratch.reticleSweepPath.reset()
            for (si in 0..7) {
                val theta = sweepPhaseRad + si * sweepStepRad
                val c = cos(theta) * sweepRadiusM
                val s = sin(theta) * sweepRadiusM
                if (!ArMath.projectCoordsToScreen(
                        centerX + c * ux + s * vx,
                        centerY + c * uy + s * vy,
                        centerZ + c * uz + s * vz,
                        viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored
                    )
                ) {
                    sweepValid = false
                    break
                }
                if (si == 0) scratch.reticleSweepPath.moveTo(scratch.projC[0], scratch.projC[1])
                else scratch.reticleSweepPath.lineTo(scratch.projC[0], scratch.projC[1])
            }
            if (sweepValid) {
                drawPath(
                    path = scratch.reticleSweepPath,
                    color = Color.White.copy(alpha = if (isSnapped) 0.92f else 0.68f),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 4 cardinal 3D (U, V) tangent ticks on the outer plane ring
            val tickInnerM = outerRadiusM * 0.87
            val tickOuterM = outerRadiusM * 1.13
            for (cardinal in 0 until 4) {
                val ax = if (cardinal < 2) ux else vx
                val ay = if (cardinal < 2) uy else vy
                val az = if (cardinal < 2) uz else vz
                val sign = if (cardinal % 2 == 0) 1.0 else -1.0
                if (ArMath.projectCoordsToScreen(
                        centerX + sign * ax * tickInnerM,
                        centerY + sign * ay * tickInnerM,
                        centerZ + sign * az * tickInnerM,
                        viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored
                    ) &&
                    ArMath.projectCoordsToScreen(
                        centerX + sign * ax * tickOuterM,
                        centerY + sign * ay * tickOuterM,
                        centerZ + sign * az * tickOuterM,
                        viewMatrix, projectionMatrix, screenW, screenH, scratch.projC, isHorizontallyMirrored
                    )
                ) {
                    drawLine(
                        color = activeColor.copy(alpha = 0.88f),
                        start = Offset(scratch.projA[0], scratch.projA[1]),
                        end = Offset(scratch.projC[0], scratch.projC[1]),
                        strokeWidth = 1.9.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }

    // 5. Universal 3D Surface-Normal Parallax Stem & Elevated Center Anchor Jewel
    val stemDx = topPos.x - basePos.x
    val stemDy = topPos.y - basePos.y
    val stemLenPxSq = stemDx * stemDx + stemDy * stemDy
    if (stemLenPxSq > 4f && stemLenPxSq < 180f * 180f) {
        // Surface contact dot + shadow halo
        drawCircle(
            color = Color.Black.copy(alpha = 0.42f),
            center = basePos,
            radius = 3.2.dp.toPx()
        )
        drawCircle(
            color = activeColor.copy(alpha = 0.65f),
            center = basePos,
            radius = 2.0.dp.toPx()
        )
        // Translucent 3D normal stalk
        drawLine(
            color = Color.White.copy(alpha = 0.72f),
            start = basePos,
            end = topPos,
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    val dotRadius = if (isSnapped) 5.2.dp.toPx() else 4.4.dp.toPx()
    drawCircle(
        color = activeColor.copy(alpha = 0.85f),
        center = topPos,
        radius = dotRadius + 1.3.dp.toPx(),
        style = Stroke(width = 1.3.dp.toPx())
    )
    drawCircle(
        color = Color.White,
        center = topPos,
        radius = dotRadius
    )
    return true
}

/**
 * Renders the SubMode 4 3D Measurement Circle with grounded contact shadow, perspective depth shading,
 * 24 radial degree graduation ticks, orthogonal tangent diameters, and 3D center elevation pin.
 */
internal fun DrawScope.draw3DMeasuredCircle(
    scratch: ArCircle3DRenderScratch,
    drawCx: Double,
    drawCy: Double,
    drawCz: Double,
    drawRadius: Double,
    isComplete: Boolean,
    pulseProgress: Float,
    strokePx: Float,
    dashPhase: Float,
    dash10_6: PathEffect,
    dash14_10: PathEffect,
    primaryColor: Color,
    tertiaryColor: Color,
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray,
    screenW: Int,
    screenH: Int,
    isHorizontallyMirrored: Boolean
): Offset? {
    val segCount = 48
    val nx = scratch.circleBasis[0]; val ny = scratch.circleBasis[1]; val nz = scratch.circleBasis[2]
    val ux = scratch.circleBasis[3]; val uy = scratch.circleBasis[4]; val uz = scratch.circleBasis[5]
    val vx = scratch.circleBasis[6]; val vy = scratch.circleBasis[7]; val vz = scratch.circleBasis[8]

    val projectedCount = ArMath.projectCircle3DWithDepthToScreen(
        cx = drawCx,
        cy = drawCy,
        cz = drawCz,
        radiusMeters = drawRadius,
        basis = scratch.circleBasis,
        segments = segCount,
        viewMatrix = viewMatrix,
        projectionMatrix = projectionMatrix,
        screenWidth = screenW,
        screenHeight = screenH,
        outScreenX = scratch.circleX,
        outScreenY = scratch.circleY,
        outClipW = scratch.circleDepth,
        outDepthBounds = scratch.circleDepthBounds,
        isHorizontallyMirrored = isHorizontallyMirrored,
        phaseOffsetRad = if (isComplete) 0.0 else (dashPhase * 0.04).toDouble()
    )
    if (projectedCount < 3) return null

    scratch.circlePath.reset()
    scratch.circlePath.moveTo(scratch.circleX[0], scratch.circleY[0])
    for (i in 1 until projectedCount) {
        scratch.circlePath.lineTo(scratch.circleX[i], scratch.circleY[i])
    }
    scratch.circlePath.close()

    // 1. Grounded 3D Contact Drop Shadow beneath the plane
    val shadowOffsetM = min(0.006, drawRadius * 0.020)
    val shadowCount = ArMath.projectCircle3DToScreen(
        cx = drawCx - nx * shadowOffsetM,
        cy = drawCy - ny * shadowOffsetM,
        cz = drawCz - nz * shadowOffsetM,
        radiusMeters = drawRadius * 1.015,
        basis = scratch.circleBasis,
        segments = segCount,
        viewMatrix = viewMatrix,
        projectionMatrix = projectionMatrix,
        screenWidth = screenW,
        screenHeight = screenH,
        outScreenX = scratch.circleShadowX,
        outScreenY = scratch.circleShadowY,
        scratchXY = scratch.projA,
        isHorizontallyMirrored = isHorizontallyMirrored
    )
    if (shadowCount >= 3) {
        scratch.circleShadowPath.reset()
        scratch.circleShadowPath.moveTo(scratch.circleShadowX[0], scratch.circleShadowY[0])
        for (i in 1 until shadowCount) {
            scratch.circleShadowPath.lineTo(scratch.circleShadowX[i], scratch.circleShadowY[i])
        }
        scratch.circleShadowPath.close()
        drawPath(
            path = scratch.circleShadowPath,
            color = Color.Black.copy(alpha = 0.22f),
            style = Stroke(width = strokePx * 2.2f, cap = StrokeCap.Round)
        )
    }

    // 2. Completion 3D Ripple Ring when 3rd point locks
    if (isComplete && pulseProgress in 0.01f..0.98f) {
        val rippleRadius = drawRadius * (1.0 + 0.16 * pulseProgress)
        val rippleCount = ArMath.projectCircle3DToScreen(
            cx = drawCx,
            cy = drawCy,
            cz = drawCz,
            radiusMeters = rippleRadius,
            basis = scratch.circleBasis,
            segments = segCount,
            viewMatrix = viewMatrix,
            projectionMatrix = projectionMatrix,
            screenWidth = screenW,
            screenHeight = screenH,
            outScreenX = scratch.circleShadowX,
            outScreenY = scratch.circleShadowY,
            scratchXY = scratch.projA,
            isHorizontallyMirrored = isHorizontallyMirrored
        )
        if (rippleCount >= 3) {
            scratch.circleBloomPath.reset()
            scratch.circleBloomPath.moveTo(scratch.circleShadowX[0], scratch.circleShadowY[0])
            for (i in 1 until rippleCount) {
                scratch.circleBloomPath.lineTo(scratch.circleShadowX[i], scratch.circleShadowY[i])
            }
            scratch.circleBloomPath.close()
            drawPath(
                path = scratch.circleBloomPath,
                color = primaryColor.copy(alpha = (1f - pulseProgress) * 0.65f),
                style = Stroke(width = (strokePx * (1.6f - 0.6f * pulseProgress)), cap = StrokeCap.Round)
            )
        }
    }

    // 3. Semi-transparent 3D surface disc fill
    drawPath(
        path = scratch.circlePath,
        color = primaryColor.copy(alpha = if (isComplete) 0.18f else 0.11f)
    )

    // 4. Depth-modulated 3D perspective perimeter (solid when complete, flowing dash + depth shading on preview)
    if (isComplete) {
        drawDepthModulated3DRing(
            xs = scratch.circleX,
            ys = scratch.circleY,
            clipW = scratch.circleDepth,
            depthBounds = scratch.circleDepthBounds,
            count = projectedCount,
            baseColor = primaryColor,
            baseStrokePx = strokePx,
            bloomAlpha = 0.22f,
            coreAlpha = 0.98f,
            specularAlpha = 0.55f
        )
    } else {
        drawPath(
            path = scratch.circlePath,
            color = primaryColor,
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                pathEffect = dash14_10
            )
        )
    }

    // 5. 24 Radial 3D degree graduation ticks along perimeter (every 15 deg; 90 deg cardinals emphasized)
    val tickCount = 24
    val tickStepRad = (2.0 * PI) / tickCount
    for (ti in 0 until tickCount) {
        val isCardinal = (ti % 6 == 0)
        val innerFactor = if (isCardinal) 0.87 else 0.93
        val theta = ti * tickStepRad
        val dirX = cos(theta) * ux + sin(theta) * vx
        val dirY = cos(theta) * uy + sin(theta) * vy
        val dirZ = cos(theta) * uz + sin(theta) * vz
        if (ArMath.projectCoordsToScreen(
                drawCx + dirX * drawRadius * innerFactor,
                drawCy + dirY * drawRadius * innerFactor,
                drawCz + dirZ * drawRadius * innerFactor,
                viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored
            ) &&
            ArMath.projectCoordsToScreen(
                drawCx + dirX * drawRadius,
                drawCy + dirY * drawRadius,
                drawCz + dirZ * drawRadius,
                viewMatrix, projectionMatrix, screenW, screenH, scratch.projB, isHorizontallyMirrored
            )
        ) {
            drawLine(
                color = if (isCardinal) Color.White.copy(alpha = 0.88f) else primaryColor.copy(alpha = 0.55f),
                start = Offset(scratch.projA[0], scratch.projA[1]),
                end = Offset(scratch.projB[0], scratch.projB[1]),
                strokeWidth = if (isCardinal) 2.0.dp.toPx() else 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }

    // 6. Orthogonal 3D diameter crossbars along tangent basis (U and V)
    if (ArMath.projectCoordsToScreen(drawCx - ux * drawRadius, drawCy - uy * drawRadius, drawCz - uz * drawRadius, viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored) &&
        ArMath.projectCoordsToScreen(drawCx + ux * drawRadius, drawCy + uy * drawRadius, drawCz + uz * drawRadius, viewMatrix, projectionMatrix, screenW, screenH, scratch.projB, isHorizontallyMirrored)
    ) {
        drawLine(
            color = primaryColor.copy(alpha = 0.44f),
            start = Offset(scratch.projA[0], scratch.projA[1]),
            end = Offset(scratch.projB[0], scratch.projB[1]),
            strokeWidth = 1.4.dp.toPx(),
            pathEffect = dash10_6
        )
    }
    if (ArMath.projectCoordsToScreen(drawCx - vx * drawRadius, drawCy - vy * drawRadius, drawCz - vz * drawRadius, viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored) &&
        ArMath.projectCoordsToScreen(drawCx + vx * drawRadius, drawCy + vy * drawRadius, drawCz + vz * drawRadius, viewMatrix, projectionMatrix, screenW, screenH, scratch.projB, isHorizontallyMirrored)
    ) {
        drawLine(
            color = primaryColor.copy(alpha = 0.30f),
            start = Offset(scratch.projA[0], scratch.projA[1]),
            end = Offset(scratch.projB[0], scratch.projB[1]),
            strokeWidth = 1.1.dp.toPx(),
            pathEffect = dash10_6
        )
    }

    // 7. 3D Center Marker + Elevated Normal Pin
    val centerOk = ArMath.projectCoordsToScreen(
        drawCx, drawCy, drawCz,
        viewMatrix, projectionMatrix, screenW, screenH, scratch.projA, isHorizontallyMirrored
    )
    if (!centerOk) return null
    val centerBase = Offset(scratch.projA[0], scratch.projA[1])
    val centerPinM = (drawRadius * 0.14).coerceIn(0.005, 0.045)
    val pinOk = ArMath.projectCoordsToScreen(
        drawCx + nx * centerPinM, drawCy + ny * centerPinM, drawCz + nz * centerPinM,
        viewMatrix, projectionMatrix, screenW, screenH, scratch.projB, isHorizontallyMirrored
    )
    val centerTop = if (pinOk) Offset(scratch.projB[0], scratch.projB[1]) else centerBase

    val pinDx = centerTop.x - centerBase.x
    val pinDy = centerTop.y - centerBase.y
    if (pinDx * pinDx + pinDy * pinDy in 4f..(160f * 160f)) {
        drawCircle(Color.Black.copy(alpha = 0.35f), center = centerBase, radius = 3.0.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.72f), start = centerBase, end = centerTop, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    }
    drawCircle(Color.White, center = centerTop, radius = 3.8.dp.toPx())
    drawCircle(tertiaryColor, center = centerTop, radius = 6.dp.toPx(), style = Stroke(width = 1.6.dp.toPx()))

    return centerBase
}
