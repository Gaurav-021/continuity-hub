package com.continuityhub.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.continuityhub.proto.CallActionRequest
import com.continuityhub.proto.CallEvent
import com.continuityhub.service.ContinuityService
import java.util.UUID

class TelephonyCallManager(private val context: Context) {

    private val tag = "TelephonyCallManager"
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private var activeCallId = UUID.randomUUID().toString()
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    init {
        registerCallListener()
    }

    private fun registerCallListener() {
        if (telephonyManager == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    processCallState(state, null)
                }
            }
            try {
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
            } catch (e: Exception) {
                Log.w(tag, "Failed to register TelephonyCallback: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : android.telephony.PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    processCallState(state, phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun processCallState(state: Int, incomingNumber: String?) {
        val number = incomingNumber ?: ""
        val contactName = ContactUtils.resolveContactName(context, number)

        val protoState = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                activeCallId = UUID.randomUUID().toString()
                CallEvent.CallState.CALL_STATE_RINGING
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> CallEvent.CallState.CALL_STATE_OFFHOOK
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    CallEvent.CallState.CALL_STATE_MISSED
                } else {
                    CallEvent.CallState.CALL_STATE_IDLE
                }
            }
            else -> CallEvent.CallState.CALL_STATE_UNKNOWN
        }

        lastState = state

        val event = CallEvent.newBuilder()
            .setCallId(activeCallId)
            .setPhoneNumber(number)
            .setContactName(contactName)
            .setState(protoState)
            .setTimestampMs(System.currentTimeMillis())
            .build()

        ContinuityService.broadcastCallEvent(event)
    }

    fun executeCallAction(request: CallActionRequest) {
        Log.i(tag, "Executing CallAction: ${request.action} for call ID ${request.callId}")
        when (request.action) {
            CallActionRequest.Action.ACTION_MUTE -> {
                try {
                    audioManager?.adjustStreamVolume(
                        AudioManager.STREAM_RING,
                        AudioManager.ADJUST_MUTE,
                        0
                    )
                    Log.i(tag, "Muted phone ringer via remote desktop command")
                } catch (e: Exception) {
                    Log.e(tag, "Failed muting ringer: ${e.message}")
                }
            }
            CallActionRequest.Action.ACTION_REJECT -> {
                endActiveCall()
            }
            CallActionRequest.Action.ACTION_REJECT_WITH_SMS -> {
                if (request.phoneNumber.isNotEmpty() && request.smsReply.isNotEmpty()) {
                    try {
                        @Suppress("DEPRECATION")
                        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            SmsManager.getDefault()
                        }
                        sms?.sendTextMessage(request.phoneNumber, null, request.smsReply, null, null)
                        Log.i(tag, "Sent quick rejection SMS to ${request.phoneNumber}: '${request.smsReply}'")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed sending rejection SMS: ${e.message}")
                    }
                }
                endActiveCall()
            }
            else -> {}
        }
    }

    private fun endActiveCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    telecomManager?.endCall()
                    Log.i(tag, "Ended active call via TelecomManager")
                } catch (e: Exception) {
                    Log.e(tag, "Failed ending call: ${e.message}")
                }
            } else {
                Log.w(tag, "ANSWER_PHONE_CALLS permission not granted to end call")
            }
        }
    }
}
