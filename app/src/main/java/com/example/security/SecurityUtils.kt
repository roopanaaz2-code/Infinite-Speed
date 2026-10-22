package com.example.security

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object SecurityUtils {

    private val secureRandom = SecureRandom()

    fun generatePairingPin(): String {
        val pin = secureRandom.nextInt(900000) + 100000
        return pin.toString()
    }

    fun generateSessionToken(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    fun calculateSha256(inputStream: InputStream, onProgress: ((bytesRead: Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024) // 128KB buffer
        var read: Int
        var totalRead = 0L

        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
            totalRead += read
            onProgress?.invoke(totalRead)
        }

        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun calculateFileSha256(file: File): String {
        if (!file.exists() || !file.canRead()) return ""
        return file.inputStream().use { calculateSha256(it) }
    }

    /**
     * Sanitizes file names to strictly avoid path traversal (e.g., ../ or /etc/passwd)
     */
    fun sanitizeFileName(rawName: String): String {
        val normalized = rawName.replace('\\', '/')
        var clean = File(normalized).name // Strips any directory structure
        clean = clean.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        clean = clean.trim()
        if (clean.isEmpty() || clean == "." || clean == "..") {
            clean = "received_file_${System.currentTimeMillis()}"
        }
        return clean
    }
}
