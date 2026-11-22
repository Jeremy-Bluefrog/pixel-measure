package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.logic.TranslationManager
import com.example.logic.ar.ArCoreEngineStatus
import com.example.logic.ar.ArTrackingStability
import com.example.logic.ar.StabilityLevel
import com.example.logic.camera.ArVideoRecorder
import com.example.logic.camera.HighSpeedCamera2Manager
import com.example.ui.adaptive.AdaptiveHeightClass
import com.example.ui.adaptive.AdaptiveWidthClass
import com.example.ui.adaptive.AdaptiveWindowState
import com.example.ui.adaptive.FoldPosture
import com.example.ui.adaptive.LocalAdaptiveWindowState
import com.example.ui.components.ActiveTrackingStabilityWarningBanner
import com.example.ui.components.AdaptiveNavigationRail
import com.example.ui.components.ExpressiveSplitButton
import com.example.ui.components.MeasurementGuidancePill
import com.example.ui.components.ModernArBottomDeck
import com.example.ui.components.ModernArTopBar
import com.example.ui.i18n.LocalAppLanguage
import com.example.ui.screens.MainScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rTW-w411dp-h891dp")
class UiFixesAndNavigationRegressionTest {

    private val vmRule = ManagedViewModelRule()
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(vmRule).around(composeTestRule)

