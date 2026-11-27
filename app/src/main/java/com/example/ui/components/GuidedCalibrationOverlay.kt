package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.logic.TranslationManager
import com.example.ui.i18n.LocalAppLanguage
import com.example.ui.i18n.tr
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.ExpressiveTypography
import com.example.ui.theme.GoogleSansRounded
import com.example.ui.viewmodel.CalibrationReferencePreset
import com.example.ui.viewmodel.CalibrationStep
import com.example.ui.viewmodel.GuidedCalibrationState
import com.example.ui.viewmodel.MeasureViewModel
import java.util.Locale
import kotlin.math.abs

@Composable
fun GuidedCalibrationOverlay(
    viewModel: MeasureViewModel,
    modifier: Modifier = Modifier
) {
    val calibrationState by viewModel.guidedCalibrationState.collectAsStateWithLifecycle()
    val scaleCalibrationFactor by viewModel.scaleCalibrationFactor.collectAsStateWithLifecycle()
    val selectedUnit by viewModel.selectedUnit.collectAsStateWithLifecycle()

    if (!calibrationState.isActive) return

    GuidedCalibrationOverlayContent(
        state = calibrationState,
        currentScaleFactor = scaleCalibrationFactor,
        selectedUnit = selectedUnit,
        formatLength = { meters, unit -> viewModel.formatLength(meters, unit) },
        onSelectPreset = { viewModel.selectCalibrationPreset(it) },
        onUpdateCustomInput = { text, unit -> viewModel.updateCalibrationCustomInput(text, unit) },
        onBeginAiming = { viewModel.beginCalibrationAiming() },
        onRemeasure = { viewModel.remeasureCalibration() },
        onApply = { viewModel.applyGuidedCalibration() },
        onResetUnity = { viewModel.resetScaleCalibrationToUnity() },
        onCancel = { viewModel.cancelGuidedCalibration() },
        modifier = modifier
    )
}

