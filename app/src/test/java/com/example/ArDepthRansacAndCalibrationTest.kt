package com.example

import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.logic.TranslationManager
import com.example.logic.ar.ArMath
import com.example.logic.camera.HighSpeedCamera2Manager
import com.example.logic.sensor.SensorFusionCorrectionEngine
import com.example.logic.vulkan.VulkanHardwareBufferManager
import com.example.ui.components.GuidedCalibrationOverlayContent
import com.example.ui.components.ModernArTopBar
import com.example.ui.i18n.LocalAppLanguage
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CalibrationReferencePreset
import com.example.ui.viewmodel.CalibrationStep
import com.example.ui.viewmodel.GuidedCalibrationState
import com.example.ui.viewmodel.Point3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rTW-w411dp-h891dp")
class ArDepthRansacAndCalibrationTest {

    private val vmRule = ManagedViewModelRule()
    val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(vmRule).around(composeTestRule)

    @Test
    fun `fitConfidenceWeightedLocalPlane reconstructs 35 degree tilted plane despite outliers`() {
        val tiltRad = Math.toRadians(35.0)
        val trueNx = sin(tiltRad)
        val trueNy = 0.0
        val trueNz = cos(tiltRad)
        // Plane passes through (0, 0, -2.2)
        val px = 0.0
        val py = 0.0
        val pz = -2.2

        // Orthogonal tangent basis on the plane
        val tx = cos(tiltRad)
        val tz = -sin(tiltRad)

        val n = 25
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val zs = DoubleArray(n)
        val ws = DoubleArray(n)

        var idx = 0
        for (row in -2..2) {
            for (col in -2..2) {
                val u = col * 0.04
                val v = row * 0.04
                var x = px + u * tx
                var y = py + v
                var z = pz + u * tz
                var w = 0.90

                // Inject 5 high-magnitude outliers with lower depth confidence
                if (idx == 1 || idx == 6 || idx == 13 || idx == 19 || idx == 23) {
                    x += trueNx * 0.65
                    z += trueNz * 0.65
                    w = 0.25
                }
                xs[idx] = x
                ys[idx] = y
                zs[idx] = z
                ws[idx] = w
                idx++
            }
        }

        val outPlane = DoubleArray(7)
        val outHit = DoubleArray(4)

        val ok = ArMath.fitConfidenceWeightedLocalPlane(
            xs = xs,
            ys = ys,
            zs = zs,
            weights = ws,
            count = n,
            rayOriginX = 0.0,
            rayOriginY = 0.0,
            rayOriginZ = 0.0,
            rayDirX = 0.0,
            rayDirY = 0.0,
            rayDirZ = -1.0,
            inlierDistThreshold = 0.025,
            minCosIncidence = 0.25,
            minT = 0.10,
            maxT = 8.0,
            outPlane7 = outPlane,
            outHitXYZT = outHit
        )

        assertTrue("Expected RANSAC + Jacobi TLS fit to succeed", ok)
        val normalDot = abs(outPlane[0] * trueNx + outPlane[1] * trueNy + outPlane[2] * trueNz)
        assertTrue("Fitted normal should align closely with 35 deg plane (dot=$normalDot)", normalDot > 0.995)
        assertEquals(0.0, outHit[0], 0.01)
        assertEquals(0.0, outHit[1], 0.01)
        assertEquals(-2.2, outHit[2], 0.01)
        assertTrue("Inlier weight ratio should exceed 0.70 (was ${outPlane[6]})", outPlane[6] > 0.70)
    }

    @Test
    fun `fitConfidenceWeightedLocalPlane rejects sparse collinear or steep patches`() {
        val xs = doubleArrayOf(0.0, 0.01, 0.02, 0.03)
        val ys = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val zs = doubleArrayOf(-1.5, -1.5, -1.5, -1.5)
        val ws = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        val outPlane = DoubleArray(7)
        val outHit = DoubleArray(4)

        // Strictly collinear points must be rejected
        assertFalse(
            ArMath.fitConfidenceWeightedLocalPlane(
                xs, ys, zs, ws, 4,
                0.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                outPlane7 = outPlane,
                outHitXYZT = outHit
            )
        )

        // Safe validation of computeIncidenceCosine and computeCalibratedScaleFactor
        val cos35 = ArMath.computeIncidenceCosine(0.0, 0.0, -1.0, sin(Math.toRadians(35.0)), 0.0, cos(Math.toRadians(35.0)))
        assertEquals(cos(Math.toRadians(35.0)).toFloat(), cos35, 1e-4f)
        assertTrue(ArMath.computeCalibratedScaleFactor(0.21, 0.0).isNaN())
        assertEquals(1.05, ArMath.computeCalibratedScaleFactor(0.210, 0.200), 1e-6)
    }

