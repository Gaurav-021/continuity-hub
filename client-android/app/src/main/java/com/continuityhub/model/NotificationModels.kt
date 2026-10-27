package com.continuityhub.model

data class MirroredNotificationItem(
    val notificationId: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    val hasQuickReply: Boolean,
    val isIncomingFromDesktop: Boolean = false
)
