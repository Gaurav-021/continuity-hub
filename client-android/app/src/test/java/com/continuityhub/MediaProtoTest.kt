package com.continuityhub

import com.continuityhub.proto.Channel
import com.continuityhub.proto.MediaControlCommand
import com.continuityhub.proto.MediaMessage
import com.continuityhub.proto.MediaMetadata
import com.continuityhub.proto.MediaPlaybackState
import com.continuityhub.proto.Packet
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProtoTest {

    @Test
    fun testMediaPlaybackStateSerialization() {
        val state = MediaPlaybackState.newBuilder()
            .setState(MediaPlaybackState.State.STATE_PLAYING)
            .setPositionMs(45000L)
            .setPlaybackSpeed(1.0f)
            .setCanPlay(true)
            .setCanPause(true)
            .setCanSkipNext(true)
            .setCanSkipPrevious(true)
            .setCanSeek(true)
            .setUpdateTimeMs(1700000000000L)
            .build()

        val msg = MediaMessage.newBuilder().setPlaybackState(state).build()
        val bytes = msg.toByteArray()

        val parsed = MediaMessage.parseFrom(bytes)
        assertTrue(parsed.hasPlaybackState())
        assertEquals(MediaPlaybackState.State.STATE_PLAYING, parsed.playbackState.state)
        assertEquals(45000L, parsed.playbackState.positionMs)
        assertEquals(1.0f, parsed.playbackState.playbackSpeed)
        assertTrue(parsed.playbackState.canPlay)
        assertTrue(parsed.playbackState.canPause)
        assertTrue(parsed.playbackState.canSkipNext)
        assertTrue(parsed.playbackState.canSkipPrevious)
        assertTrue(parsed.playbackState.canSeek)
    }

    @Test
    fun testMediaMetadataSerialization() {
        val artBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val metadata = MediaMetadata.newBuilder()
            .setTrackId("spotify:track:4cOdK2wGLETKBW3PvgPWqT")
            .setTitle("Starboy")
            .setArtist("The Weeknd")
            .setAlbum("Starboy")
            .setDurationMs(230000L)
            .setArtBytes(ByteString.copyFrom(artBytes))
            .setPlayerName("Spotify")
            .build()

        val msg = MediaMessage.newBuilder().setMetadata(metadata).build()
        val bytes = msg.toByteArray()

        val parsed = MediaMessage.parseFrom(bytes)
        assertTrue(parsed.hasMetadata())
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", parsed.metadata.trackId)
        assertEquals("Starboy", parsed.metadata.title)
        assertEquals("The Weeknd", parsed.metadata.artist)
        assertEquals("Starboy", parsed.metadata.album)
        assertEquals(230000L, parsed.metadata.durationMs)
        assertEquals(4, parsed.metadata.artBytes.size())
        assertEquals("Spotify", parsed.metadata.playerName)
    }

    @Test
    fun testMediaControlCommandSerialization() {
        val control = MediaControlCommand.newBuilder()
            .setCommand(MediaControlCommand.Command.COMMAND_SEEK)
            .setSeekPositionMs(120000L)
            .setVolume(0.85f)
            .setTargetPlayer("Spotify")
            .build()

        val msg = MediaMessage.newBuilder().setControl(control).build()
        val bytes = msg.toByteArray()

        val parsed = MediaMessage.parseFrom(bytes)
        assertTrue(parsed.hasControl())
        assertEquals(MediaControlCommand.Command.COMMAND_SEEK, parsed.control.command)
        assertEquals(120000L, parsed.control.seekPositionMs)
        assertEquals(0.85f, parsed.control.volume, 0.001f)
        assertEquals("Spotify", parsed.control.targetPlayer)
    }

    @Test
    fun testMediaPacketChannel() {
        val control = MediaControlCommand.newBuilder()
            .setCommand(MediaControlCommand.Command.COMMAND_PLAY_PAUSE)
            .build()

        val msg = MediaMessage.newBuilder().setControl(control).build()

        val packet = Packet.newBuilder()
            .setSessionId("android-session-1")
            .setSequenceNumber(42L)
            .setChannel(Channel.CHANNEL_MEDIA)
            .setPayload(msg.toByteString())
            .build()

        assertEquals(Channel.CHANNEL_MEDIA, packet.channel)
        val decodedMsg = MediaMessage.parseFrom(packet.payload)
        assertEquals(MediaControlCommand.Command.COMMAND_PLAY_PAUSE, decodedMsg.control.command)
    }
}
