package com.example.network

import android.content.Context
import android.net.Uri
import com.example.model.FileItem
import com.example.model.PeerDevice
import com.example.model.TransferStatus
import com.example.security.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class ClientTransferProgress(
    val fileId: String,
    val fileName: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val overallBytesTransferred: Long,
    val overallTotalBytes: Long,
    val speedBps: Long,
    val status: TransferStatus,
    val errorMessage: String? = null
)

class TransferClient(
    private val context: Context
) {
    private var currentSessionToken: String = ""
    @Volatile
    private var isCancelled = false

    @Volatile
    private var isPaused = false

    fun cancel() {
        isCancelled = true
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    suspend fun verifyHandshake(peer: PeerDevice): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${peer.ipAddress}:${peer.port}/api/handshake")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                Result.success(true)
            } else {
                Result.failure(Exception("Handshake failed with HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairWithPin(peer: PeerDevice, pin: String, sessionToken: String = SecurityUtils.generateSessionToken()): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${peer.ipAddress}:${peer.port}/api/pair")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val json = JSONObject().apply {
                put("pin", pin)
                put("token", sessionToken)
            }

            conn.outputStream.use { it.write(json.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val respStr = conn.inputStream.bufferedReader().readText()
                val respJson = JSONObject(respStr)
                Result.success(respJson.optString("token"))
            } else {
                Result.failure(Exception("Pairing PIN incorrect or rejected"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendFiles(
        peer: PeerDevice,
        files: List<FileItem>,
        senderName: String,
        sessionToken: String,
        onProgress: (ClientTransferProgress) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        isCancelled = false
        isPaused = false
        currentSessionToken = sessionToken

        try {
            // 1. Send Transfer Manifest
            val manifestUrl = URL("http://${peer.ipAddress}:${peer.port}/api/manifest")
            val manifestConn = (manifestUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val filesArray = JSONArray()
            var overallTotalBytes = 0L
            for (f in files) {
                overallTotalBytes += f.size
                val item = JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("size", f.size)
                    put("mimeType", f.mimeType)
                    put("sha256", sha256ForFile(f))
                }
                filesArray.put(item)
            }

            val manifestPayload = JSONObject().apply {
                put("senderName", senderName)
                put("sessionToken", sessionToken)
                put("files", filesArray)
            }

            manifestConn.outputStream.use { it.write(manifestPayload.toString().toByteArray()) }
            val manifestCode = manifestConn.responseCode
            if (manifestCode != 200) {
                return@withContext Result.failure(Exception("Receiver rejected transfer manifest ($manifestCode)"))
            }
            val manifestResponse = manifestConn.inputStream.bufferedReader().use { it.readText() }
            val manifestStatus = JSONObject(manifestResponse).optString("status")
            if (manifestStatus == "pending_user_approval") {
                val approved = waitForApproval(peer, sessionToken)
                if (!approved) return@withContext Result.failure(Exception("Receiver did not approve the transfer"))
            }

            var overallBytesSent = 0L

            // 2. Transfer files sequentially
            for ((index, fileItem) in files.withIndex()) {
                if (isCancelled) {
                    onProgress(
                        ClientTransferProgress(
                            fileId = fileItem.id,
                            fileName = fileItem.name,
                            fileIndex = index + 1,
                            totalFiles = files.size,
                            bytesTransferred = 0,
                            totalBytes = fileItem.size,
                            overallBytesTransferred = overallBytesSent,
                            overallTotalBytes = overallTotalBytes,
                            speedBps = 0,
                            status = TransferStatus.CANCELLED
                        )
                    )
                    return@withContext Result.failure(Exception("Transfer cancelled by user"))
                }

                val transferResult = uploadSingleFile(
                    peer = peer,
                    file = fileItem,
                    fileIndex = index + 1,
                    totalFiles = files.size,
                    overallTotalBytes = overallTotalBytes,
                    getOverallBytesSent = { overallBytesSent },
                    onBytesChunkSent = { chunkLen, currentSpeed, fileBytesSent ->
                        overallBytesSent += chunkLen
                        onProgress(
                            ClientTransferProgress(
                                fileId = fileItem.id,
                                fileName = fileItem.name,
                                fileIndex = index + 1,
                                totalFiles = files.size,
                                bytesTransferred = fileBytesSent,
                                totalBytes = fileItem.size,
                                overallBytesTransferred = overallBytesSent,
                                overallTotalBytes = overallTotalBytes,
                                speedBps = currentSpeed,
                                status = TransferStatus.IN_PROGRESS
                            )
                        )
                    }
                )

                if (transferResult.isFailure) {
                    return@withContext transferResult
                }
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun uploadSingleFile(
        peer: PeerDevice,
        file: FileItem,
        fileIndex: Int,
        totalFiles: Int,
        overallTotalBytes: Long,
        getOverallBytesSent: () -> Long,
        onBytesChunkSent: (chunkLen: Long, currentSpeed: Long, fileBytesSent: Long) -> Unit
    ): Result<Boolean> {
        var inputStream: InputStream? = null
        var conn: HttpURLConnection? = null

        try {
            inputStream = if (file.path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(file.path))
                    ?: return Result.failure(Exception("Cannot open content URI: ${file.name}"))
            } else {
                val f = File(file.path)
                if (!f.exists() || !f.canRead()) {
                    return Result.failure(Exception("File unreadable: ${file.path}"))
                }
                FileInputStream(f)
            }
            val sourceLength = resolveSourceLength(file)

            val uploadUrl = URL("http://${peer.ipAddress}:${peer.port}/api/upload")
            conn = (uploadUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 300000
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(sourceLength)
                setRequestProperty("Content-Type", file.mimeType)
                setRequestProperty("Content-Length", sourceLength.toString())
                setRequestProperty("X-File-Size", sourceLength.toString())
                setRequestProperty("X-File-Name", URLEncoder.encode(file.name, "UTF-8"))
                setRequestProperty("X-File-Id", file.id)
                setRequestProperty("X-File-Sha256", sha256ForFile(file))
                setRequestProperty("X-Session-Token", currentSessionToken)
                setRequestProperty("Connection", "close")
            }

            val buffer = ByteArray(128 * 1024) // 128KB chunk for high throughput
            val digest = MessageDigest.getInstance("SHA-256")
            var fileBytesSent = 0L

            var lastCalcTime = System.currentTimeMillis()
            var lastSentBytes = 0L
            var currentSpeed = 0L

            conn.outputStream.use { outputStream ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        return Result.failure(Exception("Transfer cancelled"))
                    }
                    while (isPaused && !isCancelled) {
                        Thread.sleep(100)
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    fileBytesSent += bytesRead

                    val now = System.currentTimeMillis()
                    val deltaMs = now - lastCalcTime
                    if (deltaMs >= 400) {
                        val durationSec = deltaMs / 1000.0
                        val deltaBytes = fileBytesSent - lastSentBytes
                        currentSpeed = (deltaBytes / durationSec).toLong()
                        lastSentBytes = fileBytesSent
                        lastCalcTime = now
                    }

                    onBytesChunkSent(bytesRead.toLong(), currentSpeed, fileBytesSent)
                }
                outputStream.flush()
            }

            if (fileBytesSent != sourceLength) {
                return Result.failure(Exception("Source changed while reading: sent $fileBytesSent of $sourceLength bytes"))
            }

            val respCode = conn.responseCode
            if (respCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val verified = runCatching { JSONObject(response).optBoolean("verified", false) }.getOrDefault(false)
                return if (verified) Result.success(true)
                else Result.failure(Exception("Receiver rejected file integrity verification"))
            } else {
                val errorText = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                return Result.failure(Exception("Server returned status $respCode during upload${if (errorText.isNotBlank()) ": $errorText" else ""}"))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun waitForApproval(peer: PeerDevice, token: String): Boolean {
        repeat(150) {
            if (isCancelled) return false
            try {
                val url = URL("http://${peer.ipAddress}:${peer.port}/api/session?token=${URLEncoder.encode(token, "UTF-8")}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val approved = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optBoolean("approved")
                    if (approved) return true
                }
            } catch (_: Exception) { }
            Thread.sleep(1000)
        }
        return false
    }

    private fun sha256ForFile(file: FileItem): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = if (file.path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(file.path))
        } else {
            FileInputStream(File(file.path))
        } ?: throw IllegalStateException("Cannot open file: ${file.name}")
        input.use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveSourceLength(file: FileItem): Long {
        val discovered = if (file.path.startsWith("content://")) {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(file.path), "r")?.use { it.length }
        } else {
            File(file.path).length()
        }
        return if (discovered != null && discovered >= 0L) discovered else file.size
    }
}
