package com.continuityhub.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.continuityhub.proto.CameraFrame
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AndroidPhoneMirrorManager(
    private val context: Context,
    private val onFrameCaptured: (CameraFrame) -> Unit
) {
    private val tag = "PhoneMirrorManager"

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val isMirroring = AtomicBoolean(false)
    private val _isMirroringState = MutableStateFlow(false)
    val isMirroringState = _isMirroringState.asStateFlow()

    private val frameCounter = AtomicLong(0)
    private var lastFrameTimeMs = 0L
    private val minFrameIntervalMs = 33L // Cap at ~30 FPS for smooth, efficient streaming

    // Reusable buffers to eliminate garbage collector churn (0 MB/s heap allocations during streaming)
    private var reusableRawBitmap: Bitmap? = null
    private var reusableCroppedBitmap: Bitmap? = null
    private var reusableCanvas: android.graphics.Canvas? = null
    private val reusableBaos = FastByteArrayOutputStream(256 * 1024)

    private class FastByteArrayOutputStream(capacity: Int) : ByteArrayOutputStream(capacity) {
        fun toByteString(): ByteString = ByteString.copyFrom(buf, 0, count)
    }

    fun startMirror(projection: MediaProjection, targetWidth: Int = 720, targetHeight: Int = 1600) {
        if (isMirroring.get()) {
            Log.w(tag, "Phone screen mirroring is already running")
            return
        }

        mediaProjection = projection

        handlerThread = HandlerThread("ContinuityScreenCapture").apply { start() }
        val handler = Handler(handlerThread!!.looper)
        backgroundHandler = handler

        // Android 14+ (API 34/35) mandates registering a MediaProjection.Callback before creating VirtualDisplay
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(tag, "MediaProjection stopped by system")
                stopMirror()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, handler)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Calculate proportional scale maintaining aspect ratio
        val aspectRatio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
        val width = targetWidth
        val height = (targetWidth * aspectRatio).toInt()
        val dpi = (metrics.densityDpi * (width.toFloat() / metrics.widthPixels.toFloat())).toInt().coerceAtLeast(160)

        Log.i(tag, "Starting Phone Screen Mirror: ${width}x${height} @ ${dpi}dpi (device: ${metrics.widthPixels}x${metrics.heightPixels})")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            handleImageAvailable(reader, width, height)
        }, backgroundHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ContinuityPhoneMirror",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.i(tag, "VirtualDisplay stopped")
                    stopMirror()
                }
            },
            backgroundHandler
        )

        isMirroring.set(true)
        _isMirroringState.value = true
        Log.i(tag, "Phone screen mirroring active and capturing frames")
    }

    private fun handleImageAvailable(reader: ImageReader, width: Int, height: Int) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < minFrameIntervalMs) {
                // Throttle to target FPS
                image.close()
                return
            }
            lastFrameTimeMs = now

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val rawWidth = width + rowPadding / pixelStride
            var rawBitmap = reusableRawBitmap
            if (rawBitmap == null || rawBitmap.width != rawWidth || rawBitmap.height != height) {
                rawBitmap?.recycle()
                rawBitmap = Bitmap.createBitmap(rawWidth, height, Bitmap.Config.ARGB_8888)
                reusableRawBitmap = rawBitmap
            }
            buffer.rewind()
            rawBitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = if (rowPadding == 0) {
                rawBitmap
            } else {
                var cropped = reusableCroppedBitmap
                if (cropped == null || cropped.width != width || cropped.height != height) {
                    cropped?.recycle()
                    cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    reusableCroppedBitmap = cropped
                    reusableCanvas = android.graphics.Canvas(cropped)
                }
                reusableCanvas?.drawBitmap(rawBitmap, 0f, 0f, null)
                cropped
            }

            reusableBaos.reset()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 65, reusableBaos)

            val frame = CameraFrame.newBuilder()
                .setFrameIndex(frameCounter.incrementAndGet())
                .setTimestampUs(System.currentTimeMillis() * 1000L)
                .setWidth(width)
                .setHeight(height)
                .setFrameData(reusableBaos.toByteString())
                .setIsKeyframe(true)
                .setIsPhoneScreen(true)
                .build()

            onFrameCaptured(frame)
        } catch (e: Exception) {
            Log.d(tag, "Screen frame capture skipped: ${e.message}")
        } finally {
            image?.close()
        }
    }

    fun stopMirror() {
        if (!isMirroring.getAndSet(false)) return

        _isMirroringState.value = false
        try {
            projectionCallback?.let { cb ->
                try {
                    mediaProjection?.unregisterCallback(cb)
                } catch (e: Exception) {
                    Log.d(tag, "Error unregistering projection callback: ${e.message}")
                }
            }
            projectionCallback = null

            reusableRawBitmap?.recycle()
            reusableRawBitmap = null
            reusableCroppedBitmap?.recycle()
            reusableCroppedBitmap = null
            reusableCanvas = null

            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            handlerThread?.quitSafely()
            handlerThread = null
            backgroundHandler = null

            Log.i(tag, "Phone screen mirroring stopped cleanly")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping screen mirror", e)
        }
    }
}
