package com.continuityhub

import com.continuityhub.proto.Channel
import com.continuityhub.proto.InputMessage
import com.continuityhub.proto.KeyEvent
import com.continuityhub.proto.MouseButtonEvent
import com.continuityhub.proto.MouseMoveEvent
import com.continuityhub.proto.MouseScrollEvent
import com.continuityhub.proto.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputProtoTest {

    @Test
    fun testMouseMoveSerialization() {
        val move = MouseMoveEvent.newBuilder()
            .setDx(12.5f)
            .setDy(-4.25f)
            .build()
        val msg = InputMessage.newBuilder()
            .setMouseMove(move)
            .build()

        val bytes = msg.toByteArray()
        val parsed = InputMessage.parseFrom(bytes)

        assertTrue(parsed.hasMouseMove())
        assertEquals(12.5f, parsed.mouseMove.dx, 0.001f)
        assertEquals(-4.25f, parsed.mouseMove.dy, 0.001f)
    }

    @Test
    fun testMouseButtonSerialization() {
        val btn = MouseButtonEvent.newBuilder()
            .setButton(MouseButtonEvent.Button.BUTTON_RIGHT)
            .setState(MouseButtonEvent.State.STATE_CLICK)
            .build()
        val msg = InputMessage.newBuilder()
            .setMouseButton(btn)
            .build()

        val bytes = msg.toByteArray()
        val parsed = InputMessage.parseFrom(bytes)

        assertTrue(parsed.hasMouseButton())
        assertEquals(MouseButtonEvent.Button.BUTTON_RIGHT, parsed.mouseButton.button)
        assertEquals(MouseButtonEvent.State.STATE_CLICK, parsed.mouseButton.state)
    }

    @Test
    fun testMouseScrollSerialization() {
        val scroll = MouseScrollEvent.newBuilder()
            .setDx(0f)
            .setDy(15.5f)
            .build()
        val msg = InputMessage.newBuilder()
            .setMouseScroll(scroll)
            .build()

        val bytes = msg.toByteArray()
        val parsed = InputMessage.parseFrom(bytes)

        assertTrue(parsed.hasMouseScroll())
        assertEquals(15.5f, parsed.mouseScroll.dy, 0.001f)
    }

    @Test
    fun testKeyMessageInPacketEnvelope() {
        val keyEvent = KeyEvent.newBuilder()
            .setKeyName("Escape")
            .setText("")
            .setState(KeyEvent.KeyState.KEY_CLICK)
            .addModifiers("ctrl")
            .build()

        val inputMsg = InputMessage.newBuilder()
            .setKeyEvent(keyEvent)
            .build()

        val packet = Packet.newBuilder()
            .setSessionId("device-123")
            .setSequenceNumber(100)
            .setChannel(Channel.CHANNEL_INPUT)
            .setPayload(inputMsg.toByteString())
            .build()

        val packetBytes = packet.toByteArray()
        val parsedPacket = Packet.parseFrom(packetBytes)

        assertEquals(Channel.CHANNEL_INPUT, parsedPacket.channel)
        val parsedInput = InputMessage.parseFrom(parsedPacket.payload)
        assertTrue(parsedInput.hasKeyEvent())
        assertEquals("Escape", parsedInput.keyEvent.keyName)
        assertEquals(listOf("ctrl"), parsedInput.keyEvent.modifiersList)
    }
}
