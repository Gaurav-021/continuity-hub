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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.continuityhub.model.ClipboardLog
import com.continuityhub.model.FileTransferItem
import com.continuityhub.model.MirroredNotificationItem
import com.continuityhub.service.ContinuityService
import com.continuityhub.ui.CameraScreen
import com.continuityhub.ui.ContinuityHubTheme
import com.continuityhub.ui.MediaScreen
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

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        handleShareIntent(intent)

        setContent {
            ContinuityHubTheme {
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
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.ANSWER_PHONE_CALLS,
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

    override fun onResume() {
        super.onResume()
        ContinuityService.syncLocalClipboard()
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
    val sasCode by ContinuityService.sasCodeState.collectAsState()

    val isNotificationListenerEnabled = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

    var selectedTab by remember { mutableIntStateOf(0) }
    var showDirectConnectDialog by remember { mutableStateOf(false) }
    var directConnectHost by remember { mutableStateOf("127.0.0.1") }
    var directConnectPort by remember { mutableStateOf("52800") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendFileUri(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            1 -> "Remote Trackpad"
                            2 -> "Multimedia Sync"
                            3 -> "Continuity Camera"
                            else -> "Continuity Hub"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (connectedPeers.isNotEmpty())
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (connectedPeers.isNotEmpty()) Color(0xFF10B981) else Color(0xFFF59E0B),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (sasCode.isNotEmpty()) "SAS: $sasCode" else if (connectedPeers.isNotEmpty()) "Connected" else "Discovering",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (connectedPeers.isNotEmpty())
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (connectedPeers.isNotEmpty()) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Devices, contentDescription = "Hub")
                        }
                    },
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
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = "Media") },
                    label = { Text("Media") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "Camera") },
                    label = { Text("Camera") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            label = "tabTransition",
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { width -> width / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -width / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { width -> -width / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> width / 4 } + fadeOut())
                }
            }
        ) { tab ->
            when (tab) {
                1 -> {
                    TrackpadScreen(
                        isConnected = connectedPeers.isNotEmpty(),
                        modifier = Modifier.padding(padding)
                    )
                }
                2 -> {
                    MediaScreen(
                        isConnected = connectedPeers.isNotEmpty(),
                        modifier = Modifier.padding(padding)
                    )
                }
                3 -> {
                    CameraScreen(
                        isConnected = connectedPeers.isNotEmpty(),
                        modifier = Modifier.padding(padding)
                    )
                }
                else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
            // 1. Device Identity Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Devices,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Local Device Identity",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = deviceConfig?.deviceName ?: "Initializing...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Port: ${deviceConfig?.port ?: 52801}", style = MaterialTheme.typography.bodyMedium)
                            Text("ID: ${deviceConfig?.deviceId?.take(8) ?: ""}...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("TLS Certificate Fingerprint (SHA-256):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = fingerprint,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onCopyText(fingerprint) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Fingerprint", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 2. Peer Connections Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Sync,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "LAN Peers & mTLS Status",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        if (connectedPeers.isEmpty() && discoveredPeers.isEmpty()) {
                            Text(
                                "Scanning network via mDNS (_continuity._tcp.local)...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            if (connectedPeers.isNotEmpty()) {
                                Text("Active Encrypted Sessions:", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(6.dp))

                                if (sasCode.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0x2210B981),
                                        border = BorderStroke(1.dp, Color(0x6610B981)),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Security,
                                                contentDescription = "Verified SAS",
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Security Verification Code (SAS)",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF10B981)
                                                )
                                                Text(
                                                    text = sasCode,
                                                    fontSize = 16.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }

                                for (peer in connectedPeers) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(peer, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (discoveredPeers.isNotEmpty()) {
                                Text("Discovered Peers:", style = MaterialTheme.typography.labelMedium)
                                for (peer in discoveredPeers) {
                                    Text(
                                        "• ${peer.deviceName} (${peer.host}:${peer.port})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { showDirectConnectDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect to IP Directly (USB / Manual)")
                        }
                    }
                }
            }

            // 3. Telemetry & Cellular Status Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.BatteryChargingFull,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Device Health & Telemetry",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))

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
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Temp: ${String.format("%.1f", temp)}°C  •  Wi-Fi: $wifi  •  Carrier: $carrier",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onTestCallAlert,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Call")
                            }

                            FilledTonalButton(
                                onClick = onTestSmsAlert,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test SMS")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRequestTelephonyPermissions,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Phone & SMS Permissions")
                        }
                    }
                }
            }

            // 4. Notification Mirroring Status Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (isNotificationListenerEnabled)
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (isNotificationListenerEnabled)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isNotificationListenerEnabled) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = if (isNotificationListenerEnabled)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Notification Mirroring",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isNotificationListenerEnabled)
                                "Phone notifications are mirrored seamlessly to desktop with quick replies & actions."
                            else
                                "Notification access permission required to mirror notifications to desktop.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isNotificationListenerEnabled)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (!isNotificationListenerEnabled) {
                            Button(
                                onClick = onOpenNotificationSettings,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Notification Access")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onSendTestAlert,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Send Test Alert to Desktop")
                            }
                        }
                    }
                }
            }

            // 5. Actions: File Drop & Test Sync
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send File Drop")
                    }

                    FilledTonalButton(
                        onClick = {
                            val testText = "Continuity Test: " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                            onCopyText(testText)
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
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
                    ClipboardLogItem(log, onCopyText)
                }
            }
        }
            }
        }
        }
    }

    if (showDirectConnectDialog) {
        AlertDialog(
            onDismissRequest = { showDirectConnectDialog = false },
            title = { Text("Manual Direct Connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter Linux desktop IP address or localhost (for adb reverse):", fontSize = 13.sp)
                    OutlinedTextField(
                        value = directConnectHost,
                        onValueChange = { directConnectHost = it },
                        label = { Text("IP Address / Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = directConnectPort,
                        onValueChange = { directConnectPort = it },
                        label = { Text("Daemon Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val portInt = directConnectPort.toIntOrNull() ?: 52800
                        ContinuityService.connectDirect(directConnectHost.trim(), portInt)
                        showDirectConnectDialog = false
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDirectConnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun NotificationCard(item: MirroredNotificationItem) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(item.timestamp))

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[${item.appName}] ${item.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = item.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun FileTransferCard(item: FileTransferItem, onOpenFile: (String) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (item.isIncoming) Icons.Default.Download else Icons.Default.Upload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (item.isIncoming) "From: ${item.peerName}" else "To: ${item.peerName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                if (item.isCompleted) {
                    if (item.localPath != null) {
                        IconButton(onClick = { onOpenFile(item.localPath) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open File", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                    }
                } else if (item.isFailed) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!item.isCompleted && !item.isFailed) {
                LinearProgressIndicator(
                    progress = { item.progressPercentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.formattedProgress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = when {
                        item.isCompleted -> "Completed"
                        item.isFailed -> item.errorMessage ?: "Failed"
                        else -> "${(item.progressPercentage * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        item.isCompleted -> Color(0xFF10B981)
                        item.isFailed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
fun ClipboardLogItem(log: ClipboardLog, onCopyText: (String) -> Unit) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.source,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = log.preview,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SHA-256: ${log.hash.take(16)}...",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onCopyText(log.preview) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Content",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