    @Test
    fun `SensorFusionCorrectionEngine increases error budget under oblique and far geometry`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = SensorFusionCorrectionEngine(app)

        engine.updateSurfaceUncertaintyContext(
            depthConfidencePercent = 90,
            surfaceDistanceMeters = 1.0f,
            incidenceCosine = 0.98f,
            rawDepthInlierRatio = 0.95f
        )
        engine.correctPointWithSensorFusion(Point3D(0.0, 0.0, -1.0, isArPrecision = true), null)
        val errSquareClose = engine.telemetry.value.estimatedErrorMm

        engine.updateSurfaceUncertaintyContext(
            depthConfidencePercent = 30,
            surfaceDistanceMeters = 4.6f,
            incidenceCosine = 0.30f,
            rawDepthInlierRatio = 0.35f
        )
        engine.correctPointWithSensorFusion(Point3D(0.0, 0.0, -4.6, isArPrecision = true), Point3D(0.0, 0.0, -1.0))
        val errObliqueFar = engine.telemetry.value.estimatedErrorMm

        assertTrue(
            "Oblique far low-confidence surface must raise error budget (close=$errSquareClose, obliqueFar=$errObliqueFar)",
            errObliqueFar > errSquareClose + 3.0f
        )
    }

    @Test
    fun `MeasureViewModel guided calibration preserves prior points on cancel and applies scale on finish`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)

        vm.setScaleCalibrationFactor(1.0f)
        vm.clearActivePoints()
        vm.capturedPoints.add(Point3D(0.0, 0.0, -1.0, label = "PriorA"))
        vm.capturedPoints.add(Point3D(1.0, 0.0, -1.0, label = "PriorB"))
        vm.updatePointLabel(0, "PriorA")

        // Start & Cancel restores previous points untouched
        vm.startGuidedCalibration()
        assertTrue(vm.guidedCalibrationState.value.isActive)
        assertEquals(CalibrationStep.SELECT_REFERENCE, vm.guidedCalibrationState.value.step)
        assertEquals(0, vm.capturedPoints.size)

        vm.cancelGuidedCalibration()
        assertFalse(vm.guidedCalibrationState.value.isActive)
        assertEquals(2, vm.capturedPoints.size)
        assertEquals("PriorA", vm.capturedPoints[0].label)

        // Start -> Select A4 short edge (0.210m) -> Aim -> Measure 0.200m -> k = 1.050x
        vm.startGuidedCalibration()
        vm.selectCalibrationPreset(CalibrationReferencePreset.A4_SHORT)
        assertEquals(0.210, vm.guidedCalibrationState.value.referenceMeters, 1e-6)
        vm.beginCalibrationAiming()
        assertEquals(CalibrationStep.AIM_POINT_1, vm.guidedCalibrationState.value.step)

        vm.capturedPoints.add(Point3D(0.0, 0.0, -1.0, isArPrecision = true))
        val evalMethod = vm.javaClass.getDeclaredMethod("evaluateCalibrationStepOnPointAdded").apply {
            isAccessible = true
        }
        evalMethod.invoke(vm)
        assertEquals(CalibrationStep.AIM_POINT_2, vm.guidedCalibrationState.value.step)

        vm.capturedPoints.add(Point3D(0.200, 0.0, -1.0, isArPrecision = true))
        evalMethod.invoke(vm)

        val review = vm.guidedCalibrationState.value
        assertEquals(CalibrationStep.REVIEW_RESULT, review.step)
        assertEquals(1.050f, review.proposedFactor, 1e-4f)
        assertEquals(1.050f, review.clampedFactor, 1e-4f)
        assertFalse(review.isOutOfRecommendedRange)

        vm.applyGuidedCalibration()
        assertFalse(vm.guidedCalibrationState.value.isActive)
        assertEquals(1.050f, vm.scaleCalibrationFactor.value, 1e-4f)
        assertEquals(2, vm.capturedPoints.size)
    }

    @Test
    fun `MeasureViewModel clamps excessive calibration factor and supports settings handoff`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)

        vm.requestGuidedCalibrationFromSettings()
        vm.syncAllPreferencesFromDisk(consumePendingWelcome = true)
        assertEquals(CalibrationStep.SELECT_REFERENCE, vm.guidedCalibrationState.value.step)

        vm.updateCalibrationCustomInput("25.0", "cm")
        assertEquals(0.250, vm.guidedCalibrationState.value.referenceMeters, 1e-6)
        vm.beginCalibrationAiming()

        val evalMethod = vm.javaClass.getDeclaredMethod("evaluateCalibrationStepOnPointAdded").apply {
            isAccessible = true
        }
        vm.capturedPoints.add(Point3D(0.0, 0.0, -1.0, isArPrecision = true))
        evalMethod.invoke(vm)
        // Raw = 0.200m vs True = 0.250m -> k = 1.25x -> out of range, clamped to 1.10x
        vm.capturedPoints.add(Point3D(0.200, 0.0, -1.0, isArPrecision = true))
        evalMethod.invoke(vm)

        val review = vm.guidedCalibrationState.value
        assertEquals(CalibrationStep.REVIEW_RESULT, review.step)
        assertEquals(1.25f, review.proposedFactor, 1e-3f)
        assertEquals(1.10f, review.clampedFactor, 1e-4f)
        assertTrue(review.isOutOfRecommendedRange)
        vm.cancelGuidedCalibration()
    }

    @Test
    fun `GuidedCalibrationOverlayContent handles custom validation, aiming banner, and review apply`() {
        var state by mutableStateOf(
            GuidedCalibrationState(
                step = CalibrationStep.SELECT_REFERENCE,
                selectedPreset = CalibrationReferencePreset.CUSTOM,
                customLengthText = "-1",
                customUnit = "cm",
                referenceMeters = 0.0
            )
        )
        var applied = false

        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    GuidedCalibrationOverlayContent(
                        state = state,
                        currentScaleFactor = 1.0f,
                        selectedUnit = "cm",
                        formatLength = { m, _ -> String.format("%.1f cm", m * 100.0) },
                        onSelectPreset = {},
                        onUpdateCustomInput = { txt, unit ->
                            val v = txt.toDoubleOrNull() ?: 0.0
                            state = state.copy(
                                customLengthText = txt,
                                customUnit = unit,
                                referenceMeters = if (v in 2.0..500.0) v / 100.0 else 0.0
                            )
                        },
                        onBeginAiming = {},
                        onRemeasure = {},
                        onApply = { applied = true },
                        onResetUnity = {},
                        onCancel = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calibration_select_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calib_start_aiming_button").assertIsNotEnabled()
        composeTestRule.onNodeWithText(TranslationManager.getString("calib_custom_range_err", "zh-TW")).assertIsDisplayed()

        composeTestRule.onNodeWithTag("calib_custom_input").performTextReplacement("21.0")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calib_start_aiming_button").assertIsEnabled()

        state = state.copy(step = CalibrationStep.AIM_POINT_1, referenceMeters = 0.210)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calib_aiming_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calib_cancel_aiming_button").assertIsDisplayed()

        state = state.copy(
            step = CalibrationStep.REVIEW_RESULT,
            referenceMeters = 0.210,
            rawMeasuredMeters = 0.160,
            proposedFactor = 1.3125f,
            clampedFactor = 1.100f,
            isOutOfRecommendedRange = true,
            errorPercent = -23.8
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calibration_review_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calib_out_of_range_warning").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calib_apply_button").performClick()
        assertTrue(applied)
    }

    @Test
    fun `ArMath findStrongestLocalEdgeXY snaps to step edge and rejects blank wall`() {
        val w = 48
        val h = 48
        val stepBuf = ByteBuffer.allocateDirect(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                stepBuf.put(y * w + x, if (x < 26) 30.toByte() else 220.toByte())
            }
        }
        val outXY = FloatArray(2)
        val mag = ArMath.findStrongestLocalEdgeXY(
            luma = stepBuf,
            width = w,
            height = h,
            rowStride = w,
            pixelStride = 1,
            centerX = 22f,
            centerY = 24f,
            searchRadiusPx = 10,
            minGradientThreshold = 42f,
            outBestXY = outXY
        )
        assertTrue("Expected crisp gradient magnitude on step edge, got $mag", mag > 42f)
        assertTrue("Expected edge snap near x=25.5 (got ${outXY[0]})", outXY[0] in 24.5f..26.5f)

        val blankWall = ByteBuffer.allocateDirect(w * h)
        for (i in 0 until w * h) blankWall.put(i, 128.toByte())
        val wallMag = ArMath.findStrongestLocalEdgeXY(
            luma = blankWall,
            width = w,
            height = h,
            rowStride = w,
            pixelStride = 1,
            centerX = 24f,
            centerY = 24f,
            searchRadiusPx = 10,
            minGradientThreshold = 42f,
            outBestXY = outXY
        )
        assertEquals(0f, wallMag, 1e-4f)
    }

    @Test
    fun `HighSpeedCamera2Manager selectOptimalAfMode prefers CONTINUOUS_VIDEO under SharedCamera`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = HighSpeedCamera2Manager(app)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(chars).set(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        )

        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            manager.selectOptimalAfMode(chars, preferVideoAf = true)
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            manager.selectOptimalAfMode(chars, preferVideoAf = false)
        )
    }

    @Test
    fun `VulkanHardwareBufferManager recordFrameHandoff increments stats and stops after release`() {
        val mgr = VulkanHardwareBufferManager(1280, 720)
        assertEquals(0L, mgr.getStats().producedFrames)
        assertEquals(0L, mgr.getStats().acquiredFrames)

        mgr.recordFrameHandoff(hasHardwareBuffer = true)
        mgr.recordFrameHandoff(hasHardwareBuffer = false)
        val midStats = mgr.getStats()
        assertEquals(2L, midStats.producedFrames)
        assertEquals(1L, midStats.acquiredFrames)
        assertFalse(midStats.isReleased)

        mgr.release()
        mgr.recordFrameHandoff(hasHardwareBuffer = true)
        val afterStats = mgr.getStats()
        assertEquals(2L, afterStats.producedFrames)
        assertEquals(1L, afterStats.acquiredFrames)
        assertTrue(afterStats.isReleased)
    }

    @Test
    fun `MeasureViewModel clearActivePoints resets snap and surface quality states`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)
        vm.capturedPoints.add(Point3D(0.0, 0.0, -1.0, isArPrecision = true))

        vm.clearActivePoints()
        assertFalse(vm.isSnapped.value)
        assertFalse(vm.isImageEdgeSnapped.value)
        assertEquals(0, vm.capturedPoints.size)
        assertEquals(com.example.logic.ar.GeometryWarning.NONE, vm.surfaceQuality.value.warning)
    }

    @Test
    fun `ModernArTopBar calibration factor pill visibility and tap opens calibration wizard`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)
        vm.setScaleCalibrationFactor(1.000f)

        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    ModernArTopBar(
                        viewModel = vm,
                        context = app,
                        subMode = 0,
                        autoDetectedType = "DISTANCE",
                        selectedUnit = "cm",
                        isTorchOn = false,
                        torchBrightness = 1.0f,
                        cameraAspectRatio = "16:9",
                        planesCount = 0,
                        isRecordingVideo = false,
                        recordingSeconds = 0,
                        onShowHistoryClick = {},
                        onShowSettingsClick = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("calibration_factor_pill").assertCountEquals(0)

        vm.setScaleCalibrationFactor(1.045f)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calibration_factor_pill").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.045x").assertIsDisplayed()

        composeTestRule.onNodeWithTag("calibration_factor_pill").performClick()
        composeTestRule.waitForIdle()
        assertTrue(vm.guidedCalibrationState.value.isActive)
        assertEquals(CalibrationStep.SELECT_REFERENCE, vm.guidedCalibrationState.value.step)
        vm.cancelGuidedCalibration()
    }
}
