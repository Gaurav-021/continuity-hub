package com.continuityhub

import com.continuityhub.proto.Channel
import com.continuityhub.proto.InputMessage
import com.continuityhub.proto.KeyEvent
import com.continuityhub.proto.MouseButtonEvent
import com.continuityhub.proto.MouseMoveEvent
import com.continuityhub.proto.MouseScrollEvent
import com.continuityhub.proto.Packet
import com.continuityhub.proto.PhoneNavAction
import com.continuityhub.proto.PhoneTextAction
import com.continuityhub.proto.PhoneTouchEvent
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

    @Test
    fun testPhoneTouchTapAndSwipeSerialization() {
        val tap = PhoneTouchEvent.newBuilder()
            .setType(PhoneTouchEvent.TouchType.TOUCH_TAP)
            .setNormalizedX(0.5f)
            .setNormalizedY(0.75f)
            .build()
        val msgTap = InputMessage.newBuilder().setPhoneTouch(tap).build()
        val parsedTap = InputMessage.parseFrom(msgTap.toByteArray())
        assertTrue(parsedTap.hasPhoneTouch())
        assertEquals(PhoneTouchEvent.TouchType.TOUCH_TAP, parsedTap.phoneTouch.type)
        assertEquals(0.5f, parsedTap.phoneTouch.normalizedX, 0.001f)
        assertEquals(0.75f, parsedTap.phoneTouch.normalizedY, 0.001f)

        val swipe = PhoneTouchEvent.newBuilder()
            .setType(PhoneTouchEvent.TouchType.TOUCH_SWIPE)
            .setNormalizedX(0.5f)
            .setNormalizedY(0.8f)
            .setEndX(0.5f)
            .setEndY(0.2f)
            .setDurationMs(250)
            .build()
        val msgSwipe = InputMessage.newBuilder().setPhoneTouch(swipe).build()
        val parsedSwipe = InputMessage.parseFrom(msgSwipe.toByteArray())
        assertTrue(parsedSwipe.hasPhoneTouch())
        assertEquals(PhoneTouchEvent.TouchType.TOUCH_SWIPE, parsedSwipe.phoneTouch.type)
        assertEquals(250, parsedSwipe.phoneTouch.durationMs)
    }

    @Test
    fun testPhoneNavActionSerialization() {
        val nav = PhoneNavAction.newBuilder()
            .setAction(PhoneNavAction.Action.ACTION_BACK)
            .build()
        val msg = InputMessage.newBuilder().setPhoneNav(nav).build()
        val parsed = InputMessage.parseFrom(msg.toByteArray())
        assertTrue(parsed.hasPhoneNav())
        assertEquals(PhoneNavAction.Action.ACTION_BACK, parsed.phoneNav.action)

        val navBackspace = PhoneNavAction.newBuilder()
            .setAction(PhoneNavAction.Action.ACTION_BACKSPACE)
            .build()
        val msgBackspace = InputMessage.newBuilder().setPhoneNav(navBackspace).build()
        val parsedBackspace = InputMessage.parseFrom(msgBackspace.toByteArray())
        assertEquals(PhoneNavAction.Action.ACTION_BACKSPACE, parsedBackspace.phoneNav.action)
    }

    @Test
    fun testPhoneTextActionSerialization() {
        val textAction = PhoneTextAction.newBuilder()
            .setText("Hello Continuity!")
            .setPressEnter(true)
            .build()
        val msg = InputMessage.newBuilder().setPhoneText(textAction).build()
        val parsed = InputMessage.parseFrom(msg.toByteArray())
        assertTrue(parsed.hasPhoneText())
        assertEquals("Hello Continuity!", parsed.phoneText.text)
        assertTrue(parsed.phoneText.pressEnter)
    }
}
