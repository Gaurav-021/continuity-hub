package com.continuityhub.model

data class DeviceConfig(
    val deviceId: String,
    val deviceName: String,
    val port: Int
)

data class KnownDevice(
    val deviceId: String,
    val deviceName: String,
    val certFingerprint: String,
    val pinnedAt: String,
    val trusted: Boolean
)

data class DiscoveredPeer(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int
)

data class ClipboardLog(
    val timestamp: Long,
    val source: String,
    val mimeType: String,
    val preview: String,
    val hash: String
)
