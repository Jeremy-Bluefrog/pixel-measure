package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.logic.TranslationManager
import com.example.logic.ar.ArCoreEngineStatus
import com.example.logic.ar.GeometryWarning
import com.example.ui.i18n.LocalAppLanguage
import com.example.ui.theme.ExpressiveCookie8Shape
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.ExpressiveTypography
import com.example.ui.viewmodel.CalibrationStep
import com.example.ui.viewmodel.MeasureViewModel
import kotlin.math.acos
import kotlin.math.roundToInt

/**
 * Pure visual dynamic guidance pill HUD enhanced with Material 3 Expressive motion & morphing indicator:
 * - Uses ExpressiveContainedLoadingIndicator (morphing geometric shape) during ARCore plane scanning
 * - Uses ExpressiveCookie8Shape badge when magnetic vertex snap activates
 * - Uses ExpressiveMotion.spatialDefault() physics-based expansion and pop transitions
 * - Uses tabular numerals (`tnum`) for live distance values
 */
@Composable
fun MeasurementGuidancePill(
    viewModel: MeasureViewModel,
    subMode: Int,
    autoDetectedType: String,
    planesCount: Int,
    hasCapturedPoints: Boolean,
    isWaitingForSecondPoint: Boolean,
    capturedPointsCount: Int,
    isMeasurementAvailable: Boolean,
    isSnapped: Boolean,
    onOpenTutorial: () -> Unit,
    modifier: Modifier = Modifier,
    arCoreStatus: ArCoreEngineStatus = ArCoreEngineStatus.ACTIVE,
    arCoreStatusMessage: String = ""
) {
    val liveDistanceMeters by viewModel.liveDistanceMeters.collectAsStateWithLifecycle()
    val liveAngleDegrees by viewModel.liveAngleDegrees.collectAsStateWithLifecycle()
    val selectedUnit by viewModel.selectedUnit.collectAsStateWithLifecycle()
    val surfaceQuality by viewModel.surfaceQuality.collectAsStateWithLifecycle()
    val guidedCalibrationState by viewModel.guidedCalibrationState.collectAsStateWithLifecycle()
    val isCameraFocusSettled by viewModel.isCameraFocusSettled.collectAsStateWithLifecycle()
    val isImageEdgeSnapped by viewModel.isImageEdgeSnapped.collectAsStateWithLifecycle()
    val currentLang = LocalAppLanguage.current

    val isScanningPlanes = arCoreStatus == ArCoreEngineStatus.ACTIVE &&
        !isSnapped &&
        !isWaitingForSecondPoint &&
        !isMeasurementAvailable &&
        !guidedCalibrationState.isActive &&
        isCameraFocusSettled &&
        surfaceQuality.warning == GeometryWarning.NONE

    val infiniteTransition = rememberInfiniteTransition(label = "GuidancePillPulse")
    val pulseScale by if (isSnapped || isImageEdgeSnapped) {
        infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }

    val cs = MaterialTheme.colorScheme
    val (icon: ImageVector, title: String, subtitle: String, accentColor: Color) = remember(
        planesCount,
        hasCapturedPoints,
        isWaitingForSecondPoint,
        capturedPointsCount,
        isMeasurementAvailable,
        isSnapped,
        isCameraFocusSettled,
        isImageEdgeSnapped,
        subMode,
        autoDetectedType,
        liveDistanceMeters,
        liveAngleDegrees,
        selectedUnit,
        surfaceQuality,
        guidedCalibrationState,
        arCoreStatus,
        arCoreStatusMessage,
        currentLang,
        cs
    ) {
        when {
            arCoreStatus == ArCoreEngineStatus.NEEDS_INSTALLATION -> {
                Quad(
                    Icons.Rounded.DownloadForOffline,
                    TranslationManager.getString("arcore_need_install_short", currentLang),
                    "com.google.ar.core",
                    cs.tertiary
                )
            }
            arCoreStatus == ArCoreEngineStatus.UNSUPPORTED || arCoreStatus == ArCoreEngineStatus.ERROR -> {
                Quad(
                    Icons.Rounded.WarningAmber,
                    arCoreStatusMessage.ifBlank { TranslationManager.getString("arcore_not_ready_short", currentLang) },
                    "",
                    cs.error
                )
            }
            guidedCalibrationState.step == CalibrationStep.AIM_POINT_1 -> {
                val refStr = viewModel.formatLength(guidedCalibrationState.referenceMeters, selectedUnit)
                Quad(
                    Icons.Rounded.CenterFocusStrong,
                    TranslationManager.getString("calib_guide_p1_title", currentLang),
                    TranslationManager.formatString("calib_guide_sub_fmt", currentLang, refStr),
                    cs.primary
                )
            }
            guidedCalibrationState.step == CalibrationStep.AIM_POINT_2 -> {
                val displayStr = liveDistanceMeters?.let { viewModel.formatLength(it, selectedUnit) }
                    ?: viewModel.formatLength(guidedCalibrationState.referenceMeters, selectedUnit)
                Quad(
                    Icons.Rounded.Straighten,
                    TranslationManager.getString("calib_guide_p2_title", currentLang),
                    TranslationManager.formatString("calib_guide_sub_fmt", currentLang, displayStr),
                    cs.primary
                )
            }
            isSnapped -> {
                Quad(
                    Icons.Rounded.AllInclusive,
                    TranslationManager.getString("guide_snap_title", currentLang),
                    TranslationManager.getString("guide_snap_sub", currentLang),
                    cs.primary
                )
            }
            arCoreStatus == ArCoreEngineStatus.ACTIVE && surfaceQuality.warning == GeometryWarning.LOW_LIGHT -> {
                Quad(
                    Icons.Rounded.WbTwilight,
                    TranslationManager.getString("warn_low_light_title", currentLang),
                    TranslationManager.getString("warn_low_light_sub", currentLang),
                    cs.error
                )
            }
            arCoreStatus == ArCoreEngineStatus.ACTIVE && surfaceQuality.warning == GeometryWarning.LOW_TEXTURE_WALL -> {
                Quad(
                    Icons.Rounded.GridOff,
                    TranslationManager.getString("warn_low_texture_title", currentLang),
                    TranslationManager.getString("warn_low_texture_sub", currentLang),
                    cs.error
                )
            }
            arCoreStatus == ArCoreEngineStatus.ACTIVE && surfaceQuality.warning == GeometryWarning.OBLIQUE_ANGLE -> {
                val tiltDeg = Math.toDegrees(acos(surfaceQuality.incidenceCosine.coerceIn(0f, 1f).toDouble())).roundToInt()
                Quad(
                    Icons.Rounded.ChangeHistory,
                    TranslationManager.getString("warn_oblique_title", currentLang),
                    TranslationManager.formatString("warn_oblique_sub_fmt", currentLang, tiltDeg),
                    cs.tertiary
                )
            }
            arCoreStatus == ArCoreEngineStatus.ACTIVE && surfaceQuality.warning == GeometryWarning.LONG_RANGE -> {
                val distStr = viewModel.formatLength(surfaceQuality.distanceMeters.toDouble(), selectedUnit)
                Quad(
                    Icons.Rounded.SocialDistance,
                    TranslationManager.getString("warn_long_range_title", currentLang),
                    TranslationManager.formatString("warn_long_range_sub_fmt", currentLang, distStr),
                    cs.tertiary
                )
            }
            arCoreStatus == ArCoreEngineStatus.ACTIVE && !isCameraFocusSettled -> {
                Quad(
                    Icons.Rounded.CenterFocusWeak,
                    TranslationManager.getString("guide_focus_title", currentLang),
                    TranslationManager.getString("guide_focus_sub", currentLang),
                    cs.secondary
                )
            }
            isImageEdgeSnapped && !isSnapped -> {
                Quad(
                    Icons.Rounded.FilterCenterFocus,
                    TranslationManager.getString("guide_edge_snap_title", currentLang),
                    TranslationManager.getString("guide_edge_snap_sub", currentLang),
                    cs.primary
                )
            }
            isWaitingForSecondPoint -> {
                val distStr = liveDistanceMeters?.let { viewModel.formatLength(it, selectedUnit) }
                    ?: TranslationManager.getString("ar_measuring", currentLang)
                when (subMode) {
                    1 -> Quad(
                        Icons.Rounded.SquareFoot,
                        TranslationManager.formatString("guide_poly_vertex_fmt", currentLang, capturedPointsCount + 1),
                        TranslationManager.formatString("guide_edge_len_fmt", currentLang, distStr),
                        cs.tertiary
                    )
                    2 -> Quad(
                        Icons.Rounded.Height,
                        TranslationManager.getString("guide_height_up_title", currentLang),
                        TranslationManager.formatString("guide_height_up_sub_fmt", currentLang, distStr),
                        cs.secondary
                    )
                    3 -> Quad(
                        Icons.Rounded.ViewInAr,
                        TranslationManager.getString("guide_box_diag_title", currentLang),
                        TranslationManager.formatString("guide_box_diag_sub_fmt", currentLang, distStr),
                        cs.tertiary
                    )
                    4 -> {
                        if (capturedPointsCount == 2) {
                            Quad(
                                Icons.Rounded.Adjust,
                                TranslationManager.getString("guide_circle_3_title", currentLang),
                                TranslationManager.formatString("guide_circle_fit_fmt", currentLang, distStr),
                                cs.primary
                            )
                        } else {
                            Quad(
                                Icons.Rounded.Adjust,
                                TranslationManager.getString("guide_circle_2_title", currentLang),
                                TranslationManager.formatString("guide_circle_preview_fmt", currentLang, distStr),
                                cs.primary
                            )
                        }
                    }
                    5 -> {
                        if (capturedPointsCount == 2) {
                            val angleStr = liveAngleDegrees?.let {
                                String.format(java.util.Locale.US, "%.1f°", it)
                            } ?: distStr
                            Quad(
                                Icons.Rounded.Architecture,
                                TranslationManager.getString("guide_angle_2_title", currentLang),
                                TranslationManager.formatString("guide_angle_live_fmt", currentLang, angleStr),
                                cs.secondary
                            )
                        } else {
                            Quad(
                                Icons.Rounded.Architecture,
                                TranslationManager.getString("guide_angle_vertex_title", currentLang),
                                TranslationManager.formatString("guide_angle_edge1_fmt", currentLang, distStr),
                                cs.secondary
                            )
                        }
                    }
                    else -> Quad(
                        Icons.Rounded.Straighten,
                        TranslationManager.getString("guide_end_title", currentLang),
                        TranslationManager.formatString("guide_end_sub_fmt", currentLang, distStr),
                        cs.primary
                    )
                }
            }
            subMode == 1 && capturedPointsCount >= 3 && isMeasurementAvailable -> {
                val distStr = liveDistanceMeters?.let { viewModel.formatLength(it, selectedUnit) }
                    ?: TranslationManager.getString("ar_measuring", currentLang)
                Quad(
                    Icons.Rounded.SquareFoot,
                    TranslationManager.formatString("guide_area_done_fmt", currentLang, capturedPointsCount),
                    TranslationManager.formatString("guide_edge_done_fmt", currentLang, distStr),
                    cs.tertiary
                )
            }
            subMode == 3 && capturedPointsCount >= 2 && isMeasurementAvailable -> {
                Quad(
                    Icons.Rounded.ViewInAr,
                    TranslationManager.getString("guide_vol_done_title", currentLang),
                    TranslationManager.formatString(
                        "guide_vol_done_fmt",
                        currentLang,
                        viewModel.formatVolume(viewModel.calculateBoundingBox().volume, selectedUnit)
                    ),
                    cs.tertiary
                )
            }
            subMode == 4 && capturedPointsCount >= 2 && isMeasurementAvailable -> {
                val distStr = liveDistanceMeters?.let { viewModel.formatLength(it, selectedUnit) }
                    ?: TranslationManager.getString("ar_measuring", currentLang)
                if (capturedPointsCount >= 3) {
                    Quad(
                        Icons.Rounded.Adjust,
                        TranslationManager.getString("guide_circle_done_title", currentLang),
                        TranslationManager.formatString("guide_circle_done_fmt", currentLang, distStr),
                        cs.tertiary
                    )
                } else {
                    Quad(
                        Icons.Rounded.Adjust,
                        TranslationManager.getString("guide_circle_3_add_title", currentLang),
                        TranslationManager.formatString("guide_circle_add_sub_fmt", currentLang, distStr),
                        cs.primary
                    )
                }
            }
            subMode == 5 && capturedPointsCount >= 3 && isMeasurementAvailable -> {
                Quad(
                    Icons.Rounded.Architecture,
                    TranslationManager.getString("guide_angle_done_title", currentLang),
                    TranslationManager.formatString("guide_angle_done_fmt", currentLang, Math.round(viewModel.calculateAngle()).toString()),
                    cs.tertiary
                )
            }
            isMeasurementAvailable -> {
                val nextSegment = (capturedPointsCount / 2) + 1
                val role = when (subMode) {
                    1 -> TranslationManager.getString("guide_set_area_1", currentLang)
                    2 -> TranslationManager.getString("guide_set_ground_base", currentLang)
                    3 -> TranslationManager.getString("guide_set_box_start", currentLang)
                    4 -> TranslationManager.getString("guide_set_circle_1", currentLang)
                    5 -> TranslationManager.getString("guide_set_angle_1", currentLang)
                    else -> if (capturedPointsCount == 0) {
                        TranslationManager.getString("guide_origin_point", currentLang)
                    } else {
                        TranslationManager.formatString("guide_segment_start_fmt", currentLang, nextSegment)
                    }
                }
                Quad(
                    Icons.Rounded.Adjust,
                    role,
                    TranslationManager.getString("guide_aim_tap_plus", currentLang),
                    cs.primary
                )
            }
            else -> {
                Quad(
                    Icons.Rounded.Radar,
                    TranslationManager.getString("guide_scanning_title", currentLang),
                    TranslationManager.getString("guide_scanning_sub", currentLang),
                    cs.secondary
                )
            }
        }
    }

    val animatedAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "guidanceAccentColor"
    )
    val pillScale by animateFloatAsState(
        targetValue = if (isSnapped) 1.04f else 1.0f,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "guidancePillPopScale"
    )

    val isDark = com.example.ui.theme.LocalAppDarkTheme.current
    val pillContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.82f else 0.90f)
    val pillTitleColor = MaterialTheme.colorScheme.onSurface
    val pillSubtitleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = pillContainerColor,
        border = BorderStroke(1.dp, animatedAccent.copy(alpha = 0.45f)),
        shadowElevation = 0.dp,
        modifier = modifier
            .widthIn(max = 360.dp)
            .heightIn(min = 36.dp)
            .padding(horizontal = 8.dp)
            .graphicsLayer {
                scaleX = pillScale
                scaleY = pillScale
            }
            .animateContentSize(animationSpec = ExpressiveMotion.spatialDefault())
            .testTag("measurement_guidance_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isScanningPlanes) {
                ExpressiveContainedLoadingIndicator(
                    containerSize = 26.dp,
                    indicatorSize = 15.dp,
                    containerColor = animatedAccent.copy(alpha = 0.22f),
                    indicatorColor = animatedAccent
                )
            } else {
                val badgeShape = if (isSnapped) ExpressiveCookie8Shape else CircleShape
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(badgeShape)
                        .background(animatedAccent.copy(alpha = 0.22f))
                        .graphicsLayer {
                            if (isSnapped) {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                        }
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = animatedAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = pillTitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle.isNotBlank() && subtitle != title) {
                    val displaySub = if (subtitle.contains("：") || subtitle.contains(":")) {
                        subtitle.substringAfter("：").substringAfter(":").trim().ifEmpty { subtitle }
                    } else {
                        subtitle
                    }
                    Text(
                        text = displaySub,
                        style = ExpressiveTypography.labelNumeral,
                        fontSize = 11.sp,
                        color = pillSubtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
