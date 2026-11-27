package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import com.example.ui.components.BlurDirection
import com.example.ui.components.GradientBlurBackdropState
import com.example.ui.components.progressiveGlass
import com.example.ui.i18n.tr
import com.example.ui.theme.ExpressiveCloverShape
import com.example.ui.theme.ExpressiveCookie8Shape
import com.example.ui.theme.ExpressiveSquircle
import com.example.ui.theme.GoogleSansRounded
import com.example.ui.theme.LocalAppDarkTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slide 1 Showcase: Enhanced Material 3 AR Spatial Reticle & 3D Glowing Orbit
 */
@Composable
internal fun WelcomeArHeroShowcase(
    backdropState: GradientBlurBackdropState? = null,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "welcomeHeroAnim")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reticlePulse"
    )
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )
    val orbitAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAngle"
    )

    val isDark = LocalAppDarkTheme.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val cardShape = RoundedCornerShape(32.dp)

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .progressiveGlass(
                backdropState = backdropState,
                direction = BlurDirection.RADIAL_EDGE,
                maxBlurRadius = 28.dp,
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.54f else 0.70f),
                shape = cardShape,
                borderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.70f)
            )
            .testTag("welcome_hero_card")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Multi-stop glowing ambient bokeh backdrop (radial gradients already feather smoothly to 0 alpha)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = if (isDark) 0.36f else 0.24f),
                                primaryColor.copy(alpha = if (isDark) 0.14f else 0.09f),
                                primaryColor.copy(alpha = 0f)
                            ),
                            center = Offset(size.width * 0.28f, size.height * 0.32f),
                            radius = size.minDimension * 0.62f
                        ),
                        radius = size.minDimension * 0.62f,
                        center = Offset(size.width * 0.28f, size.height * 0.32f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                tertiaryColor.copy(alpha = if (isDark) 0.32f else 0.20f),
                                tertiaryColor.copy(alpha = if (isDark) 0.12f else 0.08f),
                                tertiaryColor.copy(alpha = 0f)
                            ),
                            center = Offset(size.width * 0.76f, size.height * 0.62f),
                            radius = size.minDimension * 0.58f
                        ),
                        radius = size.minDimension * 0.58f,
                        center = Offset(size.width * 0.76f, size.height * 0.62f)
                    )
                }

                // Advanced 3D AR Reticle & Orbiting Particles Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height * 0.44f)
                    val baseRadius = 62.dp.toPx() * pulse

                    // Outer glowing guide rings
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.18f),
                        radius = baseRadius * 1.35f,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(
                        color = secondaryColor.copy(alpha = 0.12f),
                        radius = baseRadius * 1.65f,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Rotating primary sweep arc
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.05f),
                                primaryColor.copy(alpha = 0.85f),
                                primaryColor.copy(alpha = 0.05f)
                            ),
                            center = center
                        ),
                        startAngle = sweep,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                        size = Size(baseRadius * 2f, baseRadius * 2f),
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Counter-rotating tertiary calibration arc
                    drawArc(
                        color = tertiaryColor.copy(alpha = 0.65f),
                        startAngle = -sweep * 0.8f,
                        sweepAngle = 130f,
                        useCenter = false,
                        topLeft = Offset(center.x - baseRadius * 1.22f, center.y - baseRadius * 1.22f),
                        size = Size(baseRadius * 2.44f, baseRadius * 2.44f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Orbiting 3D spatial coordinate node
                    val rad = Math.toRadians(orbitAngle.toDouble())
                    val orbitX = center.x + (baseRadius * 1.38f) * cos(rad).toFloat()
                    val orbitY = center.y + (baseRadius * 0.65f) * sin(rad).toFloat()
                    drawCircle(
                        color = secondaryColor,
                        radius = 5.dp.toPx(),
                        center = Offset(orbitX, orbitY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.dp.toPx(),
                        center = Offset(orbitX, orbitY)
                    )
                }

                // Central Hero Icon & Floating Satellites
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-10).dp)
                ) {
                    Surface(
                        shape = ExpressiveCookie8Shape,
                        color = primaryColor,
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(86.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ViewInAr,
                                contentDescription = tr("wel_card_ar_icon_desc"),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    Surface(
                        shape = ExpressiveSquircle,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = if (isDark) 0.20f else 0.60f)),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-34).dp, y = (-10).dp)
                            .size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Straighten,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    Surface(
                        shape = ExpressiveCloverShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = if (isDark) 0.20f else 0.60f)),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 34.dp, y = 8.dp)
                            .size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.SquareFoot,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Tinted M3 Expressive Bottom Badge
            val pillShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.86f else 0.92f))
                    .border(
                        0.8.dp,
                        Color.White.copy(alpha = if (isDark) 0.20f else 0.60f),
                        pillShape
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = ExpressiveCookie8Shape,
                        color = primaryColor,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CenterFocusStrong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Text(
                        text = tr("wel_card_ar_caption"),
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Slide 2 Showcase: Enhanced Photo & Video Recording Showcase with Laser Scan & Target Brackets
 */
@Composable
internal fun WelcomeCaptureShowcase(
    backdropState: GradientBlurBackdropState? = null,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "welcomeCaptureAnim")
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lineScanProgress"
    )
    val recAlpha by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recBlink"
    )

    val isDark = LocalAppDarkTheme.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val cardShape = RoundedCornerShape(32.dp)

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .progressiveGlass(
                backdropState = backdropState,
                direction = BlurDirection.RADIAL_EDGE,
                maxBlurRadius = 28.dp,
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.54f else 0.70f),
                shape = cardShape,
                borderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.70f)
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                secondary.copy(alpha = if (isDark) 0.34f else 0.22f),
                                secondary.copy(alpha = if (isDark) 0.12f else 0.08f),
                                secondary.copy(alpha = 0f)
                            ),
                            center = Offset(size.width * 0.72f, size.height * 0.30f),
                            radius = size.minDimension * 0.58f
                        ),
                        radius = size.minDimension * 0.58f,
                        center = Offset(size.width * 0.72f, size.height * 0.30f)
                    )
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pad = 22.dp.toPx()
                    val frameW = size.width - pad * 2
                    val frameH = size.height - pad * 2
                    val cornerLen = 20.dp.toPx()
                    val strokeW = 3.5.dp.toPx()

                    val left = pad
                    val top = pad
                    val right = left + frameW
                    val bottom = top + frameH
                    val bracketColor = primary.copy(alpha = 0.78f)

                    // Corner Targeting Brackets
                    drawLine(bracketColor, Offset(left, top), Offset(left + cornerLen, top), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(left, top), Offset(left, top + cornerLen), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(right - cornerLen, top), Offset(right, top), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(right, top), Offset(right, top + cornerLen), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(left, bottom - cornerLen), Offset(left, bottom), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(right - cornerLen, bottom), Offset(right, bottom), strokeW, StrokeCap.Round)
                    drawLine(bracketColor, Offset(right, bottom - cornerLen), Offset(right, bottom), strokeW, StrokeCap.Round)

                    val boxLeft = size.width * 0.20f
                    val boxTop = size.height * 0.31f
                    val boxW = size.width * 0.60f
                    val boxH = size.height * 0.36f

                    drawRoundRect(
                        color = primary.copy(alpha = 0.14f),
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(16.dp.toPx())
                    )
                    drawRoundRect(
                        color = primary.copy(alpha = 0.45f),
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Laser Scanning Line with Gradient Trail
                    val currentScanY = boxTop + boxH * progress
                    drawLine(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                primary.copy(alpha = 0f),
                                primary,
                                primary.copy(alpha = 0f)
                            ),
                            startY = currentScanY - 16.dp.toPx(),
                            endY = currentScanY + 16.dp.toPx()
                        ),
                        start = Offset(boxLeft + 8.dp.toPx(), currentScanY),
                        end = Offset(boxLeft + boxW - 8.dp.toPx(), currentScanY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Photo & Video Icon Badge Pair
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = ExpressiveSquircle,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    Surface(
                        shape = ExpressiveCloverShape,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Videocam,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            // REC Badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.86f else 0.92f))
                    .border(
                        0.8.dp,
                        Color.White.copy(alpha = if (isDark) 0.18f else 0.60f),
                        CircleShape
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = recAlpha), CircleShape)
                    )
                    Text(
                        text = tr("wel_card_capture_badge"),
                        fontFamily = GoogleSansRounded,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Bottom Caption Badge
            val pillShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (isDark) 0.86f else 0.92f))
                    .border(
                        0.8.dp,
                        Color.White.copy(alpha = if (isDark) 0.20f else 0.60f),
                        pillShape
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = ExpressiveCloverShape,
                        color = secondary,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Text(
                        text = tr("wel_card_capture_caption"),
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Slide 3 Showcase: Enhanced Screen Ruler & Sliding Caliper Precision Showcase
 */
@Composable
internal fun WelcomeToolsShowcase(
    backdropState: GradientBlurBackdropState? = null,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "welcomeToolsAnim")
    val caliperSweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caliperSweep"
    )

    val isDark = LocalAppDarkTheme.current
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val cardShape = RoundedCornerShape(32.dp)

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .progressiveGlass(
                backdropState = backdropState,
                direction = BlurDirection.RADIAL_EDGE,
                maxBlurRadius = 28.dp,
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.54f else 0.70f),
                shape = cardShape,
                borderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.70f)
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                tertiary.copy(alpha = if (isDark) 0.34f else 0.22f),
                                tertiary.copy(alpha = if (isDark) 0.12f else 0.08f),
                                tertiary.copy(alpha = 0f)
                            ),
                            center = Offset(size.width * 0.50f, size.height * 0.44f),
                            radius = size.minDimension * 0.58f
                        ),
                        radius = size.minDimension * 0.58f,
                        center = Offset(size.width * 0.50f, size.height * 0.44f)
                    )
                }

                // Calibrated Precision Ruler Ticks & Sliding Caliper Band Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Left Metric Ticks
                    var y = 18.dp.toPx()
                    var idx = 0
                    val step = 10.dp.toPx()
                    while (y < h - 48.dp.toPx()) {
                        val tickLen = if (idx % 5 == 0) 26.dp.toPx() else 14.dp.toPx()
                        drawLine(
                            color = onSurface.copy(alpha = if (idx % 5 == 0) 0.75f else 0.38f),
                            start = Offset(0f, y),
                            end = Offset(tickLen, y),
                            strokeWidth = if (idx % 5 == 0) 2.5.dp.toPx() else 1.3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        y += step
                        idx++
                    }

                    // Right Imperial Ticks
                    y = 18.dp.toPx()
                    idx = 0
                    val inchStep = 14.dp.toPx()
                    while (y < h - 48.dp.toPx()) {
                        val tickLen = if (idx % 4 == 0) 26.dp.toPx() else 14.dp.toPx()
                        drawLine(
                            color = primary.copy(alpha = if (idx % 4 == 0) 0.82f else 0.38f),
                            start = Offset(w, y),
                            end = Offset(w - tickLen, y),
                            strokeWidth = if (idx % 4 == 0) 2.5.dp.toPx() else 1.3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        y += inchStep
                        idx++
                    }

                    // Sliding Caliper Span Indicator
                    val bandTop = h * 0.24f
                    val bandBottom = h * (0.44f + caliperSweep * 0.18f)

                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                tertiary.copy(alpha = 0.08f),
                                tertiary.copy(alpha = 0.22f),
                                primary.copy(alpha = 0.08f)
                            ),
                            startY = bandTop,
                            endY = bandBottom
                        ),
                        topLeft = Offset(36.dp.toPx(), bandTop),
                        size = Size(w - 72.dp.toPx(), bandBottom - bandTop)
                    )

                    drawLine(
                        color = tertiary,
                        start = Offset(30.dp.toPx(), bandTop),
                        end = Offset(w - 30.dp.toPx(), bandTop),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = primary,
                        start = Offset(30.dp.toPx(), bandBottom),
                        end = Offset(w - 30.dp.toPx(), bandBottom),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    drawCircle(color = tertiary, radius = 6.5.dp.toPx(), center = Offset(w * 0.5f, bandTop))
                    drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(w * 0.5f, bandTop))

                    drawCircle(color = primary, radius = 6.5.dp.toPx(), center = Offset(w * 0.5f, bandBottom))
                    drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(w * 0.5f, bandBottom))
                }
            }

            // Bottom Caption Badge
            val pillShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isDark) 0.86f else 0.92f))
                    .border(
                        0.8.dp,
                        Color.White.copy(alpha = if (isDark) 0.20f else 0.60f),
                        pillShape
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = ExpressiveSquircle,
                        color = tertiary,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Straighten,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Text(
                        text = tr("wel_card_tools_caption"),
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
