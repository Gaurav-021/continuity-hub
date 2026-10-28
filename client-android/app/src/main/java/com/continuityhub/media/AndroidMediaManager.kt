package com.continuityhub.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import com.continuityhub.notifications.ContinuityNotificationListener
import com.continuityhub.proto.MediaControlCommand
import com.continuityhub.proto.MediaMessage
import com.continuityhub.proto.MediaMetadata as ProtoMediaMetadata
import com.continuityhub.proto.MediaPlaybackState
import com.continuityhub.service.ContinuityService
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

data class ActiveMediaState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val albumArt: Bitmap? = null,
    val appName: String = "",
    val canPlay: Boolean = true,
    val canPause: Boolean = true,
    val canSkipNext: Boolean = true,
    val canSkipPrevious: Boolean = true,
    val canSeek: Boolean = true
)

class AndroidMediaManager(private val context: Context) {

    private val tag = "AndroidMediaManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _mediaState = MutableStateFlow(ActiveMediaState())
    val mediaState = _mediaState.asStateFlow()

    private var activeController: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            handlePlaybackStateChanged(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            handleMetadataChanged(metadata)
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Log.d(tag, "Active media session destroyed")
            activeController = null
            _mediaState.value = ActiveMediaState()
            dispatchPlaybackState(PlaybackState.STATE_STOPPED, 0L, 1.0f)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(tag, "Active sessions changed. Found ${controllers?.size ?: 0} controllers")
        updateActiveController(controllers)
    }

    fun start() {
        try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (sessionManager == null) {
                Log.w(tag, "MediaSessionManager is not available on this device")
                return
            }

            val listenerComponent = ComponentName(context, ContinuityNotificationListener::class.java)
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            updateActiveController(controllers)
            Log.i(tag, "AndroidMediaManager initialized successfully")
        } catch (e: SecurityException) {
            Log.w(tag, "Notification listener permission not yet granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize AndroidMediaManager: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
        } catch (e: Exception) {
            Log.w(tag, "Error during AndroidMediaManager teardown: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        val newController = controllers?.firstOrNull()
        if (newController?.sessionToken == activeController?.sessionToken) {
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = newController

        if (newController != null) {
            newController.registerCallback(controllerCallback)
            Log.i(tag, "Monitoring media controller for package: ${newController.packageName}")
            handleMetadataChanged(newController.metadata)
            handlePlaybackStateChanged(newController.playbackState)
        } else {
            _mediaState.value = ActiveMediaState()
        }
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Unknown Track"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val artBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val appName = try {
            val pkg = activeController?.packageName ?: ""
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "Media Player"
        }

        _mediaState.value = _mediaState.value.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            albumArt = artBitmap,
            appName = appName
        )

        // Compress artwork to JPEG bytes (max 512px) for streaming
        val artBytes = if (artBitmap != null) {
            compressBitmap(artBitmap)
        } else {
            ByteArray(0)
        }

        // Send metadata to Linux desktop
        scope.launch {
            val protoMetadata = ProtoMediaMetadata.newBuilder()
                .setTrackId(title.hashCode().toString())
                .setTitle(title)
                .setArtist(artist)
                .setAlbum(album)
                .setDurationMs(durationMs)
                .setArtBytes(ByteString.copyFrom(artBytes))
                .setPlayerName(appName)
                .build()

            val msg = MediaMessage.newBuilder()
                .setMetadata(protoMetadata)
                .build()

            ContinuityService.broadcastMediaMessage(msg)
        }
    }

    private fun handlePlaybackStateChanged(state: PlaybackState?) {
        if (state == null) return

        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val positionMs = state.position
        val speed = state.playbackSpeed
        val actions = state.actions

        val canPlay = (actions and PlaybackState.ACTION_PLAY) != 0L || (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val canPause = (actions and PlaybackState.ACTION_PAUSE) != 0L || (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        val canSkipPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        val canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L

        _mediaState.value = _mediaState.value.copy(
            isPlaying = isPlaying,
            positionMs = positionMs,
            canPlay = canPlay,
            canPause = canPause,
            canSkipNext = canSkipNext,
            canSkipPrevious = canSkipPrev,
            canSeek = canSeek
        )

        dispatchPlaybackState(state.state, positionMs, speed)
    }

    private fun dispatchPlaybackState(rawState: Int, positionMs: Long, speed: Float) {
        val protoState = when (rawState) {
            PlaybackState.STATE_PLAYING -> MediaPlaybackState.State.STATE_PLAYING
            PlaybackState.STATE_PAUSED -> MediaPlaybackState.State.STATE_PAUSED
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> MediaPlaybackState.State.STATE_STOPPED
            else -> MediaPlaybackState.State.STATE_PAUSED
        }

        scope.launch {
            val current = _mediaState.value
            val playback = MediaPlaybackState.newBuilder()
                .setState(protoState)
                .setPositionMs(positionMs)
                .setPlaybackSpeed(speed)
                .setCanPlay(current.canPlay)
                .setCanPause(current.canPause)
                .setCanSkipNext(current.canSkipNext)
                .setCanSkipPrevious(current.canSkipPrevious)
                .setCanSeek(current.canSeek)
                .setUpdateTimeMs(System.currentTimeMillis())
                .build()

            val msg = MediaMessage.newBuilder()
                .setPlaybackState(playback)
                .build()

            ContinuityService.broadcastMediaMessage(msg)
        }
    }

    fun executeRemoteCommand(cmd: MediaControlCommand) {
        val controller = activeController ?: return
        val transport = controller.transportControls ?: return

        Log.i(tag, "Executing remote media command: ${cmd.command}")
        when (cmd.command) {
            MediaControlCommand.Command.COMMAND_PLAY -> transport.play()
            MediaControlCommand.Command.COMMAND_PAUSE -> transport.pause()
            MediaControlCommand.Command.COMMAND_PLAY_PAUSE -> {
                if (_mediaState.value.isPlaying) {
                    transport.pause()
                } else {
                    transport.play()
                }
            }
            MediaControlCommand.Command.COMMAND_NEXT -> transport.skipToNext()
            MediaControlCommand.Command.COMMAND_PREVIOUS -> transport.skipToPrevious()
            MediaControlCommand.Command.COMMAND_STOP -> transport.stop()
            MediaControlCommand.Command.COMMAND_SEEK -> transport.seekTo(cmd.seekPositionMs)
            MediaControlCommand.Command.COMMAND_SET_VOLUME -> {
                val vol = (cmd.volume * 100).toInt()
                controller.setVolumeTo(vol, 0)
            }
            else -> {}
        }
    }

    // Local UI control triggers
    fun play() = activeController?.transportControls?.play()
    fun pause() = activeController?.transportControls?.pause()
    fun togglePlayPause() {
        if (_mediaState.value.isPlaying) pause() else play()
    }
    fun skipToNext() = activeController?.transportControls?.skipToNext()
    fun skipToPrevious() = activeController?.transportControls?.skipToPrevious()
    fun seekTo(positionMs: Long) = activeController?.transportControls?.seekTo(positionMs)

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val maxDim = 512
        val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val maxOriginal = maxOf(bitmap.width, bitmap.height)
            maxDim.toFloat() / maxOriginal
        } else {
            1.0f
        }

        val scaled = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }
}
