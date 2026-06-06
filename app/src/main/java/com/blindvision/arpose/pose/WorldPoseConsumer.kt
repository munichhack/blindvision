package com.blindvision.arpose.pose

import android.util.Log
import kotlin.math.sqrt

/**
 * A representative *downstream application* of the world-position stream.
 *
 * It takes each incoming [Pose6Dof] and derives higher-level quantities that a
 * real consumer (navigation, mapping, robotics, accessibility guidance, …)
 * would want: cumulative path length and instantaneous speed. Every pose is
 * also emitted as a structured logcat line under the tag [LOG_TAG], so the data
 * pipeline can be observed from `adb logcat` even with no UI.
 *
 * Results are surfaced to the UI through [onUpdate], throttled to ~10 Hz.
 */
class WorldPoseConsumer(
    private val onUpdate: (Readout) -> Unit
) {
    data class Readout(
        val source: String,
        val tx: Float, val ty: Float, val tz: Float,
        val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float,
        val pathLengthMeters: Float,
        val speedMetersPerSec: Float,
        val poseCount: Long
    )

    private var lastTx = 0f
    private var lastTy = 0f
    private var lastTz = 0f
    private var lastTimestamp = 0L
    private var havePrevious = false

    private var pathLength = 0f
    private var poseCount = 0L
    private var lastUiEmitNanos = 0L

    /** Feed one world pose into the downstream pipeline. */
    fun onPose(p: Pose6Dof) {
        poseCount++

        var speed = 0f
        if (havePrevious) {
            val dx = p.tx - lastTx
            val dy = p.ty - lastTy
            val dz = p.tz - lastTz
            val step = sqrt(dx * dx + dy * dy + dz * dz)
            pathLength += step
            val dtSec = (p.timestampNanos - lastTimestamp) / 1_000_000_000f
            if (dtSec > 0f) speed = step / dtSec
        }
        lastTx = p.tx; lastTy = p.ty; lastTz = p.tz
        lastTimestamp = p.timestampNanos
        havePrevious = true

        val (yaw, pitch, roll) = p.eulerDegrees()

        // Structured pipeline log — observable via `adb logcat -s WorldPose`.
        Log.i(
            LOG_TAG,
            "src=%s n=%d pos=[% .3f,% .3f,% .3f]m quat=[% .3f,% .3f,% .3f,% .3f] yaw=% .1f pitch=% .1f roll=% .1f path=%.3fm speed=%.3fm/s"
                .format(
                    p.source, poseCount, p.tx, p.ty, p.tz,
                    p.qx, p.qy, p.qz, p.qw, yaw, pitch, roll,
                    pathLength, speed
                )
        )

        // Throttle UI updates to ~10 Hz to keep the main thread light.
        val now = System.nanoTime()
        if (now - lastUiEmitNanos >= 100_000_000L) {
            lastUiEmitNanos = now
            onUpdate(
                Readout(
                    source = p.source,
                    tx = p.tx, ty = p.ty, tz = p.tz,
                    yawDeg = yaw, pitchDeg = pitch, rollDeg = roll,
                    pathLengthMeters = pathLength,
                    speedMetersPerSec = speed,
                    poseCount = poseCount
                )
            )
        }
    }

    companion object {
        const val LOG_TAG = "WorldPose"
    }
}
