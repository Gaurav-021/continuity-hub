package com.continuityhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.continuityhub.proto.InputMessage
import com.continuityhub.proto.KeyEvent
import com.continuityhub.proto.MouseButtonEvent
import com.continuityhub.proto.MouseMoveEvent
import com.continuityhub.proto.MouseScrollEvent
import com.continuityhub.service.ContinuityService

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrackpadScreen(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    var sensitivity by remember { mutableFloatStateOf(1.6f) }
    var textInput by remember { mutableStateOf("") }
    var showKeyboardInput by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status & Sensitivity header
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.TouchApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Virtual Wayland Pointer",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    IconButton(onClick = { showKeyboardInput = !showKeyboardInput }) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Toggle Keyboard",
                            tint = if (showKeyboardInput) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Sensitivity: ${String.format("%.1f", sensitivity)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(115.dp)
                    )
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        valueRange = 0.5f..3.5f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Live Text / Voice Input Field
        if (showKeyboardInput) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Type text or dictate to desktop...") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (textInput.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                sendTextToDesktop(textInput)
                                textInput = ""
                            }
                        },
                        enabled = isConnected && textInput.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Text")
                    }
                }
            }
        }

        // Trackpad Touch Surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(24.dp))
                .pointerInput(sensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var pointerCount = 1
                        var totalDx = 0f
                        var totalDy = 0f
                        val downTime = System.currentTimeMillis()

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointers = event.changes.filter { it.pressed }
                            pointerCount = maxOf(pointerCount, pointers.size)

                            if (pointers.size == 1) {
                                val change = pointers[0]
                                val delta = change.positionChange()

                                // Ergonomic non-linear velocity curve:
                                // Sub-pixel precision for slow moves, dynamic acceleration for fast flicks
                                val rawDist = kotlin.math.hypot(delta.x, delta.y)
                                val accel = if (rawDist > 5f) {
                                    1.0f + (rawDist - 5f).coerceAtMost(30f) * 0.045f
                                } else {
                                    1.0f
                                }

                                val dx = delta.x * sensitivity * accel
                                val dy = delta.y * sensitivity * accel

                                totalDx += kotlin.math.abs(dx)
                                totalDy += kotlin.math.abs(dy)

                                if (dx != 0f || dy != 0f) {
                                    sendMouseMove(dx, dy)
                                    change.consume()
                                }
                            } else if (pointers.size == 2) {
                                val delta1 = pointers[0].positionChange()
                                val delta2 = pointers[1].positionChange()
                                val avgScrollX = (delta1.x + delta2.x) / 2f
                                val avgScrollY = (delta1.y + delta2.y) / 2f

                                totalDx += kotlin.math.abs(avgScrollX)
                                totalDy += kotlin.math.abs(avgScrollY)

                                if (avgScrollX != 0f || avgScrollY != 0f) {
                                    // Negative sign standard for scroll wheel direction
                                    sendMouseScroll(-avgScrollX * 0.5f, -avgScrollY * 0.5f)
                                    pointers[0].consume()
                                    pointers[1].consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        val duration = System.currentTimeMillis() - downTime
                        val distance = totalDx + totalDy

                        // Tap detection: short duration and minimal motion
                        if (duration < 250 && distance < 15f) {
                            if (pointerCount == 1) {
                                // 1-Finger Tap -> Left Click
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                sendMouseButton(MouseButtonEvent.Button.BUTTON_LEFT, MouseButtonEvent.State.STATE_CLICK)
                            } else if (pointerCount >= 2) {
                                // 2-Finger Tap -> Right Click
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                sendMouseButton(MouseButtonEvent.Button.BUTTON_RIGHT, MouseButtonEvent.State.STATE_CLICK)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mouse,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isConnected) "Touch & Drag to Move Cursor" else "Connecting to Desktop...",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "1-Finger Tap: Left Click  •  2-Finger Drag: Scroll  •  2-Finger Tap: Right Click",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        // Dedicated Mouse Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sendMouseButton(MouseButtonEvent.Button.BUTTON_LEFT, MouseButtonEvent.State.STATE_CLICK)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Left Click", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sendMouseButton(MouseButtonEvent.Button.BUTTON_MIDDLE, MouseButtonEvent.State.STATE_CLICK)
                },
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxSize(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Middle", style = MaterialTheme.typography.labelMedium)
            }

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sendMouseButton(MouseButtonEvent.Button.BUTTON_RIGHT, MouseButtonEvent.State.STATE_CLICK)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Right Click", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }

        // Quick Desktop Shortcuts Row
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShortcutChip("Esc") { sendSpecialKey("Escape") }
                ShortcutChip("Tab") { sendSpecialKey("Tab") }
                ShortcutChip("Enter") { sendSpecialKey("Return") }
                ShortcutChip("Bksp") { sendSpecialKey("BackSpace") }
                ShortcutChip("Space") { sendSpecialKey("space") }
                ShortcutChip("Super") { sendSpecialKey("Super_L") }
                ShortcutChip("Ctrl+C") { sendKeyWithModifier("c", "ctrl") }
                ShortcutChip("Ctrl+V") { sendKeyWithModifier("v", "ctrl") }
                ShortcutChip("Ctrl+Z") { sendKeyWithModifier("z", "ctrl") }
                ShortcutChip("Alt+Tab") { sendKeyWithModifier("Tab", "alt") }
                ShortcutChip("▲") { sendSpecialKey("Up") }
                ShortcutChip("▼") { sendSpecialKey("Down") }
                ShortcutChip("◀") { sendSpecialKey("Left") }
                ShortcutChip("▶") { sendSpecialKey("Right") }
            }
        }
    }
}

