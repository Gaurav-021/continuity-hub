package com.continuityhub

import com.continuityhub.proto.CameraConfig
import com.continuityhub.proto.CameraControlCommand
import com.continuityhub.proto.CameraFrame
import com.continuityhub.proto.CameraMessage
import com.continuityhub.proto.CameraSnapshot
import com.continuityhub.proto.Channel
import com.continuityhub.proto.DesktopScreenFrame
import com.continuityhub.proto.Packet
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProtoTest {

    @Test
    fun testCameraConfigSerialization() {
        val config = CameraConfig.newBuilder()
            .setWidth(1920)
            .setHeight(1080)
            .setTargetFps(60)
            .setLens(CameraConfig.Lens.LENS_BACK)
            .setFormat(CameraConfig.VideoFormat.FORMAT_MJPEG)
            .setTorchEnabled(true)
            .setZoomRatio(2.5f)
            .build()

        val msg = CameraMessage.newBuilder().setConfig(config).build()
        val bytes = msg.toByteArray()

        val parsed = CameraMessage.parseFrom(bytes)
        assertTrue(parsed.hasConfig())
        assertEquals(1920, parsed.config.width)
        assertEquals(1080, parsed.config.height)
        assertEquals(60, parsed.config.targetFps)
        assertEquals(CameraConfig.Lens.LENS_BACK, parsed.config.lens)
        assertEquals(CameraConfig.VideoFormat.FORMAT_MJPEG, parsed.config.format)
        assertTrue(parsed.config.torchEnabled)
        assertEquals(2.5f, parsed.config.zoomRatio, 0.001f)
    }

    @Test
    fun testCameraControlCommandSerialization() {
        val control = CameraControlCommand.newBuilder()
            .setAction(CameraControlCommand.Action.ACTION_START)
            .setLens(CameraConfig.Lens.LENS_FRONT)
            .setTorch(false)
            .setZoomRatio(1.0f)
            .setWidth(1280)
            .setHeight(720)
            .build()

        val msg = CameraMessage.newBuilder().setControl(control).build()
        val bytes = msg.toByteArray()

        val parsed = CameraMessage.parseFrom(bytes)
        assertTrue(parsed.hasControl())
        assertEquals(CameraControlCommand.Action.ACTION_START, parsed.control.action)
        assertEquals(CameraConfig.Lens.LENS_FRONT, parsed.control.lens)
        assertEquals(1280, parsed.control.width)
        assertEquals(720, parsed.control.height)
    }

    @Test
    fun testCameraFrameSerialization() {
        val dummyData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val frame = CameraFrame.newBuilder()
            .setFrameIndex(100L)
            .setTimestampUs(5000000L)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameData(ByteString.copyFrom(dummyData))
            .setIsKeyframe(true)
            .build()

        val msg = CameraMessage.newBuilder().setFrame(frame).build()
        val bytes = msg.toByteArray()

        val parsed = CameraMessage.parseFrom(bytes)
        assertTrue(parsed.hasFrame())
        assertEquals(100L, parsed.frame.frameIndex)
        assertEquals(5000000L, parsed.frame.timestampUs)
        assertEquals(1920, parsed.frame.width)
        assertEquals(1080, parsed.frame.height)
        assertEquals(4, parsed.frame.frameData.size())
        assertTrue(parsed.frame.isKeyframe)
    }

    @Test
    fun testCameraSnapshotSerialization() {
        val dummyData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val snapshot = CameraSnapshot.newBuilder()
            .setSnapshotId("snap-999")
            .setTimestampMs(1700000000000L)
            .setImageBytes(ByteString.copyFrom(dummyData))
            .setWidth(3840)
            .setHeight(2160)
            .build()

        val msg = CameraMessage.newBuilder().setSnapshot(snapshot).build()
        val bytes = msg.toByteArray()

        val parsed = CameraMessage.parseFrom(bytes)
        assertTrue(parsed.hasSnapshot())
        assertEquals("snap-999", parsed.snapshot.snapshotId)
        assertEquals(3840, parsed.snapshot.width)
        assertEquals(2160, parsed.snapshot.height)
        assertEquals(5, parsed.snapshot.imageBytes.size())
    }

    @Test
    fun testDesktopScreenFrameSerialization() {
        val screenData = byteArrayOf(0x0A, 0x0B, 0x0C)
        val screen = DesktopScreenFrame.newBuilder()
            .setTimestampMs(1700000000100L)
            .setWidth(2560)
            .setHeight(1440)
            .setImageBytes(ByteString.copyFrom(screenData))
            .build()

        val msg = CameraMessage.newBuilder().setDesktopScreen(screen).build()
        val bytes = msg.toByteArray()

        val parsed = CameraMessage.parseFrom(bytes)
        assertTrue(parsed.hasDesktopScreen())
        assertEquals(2560, parsed.desktopScreen.width)
        assertEquals(1440, parsed.desktopScreen.height)
        assertEquals(3, parsed.desktopScreen.imageBytes.size())
    }

    @Test
    fun testCameraPacketChannelRouting() {
        val control = CameraControlCommand.newBuilder()
            .setAction(CameraControlCommand.Action.ACTION_REQUEST_DESKTOP_SCREEN)
            .build()

        val msg = CameraMessage.newBuilder().setControl(control).build()
        val packet = Packet.newBuilder()
            .setSessionId("camera-session-1")
            .setSequenceNumber(15L)
            .setChannel(Channel.CHANNEL_CAMERA)
            .setPayload(msg.toByteString())
            .build()

        assertEquals(Channel.CHANNEL_CAMERA, packet.channel)
        val decoded = CameraMessage.parseFrom(packet.payload)
        assertEquals(CameraControlCommand.Action.ACTION_REQUEST_DESKTOP_SCREEN, decoded.control.action)
    }
}
