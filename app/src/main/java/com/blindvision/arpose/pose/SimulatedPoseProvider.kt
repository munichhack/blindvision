package com.blindvision.arpose.pose

import android.os.Handler
import android.os.HandlerThread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emits a deterministic, physically plausible 6-DoF trajectory at ~30 Hz.
 *
 * Used when ARCore is unavailable — most importantly on the Apple-Silicon
 * arm64 emulator, where ARCore's emulator support (x86-only) does not apply.
 * The device "walks" a horizontal Lissajous loop while gently bobbing in
 * height and continuously yawing, so every component of the pose changes and
 * downstream consumers receive a realistic, moving world position.
 */
class SimulatedPoseProvider : PoseProvider {

    override val sourceName: String = "SIMULATED"

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val startNanos = System.nanoTime()

    override fun start(onPose: (Pose6Dof) -> Unit) {
        val ht = HandlerThread("sim-pose").also { it.start() }
        val h = Handler(ht.looper)
        thread = ht
        handler = h

        val tick = object : Runnable {
            override fun run() {
                val t = (System.nanoTime() - startNanos) / 1_000_000_000.0

                // Position: a 2 m x 1 m Lissajous loop on the floor plane,
                // with a small vertical bob — units are meters.
                val tx = (2.0 * sin(0.50 * t)).toFloat()
                val tz = (1.0 * cos(0.30 * t)).toFloat()
                val ty = (0.10 * sin(1.20 * t)).toFloat()

                // Orientation: steady yaw about the vertical (Y) axis.
                val yaw = 0.40 * t
                val qw = cos(yaw / 2.0).toFloat()
                val qy = sin(yaw / 2.0).toFloat()

                onPose(
                    Pose6Dof(
                        tx = tx, ty = ty, tz = tz,
                        qx = 0f, qy = qy, qz = 0f, qw = qw,
                        timestampNanos = System.nanoTime(),
                        source = sourceName
                    )
                )
                h.postDelayed(this, 33L) // ~30 Hz
            }
        }
        h.post(tick)
    }

    override fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        handler = null
        thread = null
    }
}
