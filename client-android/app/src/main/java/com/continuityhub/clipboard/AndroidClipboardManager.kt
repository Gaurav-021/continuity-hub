package com.continuityhub.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.continuityhub.proto.ClipboardPayload
import com.google.protobuf.ByteString
import java.io.File
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

    fun checkClipboard() {
        mainHandler.post {
            handleLocalClipChange()
        }
    }

    private fun handleLocalClipChange() {
        val clip = try {
            clipboard.primaryClip
        } catch (e: Exception) {
            Log.d(tag, "Unable to read primaryClip (background/focus restricted): ${e.message}")
            null
        } ?: return
        if (clip.itemCount == 0) return

        val item = clip.getItemAt(0)
        val uri = item.uri
        val uriMime = if (uri != null) context.contentResolver.getType(uri) else null

        val (bytes, effectiveMime) = if (uri != null && uriMime != null && uriMime.startsWith("image/")) {
            try {
                val imgBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (imgBytes == null || imgBytes.isEmpty()) return
                imgBytes to uriMime
            } catch (e: Exception) {
                Log.w(tag, "Failed reading image data from clipboard URI: ${e.message}")
                return
            }
        } else {
            val text = item.text?.toString() ?: item.coerceToText(context)?.toString() ?: ""
            if (text.isEmpty()) return
            text.toByteArray(Charsets.UTF_8) to "text/plain"
        }

        val hash = hashBytes(bytes)

        if (isSeen(hash)) {
            Log.d(tag, "Local clip change: Suppressing duplicate content (hash: $hash)")
            return
        }

        markSeen(hash)
        Log.i(tag, "Local clip change detected: ${bytes.size} bytes (mime: $effectiveMime, hash: $hash)")

        val payload = ClipboardPayload.newBuilder()
            .setTimestampMs(System.currentTimeMillis())
            .setMimeType(effectiveMime)
            .setData(ByteString.copyFrom(bytes))
            .setContentHash(hash)
            .setSourceDeviceId(myDeviceId)
            .build()

        onClipboardUpdated(payload)
    }

    fun setRemoteClipboard(payload: ClipboardPayload) {
        markSeen(payload.contentHash)

        mainHandler.post {
            try {
                if (payload.mimeType.startsWith("image/")) {
                    val ext = if (payload.mimeType == "image/jpeg") ".jpg" else ".png"
                    val clipImageFile = File(context.cacheDir, "continuity_clip_image$ext")
                    clipImageFile.writeBytes(payload.data.toByteArray())

                    val authority = "${context.packageName}.provider"
                    val contentUri = FileProvider.getUriForFile(context, authority, clipImageFile)

                    val clipData = ClipData.newUri(context.contentResolver, "Continuity Image", contentUri)
                    clipboard.setPrimaryClip(clipData)
                    Log.i(tag, "Successfully updated Android system clipboard with image (${payload.data.size()} bytes)")
                } else {
                    val text = payload.data.toStringUtf8()
                    val clipData = ClipData.newPlainText("Continuity", text)
                    clipboard.setPrimaryClip(clipData)
                    Log.i(tag, "Successfully updated Android system clipboard with text from '${payload.sourceDeviceId}'")
                }
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
