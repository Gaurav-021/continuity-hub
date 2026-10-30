package com.continuityhub.model

data class FileTransferItem(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val bytesTransferred: Long,
    val isIncoming: Boolean,
    val isCompleted: Boolean,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val localPath: String? = null,
    val peerName: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progressPercentage: Float
        get() = if (fileSize > 0) (bytesTransferred.toFloat() / fileSize).coerceIn(0f, 1f) else 0f

    val formattedSize: String
        get() = formatBytes(fileSize)

    val formattedProgress: String
        get() = "${formatBytes(bytesTransferred)} / ${formatBytes(fileSize)}"

    companion object {
        fun formatBytes(bytes: Long): String {
            val kb = 1024L
            val mb = kb * 1024
            val gb = mb * 1024
            return when {
                bytes >= gb -> "%.2f GB".format(bytes.toDouble() / gb)
                bytes >= mb -> "%.2f MB".format(bytes.toDouble() / mb)
                bytes >= kb -> "%.1f KB".format(bytes.toDouble() / kb)
                else -> "$bytes B"
            }
        }
    }
}
