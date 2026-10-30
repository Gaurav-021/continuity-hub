package com.continuityhub.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.continuityhub.proto.NotificationAction
import com.continuityhub.proto.NotificationActionRequest
import com.continuityhub.proto.NotificationDismiss
import com.continuityhub.proto.NotificationPayload
import com.continuityhub.service.ContinuityService
import java.util.concurrent.ConcurrentHashMap

class ContinuityNotificationListener : NotificationListenerService() {

    private val tag = "ContinuityNotifListener"

    data class CachedNotificationAction(
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput? = null
    )

    companion object {
        var instance: ContinuityNotificationListener? = null
            private set

        private val cachedActions = ConcurrentHashMap<String, CachedNotificationAction>()

        fun executeRemoteAction(request: NotificationActionRequest) {
            if (!ContinuityService.notificationSyncEnabledState.value ||
                !ContinuityService.notificationActionsEnabledState.value) {
                Log.d("ContinuityNotifListener", "Remote notification action ignored: actions disabled")
                return
            }
            instance?.handleRemoteAction(request)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(tag, "NotificationListenerService connected and active")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.i(tag, "NotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (!ContinuityService.notificationSyncEnabledState.value ||
            !ContinuityService.notificationOutboundEnabledState.value) {
            return
        }

        val pkg = sbn.packageName
        if (pkg == packageName) return // Ignore own app notifications

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        val notifKey = sbn.key
        val actionsList = mutableListOf<NotificationAction>()
        var hasQuickReply = false

        sbn.notification.actions?.forEachIndexed { index, action ->
            val label = action.title?.toString() ?: "Action $index"
            val actionId = "$index"
            val remoteInput = action.remoteInputs?.firstOrNull()
            val isReply = remoteInput != null

            if (isReply) {
                cachedActions[notifKey] = CachedNotificationAction(action.actionIntent, remoteInput)
                hasQuickReply = true
            }
            cachedActions["$notifKey:$actionId"] = CachedNotificationAction(action.actionIntent, remoteInput)

            actionsList.add(
                NotificationAction.newBuilder()
                    .setActionId(actionId)
                    .setLabel(label)
                    .setIsQuickReply(isReply)
                    .build()
            )
        }

        val payload = NotificationPayload.newBuilder()
            .setNotificationId(notifKey)
            .setPackageName(pkg)
            .setAppName(appName)
            .setTitle(title)
            .setBody(text)
            .setTimestampMs(sbn.postTime)
            .setCategory(sbn.notification.category ?: "general")
            .setHasQuickReply(hasQuickReply)
            .addAllActions(actionsList)
            .build()

        ContinuityService.broadcastNotification(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        if (!ContinuityService.notificationSyncEnabledState.value ||
            !ContinuityService.notificationOutboundEnabledState.value) {
            return
        }

        val notifKey = sbn.key
        cachedActions.remove(notifKey)
        cachedActions.keys.removeIf { it.startsWith("$notifKey:") }

        val dismiss = NotificationDismiss.newBuilder()
            .setNotificationId(notifKey)
            .build()

        ContinuityService.broadcastNotificationDismiss(dismiss)
    }

    fun handleRemoteAction(request: NotificationActionRequest) {
        val notifKey = request.notificationId
        Log.i(tag, "Executing remote notification action: ${request.actionType} on $notifKey (actionId: ${request.actionId})")

        when (request.actionType) {
            NotificationActionRequest.ActionType.ACTION_DISMISS -> {
                try {
                    cancelNotification(notifKey)
                    cachedActions.remove(notifKey)
                    cachedActions.keys.removeIf { it.startsWith("$notifKey:") }
                    Log.i(tag, "Successfully dismissed notification: $notifKey")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to dismiss notification", e)
                }
            }

            NotificationActionRequest.ActionType.ACTION_REPLY -> {
                val cached = cachedActions["$notifKey:${request.actionId}"] ?: cachedActions[notifKey]
                if (cached != null && cached.remoteInput != null) {
                    try {
                        val replyText = request.replyText
                        val bundle = Bundle().apply {
                            putCharSequence(cached.remoteInput.resultKey, replyText)
                        }
                        val intent = Intent()
                        RemoteInput.addResultsToIntent(arrayOf(cached.remoteInput), intent, bundle)
                        cached.pendingIntent.send(this, 0, intent)
                        Log.i(tag, "Successfully sent quick reply for $notifKey: '$replyText'")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed sending quick reply", e)
                    }
                } else {
                    Log.w(tag, "No cached reply action found for $notifKey")
                }
            }

            NotificationActionRequest.ActionType.ACTION_CLICK -> {
                val cached = cachedActions["$notifKey:${request.actionId}"] ?: cachedActions[notifKey]
                if (cached != null) {
                    try {
                        cached.pendingIntent.send(this, 0, null)
                        Log.i(tag, "Successfully executed remote action ${request.actionId} for $notifKey")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed executing remote action", e)
                    }
                } else {
                    Log.w(tag, "No cached action found for $notifKey:${request.actionId}")
                }
            }

            else -> {}
        }
    }
}
