package com.continuityhub

import com.continuityhub.proto.CallActionRequest
import com.continuityhub.proto.CallEvent
import com.continuityhub.proto.Channel
import com.continuityhub.proto.DeviceTelemetry
import com.continuityhub.proto.Packet
import com.continuityhub.proto.SendSmsRequest
import com.continuityhub.proto.SendSmsResponse
import com.continuityhub.proto.SmsMessage
import com.continuityhub.proto.TelephonyMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyProtoTest {

    @Test
    fun testCallEventSerialization() {
        val call = CallEvent.newBuilder()
            .setCallId("call-abc-123")
            .setPhoneNumber("+15551234567")
            .setContactName("Sarah Connor")
            .setState(CallEvent.CallState.CALL_STATE_RINGING)
            .setTimestampMs(1700000000000L)
            .build()

        val msg = TelephonyMessage.newBuilder().setCallEvent(call).build()
        val bytes = msg.toByteArray()

        val parsed = TelephonyMessage.parseFrom(bytes)
        assertTrue(parsed.hasCallEvent())
        assertEquals("call-abc-123", parsed.callEvent.callId)
        assertEquals("+15551234567", parsed.callEvent.phoneNumber)
        assertEquals("Sarah Connor", parsed.callEvent.contactName)
        assertEquals(CallEvent.CallState.CALL_STATE_RINGING, parsed.callEvent.state)
    }

    @Test
    fun testCallActionSerialization() {
        val action = CallActionRequest.newBuilder()
            .setCallId("call-abc-123")
            .setAction(CallActionRequest.Action.ACTION_REJECT_WITH_SMS)
            .setSmsReply("In a meeting, call back soon.")
            .setPhoneNumber("+15551234567")
            .build()

        val msg = TelephonyMessage.newBuilder().setCallAction(action).build()
        val bytes = msg.toByteArray()

        val parsed = TelephonyMessage.parseFrom(bytes)
        assertTrue(parsed.hasCallAction())
        assertEquals(CallActionRequest.Action.ACTION_REJECT_WITH_SMS, parsed.callAction.action)
        assertEquals("In a meeting, call back soon.", parsed.callAction.smsReply)
    }

    @Test
    fun testSmsMessageSerialization() {
        val sms = SmsMessage.newBuilder()
            .setMessageId("sms-789")
            .setPhoneNumber("+15559876543")
            .setContactName("John Doe")
            .setBody("Hello from Android SMS!")
            .setTimestampMs(1700000005000L)
            .setIsIncoming(true)
            .build()

        val msg = TelephonyMessage.newBuilder().setSmsMessage(sms).build()
        val bytes = msg.toByteArray()

        val parsed = TelephonyMessage.parseFrom(bytes)
        assertTrue(parsed.hasSmsMessage())
        assertEquals("sms-789", parsed.smsMessage.messageId)
        assertEquals("Hello from Android SMS!", parsed.smsMessage.body)
        assertTrue(parsed.smsMessage.isIncoming)
    }

    @Test
    fun testSendSmsRequestAndResponse() {
        val req = SendSmsRequest.newBuilder()
            .setRequestId("req-101")
            .setPhoneNumber("+15551112222")
            .setBody("Test message from Linux terminal")
            .build()

        val msgReq = TelephonyMessage.newBuilder().setSendSms(req).build()
        val parsedReq = TelephonyMessage.parseFrom(msgReq.toByteArray())
        assertEquals("Test message from Linux terminal", parsedReq.sendSms.body)

        val resp = SendSmsResponse.newBuilder()
            .setRequestId("req-101")
            .setSuccess(true)
            .build()

        val msgResp = TelephonyMessage.newBuilder().setSendSmsResp(resp).build()
        val parsedResp = TelephonyMessage.parseFrom(msgResp.toByteArray())
        assertTrue(parsedResp.sendSmsResp.success)
    }

    @Test
    fun testDeviceTelemetrySerialization() {
        val telemetry = DeviceTelemetry.newBuilder()
            .setBatteryPercentage(87)
            .setIsCharging(true)
            .setChargingType(DeviceTelemetry.ChargingType.CHARGING_FAST)
            .setBatteryTemperatureC(31.5f)
            .setNetworkType(DeviceTelemetry.NetworkType.NETWORK_CELLULAR_5G)
            .setCellularCarrier("T-Mobile")
            .setWifiSsid("HomeNetwork-5G")
            .setSignalBars(4)
            .setTimestampMs(System.currentTimeMillis())
            .build()

        val packet = Packet.newBuilder()
            .setSessionId("device-abc")
            .setSequenceNumber(1)
            .setChannel(Channel.CHANNEL_TELEPHONY)
            .setPayload(TelephonyMessage.newBuilder().setTelemetry(telemetry).build().toByteString())
            .build()

        val parsedPacket = Packet.parseFrom(packet.toByteArray())
        assertEquals(Channel.CHANNEL_TELEPHONY, parsedPacket.channel)

        val parsedTel = TelephonyMessage.parseFrom(parsedPacket.payload)
        assertTrue(parsedTel.hasTelemetry())
        assertEquals(87, parsedTel.telemetry.batteryPercentage)
        assertTrue(parsedTel.telemetry.isCharging)
        assertEquals(DeviceTelemetry.ChargingType.CHARGING_FAST, parsedTel.telemetry.chargingType)
        assertEquals("T-Mobile", parsedTel.telemetry.cellularCarrier)
        assertEquals(4, parsedTel.telemetry.signalBars)
    }
}
