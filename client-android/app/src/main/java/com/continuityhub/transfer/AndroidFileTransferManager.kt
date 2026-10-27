package com.continuityhub.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.continuityhub.MainActivity
import com.continuityhub.model.FileTransferItem
import com.continuityhub.proto.Channel
import com.continuityhub.proto.FileChunk
import com.continuityhub.proto.FileTransferAck
import com.continuityhub.proto.FileTransferHeader
import com.continuityhub.proto.FileTransferMessage
import com.continuityhub.proto.Packet
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AndroidFileTransferManager(
    private val context: Context,
    private val myDeviceId: String,
    private val scope: CoroutineScope
) {
    private val tag = "AndroidFileTransfer"
    private val sequenceNumber = AtomicLong(0)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "continuity_file_transfers"
        const val CHUNK_SIZE = 64 * 1024 // 64 KB
    }

    private val continuityDir: File by lazy {
        val publicDownloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(publicDownloads, "Continuity")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private data class IncomingState(
        val header: FileTransferHeader,
        val partFile: File,
        val finalFile: File,
        val outputStream: FileOutputStream,
        val digest: MessageDigest,
        var bytesReceived: Long,
        val senderName: String
    )

    private val incomingTransfers = ConcurrentHashMap<String, IncomingState>()
    private val outgoingAcks = ConcurrentHashMap<String, KChannel<FileTransferAck>>()

    private val _transfersState = MutableStateFlow<List<FileTransferItem>>(emptyList())
    val transfersState = _transfersState.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Continuity File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and completion of incoming and outgoing files"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun wrapPacket(msg: FileTransferMessage): Packet {
        return Packet.newBuilder()
            .setSessionId(myDeviceId)
            .setSequenceNumber(sequenceNumber.incrementAndGet())
            .setChannel(Channel.CHANNEL_FILE_TRANSFER)
            .setPayload(msg.toByteString())
            .build()
    }

    fun handleIncomingMessage(
        msg: FileTransferMessage,
        peerDeviceId: String,
        peerDeviceName: String
    ): Packet? {
        when (msg.msgCase) {
            FileTransferMessage.MsgCase.HEADER -> {
                val header = msg.header
                Log.i(tag, "Incoming FileTransferHeader: '${header.fileName}' (${header.fileSize} bytes) from $peerDeviceName")

                val safeFileName = header.fileName.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
                val finalFile = File(continuityDir, safeFileName)
                val partFile = File(continuityDir, "${safeFileName}.${UUID.randomUUID().toString().take(8)}.part")

                try {
                    val fos = FileOutputStream(partFile)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val state = IncomingState(
                        header = header,
                        partFile = partFile,
                        finalFile = finalFile,
                        outputStream = fos,
                        digest = digest,
                        bytesReceived = 0,
                        senderName = peerDeviceName
                    )
                    incomingTransfers[header.transferId] = state

                    updateTransferItem(
                        FileTransferItem(
                            transferId = header.transferId,
                            fileName = safeFileName,
                            fileSize = header.fileSize,
                            bytesTransferred = 0,
                            isIncoming = true,
                            isCompleted = false,
                            localPath = finalFile.absolutePath,
                            peerName = peerDeviceName
                        )
                    )

                    val ack = FileTransferAck.newBuilder()
                        .setTransferId(header.transferId)
                        .setStatus(FileTransferAck.Status.STATUS_ACCEPTED)
                        .setLastReceivedChunk(0)
                        .setMessage("Ready for chunks")
                        .build()

                    return wrapPacket(FileTransferMessage.newBuilder().setAck(ack).build())
                } catch (e: Exception) {
                    Log.e(tag, "Failed to initialize incoming file transfer", e)
                    val ack = FileTransferAck.newBuilder()
                        .setTransferId(header.transferId)
                        .setStatus(FileTransferAck.Status.STATUS_REJECTED)
                        .setMessage(e.message ?: "Failed creating file")
                        .build()
                    return wrapPacket(FileTransferMessage.newBuilder().setAck(ack).build())
                }
            }

            FileTransferMessage.MsgCase.CHUNK -> {
                val chunk = msg.chunk
                val state = incomingTransfers[chunk.transferId] ?: return null

                try {
                    val bytes = chunk.data.toByteArray()
                    state.outputStream.write(bytes)
                    state.digest.update(bytes)
                    state.bytesReceived += bytes.size

                    updateTransferProgress(chunk.transferId, state.bytesReceived)

                    if (chunk.isLastChunk) {
                        state.outputStream.flush()
                        state.outputStream.close()

                        val calculatedHash = state.digest.digest().joinToString("") { "%02x".format(it) }
                        if (calculatedHash.equals(state.header.sha256Checksum, ignoreCase = true)) {
                            // Checksum matched!
                            if (state.partFile.renameTo(state.finalFile)) {
                                Log.i(tag, "File transfer completed and verified: '${state.finalFile.name}' (${state.bytesReceived} bytes)")
                                MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(state.finalFile.absolutePath),
                                    arrayOf(state.header.mimeType)
                                ) { path, uri ->
                                    Log.d(tag, "MediaScanner registered file: $path -> $uri")
                                }

                                markTransferCompleted(chunk.transferId)
                                showCompletionNotification(state.finalFile.name, state.senderName, state.finalFile)

                                val ack = FileTransferAck.newBuilder()
                                    .setTransferId(chunk.transferId)
                                    .setStatus(FileTransferAck.Status.STATUS_COMPLETED)
                                    .setMessage("Verified successfully")
                                    .build()
                                incomingTransfers.remove(chunk.transferId)
                                return wrapPacket(FileTransferMessage.newBuilder().setAck(ack).build())
                            } else {
                                state.partFile.delete()
                                markTransferFailed(chunk.transferId, "Failed renaming part file")
                            }
                        } else {
                            Log.e(tag, "Checksum mismatch! Expected: ${state.header.sha256Checksum}, Got: $calculatedHash")
                            state.partFile.delete()
                            markTransferFailed(chunk.transferId, "Checksum verification failed")

                            val ack = FileTransferAck.newBuilder()
                                .setTransferId(chunk.transferId)
                                .setStatus(FileTransferAck.Status.STATUS_CHECKSUM_FAILED)
                                .setMessage("Checksum mismatch")
                                .build()
                            incomingTransfers.remove(chunk.transferId)
                            return wrapPacket(FileTransferMessage.newBuilder().setAck(ack).build())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error handling chunk", e)
                    state.partFile.delete()
                    incomingTransfers.remove(chunk.transferId)
                    markTransferFailed(chunk.transferId, e.message ?: "Write error")
                }
                return null
            }

            FileTransferMessage.MsgCase.ACK -> {
                val ack = msg.ack
                scope.launch {
                    outgoingAcks[ack.transferId]?.send(ack)
                }
                return null
            }

            else -> return null
        }
    }

    suspend fun sendFile(
        uri: Uri,
        peerDeviceName: String,
        sendPacket: (Packet) -> Unit
    ): Result<String> {
        val contentResolver = context.contentResolver
        var fileName = "file"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val transferId = UUID.randomUUID().toString()

        Log.i(tag, "Preparing to send file: '$fileName' ($fileSize bytes) to $peerDeviceName")

        // 1. Calculate SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        } ?: return Result.failure(IllegalStateException("Cannot open input stream for URI"))

        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        val totalChunks = if (fileSize == 0L) 1L else (fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE

        val header = FileTransferHeader.newBuilder()
            .setTransferId(transferId)
            .setFileName(fileName)
            .setFileSize(fileSize)
            .setMimeType(mimeType)
            .setTotalChunks(totalChunks)
            .setChunkSize(CHUNK_SIZE)
            .setSha256Checksum(checksum)
            .setSourceDeviceId(myDeviceId)
            .build()

        val ackChannel = KChannel<FileTransferAck>(16)
        outgoingAcks[transferId] = ackChannel

        updateTransferItem(
            FileTransferItem(
                transferId = transferId,
                fileName = fileName,
                fileSize = fileSize,
                bytesTransferred = 0,
                isIncoming = false,
                isCompleted = false,
                peerName = peerDeviceName
            )
        )

        // Send Header
        val headerPacket = wrapPacket(FileTransferMessage.newBuilder().setHeader(header).build())
        sendPacket(headerPacket)

        // Wait for Accept Ack
        val acceptAck = withTimeoutOrNull(10000) { ackChannel.receive() }
        if (acceptAck == null || acceptAck.status != FileTransferAck.Status.STATUS_ACCEPTED) {
            outgoingAcks.remove(transferId)
            markTransferFailed(transferId, acceptAck?.message ?: "Peer rejected or timed out")
            return Result.failure(IllegalStateException("Transfer rejected: ${acceptAck?.message}"))
        }

        Log.i(tag, "Peer accepted file transfer. Streaming chunks...")

        // Stream Chunks
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var chunkIndex = 0L
            var offset = 0L
            var read: Int

            while (input.read(buffer).also { read = it } != -1) {
                val isLast = (offset + read >= fileSize) || read == 0

                val chunk = FileChunk.newBuilder()
                    .setTransferId(transferId)
                    .setChunkIndex(chunkIndex)
                    .setOffset(offset)
                    .setData(ByteString.copyFrom(buffer, 0, read))
                    .setIsLastChunk(isLast)
                    .build()

                val packet = wrapPacket(FileTransferMessage.newBuilder().setChunk(chunk).build())
                sendPacket(packet)

                offset += read
                chunkIndex++
                updateTransferProgress(transferId, offset)

                if (isLast) break
            }
        }

        // Wait for Completion Ack
        val finalAck = withTimeoutOrNull(30000) { ackChannel.receive() }
        outgoingAcks.remove(transferId)

        return if (finalAck?.status == FileTransferAck.Status.STATUS_COMPLETED) {
            Log.i(tag, "File transfer '$fileName' completed successfully!")
            markTransferCompleted(transferId)
            Result.success(transferId)
        } else {
            markTransferFailed(transferId, finalAck?.message ?: "Transfer verification failed")
            Result.failure(IllegalStateException("Transfer verification failed"))
        }
    }

    private fun updateTransferItem(item: FileTransferItem) {
        val current = _transfersState.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == item.transferId }
        if (index != -1) {
            current[index] = item
        } else {
            current.add(0, item)
        }
        _transfersState.value = current
    }

    private fun updateTransferProgress(transferId: String, bytes: Long) {
        val current = _transfersState.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == transferId }
        if (index != -1) {
            current[index] = current[index].copy(bytesTransferred = bytes)
            _transfersState.value = current
        }
    }

    private fun markTransferCompleted(transferId: String) {
        val current = _transfersState.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == transferId }
        if (index != -1) {
            current[index] = current[index].copy(
                isCompleted = true,
                bytesTransferred = current[index].fileSize
            )
            _transfersState.value = current
        }
    }

    private fun markTransferFailed(transferId: String, error: String) {
        val current = _transfersState.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == transferId }
        if (index != -1) {
            current[index] = current[index].copy(
                isFailed = true,
                errorMessage = error
            )
            _transfersState.value = current
        }
    }

    private fun showCompletionNotification(fileName: String, senderName: String, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            setDataAndType(uri, "*/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            file.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("File Received")
            .setContentText("'$fileName' received from $senderName")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(file.hashCode(), notification)
    }
}