@Composable
fun GuidedCalibrationOverlayContent(
    state: GuidedCalibrationState,
    currentScaleFactor: Float,
    selectedUnit: String,
    formatLength: (Double, String) -> String,
    onSelectPreset: (CalibrationReferencePreset) -> Unit,
    onUpdateCustomInput: (String, String) -> Unit,
    onBeginAiming: () -> Unit,
    onRemeasure: () -> Unit,
    onApply: () -> Unit,
    onResetUnity: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentLang = LocalAppLanguage.current
    val cs = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.step) {
        if (state.step != CalibrationStep.SELECT_REFERENCE) {
            focusManager.clearFocus(force = true)
        }
    }

    when (state.step) {
        CalibrationStep.SELECT_REFERENCE -> {
            val dialogFocusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val dismissSelect = {
                try {
                    keyboardController?.hide()
                    dialogFocusManager.clearFocus(force = true)
                } catch (_: Throwable) {}
                onCancel()
            }
            BackHandler(enabled = true, onBack = dismissSelect)
            DisposableEffect(Unit) {
                onDispose {
                    try {
                        keyboardController?.hide()
                        dialogFocusManager.clearFocus(force = true)
                    } catch (_: Throwable) {}
                }
            }
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(cs.scrim.copy(alpha = 0.56f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = dismissSelect
                    )
                    .systemBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = cs.surfaceContainerHigh,
                    border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {}
                        )
                        .testTag("calibration_select_dialog")
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = cs.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Straighten,
                                            contentDescription = null,
                                            tint = cs.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = tr("calib_wizard_title"),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = cs.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    keyboardController?.hide()
                                    dialogFocusManager.clearFocus(force = true)
                                    onCancel()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("calib_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = tr("calib_cancel_btn"),
                                    tint = cs.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = tr("calib_select_desc"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            color = cs.onSurfaceVariant
                        )

                        val presets = listOf(
                            Triple(
                                CalibrationReferencePreset.ID_CARD,
                                "calib_preset_card",
                                "calib_preset_card_sub" to "calib_preset_card"
                            ),
                            Triple(
                                CalibrationReferencePreset.A4_SHORT,
                                "calib_preset_a4_short",
                                "calib_preset_a4_short_sub" to "calib_preset_a4_short"
                            ),
                            Triple(
                                CalibrationReferencePreset.A4_LONG,
                                "calib_preset_a4_long",
                                "calib_preset_a4_long_sub" to "calib_preset_a4_long"
                            ),
                            Triple(
                                CalibrationReferencePreset.CUSTOM,
                                "calib_preset_custom",
                                "calib_preset_custom_sub" to "calib_preset_custom"
                            )
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.forEach { (preset, titleKey, subPair) ->
                                val isSelected = state.selectedPreset == preset
                                val tag = when (preset) {
                                    CalibrationReferencePreset.ID_CARD -> "calib_preset_card"
                                    CalibrationReferencePreset.A4_SHORT -> "calib_preset_a4_short"
                                    CalibrationReferencePreset.A4_LONG -> "calib_preset_a4_long"
                                    CalibrationReferencePreset.CUSTOM -> "calib_preset_custom"
                                }
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isSelected) cs.primaryContainer else cs.surfaceContainerLow,
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) cs.primary else cs.outlineVariant.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { onSelectPreset(preset) }
                                        .testTag(tag)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = tr(titleKey),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontFamily = GoogleSansRounded,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) cs.onPrimaryContainer else cs.onSurface
                                            )
                                            Text(
                                                text = tr(subPair.first),
                                                style = ExpressiveTypography.labelNumeral,
                                                color = if (isSelected) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (state.selectedPreset == CalibrationReferencePreset.CUSTOM) {
                            val isInputValid = state.referenceMeters in 0.02..5.0
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = state.customLengthText,
                                    onValueChange = { onUpdateCustomInput(it, state.customUnit) },
                                    label = { Text(tr("calib_custom_label"), fontFamily = GoogleSansRounded) },
                                    singleLine = true,
                                    isError = !isInputValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    suffix = {
                                        Text(
                                            text = state.customUnit,
                                            style = ExpressiveTypography.labelNumeral,
                                            color = cs.primary
                                        )
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("calib_custom_input")
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("mm", "cm", "m", "in", "ft").forEach { u ->
                                        val selected = state.customUnit == u
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onUpdateCustomInput(state.customLengthText, u) },
                                            label = {
                                                Text(
                                                    text = u,
                                                    style = ExpressiveTypography.labelNumeral
                                                )
                                            },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("calib_unit_chip_$u")
                                        )
                                    }
                                }
                                if (!isInputValid) {
                                    Text(
                                        text = tr("calib_custom_range_err"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.error,
                                        fontFamily = GoogleSansRounded
                                    )
                                }
                            }
                        }

                        if (abs(currentScaleFactor - 1.0f) > 0.002f) {
                            OutlinedButton(
                                onClick = onResetUnity,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("calib_reset_unity_button")
                            ) {
                                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = tr("calib_reset_unity_btn"),
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    keyboardController?.hide()
                                    dialogFocusManager.clearFocus(force = true)
                                    focusManager.clearFocus(force = true)
                                    onCancel()
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = tr("calib_cancel_btn"),
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Button(
                                onClick = {
                                    keyboardController?.hide()
                                    dialogFocusManager.clearFocus(force = true)
                                    focusManager.clearFocus(force = true)
                                    onBeginAiming()
                                },
                                enabled = state.referenceMeters in 0.02..5.0,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .heightIn(min = 48.dp)
                                    .testTag("calib_start_aiming_button")
                            ) {
                                Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = tr("calib_start_btn"),
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        CalibrationStep.AIM_POINT_1, CalibrationStep.AIM_POINT_2 -> {
            Box(modifier = modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 178.dp)
                ) {
                    val stepIndex = if (state.step == CalibrationStep.AIM_POINT_1) 1 else 2
                    val refLabel = formatLength(state.referenceMeters, selectedUnit)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = cs.primaryContainer.copy(alpha = 0.94f),
                        contentColor = cs.onPrimaryContainer,
                        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.55f)),
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .testTag("calib_aiming_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = cs.primary
                            ) {
                                Text(
                                    text = "$stepIndex/2",
                                    style = ExpressiveTypography.labelNumeral,
                                    color = cs.onPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = tr("calib_aim_badge"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = TranslationManager.formatString("calib_guide_sub_fmt", currentLang, refLabel),
                                    style = ExpressiveTypography.labelNumeral,
                                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("calib_cancel_aiming_button")
                            ) {
                                Text(
                                    text = tr("calib_cancel_btn"),
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }

        CalibrationStep.REVIEW_RESULT -> {
            BackHandler(enabled = true, onBack = onCancel)
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(cs.scrim.copy(alpha = 0.56f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onCancel
                    )
                    .systemBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = cs.surfaceContainerHigh,
                    border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {}
                        )
                        .testTag("calibration_review_dialog")
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = cs.tertiaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Verified,
                                        contentDescription = null,
                                        tint = cs.onTertiaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Text(
                                text = tr("calib_review_title"),
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.ExtraBold,
                                color = cs.onSurface
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = cs.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CalibrationMetricRow(
                                    label = tr("calib_label_true_len"),
                                    value = formatLength(state.referenceMeters, selectedUnit),
                                    valueColor = cs.onSurface
                                )
                                CalibrationMetricRow(
                                    label = tr("calib_label_raw_len"),
                                    value = state.rawMeasuredMeters?.let { formatLength(it, selectedUnit) } ?: "--",
                                    valueColor = cs.onSurface
                                )
                                val errSign = if (state.errorPercent >= 0) "+" else ""
                                CalibrationMetricRow(
                                    label = tr("calib_label_deviation"),
                                    value = String.format(Locale.US, "%s%.1f%%", errSign, state.errorPercent),
                                    valueColor = if (abs(state.errorPercent) > 5.0) cs.error else cs.tertiary
                                )
                                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                                CalibrationMetricRow(
                                    label = tr("calib_label_new_scale"),
                                    value = String.format(
                                        Locale.US,
                                        "%.3fx → %.3fx",
                                        currentScaleFactor,
                                        state.clampedFactor
                                    ),
                                    valueColor = cs.primary,
                                    emphasize = true
                                )
                            }
                        }

                        if (state.isOutOfRecommendedRange) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = cs.errorContainer.copy(alpha = 0.9f),
                                border = BorderStroke(1.dp, cs.error.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("calib_out_of_range_warning")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = cs.onErrorContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = TranslationManager.formatString(
                                            "calib_out_of_range_fmt",
                                            currentLang,
                                            state.proposedFactor,
                                            state.clampedFactor
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = GoogleSansRounded,
                                        color = cs.onErrorContainer
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRemeasure,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("calib_remeasure_button")
                            ) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = tr("calib_remeasure_btn"),
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = onApply,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .heightIn(min = 48.dp)
                                    .testTag("calib_apply_button")
                            ) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = tr("calib_apply_btn"),
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = onResetUnity,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("calib_reset_unity_button")
                            ) {
                                Text(tr("calib_reset_unity_btn"), fontFamily = GoogleSansRounded, fontSize = 13.sp)
                            }
                            TextButton(
                                onClick = onCancel,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(tr("calib_cancel_btn"), fontFamily = GoogleSansRounded, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        CalibrationStep.INACTIVE -> Unit
    }
}

@Composable
private fun CalibrationMetricRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    emphasize: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = GoogleSansRounded,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (emphasize) ExpressiveTypography.titleNumeral else ExpressiveTypography.labelNumeral,
            fontSize = if (emphasize) 16.sp else 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor
        )
    }
}
