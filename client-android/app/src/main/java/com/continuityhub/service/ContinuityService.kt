package com.continuityhub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.continuityhub.MainActivity
import com.continuityhub.camera.AndroidCameraManager
import com.continuityhub.camera.CameraUiState
import com.continuityhub.clipboard.AndroidClipboardManager
import com.continuityhub.crypto.CryptoManager
import com.continuityhub.discovery.NsdDiscoveryManager
import com.continuityhub.media.ActiveMediaState
import com.continuityhub.media.AndroidMediaManager
import com.continuityhub.model.ClipboardLog
import com.continuityhub.model.DeviceConfig
import com.continuityhub.model.DiscoveredPeer
import com.continuityhub.model.FileTransferItem
import com.continuityhub.model.MirroredNotificationItem
import com.continuityhub.notifications.ContinuityNotificationListener
import com.continuityhub.proto.CameraConfig
import com.continuityhub.proto.CameraControlCommand
import com.continuityhub.proto.CameraMessage
import com.continuityhub.proto.Channel
import com.continuityhub.proto.ClipboardPayload
import com.continuityhub.proto.FileTransferMessage
import com.continuityhub.proto.HandshakeRequest
import com.continuityhub.proto.HandshakeResponse
import com.continuityhub.proto.InputMessage
import com.continuityhub.proto.MediaControlCommand
import com.continuityhub.proto.MediaMessage
import com.continuityhub.proto.NotificationDismiss
import com.continuityhub.proto.NotificationMessage
import com.continuityhub.proto.NotificationPayload
import com.continuityhub.proto.Packet
import com.continuityhub.proto.CallActionRequest
import com.continuityhub.proto.CallEvent
import com.continuityhub.proto.DeviceTelemetry
import com.continuityhub.proto.SendSmsRequest
import com.continuityhub.proto.SendSmsResponse
import com.continuityhub.proto.SmsMessage
import com.continuityhub.proto.TelephonyMessage
import com.continuityhub.telephony.DeviceTelemetryMonitor
import com.continuityhub.telephony.TelephonyCallManager
import com.continuityhub.transfer.AndroidFileTransferManager
import com.continuityhub.transport.PacketFraming
import com.continuityhub.transport.TlsManager
import com.continuityhub.transport.TofuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ContinuityService : Service() {

    private val tag = "ContinuityService"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private lateinit var cryptoManager: CryptoManager
    private lateinit var tofuManager: TofuManager
    private lateinit var tlsManager: TlsManager
    private lateinit var discoveryManager: NsdDiscoveryManager
    private lateinit var clipboardManager: AndroidClipboardManager
    private lateinit var fileTransferManager: AndroidFileTransferManager
    private lateinit var telephonyCallManager: TelephonyCallManager
    private lateinit var telemetryMonitor: DeviceTelemetryMonitor
    private lateinit var mediaManager: AndroidMediaManager
    private lateinit var cameraManager: AndroidCameraManager

    private val activeSessions = ConcurrentHashMap<String, PeerSession>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val sequenceNumber = AtomicLong(0)

    private var serverJob: Job? = null

    class PeerSession(
        val deviceId: String,
        val deviceName: String,
        val socket: Socket,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val sessionScope: CoroutineScope
    ) {
        private val sendChannel = kotlinx.coroutines.channels.Channel<Packet>(128)

        init {
            sessionScope.launch(Dispatchers.IO) {
                for (packet in sendChannel) {
                    try {
                        PacketFraming.writePacket(outputStream, packet)
                    } catch (e: Exception) {
                        Log.w("PeerSession", "Error writing packet to $deviceName: ${e.message}")
                        break
                    }
                }
            }
        }

        fun sendPacket(packet: Packet) {
            val result = sendChannel.trySend(packet)
            if (result.isFailure) {
                // For video frames, drop stale frame immediately under network backpressure
                if (packet.channel == Channel.CHANNEL_CAMERA) {
                    return
                }
                sessionScope.launch(Dispatchers.IO) {
                    try {
                        sendChannel.send(packet)
                    } catch (_: Exception) {}
                }
            }
        }

        fun close() {
            sendChannel.close()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "continuity_service_channel"
        private const val ALERT_CHANNEL_ID = "continuity_alerts_channel"

        var instance: ContinuityService? = null
            private set

        val deviceConfigState = MutableStateFlow<DeviceConfig?>(null)
        val fingerprintState = MutableStateFlow("")
        val discoveredPeersState = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
        val connectedPeersState = MutableStateFlow<List<String>>(emptyList())
        val clipboardLogsState = MutableStateFlow<List<ClipboardLog>>(emptyList())
        val fileTransfersState = MutableStateFlow<List<FileTransferItem>>(emptyList())
        val notificationsState = MutableStateFlow<List<MirroredNotificationItem>>(emptyList())
        val telemetryState = MutableStateFlow<DeviceTelemetry?>(null)
        val mediaState = MutableStateFlow(ActiveMediaState())
        val cameraUiState = MutableStateFlow(CameraUiState())
        val sasCodeState = MutableStateFlow("")
        val clipboardSyncEnabledState = MutableStateFlow(true)
        val clipboardOutboundEnabledState = MutableStateFlow(true)
        val clipboardInboundEnabledState = MutableStateFlow(true)
        val notificationSyncEnabledState = MutableStateFlow(true)
        val notificationOutboundEnabledState = MutableStateFlow(true)
        val notificationActionsEnabledState = MutableStateFlow(true)
        val notificationInboundEnabledState = MutableStateFlow(true)

        fun setClipboardSyncEnabled(enabled: Boolean) {
            clipboardSyncEnabledState.value = enabled
            instance?.clipboardManager?.isSyncEnabled = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("clipboard_sync_enabled", enabled)?.apply()
        }

        fun setClipboardOutboundEnabled(enabled: Boolean) {
            clipboardOutboundEnabledState.value = enabled
            instance?.clipboardManager?.isOutboundEnabled = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("clipboard_outbound_enabled", enabled)?.apply()
        }

        fun setClipboardInboundEnabled(enabled: Boolean) {
            clipboardInboundEnabledState.value = enabled
            instance?.clipboardManager?.isInboundEnabled = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("clipboard_inbound_enabled", enabled)?.apply()
        }

        fun setNotificationSyncEnabled(enabled: Boolean) {
            notificationSyncEnabledState.value = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("notification_sync_enabled", enabled)?.apply()
        }

        fun setNotificationOutboundEnabled(enabled: Boolean) {
            notificationOutboundEnabledState.value = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("notification_outbound_enabled", enabled)?.apply()
        }

        fun setNotificationActionsEnabled(enabled: Boolean) {
            notificationActionsEnabledState.value = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("notification_actions_enabled", enabled)?.apply()
        }

        fun setNotificationInboundEnabled(enabled: Boolean) {
            notificationInboundEnabledState.value = enabled
            instance?.getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("notification_inbound_enabled", enabled)?.apply()
        }

        fun start(context: Context) {
            val intent = Intent(context, ContinuityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendFile(uri: Uri) {
            instance?.sendFileInternal(uri)
        }

        fun broadcastNotification(payload: NotificationPayload) {
            instance?.broadcastNotificationInternal(payload)
        }

        fun broadcastNotificationDismiss(dismiss: NotificationDismiss) {
            instance?.broadcastNotificationDismissInternal(dismiss)
        }

        fun sendTestAlertToDesktop(title: String, message: String) {
            instance?.sendTestAlertInternal(title, message)
        }

        fun sendInput(inputMsg: InputMessage) {
            instance?.sendInputInternal(inputMsg)
        }

        fun broadcastCallEvent(call: CallEvent) {
            instance?.broadcastCallEventInternal(call)
        }

        fun broadcastSmsMessage(sms: SmsMessage) {
            instance?.broadcastSmsMessageInternal(sms)
        }

        fun broadcastTelemetry(telemetry: DeviceTelemetry) {
            telemetryState.value = telemetry
            instance?.broadcastTelemetryInternal(telemetry)
        }

        fun broadcastMediaMessage(msg: MediaMessage) {
            instance?.broadcastMediaMessageInternal(msg)
        }

        fun sendDesktopMediaCommand(command: MediaControlCommand.Command, seekPositionMs: Long = 0L) {
            instance?.sendDesktopMediaCommandInternal(command, seekPositionMs)
        }

        fun getMediaManager(): AndroidMediaManager? = instance?.mediaManager

        fun broadcastCameraMessage(msg: CameraMessage) {
            instance?.broadcastCameraMessageInternal(msg)
        }

        fun getCameraManager(): AndroidCameraManager? = instance?.cameraManager

        fun syncLocalClipboard() {
            instance?.clipboardManager?.checkClipboard()
        }

        fun connectDirect(host: String, port: Int = 52800) {
            instance?.connectDirectInternal(host, port)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(tag, "ContinuityService starting (Phase 3)...")

        startInForeground()

        val prefs = getSharedPreferences("continuity_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        val deviceName = prefs.getString("device_name", null) ?: Build.MODEL.also {
            prefs.edit().putString("device_name", it).apply()
        }
        val port = 52801

        val config = DeviceConfig(deviceId, deviceName, port)
        deviceConfigState.value = config

        cryptoManager = CryptoManager(this)
        fingerprintState.value = cryptoManager.fingerprint

        tofuManager = TofuManager(this)
        tlsManager = TlsManager(cryptoManager, tofuManager)

        val clipboardSync = prefs.getBoolean("clipboard_sync_enabled", true)
        val clipboardOut = prefs.getBoolean("clipboard_outbound_enabled", true)
        val clipboardIn = prefs.getBoolean("clipboard_inbound_enabled", true)
        clipboardSyncEnabledState.value = clipboardSync
        clipboardOutboundEnabledState.value = clipboardOut
        clipboardInboundEnabledState.value = clipboardIn

        val notifSync = prefs.getBoolean("notification_sync_enabled", true)
        val notifOut = prefs.getBoolean("notification_outbound_enabled", true)
        val notifActions = prefs.getBoolean("notification_actions_enabled", true)
        val notifIn = prefs.getBoolean("notification_inbound_enabled", true)
        notificationSyncEnabledState.value = notifSync
        notificationOutboundEnabledState.value = notifOut
        notificationActionsEnabledState.value = notifActions
        notificationInboundEnabledState.value = notifIn

        clipboardManager = AndroidClipboardManager(this, deviceId) { payload ->
            broadcastClipboard(payload)
            addLog("Android", "Local Copy: ${payload.data.toStringUtf8().take(30)}", payload.contentHash)
        }
        clipboardManager.isSyncEnabled = clipboardSync
        clipboardManager.isOutboundEnabled = clipboardOut
        clipboardManager.isInboundEnabled = clipboardIn

        fileTransferManager = AndroidFileTransferManager(this, deviceId, scope)
        scope.launch {
            fileTransferManager.transfersState.collect { transfers ->
                fileTransfersState.value = transfers
            }
        }

        discoveryManager = NsdDiscoveryManager(this, deviceId)
        telephonyCallManager = TelephonyCallManager(this)
        telemetryMonitor = DeviceTelemetryMonitor(this, scope)

        mediaManager = AndroidMediaManager(this)
        mediaManager.start()
        scope.launch {
            mediaManager.mediaState.collect { state ->
                mediaState.value = state
            }
        }

        cameraManager = AndroidCameraManager(this)
        scope.launch {
            cameraManager.uiState.collect { state ->
                cameraUiState.value = state
            }
        }

        startTlsServer(port, config)
        startDiscoveryAndAdvertising(config)
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Continuity Hub Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background TLS sync and discovery"
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Continuity Desktop Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts mirrored from Desktop"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(alertChannel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Continuity Hub Active")
            .setContentText("Syncing clipboard, file drop & notification mirroring...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTlsServer(port: Int, config: DeviceConfig) {
        serverJob = scope.launch {
            try {
                val serverSocket = tlsManager.createServerSocket(port)
                Log.i(tag, "TLS TCP Server listening on port $port")

                while (isActive) {
                    val clientSocket = serverSocket.accept()
                    clientSocket.tcpNoDelay = true
                    Log.i(tag, "Accepted TLS connection from ${clientSocket.remoteSocketAddress}")

                    launch {
                        handleIncomingConnection(clientSocket, config)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(tag, "Error in TLS server loop", e)
                }
            }
        }
    }

    private fun startDiscoveryAndAdvertising(config: DeviceConfig) {
        discoveryManager.registerService(config.port, config.deviceName)
        discoveryManager.startDiscovery { peer ->
            val current = discoveredPeersState.value.toMutableList()
            if (current.none { it.deviceId == peer.deviceId }) {
                current.add(peer)
                discoveredPeersState.value = current
            }
            evaluateOutboundConnection(peer, config)
        }
    }

    private fun evaluateOutboundConnection(peer: DiscoveredPeer, config: DeviceConfig) {
        if (activeSessions.containsKey(peer.deviceId)) return

        if (!connectingPeers.add(peer.deviceId)) return

        scope.launch {
            try {
                Log.i(tag, "Initiating outbound mTLS connection to ${peer.deviceName} at ${peer.host}:${peer.port}")
                val socket = tlsManager.connect(peer.host, peer.port)
                handleEstablishedConnection(socket, config, isInitiator = true, suggestedPeer = peer)
            } catch (e: Exception) {
                Log.w(tag, "Failed connecting to peer ${peer.deviceName}: ${e.message}")
            } finally {
                connectingPeers.remove(peer.deviceId)
            }
        }
    }

    private fun connectDirectInternal(host: String, port: Int = 52800) {
        val config = deviceConfigState.value ?: return
        scope.launch {
            try {
                Log.i(tag, "Initiating direct manual mTLS connection to $host:$port")
                val socket = tlsManager.connect(host, port)
                val peer = DiscoveredPeer(
                    deviceId = "manual-$host",
                    deviceName = "Desktop ($host)",
                    host = host,
                    port = port
                )
                handleEstablishedConnection(socket, config, isInitiator = true, suggestedPeer = peer)
            } catch (e: Exception) {
                Log.e(tag, "Direct connection to $host:$port failed: ${e.message}")
            }
        }
    }

    private fun handleIncomingConnection(socket: Socket, config: DeviceConfig) {
        handleEstablishedConnection(socket, config, isInitiator = false, suggestedPeer = null)
    }

    private fun handleEstablishedConnection(
        socket: Socket,
        config: DeviceConfig,
        isInitiator: Boolean,
        suggestedPeer: DiscoveredPeer?
    ) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val peerDeviceId: String
        val peerDeviceName: String
        val peerFingerprint: String

        if (isInitiator) {
            val req = HandshakeRequest.newBuilder()
                .setDeviceName(config.deviceName)
                .setDeviceId(config.deviceId)
                .setCertFingerprint(cryptoManager.fingerprint)
                .setProtocolVersion(1)
                .build()

            val packet = Packet.newBuilder()
                .setSessionId(config.deviceId)
                .setSequenceNumber(sequenceNumber.incrementAndGet())
                .setChannel(Channel.CHANNEL_HANDSHAKE)
                .setPayload(req.toByteString())
                .build()

            PacketFraming.writePacket(output, packet)

            val respPacket = PacketFraming.readPacket(input)
            val resp = HandshakeResponse.parseFrom(respPacket.payload)
            if (resp.status != HandshakeResponse.Status.STATUS_ACCEPTED) {
                Log.w(tag, "Peer rejected handshake: ${resp.message}")
                socket.close()
                return
            }
            peerDeviceId = resp.deviceId
            peerDeviceName = resp.deviceName
            peerFingerprint = resp.certFingerprint
        } else {
            val reqPacket = PacketFraming.readPacket(input)
            val req = HandshakeRequest.parseFrom(reqPacket.payload)
            peerDeviceId = req.deviceId
            peerDeviceName = req.deviceName
            peerFingerprint = req.certFingerprint

            val resp = HandshakeResponse.newBuilder()
                .setStatus(HandshakeResponse.Status.STATUS_ACCEPTED)
                .setDeviceName(config.deviceName)
                .setDeviceId(config.deviceId)
                .setCertFingerprint(cryptoManager.fingerprint)
                .setMessage("OK")
                .build()

            val respPacket = Packet.newBuilder()
                .setSessionId(config.deviceId)
                .setSequenceNumber(sequenceNumber.incrementAndGet())
                .setChannel(Channel.CHANNEL_HANDSHAKE)
                .setPayload(resp.toByteString())
                .build()

            PacketFraming.writePacket(output, respPacket)
        }

        val sslSocket = socket as? javax.net.ssl.SSLSocket
        val peerCert = sslSocket?.session?.peerCertificates?.firstOrNull()
        val tlsFingerprint = if (peerCert != null) CryptoManager.calculateFingerprint(peerCert.encoded) else ""
        if (tlsFingerprint.isNotEmpty() && !tlsFingerprint.equals(peerFingerprint, ignoreCase = true)) {
            Log.e(tag, "Handshake fingerprint mismatch: TLS cert $tlsFingerprint vs claimed $peerFingerprint")
            socket.close()
            return
        }

        tofuManager.updateDeviceInfo(peerFingerprint, peerDeviceId, peerDeviceName)
        val sasCode = CryptoManager.calculateSasCode(cryptoManager.fingerprint, peerFingerprint)
        sasCodeState.value = sasCode
        Log.i(tag, "mTLS Handshake complete with peer '$peerDeviceName' ($peerDeviceId). Security SAS Code: $sasCode")

        val session = PeerSession(peerDeviceId, peerDeviceName, socket, input, output, scope)
        activeSessions[peerDeviceId] = session
        updateConnectedPeers()

        // Read loop
        try {
            while (scope.isActive) {
                val packet = PacketFraming.readPacket(input)
                when (packet.channel) {
                    Channel.CHANNEL_CLIPBOARD -> {
                        val payload = ClipboardPayload.parseFrom(packet.payload)
                        clipboardManager.setRemoteClipboard(payload)
                        addLog(
                            peerDeviceName,
                            "Remote Sync: ${payload.data.toStringUtf8().take(30)}",
                            payload.contentHash
                        )
                    }
                    Channel.CHANNEL_FILE_TRANSFER -> {
                        val msg = FileTransferMessage.parseFrom(packet.payload)
                        val reply = fileTransferManager.handleIncomingMessage(msg, peerDeviceId, peerDeviceName)
                        if (reply != null) {
                            session.sendPacket(reply)
                        }
                    }
                    Channel.CHANNEL_NOTIFICATIONS -> {
                        val msg = NotificationMessage.parseFrom(packet.payload)
                        when (msg.msgCase) {
                            NotificationMessage.MsgCase.POST -> {
                                if (!notificationSyncEnabledState.value || !notificationInboundEnabledState.value) {
                                    Log.d(tag, "Desktop notification ignored: Inbound notification sync is disabled")
                                } else {
                                    val post = msg.post
                                    showDesktopAlertNotification(post.title, post.body, post.appName)
                                    addMirroredNotification(
                                        MirroredNotificationItem(
                                            notificationId = post.notificationId,
                                            packageName = post.packageName,
                                            appName = post.appName,
                                            title = post.title,
                                            body = post.body,
                                            timestamp = post.timestampMs,
                                            hasQuickReply = false,
                                            isIncomingFromDesktop = true
                                        )
                                    )
                                }
                            }
                            NotificationMessage.MsgCase.ACTION -> {
                                if (!notificationSyncEnabledState.value || !notificationActionsEnabledState.value) {
                                    Log.d(tag, "Remote action ignored: Notification actions are disabled")
                                } else {
                                    ContinuityNotificationListener.executeRemoteAction(msg.action)
                                }
                            }
                            else -> {}
                        }
                    }
                    Channel.CHANNEL_HEARTBEAT -> {
                        Log.d(tag, "Heartbeat from $peerDeviceName")
                    }
                    Channel.CHANNEL_INPUT -> {
                        Log.d(tag, "Received input from $peerDeviceName")
                    }
                    Channel.CHANNEL_TELEPHONY -> {
                        val telephonyMsg = TelephonyMessage.parseFrom(packet.payload)
                        when (telephonyMsg.msgCase) {
                            TelephonyMessage.MsgCase.CALL_ACTION -> {
                                telephonyCallManager.executeCallAction(telephonyMsg.callAction)
                            }
                            TelephonyMessage.MsgCase.SEND_SMS -> {
                                handleSendSmsRequest(telephonyMsg.sendSms)
                            }
                            else -> {}
                        }
                    }
                    Channel.CHANNEL_MEDIA -> {
                        val mediaMsg = MediaMessage.parseFrom(packet.payload)
                        when (mediaMsg.msgCase) {
                            MediaMessage.MsgCase.CONTROL -> {
                                mediaManager.executeRemoteCommand(mediaMsg.control)
                            }
                            else -> {}
                        }
                    }
                    Channel.CHANNEL_CAMERA -> {
                        val cameraMsg = CameraMessage.parseFrom(packet.payload)
                        when (cameraMsg.msgCase) {
                            CameraMessage.MsgCase.CONTROL -> {
                                cameraManager.executeRemoteControl(cameraMsg.control)
                            }
                            CameraMessage.MsgCase.DESKTOP_SCREEN -> {
                                cameraManager.handleDesktopScreenFrame(cameraMsg.desktopScreen)
                            }
                            else -> {}
                        }
                    }
                    else -> {
                        Log.d(tag, "Unhandled channel ${packet.channel} from $peerDeviceName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(tag, "Peer connection ended for $peerDeviceName: ${e.message}")
        } finally {
            activeSessions.remove(peerDeviceId)
            if (activeSessions.isEmpty()) {
                sasCodeState.value = ""
            }
            updateConnectedPeers()
            session.close()
        }
    }

    private fun broadcastClipboard(payload: ClipboardPayload) {
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_CLIPBOARD)
            .setPayload(payload.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            session.sendPacket(packet)
            Log.d(tag, "Streamed clipboard payload to ${session.deviceName}")
        }
    }

    private fun broadcastNotificationInternal(payload: NotificationPayload) {
        if (!notificationSyncEnabledState.value || !notificationOutboundEnabledState.value) {
            Log.d(tag, "Notification mirroring ignored: Notification outbound sync is disabled")
            return
        }

        addMirroredNotification(
            MirroredNotificationItem(
                notificationId = payload.notificationId,
                packageName = payload.packageName,
                appName = payload.appName,
                title = payload.title,
                body = payload.body,
                timestamp = payload.timestampMs,
                hasQuickReply = payload.hasQuickReply,
                isIncomingFromDesktop = false
            )
        )

        val msg = NotificationMessage.newBuilder().setPost(payload).build()
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_NOTIFICATIONS)
            .setPayload(msg.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            scope.launch {
                try {
                    session.sendPacket(packet)
                    Log.d(tag, "Mirrored notification [${payload.appName}] to ${session.deviceName}")
                } catch (e: Exception) {
                    Log.w(tag, "Failed mirroring notification to ${session.deviceName}: ${e.message}")
                }
            }
        }
    }

    private fun broadcastNotificationDismissInternal(dismiss: NotificationDismiss) {
        if (!notificationSyncEnabledState.value || !notificationOutboundEnabledState.value) {
            return
        }

        val msg = NotificationMessage.newBuilder().setDismiss(dismiss).build()
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_NOTIFICATIONS)
            .setPayload(msg.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            scope.launch {
                try {
                    session.sendPacket(packet)
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendTestAlertInternal(title: String, message: String) {
        val payload = NotificationPayload.newBuilder()
            .setNotificationId(UUID.randomUUID().toString())
            .setPackageName(packageName)
            .setAppName("Continuity Android")
            .setTitle(title)
            .setBody(message)
            .setTimestampMs(System.currentTimeMillis())
            .setCategory("test_alert")
            .setHasQuickReply(false)
            .build()

        broadcastNotificationInternal(payload)
    }

    fun sendFileInternal(uri: Uri) {
        val session = activeSessions.values.firstOrNull()
        if (session == null) {
            Log.w(tag, "No active peer session to send file to")
            return
        }

        scope.launch {
            val result = fileTransferManager.sendFile(uri, session.deviceName) { packet ->
                session.sendPacket(packet)
            }
            if (result.isSuccess) {
                Log.i(tag, "File sent successfully via Continuity")
            } else {
                Log.e(tag, "Failed sending file", result.exceptionOrNull())
            }
        }
    }

    private fun showDesktopAlertNotification(title: String, body: String, appName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("[$appName] $title")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager?.notify(title.hashCode(), notification)
    }

    private fun sendInputInternal(inputMsg: InputMessage) {
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_INPUT)
            .setPayload(inputMsg.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            session.sendPacket(packet)
        }
    }

    private fun broadcastCallEventInternal(call: CallEvent) {
        val msg = TelephonyMessage.newBuilder().setCallEvent(call).build()
        sendTelephonyMessage(msg)
    }

    private fun broadcastSmsMessageInternal(sms: SmsMessage) {
        val msg = TelephonyMessage.newBuilder().setSmsMessage(sms).build()
        sendTelephonyMessage(msg)
    }

    private fun broadcastTelemetryInternal(telemetry: DeviceTelemetry) {
        val msg = TelephonyMessage.newBuilder().setTelemetry(telemetry).build()
        sendTelephonyMessage(msg)
    }

    private fun handleSendSmsRequest(req: SendSmsRequest) {
        try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.telephony.SmsManager::class.java)
            } else {
                android.telephony.SmsManager.getDefault()
            }
            val parts = smsManager?.divideMessage(req.body) ?: arrayListOf(req.body)
            if (parts.size > 1) {
                smsManager?.sendMultipartTextMessage(req.phoneNumber, null, parts, null, null)
            } else {
                smsManager?.sendTextMessage(req.phoneNumber, null, req.body, null, null)
            }

            val resp = SendSmsResponse.newBuilder()
                .setRequestId(req.requestId)
                .setSuccess(true)
                .build()
            sendTelephonyMessage(TelephonyMessage.newBuilder().setSendSmsResp(resp).build())
            Log.i(tag, "Dispatched outbound SMS to ${req.phoneNumber} requested by desktop")
        } catch (e: Exception) {
            Log.e(tag, "Failed to send outbound SMS: ${e.message}")
            val resp = SendSmsResponse.newBuilder()
                .setRequestId(req.requestId)
                .setSuccess(false)
                .setErrorMessage(e.message ?: "SMS delivery error")
                .build()
            sendTelephonyMessage(TelephonyMessage.newBuilder().setSendSmsResp(resp).build())
        }
    }

    private fun sendTelephonyMessage(msg: TelephonyMessage) {
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_TELEPHONY)
            .setPayload(msg.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            scope.launch {
                try {
                    session.sendPacket(packet)
                } catch (e: Exception) {
                    Log.w(tag, "Failed sending telephony message to ${session.deviceName}: ${e.message}")
                }
            }
        }
    }

    private fun addMirroredNotification(item: MirroredNotificationItem) {
        val current = notificationsState.value.toMutableList()
        current.add(0, item)
        if (current.size > 50) current.removeAt(current.size - 1)
        notificationsState.value = current
    }

    private fun updateConnectedPeers() {
        connectedPeersState.value = activeSessions.values.map { "${it.deviceName} (${it.deviceId.take(8)})" }
    }

    private fun addLog(source: String, preview: String, hash: String) {
        val current = clipboardLogsState.value.toMutableList()
        current.add(0, ClipboardLog(System.currentTimeMillis(), source, "text/plain", preview, hash))
        if (current.size > 50) current.removeAt(current.size - 1)
        clipboardLogsState.value = current
    }

    private fun broadcastMediaMessageInternal(msg: MediaMessage) {
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_MEDIA)
            .setPayload(msg.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            scope.launch {
                try {
                    session.sendPacket(packet)
                } catch (e: Exception) {
                    Log.w(tag, "Failed sending media message to ${session.deviceName}: ${e.message}")
                }
            }
        }
    }

    private fun sendDesktopMediaCommandInternal(command: MediaControlCommand.Command, seekPositionMs: Long) {
        val cmd = MediaControlCommand.newBuilder()
            .setCommand(command)
            .setSeekPositionMs(seekPositionMs)
            .setVolume(1.0f)
            .build()

        val msg = MediaMessage.newBuilder()
            .setControl(cmd)
            .build()

        broadcastMediaMessageInternal(msg)
    }

    private fun broadcastCameraMessageInternal(msg: CameraMessage) {
        val packet = Packet.newBuilder()
            .setSessionId(deviceConfigState.value?.deviceId ?: "")
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_CAMERA)
            .setPayload(msg.toByteString())
            .build()

        for ((_, session) in activeSessions) {
            session.sendPacket(packet)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(tag, "ContinuityService stopping...")
        cameraManager.stopStreaming()
        mediaManager.stop()
        discoveryManager.stopDiscovery()
        discoveryManager.unregisterService()
        clipboardManager.release()
        telemetryMonitor.release()
        for ((_, session) in activeSessions) {
            try { session.socket.close() } catch (_: Exception) {}
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
