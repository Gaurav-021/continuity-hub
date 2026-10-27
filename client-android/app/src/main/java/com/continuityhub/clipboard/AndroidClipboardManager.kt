package com.continuityhub.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.continuityhub.proto.ClipboardPayload
import com.google.protobuf.ByteString
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet

class AndroidClipboardManager(
    private val context: Context,
    private val myDeviceId: String,
    private val onClipboardUpdated: (ClipboardPayload) -> Unit
) {
    private val tag = "AndroidClipboard"
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dedupCapacity = 64
    private val dedupCache = Collections.synchronizedSet(LinkedHashSet<String>())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleLocalClipChange()
    }

    init {
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    fun markSeen(hash: String) {
        synchronized(dedupCache) {
            if (dedupCache.size >= dedupCapacity) {
                val oldest = dedupCache.iterator().next()
                dedupCache.remove(oldest)
            }
            dedupCache.add(hash)
        }
    }

    fun isSeen(hash: String): Boolean {
        return dedupCache.contains(hash)
    }

    private fun handleLocalClipChange() {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return

        val item = clip.getItemAt(0)
        val text = item.text?.toString() ?: item.coerceToText(context)?.toString() ?: ""
        if (text.isEmpty()) return

        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = hashBytes(bytes)

        if (isSeen(hash)) {
            Log.d(tag, "Local clip change: Suppressing duplicate content (hash: $hash)")
            return
        }

        markSeen(hash)
        Log.i(tag, "Local clip change detected: ${bytes.size} bytes (hash: $hash)")

        val payload = ClipboardPayload.newBuilder()
            .setTimestampMs(System.currentTimeMillis())
            .setMimeType("text/plain")
            .setData(ByteString.copyFrom(bytes))
            .setContentHash(hash)
            .setSourceDeviceId(myDeviceId)
            .build()

        onClipboardUpdated(payload)
    }

    fun setRemoteClipboard(payload: ClipboardPayload) {
        markSeen(payload.contentHash)

        val text = payload.data.toStringUtf8()
        mainHandler.post {
            try {
                val clipData = ClipData.newPlainText("Continuity", text)
                clipboard.setPrimaryClip(clipData)
                Log.i(tag, "Successfully updated Android system clipboard from remote device '${payload.sourceDeviceId}'")
            } catch (e: Exception) {
                Log.e(tag, "Failed to set Android primary clip", e)
            }
        }
    }

    fun release() {
        clipboard.removePrimaryClipChangedListener(clipListener)
    }

    companion object {
        fun hashBytes(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
