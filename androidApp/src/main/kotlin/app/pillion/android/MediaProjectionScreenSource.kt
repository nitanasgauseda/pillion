package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import app.pillion.core.MirrorFit
import app.pillion.core.MirrorGeometry
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [ScreenSource] backed by MediaProjection. Single responsibility: screen -> JPEG.
 *
 * - **Encodes ahead.** JPEGs are produced on the capture thread as soon as the screen changes
 *   (throttled to twice the send rate, with a trailing encode so the last change is never lost), so
 *   [latestFrame] just hands over a finished JPEG and the Bluetooth loop never waits on compression.
 * - **Captures at the dash's size.** Once the handshake reveals the dash size the virtual display is
 *   resized (no new projection token needed) so a frame is a 1:1 crop, not a rescale. [MirrorFit.FILL]
 *   mirrors at the phone's aspect ratio and crops the centre instead of letterboxing.
 * - **Change tracking.** Each JPEG carries a sequence number ([frameSequence]) that only advances when
 *   the screen produced new pixels, letting the engine skip re-sending an unchanged picture.
 * - **No per-frame allocation** of bitmaps/streams: the pixel copy, output bitmap and JPEG buffer are reused.
 *
 * Threading: all capture state is confined to the capture [HandlerThread]; other threads only
 * read the published, immutable [Frame] and the volatile size/quality fields.
 */
