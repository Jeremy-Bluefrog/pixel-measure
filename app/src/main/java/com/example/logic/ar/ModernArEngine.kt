package com.example.logic.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.example.logic.TranslationManager
import com.example.ui.viewmodel.Point3D
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumSet

/**
 * Geometric information about a detected surface in AR space.
 */
data class DetectedPlaneInfo(
    val id: String,
    val type: Plane.Type,
    val centerPose: Pose,
    val extentX: Float,
    val extentZ: Float,
    val polygonVertices: FloatArray,
    val isTracking: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DetectedPlaneInfo
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

/**
 * Enhanced data packet extracted from an AR frame on the GL rendering thread.
 */
data class ModernArFrame(
    val trackingState: TrackingState,
    val trackingFailureReason: TrackingFailureReason,
    val viewMatrix: FloatArray,
    val projectionMatrix: FloatArray,
    val pointCloud: FloatArray?,
    val planes: List<DetectedPlaneInfo>,
    val cameraPoseX: Float,
    val cameraPoseY: Float,
    val cameraPoseZ: Float,
    val cameraPitch: Float,
    val cameraYaw: Float,
    val isDepthAvailable: Boolean,
    val lightIntensity: Float,
    val surfaceTypeAtCenter: String,
    val stability: ArTrackingStability = ArTrackingStability.default()
)

/**
 * Modern AR Engine managing ARCore Session lifecycle, configurations,
 * multi-tier hit-testing (Planes, Depth, InstantPlacement, Points),
 * and dynamic Anchor management for zero-drift physical tracking.
 */
enum class ArCoreEngineStatus {
    ACTIVE,
    NEEDS_INSTALLATION,
    UNSUPPORTED,
    ERROR
}

/**
 * Modern AR Engine managing ARCore Session lifecycle, configurations,
 * multi-tier hit-testing (Planes, Depth, InstantPlacement, Points),
 * and dynamic Anchor management for zero-drift physical tracking.
 */
class ModernArEngine(private val context: Context) {

    @Volatile
    var session: Session? = null
        private set

    private val arSessionLock = Any()

    val sharedCamera: SharedCamera?
        get() = try {
            session?.sharedCamera
        } catch (_: Throwable) {
            null
        }

    var isSupported: Boolean = false
        private set

    @Volatile
    var currentLangCode: String = "zh-TW"

    private val _arCoreStatus = MutableStateFlow(ArCoreEngineStatus.UNSUPPORTED)
    val arCoreStatus: StateFlow<ArCoreEngineStatus> = _arCoreStatus.asStateFlow()

    private val _arCoreStatusMessage = MutableStateFlow(TranslationManager.getString("arcore_checking", currentLangCode))
    val arCoreStatusMessage: StateFlow<String> = _arCoreStatusMessage.asStateFlow()

    var is60FpsActive: Boolean = false
        private set

    var isDepthModeActive: Boolean = false
        private set

    var isTorchActive: Boolean = false
        private set

    @Volatile
    var isSessionResumed: Boolean = false
        private set

    @Volatile
    var hasNativeSessionResumed: Boolean = false
        private set

    @Volatile
    var isExposeHardwareBufferActive: Boolean = false
        private set

    var selectedCameraId: String? = null
        private set

    var selectedCameraImageSize: Size? = null
        private set

    var selectedCameraFpsRange: android.util.Range<Int>? = null
        private set

    var isRawDepthConfidenceFilterEnabled: Boolean = true
    var rawDepthConfidenceThreshold: Int = 45

    @Volatile
    var preferHighFps: Boolean = true
    @Volatile
    var preferHighDef: Boolean = true
    @Volatile
    var allowDepthMode: Boolean = true

    private var frameCounter = 0
    private var aimMissCounter = 0
    private var cachedPlanes: List<DetectedPlaneInfo> = emptyList()
    private var lastCameraPose: Pose? = null
    private var lastFrameTimeNs: Long = 0L
    private var smoothedSpeedMps: Float = 0.05f
    private var smoothedConfidence: Float = 0.85f
    private var isDepthImageAvailable: Boolean = false

    // Official ARCore requestInstall state: true after INSTALL_REQUESTED so onResume passes userRequestedInstall=false
    private var installRequested = false
    private var eglHelper: HeadlessEglHelper? = null
    private val glContextLock = Any()
    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var viewportChanged = false

    /**
     * Executes an ARCore frame update while holding [arSessionLock] so lifecycle transitions
     * ([pause], [resume], [destroy]) cannot close or tear down EGL/Session mid-frame.
     */
    fun withLockedSessionUpdate(
        fallbackSession: Session? = null,
        block: (Session) -> Unit
    ) {
        synchronized(arSessionLock) {
            if (!isSessionResumed) return
            val activeSession = session ?: fallbackSession ?: return
            block(activeSession)
        }
    }

    // Pre-allocated scratch buffers to eliminate per-frame heap allocations on Pixel
    private val scratchPointIn = FloatArray(3)
    private val scratchPointOut = FloatArray(3)
    private val scratchNormalIn = floatArrayOf(0f, 1f, 0f)
    private val scratchNormalOut = FloatArray(3)
    private val scratchPoseTrans = FloatArray(3)
    private val scratchViewXY = FloatArray(2)
    private val scratchDepthNormXY = FloatArray(2)
    private val scratchRayOrigin = DoubleArray(3)
    private val scratchRayDir = DoubleArray(3)
    private val scratchRayHitOut = DoubleArray(4)
    private var cachedDepthConfidencePercent: Int = -1
    private var cachedCenterDepthMeters: Float = -1f
    private var cachedRawDepthRoiValid: Boolean = false
    private var cachedRawDepthInlierRatio: Float = -1f
    private var cachedRawDepthFitDistance: Float = -1f
    private var lastRoiSampleX: Float = -1f
    private var lastRoiSampleY: Float = -1f
    private var lastRoiSampleWasTap: Boolean = false
    private val cachedRawDepthHitPoseTrans = FloatArray(3)
    private val cachedRawDepthNormal = FloatArray(3)
    private val roiNormCoords = FloatArray(50)
    private val roiViewCoords = FloatArray(50)
    private val roiWorldX = DoubleArray(25)
    private val roiWorldY = DoubleArray(25)
    private val roiWorldZ = DoubleArray(25)
    private val roiWeights = DoubleArray(25)
    private val roiValidFlags = BooleanArray(25)
    private val roiPlaneOut7 = DoubleArray(7)
    private val roiHitOut4 = DoubleArray(4)

    // Zero-alloc 4-slot temporal Raw Depth ROI ring buffer for smoothing + sparse dropout bridging
    private val depthRingCapacity = 4
    private var depthRingHead = 0
    private var depthRingCount = 0
    private val ringTimestampsMs = LongArray(4)
    private val ringHitX = FloatArray(4)
    private val ringHitY = FloatArray(4)
    private val ringHitZ = FloatArray(4)
    private val ringNormX = FloatArray(4)
    private val ringNormY = FloatArray(4)
    private val ringNormZ = FloatArray(4)
    private val ringCamX = FloatArray(4)
    private val ringCamY = FloatArray(4)
    private val ringCamZ = FloatArray(4)
    private val ringTargetX = FloatArray(4)
    private val ringTargetY = FloatArray(4)
    private val ringConfPct = IntArray(4)
    private val ringInlierRatio = FloatArray(4)

    // Throttled zero-alloc scratch buffers for Sobel optical edge snapping & intrinsics fallback
    private var lastEdgeSnapFrame = -10
    private var lastEdgeSnapValid = false
    private var lastEdgeSnapViewX = 0f
    private var lastEdgeSnapViewY = 0f
    private var lastEdgeQueryViewX = -1f
    private var lastEdgeQueryViewY = -1f
    private val edgeQueryImageXY = FloatArray(2)
    private val edgeBestImageXY = FloatArray(2)
    private val edgeBestViewXY = FloatArray(2)
    private val intrinsicsFocal = FloatArray(2)
    private val intrinsicsPrincipal = FloatArray(2)
    private val intrinsicsDims = IntArray(2)
    private val intrinsicsImgXY = FloatArray(2)

    private val scratchQuatRot = FloatArray(4)
    private val neighborDx = floatArrayOf(-4f, 4f, 0f, 0f)
    private val neighborDy = floatArrayOf(0f, 0f, -4f, 4f)
    private val vMatrixPool = Array(2) { FloatArray(16) }
    private val pMatrixPool = Array(2) { FloatArray(16) }
    private var matrixPoolIdx = 0

    companion object {
        /**
         * Checks whether Google Play Store is installed and active on the device.
         */
        fun isGooglePlayStoreAvailable(context: Context): Boolean {
            return try {
                val pm = context.packageManager
                val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo("com.android.vending", android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo("com.android.vending", 0)
                }
                info.applicationInfo?.enabled == true
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Verifies that Play Store's exported ARCore install service
         * (`com.google.android.play.core.install.BIND_INSTALL_SERVICE`) actually resolves
         * before letting ARCore SDK attempt bindService(), avoiding spurious
         * `E/ARCore-InstallService: Play Store install service could not be bound.` errors
         * on emulators or devices without a working Play Store InstallService.
         */
        fun isPlayStoreInstallServiceAvailable(context: Context): Boolean {
            if (!isGooglePlayStoreAvailable(context)) return false
            return try {
                val intent = android.content.Intent("com.google.android.play.core.install.BIND_INSTALL_SERVICE").apply {
                    setPackage("com.android.vending")
                }
                val pm = context.packageManager
                val resolved = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.resolveService(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveService(intent, 0)
                }
                val serviceInfo = resolved?.serviceInfo ?: return false
                if (!serviceInfo.enabled || !serviceInfo.exported) return false
                val reqPerm = serviceInfo.permission
                reqPerm == null || ContextCompat.checkSelfPermission(context, reqPerm) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }

        private fun isEmulatorEnvironment(): Boolean {
            return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("cutf") ||
                Build.PRODUCT.contains("sdk_gphone") ||
                Build.PRODUCT.contains("emulator") ||
                Build.PRODUCT.contains("simulator")
        }

        fun canQueryPlayStoreForArCoreInstall(context: Context): Boolean {
            if (isEmulatorEnvironment()) return false
            return isPlayStoreInstallServiceAvailable(context)
        }

        /**
         * Checks whether Google Play Services for AR (ARCore) package is installed and enabled on the device.
         */
        fun isArCorePackageInstalled(context: Context): Boolean {
            return try {
                val pm = context.packageManager
                val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo("com.google.ar.core", android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo("com.google.ar.core", 0)
                }
                info.applicationInfo?.enabled ?: true
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Opens the Google Play Store or web listing for com.google.ar.core.
         */
        fun openArCorePlayStorePage(context: Context): Boolean {
            val uris = listOf(
                android.net.Uri.parse("market://details?id=com.google.ar.core"),
                android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.ar.core")
            )
            for (uri in uris) {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return true
                } catch (_: Throwable) {}
            }
            return false
        }
    }

    @Volatile
    private var playStoreInstallServiceBindFailed = false

    /**
     * Primes ARCore availability cache early in Activity.onCreate per official Google ARCore guidelines.
     * Never returns UNKNOWN_CHECKING and invokes callback on Main thread.
     * Skips ARCore SDK Play Store binding when `com.google.ar.core` is missing and Play Store's
     * install service is not resolvable/bindable on the current device/emulator.
     */
    fun warmUpAvailability(onResult: ((ArCoreApk.Availability) -> Unit)? = null) {
        if (!isArCorePackageInstalled(context) &&
            (!canQueryPlayStoreForArCoreInstall(context) || playStoreInstallServiceBindFailed)
        ) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
            onResult?.invoke(ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED)
            return
        }
        try {
            ArCoreApk.getInstance().checkAvailabilityAsync(context.applicationContext) { availability ->
                when (availability) {
                    ArCoreApk.Availability.UNKNOWN_ERROR -> {
                        if (!isArCorePackageInstalled(context)) {
                            playStoreInstallServiceBindFailed = true
                            isSupported = false
                            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                            _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
                        }
                    }
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                        if (!isRobolectricEnvironment()) {
                            isSupported = false
                            _arCoreStatus.value = ArCoreEngineStatus.UNSUPPORTED
                            _arCoreStatusMessage.value = TranslationManager.getString("arcore_unsupported", currentLangCode)
                        }
                    }
                    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                        isSupported = false
                        _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                        _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
                    }
                    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                        isSupported = false
                        _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                        _arCoreStatusMessage.value = TranslationManager.getString("arcore_too_old", currentLangCode)
                    }
                    ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                        isSupported = true
                        if (_arCoreStatus.value != ArCoreEngineStatus.ACTIVE) {
                            _arCoreStatusMessage.value = TranslationManager.getString("arcore_ready_pending", currentLangCode)
                        }
                    }
                    else -> {}
                }
                onResult?.invoke(availability)
            }
        } catch (t: Throwable) {
            Log.w("ModernArEngine", "checkAvailabilityAsync note: ${t.message}")
        }
    }

    private fun isRobolectricEnvironment(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    }

    private fun checkAvailabilityWithTransientRetry(): ArCoreApk.Availability {
        val arCoreInstalled = isArCorePackageInstalled(context)
        if (!arCoreInstalled && (!canQueryPlayStoreForArCoreInstall(context) || playStoreInstallServiceBindFailed)) {
            return ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        }
        var availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (_: Throwable) {
            ArCoreApk.Availability.UNKNOWN_ERROR
        }
        var retries = 0
        while (availability.isTransient && retries < 5) {
            retries++
            try {
                Thread.sleep(150L)
                availability = ArCoreApk.getInstance().checkAvailability(context)
            } catch (_: Throwable) {
                break
            }
        }
        if (!arCoreInstalled && availability == ArCoreApk.Availability.UNKNOWN_ERROR) {
            playStoreInstallServiceBindFailed = true
        }
        return availability
    }

    /**
     * Must be called on the UI / Main thread when an Activity is available!
     * Follows Google ARCore's official checkAvailability + requestInstall(activity, userRequestedInstall) flow.
     * Returns true if ARCore is installed and ready for Session creation, or false if installation was requested,
     * unsupported, or declined.
     */
    fun ensureInstallReadyOnMainThread(activity: Activity, explicitUserAction: Boolean = false): Boolean {
        if (explicitUserAction) {
            installRequested = false
            playStoreInstallServiceBindFailed = false
        }

        val arCoreInstalled = isArCorePackageInstalled(context)
        if (!arCoreInstalled && (!canQueryPlayStoreForArCoreInstall(activity) || playStoreInstallServiceBindFailed)) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
            return false
        }

        val availability = checkAvailabilityWithTransientRetry()
        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE && !isRobolectricEnvironment()) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.UNSUPPORTED
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_unsupported", currentLangCode)
            return false
        }

        if (!arCoreInstalled ||
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        ) {
            if (!canQueryPlayStoreForArCoreInstall(activity) || playStoreInstallServiceBindFailed) {
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                _arCoreStatusMessage.value = if (availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD) {
                    TranslationManager.getString("arcore_too_old", currentLangCode)
                } else {
                    TranslationManager.getString("arcore_not_installed", currentLangCode)
                }
                return false
            }
            return try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALLED -> true
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        isSupported = false
                        _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                        _arCoreStatusMessage.value = TranslationManager.getString("arcore_installing", currentLangCode)
                        false
                    }
                }
            } catch (e: UnavailableUserDeclinedInstallationException) {
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_declined", currentLangCode)
                false
            } catch (e: UnavailableDeviceNotCompatibleException) {
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.UNSUPPORTED
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_unsupported", currentLangCode)
                false
            } catch (t: Throwable) {
                Log.w("ModernArEngine", "requestInstall failed: ${t.message}")
                playStoreInstallServiceBindFailed = true
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
                false
            }
        }
        return true
    }

    /**
     * Check device compatibility and create configured ARCore Session safely.
     * Note: When an Activity is provided, callers on background threads should call
     * [ensureInstallReadyOnMainThread] on the Main thread first, or pass [skipInstallPrompt] = true.
     */
    fun checkAndCreateSession(
        activity: Activity? = null,
        skipInstallPrompt: Boolean = false
    ): Session? {
        if (session != null) return session
        if (!isInitializing.compareAndSet(false, true)) {
            return session
        }

        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.i("ModernArEngine", "Camera permission not granted yet; deferring ARCore session creation.")
                return null
            }

            val arCoreInstalled = isArCorePackageInstalled(context)
            if (!arCoreInstalled && (!canQueryPlayStoreForArCoreInstall(context) || playStoreInstallServiceBindFailed)) {
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
                Log.i("ModernArEngine", "ARCore package missing and Play Store InstallService unavailable; skipping ARCore Session creation.")
                return null
            }

            val availability = checkAvailabilityWithTransientRetry()
            if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE && !isRobolectricEnvironment()) {
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.UNSUPPORTED
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_unsupported", currentLangCode)
                Log.w("ModernArEngine", "ArCoreApk reported UNSUPPORTED_DEVICE_NOT_CAPABLE.")
                return null
            }

            if (!arCoreInstalled ||
                availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
                availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
            ) {
                isSupported = false
                _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
                _arCoreStatusMessage.value = if (availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD) {
                    TranslationManager.getString("arcore_too_old", currentLangCode)
                } else {
                    TranslationManager.getString("arcore_not_installed", currentLangCode)
                }
                Log.i("ModernArEngine", "ARCore not ready (installed=$arCoreInstalled, availability=$availability). Skipping Session creation.")
                if (activity != null && !skipInstallPrompt &&
                    android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                    !installRequested && canQueryPlayStoreForArCoreInstall(activity) && !playStoreInstallServiceBindFailed
                ) {
                    try {
                        val status = ArCoreApk.getInstance().requestInstall(activity, true)
                        if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                            installRequested = true
                            return null
                        }
                    } catch (_: Throwable) {
                        playStoreInstallServiceBindFailed = true
                        return null
                    }
                } else {
                    return null
                }
            }

            // First priority: Create ARCore session supporting SharedCamera
            val newSession = try {
                Session(context, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: UnavailableException) {
                throw e
            } catch (e: Throwable) {
                Log.w("ModernArEngine", "SharedCamera session creation note, fallback to standard: ${e.message}")
                Session(context)
            }

            // Align ARCore CameraConfig with Camera2 target FPS (30 vs 60) & resolution (1080p vs 720p).
            // Per official ARCore docs, StereoCameraUsage must be DO_NOT_USE when SharedCamera is enabled.
            try {
                val desiredFpsFlag = if (preferHighFps) CameraConfig.TargetFps.TARGET_FPS_60 else CameraConfig.TargetFps.TARGET_FPS_30
                val isSharedSession = try { newSession.sharedCamera; true } catch (_: Throwable) { false }
                val fpsFilter = CameraConfigFilter(newSession).apply {
                    facingDirection = CameraConfig.FacingDirection.BACK
                    targetFps = EnumSet.of(desiredFpsFlag)
                    if (isSharedSession) {
                        stereoCameraUsage = EnumSet.of(CameraConfig.StereoCameraUsage.DO_NOT_USE)
                    }
                }
                var supportedConfigs = newSession.getSupportedCameraConfigs(fpsFilter)
                if (supportedConfigs.isEmpty()) {
                    val fallbackFilter = CameraConfigFilter(newSession).apply {
                        facingDirection = CameraConfig.FacingDirection.BACK
                        if (isSharedSession) {
                            stereoCameraUsage = EnumSet.of(CameraConfig.StereoCameraUsage.DO_NOT_USE)
                        }
                    }
                    supportedConfigs = newSession.getSupportedCameraConfigs(fallbackFilter)
                }
                if (supportedConfigs.isNotEmpty()) {
                    // Prefer configs using hardware depth sensor if available on Pixel Pro
                    val sortedConfigs = if (allowDepthMode) {
                        supportedConfigs.sortedByDescending { cfg ->
                            if (cfg.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) 1 else 0
                        }
                    } else {
                        supportedConfigs
                    }
                    val preferredConfig = if (preferHighDef) {
                        sortedConfigs.firstOrNull { cfg ->
                            val ts = cfg.textureSize
                            ts.width == 1920 && ts.height == 1080
                        } ?: sortedConfigs.firstOrNull { cfg ->
                            val ts = cfg.textureSize
                            ts.width == 1280 && ts.height == 720
                        }
                    } else {
                        sortedConfigs.firstOrNull { cfg ->
                            val ts = cfg.textureSize
                            ts.width == 1280 && ts.height == 720
                        } ?: sortedConfigs.firstOrNull { cfg ->
                            val ts = cfg.textureSize
                            ts.width == 1920 && ts.height == 1080
                        }
                    } ?: sortedConfigs.firstOrNull { cfg ->
                        val ts = cfg.textureSize
                        kotlin.math.abs((ts.width.toFloat() / ts.height.coerceAtLeast(1)) - (16f / 9f)) < 0.05f
                    } ?: sortedConfigs.first()
                    newSession.cameraConfig = preferredConfig
                    is60FpsActive = try { preferredConfig.fpsRange.upper >= 60 } catch (_: Throwable) { false }
                }
            } catch (cfgEx: Throwable) {
                Log.w("ModernArEngine", "CameraConfig selection fallback: ${cfgEx.message}")
            }

            selectedCameraId = try { newSession.cameraConfig.cameraId } catch (_: Throwable) { null }
            selectedCameraImageSize = try { newSession.cameraConfig.textureSize } catch (_: Throwable) { null }
            selectedCameraFpsRange = try { newSession.cameraConfig.fpsRange } catch (_: Throwable) { null }

            val isSharedSession = try { newSession.sharedCamera; true } catch (_: Throwable) { false }
            val configured = configureSessionWithFallback(newSession, isSharedSession)
            isExposeHardwareBufferActive = configured.textureUpdateMode == Config.TextureUpdateMode.EXPOSE_HARDWARE_BUFFER

            // Only create headless external OES GL texture when ARCore falls back to BIND_TO_TEXTURE_EXTERNAL_OES.
            // Under EXPOSE_HARDWARE_BUFFER (Vulkan / AHardwareBuffer mode), ARCore ignores setCameraTextureName and needs no GL context.
            if (!isExposeHardwareBufferActive) {
                synchronized(glContextLock) {
                    eglHelper?.release()
                    val helper = HeadlessEglHelper()
                    val texId = helper.init()
                    if (texId > 0) {
                        helper.makeCurrent()
                        try {
                            newSession.setCameraTextureName(texId)
                        } finally {
                            helper.detachCurrent()
                        }
                        eglHelper = helper
                    } else {
                        helper.release()
                        eglHelper = null
                        Log.w("ModernArEngine", "HeadlessEglHelper failed to allocate OES texture ID.")
                    }
                }
            } else {
                synchronized(glContextLock) {
                    eglHelper?.release()
                    eglHelper = null
                }
            }

            session = newSession
            isSessionResumed = false
            hasNativeSessionResumed = false
            isSupported = true
            _arCoreStatus.value = ArCoreEngineStatus.ACTIVE
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_active", currentLangCode)
            Log.i("ModernArEngine", "Google ARCore Session configured: camId=$selectedCameraId, textureSize=$selectedCameraImageSize, fps=$selectedCameraFpsRange, depth=$isDepthModeActive, ahb=$isExposeHardwareBufferActive")
            return newSession
        } catch (e: UnavailableDeviceNotCompatibleException) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.UNSUPPORTED
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_unsupported", currentLangCode)
            Log.w("ModernArEngine", "DeviceNotCompatible: ${e.message}")
            return null
        } catch (e: UnavailableArcoreNotInstalledException) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_not_installed", currentLangCode)
            Log.w("ModernArEngine", "ArcoreNotInstalled: ${e.message}")
            return null
        } catch (e: UnavailableApkTooOldException) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_too_old", currentLangCode)
            Log.w("ModernArEngine", "ApkTooOld: ${e.message}")
            return null
        } catch (e: UnavailableSdkTooOldException) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.UNSUPPORTED
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_sdk_too_old", currentLangCode)
            return null
        } catch (e: UnavailableUserDeclinedInstallationException) {
            isSupported = false
            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_declined", currentLangCode)
            return null
        } catch (t: Throwable) {
            Log.e("ModernArEngine", "ARCore Session init exception: ${t.message}", t)
            _arCoreStatus.value = ArCoreEngineStatus.ERROR
            _arCoreStatusMessage.value = TranslationManager.formatString(
                "arcore_init_failed_fmt",
                currentLangCode,
                t.localizedMessage ?: TranslationManager.getString("arcore_unknown", currentLangCode)
            )
            isSupported = false
            return null
        } finally {
            isInitializing.set(false)
        }
    }

    private fun configureSessionWithFallback(newSession: Session, isSharedSession: Boolean): Config {
        val autoDepthSupported = allowDepthMode && try {
            newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (_: Throwable) {
            false
        }
        val rawDepthSupported = allowDepthMode && try {
            newSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        } catch (_: Throwable) {
            false
        }
        val preferredDepthMode = when {
            autoDepthSupported -> Config.DepthMode.AUTOMATIC
            rawDepthSupported -> Config.DepthMode.RAW_DEPTH_ONLY
            else -> Config.DepthMode.DISABLED
        }

        // Google ARCore SharedCamera requires BIND_TO_TEXTURE_EXTERNAL_OES + setCameraTextureName().
        // Attempting EXPOSE_HARDWARE_BUFFER with SharedCamera causes pipeline stall/starvation on Pixel.
        data class SessionAttempt(
            val textureMode: Config.TextureUpdateMode,
            val depthMode: Config.DepthMode,
            val lightMode: Config.LightEstimationMode,
            val instantPlacement: Config.InstantPlacementMode
        )

        val baseAttempts = buildList {
            add(SessionAttempt(Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES, preferredDepthMode, Config.LightEstimationMode.ENVIRONMENTAL_HDR, Config.InstantPlacementMode.LOCAL_Y_UP))
            add(SessionAttempt(Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES, preferredDepthMode, Config.LightEstimationMode.AMBIENT_INTENSITY, Config.InstantPlacementMode.LOCAL_Y_UP))
            if (preferredDepthMode == Config.DepthMode.AUTOMATIC && rawDepthSupported) {
                add(SessionAttempt(Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES, Config.DepthMode.RAW_DEPTH_ONLY, Config.LightEstimationMode.AMBIENT_INTENSITY, Config.InstantPlacementMode.LOCAL_Y_UP))
            }
            add(SessionAttempt(Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES, Config.DepthMode.DISABLED, Config.LightEstimationMode.AMBIENT_INTENSITY, Config.InstantPlacementMode.LOCAL_Y_UP))
            add(SessionAttempt(Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES, Config.DepthMode.DISABLED, Config.LightEstimationMode.AMBIENT_INTENSITY, Config.InstantPlacementMode.DISABLED))
        }
        val attempts = if (isSharedSession) {
            baseAttempts
        } else {
            listOf(
                SessionAttempt(Config.TextureUpdateMode.EXPOSE_HARDWARE_BUFFER, preferredDepthMode, Config.LightEstimationMode.ENVIRONMENTAL_HDR, Config.InstantPlacementMode.LOCAL_Y_UP)
            ) + baseAttempts
        }

        var lastError: Throwable? = null
        for (attempt in attempts) {
            try {
                val config = Config(newSession)
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.lightEstimationMode = attempt.lightMode
                config.depthMode = attempt.depthMode
                config.instantPlacementMode = attempt.instantPlacement
                config.textureUpdateMode = attempt.textureMode
                newSession.configure(config)
                isDepthModeActive = (config.depthMode == Config.DepthMode.AUTOMATIC || config.depthMode == Config.DepthMode.RAW_DEPTH_ONLY)
                return config
            } catch (t: Throwable) {
                lastError = t
                Log.w("ModernArEngine", "ARCore configure attempt failed (tex=${attempt.textureMode}, depth=${attempt.depthMode}, light=${attempt.lightMode}, ip=${attempt.instantPlacement}): ${t.message}")
            }
        }
        throw lastError ?: IllegalStateException("Unable to configure ARCore session")
    }

    /**
     * Dynamically adapts ARCore Depth usage under thermal stress to prevent Tensor throttling.
     */
    fun applyRuntimePerformanceConfig(allowDepth: Boolean) {
        allowDepthMode = allowDepth
        synchronized(arSessionLock) {
            val currentSession = session ?: return
            try {
                val config = currentSession.config
                val desiredDepth = when {
                    !allowDepth -> Config.DepthMode.DISABLED
                    currentSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                    currentSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                    else -> Config.DepthMode.DISABLED
                }
                if (config.depthMode != desiredDepth) {
                    config.depthMode = desiredDepth
                    currentSession.configure(config)
                    isDepthModeActive = (desiredDepth == Config.DepthMode.AUTOMATIC || desiredDepth == Config.DepthMode.RAW_DEPTH_ONLY)
                    if (!isDepthModeActive) resetTemporalDepthBuffer()
                    Log.i("ModernArEngine", "Adjusted ARCore DepthMode=$desiredDepth for thermal/performance policy.")
                }
            } catch (t: Throwable) {
                Log.w("ModernArEngine", "applyRuntimePerformanceConfig skipped: ${t.message}")
            }
        }
    }

    /**
     * User-requested explicit installation of Google Play Services for AR.
     * Must be invoked on the Main/UI thread.
     */
    fun requestInstallArCore(activity: Activity): Boolean {
        if (!canQueryPlayStoreForArCoreInstall(activity)) {
            _arCoreStatusMessage.value = if (isGooglePlayStoreAvailable(activity)) {
                TranslationManager.getString("arcore_play_store_redirect", currentLangCode)
            } else {
                TranslationManager.getString("arcore_no_play_store", currentLangCode)
            }
            Log.i("ModernArEngine", "Play Store InstallService unavailable on this device/emulator.")
            return if (isGooglePlayStoreAvailable(activity)) openArCorePlayStorePage(activity) else false
        }
        return try {
            installRequested = false
            playStoreInstallServiceBindFailed = false
            val status = ArCoreApk.getInstance().requestInstall(activity, true)
            when (status) {
                ArCoreApk.InstallStatus.INSTALLED -> true
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    false
                }
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            _arCoreStatus.value = ArCoreEngineStatus.NEEDS_INSTALLATION
            _arCoreStatusMessage.value = TranslationManager.getString("arcore_declined", currentLangCode)
            false
        } catch (e: Throwable) {
            Log.w("ModernArEngine", "Error requesting ARCore install: ${e.message}")
            playStoreInstallServiceBindFailed = true
            openArCorePlayStorePage(activity)
        }
    }

    fun makeGlContextCurrent(): Boolean {
        if (isExposeHardwareBufferActive) return false
        synchronized(glContextLock) {
            val helper = eglHelper ?: return false
            return try {
                helper.makeCurrent()
            } catch (t: Throwable) {
                Log.w("ModernArEngine", "makeGlContextCurrent error: ${t.message}")
                false
            }
        }
    }

    fun detachGlContext() {
        if (isExposeHardwareBufferActive) return
        synchronized(glContextLock) {
            val helper = eglHelper ?: return
            try {
                helper.detachCurrent()
            } catch (_: Throwable) {}
        }
    }

    fun rebindCameraTexture(): Boolean = synchronized(arSessionLock) {
        if (isExposeHardwareBufferActive) return@synchronized true
        val curSession = session ?: return@synchronized false
        synchronized(glContextLock) {
            return try {
                eglHelper?.release()
                val helper = HeadlessEglHelper()
                val texId = helper.init()
                if (texId > 0) {
                    helper.makeCurrent()
                    try {
                        curSession.setCameraTextureName(texId)
                    } finally {
                        helper.detachCurrent()
                    }
                    eglHelper = helper
                    true
                } else {
                    helper.release()
                    eglHelper = null
                    false
                }
            } catch (t: Throwable) {
                Log.w("ModernArEngine", "rebindCameraTexture error: ${t.message}")
                false
            }
        }
    }

    fun createSession(): Session? {
        return checkAndCreateSession(null)
    }

    fun setTorchMode(enabled: Boolean): Boolean {
        isTorchActive = enabled
        synchronized(arSessionLock) {
            val currentSession = session ?: return false
            return try {
                val config = currentSession.config
                val desiredFlash = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
                if (config.flashMode != desiredFlash) {
                    config.flashMode = desiredFlash
                    currentSession.configure(config)
                    Log.i("ModernArEngine", "ARCore FlashMode configured: ${config.flashMode}")
                }
                true
            } catch (e: Throwable) {
                Log.w("ModernArEngine", "Failed to set ARCore flashMode: ${e.message}")
                false
            }
        }
    }

    fun resetTemporalDepthBuffer() {
        depthRingHead = 0
        depthRingCount = 0
        cachedRawDepthRoiValid = false
        cachedRawDepthInlierRatio = -1f
        cachedRawDepthFitDistance = -1f
        cachedDepthConfidencePercent = -1
        cachedCenterDepthMeters = -1f
        lastEdgeSnapFrame = -10
        lastEdgeSnapValid = false
        lastEdgeQueryViewX = -1f
        lastEdgeQueryViewY = -1f
    }

    private fun resetTransientMotionState() {
        lastCameraPose = null
        lastFrameTimeNs = 0L
        smoothedSpeedMps = 0.05f
        aimMissCounter = 0
        resetTemporalDepthBuffer()
    }

    fun pause() {
        synchronized(arSessionLock) {
            isSessionResumed = false
            isTorchActive = false
            resetTransientMotionState()
            if (!hasNativeSessionResumed) return
            hasNativeSessionResumed = false
            val curSession = session ?: return
            try {
                if (isExposeHardwareBufferActive) {
                    curSession.pause()
                } else {
                    synchronized(glContextLock) {
                        makeGlContextCurrent()
                        try {
                            curSession.pause()
                        } finally {
                            detachGlContext()
                        }
                    }
                }
                Log.i("ModernArEngine", "ARCore session paused.")
            } catch (e: Throwable) {
                Log.w("ModernArEngine", "Failed to pause ARCore session: ${e.message}")
            }
        }
    }

    fun markSessionPaused() {
        isSessionResumed = false
        hasNativeSessionResumed = false
        resetTransientMotionState()
    }

    fun reportRuntimeError(message: String) {
        _arCoreStatusMessage.value = message
    }

    fun resume(
        sharedCaptureCallback: CameraCaptureSession.CaptureCallback? = null,
        sharedCaptureHandler: Handler? = null
    ) {
        synchronized(arSessionLock) {
            val curSession = session ?: return
            if (isSessionResumed && hasNativeSessionResumed) return
            resetTransientMotionState()
            try {
                if (isExposeHardwareBufferActive) {
                    if (viewportWidth > 0 && viewportHeight > 0) {
                        curSession.setDisplayGeometry(lastDisplayRotation, viewportWidth, viewportHeight)
                        viewportChanged = false
                    }
                    curSession.resume()
                } else {
                    synchronized(glContextLock) {
                        if (eglHelper == null) {
                            rebindCameraTexture()
                        }
                        makeGlContextCurrent()
                        try {
                            if (viewportWidth > 0 && viewportHeight > 0) {
                                curSession.setDisplayGeometry(lastDisplayRotation, viewportWidth, viewportHeight)
                                viewportChanged = false
                            }
                            curSession.resume()
                        } finally {
                            detachGlContext()
                        }
                    }
                }
                hasNativeSessionResumed = true
                // Official SharedCamera lifecycle: supply capture callback immediately after session.resume()
                if (sharedCaptureCallback != null) {
                    try {
                        curSession.sharedCamera?.setCaptureCallback(sharedCaptureCallback, sharedCaptureHandler)
                    } catch (cbEx: Throwable) {
                        Log.w("ModernArEngine", "SharedCamera setCaptureCallback note: ${cbEx.message}")
                    }
                }
                // Publish resumed state ONLY after resume + capture callback setup completes
                isSessionResumed = true
                _arCoreStatus.value = ArCoreEngineStatus.ACTIVE
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_active", currentLangCode)
                Log.i("ModernArEngine", "ARCore session resumed successfully (ahb=$isExposeHardwareBufferActive).")
            } catch (e: CameraNotAvailableException) {
                isSessionResumed = false
                hasNativeSessionResumed = false
                _arCoreStatusMessage.value = TranslationManager.getString("arcore_cam_reconnecting", currentLangCode)
                Log.w("ModernArEngine", "Camera not available for ARCore: ${e.message}")
            } catch (e: Exception) {
                isSessionResumed = false
                hasNativeSessionResumed = false
                _arCoreStatusMessage.value = TranslationManager.formatString(
                    "arcore_resume_error_fmt",
                    currentLangCode,
                    e.localizedMessage ?: e.javaClass.simpleName
                )
                Log.e("ModernArEngine", "Failed to resume ARCore session", e)
            }
        }
    }

    var viewportWidth: Int = 1080
        private set
    var viewportHeight: Int = 2400
        private set
    private var lastDisplayRotation: Int = 0

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (lastDisplayRotation != rotation || viewportWidth != width || viewportHeight != height) {
            lastDisplayRotation = rotation
            viewportWidth = width
            viewportHeight = height
            viewportChanged = true
        }
    }

    /**
     * Official DisplayRotationHelper pattern: apply pending display geometry on the render thread
     * immediately before calling Session.update(). Must be called under [arSessionLock].
     */
    fun updateDisplayGeometryIfNeeded(activeSession: Session) {
        if (!viewportChanged || viewportWidth <= 0 || viewportHeight <= 0) return
        try {
            activeSession.setDisplayGeometry(lastDisplayRotation, viewportWidth, viewportHeight)
            viewportChanged = false
        } catch (t: Throwable) {
            Log.w("ModernArEngine", "updateDisplayGeometryIfNeeded note: ${t.message}")
        }
    }

    fun destroy() {
        synchronized(arSessionLock) {
            isSessionResumed = false
            if (hasNativeSessionResumed) {
                hasNativeSessionResumed = false
                try {
                    session?.pause()
                } catch (_: Throwable) {}
            }
            isTorchActive = false
            try {
                session?.close()
            } catch (e: Exception) {
                Log.e("ModernArEngine", "Error closing ARCore session", e)
            }
            session = null
            selectedCameraId = null
            selectedCameraImageSize = null
            selectedCameraFpsRange = null
            isExposeHardwareBufferActive = false
            synchronized(glContextLock) {
                eglHelper?.release()
                eglHelper = null
            }
        }
    }

    private fun transformCameraRayToWorld(
        cameraPose: Pose,
        camX: Float,
        camY: Float,
        camZ: Float,
        rayCamX: Float,
        rayCamY: Float
    ): Boolean {
        scratchPointIn[0] = rayCamX
        scratchPointIn[1] = rayCamY
        scratchPointIn[2] = -1.0f
        cameraPose.transformPoint(scratchPointIn, 0, scratchPointOut, 0)

        val dx = (scratchPointOut[0] - camX).toDouble()
        val dy = (scratchPointOut[1] - camY).toDouble()
        val dz = (scratchPointOut[2] - camZ).toDouble()
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5) return false

        scratchRayOrigin[0] = camX.toDouble()
        scratchRayOrigin[1] = camY.toDouble()
        scratchRayOrigin[2] = camZ.toDouble()
        scratchRayDir[0] = dx / len
        scratchRayDir[1] = dy / len
        scratchRayDir[2] = dz / len
        return true
    }

    /**
     * Unprojects screen pixel (x, y) into a normalized world-space ray stored in
     * [scratchRayOrigin] and [scratchRayDir]. Falls back to [Camera.getImageIntrinsics]
     * if projection matrix values are degenerate. Returns true if valid.
     */
    private fun computeWorldRayForPixel(frame: Frame, camera: Camera, x: Float, y: Float): Boolean {
        if (viewportWidth <= 0 || viewportHeight <= 0) return false
        val cameraPose = camera.pose
        val camX = cameraPose.tx()
        val camY = cameraPose.ty()
        val camZ = cameraPose.tz()

        camera.getProjectionMatrix(reusablePMatrix, 0, 0.05f, 50.0f)
        val p00 = reusablePMatrix[0]
        val p11 = reusablePMatrix[5]
        if (kotlin.math.abs(p00) <= 1e-4f || kotlin.math.abs(p11) <= 1e-4f) {
            return try {
                val intrinsics = camera.imageIntrinsics
                intrinsics.getFocalLength(intrinsicsFocal, 0)
                intrinsics.getPrincipalPoint(intrinsicsPrincipal, 0)
                intrinsics.getImageDimensions(intrinsicsDims, 0)
                val fx = intrinsicsFocal[0]
                val fy = intrinsicsFocal[1]
                if (fx <= 1e-3f || fy <= 1e-3f) return false
                scratchViewXY[0] = x
                scratchViewXY[1] = y
                frame.transformCoordinates2d(
                    Coordinates2d.VIEW,
                    scratchViewXY,
                    Coordinates2d.IMAGE_PIXELS,
                    intrinsicsImgXY
                )
                val rayCamX = (intrinsicsImgXY[0] - intrinsicsPrincipal[0]) / fx
                val rayCamY = -(intrinsicsImgXY[1] - intrinsicsPrincipal[1]) / fy
                transformCameraRayToWorld(cameraPose, camX, camY, camZ, rayCamX, rayCamY)
            } catch (_: Throwable) {
                false
            }
        }

        val ndcX = (2.0f * x / viewportWidth.toFloat()) - 1.0f
        val ndcY = 1.0f - (2.0f * y / viewportHeight.toFloat())
        val rayCamX = (ndcX + reusablePMatrix[8]) / p00
        val rayCamY = (ndcY + reusablePMatrix[9]) / p11

        return transformCameraRayToWorld(cameraPose, camX, camY, camZ, rayCamX, rayCamY)
    }

    private fun pushTemporalDepthSlot(
        nowMs: Long,
        hx: Float,
        hy: Float,
        hz: Float,
        nx: Float,
        ny: Float,
        nz: Float,
        camX: Float,
        camY: Float,
        camZ: Float,
        targetX: Float,
        targetY: Float,
        confPct: Int,
        inlierRatio: Float
    ) {
        val idx = depthRingHead
        ringTimestampsMs[idx] = nowMs
        ringHitX[idx] = hx
        ringHitY[idx] = hy
        ringHitZ[idx] = hz
        ringNormX[idx] = nx
        ringNormY[idx] = ny
        ringNormZ[idx] = nz
        ringCamX[idx] = camX
        ringCamY[idx] = camY
        ringCamZ[idx] = camZ
        ringTargetX[idx] = targetX
        ringTargetY[idx] = targetY
        ringConfPct[idx] = confPct
        ringInlierRatio[idx] = inlierRatio
        depthRingHead = (depthRingHead + 1) % depthRingCapacity
        if (depthRingCount < depthRingCapacity) depthRingCount++
    }

    private fun tryBridgeFromTemporalDepthRing(frame: Frame, x: Float, y: Float, nowMs: Long): Boolean {
        if (depthRingCount <= 0) return false
        val latestIdx = (depthRingHead - 1 + depthRingCapacity) % depthRingCapacity
        val ageMs = nowMs - ringTimestampsMs[latestIdx]
        if (ageMs < 0L || ageMs > 350L) return false
        if (kotlin.math.abs(x - ringTargetX[latestIdx]) > 12f || kotlin.math.abs(y - ringTargetY[latestIdx]) > 12f) {
            return false
        }
        val camPose = frame.camera.pose
        val dx = camPose.tx() - ringCamX[latestIdx]
        val dy = camPose.ty() - ringCamY[latestIdx]
        val dz = camPose.tz() - ringCamZ[latestIdx]
        if (dx * dx + dy * dy + dz * dz > 0.03f * 0.03f) return false

        if (!computeWorldRayForPixel(frame, frame.camera, x, y)) return false
        if (!ArMath.rayPlaneIntersection(
                scratchRayOrigin[0], scratchRayOrigin[1], scratchRayOrigin[2],
                scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                ringHitX[latestIdx].toDouble(), ringHitY[latestIdx].toDouble(), ringHitZ[latestIdx].toDouble(),
                ringNormX[latestIdx].toDouble(), ringNormY[latestIdx].toDouble(), ringNormZ[latestIdx].toDouble(),
                minCosIncidence = 0.22,
                minT = 0.12,
                maxT = 6.5,
                outHitXYZT = roiHitOut4
            )
        ) {
            return false
        }

        cachedRawDepthNormal[0] = ringNormX[latestIdx]
        cachedRawDepthNormal[1] = ringNormY[latestIdx]
        cachedRawDepthNormal[2] = ringNormZ[latestIdx]
        cachedRawDepthHitPoseTrans[0] = roiHitOut4[0].toFloat()
        cachedRawDepthHitPoseTrans[1] = roiHitOut4[1].toFloat()
        cachedRawDepthHitPoseTrans[2] = roiHitOut4[2].toFloat()
        cachedRawDepthFitDistance = roiHitOut4[3].toFloat()
        cachedRawDepthInlierRatio = ringInlierRatio[latestIdx]
        if (cachedDepthConfidencePercent < 0) {
            cachedDepthConfidencePercent = ringConfPct[latestIdx]
        }
        cachedRawDepthRoiValid = true
        return true
    }

    /**
     * Zero-alloc sample of a 5x5 ARCore Raw Depth ROI + Confidence patch centered at viewport
     * pixel (x, y), batch-unprojected into 3D world space using real-time CameraIntrinsics /
     * projection matrix, fitted via confidence-weighted RANSAC + Total Least Squares plane
     * reconstruction, and smoothed across a 4-slot temporal ring buffer.
     * Deterministically closes all native Image handles.
     */
    private fun sampleRawDepthAndConfidence(frame: Frame, x: Float, y: Float, forceSample: Boolean) {
        if (!isDepthModeActive || !isRawDepthConfidenceFilterEnabled) {
            cachedDepthConfidencePercent = -1
            cachedCenterDepthMeters = -1f
            cachedRawDepthRoiValid = false
            cachedRawDepthInlierRatio = -1f
            return
        }
        val camPose = frame.camera.pose
        val nowMs = (frame.timestamp / 1_000_000L).let { if (it > 0L) it else System.currentTimeMillis() }

        // Flush temporal depth ring if camera translated > 6cm or screen target moved > 14px
        if (depthRingCount > 0) {
            val latestIdx = (depthRingHead - 1 + depthRingCapacity) % depthRingCapacity
            val cdx = camPose.tx() - ringCamX[latestIdx]
            val cdy = camPose.ty() - ringCamY[latestIdx]
            val cdz = camPose.tz() - ringCamZ[latestIdx]
            if (cdx * cdx + cdy * cdy + cdz * cdz > 0.06f * 0.06f ||
                kotlin.math.abs(x - ringTargetX[latestIdx]) > 14f ||
                kotlin.math.abs(y - ringTargetY[latestIdx]) > 14f
            ) {
                depthRingCount = 0
                depthRingHead = 0
            }
        }

        val sameTarget = kotlin.math.abs(x - lastRoiSampleX) < 8f && kotlin.math.abs(y - lastRoiSampleY) < 8f
        if (!forceSample && !lastRoiSampleWasTap && sameTarget && (frameCounter % 2 != 0)) {
            return
        }
        lastRoiSampleX = x
        lastRoiSampleY = y
        lastRoiSampleWasTap = forceSample
        cachedRawDepthRoiValid = false
        cachedRawDepthInlierRatio = -1f
        cachedRawDepthFitDistance = -1f

        try {
            scratchViewXY[0] = x
            scratchViewXY[1] = y
            frame.transformCoordinates2d(
                Coordinates2d.VIEW,
                scratchViewXY,
                Coordinates2d.IMAGE_NORMALIZED,
                scratchDepthNormXY
            )
            val normX = scratchDepthNormXY[0]
            val normY = scratchDepthNormXY[1]
            if (normX !in 0f..1f || normY !in 0f..1f) return

            var roiValidCount = 0
            var roiConfSum = 0
            var roiConfSamples = 0

            frame.acquireRawDepthConfidenceImage().use { confImg ->
                val confPlane = confImg.planes.firstOrNull() ?: return@use
                val cW = confImg.width
                val cH = confImg.height
                if (cW <= 0 || cH <= 0) return@use
                val confBuf = confPlane.buffer

                val cpx = (normX * cW).toInt().coerceIn(0, cW - 1)
                val cpy = (normY * cH).toInt().coerceIn(0, cH - 1)
                val cOff = cpy * confPlane.rowStride + cpx * confPlane.pixelStride
                if (cOff in 0 until confBuf.limit()) {
                    val cByte = confBuf.get(cOff).toInt() and 0xFF
                    cachedDepthConfidencePercent = ((cByte * 100) / 255).coerceIn(0, 100)
                }

                frame.acquireRawDepthImage16Bits().use { depthImg ->
                    val depthPlane = depthImg.planes.firstOrNull() ?: return@use
                    val dW = depthImg.width
                    val dH = depthImg.height
                    if (dW <= 0 || dH <= 0) return@use
                    val depthBuf = depthPlane.buffer

                    val dcx = (normX * dW).toInt().coerceIn(0, dW - 1)
                    val dcy = (normY * dH).toInt().coerceIn(0, dH - 1)
                    val dCenterOff = dcy * depthPlane.rowStride + dcx * depthPlane.pixelStride
                    if (dCenterOff >= 0 && dCenterOff + 1 < depthBuf.limit()) {
                        val lo = depthBuf.get(dCenterOff).toInt() and 0xFF
                        val hi = depthBuf.get(dCenterOff + 1).toInt() and 0xFF
                        val depthMm = (hi shl 8) or lo
                        cachedCenterDepthMeters = if (depthMm in 100..20000) depthMm / 1000f else -1f
                    }

                    // Sample a 5x5 patch (2px depth stride) around (dcx, dcy)
                    var idx = 0
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val px = dcx + dx * 2
                            val py = dcy + dy * 2
                            roiValidFlags[idx] = false
                            roiNormCoords[idx * 2] = normX
                            roiNormCoords[idx * 2 + 1] = normY

                            if (px in 0 until dW && py in 0 until dH) {
                                val u = (px + 0.5f) / dW.toFloat()
                                val v = (py + 0.5f) / dH.toFloat()
                                val qcx = (u * cW).toInt().coerceIn(0, cW - 1)
                                val qcy = (v * cH).toInt().coerceIn(0, cH - 1)
                                val qOff = qcy * confPlane.rowStride + qcx * confPlane.pixelStride
                                val dOff = py * depthPlane.rowStride + px * depthPlane.pixelStride

                                if (qOff in 0 until confBuf.limit() && dOff >= 0 && dOff + 1 < depthBuf.limit()) {
                                    val confByte = confBuf.get(qOff).toInt() and 0xFF
                                    val confPct = (confByte * 100) / 255
                                    roiConfSum += confPct
                                    roiConfSamples++

                                    if (confPct >= rawDepthConfidenceThreshold) {
                                        val lo = depthBuf.get(dOff).toInt() and 0xFF
                                        val hi = depthBuf.get(dOff + 1).toInt() and 0xFF
                                        val depthMm = (hi shl 8) or lo
                                        if (depthMm in 120..6500) {
                                            val confNorm = confByte / 255.0
                                            roiValidFlags[idx] = true
                                            roiWorldZ[idx] = depthMm / 1000.0
                                            roiWeights[idx] = confNorm * confNorm
                                            roiNormCoords[idx * 2] = u
                                            roiNormCoords[idx * 2 + 1] = v
                                            roiValidCount++
                                        }
                                    }
                                }
                            }
                            idx++
                        }
                    }
                }
            }

            if (roiConfSamples > 0 && cachedDepthConfidencePercent < 0) {
                cachedDepthConfidencePercent = (roiConfSum / roiConfSamples).coerceIn(0, 100)
            }

            var fitSucceeded = false
            if (roiValidCount >= 5 && viewportWidth > 0 && viewportHeight > 0 && computeWorldRayForPixel(frame, frame.camera, x, y)) {
                frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_NORMALIZED,
                    roiNormCoords,
                    Coordinates2d.VIEW,
                    roiViewCoords
                )
                val p00 = reusablePMatrix[0]
                val p11 = reusablePMatrix[5]
                if (kotlin.math.abs(p00) > 1e-4f && kotlin.math.abs(p11) > 1e-4f) {
                    var packed = 0
                    for (idx in 0 until 25) {
                        if (!roiValidFlags[idx]) continue
                        val vx = roiViewCoords[idx * 2]
                        val vy = roiViewCoords[idx * 2 + 1]
                        if (!vx.isFinite() || !vy.isFinite()) continue
                        val depthM = roiWorldZ[idx]
                        val ndcX = (2.0f * vx / viewportWidth.toFloat()) - 1.0f
                        val ndcY = 1.0f - (2.0f * vy / viewportHeight.toFloat())
                        val rayCamX = (ndcX + reusablePMatrix[8]) / p00
                        val rayCamY = (ndcY + reusablePMatrix[9]) / p11
                        scratchPointIn[0] = (rayCamX * depthM).toFloat()
                        scratchPointIn[1] = (rayCamY * depthM).toFloat()
                        scratchPointIn[2] = (-depthM).toFloat()
                        camPose.transformPoint(scratchPointIn, 0, scratchPointOut, 0)
                        roiWorldX[packed] = scratchPointOut[0].toDouble()
                        roiWorldY[packed] = scratchPointOut[1].toDouble()
                        roiWorldZ[packed] = scratchPointOut[2].toDouble()
                        roiWeights[packed] = roiWeights[idx]
                        packed++
                    }

                    val refDepth = if (cachedCenterDepthMeters > 0.2f) cachedCenterDepthMeters else 1.0f
                    val inlierThresh = (0.015 + refDepth * 0.012).coerceIn(0.015, 0.045)
                    if (packed >= 5 && ArMath.fitConfidenceWeightedLocalPlane(
                            xs = roiWorldX,
                            ys = roiWorldY,
                            zs = roiWorldZ,
                            weights = roiWeights,
                            count = packed,
                            rayOriginX = scratchRayOrigin[0],
                            rayOriginY = scratchRayOrigin[1],
                            rayOriginZ = scratchRayOrigin[2],
                            rayDirX = scratchRayDir[0],
                            rayDirY = scratchRayDir[1],
                            rayDirZ = scratchRayDir[2],
                            inlierDistThreshold = inlierThresh,
                            minCosIncidence = 0.22,
                            minT = 0.12,
                            maxT = 6.5,
                            outPlane7 = roiPlaneOut7,
                            outHitXYZT = roiHitOut4
                        )
                    ) {
                        val curNx = roiPlaneOut7[0].toFloat()
                        val curNy = roiPlaneOut7[1].toFloat()
                        val curNz = roiPlaneOut7[2].toFloat()
                        val curHx = roiHitOut4[0].toFloat()
                        val curHy = roiHitOut4[1].toFloat()
                        val curHz = roiHitOut4[2].toFloat()
                        val curInlier = roiPlaneOut7[6].toFloat()

                        pushTemporalDepthSlot(
                            nowMs = nowMs,
                            hx = curHx, hy = curHy, hz = curHz,
                            nx = curNx, ny = curNy, nz = curNz,
                            camX = camPose.tx(), camY = camPose.ty(), camZ = camPose.tz(),
                            targetX = x, targetY = y,
                            confPct = cachedDepthConfidencePercent,
                            inlierRatio = curInlier
                        )

                        // Temporally smooth consistent recent ROI planes (age <= 450ms, dot > 0.92)
                        var sumNx = curNx.toDouble()
                        var sumNy = curNy.toDouble()
                        var sumNz = curNz.toDouble()
                        var sumHx = curHx.toDouble()
                        var sumHy = curHy.toDouble()
                        var sumHz = curHz.toDouble()
                        var wSum = 1.0
                        for (s in 0 until depthRingCount) {
                            val age = nowMs - ringTimestampsMs[s]
                            if (age in 1L..450L) {
                                val dot = ringNormX[s] * curNx + ringNormY[s] * curNy + ringNormZ[s] * curNz
                                if (dot > 0.92f) {
                                    val w = (1.0 - age / 600.0).coerceAtLeast(0.25)
                                    sumNx += ringNormX[s] * w
                                    sumNy += ringNormY[s] * w
                                    sumNz += ringNormZ[s] * w
                                    sumHx += ringHitX[s] * w
                                    sumHy += ringHitY[s] * w
                                    sumHz += ringHitZ[s] * w
                                    wSum += w
                                }
                            }
                        }

                        val avgNLen = kotlin.math.sqrt(sumNx * sumNx + sumNy * sumNy + sumNz * sumNz)
                        var usedSmoothed = false
                        if (wSum > 1.05 && avgNLen > 1e-6) {
                            val uNx = sumNx / avgNLen
                            val uNy = sumNy / avgNLen
                            val uNz = sumNz / avgNLen
                            val cHx = sumHx / wSum
                            val cHy = sumHy / wSum
                            val cHz = sumHz / wSum
                            if (ArMath.rayPlaneIntersection(
                                    scratchRayOrigin[0], scratchRayOrigin[1], scratchRayOrigin[2],
                                    scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                                    cHx, cHy, cHz,
                                    uNx, uNy, uNz,
                                    minCosIncidence = 0.22,
                                    minT = 0.12,
                                    maxT = 6.5,
                                    outHitXYZT = roiHitOut4
                                )
                            ) {
                                cachedRawDepthNormal[0] = uNx.toFloat()
                                cachedRawDepthNormal[1] = uNy.toFloat()
                                cachedRawDepthNormal[2] = uNz.toFloat()
                                cachedRawDepthHitPoseTrans[0] = roiHitOut4[0].toFloat()
                                cachedRawDepthHitPoseTrans[1] = roiHitOut4[1].toFloat()
                                cachedRawDepthHitPoseTrans[2] = roiHitOut4[2].toFloat()
                                cachedRawDepthFitDistance = roiHitOut4[3].toFloat()
                                usedSmoothed = true
                            }
                        }
                        if (!usedSmoothed) {
                            cachedRawDepthNormal[0] = curNx
                            cachedRawDepthNormal[1] = curNy
                            cachedRawDepthNormal[2] = curNz
                            cachedRawDepthHitPoseTrans[0] = curHx
                            cachedRawDepthHitPoseTrans[1] = curHy
                            cachedRawDepthHitPoseTrans[2] = curHz
                            cachedRawDepthFitDistance = roiHitOut4[3].toFloat()
                        }
                        cachedRawDepthInlierRatio = curInlier
                        cachedRawDepthRoiValid = true
                        fitSucceeded = true
                    }
                }
            }
            if (!fitSucceeded) {
                tryBridgeFromTemporalDepthRing(frame, x, y, nowMs)
            }
        } catch (_: NotYetAvailableException) {
            tryBridgeFromTemporalDepthRing(frame, x, y, nowMs)
        } catch (_: Throwable) {
            // Ignore unsupported raw depth devices safely
        }
    }

    /**
     * Builds a Pose at ([px], [py], [pz]) whose local +Y axis aligns with unit normal ([nx], [ny], [nz]).
     */
    private fun buildPoseFromPointAndNormal(
        px: Float,
        py: Float,
        pz: Float,
        nx: Float,
        ny: Float,
        nz: Float
    ): Pose {
        scratchPoseTrans[0] = px
        scratchPoseTrans[1] = py
        scratchPoseTrans[2] = pz
        if (ny > 0.9999f) {
            scratchQuatRot[0] = 0f; scratchQuatRot[1] = 0f; scratchQuatRot[2] = 0f; scratchQuatRot[3] = 1f
        } else if (ny < -0.9999f) {
            scratchQuatRot[0] = 1f; scratchQuatRot[1] = 0f; scratchQuatRot[2] = 0f; scratchQuatRot[3] = 0f
        } else {
            val qx = nz
            val qy = 0f
            val qz = -nx
            val qw = 1f + ny
            val qLen = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            if (qLen > 1e-6f) {
                scratchQuatRot[0] = qx / qLen
                scratchQuatRot[1] = qy / qLen
                scratchQuatRot[2] = qz / qLen
                scratchQuatRot[3] = qw / qLen
            } else {
                scratchQuatRot[0] = 0f; scratchQuatRot[1] = 0f; scratchQuatRot[2] = 0f; scratchQuatRot[3] = 1f
            }
        }
        return Pose(scratchPoseTrans, scratchQuatRot)
    }

    /**
     * Creates a persistent ARCore world anchor at an arbitrary snapped coordinate so
     * snapped segment midpoints/edges stay tracked alongside SLAM relocalization.
     */
    fun createAnchorAtWorldPoint(point: Point3D): Anchor? {
        val currentSession = session ?: return null
        if (!isSessionResumed) return null
        return try {
            scratchPoseTrans[0] = point.x.toFloat()
            scratchPoseTrans[1] = point.y.toFloat()
            scratchPoseTrans[2] = point.z.toFloat()
            currentSession.createAnchor(Pose(scratchPoseTrans, floatArrayOf(0f, 0f, 0f, 1f)))
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveOpticalSobelEdgeSnap(
        frame: Frame,
        viewX: Float,
        viewY: Float,
        forceSample: Boolean
    ): Boolean {
        val targetMoved = kotlin.math.abs(viewX - lastEdgeQueryViewX) >= 6f ||
            kotlin.math.abs(viewY - lastEdgeQueryViewY) >= 6f
        if (!forceSample && !targetMoved && (frameCounter - lastEdgeSnapFrame) < 2) {
            return lastEdgeSnapValid
        }
        lastEdgeQueryViewX = viewX
        lastEdgeQueryViewY = viewY
        lastEdgeSnapFrame = frameCounter

        var snapped = false
        try {
            scratchViewXY[0] = viewX
            scratchViewXY[1] = viewY
            frame.transformCoordinates2d(
                Coordinates2d.VIEW,
                scratchViewXY,
                Coordinates2d.IMAGE_PIXELS,
                edgeQueryImageXY
            )
            val ix = edgeQueryImageXY[0]
            val iy = edgeQueryImageXY[1]
            if (ix.isFinite() && iy.isFinite()) {
                frame.acquireCameraImage()?.use { camImg ->
                    val yPlane = camImg.planes.firstOrNull() ?: return@use
                    val gradMag = ArMath.findStrongestLocalEdgeXY(
                        luma = yPlane.buffer,
                        width = camImg.width,
                        height = camImg.height,
                        rowStride = yPlane.rowStride,
                        pixelStride = yPlane.pixelStride,
                        centerX = ix,
                        centerY = iy,
                        searchRadiusPx = 14,
                        minGradientThreshold = 42f,
                        outBestXY = edgeBestImageXY
                    )
                    if (gradMag > 0f) {
                        frame.transformCoordinates2d(
                            Coordinates2d.IMAGE_PIXELS,
                            edgeBestImageXY,
                            Coordinates2d.VIEW,
                            edgeBestViewXY
                        )
                        val svx = edgeBestViewXY[0]
                        val svy = edgeBestViewXY[1]
                        if (svx.isFinite() && svy.isFinite()) {
                            val dx = svx - viewX
                            val dy = svy - viewY
                            if (dx * dx + dy * dy <= 16f * 16f) {
                                lastEdgeSnapViewX = svx.coerceIn(0f, viewportWidth.toFloat().coerceAtLeast(1f))
                                lastEdgeSnapViewY = svy.coerceIn(0f, viewportHeight.toFloat().coerceAtLeast(1f))
                                snapped = true
                            }
                        }
                    }
                }
            }
        } catch (_: NotYetAvailableException) {
            // Expected briefly before CPU image stream is ready
        } catch (_: Throwable) {
            // Safe fallback if camera CPU image cannot be acquired on this frame
        }
        lastEdgeSnapValid = snapped
        return snapped
    }

    fun performHitTest(frame: Frame, x: Float, y: Float, createAnchor: Boolean = false): HitTestResult? {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return null
        }
        if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite() || x < 0f || y < 0f) {
            return null
        }
        if (viewportWidth > 0 && viewportHeight > 0 && (x > viewportWidth * 1.5f || y > viewportHeight * 1.5f)) {
            return null
        }
        try {
            val isImageEdgeSnapped = resolveOpticalSobelEdgeSnap(frame, x, y, forceSample = createAnchor)
            val effX = if (isImageEdgeSnapped) lastEdgeSnapViewX else x
            val effY = if (isImageEdgeSnapped) lastEdgeSnapViewY else y

            sampleRawDepthAndConfidence(frame, effX, effY, forceSample = createAnchor)
            val depthConf = cachedDepthConfidencePercent
            val depthConfPasses = !isRawDepthConfidenceFilterEnabled || depthConf < 0 || depthConf >= rawDepthConfidenceThreshold

            val hits = frame.hitTest(effX, effY)

            // Single-pass zero-allocation tier resolution over hit list (hits are sorted closest-first)
            var planePolygonHit: HitResult? = null
            var planeEstimatedHit: HitResult? = null
            var depthHit: HitResult? = null
            var instantHit: HitResult? = null
            var featurePointHit: HitResult? = null
            var hasValidHit = false

            for (i in hits.indices) {
                val hit = hits[i]
                val dist = hit.distance
                if (dist < 0.05f || dist > 25.0f) continue
                val trackable = hit.trackable
                when {
                    trackable is Plane &&
                        trackable.trackingState == TrackingState.TRACKING &&
                        trackable.subsumedBy == null -> {
                        hasValidHit = true
                        if (planePolygonHit == null && trackable.isPoseInPolygon(hit.hitPose)) {
                            planePolygonHit = hit
                            break
                        } else if (planeEstimatedHit == null) {
                            planeEstimatedHit = hit
                        }
                    }
                    trackable is DepthPoint && trackable.trackingState == TrackingState.TRACKING -> {
                        hasValidHit = true
                        if (depthHit == null) depthHit = hit
                    }
                    trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING -> {
                        hasValidHit = true
                        if (depthHit == null && trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                            depthHit = hit
                        } else if (featurePointHit == null) {
                            featurePointHit = hit
                        }
                    }
                    trackable is InstantPlacementPoint &&
                        trackable.trackingState == TrackingState.TRACKING &&
                        trackable.trackingMethod != InstantPlacementPoint.TrackingMethod.NOT_TRACKING -> {
                        hasValidHit = true
                        if (instantHit == null) instantHit = hit
                    }
                }
            }

            // Sub-pixel 4-neighbor cross check with exact center-ray reprojection onto the hit plane
            // so fallback hits never carry a 4px lateral aiming bias.
            var reprojectedCenterPose: Pose? = null
            var reprojectedCenterDistance = 0f
            if (planePolygonHit == null && depthHit == null && (createAnchor || aimMissCounter++ % 3 == 0)) {
                for (n in 0 until 4) {
                    val nHits = frame.hitTest(effX + neighborDx[n], effY + neighborDy[n])
                    for (j in nHits.indices) {
                        val hit = nHits[j]
                        if (hit.distance in 0.05f..25.0f) {
                            val trackable = hit.trackable
                            if (trackable is Plane &&
                                trackable.trackingState == TrackingState.TRACKING &&
                                trackable.subsumedBy == null &&
                                trackable.isPoseInPolygon(hit.hitPose)
                            ) {
                                planePolygonHit = hit
                                hasValidHit = true
                                if (computeWorldRayForPixel(frame, frame.camera, effX, effY)) {
                                    val cPose = trackable.centerPose
                                    cPose.transformPoint(scratchNormalIn, 0, scratchNormalOut, 0)
                                    val nx = (scratchNormalOut[0] - cPose.tx()).toDouble()
                                    val ny = (scratchNormalOut[1] - cPose.ty()).toDouble()
                                    val nz = (scratchNormalOut[2] - cPose.tz()).toDouble()
                                    if (ArMath.rayPlaneIntersection(
                                            scratchRayOrigin[0], scratchRayOrigin[1], scratchRayOrigin[2],
                                            scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                                            cPose.tx().toDouble(), cPose.ty().toDouble(), cPose.tz().toDouble(),
                                            nx, ny, nz,
                                            minCosIncidence = 0.15,
                                            minT = 0.05,
                                            maxT = 25.0,
                                            outHitXYZT = scratchRayHitOut
                                        )
                                    ) {
                                        val nhp = hit.hitPose
                                        val dx = scratchRayHitOut[0] - nhp.tx()
                                        val dy = scratchRayHitOut[1] - nhp.ty()
                                        val dz = scratchRayHitOut[2] - nhp.tz()
                                        if (dx * dx + dy * dy + dz * dz <= 0.15 * 0.15) {
                                            scratchPoseTrans[0] = scratchRayHitOut[0].toFloat()
                                            scratchPoseTrans[1] = scratchRayHitOut[1].toFloat()
                                            scratchPoseTrans[2] = scratchRayHitOut[2].toFloat()
                                            reprojectedCenterPose = Pose(scratchPoseTrans, cPose.rotationQuaternion)
                                            reprojectedCenterDistance = scratchRayHitOut[3].toFloat()
                                        }
                                    }
                                }
                                break
                            }
                        }
                    }
                    if (planePolygonHit != null) break
                }
            }

            val camPose = frame.camera.pose
            val hasCenterRay = computeWorldRayForPixel(frame, frame.camera, effX, effY)
            val roiInlierRatio = cachedRawDepthInlierRatio

            // Fitted Raw Depth ROI local plane beats background PlanePolygon ONLY when significantly closer (foreground object),
            // and beats unsmoothed depthHit / outside-polygon planeEstimatedHit.
            val roiDepthBeatsPolygonPlane = cachedRawDepthRoiValid && depthConfPasses &&
                (planePolygonHit == null || cachedRawDepthFitDistance < planePolygonHit.distance - 0.06f)

            if (roiDepthBeatsPolygonPlane) {
                val roiPose = buildPoseFromPointAndNormal(
                    cachedRawDepthHitPoseTrans[0],
                    cachedRawDepthHitPoseTrans[1],
                    cachedRawDepthHitPoseTrans[2],
                    cachedRawDepthNormal[0],
                    cachedRawDepthNormal[1],
                    cachedRawDepthNormal[2]
                )
                val cosIncidence = if (hasCenterRay) {
                    ArMath.computeIncidenceCosine(
                        scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                        cachedRawDepthNormal[0].toDouble(), cachedRawDepthNormal[1].toDouble(), cachedRawDepthNormal[2].toDouble()
                    )
                } else 1.0f
                val anchor = if (createAnchor) {
                    try { session?.createAnchor(roiPose) } catch (_: Throwable) { null }
                } else null
                return HitTestResult(
                    pose = roiPose,
                    anchor = anchor,
                    hitType = HitType.DEPTH_POINT,
                    distance = cachedRawDepthFitDistance,
                    planeType = null,
                    normalX = cachedRawDepthNormal[0],
                    normalY = cachedRawDepthNormal[1],
                    normalZ = cachedRawDepthNormal[2],
                    hasSurfaceNormal = true,
                    depthConfidencePercent = depthConf,
                    incidenceCosine = cosIncidence,
                    rawDepthInlierRatio = roiInlierRatio,
                    isImageEdgeSnapped = isImageEdgeSnapped
                )
            }

            // Foreground ARCore DepthPoint beats background Plane ONLY when Raw Depth confidence passes filter
            val depthBeatsPolygonPlane = depthHit != null && depthConfPasses &&
                (planePolygonHit == null || depthHit.distance < planePolygonHit.distance - 0.08f)

            if (planePolygonHit != null && !depthBeatsPolygonPlane) {
                var plane = planePolygonHit.trackable as Plane
                while (plane.subsumedBy != null) {
                    plane = plane.subsumedBy!!
                }
                val finalPose = reprojectedCenterPose ?: planePolygonHit.hitPose
                val finalDistance = if (reprojectedCenterPose != null) reprojectedCenterDistance else planePolygonHit.distance
                extractOrientedNormal(plane.centerPose, finalPose, camPose)
                val cosIncidence = if (hasCenterRay) {
                    ArMath.computeIncidenceCosine(
                        scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                        scratchNormalOut[0].toDouble(), scratchNormalOut[1].toDouble(), scratchNormalOut[2].toDouble()
                    )
                } else 1.0f

                val anchor = if (createAnchor) {
                    try {
                        plane.createAnchor(finalPose)
                    } catch (e: Exception) {
                        try { planePolygonHit.createAnchor() } catch (e2: Exception) { null }
                    }
                } else null
                return HitTestResult(
                    pose = finalPose,
                    anchor = anchor,
                    hitType = HitType.PLANE_POLYGON,
                    distance = finalDistance,
                    planeType = plane.type,
                    planeCenterPose = plane.centerPose,
                    isSnappedToFeature = false,
                    normalX = scratchNormalOut[0],
                    normalY = scratchNormalOut[1],
                    normalZ = scratchNormalOut[2],
                    hasSurfaceNormal = true,
                    depthConfidencePercent = depthConf,
                    incidenceCosine = cosIncidence,
                    rawDepthInlierRatio = roiInlierRatio,
                    isImageEdgeSnapped = isImageEdgeSnapped
                )
            }

            if (hasValidHit) {
                // High-confidence Depth Point beats extrapolated outside-polygon Plane hits
                val depthBeatsEstimatedPlane = depthHit != null && depthConfPasses &&
                    (planeEstimatedHit == null || depthHit.distance <= planeEstimatedHit.distance + 0.04f)

                if (depthHit != null && (depthBeatsPolygonPlane || depthBeatsEstimatedPlane)) {
                    extractOrientedNormal(depthHit.hitPose, depthHit.hitPose, camPose)
                    val cosIncidence = if (hasCenterRay) {
                        ArMath.computeIncidenceCosine(
                            scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                            scratchNormalOut[0].toDouble(), scratchNormalOut[1].toDouble(), scratchNormalOut[2].toDouble()
                        )
                    } else 1.0f
                    val anchor = if (createAnchor) {
                        try { depthHit.createAnchor() } catch (e: Exception) { null }
                    } else null
                    return HitTestResult(
                        pose = depthHit.hitPose,
                        anchor = anchor,
                        hitType = HitType.DEPTH_POINT,
                        distance = depthHit.distance,
                        planeType = null,
                        normalX = scratchNormalOut[0],
                        normalY = scratchNormalOut[1],
                        normalZ = scratchNormalOut[2],
                        hasSurfaceNormal = true,
                        depthConfidencePercent = depthConf,
                        incidenceCosine = cosIncidence,
                        rawDepthInlierRatio = roiInlierRatio,
                        isImageEdgeSnapped = isImageEdgeSnapped
                    )
                }

                // Plane estimated (outside current polygon)
                if (planeEstimatedHit != null) {
                    var plane = planeEstimatedHit.trackable as Plane
                    while (plane.subsumedBy != null) {
                        plane = plane.subsumedBy!!
                    }
                    val finalPose = planeEstimatedHit.hitPose
                    extractOrientedNormal(plane.centerPose, finalPose, camPose)
                    val cosIncidence = if (hasCenterRay) {
                        ArMath.computeIncidenceCosine(
                            scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                            scratchNormalOut[0].toDouble(), scratchNormalOut[1].toDouble(), scratchNormalOut[2].toDouble()
                        )
                    } else 1.0f

                    val anchor = if (createAnchor) {
                        try {
                            plane.createAnchor(finalPose)
                        } catch (e: Exception) {
                            try { planeEstimatedHit.createAnchor() } catch (e2: Exception) { null }
                        }
                    } else null
                    return HitTestResult(
                        pose = finalPose,
                        anchor = anchor,
                        hitType = HitType.PLANE_ESTIMATED,
                        distance = planeEstimatedHit.distance,
                        planeType = plane.type,
                        planeCenterPose = plane.centerPose,
                        isSnappedToFeature = false,
                        normalX = scratchNormalOut[0],
                        normalY = scratchNormalOut[1],
                        normalZ = scratchNormalOut[2],
                        hasSurfaceNormal = true,
                        depthConfidencePercent = depthConf,
                        incidenceCosine = cosIncidence,
                        rawDepthInlierRatio = roiInlierRatio,
                        isImageEdgeSnapped = isImageEdgeSnapped
                    )
                }

                // Depth Point fallback (only if confidence meets threshold or no other metric hit exists)
                if (depthHit != null && (depthConfPasses || (instantHit == null && featurePointHit == null))) {
                    extractOrientedNormal(depthHit.hitPose, depthHit.hitPose, camPose)
                    val cosIncidence = if (hasCenterRay) {
                        ArMath.computeIncidenceCosine(
                            scratchRayDir[0], scratchRayDir[1], scratchRayDir[2],
                            scratchNormalOut[0].toDouble(), scratchNormalOut[1].toDouble(), scratchNormalOut[2].toDouble()
                        )
                    } else 1.0f
                    val anchor = if (createAnchor) {
                        try { depthHit.createAnchor() } catch (e: Exception) { null }
                    } else null
                    return HitTestResult(
                        pose = depthHit.hitPose,
                        anchor = anchor,
                        hitType = HitType.DEPTH_POINT,
                        distance = depthHit.distance,
                        planeType = null,
                        normalX = scratchNormalOut[0],
                        normalY = scratchNormalOut[1],
                        normalZ = scratchNormalOut[2],
                        hasSurfaceNormal = true,
                        depthConfidencePercent = depthConf,
                        incidenceCosine = cosIncidence,
                        rawDepthInlierRatio = roiInlierRatio,
                        isImageEdgeSnapped = isImageEdgeSnapped
                    )
                }

                // Tier 4: Instant Placement Point
                if (instantHit != null) {
                    val ipPoint = instantHit.trackable as? InstantPlacementPoint
                    val isApprox = ipPoint?.trackingMethod == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
                    val anchor = if (createAnchor && !isApprox) {
                        try { instantHit.createAnchor() } catch (e: Exception) { null }
                    } else null
                    return HitTestResult(
                        pose = instantHit.hitPose,
                        anchor = anchor,
                        hitType = HitType.INSTANT_PLACEMENT,
                        distance = instantHit.distance,
                        planeType = null,
                        isApproximatePlacement = isApprox,
                        normalX = 0f,
                        normalY = 1f,
                        normalZ = 0f,
                        hasSurfaceNormal = false,
                        depthConfidencePercent = depthConf,
                        rawDepthInlierRatio = roiInlierRatio,
                        isImageEdgeSnapped = isImageEdgeSnapped
                    )
                }

                // Tier 5: PointCloud feature point
                if (featurePointHit != null) {
                    val anchor = if (createAnchor) {
                        try { featurePointHit.createAnchor() } catch (e: Exception) { null }
                    } else null
                    return HitTestResult(
                        pose = featurePointHit.hitPose,
                        anchor = anchor,
                        hitType = HitType.FEATURE_POINT,
                        distance = featurePointHit.distance,
                        planeType = null,
                        isSnappedToFeature = true,
                        normalX = 0f,
                        normalY = 1f,
                        normalZ = 0f,
                        hasSurfaceNormal = false,
                        depthConfidencePercent = depthConf,
                        rawDepthInlierRatio = roiInlierRatio,
                        isImageEdgeSnapped = isImageEdgeSnapped
                    )
                }
            }

            // Tier 6: Bounded Mathematical Ray-Plane Surface Extrapolation (within local plane extent + margin)
            val raycastPlaneHit = performPlaneRaycastIntersection(frame, effX, effY, createAnchor, isImageEdgeSnapped)
            if (raycastPlaneHit != null) {
                return raycastPlaneHit
            }

            // Tier 7: ARCore Instant Placement API (only when camera tracking is active)
            val currentSession = session
            if (currentSession != null && currentSession.config.instantPlacementMode != Config.InstantPlacementMode.DISABLED) {
                val instantHits = try {
                    frame.hitTestInstantPlacement(effX, effY, 1.5f)
                } catch (_: Throwable) {
                    emptyList()
                }
                val firstInstant = instantHits.firstOrNull { hit ->
                    val ip = hit.trackable as? InstantPlacementPoint
                    hit.distance in 0.10f..15.0f &&
                        ip != null &&
                        ip.trackingState == TrackingState.TRACKING &&
                        ip.trackingMethod != InstantPlacementPoint.TrackingMethod.NOT_TRACKING
                }
                if (firstInstant != null) {
                    val ipPoint = firstInstant.trackable as? InstantPlacementPoint
                    val isApprox = ipPoint?.trackingMethod == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
                    val anchor = if (createAnchor && !isApprox) {
                        try { firstInstant.createAnchor() } catch (_: Exception) { null }
                    } else null
                    return HitTestResult(
                        pose = firstInstant.hitPose,
                        anchor = anchor,
                        hitType = HitType.INSTANT_PLACEMENT,
                        distance = firstInstant.distance,
                        planeType = null,
                        isApproximatePlacement = isApprox,
                        depthConfidencePercent = depthConf,
                        isImageEdgeSnapped = isImageEdgeSnapped
                    )
                }
            }

        } catch (e: Exception) {
            Log.e("ModernArEngine", "Hit test exception: ${e.message}")
        }
        return null
    }

    /**
     * Tier 6: Bounded Mathematical Ray-Plane Surface Extrapolation.
     * Restricts intersection to within 0.35m of the detected plane bounding extents, 8m range,
     * and >= 10.4° grazing angle (cos >= 0.18) to avoid shallow-angle distance blowup.
     */
    private fun performPlaneRaycastIntersection(
        frame: Frame,
        x: Float,
        y: Float,
        createAnchor: Boolean,
        isImageEdgeSnapped: Boolean = false
    ): HitTestResult? {
        val currentSession = session ?: return null
        val planes = currentSession.getAllTrackables(Plane::class.java)
        if (planes.isEmpty()) return null

        val camera = frame.camera
        val cameraPose = camera.pose
        val camX = cameraPose.tx()
        val camY = cameraPose.ty()
        val camZ = cameraPose.tz()

        if (!computeWorldRayForPixel(frame, camera, x, y)) {
            return null
        }
        val rNormX = scratchRayDir[0].toFloat()
        val rNormY = scratchRayDir[1].toFloat()
        val rNormZ = scratchRayDir[2].toFloat()

        var bestT = Float.MAX_VALUE
        var bestPlane: Plane? = null
        var bestHitX = 0f
        var bestHitY = 0f
        var bestHitZ = 0f
        val maxExtrapolationMargin = 0.35f

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val cPose = plane.centerPose
            val px = cPose.tx()
            val py = cPose.ty()
            val pz = cPose.tz()

            // Plane normal in world space (y-axis of center pose)
            cPose.transformPoint(scratchNormalIn, 0, scratchNormalOut, 0)
            val nx = scratchNormalOut[0] - px
            val ny = scratchNormalOut[1] - py
            val nz = scratchNormalOut[2] - pz

            val denom = rNormX * nx + rNormY * ny + rNormZ * nz
            if (kotlin.math.abs(denom) > 0.25f) {
                val t = ((px - camX) * nx + (py - camY) * ny + (pz - camZ) * nz) / denom
                if (t in 0.15f..5.5f && t < bestT) {
                    val hx = camX + t * rNormX
                    val hy = camY + t * rNormY
                    val hz = camZ + t * rNormZ

                    // Check bounds in plane-local XZ coordinates with range-decayed extrapolation margin
                    val decayMargin = kotlin.math.min(maxExtrapolationMargin, (0.35f / t.coerceAtLeast(1.0f)))
                    scratchPointIn[0] = hx
                    scratchPointIn[1] = hy
                    scratchPointIn[2] = hz
                    cPose.inverse().transformPoint(scratchPointIn, 0, scratchPointOut, 0)
                    val maxHalfX = (plane.extentX * 0.5f) + decayMargin
                    val maxHalfZ = (plane.extentZ * 0.5f) + decayMargin
                    if (kotlin.math.abs(scratchPointOut[0]) <= maxHalfX &&
                        kotlin.math.abs(scratchPointOut[2]) <= maxHalfZ
                    ) {
                        bestT = t
                        bestPlane = plane
                        bestHitX = hx
                        bestHitY = hy
                        bestHitZ = hz
                    }
                }
            }
        }

        if (bestPlane != null && bestT <= 5.5f) {
            scratchPoseTrans[0] = bestHitX
            scratchPoseTrans[1] = bestHitY
            scratchPoseTrans[2] = bestHitZ
            val finalPose = Pose(scratchPoseTrans, bestPlane.centerPose.rotationQuaternion)

            extractOrientedNormal(bestPlane.centerPose, finalPose, cameraPose)
            val cosIncidence = ArMath.computeIncidenceCosine(
                rNormX.toDouble(), rNormY.toDouble(), rNormZ.toDouble(),
                scratchNormalOut[0].toDouble(), scratchNormalOut[1].toDouble(), scratchNormalOut[2].toDouble()
            )

            val anchor = if (createAnchor) {
                try {
                    bestPlane.createAnchor(finalPose)
                } catch (e: Exception) { null }
            } else null

            return HitTestResult(
                pose = finalPose,
                anchor = anchor,
                hitType = HitType.PLANE_ESTIMATED,
                distance = bestT,
                planeType = bestPlane.type,
                planeCenterPose = bestPlane.centerPose,
                isSnappedToFeature = false,
                normalX = scratchNormalOut[0],
                normalY = scratchNormalOut[1],
                normalZ = scratchNormalOut[2],
                hasSurfaceNormal = true,
                depthConfidencePercent = cachedDepthConfidencePercent,
                incidenceCosine = cosIncidence,
                rawDepthInlierRatio = cachedRawDepthInlierRatio,
                isImageEdgeSnapped = isImageEdgeSnapped
            )
        }
        return null
    }

    /**
     * Extracts the normalized surface normal (pose local +Y) oriented toward the camera into [scratchNormalOut].
     */
    private fun extractOrientedNormal(orientationPose: Pose, hitPose: Pose, cameraPose: Pose) {
        val rot = orientationPose.rotationQuaternion
        ArMath.extractSurfaceNormalFromQuaternion(
            qx = rot[0],
            qy = rot[1],
            qz = rot[2],
            qw = rot[3],
            outNormal = scratchNormalOut,
            towardCamX = cameraPose.tx() - hitPose.tx(),
            towardCamY = cameraPose.ty() - hitPose.ty(),
            towardCamZ = cameraPose.tz() - hitPose.tz(),
            orientTowardCamera = true
        )
    }

    /**
     * Magnetically snap and auto-follow nearest detected plane polygon vertex (corner)
     * or boundary edge segment with zero-alloc scratch transforms.
     */
    fun findPlaneEdgeOrVertexSnap(
        plane: Plane,
        hitPose: Pose,
        snapRadiusMeters: Float = 0.08f,
        isCurrentlySnapped: Boolean = false
    ): Pair<Pose, Boolean>? {
        try {
            val poly = plane.polygon ?: return null
            val count = poly.remaining() / 2
            if (count < 2) return null

            scratchPointIn[0] = hitPose.tx()
            scratchPointIn[1] = hitPose.ty()
            scratchPointIn[2] = hitPose.tz()
            plane.centerPose.inverse().transformPoint(scratchPointIn, 0, scratchPointOut, 0)
            val hx = scratchPointOut[0]
            val hz = scratchPointOut[2]

            val effectiveSnapRadius = if (isCurrentlySnapped) snapRadiusMeters * 1.6f else snapRadiusMeters
            var bestDist = effectiveSnapRadius
            var snapX = hx
            var snapZ = hz
            var didSnap = false

            for (i in 0 until count) {
                val x1 = poly.get(i * 2)
                val z1 = poly.get(i * 2 + 1)
                val nextIdx = (i + 1) % count
                val x2 = poly.get(nextIdx * 2)
                val z2 = poly.get(nextIdx * 2 + 1)

                // 1. Polygon Corner Vertex Snap
                val dx1 = hx - x1
                val dz1 = hz - z1
                val d1Sq = dx1 * dx1 + dz1 * dz1
                if (d1Sq < bestDist * bestDist) {
                    bestDist = kotlin.math.sqrt(d1Sq)
                    snapX = x1
                    snapZ = z1
                    didSnap = true
                }

                // 2. Polygon Boundary Edge Segment Snap
                val edx = x2 - x1
                val edz = z2 - z1
                val lenSq = edx * edx + edz * edz
                if (lenSq > 1e-6f) {
                    val t = (((hx - x1) * edx + (hz - z1) * edz) / lenSq).coerceIn(0f, 1f)
                    val px = x1 + t * edx
                    val pz = z1 + t * edz
                    val pdx = hx - px
                    val pdz = hz - pz
                    val segDistSq = pdx * pdx + pdz * pdz
                    val edgeCutoff = kotlin.math.min(bestDist, effectiveSnapRadius * 0.75f)
                    if (segDistSq < edgeCutoff * edgeCutoff) {
                        bestDist = kotlin.math.sqrt(segDistSq)
                        snapX = px
                        snapZ = pz
                        didSnap = true
                    }
                }
            }

            if (didSnap) {
                val innerLockRadius = 0.035f
                val finalSnapX: Float
                val finalSnapZ: Float
                if (bestDist <= innerLockRadius || isCurrentlySnapped) {
                    finalSnapX = snapX
                    finalSnapZ = snapZ
                } else {
                    val t = ((bestDist - innerLockRadius) / (effectiveSnapRadius - innerLockRadius)).coerceIn(0f, 1f)
                    val pull = 1f - (t * t * (3f - 2f * t))
                    finalSnapX = hx * (1f - pull) + snapX * pull
                    finalSnapZ = hz * (1f - pull) + snapZ * pull
                }

                scratchPointIn[0] = finalSnapX
                scratchPointIn[1] = 0f
                scratchPointIn[2] = finalSnapZ
                plane.centerPose.transformPoint(scratchPointIn, 0, scratchPointOut, 0)
                val snappedPose = Pose(scratchPointOut, hitPose.rotationQuaternion)
                return Pair(snappedPose, true)
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private val reusableVMatrix = FloatArray(16)
    private val reusablePMatrix = FloatArray(16)
    private var cachedFeaturePointsCount = 0

    /**
     * Extract frame data matrices and state safely on Vulkan/Render thread with double-buffered zero-alloc matrices.
     */
    fun extractFrameData(frame: Frame, centerHitResult: HitTestResult? = null): ModernArFrame {
        val camera = frame.camera
        matrixPoolIdx = 1 - matrixPoolIdx
        val vMatrix = vMatrixPool[matrixPoolIdx]
        val pMatrix = pMatrixPool[matrixPoolIdx]
        camera.getViewMatrix(vMatrix, 0)
        camera.getProjectionMatrix(pMatrix, 0, 0.05f, 50.0f)

        frameCounter++

        // Extract detected planes with polygon 2D vertices (throttled every 8 frames to prevent GC hiccups)
        if (frameCounter % 8 == 0 || cachedPlanes.isEmpty()) {
            val planeInfos = mutableListOf<DetectedPlaneInfo>()
            val allPlanes = session?.getAllTrackables(Plane::class.java)
            allPlanes?.forEach { plane ->
                if (plane.subsumedBy == null) {
                    val poly2d = plane.polygon?.duplicate()
                    val rem = poly2d?.remaining() ?: 0
                    val polyArray = if (poly2d != null && rem > 0) {
                        val arr = FloatArray(rem)
                        try { poly2d.get(arr); arr } catch (_: Throwable) { FloatArray(0) }
                    } else FloatArray(0)
                    planeInfos.add(
                        DetectedPlaneInfo(
                            id = plane.hashCode().toString(),
                            type = plane.type,
                            centerPose = plane.centerPose,
                            extentX = plane.extentX,
                            extentZ = plane.extentZ,
                            polygonVertices = polyArray,
                            isTracking = plane.trackingState == TrackingState.TRACKING
                        )
                    )
                }
            }
            cachedPlanes = planeInfos
        }

        val pose = camera.pose
        val quaternion = pose.rotationQuaternion // [x, y, z, w]
        val qx = quaternion[0]
        val qy = quaternion[1]
        val qz = quaternion[2]
        val qw = quaternion[3]

        // Pitch & Yaw from Camera Pose
        val sinP = 2.0 * (qw * qx - qy * qz)
        val pRad = if (Math.abs(sinP) >= 1) Math.copySign(Math.PI / 2, sinP) else Math.asin(sinP)
        val pitchDeg = Math.toDegrees(pRad).toFloat()

        val sinY = 2.0 * (qw * qy + qz * qx)
        val cosY = 1.0 - 2.0 * (qx * qx + qy * qy)
        val yawDeg = Math.toDegrees(Math.atan2(sinY, cosY)).toFloat()

        val lightVal = try {
            frame.lightEstimate.pixelIntensity
        } catch (e: Exception) {
            1.0f
        }

        // Probe Depth / Raw Depth API readiness every 15 frames and always close native Image immediately
        if (isDepthModeActive && frameCounter % 15 == 0) {
            isDepthImageAvailable = try {
                frame.acquireDepthImage16Bits().use { depthImg ->
                    depthImg.width > 0 && depthImg.height > 0
                }
            } catch (_: NotYetAvailableException) {
                try {
                    frame.acquireRawDepthImage16Bits().use { rawImg ->
                        rawImg.width > 0 && rawImg.height > 0
                    }
                } catch (_: Throwable) {
                    false
                }
            } catch (_: Throwable) {
                try {
                    frame.acquireRawDepthImage16Bits().use { rawImg ->
                        rawImg.width > 0 && rawImg.height > 0
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        }

        // Fast surface label inference from center hit test result
        val surfaceLabel = when {
            centerHitResult?.planeType == Plane.Type.HORIZONTAL_UPWARD_FACING -> TranslationManager.getString("surf_horizontal", currentLangCode)
            centerHitResult?.planeType == Plane.Type.VERTICAL -> TranslationManager.getString("surf_vertical", currentLangCode)
            centerHitResult?.planeType == Plane.Type.HORIZONTAL_DOWNWARD_FACING -> TranslationManager.getString("surf_ceiling", currentLangCode)
            centerHitResult?.hitType == HitType.DEPTH_POINT -> TranslationManager.getString("surf_depth_hi", currentLangCode)
            centerHitResult?.hitType == HitType.INSTANT_PLACEMENT && centerHitResult.isApproximatePlacement ->
                TranslationManager.getString("surf_instant_approx", currentLangCode)
            centerHitResult?.hitType == HitType.INSTANT_PLACEMENT -> TranslationManager.getString("surf_instant", currentLangCode)
            else -> if (isDepthModeActive && isDepthImageAvailable) {
                TranslationManager.getString("surf_depth", currentLangCode)
            } else {
                TranslationManager.getString("surf_features", currentLangCode)
            }
        }

        // 1. Feature points count from PointCloud (throttled every 10 frames, deterministically closed)
        if (frameCounter % 10 == 0 || cachedFeaturePointsCount == 0) {
            cachedFeaturePointsCount = try {
                frame.acquirePointCloud().use { ptCloud ->
                    ptCloud.points.remaining() / 4
                }
            } catch (_: Throwable) {
                0
            }
        }
        val featurePointsCount = cachedFeaturePointsCount

        // 2. Camera panning velocity estimation (m/s) — only integrate when tracking is active
        val isTracking = camera.trackingState == TrackingState.TRACKING
        val nowNs = frame.timestamp
        val speed = if (isTracking) {
            val prevPose = lastCameraPose
            val s = if (prevPose != null && lastFrameTimeNs > 0 && nowNs > lastFrameTimeNs) {
                val dt = (nowNs - lastFrameTimeNs) / 1_000_000_000.0f
                val dx = pose.tx() - prevPose.tx()
                val dy = pose.ty() - prevPose.ty()
                val dz = pose.tz() - prevPose.tz()
                val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (dt in 0.001f..0.5f) (dist / dt) else 0f
            } else 0f
            lastCameraPose = pose
            lastFrameTimeNs = nowNs
            s
        } else {
            lastCameraPose = null
            lastFrameTimeNs = 0L
            0f
        }
        smoothedSpeedMps = smoothedSpeedMps * 0.75f + speed * 0.25f

        // 3. Multi-factor Stability & Confidence Calculation
        val failureReason = camera.trackingFailureReason
        val isCameraUnavailable = failureReason == TrackingFailureReason.CAMERA_UNAVAILABLE ||
            failureReason == TrackingFailureReason.BAD_STATE
        val isMotionExcessive = failureReason == TrackingFailureReason.EXCESSIVE_MOTION || smoothedSpeedMps > 0.55f
        val isFeatureDeficient = failureReason == TrackingFailureReason.INSUFFICIENT_FEATURES ||
                (isTracking && featurePointsCount < 25 && cachedPlanes.isEmpty()) ||
                (isTracking && featurePointsCount < 15)
        val isLightingDeficient = failureReason == TrackingFailureReason.INSUFFICIENT_LIGHT || lightVal < 0.20f

        var score = if (isTracking) 0.60f else 0.10f
        val activePlanes = cachedPlanes.count { it.isTracking }
        score += kotlin.math.min(0.20f, activePlanes * 0.08f)

        score += when {
            featurePointsCount >= 120 -> 0.15f
            featurePointsCount >= 60 -> 0.10f
            featurePointsCount >= 30 -> 0.05f
            featurePointsCount < 15 -> -0.25f
            featurePointsCount < 25 -> -0.15f
            else -> 0.0f
        }

        score += when {
            lightVal >= 0.40f -> 0.05f
            lightVal < 0.18f -> -0.20f
            lightVal < 0.28f -> -0.10f
            else -> 0.0f
        }

        if (smoothedSpeedMps > 0.80f) {
            score -= 0.35f
        } else if (smoothedSpeedMps > 0.45f) {
            score -= 0.18f
        }

        if (failureReason == TrackingFailureReason.EXCESSIVE_MOTION) score -= 0.30f
        if (failureReason == TrackingFailureReason.INSUFFICIENT_FEATURES) score -= 0.35f
        if (failureReason == TrackingFailureReason.INSUFFICIENT_LIGHT) score -= 0.25f
        if (isCameraUnavailable) score -= 0.45f

        val clampedScore = score.coerceIn(0.05f, 0.99f)
        smoothedConfidence = smoothedConfidence * 0.80f + clampedScore * 0.20f

        val stabilityLevel = when {
            smoothedConfidence >= 0.75f -> StabilityLevel.HIGH
            smoothedConfidence >= 0.50f -> StabilityLevel.MODERATE
            smoothedConfidence >= 0.25f -> StabilityLevel.LOW
            else -> StabilityLevel.POOR
        }

        val isDriftRisk = !isTracking || isCameraUnavailable || isMotionExcessive || isFeatureDeficient ||
                stabilityLevel == StabilityLevel.LOW || stabilityLevel == StabilityLevel.POOR

        val warningMsg = when {
            failureReason == TrackingFailureReason.CAMERA_UNAVAILABLE -> TranslationManager.getString("arcore_cam_reconnecting", currentLangCode)
            isFeatureDeficient -> TranslationManager.getString("stab_banner_default", currentLangCode)
            isMotionExcessive -> TranslationManager.getString("stab_warn_motion", currentLangCode)
            isLightingDeficient -> TranslationManager.getString("stab_warn_light", currentLangCode)
            !isTracking -> TranslationManager.getString("stab_warn_tracking", currentLangCode)
            else -> null
        }

        val stabilityMetrics = ArTrackingStability(
            confidenceScore = smoothedConfidence,
            level = stabilityLevel,
            featurePointsCount = featurePointsCount,
            trackingPlanesCount = activePlanes,
            cameraSpeedMps = smoothedSpeedMps,
            lightIntensity = lightVal,
            isMotionExcessive = isMotionExcessive,
            isFeatureDeficient = isFeatureDeficient,
            isLightingDeficient = isLightingDeficient,
            isDriftRisk = isDriftRisk,
            warningMessage = warningMsg
        )

        return ModernArFrame(
            trackingState = camera.trackingState,
            trackingFailureReason = camera.trackingFailureReason,
            viewMatrix = vMatrix.copyOf(),
            projectionMatrix = pMatrix.copyOf(),
            pointCloud = null,
            planes = cachedPlanes,
            cameraPoseX = pose.tx(),
            cameraPoseY = pose.ty(),
            cameraPoseZ = pose.tz(),
            cameraPitch = pitchDeg,
            cameraYaw = yawDeg,
            isDepthAvailable = isDepthModeActive && isDepthImageAvailable,
            lightIntensity = lightVal,
            surfaceTypeAtCenter = surfaceLabel,
            stability = stabilityMetrics
        )
    }
}

enum class HitType {
    PLANE_POLYGON,
    PLANE_ESTIMATED,
    DEPTH_POINT,
    INSTANT_PLACEMENT,
    FEATURE_POINT
}

data class HitTestResult(
    val pose: Pose,
    val anchor: Anchor?,
    val hitType: HitType,
    val distance: Float,
    val planeType: Plane.Type? = null,
    val planeCenterPose: Pose? = null,
    val isSnappedToFeature: Boolean = false,
    val isApproximatePlacement: Boolean = false,
    val normalX: Float = 0f,
    val normalY: Float = 1f,
    val normalZ: Float = 0f,
    val hasSurfaceNormal: Boolean = false,
    val depthConfidencePercent: Int = -1,
    val incidenceCosine: Float = 1.0f,
    val rawDepthInlierRatio: Float = -1f,
    val isImageEdgeSnapped: Boolean = false
)

/**
 * Headless EGL context providing an OpenGL ES texture ID for ARCore camera streaming.
 * Operates purely offscreen without requiring a GLSurfaceView.
 */
private class HeadlessEglHelper {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    var textureId: Int = 0
        private set

    fun init(): Int {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return 0

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return 0

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            if (numConfigs[0] <= 0 || configs[0] == null) return 0

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return 0

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return 0

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            textureId = textures[0]

            // CRITICAL: Unbind immediately from initialization thread so VulkanRenderThread can bind it
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            return textureId
        } catch (e: Throwable) {
            Log.w("HeadlessEglHelper", "Headless EGL setup skipped: ${e.message}")
            return 0
        }
    }

    fun makeCurrent(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
            val currentContext = EGL14.eglGetCurrentContext()
            if (currentContext == eglContext) return true
            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
        return false
    }

    fun detachCurrent() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    fun release() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (textureId != 0 && makeCurrent()) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    textureId = 0
                }
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Throwable) {
        } finally {
            textureId = 0
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