    @Test
    fun `MeasureViewModel AR host ownership token prevents stale disposal from clobbering active host`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)

        val oldToken = Any()
        val oldManager = HighSpeedCamera2Manager(app)
        val newToken = Any()
        val newManager = HighSpeedCamera2Manager(app)

        vm.registerArHost(oldToken, oldManager)
        assertTrue(vm.isArHostOwned(oldToken))

        // Newer AR instance registers while old instance is animating out
        vm.registerArHost(newToken, newManager)
        assertFalse(vm.isArHostOwned(oldToken))
        assertTrue(vm.isArHostOwned(newToken))

        // Old instance disposes -> must NOT clear or overwrite active newToken ownership
        vm.pauseArAndCameraIfOwned(oldToken, oldManager)
        vm.releaseVulkanPipelineBuffersIfOwned(oldToken)
        vm.clearArHostIfOwned(oldToken, oldManager)

        assertTrue(vm.isArHostOwned(newToken))

        // Active instance disposes -> cleans up ownership cleanly
        vm.clearArHostIfOwned(newToken, newManager)
        assertFalse(vm.isArHostOwned(newToken))
    }

    @Test
    fun `MeasureViewModel syncs SharedPreferences across instances and consumes pending welcome replay`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("measure_app_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val mainVm = vmRule.create(app)
        val settingsVm = vmRule.create(app)

        // Simulate changes committed in SettingsActivity
        settingsVm.setSelectedUnit("m")
        settingsVm.setUiButtonScale(1.15f)
        settingsVm.requestReplayWelcome()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // MainActivity resumes and syncs from disk consuming pending welcome replay
        mainVm.syncAllPreferencesFromDisk(consumePendingWelcome = true)

        assertEquals("m", mainVm.selectedUnit.value)
        assertEquals(1.15f, mainVm.uiButtonScale.value, 0.001f)
        assertTrue(mainVm.showWelcomeScreen.value)
        assertFalse(prefs.contains("pending_replay_welcome"))
    }

    @Test
    fun `ActiveTrackingStabilityWarningBanner omits torch button under low light`() {
        val lowLightStability = ArTrackingStability(
            confidenceScore = 0.22f,
            level = StabilityLevel.POOR,
            isLightingDeficient = true,
            lightIntensity = 0.05f,
            warningMessage = "光線過暗，追蹤可能不穩"
        )
        val torchLabel = TranslationManager.getString("stab_action_torch", "zh-TW")

        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    ActiveTrackingStabilityWarningBanner(
                        stability = lowLightStability,
                        onActionClick = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tracking_stability_warning_banner").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText(torchLabel).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithTag("stab_diag_button").fetchSemanticsNodes().size)
    }

    @Test
    fun `ActiveTrackingStabilityWarningBanner keeps minimum 48dp touch target on diagnostics action`() {
        val motionStability = ArTrackingStability(
            confidenceScore = 0.35f,
            level = StabilityLevel.LOW,
            isMotionExcessive = true,
            cameraSpeedMps = 1.65f,
            warningMessage = "移動速度過快"
        )

        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    ActiveTrackingStabilityWarningBanner(
                        stability = motionStability,
                        onActionClick = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("stab_diag_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `MeasurementGuidancePill displays scanning planes prompt cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)
        val expectedScanning = TranslationManager.getString("guide_scanning_title", "zh-TW")

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    MeasurementGuidancePill(
                        viewModel = vm,
                        subMode = 0,
                        autoDetectedType = "DISTANCE",
                        planesCount = 0,
                        hasCapturedPoints = false,
                        isWaitingForSecondPoint = false,
                        capturedPointsCount = 0,
                        isMeasurementAvailable = false,
                        isSnapped = false,
                        onOpenTutorial = {},
                        arCoreStatus = ArCoreEngineStatus.ACTIVE,
                        arCoreStatusMessage = ""
                    )
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithTag("measurement_guidance_pill").assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedScanning).assertIsDisplayed()
    }

    @Test
    fun `ModernArTopBar primary controls meet 48dp minimum touch target`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)

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
                        cameraAspectRatio = "FULL",
                        planesCount = 2,
                        isRecordingVideo = false,
                        recordingSeconds = 0,
                        onShowHistoryClick = {},
                        onShowSettingsClick = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("submode_selector_button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("submode_dropdown_button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("unit_selector_button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("flashlight_toggle_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("top_history_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("top_settings_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `ExpressiveSplitButton enforces 48dp minimum height by default`() {
        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    ExpressiveSplitButton(
                        label = "自動偵測",
                        leadingIcon = Icons.Rounded.AutoMode,
                        onPrimaryClick = {},
                        onTrailingClick = {},
                        expanded = false,
                        leadingTestTag = "split_lead_test",
                        trailingTestTag = "split_trail_test"
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("split_lead_test").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("split_trail_test").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `AdaptiveNavigationRail unit and settings buttons meet 48dp minimum touch targets`() {
        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(
                    LocalAppLanguage provides "zh-TW",
                    LocalAdaptiveWindowState provides AdaptiveWindowState(
                        widthClass = AdaptiveWidthClass.EXPANDED,
                        heightClass = AdaptiveHeightClass.MEDIUM,
                        posture = FoldPosture.FLAT
                    )
                ) {
                    AdaptiveNavigationRail(
                        currentMode = 0,
                        onModeSelected = {},
                        cameraLabel = "AR 測量",
                        rulerLabel = "螢幕尺",
                        selectedUnit = "cm",
                        onSelectUnit = {},
                        isTorchOn = false,
                        onToggleTorch = {},
                        recordCount = 3,
                        isHistoryOpen = false,
                        onToggleHistory = {},
                        onOpenSettings = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rail_unit_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("rail_settings_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `ModernArBottomDeck keeps bounded 48dp+ controls at scaled uiButtonScale values`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)
        val recorder = ArVideoRecorder(app)
        var uiScale by mutableFloatStateOf(1.15f)

        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppLanguage provides "zh-TW") {
                    ModernArBottomDeck(
                        viewModel = vm,
                        subMode = 0,
                        selectedUnit = "cm",
                        hasCapturedPoints = false,
                        isWaitingForSecondPoint = false,
                        capturedPointsCount = 0,
                        isMeasurementAvailable = true,
                        uiButtonScale = uiScale,
                        isRecordingVideo = false,
                        bottomPadding = 0.dp,
                        textureViewRef = { null },
                        modernArVulkanViewRef = { null },
                        cameraAspectRatio = "FULL",
                        useDisplayP3ColorSpace = true,
                        videoRecorder = recorder,
                        onShowHistoryClick = {},
                        onTriggerShutterFlash = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_point_fab").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("camera_shutter_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        uiScale = 1.30f
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_point_fab").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("camera_shutter_button").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `MainScreen launches native SettingsActivity via Intent and switches AR to Ruler and back without crash`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        app.getSharedPreferences("measure_app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("has_seen_welcome_screen", true)
            .commit()

        val vm = vmRule.create(app)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                MainScreen(viewModel = vm)
            }
        }
        composeTestRule.mainClock.advanceTimeBy(400)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Click Settings -> launches native SettingsActivity via OS Intent instead of in-app overlay
        composeTestRule.onNodeWithTag("top_settings_button").assertIsDisplayed().performClick()
        composeTestRule.mainClock.advanceTimeBy(200)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val startedIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
            ?: Shadows.shadowOf(app).nextStartedActivity
            ?: org.robolectric.shadows.ShadowInstrumentation.getInstrumentation().nextStartedActivity
        assertNotNull("Clicking Settings should launch SettingsActivity via Intent", startedIntent)
        assertEquals(SettingsActivity::class.java.name, startedIntent?.component?.className)

        // Switch AR (0) -> Screen Ruler (1) -> AR (0) with pure fade transition and no crash
        composeTestRule.onNodeWithTag("segmented_button_ruler").assertIsDisplayed().performClick()
        composeTestRule.mainClock.advanceTimeBy(450)
        assertEquals(1, vm.currentMode.value)
        composeTestRule.onNodeWithTag("ruler_screen").assertIsDisplayed()

        composeTestRule.onNodeWithTag("segmented_button_camera").assertIsDisplayed().performClick()
        composeTestRule.mainClock.advanceTimeBy(450)
        assertEquals(0, vm.currentMode.value)
        composeTestRule.onNodeWithTag("top_settings_button").assertIsDisplayed()
    }

    @Test
    fun `MainActivity and SettingsActivity lifecycle create resume and destroy cleanly`() {
        val mainController = Robolectric.buildActivity(MainActivity::class.java)
        try {
            val mainActivity = mainController.create().start().resume().get()
            assertNotNull(mainActivity)
            mainController.pause().stop()
        } finally {
            mainController.destroy()
        }

        val settingsController = Robolectric.buildActivity(SettingsActivity::class.java)
        try {
            val settingsActivity = settingsController.create().start().resume().get()
            assertNotNull(settingsActivity)
            settingsController.pause().stop()
        } finally {
            settingsController.destroy()
        }
    }
}