@Composable
fun ShortcutChip(label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 1.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

// Helpers for input dispatching
private fun sendMouseMove(dx: Float, dy: Float) {
    val move = MouseMoveEvent.newBuilder().setDx(dx).setDy(dy).build()
    val msg = InputMessage.newBuilder().setMouseMove(move).build()
    ContinuityService.sendInput(msg)
}

private fun sendMouseButton(button: MouseButtonEvent.Button, state: MouseButtonEvent.State) {
    val btn = MouseButtonEvent.newBuilder().setButton(button).setState(state).build()
    val msg = InputMessage.newBuilder().setMouseButton(btn).build()
    ContinuityService.sendInput(msg)
}

private fun sendMouseScroll(dx: Float, dy: Float) {
    val scroll = MouseScrollEvent.newBuilder().setDx(dx).setDy(dy).build()
    val msg = InputMessage.newBuilder().setMouseScroll(scroll).build()
    ContinuityService.sendInput(msg)
}

private fun sendTextToDesktop(text: String) {
    val keyEvent = KeyEvent.newBuilder()
        .setText(text)
        .setState(KeyEvent.KeyState.KEY_CLICK)
        .build()
    val msg = InputMessage.newBuilder().setKeyEvent(keyEvent).build()
    ContinuityService.sendInput(msg)
}

private fun sendSpecialKey(keyName: String) {
    val keyEvent = KeyEvent.newBuilder()
        .setKeyName(keyName)
        .setState(KeyEvent.KeyState.KEY_CLICK)
        .build()
    val msg = InputMessage.newBuilder().setKeyEvent(keyEvent).build()
    ContinuityService.sendInput(msg)
}

private fun sendKeyWithModifier(keyName: String, modifier: String) {
    val keyEvent = KeyEvent.newBuilder()
        .setKeyName(keyName)
        .addModifiers(modifier)
        .setState(KeyEvent.KeyState.KEY_CLICK)
        .build()
    val msg = InputMessage.newBuilder().setKeyEvent(keyEvent).build()
    ContinuityService.sendInput(msg)
}
