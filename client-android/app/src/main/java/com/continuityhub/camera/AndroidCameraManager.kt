package com.continuityhub.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.continuityhub.proto.CameraConfig
import com.continuityhub.proto.CameraControlCommand
import com.continuityhub.proto.CameraFrame
import com.continuityhub.proto.CameraMessage
import com.continuityhub.proto.CameraSnapshot
import com.continuityhub.proto.DesktopScreenFrame
import com.continuityhub.service.ContinuityService
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class CameraUiState(
    val isStreaming: Boolean = false,
    val lens: CameraConfig.Lens = CameraConfig.Lens.LENS_BACK,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 0,
    val isTorchOn: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val previewBitmap: Bitmap? = null,
    val desktopScreenBitmap: Bitmap? = null,
    val isDesktopMirroring: Boolean = false
)

class AndroidCameraManager(private val context: Context) {

    private val tag = "AndroidCameraManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val frameIndex = AtomicLong(0)
    private var frameCounter = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("ContinuityCameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startStreaming(
        lens: CameraConfig.Lens = _uiState.value.lens,
        width: Int = 1920,
        height: Int = 1080
    ) {
        if (_uiState.value.isStreaming) {
            stopStreaming()
        }

        startBackgroundThread()
        val targetFacing = if (lens == CameraConfig.Lens.LENS_FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: cameraManager.cameraIdList.firstOrNull() ?: return

        try {
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val currentIndex = frameIndex.incrementAndGet()
                val timestampUs = System.nanoTime() / 1000

                // Track FPS
                frameCounter++
                val now = System.currentTimeMillis()
                if (now - lastFpsTimestamp >= 1000) {
                    val currentFps = frameCounter
                    frameCounter = 0
                    lastFpsTimestamp = now
                    _uiState.value = _uiState.value.copy(fps = currentFps)
                }

                // Decode preview bitmap every 3 frames for local UI
                if (currentIndex % 3L == 0L) {
                    try {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        _uiState.value = _uiState.value.copy(previewBitmap = bmp)
                    } catch (_: Exception) {}
                }

                // Stream frame to Linux desktop
                val frameMsg = CameraFrame.newBuilder()
                    .setFrameIndex(currentIndex)
                    .setTimestampUs(timestampUs)
                    .setWidth(width)
                    .setHeight(height)
                    .setFrameData(ByteString.copyFrom(bytes))
                    .setIsKeyframe(true)
                    .build()

                ContinuityService.broadcastCameraMessage(
                    CameraMessage.newBuilder().setFrame(frameMsg).build()
                )
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, width, height)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _uiState.value = _uiState.value.copy(isStreaming = false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(tag, "Camera device error: $error")
                    camera.close()
                    cameraDevice = null
                    _uiState.value = _uiState.value.copy(isStreaming = false)
                }
            }, backgroundHandler)

            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                lens = lens,
                width = width,
                height = height
            )

            // Broadcast CameraConfig to Desktop
            val configMsg = CameraConfig.newBuilder()
                .setWidth(width)
                .setHeight(height)
                .setTargetFps(30)
                .setLens(lens)
                .setFormat(CameraConfig.VideoFormat.FORMAT_MJPEG)
                .setTorchEnabled(_uiState.value.isTorchOn)
                .setZoomRatio(_uiState.value.zoomRatio)
                .build()

            ContinuityService.broadcastCameraMessage(
                CameraMessage.newBuilder().setConfig(configMsg).build()
            )

            Log.i(tag, "Camera streaming started: ${width}x$height, lens: $lens")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start camera streaming: ${e.message}", e)
            _uiState.value = _uiState.value.copy(isStreaming = false)
        }
    }

    private fun createCaptureSession(camera: CameraDevice, width: Int, height: Int) {
        val readerSurface = imageReader?.surface ?: return

        try {
            val surfaces = listOf(readerSurface)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    requestBuilder.addTarget(readerSurface)

                    // Auto-focus & Auto-exposure
                    requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                    // Torch
                    if (_uiState.value.isTorchOn) {
                        requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }

                    // Zoom
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, _uiState.value.zoomRatio)
                    }

                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "Camera capture session configuration failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(tag, "Error creating camera capture session", e)
        }
    }

    fun stopStreaming() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            stopBackgroundThread()
            _uiState.value = _uiState.value.copy(
                isStreaming = false,
                fps = 0
            )
            Log.i(tag, "Camera streaming stopped")
        } catch (e: Exception) {
            Log.w(tag, "Error stopping camera stream: ${e.message}")
        }
    }

    fun toggleLens() {
        val currentLens = _uiState.value.lens
        val newLens = if (currentLens == CameraConfig.Lens.LENS_BACK) {
            CameraConfig.Lens.LENS_FRONT
        } else {
            CameraConfig.Lens.LENS_BACK
        }
        _uiState.value = _uiState.value.copy(lens = newLens, isTorchOn = false)
        if (_uiState.value.isStreaming) {
            startStreaming(newLens, _uiState.value.width, _uiState.value.height)
        }
    }

    fun toggleTorch() {
        val currentTorch = _uiState.value.isTorchOn
        val newTorch = !currentTorch
        _uiState.value = _uiState.value.copy(isTorchOn = newTorch)

        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            requestBuilder.addTarget(readerSurface)
            if (newTorch) {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(tag, "Failed to toggle torch: ${e.message}")
        }
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(1.0f, 5.0f)
        _uiState.value = _uiState.value.copy(zoomRatio = clamped)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val session = captureSession ?: return
            val camera = cameraDevice ?: return
            val readerSurface = imageReader?.surface ?: return

            try {
                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                requestBuilder.addTarget(readerSurface)
                requestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(tag, "Failed to set zoom: ${e.message}")
            }
        }
    }

    fun takeSnapshot() {
        val frame = _uiState.value.previewBitmap ?: return
        scope.launch {
            val snap = CameraSnapshot.newBuilder()
                .setSnapshotId(UUID.randomUUID().toString())
                .setTimestampMs(System.currentTimeMillis())
                .setWidth(_uiState.value.width)
                .setHeight(_uiState.value.height)
                .build()

            ContinuityService.broadcastCameraMessage(
                CameraMessage.newBuilder().setSnapshot(snap).build()
            )
            Log.i(tag, "Dispatched Camera Snapshot to desktop")
        }
    }

    fun requestDesktopScreen() {
        scope.launch {
            val cmd = CameraControlCommand.newBuilder()
                .setAction(CameraControlCommand.Action.ACTION_REQUEST_DESKTOP_SCREEN)
                .build()

            ContinuityService.broadcastCameraMessage(
                CameraMessage.newBuilder().setControl(cmd).build()
            )
            _uiState.value = _uiState.value.copy(isDesktopMirroring = true)
            Log.i(tag, "Requested Hyprland desktop screenshot mirror")
        }
    }

    fun handleDesktopScreenFrame(frame: DesktopScreenFrame) {
        try {
            val bytes = frame.imageBytes.toByteArray()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            _uiState.value = _uiState.value.copy(desktopScreenBitmap = bmp)
            Log.i(tag, "Updated Desktop Screen Mirror frame (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.w(tag, "Failed to decode DesktopScreenFrame: ${e.message}")
        }
    }

    fun executeRemoteControl(control: CameraControlCommand) {
        Log.i(tag, "Received remote camera control action: ${control.action}")
        when (control.action) {
            CameraControlCommand.Action.ACTION_START -> {
                startStreaming(control.lens, if (control.width > 0) control.width else 1920, if (control.height > 0) control.height else 1080)
            }
            CameraControlCommand.Action.ACTION_STOP -> {
                stopStreaming()
            }
            CameraControlCommand.Action.ACTION_SWITCH_LENS -> {
                toggleLens()
            }
            CameraControlCommand.Action.ACTION_TOGGLE_TORCH -> {
                toggleTorch()
            }
            CameraControlCommand.Action.ACTION_SET_ZOOM -> {
                setZoom(control.zoomRatio)
            }
            CameraControlCommand.Action.ACTION_TAKE_SNAPSHOT -> {
                takeSnapshot()
            }
            else -> {}
        }
    }
}
