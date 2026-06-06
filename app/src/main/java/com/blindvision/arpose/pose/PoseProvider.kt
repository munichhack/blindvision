package com.blindvision.arpose.pose

/**
 * A source of world poses. Two implementations exist:
 *
 *  - [ArCorePoseProvider]   — real ARCore 6-DoF tracking (physical device).
 *  - [SimulatedPoseProvider] — a synthetic trajectory (emulator / no ARCore).
 *
 * Downstream code depends only on this interface, so the rest of the app — and
 * any "downstream application" consuming world positions — is identical in both
 * cases. This is the abstraction that lets the project run on an Apple-Silicon
 * arm64 emulator (where ARCore cannot produce poses) and on the Redmi Note 11.
 */
interface PoseProvider {

    /** Human-readable name of the backing source, e.g. "ARCORE" or "SIMULATED". */
    val sourceName: String

    /** Begin producing poses. [onPose] is invoked for every new pose. */
    fun start(onPose: (Pose6Dof) -> Unit)

    /** Stop producing poses and release resources. */
    fun stop()
}
