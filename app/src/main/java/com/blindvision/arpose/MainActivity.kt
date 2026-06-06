package com.blindvision.arpose

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import com.blindvision.arpose.pose.ArCorePoseProvider
import com.blindvision.arpose.pose.PoseProvider
import com.blindvision.arpose.pose.SimulatedPoseProvider
import com.blindvision.arpose.pose.WorldPoseConsumer
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException

/**
 * Entry point. Decides at runtime which [PoseProvider] to use:
 *
 *   - On a certified device with Google Play Services for AR → [ArCorePoseProvider]
 *     (real world positions from ARCore).
 *   - Otherwise (the arm64 emulator, or an uncertified phone) → [SimulatedPoseProvider].
 *
 * Either way the world poses flow into [WorldPoseConsumer], the downstream
 * application, which derives metrics and logs them under tag "WorldPose".
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var poseText: TextView
    private lateinit var derivedText: TextView
    private lateinit var glSurfaceView: GLSurfaceView

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var consumer: WorldPoseConsumer
    private var provider: PoseProvider? = null
    private var started = false
    private var arInstallRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)
        poseText = findViewById(R.id.pose_text)
        derivedText = findViewById(R.id.derived_text)
        glSurfaceView = findViewById(R.id.gl_surface)

        consumer = WorldPoseConsumer { readout ->
            runOnUiThread { render(readout) }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStart()
    }

    override fun onPause() {
        super.onPause()
        provider?.stop()
        provider = null
        started = false
    }

    /** Resolve the pose source and begin streaming, retrying transient states. */
    private fun maybeStart() {
        if (started) return
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                setStatus("Checking ARCore availability…")
                mainHandler.postDelayed({ maybeStart() }, 200)
            }
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                startArCoreOrRequestPermission()
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                requestArInstall()
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            ArCoreApk.Availability.UNKNOWN_ERROR ->
                startSimulated("ARCore not supported on this device.")
        }
    }

    private fun startArCoreOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        try {
            glSurfaceView.visibility = View.VISIBLE
            val p = ArCorePoseProvider(this, glSurfaceView)
            p.start { pose -> consumer.onPose(pose) }
            provider = p
            started = true
            setStatus("Source: ARCORE — real 6-DoF world tracking active.")
            Log.i(WorldPoseConsumer.LOG_TAG, "Pose source = ARCORE")
        } catch (e: UnavailableException) {
            Log.e(WorldPoseConsumer.LOG_TAG, "ARCore init failed", e)
            startSimulated("ARCore failed to start (${e.javaClass.simpleName}).")
        }
    }

    private fun requestArInstall() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arInstallRequested = true
                    setStatus("Requesting Google Play Services for AR…")
                }
                ArCoreApk.InstallStatus.INSTALLED ->
                    startArCoreOrRequestPermission()
            }
        } catch (e: UnavailableException) {
            Log.e(WorldPoseConsumer.LOG_TAG, "ARCore install unavailable", e)
            startSimulated("ARCore install unavailable (${e.javaClass.simpleName}).")
        }
    }

    private fun startSimulated(reason: String) {
        glSurfaceView.visibility = View.GONE
        val p = SimulatedPoseProvider()
        p.start { pose -> consumer.onPose(pose) }
        provider = p
        started = true
        setStatus("$reason\nSource: SIMULATED — synthetic 6-DoF trajectory.")
        Log.i(WorldPoseConsumer.LOG_TAG, "Pose source = SIMULATED ($reason)")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                maybeStart()
            } else {
                startSimulated("Camera permission denied.")
            }
        }
    }

    private fun render(r: WorldPoseConsumer.Readout) {
        poseText.text = buildString {
            append("WORLD POSITION (meters)\n")
            append("  x = % .3f\n".format(r.tx))
            append("  y = % .3f\n".format(r.ty))
            append("  z = % .3f\n".format(r.tz))
            append("\nORIENTATION (degrees)\n")
            append("  yaw   = % .1f\n".format(r.yawDeg))
            append("  pitch = % .1f\n".format(r.pitchDeg))
            append("  roll  = % .1f".format(r.rollDeg))
        }
        derivedText.text = buildString {
            append("DOWNSTREAM METRICS\n")
            append("  poses     = ${r.poseCount}\n")
            append("  path len  = %.3f m\n".format(r.pathLengthMeters))
            append("  speed     = %.3f m/s".format(r.speedMetersPerSec))
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private companion object {
        const val REQ_CAMERA = 1001
    }
}
