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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.continuityhub.model.ClipboardLog
import com.continuityhub.model.DeviceConfig
import com.continuityhub.model.DiscoveredPeer
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

// Sealed model for unified activity stream
sealed interface ActivityFeedItem {
    val id: String
    val timestamp: Long

    data class FileTransfer(val item: FileTransferItem) : ActivityFeedItem {
        override val id: String get() = item.transferId
        override val timestamp: Long get() = item.timestamp
    }

    data class Clipboard(val log: ClipboardLog) : ActivityFeedItem {
        override val id: String get() = log.hash
        override val timestamp: Long get() = log.timestamp
    }

    data class Notification(val notif: MirroredNotificationItem) : ActivityFeedItem {
        override val id: String get() = notif.notificationId
        override val timestamp: Long get() = notif.timestamp
    }
}

enum class ActivityFilter(val label: String) {
    ALL("All"),
    FILES("Files"),
    CLIPBOARD("Clipboard"),
    NOTIFICATIONS("Notifications")
}

fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0)
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 45 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
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
    val clipboardSyncEnabled by ContinuityService.clipboardSyncEnabledState.collectAsState()
    val clipboardOutboundEnabled by ContinuityService.clipboardOutboundEnabledState.collectAsState()
    val clipboardInboundEnabled by ContinuityService.clipboardInboundEnabledState.collectAsState()

    val isNotificationListenerEnabled = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

    val hasTelephonyPermissions = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedActivityFilter by remember { mutableStateOf(ActivityFilter.ALL) }
    var showDirectConnectDialog by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var directConnectHost by remember { mutableStateOf("127.0.0.1") }
    var directConnectPort by remember { mutableStateOf("52800") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendFileUri(it) }
    }

    val isConnected = connectedPeers.isNotEmpty()
    val peerDisplayName = connectedPeers.firstOrNull() ?: "Desktop"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            1 -> "Trackpad"
                            2 -> "Media"
                            3 -> "Camera & Display"
                            else -> "Continuity"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    // Discreet connection status dot
                    if (isConnected) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(Color(0xFF10B981), shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Connected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Settings & Diagnostics Tool Button
                    IconButton(onClick = { showDiagnosticsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Settings & Diagnostics",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (isConnected) {
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
                        isConnected = isConnected,
                        modifier = Modifier.padding(padding)
                    )
                }
                2 -> {
                    MediaScreen(
                        isConnected = isConnected,
                        modifier = Modifier.padding(padding)
                    )
                }
                3 -> {
                    CameraScreen(
                        isConnected = isConnected,
                        modifier = Modifier.padding(padding)
                    )
                }
                else -> {
                    // Unified chronological activity stream
                    val allActivities = remember(fileTransfers, clipboardLogs, notifications) {
                        val list = mutableListOf<ActivityFeedItem>()
                        fileTransfers.forEach { list.add(ActivityFeedItem.FileTransfer(it)) }
                        clipboardLogs.forEach { list.add(ActivityFeedItem.Clipboard(it)) }
                        notifications.forEach { list.add(ActivityFeedItem.Notification(it)) }
                        list.sortedByDescending { it.timestamp }
                    }

                    val filteredActivities = remember(allActivities, selectedActivityFilter) {
                        when (selectedActivityFilter) {
                            ActivityFilter.ALL -> allActivities
                            ActivityFilter.FILES -> allActivities.filterIsInstance<ActivityFeedItem.FileTransfer>()
                            ActivityFilter.CLIPBOARD -> allActivities.filterIsInstance<ActivityFeedItem.Clipboard>()
                            ActivityFilter.NOTIFICATIONS -> allActivities.filterIsInstance<ActivityFeedItem.Notification>()
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(2.dp))
                        }

                        // 1. Primary Device Card (Connected vs Disconnected)
                        item {
                            if (isConnected) {
                                ConnectedDeviceCard(
                                    peerName = peerDisplayName,
                                    sasCode = sasCode,
                                    telemetry = telemetry
                                )
                            } else {
                                DisconnectedDeviceCard(
                                    onCopyCommand = onCopyText,
                                    onDirectConnect = { showDirectConnectDialog = true }
                                )
                            }
                        }

                        // 2. Discovered LAN Peers (if any extra peers found)
                        if (discoveredPeers.isNotEmpty() && (!isConnected || discoveredPeers.size > 1)) {
                            item {
                                DiscoveredPeersCard(
                                    discoveredPeers = discoveredPeers,
                                    onConnectPeer = { peer ->
                                        ContinuityService.connectDirect(peer.host, peer.port)
                                    }
                                )
                            }
                        }

                        // 3. Clean Quick Action Tiles (Files, Trackpad, Sidecar, Media)
                        item {
                            QuickActionsGrid(
                                isConnected = isConnected,
                                onSendFile = { filePickerLauncher.launch("*/*") },
                                onNavigateTab = { selectedTab = it }
                            )
                        }

                        // 4. Universal Clipboard (Bidirectional copy & paste) Toggle Card
                        item {
                            ClipboardSyncCard(
                                isSyncEnabled = clipboardSyncEnabled,
                                isOutboundEnabled = clipboardOutboundEnabled,
                                isInboundEnabled = clipboardInboundEnabled,
                                onToggleSync = { ContinuityService.setClipboardSyncEnabled(it) },
                                onToggleOutbound = { ContinuityService.setClipboardOutboundEnabled(it) },
                                onToggleInbound = { ContinuityService.setClipboardInboundEnabled(it) }
                            )
                        }

                        // 5. Permissions Setup Card (ONLY shown if permissions are missing)
                        if (!isNotificationListenerEnabled || !hasTelephonyPermissions) {
                            item {
                                SetupPermissionsBanner(
                                    needsNotification = !isNotificationListenerEnabled,
                                    needsTelephony = !hasTelephonyPermissions,
                                    onOpenNotificationSettings = onOpenNotificationSettings,
                                    onRequestTelephonyPermissions = onRequestTelephonyPermissions
                                )
                            }
                        }

                        // 5. Recent Activity Section Header & Segmented Filter
                        item {
                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                Text(
                                    text = "Recent Activity",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(ActivityFilter.entries) { filter ->
                                        FilterChip(
                                            selected = selectedActivityFilter == filter,
                                            onClick = { selectedActivityFilter = filter },
                                            label = { Text(filter.label) },
                                            leadingIcon = if (selectedActivityFilter == filter) {
                                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null,
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // 6. Filtered Activity Items
                        if (filteredActivities.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 28.dp, horizontal = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = when (selectedActivityFilter) {
                                                ActivityFilter.FILES -> "No file transfers yet"
                                                ActivityFilter.CLIPBOARD -> "No clipboard history synced yet"
                                                ActivityFilter.NOTIFICATIONS -> "No notifications mirrored yet"
                                                ActivityFilter.ALL -> "No recent activity yet"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = when (selectedActivityFilter) {
                                                ActivityFilter.FILES -> "Use 'Send File' to drop photos or documents to your PC"
                                                ActivityFilter.CLIPBOARD -> "Copy text on your phone or PC to test instant sync"
                                                ActivityFilter.NOTIFICATIONS -> "Incoming notifications will appear here"
                                                ActivityFilter.ALL -> "Files, clipboard clips, and notifications will show here"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(filteredActivities, key = { it.id }) { item ->
                                when (item) {
                                    is ActivityFeedItem.FileTransfer -> {
                                        ActivityFileCard(item.item, onOpenFile)
                                    }
                                    is ActivityFeedItem.Clipboard -> {
                                        ActivityClipboardCard(item.log, onCopyText)
                                    }
                                    is ActivityFeedItem.Notification -> {
                                        ActivityNotificationCard(item.notif)
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Diagnostics, Testing & Security Details
    if (showDiagnosticsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDiagnosticsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            DiagnosticsBottomSheetContent(
                deviceConfig = deviceConfig,
                fingerprint = fingerprint,
                sasCode = sasCode,
                isClipboardSyncEnabled = clipboardSyncEnabled,
                onToggleClipboardSync = { ContinuityService.setClipboardSyncEnabled(it) },
                onCopyText = onCopyText,
                onTestCallAlert = onTestCallAlert,
                onTestSmsAlert = onTestSmsAlert,
                onSendTestAlert = onSendTestAlert,
                onTestClipboard = {
                    val testText = "Continuity Test: " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    onCopyText(testText)
                },
                onOpenDirectConnect = {
                    showDiagnosticsSheet = false
                    showDirectConnectDialog = true
                }
            )
        }
    }

    // Direct Connect Dialog
    if (showDirectConnectDialog) {
        AlertDialog(
            onDismissRequest = { showDirectConnectDialog = false },
            title = { Text("Direct Connection (USB / IP)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Connect to Linux PC over local IP address or USB cable port forwarding (adb reverse):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = directConnectHost,
                        onValueChange = { directConnectHost = it },
                        label = { Text("IP Address or Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = directConnectPort,
                        onValueChange = { directConnectPort = it },
                        label = { Text("Port (Default: 52800)") },
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

// -------------------------------------------------------------
// Component: Connected Device Card (Google Material You style)
// -------------------------------------------------------------
@Composable
fun ConnectedDeviceCard(
    peerName: String,
    sasCode: String,
    telemetry: com.continuityhub.proto.DeviceTelemetry?
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Laptop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(Color(0xFF10B981), shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Connected · Local Network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Real-time battery & network telemetry line
            if (telemetry != null) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (telemetry.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${telemetry.batteryPercentage}%${if (telemetry.isCharging) " (Charging)" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (telemetry.wifiSsid.isNotEmpty()) {
                        Text(
                            text = "Wi-Fi: ${telemetry.wifiSsid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${String.format(Locale.US, "%.1f", telemetry.batteryTemperatureC)}°C",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Verification code strip (calm Google security verification)
            if (sasCode.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Security Code:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = sasCode,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "Matches PC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Disconnected Device Card
// -------------------------------------------------------------
@Composable
fun DisconnectedDeviceCard(
    onCopyCommand: (String) -> Unit,
    onDirectConnect: () -> Unit
) {
    var isGuideExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Looking for your computer...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Ensure both devices are on the same Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Clean terminal command chip
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "continuity-daemon",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onCopyCommand("continuity-daemon") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy command",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onDirectConnect,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Direct Connect")
                }

                OutlinedButton(
                    onClick = { isGuideExpanded = !isGuideExpanded },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isGuideExpanded) "Hide Tips" else "Connection Tips")
                }
            }

            AnimatedVisibility(
                visible = isGuideExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Fast USB Cable Connection:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Plug in USB and forward ports: 'adb reverse tcp:52800 tcp:52800'",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Firewall (UFW / Firewalld):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Allow ports 52800 and 52801: 'sudo ufw allow 52800:52801/tcp'",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Discovered Peers Card
// -------------------------------------------------------------
@Composable
fun DiscoveredPeersCard(
    discoveredPeers: List<DiscoveredPeer>,
    onConnectPeer: (DiscoveredPeer) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Available Devices on Network",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            discoveredPeers.forEach { peer ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.deviceName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${peer.host}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Button(
                        onClick = { onConnectPeer(peer) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Connect", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Quick Actions 2x2 Grid (Google Quick Settings style)
// -------------------------------------------------------------
@Composable
fun QuickActionsGrid(
    isConnected: Boolean,
    onSendFile: () -> Unit,
    onNavigateTab: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickActionTile(
                title = "Send Files",
                subtitle = "Drop to desktop",
                icon = Icons.Default.UploadFile,
                isPrimary = true,
                modifier = Modifier.weight(1f),
                onClick = onSendFile
            )
            QuickActionTile(
                title = "Trackpad",
                subtitle = "Cursor & keyboard",
                icon = Icons.Default.TouchApp,
                isPrimary = false,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateTab(1) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickActionTile(
                title = "Sidecar Display",
                subtitle = "Extend PC screen",
                icon = Icons.Default.Tv,
                isPrimary = false,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateTab(3) }
            )
            QuickActionTile(
                title = "Media Remote",
                subtitle = "Sync & controls",
                icon = Icons.Default.MusicNote,
                isPrimary = false,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateTab(2) }
            )
        }
    }
}

@Composable
fun QuickActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (isPrimary)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier.height(78.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPrimary)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isPrimary)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPrimary)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPrimary)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Universal Clipboard Toggle Card (Bidirectional Sync)
// -------------------------------------------------------------
@Composable
fun ClipboardSyncCard(
    isSyncEnabled: Boolean,
    isOutboundEnabled: Boolean,
    isInboundEnabled: Boolean,
    onToggleSync: (Boolean) -> Unit,
    onToggleOutbound: (Boolean) -> Unit,
    onToggleInbound: (Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSyncEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = if (isSyncEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { if (isSyncEnabled) isExpanded = !isExpanded }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Universal Clipboard",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isSyncEnabled) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Text(
                        text = if (isSyncEnabled)
                            "Bidirectional copy & paste active"
                        else
                            "Clipboard sync paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isSyncEnabled,
                    onCheckedChange = onToggleSync
                )
            }

            AnimatedVisibility(
                visible = isSyncEnabled && isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Send phone copies to PC",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Copies on this phone sync to desktop",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = isOutboundEnabled,
                            onCheckedChange = onToggleOutbound
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Receive PC copies on phone",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Copies on desktop sync to this phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = isInboundEnabled,
                            onCheckedChange = onToggleInbound
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Setup Permissions Banner (Only shown when missing)
// -------------------------------------------------------------
@Composable
fun SetupPermissionsBanner(
    needsNotification: Boolean,
    needsTelephony: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onRequestTelephonyPermissions: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Complete Feature Setup",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (needsNotification && needsTelephony)
                        "Allow notification mirroring and phone call alerts on PC."
                    else if (needsNotification)
                        "Allow notification mirroring to your desktop."
                    else
                        "Allow incoming phone call & SMS alerts on PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (needsNotification) {
                        onOpenNotificationSettings()
                    } else {
                        onRequestTelephonyPermissions()
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Set up")
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Activity Cards (File, Clipboard, Notification)
// -------------------------------------------------------------
@Composable
fun ActivityFileCard(
    item: FileTransferItem,
    onOpenFile: (String) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (item.isIncoming) Icons.Default.Download else Icons.Default.Upload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.formattedSize} · ${if (item.isIncoming) "From ${item.peerName}" else "To ${item.peerName}"} · ${formatRelativeTime(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                if (item.isCompleted) {
                    if (item.localPath != null) {
                        FilledTonalButton(
                            onClick = { onOpenFile(item.localPath) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Open", fontSize = 12.sp)
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                    }
                } else if (item.isFailed) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }

            if (!item.isCompleted && !item.isFailed) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }
        }
    }
}

@Composable
fun ActivityClipboardCard(
    log: ClipboardLog,
    onCopyText: (String) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${if (log.source.contains("desktop", true)) "From PC" else "From Phone"} · ${formatRelativeTime(log.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { onCopyText(log.preview) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy again",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ActivityNotificationCard(
    item: MirroredNotificationItem
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[${item.appName}] ${item.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatRelativeTime(item.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Component: Diagnostics & Tools Bottom Sheet (Test tools & Security)
// -------------------------------------------------------------
@Composable
fun DiagnosticsBottomSheetContent(
    deviceConfig: DeviceConfig?,
    fingerprint: String,
    sasCode: String,
    isClipboardSyncEnabled: Boolean,
    onToggleClipboardSync: (Boolean) -> Unit,
    onCopyText: (String) -> Unit,
    onTestCallAlert: () -> Unit,
    onTestSmsAlert: () -> Unit,
    onSendTestAlert: () -> Unit,
    onTestClipboard: () -> Unit,
    onOpenDirectConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Tools & Diagnostics",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // 1. Universal Clipboard Master Toggle
        Text(
            text = "Clipboard Sync",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Universal Clipboard",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Bidirectional copy & paste sync",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isClipboardSyncEnabled,
                    onCheckedChange = onToggleClipboardSync
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // 2. Simulation & Testing
        Text(
            text = "Simulate Desktop Alerts",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onSendTestAlert,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Test Notif")
            }
            FilledTonalButton(
                onClick = onTestClipboard,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Test Clip")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // 2. Cryptographic Security & Identity
        Text(
            text = "Cryptographic Identity",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Device Name:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text(deviceConfig?.deviceName ?: "Initializing...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Port:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("${deviceConfig?.port ?: 52801}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("TLS Certificate Fingerprint (SHA-256):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fingerprint,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onCopyText(fingerprint) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Fingerprint", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onOpenDirectConnect,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Manual Direct Connection (USB / IP)")
        }
    }
}
