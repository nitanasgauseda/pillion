package app.pillion.core

/** How the phone screen is placed on the dash when their aspect ratios differ. */
enum class MirrorFit {
    /** Whole screen visible, letterboxed with black bars (the original behaviour). */
    FIT,
    /** Fill the dash edge to edge, cropping the centre of the screen (e.g. a portrait map's middle). */
    FILL;

    companion object {
        fun fromName(name: String?): MirrorFit = entries.firstOrNull { it.name == name } ?: FIT
    }
}

/**
 * Where to capture and what to crop so a frame comes out at exactly the dash's size with no
 * rescale: the mirror virtual display is [captureWidth]x[captureHeight] and the JPEG is the
 * [cropWidth]x[cropHeight] region at ([cropX], [cropY]) — which always equals the output size.
 */
data class MirrorGeometry(
    val captureWidth: Int,
    val captureHeight: Int,
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int,
) {
    companion object {
        /**
         * @param screenWidth/screenHeight the phone display's current real size (rotation-aware)
         * @param outWidth/outHeight the dash image size negotiated at handshake
         */
        fun compute(screenWidth: Int, screenHeight: Int, outWidth: Int, outHeight: Int, fit: MirrorFit): MirrorGeometry {
            require(outWidth > 0 && outHeight > 0) { "bad output size ${outWidth}x$outHeight" }
            if (fit == MirrorFit.FIT || screenWidth <= 0 || screenHeight <= 0) {
                // AUTO_MIRROR letterboxes the screen into the display itself: capture at output size.
                return MirrorGeometry(outWidth, outHeight, 0, 0, outWidth, outHeight)
            }
            // FILL: scale the screen so it covers the output (one side matches, the other overflows),
            // mirror it into a display of exactly that aspect, then crop the centre.
            val scaleByWidth = outWidth.toLong() * screenHeight >= outHeight.toLong() * screenWidth
            val captureWidth: Int
            val captureHeight: Int
            if (scaleByWidth) {
                captureWidth = outWidth
                captureHeight = maxOf(outHeight, roundDiv(screenHeight.toLong() * outWidth, screenWidth.toLong()))
            } else {
                captureHeight = outHeight
                captureWidth = maxOf(outWidth, roundDiv(screenWidth.toLong() * outHeight, screenHeight.toLong()))
            }
            return MirrorGeometry(
                captureWidth, captureHeight,
                (captureWidth - outWidth) / 2, (captureHeight - outHeight) / 2,
                outWidth, outHeight,
            )
        }

        private fun roundDiv(a: Long, b: Long): Int = ((a + b / 2) / b).toInt()
    }
}
