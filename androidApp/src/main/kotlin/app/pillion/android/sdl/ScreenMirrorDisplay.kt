package app.pillion.android.sdl

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import app.pillion.android.AndroidSettingsStore
import app.pillion.core.Logger
import app.pillion.core.MirrorFit
import app.pillion.core.MirrorGeometry
import com.smartdevicelink.streaming.video.SdlRemoteDisplay

/**
 * Casts the phone's live screen to the dash — the Pillion idea over SDL. MediaProjection mirrors the
 * device display (AUTO_MIRROR) into a TextureView inside this SdlRemoteDisplay; SDL encodes that to
 * H.264 and streams it to the head unit. The MediaProjection is supplied via [ProjectionHolder].
 *
 * Screen fit (Settings, persisted): [MirrorFit.FIT] mirrors at the dash's size, so AUTO_MIRROR
 * letterboxes the phone screen. [MirrorFit.FILL] mirrors at the phone's aspect ratio into a
 * TextureView larger than the dash and centred in a clipping container, so the dash shows the
 * centre of the screen edge to edge. It follows phone rotation.
 */
class ScreenMirrorDisplay(context: Context, display: Display) : SdlRemoteDisplay(context, display) {

    private var virtualDisplay: VirtualDisplay? = null
    private var texture: TextureView? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var fit = MirrorFit.FIT
    private var geometry: MirrorGeometry? = null
    private var dashWidth = 0
    private var dashHeight = 0

    private val displayManager: DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applyGeometry("rotation")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fit = runCatching { AndroidSettingsStore(context).mirrorFit() }.getOrDefault(MirrorFit.FIT)

        val size = Point()
        display.getSize(size)
        dashWidth = size.x
        dashHeight = size.y
        val g = computeGeometry()
        geometry = g
        val dpi = context.resources.displayMetrics.densityDpi

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        val tv = TextureView(context)
        texture = tv
        // A child larger than the container is centred with negative offsets and clipped: the crop.
        container.addView(tv, FrameLayout.LayoutParams(g.captureWidth, g.captureHeight, Gravity.CENTER))
        setContentView(container)

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val mp = ProjectionHolder.projection
                if (mp == null) {
                    Logger.d("screen mirror: no MediaProjection — grant screen capture first")
                    return
                }
                val current = geometry ?: g
                st.setDefaultBufferSize(current.captureWidth, current.captureHeight)
                val s = Surface(st)
                surfaceTexture = st
                surface = s
                try {
                    virtualDisplay = mp.createVirtualDisplay(
                        "pillion-sdl-mirror", current.captureWidth, current.captureHeight, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        s, null, null,
                    )
                    Logger.d(
                        "screen mirror started -> dash ${dashWidth}x$dashHeight " +
                            "(capture ${current.captureWidth}x${current.captureHeight}, fit=$fit)",
                    )
                } catch (t: Throwable) {
                    Logger.e("screen mirror createVirtualDisplay failed", t)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                virtualDisplay?.release()
                virtualDisplay = null
                surface?.release()
                surface = null
                surfaceTexture = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (fit == MirrorFit.FILL) {
            runCatching { displayManager?.registerDisplayListener(displayListener, null) }
        }
    }

    private fun computeGeometry(): MirrorGeometry {
        if (dashWidth <= 0 || dashHeight <= 0) return MirrorGeometry(1, 1, 0, 0, 1, 1)
        val phone = Point()
        runCatching {
            @Suppress("DEPRECATION") // rotation-aware real size; no Activity window here
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(phone)
        }
        return MirrorGeometry.compute(phone.x, phone.y, dashWidth, dashHeight, fit)
    }

    /** Phone rotated in FILL mode: re-crop for the new aspect ratio. Runs on the main thread. */
    private fun applyGeometry(reason: String) {
        val g = computeGeometry()
        if (g == geometry) return
        geometry = g
        val tv = texture ?: return
        tv.layoutParams = FrameLayout.LayoutParams(g.captureWidth, g.captureHeight, Gravity.CENTER)
        val vd = virtualDisplay ?: return
        val st = surfaceTexture ?: return
        val s = surface ?: return
        runCatching {
            // Detach before resizing: on Android 14+ the mirror is scaled to the attached surface.
            vd.surface = null
            st.setDefaultBufferSize(g.captureWidth, g.captureHeight)
            vd.resize(g.captureWidth, g.captureHeight, context.resources.displayMetrics.densityDpi)
            vd.surface = s
            Logger.d("screen mirror: capture ${g.captureWidth}x${g.captureHeight} ($reason)")
        }.onFailure { Logger.e("screen mirror: resize failed ($reason)", it) }
    }

    override fun onStop() {
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        virtualDisplay?.release()
        virtualDisplay = null
        super.onStop()
    }

    override fun onViewResized(width: Int, height: Int) {
        Logger.d("dash video surface: ${width}x$height")
    }
}
