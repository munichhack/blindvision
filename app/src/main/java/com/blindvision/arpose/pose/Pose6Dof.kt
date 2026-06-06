package com.blindvision.arpose.pose

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A single 6-DoF world pose: where the device is (translation, in meters,
 * relative to ARCore's world origin) and how it is oriented (unit quaternion).
 *
 * This is the unit of data handed to downstream consumers. It is deliberately
 * source-agnostic — it does not matter whether it came from ARCore on a phone
 * or from the simulated provider on the emulator.
 */
data class Pose6Dof(
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
    val timestampNanos: Long,
    val source: String
) {
    /** Straight-line distance from the world origin, in meters. */
    fun distanceFromOrigin(): Float = sqrt(tx * tx + ty * ty + tz * tz)

    /**
     * Yaw/pitch/roll in degrees for ARCore's Y-up world coordinate system,
     * using intrinsic Y-X-Z (heading about up, then pitch, then roll). A pure
     * rotation about the vertical axis therefore shows up as yaw.
     */
    fun eulerDegrees(): Triple<Float, Float, Float> {
        // Rotation-matrix elements needed for a Y-X-Z decomposition.
        val m13 = 2f * (qx * qz + qw * qy)
        val m23 = 2f * (qy * qz - qw * qx)
        val m33 = 1f - 2f * (qx * qx + qy * qy)
        val m21 = 2f * (qx * qy + qw * qz)
        val m22 = 1f - 2f * (qx * qx + qz * qz)

        val pitch = asin(m23.coerceIn(-1f, 1f).let { -it })
        val yaw: Float
        val roll: Float
        if (kotlin.math.abs(m23) < 0.9999999f) {
            yaw = atan2(m13, m33)
            roll = atan2(m21, m22)
        } else {
            // Gimbal lock: looking straight up/down.
            yaw = atan2(-(2f * (qx * qz - qw * qy)), 1f - 2f * (qy * qy + qz * qz))
            roll = 0f
        }
        val rad2deg = (180.0 / Math.PI).toFloat()
        return Triple(yaw * rad2deg, pitch * rad2deg, roll * rad2deg)
    }
}
