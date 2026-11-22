package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.logic.ar.ArMath
import com.example.logic.ar.OneEuroFilter3D
import com.example.logic.ar.OneEuroFilterScalar
import com.example.logic.ar.OneEuroNormalSlerp
import com.example.logic.vulkan.VulkanMatrix4
import com.example.ui.viewmodel.MeasureViewModel
import com.example.ui.viewmodel.Point3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArCircleAndThemeStrictModeTest {

    @get:Rule
    val vmRule = ManagedViewModelRule()

    @Test
    fun `app_name string resource matches Measure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Measure", context.getString(R.string.app_name))
    }

    @Test
    fun `ArMath fitCircle2Points and fitCircle3Points accurately compute 3D circles`() {
        val c2 = ArMath.fitCircle2Points(
            Point3D(0.0, 0.0, -1.0),
            Point3D(1.0, 0.0, -1.0)
        )
        assertNotNull(c2)
        assertEquals(0.5, c2!!.radius, 1e-4)
        assertEquals(1.0, c2.diameter, 1e-4)
        assertEquals(PI, c2.circumference, 1e-4)
        assertEquals(0.5, c2.center.x, 1e-4)

        // Tilted 3D plane circle with radius 0.8m around (1.0, 0.5, -2.0)
        val p1 = Point3D(1.8, 0.5, -2.0)
        val p2 = Point3D(1.0, 1.3, -2.0)
        val p3 = Point3D(0.2, 0.5, -2.0)
        val c3 = ArMath.fitCircle3Points(p1, p2, p3)
        assertNotNull(c3)
        assertEquals(0.8, c3!!.radius, 1e-4)
        assertEquals(1.6, c3.diameter, 1e-4)
        assertEquals(1.0, c3.center.x, 1e-4)
        assertEquals(0.5, c3.center.y, 1e-4)
        assertEquals(-2.0, c3.center.z, 1e-4)
    }

    @Test
    fun `ArMath extractSurfaceNormalFromQuaternion and basis project tilted 3D circle cleanly`() {
        val normal = FloatArray(3)
        // Identity quaternion -> +Y floor normal (0, 1, 0)
        ArMath.extractSurfaceNormalFromQuaternion(0f, 0f, 0f, 1f, normal)
        assertEquals(0f, normal[0], 1e-4f)
        assertEquals(1f, normal[1], 1e-4f)
        assertEquals(0f, normal[2], 1e-4f)

        // 90-degree rotation around X -> vertical wall normal (0, 0, +1)
        val halfAngle = (PI / 4.0)
        ArMath.extractSurfaceNormalFromQuaternion(
            sin(halfAngle).toFloat(),
            0f,
            0f,
            cos(halfAngle).toFloat(),
            normal
        )
        assertEquals(0f, normal[0], 1e-4f)
        assertEquals(0f, normal[1], 1e-4f)
        assertEquals(1f, normal[2], 1e-4f)

        val basis = DoubleArray(9)
        assertTrue(ArMath.buildOrthonormalBasis(normal[0].toDouble(), normal[1].toDouble(), normal[2].toDouble(), basis))

        // N, U, V must be unit length and mutually orthogonal
        val nLen = sqrt(basis[0] * basis[0] + basis[1] * basis[1] + basis[2] * basis[2])
        val uLen = sqrt(basis[3] * basis[3] + basis[4] * basis[4] + basis[5] * basis[5])
        val vLen = sqrt(basis[6] * basis[6] + basis[7] * basis[7] + basis[8] * basis[8])
        assertEquals(1.0, nLen, 1e-6)
        assertEquals(1.0, uLen, 1e-6)
        assertEquals(1.0, vLen, 1e-6)
        assertEquals(0.0, basis[0] * basis[3] + basis[1] * basis[4] + basis[2] * basis[5], 1e-6)

        val wx = DoubleArray(24)
        val wy = DoubleArray(24)
        val wz = DoubleArray(24)
        val count = ArMath.sampleCircle3DWorldCoords(0.0, 0.0, -2.0, 0.4, basis, 24, wx, wy, wz)
        assertEquals(24, count)
        for (i in 0 until count) {
            assertEquals(-2.0, wz[i], 1e-5)
            val r = sqrt(wx[i] * wx[i] + wy[i] * wy[i])
            assertEquals(0.4, r, 1e-5)
        }

        val view = FloatArray(16)
        val proj = FloatArray(16)
        VulkanMatrix4.setIdentity(view, 0)
        VulkanMatrix4.perspective(proj, 0, 60f, 1080f / 2400f, 0.05f, 50f)

        val sx = FloatArray(24)
        val sy = FloatArray(24)
        val scratch = FloatArray(2)
        val projCount = ArMath.projectCircle3DToScreen(
            0.0, 0.0, -2.0, 0.4, basis, 24, view, proj, 1080, 2400, sx, sy, scratch
        )
        assertEquals(24, projCount)
        assertTrue(sx.all { it.isFinite() && abs(it - 540f) < 600f })
        assertTrue(sy.all { it.isFinite() && abs(it - 1200f) < 600f })
    }

    @Test
    fun `OneEuroFilter3D damps stationary jitter and tracks fast panning with zero NaN`() {
        val filter = OneEuroFilter3D(minCutoff = 2.6, beta = 35.0, jumpResetDistance = 0.50)
        val out = DoubleArray(3)
        assertTrue(filter.filterInto(0.0, 0.0, -1.5, 0.016, out))

        // Stationary hand tremor +/- 4mm alternating noise at 60Hz -> output must stay well below 2mm amplitude
        var maxDeviation = 0.0
        for (i in 1..20) {
            val jitter = if (i % 2 == 0) 0.004 else -0.004
            filter.filterInto(jitter, -jitter, -1.5 + jitter, 0.016, out)
            maxDeviation = maxOf(maxDeviation, abs(out[0]))
        }
        assertTrue("1€ filter should attenuate high-freq 4mm tremor (< 2.5mm, got $maxDeviation)", maxDeviation < 0.0025)

        // Rapid continuous pan (0.25m in 100ms at 120Hz) -> cutoff ramps up and stays tight behind target
        val dt120 = 1.0 / 120.0
        for (step in 1..12) {
            val targetX = step * 0.02
            filter.filterInto(targetX, 0.0, -1.5, dt120, out)
        }
        val lag = abs(0.24 - out[0])
        assertTrue("1€ filter high-speed pan lag must remain small (< 2.5cm, got $lag)", lag < 0.025)

        // Edge resilience: dt = 0 and NaN must never break state
        filter.filterInto(Double.NaN, 0.0, -1.5, 0.016, out)
        assertTrue(out[0].isFinite() && out[1].isFinite() && out[2].isFinite())
        val scalar = OneEuroFilterScalar(minCutoff = 2.0, beta = 30.0)
        assertEquals(0.05, scalar.filter(0.05, 0.016), 1e-6)
        assertEquals(0.05, scalar.filter(0.06, 0.0), 1e-6)
    }

    @Test
    fun `slerpUnitNormal and OneEuroNormalSlerp preserve unit norm and shortest arc`() {
        val out = FloatArray(3)
        // Exact 90-degree midpoint SLERP between +Y (0,1,0) and +Z (0,0,1) -> (0, sqrt(2)/2, sqrt(2)/2)
        ArMath.slerpUnitNormal(0f, 1f, 0f, 0f, 0f, 1f, 0.5f, out)
        val norm = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        assertEquals(1.0f, norm, 1e-5f)
        assertEquals(0f, out[0], 1e-5f)
        assertEquals(0.70710677f, out[1], 1e-4f)
        assertEquals(0.70710677f, out[2], 1e-4f)

        // Opposite hemisphere normal aligns cleanly instead of cancelling to zero
        ArMath.slerpUnitNormal(0f, 1f, 0f, 0f, -1f, 0f, 0.5f, out)
        val oppNorm = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        assertEquals(1.0f, oppNorm, 1e-5f)

        // Horizontal plane stabilization suppresses 1.5-deg floor noise while preserving a 12-deg ramp
        val stab = FloatArray(3)
        val tinyTiltRad = Math.toRadians(1.5)
        ArMath.stabilizeHorizontalNormal(sin(tinyTiltRad), cos(tinyTiltRad), 0.0, stab)
        assertTrue("Tiny 1.5-deg floor noise should settle closer to +Y", stab[1] > cos(tinyTiltRad).toFloat())

        val slopeRad = Math.toRadians(12.0)
        ArMath.stabilizeHorizontalNormal(sin(slopeRad), cos(slopeRad), 0.0, stab)
        assertEquals(sin(slopeRad).toFloat(), stab[0], 1e-5f)
        assertEquals(cos(slopeRad).toFloat(), stab[1], 1e-5f)

        // OneEuroNormalSlerp converges cleanly from floor to wall over 120Hz frames
        val slerp = OneEuroNormalSlerp()
        slerp.update(0f, 1f, 0f, 0.016)
        repeat(20) { slerp.update(0f, 0f, 1f, 1.0 / 120.0) }
        assertTrue("SLERP should smoothly rotate toward wall normal (+Z)", slerp.currentNormal[2] > 0.70f)
    }

    @Test
    fun `projectCircle3DWithDepthToScreen and cameraDistanceToWorldPoint output accurate 3D depth bounds`() {
        val view = FloatArray(16)
        val proj = FloatArray(16)
        VulkanMatrix4.setIdentity(view, 0)
        VulkanMatrix4.perspective(proj, 0, 60f, 1080f / 2400f, 0.05f, 50f)

        // Floor-aligned circle (normal = +Y) placed at (0, -0.8, -2.0) with radius 0.45m -> near edge is closer (-1.55m) than far edge (-2.45m)
        val basis = DoubleArray(9)
        assertTrue(ArMath.buildOrthonormalBasis(0.0, 1.0, 0.0, basis))

        val sx = FloatArray(36)
        val sy = FloatArray(36)
        val clipW = FloatArray(36)
        val bounds = FloatArray(2)
        val cnt = ArMath.projectCircle3DWithDepthToScreen(
            cx = 0.0, cy = -0.8, cz = -2.0, radiusMeters = 0.45,
            basis = basis, segments = 36,
            viewMatrix = view, projectionMatrix = proj,
            screenWidth = 1080, screenHeight = 2400,
            outScreenX = sx, outScreenY = sy, outClipW = clipW, outDepthBounds = bounds
        )
        assertEquals(36, cnt)
        assertTrue("Near edge clipW (${bounds[0]}) must be strictly smaller than far edge (${bounds[1]})", bounds[1] - bounds[0] > 0.80f)
        assertEquals(1.55f, bounds[0], 0.03f)
        assertEquals(2.45f, bounds[1], 0.03f)

        val camDist = ArMath.cameraDistanceToWorldPoint(0.0, -0.8, -2.0, view)
        assertEquals(sqrt(0.8 * 0.8 + 2.0 * 2.0), camDist, 1e-4)
    }

    @Test
    fun `MeasureViewModel themeMode updates StateFlow and persists choice`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = vmRule.create(app)

        vm.setThemeMode("LIGHT")
        assertEquals("LIGHT", vm.themeMode.value)

        vm.setThemeMode("DARK")
        assertEquals("DARK", vm.themeMode.value)

        vm.setThemeMode("SYSTEM")
        assertEquals("SYSTEM", vm.themeMode.value)
    }

    @Test
    fun `MeasureViewModel strictly gates hit tests to native ARCore when not in explicit simulation mode`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val shadowPm = Shadows.shadowOf(app.packageManager)
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.google.ar.core"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.google.ar.core"
                    enabled = true
                }
            }
        )

        val vm = vmRule.create(app)
        // Even with com.google.ar.core installed, ARCore Session is null in Robolectric -> must NOT capture fallback points
        assertFalse(vm.isCameraSimulationActive.value)
        vm.requestHitTest(540f, 1200f)

        assertTrue(vm.getCapturedPointsSnapshot().isEmpty())
        assertFalse(vm.isCameraSimulationActive.value)
        assertFalse(vm.isMeasurementAvailable.value)
    }

    @Test
    fun `MeasureViewModel calculateCircle handles 2-point and 3-point modes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(app.packageManager)
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.google.ar.core"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.google.ar.core"
                    enabled = true
                }
            }
        )
        val vm = vmRule.create(app)
        vm.setCameraSubMode(4)
        vm.setCameraSimulationActive(true)
        vm.clearActivePoints()

        vm.requestHitTest(350f, 1000f)
        Thread.sleep(210)
        vm.requestHitTest(750f, 1000f)
        val c2 = vm.calculateCircle()
        assertNotNull(c2)
        assertTrue(c2!!.diameter > 0.0)

        Thread.sleep(210)
        vm.requestHitTest(550f, 800f)
        val c3 = vm.calculateCircle()
        assertNotNull(c3)
        assertEquals(3, vm.getCapturedPointsSnapshot().size)
        assertTrue(c3!!.diameter > 0.0)
    }
}
