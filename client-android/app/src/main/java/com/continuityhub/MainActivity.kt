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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.continuityhub.model.ClipboardLog
import com.continuityhub.model.FileTransferItem
import com.continuityhub.service.ContinuityService
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
    onOpenFile: (String) -> Unit
) {
    val deviceConfig by ContinuityService.deviceConfigState.collectAsState()
    val fingerprint by ContinuityService.fingerprintState.collectAsState()
    val discoveredPeers by ContinuityService.discoveredPeersState.collectAsState()
    val connectedPeers by ContinuityService.connectedPeersState.collectAsState()
    val clipboardLogs by ContinuityService.clipboardLogsState.collectAsState()
    val fileTransfers by ContinuityService.fileTransfersState.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendFileUri(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Continuity Hub", fontWeight = FontWeight.Bold) },
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
        }
    ) { padding ->
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

            // 3. Actions: File Drop & Test Sync
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

            // 4. File Transfers Section
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

            // 5. Live Clipboard Log Header
            item {
                Text(
                    text = "Live Clipboard Synchronization Log",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 6. Clipboard Sync Log Items
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