class MediaProjectionScreenSource(
    private val context: Context,
    private val projection: MediaProjection,
    quality: Int = DEFAULT_QUALITY,
    maxFps: Int = 15,
    private val fit: MirrorFit = MirrorFit.FIT,
    private val onProjectionStopped: (() -> Unit)? = null,
) : ScreenSource {

    /** One published JPEG. Immutable, swapped atomically so sequence and bytes always agree. */
    private class Frame(val seq: Long, val width: Int, val height: Int, val jpeg: ByteArray)

    private val thread = HandlerThread("pillion-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val encodeIntervalMs: Long =
        if (maxFps in 1..59) maxOf(MIN_ENCODE_INTERVAL_MS, 1000L / (maxFps * 2)) else MIN_ENCODE_INTERVAL_MS
    private val endLock = Any()

    @Volatile private var quality = quality.coerceIn(1, 100)
    @Volatile private var frame: Frame? = null
    @Volatile private var ended = false
    // Size the engine asked for. Read on any thread: a JPEG of any other size is never handed out.
    @Volatile private var requestedWidth = DEFAULT_WIDTH
    @Volatile private var requestedHeight = DEFAULT_HEIGHT

    // ---- capture-thread state: touched only on [handler]'s thread ----
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var geometry = MirrorGeometry.compute(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, MirrorFit.FIT)
    private var outWidth = DEFAULT_WIDTH
    private var outHeight = DEFAULT_HEIGHT
    private var raw: Bitmap? = null            // last captured pixels (may include row padding)
    private var rawGeometry: MirrorGeometry? = null // geometry [raw] was captured under
    private var packed: ByteBuffer? = null     // fallback copy target when a plane's last row is unpadded
    private var rawDirty = false
    private var encodeScheduled = false
    private var lastEncodeAt = 0L
    private var output: Bitmap? = null
    private var outputCanvas: Canvas? = null
    private val jpegBuffer = ByteArrayOutputStream(64 * 1024)
    private var nextSeq = 0L
    private val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) // overwrite, never blend with the last frame
    }
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var loggedFirstFrame = false

    private val encodeRunnable = Runnable { encode() }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "screen: projection stopped")
            releaseCapture("projection_stopped")
            onProjectionStopped?.invoke()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // Rotation changes the phone's aspect ratio, which only matters for FILL's crop.
            if (displayId == Display.DEFAULT_DISPLAY && fit == MirrorFit.FILL) reconfigure("rotation")
        }
    }

    /**
     * Creates the virtual display immediately (a MediaProjection token goes stale if this is
     * deferred), on the capture thread, and rethrows any failure to the caller.
     */
    override fun start() {
        if (ended) return
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val posted = handler.post {
            try {
                startOnCaptureThread()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        if (!posted) return // already released
        if (!done.await(START_TIMEOUT_S, TimeUnit.SECONDS)) error("screen capture start timed out")
        failure?.let { throw it }
    }

    private fun startOnCaptureThread() {
        if (ended || display != null) return // no restart with a stopped, single-use projection
        // Android 14+ requires a registered callback before createVirtualDisplay.
        projection.registerCallback(projectionCallback, handler)
        geometry = computeGeometry()
        val r = newReader(geometry)
        reader = r
        display = projection.createVirtualDisplay(
            "pillion", geometry.captureWidth, geometry.captureHeight, densityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        runCatching { displayManager.registerDisplayListener(displayListener, handler) }
        Log.d(TAG, "screen: virtual display created (${geometry.captureWidth}x${geometry.captureHeight}, fit=$fit)")
    }

    private fun newReader(g: MirrorGeometry): ImageReader =
        ImageReader.newInstance(g.captureWidth, g.captureHeight, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)
        }

    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    private fun computeGeometry(): MirrorGeometry {
        val size = Point()
        runCatching {
            @Suppress("DEPRECATION") // getRealSize is the rotation-aware size available to a Service
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        }
        return MirrorGeometry.compute(size.x, size.y, outWidth, outHeight, fit)
    }

    /** Resize the virtual display (and its reader) to the geometry for the current output size. */
    private fun reconfigure(reason: String) {
        if (ended) return
        val vd = display ?: return
        val g = computeGeometry()
        if (g == geometry) return
        if (g.captureWidth != geometry.captureWidth || g.captureHeight != geometry.captureHeight) {
            val newReader = newReader(g)
            try {
                vd.resize(g.captureWidth, g.captureHeight, densityDpi())
                vd.surface = newReader.surface
            } catch (t: Throwable) {
                // Keep the old capture: encode() still scales its crop to the output size.
                Log.w(TAG, "screen: virtual display resize failed ($reason)", t)
                runCatching { vd.surface = reader?.surface }
                newReader.close()
                return
            }
            val old = reader
            reader = newReader
            runCatching { old?.setOnImageAvailableListener(null, null) }
            runCatching { old?.close() }
        }
        geometry = g
        Log.d(TAG, "screen: capture ${g.captureWidth}x${g.captureHeight}, crop ${g.cropWidth}x${g.cropHeight}@${g.cropX},${g.cropY} ($reason)")
    }

    private fun onImage(ir: ImageReader) {
        if (ended || ir !== reader) return
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        try {
            copyPixels(image)
            rawGeometry = geometry
            rawDirty = true
        } catch (t: Throwable) {
            Log.w(TAG, "screen: dropped a frame", t)
            return
        } finally {
            image.close()
        }
        scheduleEncode()
    }

    /** Copy the image into the reused [raw] bitmap (row padding kept as extra columns, cropped later). */
    private fun copyPixels(image: Image) {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        val paddedWidth = if (pixelStride > 0) plane.rowStride / pixelStride else width
        if (buffer.remaining() >= plane.rowStride * height) {
            val bmp = reuse(raw, paddedWidth, height).also { raw = it }
            bmp.copyPixelsFromBuffer(buffer)
        } else {
            // Some drivers don't pad the final row; copyPixelsFromBuffer would throw on the short
            // buffer, so pack rows tightly instead.
            val rowBytes = width * pixelStride
            val dst = packed?.takeIf { it.capacity() >= rowBytes * height }
                ?: ByteBuffer.allocateDirect(rowBytes * height).also { packed = it }
            dst.clear()
            for (row in 0 until height) {
                buffer.limit(row * plane.rowStride + rowBytes)
                buffer.position(row * plane.rowStride)
                dst.put(buffer)
            }
            dst.flip()
            val bmp = reuse(raw, width, height).also { raw = it }
            bmp.copyPixelsFromBuffer(dst)
        }
    }

    private fun reuse(bitmap: Bitmap?, width: Int, height: Int): Bitmap =
        if (bitmap != null && bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun scheduleEncode() {
        if (encodeScheduled || ended) return
        encodeScheduled = true
        val wait = lastEncodeAt + encodeIntervalMs - SystemClock.uptimeMillis()
        if (wait <= 0L) handler.post(encodeRunnable) else handler.postDelayed(encodeRunnable, wait)
    }

    private fun encode() {
        encodeScheduled = false
        if (ended || !rawDirty) return
        val src = raw ?: return
        val g = rawGeometry ?: return
        rawDirty = false
        lastEncodeAt = SystemClock.uptimeMillis()
        val w = outWidth
        val h = outHeight
        try {
            val toCompress = if (g.cropX == 0 && g.cropY == 0 && src.width == w && src.height == h) {
                src // already exactly the output: compress directly
            } else {
                val out = output?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        output = it
                        outputCanvas = Canvas(it)
                    }
                // 1:1 copy of the crop when the capture matches the output; a scale only for the
                // one frame captured before a resize took effect.
                srcRect.set(g.cropX, g.cropY, g.cropX + g.cropWidth, g.cropY + g.cropHeight)
                dstRect.set(0, 0, w, h)
                outputCanvas!!.drawBitmap(src, srcRect, dstRect, copyPaint)
                out
            }
            jpegBuffer.reset()
            toCompress.compress(Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
            // An onStop callback may arrive during JPEG compression. Never hand out a dead frame.
            if (ended) return
            frame = Frame(++nextSeq, w, h, jpegBuffer.toByteArray())
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                Log.d(TAG, "screen: first frame encoded (${w}x$h, ${jpegBuffer.size()} bytes)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "screen: encode failed", t)
        }
    }

    override fun latestFrame(): ByteArray? {
        if (ended) return null
        val f = frame ?: return null
        return if (f.width == requestedWidth && f.height == requestedHeight) f.jpeg else null
    }

    override fun frameSequence(): Long {
        val f = frame ?: return ScreenSource.UNKNOWN_SEQUENCE
        return if (f.width == requestedWidth && f.height == requestedHeight) f.seq else ScreenSource.UNKNOWN_SEQUENCE
    }

    override fun setQuality(quality: Int) {
        this.quality = quality.coerceIn(1, 100)
    }

    override fun resizeOutput(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == requestedWidth && height == requestedHeight) return
        // Gate first, so a frame of the old size can't be handed out from this point on.
        requestedWidth = width
        requestedHeight = height
        Log.d(TAG, "screen: output size set to ${width}x$height")
        handler.post {
            if (ended) return@post
            outWidth = width
            outHeight = height
            reconfigure("dash size")
            // Re-encode the last pixels at the new size right away: a static screen may not
            // produce another image after the resize.
            if (raw != null) {
                rawDirty = true
                lastEncodeAt = 0L
                scheduleEncode()
            }
        }
    }

    override fun stop() {
        releaseCapture("stop")
        runCatching { projection.stop() }
    }

    /** A system revocation and the Stop button both converge on the same, idempotent cleanup. */
    private fun releaseCapture(reason: String) {
        synchronized(endLock) {
            if (ended) return
            ended = true
        }
        frame = null
        val posted = handler.post {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
            runCatching { projection.unregisterCallback(projectionCallback) }
            val oldDisplay = display
            val oldReader = reader
            display = null
            reader = null
            runCatching { oldReader?.setOnImageAvailableListener(null, null) }
            runCatching { oldDisplay?.release() }
            runCatching { oldReader?.close() }
            raw = null
            output = null
            outputCanvas = null
            Log.d(TAG, "screen: capture released ($reason)")
        }
        if (!posted) Log.d(TAG, "screen: capture thread already gone ($reason)")
        thread.quitSafely() // runs the cleanup above (already due), drops pending delayed encodes
    }

    private companion object {
        const val DEFAULT_WIDTH = 480
        const val DEFAULT_HEIGHT = 240
        const val DEFAULT_QUALITY = 40
        const val MIN_ENCODE_INTERVAL_MS = 20L
        const val START_TIMEOUT_S = 5L
        const val TAG = "Pillion"
    }
}
