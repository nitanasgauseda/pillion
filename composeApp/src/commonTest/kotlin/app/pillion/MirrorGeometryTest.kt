package app.pillion

import app.pillion.core.MirrorFit
import app.pillion.core.MirrorGeometry
import app.pillion.core.MirrorSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorGeometryTest {

    private fun assertCropInside(g: MirrorGeometry, outW: Int, outH: Int) {
        assertEquals(outW, g.cropWidth)
        assertEquals(outH, g.cropHeight)
        assertTrue(g.cropX >= 0 && g.cropY >= 0)
        assertTrue(g.cropX + g.cropWidth <= g.captureWidth, "crop overflows width: $g")
        assertTrue(g.cropY + g.cropHeight <= g.captureHeight, "crop overflows height: $g")
    }

    @Test
    fun fit_captures_exactly_at_the_dash_size() {
        val g = MirrorGeometry.compute(1080, 2400, 480, 234, MirrorFit.FIT)
        assertEquals(MirrorGeometry(480, 234, 0, 0, 480, 234), g)
    }

    @Test
    fun fill_portrait_phone_crops_the_vertical_centre() {
        val g = MirrorGeometry.compute(1080, 2400, 480, 240, MirrorFit.FILL)
        assertEquals(480, g.captureWidth)
        assertEquals(1067, g.captureHeight) // 2400 * 480 / 1080, rounded
        assertEquals(0, g.cropX)
        assertEquals((1067 - 240) / 2, g.cropY)
        assertCropInside(g, 480, 240)
    }

    @Test
    fun fill_wide_landscape_phone_crops_the_sides() {
        val g = MirrorGeometry.compute(2400, 1080, 480, 240, MirrorFit.FILL)
        assertEquals(240, g.captureHeight)
        assertEquals(533, g.captureWidth) // 2400 * 240 / 1080
        assertEquals((533 - 480) / 2, g.cropX)
        assertEquals(0, g.cropY)
        assertCropInside(g, 480, 240)
    }

    @Test
    fun fill_with_matching_aspect_needs_no_crop() {
        val g = MirrorGeometry.compute(960, 480, 480, 240, MirrorFit.FILL)
        assertEquals(MirrorGeometry(480, 240, 0, 0, 480, 240), g)
    }

    @Test
    fun fill_crop_always_fits_for_many_screen_shapes() {
        for (w in listOf(720, 1079, 1080, 1440, 2340, 2400, 3088)) for (h in listOf(720, 1080, 1644, 2340, 2400, 3200)) {
            for ((ow, oh) in listOf(480 to 240, 480 to 234, 800 to 480)) {
                assertCropInside(MirrorGeometry.compute(w, h, ow, oh, MirrorFit.FILL), ow, oh)
            }
        }
    }

    @Test
    fun unknown_screen_size_falls_back_to_letterbox() {
        assertEquals(MirrorGeometry(480, 240, 0, 0, 480, 240), MirrorGeometry.compute(0, 0, 480, 240, MirrorFit.FILL))
    }

    @Test
    fun fit_names_round_trip_and_default_to_letterbox() {
        assertEquals(MirrorFit.FILL, MirrorFit.fromName("FILL"))
        assertEquals(MirrorFit.FIT, MirrorFit.fromName(null))
        assertEquals(MirrorFit.FIT, MirrorFit.fromName("bogus"))
        assertEquals(MirrorFit.FIT, MirrorSettings().mirrorFit)
    }
}
