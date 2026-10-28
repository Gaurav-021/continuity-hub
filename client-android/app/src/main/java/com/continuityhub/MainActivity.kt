package com.continuityhub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.continuityhub.model.ClipboardLog
import com.continuityhub.model.FileTransferItem
import com.continuityhub.model.MirroredNotificationItem
import com.continuityhub.service.ContinuityService
import com.continuityhub.ui.TrackpadScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ContinuityService.start(this)
        }
    }

    private val telephonyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(this, "Telephony & SMS permissions granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ContinuityService.start(this)
            }
        } else {
            ContinuityService.start(this)
        }

        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ContinuityApp(
                        onCopyText = { text ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Continuity", text))
                            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onSendFileUri = { uri ->
                            ContinuityService.sendFile(uri)
                            Toast.makeText(this, "Sending file to desktop...", Toast.LENGTH_SHORT).show()
                        },
                        onOpenFile = { path ->
                            try {
                                val file = File(path)
                                val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "*/*")
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(viewIntent)
                            } catch (e: Exception) {
                                Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onOpenNotificationSettings = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            startActivity(intent)
                        },
                        onSendTestAlert = {
                            ContinuityService.sendTestAlertToDesktop(
                                "Incoming WhatsApp Message",
                                "Hey! Are you working on the Continuity engine?"
                            )
                            Toast.makeText(this, "Sent test notification alert to desktop", Toast.LENGTH_SHORT).show()
                        },
                        onTestCallAlert = {
                            val call = com.continuityhub.proto.CallEvent.newBuilder()
                                .setCallId(java.util.UUID.randomUUID().toString())
                                .setPhoneNumber("+1 (555) 234-5678")
                                .setContactName("Sarah Connor")
                                .setState(com.continuityhub.proto.CallEvent.CallState.CALL_STATE_RINGING)
                                .setTimestampMs(System.currentTimeMillis())
                                .build()
                            ContinuityService.broadcastCallEvent(call)
                            Toast.makeText(this, "Simulating incoming call alert on desktop...", Toast.LENGTH_SHORT).show()
                        },
                        onTestSmsAlert = {
                            val sms = com.continuityhub.proto.SmsMessage.newBuilder()
                                .setMessageId(java.util.UUID.randomUUID().toString())
                                .setPhoneNumber("+1 (555) 876-5432")
                                .setContactName("John Doe")
                                .setBody("Hey! Are we still meeting for lunch at 12:30?")
                                .setTimestampMs(System.currentTimeMillis())
                                .setIsIncoming(true)
                                .build()
                            ContinuityService.broadcastSmsMessage(sms)
                            Toast.makeText(this, "Simulating incoming SMS on desktop...", Toast.LENGTH_SHORT).show()
                        },
                        onRequestTelephonyPermissions = {
                            telephonyPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.RECEIVE_SMS,
                                    Manifest.permission.SEND_SMS,
                                    Manifest.permission.READ_CONTACTS
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    ContinuityService.sendFile(uri)
                    Toast.makeText(this, "Sharing file to Continuity Desktop...", Toast.LENGTH_SHORT).show()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.forEach { uri ->
                    ContinuityService.sendFile(uri)
                }
                if (!uris.isNullOrEmpty()) {
                    Toast.makeText(this, "Sharing ${uris.size} files to Desktop...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinuityApp(
    onCopyText: (String) -> Unit,
    onSendFileUri: (Uri) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendTestAlert: () -> Unit,
    onTestCallAlert: () -> Unit,
    onTestSmsAlert: () -> Unit,
    onRequestTelephonyPermissions: () -> Unit
) {
    val context = LocalContext.current
    val deviceConfig by ContinuityService.deviceConfigState.collectAsState()
    val fingerprint by ContinuityService.fingerprintState.collectAsState()
    val discoveredPeers by ContinuityService.discoveredPeersState.collectAsState()
    val connectedPeers by ContinuityService.connectedPeersState.collectAsState()
    val clipboardLogs by ContinuityService.clipboardLogsState.collectAsState()
    val fileTransfers by ContinuityService.fileTransfersState.collectAsState()
    val notifications by ContinuityService.notificationsState.collectAsState()
    val telemetry by ContinuityService.telemetryState.collectAsState()

    val isNotificationListenerEnabled = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

    var selectedTab by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendFileUri(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTab == 1) "Remote Trackpad" else "Continuity Hub", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (connectedPeers.isNotEmpty()) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (connectedPeers.isNotEmpty()) "Connected" else "Discovering",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Devices, contentDescription = "Hub") },
                    label = { Text("Hub") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = "Trackpad") },
                    label = { Text("Trackpad") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    ) { padding ->
        if (selectedTab == 1) {
            TrackpadScreen(
                isConnected = connectedPeers.isNotEmpty(),
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // 1. Device Identity Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Local Device Identity",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Device: ${deviceConfig?.deviceName ?: "Initializing..."}", fontWeight = FontWeight.Medium)
                        Text("Port: ${deviceConfig?.port ?: 52801}", fontSize = 13.sp)
                        Text("ID: ${deviceConfig?.deviceId ?: ""}", fontSize = 12.sp, color = Color.Gray)

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("TLS Certificate Fingerprint (SHA-256):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = fingerprint,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onCopyText(fingerprint) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Fingerprint")
                            }
                        }
                    }
                }
            }

            // 2. Peer Connections Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LAN Peers & mTLS Status",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        if (connectedPeers.isEmpty() && discoveredPeers.isEmpty()) {
                            Text(
                                "Scanning network via mDNS (_continuity._tcp.local)...",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        } else {
                            if (connectedPeers.isNotEmpty()) {
                                Text("Active Encrypted Sessions:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                for (peer in connectedPeers) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(peer, fontSize = 13.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (discoveredPeers.isNotEmpty()) {
                                Text("Discovered Peers:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                for (peer in discoveredPeers) {
                                    Text(
                                        "• ${peer.deviceName} (${peer.host}:${peer.port})",
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Telemetry & Cellular Status Card (Phase 5)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Device Health & Cellular Continuity",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        val batteryPct = telemetry?.batteryPercentage ?: 85
                        val isCharging = telemetry?.isCharging ?: false
                        val temp = telemetry?.batteryTemperatureC ?: 29.5f
                        val wifi = telemetry?.wifiSsid?.takeIf { it.isNotEmpty() } ?: "Connected"
                        val carrier = telemetry?.cellularCarrier?.takeIf { it.isNotEmpty() } ?: "Cellular"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Battery: $batteryPct% ${if (isCharging) "⚡ Charging" else "Discharging"}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Temp: ${String.format("%.1f", temp)}°C  •  Wi-Fi: $wifi  •  Carrier: $carrier",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onTestCallAlert,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Call", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onTestSmsAlert,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test SMS", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = onRequestTelephonyPermissions,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Phone & SMS Permissions", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 4. Notification Mirroring Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isNotificationListenerEnabled)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            Color(0xFFFFF3E0)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isNotificationListenerEnabled) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                contentDescription = null,
                                tint = if (isNotificationListenerEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Notification Mirroring",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isNotificationListenerEnabled)
                                "Notification mirroring is active. Phone notifications are mirrored to desktop with quick actions."
                            else
                                "Notification access permission required to mirror notifications to desktop.",
                            fontSize = 13.sp,
                            color = if (isNotificationListenerEnabled) Color.Unspecified else Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isNotificationListenerEnabled) {
                                Button(onClick = onOpenNotificationSettings) {
                                    Text("Grant Notification Access")
                                }
                            } else {
                                OutlinedButton(onClick = onSendTestAlert) {
                                    Text("Send Test Alert to Desktop")
                                }
                            }
                        }
                    }
                }
            }

            // 4. Actions: File Drop & Test Sync
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send File Drop")
                    }

                    OutlinedButton(
                        onClick = {
                            val testText = "Continuity Test: " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                            onCopyText(testText)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Clipboard")
                    }
                }
            }

            // 5. File Transfers Section
            if (fileTransfers.isNotEmpty()) {
                item {
                    Text(
                        text = "File Transfers (Drop / Received)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(fileTransfers) { transfer ->
                    FileTransferCard(transfer, onOpenFile)
                }
            }

            // 6. Mirrored Notifications Section
            if (notifications.isNotEmpty()) {
                item {
                    Text(
                        text = "Mirrored Notifications",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(notifications) { notif ->
                    NotificationCard(notif)
                }
            }

            // 7. Live Clipboard Log Header
            item {
                Text(
                    text = "Live Clipboard Synchronization Log",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 8. Clipboard Sync Log Items
            if (clipboardLogs.isEmpty()) {
                item {
                    Text(
                        "No clipboard events synced yet. Copy text on your phone or desktop to test sub-10ms synchronization.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            } else {
                items(clipboardLogs) { log ->
                    ClipboardLogItem(log)
                }
            }
        }
        }
    }
}

@Composable
fun NotificationCard(item: MirroredNotificationItem) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(item.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[${item.appName}] ${item.title}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = timeStr,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = item.body, fontSize = 13.sp)
        }
    }
}

@Composable
fun FileTransferCard(item: FileTransferItem, onOpenFile: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (item.isIncoming) Icons.Default.Download else Icons.Default.Upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (item.isIncoming) "From: ${item.peerName}" else "To: ${item.peerName}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                if (item.isCompleted) {
                    if (item.localPath != null) {
                        IconButton(onClick = { onOpenFile(item.localPath) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open File", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                    }
                } else if (item.isFailed) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!item.isCompleted && !item.isFailed) {
                LinearProgressIndicator(
                    progress = { item.progressPercentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.formattedProgress,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Text(
                    text = when {
                        item.isCompleted -> "Completed"
                        item.isFailed -> item.errorMessage ?: "Failed"
                        else -> "${(item.progressPercentage * 100).toInt()}%"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        item.isCompleted -> Color(0xFF4CAF50)
                        item.isFailed -> Color.Red
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
fun ClipboardLogItem(log: ClipboardLog) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.source,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = timeStr,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.preview,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "SHA-256: ${log.hash.take(16)}...",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray
            )
        }
    }
}
