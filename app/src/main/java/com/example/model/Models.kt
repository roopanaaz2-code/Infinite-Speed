package com.example.model

enum class FileCategory(val displayName: String) {
    ALL("All Files"),
    PHOTO("Photos"),
    VIDEO("Videos"),
    AUDIO("Music"),
    DOCUMENT("Documents"),
    APP("Apps (APK)"),
    ARCHIVE("Archives"),
    OTHER("Other")
}

data class FileItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val category: FileCategory,
    val dateModified: Long = System.currentTimeMillis(),
    val packageName: String? = null, // For APK apps
    val isSelected: Boolean = false
) {
    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            val value = size / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups.coerceIn(0, units.size - 1)])
        }
}

data class PeerDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 8990,
    val deviceType: String = "Android",
    val isPaired: Boolean = false,
    val connectionType: String = "Wi-Fi LAN",
    val lastSeen: Long = System.currentTimeMillis(),
    val pairingPin: String? = null
)

enum class TransferDirection {
    SEND,
    RECEIVE
}

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
