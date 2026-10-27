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
import com.continuityhub.clipboard.AndroidClipboardManager
import com.continuityhub.crypto.CryptoManager
import com.continuityhub.discovery.NsdDiscoveryManager
import com.continuityhub.model.ClipboardLog
import com.continuityhub.model.DeviceConfig
import com.continuityhub.model.DiscoveredPeer
import com.continuityhub.model.FileTransferItem
import com.continuityhub.model.MirroredNotificationItem
import com.continuityhub.notifications.ContinuityNotificationListener
import com.continuityhub.proto.Channel
import com.continuityhub.proto.ClipboardPayload
import com.continuityhub.proto.FileTransferMessage
import com.continuityhub.proto.HandshakeRequest
import com.continuityhub.proto.HandshakeResponse
import com.continuityhub.proto.InputMessage
import com.continuityhub.proto.NotificationDismiss
import com.continuityhub.proto.NotificationMessage
import com.continuityhub.proto.NotificationPayload
import com.continuityhub.proto.Packet
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

    private val activeSessions = ConcurrentHashMap<String, PeerSession>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val sequenceNumber = AtomicLong(0)

    private var serverJob: Job? = null

    class PeerSession(
        val deviceId: String,
        val deviceName: String,
        val socket: Socket,
        val inputStream: InputStream,
        val outputStream: OutputStream
    ) {
        fun sendPacket(packet: Packet) {
            PacketFraming.writePacket(outputStream, packet)
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

        clipboardManager = AndroidClipboardManager(this, deviceId) { payload ->
            broadcastClipboard(payload)
            addLog("Android", "Local Copy: ${payload.data.toStringUtf8().take(30)}", payload.contentHash)
        }

        fileTransferManager = AndroidFileTransferManager(this, deviceId, scope)
        scope.launch {
            fileTransferManager.transfersState.collect { transfers ->
                fileTransfersState.value = transfers
            }
        }

        discoveryManager = NsdDiscoveryManager(this, deviceId)

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

        if (config.deviceId > peer.deviceId) {
            Log.d(tag, "Tie-breaker: letting peer ${peer.deviceName} initiate connection to us")
            return
        }

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

        tofuManager.updateDeviceInfo(peerFingerprint, peerDeviceId, peerDeviceName)
        Log.i(tag, "mTLS Handshake complete with peer '$peerDeviceName' ($peerDeviceId)")

        val session = PeerSession(peerDeviceId, peerDeviceName, socket, input, output)
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
                            NotificationMessage.MsgCase.ACTION -> {
                                ContinuityNotificationListener.executeRemoteAction(msg.action)
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
                    else -> {
                        Log.d(tag, "Unhandled channel ${packet.channel} from $peerDeviceName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(tag, "Peer connection ended for $peerDeviceName: ${e.message}")
        } finally {
            activeSessions.remove(peerDeviceId)
            updateConnectedPeers()
            try { socket.close() } catch (_: Exception) {}
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
            scope.launch {
                try {
                    session.sendPacket(packet)
                    Log.d(tag, "Streamed clipboard payload to ${session.deviceName}")
                } catch (e: Exception) {
                    Log.w(tag, "Failed streaming to ${session.deviceName}: ${e.message}")
                }
            }
        }
    }

    private fun broadcastNotificationInternal(payload: NotificationPayload) {
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
            scope.launch {
                try {
                    session.sendPacket(packet)
                } catch (e: Exception) {
                    Log.w(tag, "Failed sending input to ${session.deviceName}: ${e.message}")
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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(tag, "ContinuityService stopping...")
        discoveryManager.stopDiscovery()
        discoveryManager.unregisterService()
        clipboardManager.release()
        for ((_, session) in activeSessions) {
            try { session.socket.close() } catch (_: Exception) {}
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
