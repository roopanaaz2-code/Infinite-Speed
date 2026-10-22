package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.TransferDirection
import com.example.model.TransferStatus

@Entity(tableName = "transfer_history")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val transferId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val direction: TransferDirection,
    val peerName: String,
    val peerAddress: String,
    val status: TransferStatus,
    val bytesTransferred: Long = 0L,
    val sha256Checksum: String = "",
    val speedBytesPerSec: Long = 0L,
    val isVerified: Boolean = false,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progressPercent: Float
        get() = if (fileSize > 0) (bytesTransferred.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedSpeed: String
        get() {
            val mbps = speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
            return if (mbps >= 1.0) {
                String.format(java.util.Locale.US, "%.1f MB/s", mbps)
            } else {
                val kbps = speedBytesPerSec.toDouble() / 1024.0
                String.format(java.util.Locale.US, "%.0f KB/s", kbps)
            }
        }

    val formattedSize: String
        get() {
            if (fileSize <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(fileSize.toDouble()) / Math.log10(1024.0)).toInt()
            val value = fileSize / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups.coerceIn(0, units.size - 1)])
        }
}
