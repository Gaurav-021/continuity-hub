package com.continuityhub

import com.continuityhub.proto.Channel
import com.continuityhub.proto.NotificationAction
import com.continuityhub.proto.NotificationActionRequest
import com.continuityhub.proto.NotificationDismiss
import com.continuityhub.proto.NotificationMessage
import com.continuityhub.proto.NotificationPayload
import com.continuityhub.proto.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProtoTest {

    @Test
    fun testNotificationPayloadSerialization() {
        val action = NotificationAction.newBuilder()
            .setActionId("reply")
            .setLabel("Reply")
            .setIsQuickReply(true)
            .build()

        val payload = NotificationPayload.newBuilder()
            .setNotificationId("notif-101")
            .setPackageName("com.whatsapp")
            .setAppName("WhatsApp")
            .setTitle("Alice")
            .setBody("Are we still meeting at 3?")
            .setTimestampMs(1700000000000L)
            .setCategory("msg")
            .setHasQuickReply(true)
            .addActions(action)
            .build()

        val msg = NotificationMessage.newBuilder().setPost(payload).build()
        val bytes = msg.toByteArray()

        val parsed = NotificationMessage.parseFrom(bytes)
        assertTrue(parsed.hasPost())
        assertEquals("notif-101", parsed.post.notificationId)
        assertEquals("com.whatsapp", parsed.post.packageName)
        assertEquals("WhatsApp", parsed.post.appName)
        assertEquals("Alice", parsed.post.title)
        assertEquals("Are we still meeting at 3?", parsed.post.body)
        assertTrue(parsed.post.hasQuickReply)
        assertEquals(1, parsed.post.actionsCount)
        assertEquals("Reply", parsed.post.getActions(0).label)
    }

    @Test
    fun testNotificationActionRequestSerialization() {
        val req = NotificationActionRequest.newBuilder()
            .setNotificationId("notif-101")
            .setActionId("reply")
            .setActionType(NotificationActionRequest.ActionType.ACTION_REPLY)
            .setReplyText("Yes, see you there!")
            .setSourceDeviceId("linux-pc-1")
            .build()

        val msg = NotificationMessage.newBuilder().setAction(req).build()
        val bytes = msg.toByteArray()

        val parsed = NotificationMessage.parseFrom(bytes)
        assertTrue(parsed.hasAction())
        assertEquals("notif-101", parsed.action.notificationId)
        assertEquals(NotificationActionRequest.ActionType.ACTION_REPLY, parsed.action.actionType)
        assertEquals("Yes, see you there!", parsed.action.replyText)
    }

    @Test
    fun testNotificationDismissSerialization() {
        val dismiss = NotificationDismiss.newBuilder()
            .setNotificationId("notif-101")
            .build()

        val msg = NotificationMessage.newBuilder().setDismiss(dismiss).build()
        val bytes = msg.toByteArray()

        val parsed = NotificationMessage.parseFrom(bytes)
        assertTrue(parsed.hasDismiss())
        assertEquals("notif-101", parsed.dismiss.notificationId)
    }

    @Test
    fun testNotificationPacketFraming() {
        val payload = NotificationPayload.newBuilder()
            .setNotificationId("notif-202")
            .setPackageName("org.telegram.messenger")
            .setAppName("Telegram")
            .setTitle("Bob")
            .setBody("Code review approved")
            .setTimestampMs(1700000005000L)
            .build()

        val notifMsg = NotificationMessage.newBuilder().setPost(payload).build()
        val packet = Packet.newBuilder()
            .setSessionId("dev-phone-1")
            .setSequenceNumber(42L)
            .setChannel(Channel.CHANNEL_NOTIFICATIONS)
            .setPayload(notifMsg.toByteString())
            .build()

        val packetBytes = packet.toByteArray()
        val parsedPacket = Packet.parseFrom(packetBytes)

        assertEquals(Channel.CHANNEL_NOTIFICATIONS, parsedPacket.channel)
        assertEquals(42L, parsedPacket.sequenceNumber)
        val parsedNotifMsg = NotificationMessage.parseFrom(parsedPacket.payload)
        assertEquals("Bob", parsedNotifMsg.post.title)
    }
}
