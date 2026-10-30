package com.continuityhub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.continuityhub.proto.CameraConfig
import com.continuityhub.service.ContinuityService

@Composable
fun CameraScreen(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val cameraState by ContinuityService.cameraUiState.collectAsState()
    val cameraManager = ContinuityService.getCameraManager()
    var showFullscreenMirror by remember { mutableStateOf(false) }

    // Fullscreen Landscape Desktop Mirror / Sidecar Dialog
    if (showFullscreenMirror && cameraState.desktopScreenBitmap != null) {
        DesktopFullscreenViewer(
            bitmap = cameraState.desktopScreenBitmap!!,
            isStreaming = cameraState.isDesktopStreaming,
            isSidecar = cameraState.isSidecarActive,
            onToggleStream = {
                if (cameraState.isSidecarActive) {
                    cameraManager?.toggleSidecarStream()
                } else {
                    cameraManager?.toggleDesktopStream()
                }
            },
            onSidecarClick = { normX, normY ->
                cameraManager?.sendSidecarClick(normX, normY)
            },
            onRefresh = { cameraManager?.requestDesktopScreen() },
            onDismiss = { showFullscreenMirror = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Camera Viewfinder Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (cameraState.previewBitmap != null && cameraState.isStreaming) {
                    Image(
                        bitmap = cameraState.previewBitmap!!.asImageBitmap(),
                        contentDescription = "Camera Viewfinder",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(rotationZ = cameraState.rotationDegrees.toFloat()),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = "Camera Standby",
                            tint = Color.Gray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isConnected) "Camera Ready (Standby)" else "Waiting for Desktop Connection",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                    }
                }

                // Overlay status badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Live / Standby badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                color = if (cameraState.isStreaming) Color(0xCCEF4444) else Color(0x88000000),
                                shape = RoundedCornerShape(9999.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (cameraState.isStreaming) Color.White else Color.Gray,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (cameraState.isStreaming) "LIVE • ${cameraState.fps} FPS" else "STANDBY",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Resolution & Rotation Badges
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .background(Color(0x88000000), shape = RoundedCornerShape(9999.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${cameraState.rotationDegrees}°",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(Color(0x88000000), shape = RoundedCornerShape(9999.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${cameraState.width}x${cameraState.height}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 2. Camera Controls Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Big Stream Toggle Button
                Button(
                    onClick = {
                        if (cameraState.isStreaming) {
                            cameraManager?.stopStreaming()
                        } else {
                            cameraManager?.startStreaming()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (cameraState.isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = if (cameraState.isStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (cameraState.isStreaming) "Stop Wireless Webcam" else "Start Wireless Webcam",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Action Toolbar (Switch Lens, Rotate 90°, Torch, Snapshot)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Switch Lens
                    OutlinedButton(
                        onClick = { cameraManager?.toggleLens() },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Switch Lens", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (cameraState.lens == CameraConfig.Lens.LENS_FRONT) "Front" else "Back")
                    }

                    // Rotate 90°
                    OutlinedButton(
                        onClick = { cameraManager?.rotateCamera(90) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${cameraState.rotationDegrees}°")
                    }

                    // Torch Toggle
                    OutlinedButton(
                        onClick = { cameraManager?.toggleTorch() },
                        enabled = cameraState.lens == CameraConfig.Lens.LENS_BACK,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (cameraState.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Torch",
                            modifier = Modifier.size(18.dp),
                            tint = if (cameraState.isTorchOn) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Snapshot Shutter
                    FilledIconButton(
                        onClick = { cameraManager?.takeSnapshot() },
                        enabled = cameraState.isStreaming,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Snapshot")
                    }
                }

                // Digital Zoom Slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Digital Zoom", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = String.format("%.1fx", cameraState.zoomRatio),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = cameraState.zoomRatio,
                        onValueChange = { cameraManager?.setZoom(it) },
                        valueRange = 1.0f..5.0f,
                        steps = 8
                    )
                }
            }
        }

        // 3. Desktop Screencopy Mirror Card with Live Streaming & Fullscreen
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                var isSidecarModeSelected by remember { mutableStateOf(cameraState.isSidecarActive) }

                // Mode Selector: Desktop Mirror vs Sidecar Virtual Monitor
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isSidecarModeSelected,
                        onClick = { isSidecarModeSelected = false },
                        label = { Text("Desktop Mirror", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isSidecarModeSelected,
                        onClick = { isSidecarModeSelected = true },
                        label = { Text("Sidecar Display", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isSidecarModeSelected) {
                    // SIDECAR MODE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.2f),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Sidecar Extended Display",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (cameraState.isSidecarActive) {
                                    Text(
                                        text = "● SIDECAR ACTIVE • 1920x1080",
                                        fontSize = 10.sp,
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (cameraState.desktopScreenBitmap != null && cameraState.isSidecarActive) {
                            FilledIconButton(
                                onClick = { showFullscreenMirror = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Creates an independent virtual Wayland monitor for your Linux desktop. Drag windows to the right of your laptop screen or click the Continuity Hub tray icon to move windows onto your phone!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { cameraManager?.toggleSidecarStream() },
                        enabled = isConnected,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cameraState.isSidecarActive) MaterialTheme.colorScheme.error else Color(0xFF10B981)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (cameraState.isSidecarActive) Icons.Default.Stop else Icons.Default.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (cameraState.isSidecarActive) "Stop Sidecar Display" else "Start Sidecar Extended Display (1920x1080)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // DESKTOP MIRROR MODE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Computer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Hyprland Desktop Mirror",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (cameraState.isDesktopStreaming && !cameraState.isSidecarActive) {
                                    Text(
                                        text = "LIVE STREAMING",
                                        fontSize = 10.sp,
                                        color = Color(0xFF22C55E),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (cameraState.desktopScreenBitmap != null && !cameraState.isSidecarActive) {
                                FilledIconButton(
                                    onClick = { showFullscreenMirror = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", modifier = Modifier.size(20.dp))
                                }
                            }

                            OutlinedButton(
                                onClick = { cameraManager?.requestDesktopScreen() },
                                enabled = isConnected && !cameraState.isDesktopStreaming,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Capture", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Capture", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Stream a real-time feed of your primary Linux desktop or view in fullscreen landscape.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { cameraManager?.toggleDesktopStream() },
                        enabled = isConnected,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cameraState.isDesktopStreaming && !cameraState.isSidecarActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (cameraState.isDesktopStreaming && !cameraState.isSidecarActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (cameraState.isDesktopStreaming && !cameraState.isSidecarActive) "Stop Desktop Stream" else "Start Live Desktop Stream (~15 FPS)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (cameraState.desktopScreenBitmap != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showFullscreenMirror = true }
                    ) {
                        Image(
                            bitmap = cameraState.desktopScreenBitmap!!.asImageBitmap(),
                            contentDescription = "Desktop Screen Mirror (Tap for Fullscreen)",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Tap to enlarge hint badge
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                            color = Color(0xAA000000),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Tap for Fullscreen", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. Linux Streaming Endpoint Info Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Linux Virtual Webcam URL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Use in OBS Studio, VLC, or browser as a video source:\nhttp://127.0.0.1:52802/live.mjpg",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Immersive Fullscreen Desktop Screen Viewer with pinch-to-zoom, pan, and live streaming controls.
 */
@Composable
fun DesktopFullscreenViewer(
    bitmap: android.graphics.Bitmap,
    isStreaming: Boolean,
    isSidecar: Boolean = false,
    onToggleStream: () -> Unit,
    onSidecarClick: ((Float, Float) -> Unit)? = null,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Interactive Zoomable Desktop Image with Sidecar Touch support
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isSidecar) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1.2f) 1.0f else 2.2f
                                offsetX = 0f
                                offsetY = 0f
                            },
                            onTap = { offset ->
                                if (isSidecar && onSidecarClick != null && size.width > 0 && size.height > 0) {
                                    val normX = (offset.x / size.width).coerceIn(0f, 1f)
                                    val normY = (offset.y / size.height).coerceIn(0f, 1f)
                                    onSidecarClick(normX, normY)
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1.0f, 5.0f)
                            if (scale > 1.0f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Fullscreen Desktop View",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
            }

            // Top Floating Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(9999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isSidecar) Color(0xFF10B981) else if (isStreaming) Color(0xFF22C55E) else Color.Gray,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSidecar) "SIDECAR ACTIVE • TAP TO CLICK" else if (isStreaming) "LIVE STREAM" else "PAUSED",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (scale > 1.05f) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "•  ${String.format("%.1fx", scale)}",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Play/Pause stream
                    FilledIconButton(
                        onClick = onToggleStream,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Stream"
                        )
                    }

                    // Refresh frame
                    FilledIconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }

                    // Close / Exit Fullscreen
                    FilledIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen")
                    }
                }
            }

            // Bottom Double-Tap Reset Zoom Hint
            if (scale > 1.05f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    color = Color(0x88000000),
                    shape = RoundedCornerShape(9999.dp)
                ) {
                    Text(
                        text = "Double-tap to reset zoom",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
