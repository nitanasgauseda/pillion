package app.pillion.core

/**
 * User-tunable session settings. Lower values trade smoothness for battery, heat and bandwidth.
 *
 * @param quality JPEG quality of each frame (10–80).
 * @param maxFps  upper bound on frames sent per second; the engine paces to this.
 * @param dashResolution off-screen display size for dedicated dash mode; output is scaled to 480x240.
 * @param mirrorFit letterbox the whole phone screen ([MirrorFit.FIT]) or fill the dash by cropping
 *   its centre ([MirrorFit.FILL]). Phone-mirroring mode only.
 */
data class MirrorSettings(
    val quality: Int = 40,
    val maxFps: Int = 15,
    val dashResolution: DashResolution = DashResolution.DEFAULT,
    val dashDpi: Int = DashDpi.DEFAULT,
    val dashMargins: DashMargins = DashMargins(),
    val dashMarginColor: DashMarginColor = DashMarginColor.Black,
    val mirrorFit: MirrorFit = MirrorFit.FIT,
)
