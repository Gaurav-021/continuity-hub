package com.continuityhub.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.continuityhub.proto.SmsMessage
import com.continuityhub.service.ContinuityService
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {

    private val tag = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return

                val fullBody = StringBuilder()
                var senderNumber = ""
                var timestamp = System.currentTimeMillis()

                for (msg in messages) {
                    senderNumber = msg.originatingAddress ?: senderNumber
                    fullBody.append(msg.messageBody)
                    timestamp = msg.timestampMillis
                }

                val contactName = ContactUtils.resolveContactName(context, senderNumber)

                val sms = SmsMessage.newBuilder()
                    .setMessageId(UUID.randomUUID().toString())
                    .setPhoneNumber(senderNumber)
                    .setContactName(contactName)
                    .setBody(fullBody.toString())
                    .setTimestampMs(timestamp)
                    .setIsIncoming(true)
                    .build()

                Log.i(tag, "Received SMS from $senderNumber ($contactName), broadcasting to desktop")
                ContinuityService.broadcastSmsMessage(sms)
            } catch (e: Exception) {
                Log.e(tag, "Error processing incoming SMS broadcast: ${e.message}", e)
            }
        }
    }
}
