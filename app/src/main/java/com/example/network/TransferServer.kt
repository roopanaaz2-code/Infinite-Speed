package com.example.network

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.model.FileItem
import com.example.model.TransferDirection
import com.example.model.TransferStatus
import com.example.security.SecurityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

data class IncomingTransferRequest(
    val senderName: String,
    val senderIp: String,
    val files: List<IncomingFileInfo>,
    val totalSize: Long,
    val sessionToken: String
)

data class IncomingFileInfo(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val expectedSha256: String
)

data class ServerTransferProgress(
    val fileId: String,
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val status: TransferStatus,
    val isVerified: Boolean = false,
    val error: String? = null
)

class TransferServer(
    private val context: Context,
    private val scope: CoroutineScope,
    var deviceName: String = "Infinite Speed Peer",
    var requiredPin: String = "123456",
    var autoAccept: Boolean = false
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _incomingRequests = MutableSharedFlow<IncomingTransferRequest>(extraBufferCapacity = 10)
    val incomingRequests: SharedFlow<IncomingTransferRequest> = _incomingRequests.asSharedFlow()

    private val _transferProgress = MutableStateFlow<ServerTransferProgress?>(null)
    val transferProgress: StateFlow<ServerTransferProgress?> = _transferProgress.asStateFlow()

    // Host files ready for download (e.g. for Web Share or Receiver-pull transfers)
    private val sharedFilesMap = mutableMapOf<String, FileItem>()
    private val acceptedSessions = mutableSetOf<String>()

    fun setSharedFiles(files: List<FileItem>) {
        synchronized(sharedFilesMap) {
            sharedFilesMap.clear()
            files.forEach { sharedFilesMap[it.id] = it }
        }
    }

    fun approveSession(token: String) {
        synchronized(acceptedSessions) {
            acceptedSessions.add(token)
        }
    }

    private fun isSessionApproved(token: String): Boolean = token.isNotBlank() && synchronized(acceptedSessions) {
        acceptedSessions.contains(token)
    }

    fun start(port: Int = 8990) {
        if (_isRunning.value) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                _isRunning.value = true

                while (isActive) {
                    val clientSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    scope.launch(Dispatchers.IO) {
                        handleClient(clientSocket)
                    }
                }
            } catch (_: Exception) {
            } finally {
                _isRunning.value = false
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 300000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return socket.close()
            val tokens = requestLine.split(" ")
            if (tokens.size < 2) return socket.close()

            val method = tokens[0].uppercase()
            val rawPath = tokens[1]
            val path = rawPath.substringBefore("?")
            val query = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isBlank()) break
                val colon = line.indexOf(":")
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            when {
                // Handshake API
                method == "GET" && path == "/api/handshake" -> {
                    val json = JSONObject().apply {
                        put("status", "ok")
                        put("name", deviceName)
                        put("version", "1.0.0")
                        put("requirePin", requiredPin.isNotBlank())
                        put("ip", NetworkUtils.getLocalIpAddress())
                    }
                    sendJsonResponse(output, 200, json.toString())
                }

                // PIN / Pairing verification
                method == "POST" && path == "/api/pair" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readBody(input, contentLength)
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val pin = json.optString("pin")
                    val token = json.optString("token", UUID.randomUUID().toString())

                    if (pin == requiredPin || requiredPin.isBlank()) {
                        approveSession(token)
                        val resp = JSONObject().apply {
                            put("success", true)
                            put("token", token)
                            put("deviceName", deviceName)
                        }
                        sendJsonResponse(output, 200, resp.toString())
                    } else {
                        val resp = JSONObject().apply {
                            put("success", false)
                            put("error", "Incorrect pairing PIN")
                        }
                        sendJsonResponse(output, 401, resp.toString())
                    }
                }

                // Transfer Manifest (Sender announces files)
                method == "POST" && path == "/api/manifest" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readBody(input, contentLength)
                    val json = JSONObject(body)
                    val senderName = json.optString("senderName", "Unknown")
                    val sessionToken = json.optString("sessionToken", UUID.randomUUID().toString())
                    val filesArray = json.optJSONArray("files") ?: JSONArray()

                    val fileList = mutableListOf<IncomingFileInfo>()
                    var totalSize = 0L

                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        val fileId = item.getString("id")
                        val fileName = SecurityUtils.sanitizeFileName(item.getString("name"))
                        val size = item.getLong("size")
                        val mime = item.optString("mimeType", "application/octet-stream")
                        val sha256 = item.optString("sha256", "")

                        totalSize += size
                        fileList.add(IncomingFileInfo(fileId, fileName, size, mime, sha256))
                    }

                    val request = IncomingTransferRequest(
                        senderName = senderName,
                        senderIp = socket.inetAddress.hostAddress ?: "",
                        files = fileList,
                        totalSize = totalSize,
                        sessionToken = sessionToken
                    )

                    if (autoAccept || isSessionApproved(sessionToken)) {
                        approveSession(sessionToken)
                        sendJsonResponse(output, 200, JSONObject().put("status", "accepted").toString())
                    } else {
                        _incomingRequests.tryEmit(request)
                        sendJsonResponse(output, 200, JSONObject().put("status", "pending_user_approval").toString())
                    }
                }

                // File Upload (Sender streams file to this Receiver)
                method == "POST" && path == "/api/upload" -> {
                    val token = headers["x-session-token"].orEmpty()
                    if (!isSessionApproved(token)) {
                        sendJsonResponse(output, 403, JSONObject().put("error", "Transfer session is not approved").toString())
                    } else {
                        handleFileUpload(input, output, headers)
                    }
                }

                // Browser upload to this device over the local Wi-Fi connection.
                method == "POST" && path == "/api/web-upload" -> handleFileUpload(input, output, headers)

                // Sender polls while the user reviews the incoming transfer dialog.
                method == "GET" && path == "/api/session" -> {
                    val token = queryParams(query)["token"].orEmpty()
                    sendJsonResponse(output, 200, JSONObject().put("approved", isSessionApproved(token)).toString())
                }

                // File Download (Recipient or Web browser downloads file from this device)
                method == "GET" && path == "/api/download" -> {
                    val fileId = queryParams(query)["fileId"] ?: ""
                    handleFileDownload(output, fileId, headers)
                }

                // Web Share Interface (HTML5 Portal for browser transfers without app)
                method == "GET" && (path == "/web" || path == "/") -> {
                    serveWebPortal(output)
                }

                // Ping for speed test & latency
                method == "GET" && path == "/api/ping" -> {
                    sendJsonResponse(output, 200, JSONObject().put("status", "pong").put("time", System.currentTimeMillis()).toString())
                }

                else -> {
                    sendRawResponse(output, 404, "Not Found", "text/plain", "Endpoint not found".toByteArray())
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleFileUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val fileNameRaw = headers["x-file-name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "transfer_${System.currentTimeMillis()}"
        val fileName = SecurityUtils.sanitizeFileName(fileNameRaw)
        val fileId = headers["x-file-id"] ?: UUID.randomUUID().toString()
        val expectedSha256 = headers["x-file-sha256"] ?: ""
        val contentLength = headers["x-file-size"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: headers["content-length"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: 0L
        if (contentLength <= 0L) {
            sendJsonResponse(output, 411, JSONObject().put("error", "Missing upload length").toString())
            return
        }

        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InfiniteSpeed")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        var destFile = File(downloadDir, fileName)
        var counter = 1
        val nameWithoutExt = fileName.substringBeforeLast(".")
        val ext = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ""
        while (destFile.exists()) {
            destFile = File(downloadDir, "${nameWithoutExt}_$counter$ext")
            counter++
        }

        val mediaUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, headers["content-type"] ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InfiniteSpeed")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else null

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024) // 128KB buffer for max throughput
        var bytesReadTotal = 0L
        val startTime = System.currentTimeMillis()
        var lastSpeedCalcTime = startTime
        var lastBytes = 0L
        var currentSpeed = 0L

        try {
            val destination = mediaUri?.let { context.contentResolver.openOutputStream(it) }
                ?: FileOutputStream(destFile)
            destination.use { fos ->
                var read: Int
                while (bytesReadTotal < contentLength) {
                    val toRead = minOf(buffer.size.toLong(), contentLength - bytesReadTotal).toInt()
                    read = input.read(buffer, 0, toRead)
                    if (read == -1) break

                    fos.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesReadTotal += read

                    val now = System.currentTimeMillis()
                    if (now - lastSpeedCalcTime >= 500) {
                        val durationSec = (now - lastSpeedCalcTime) / 1000.0
                        val deltaBytes = bytesReadTotal - lastBytes
                        currentSpeed = (deltaBytes / durationSec).toLong()
                        lastBytes = bytesReadTotal
                        lastSpeedCalcTime = now

                        _transferProgress.value = ServerTransferProgress(
                            fileId = fileId,
                            fileName = destFile.name,
                            bytesTransferred = bytesReadTotal,
                            totalBytes = contentLength,
                            speedBps = currentSpeed,
                            status = TransferStatus.IN_PROGRESS
                        )
                    }
                }
                fos.flush()
            }

            if (bytesReadTotal != contentLength) {
                throw java.io.IOException("Incomplete upload: received $bytesReadTotal of $contentLength bytes")
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val isVerified = expectedSha256.isEmpty() || expectedSha256.equals(actualSha256, ignoreCase = true)

            if (!isVerified) {
                mediaUri?.let { context.contentResolver.delete(it, null, null) }
                if (mediaUri == null) destFile.delete()
                _transferProgress.value = ServerTransferProgress(
                    fileId = fileId,
                    fileName = destFile.name,
                    bytesTransferred = bytesReadTotal,
                    totalBytes = contentLength,
                    speedBps = 0,
                    status = TransferStatus.FAILED,
                    isVerified = false,
                    error = "SHA-256 integrity mismatch"
                )
                sendJsonResponse(output, 200, JSONObject().put("success", false).put("verified", false).put("error", "SHA-256 integrity mismatch").toString())
                return
            }

            if (mediaUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(mediaUri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }

            _transferProgress.value = ServerTransferProgress(
                fileId = fileId,
                fileName = destFile.name,
                bytesTransferred = bytesReadTotal,
                totalBytes = contentLength,
                speedBps = 0,
                status = TransferStatus.COMPLETED,
                isVerified = isVerified,
                error = if (isVerified) null else "SHA-256 integrity mismatch"
            )

            val resp = JSONObject().apply {
                put("success", isVerified)
                put("bytesReceived", bytesReadTotal)
                put("sha256", actualSha256)
                put("verified", isVerified)
                put("savedPath", mediaUri?.toString() ?: destFile.absolutePath)
            }
            sendJsonResponse(output, 200, resp.toString())
        } catch (e: Exception) {
            mediaUri?.let { context.contentResolver.delete(it, null, null) }
            if (mediaUri == null) destFile.delete()
            _transferProgress.value = ServerTransferProgress(
                fileId = fileId,
                fileName = destFile.name,
                bytesTransferred = bytesReadTotal,
                totalBytes = contentLength,
                speedBps = 0,
                status = TransferStatus.FAILED,
                error = e.message
            )
            sendJsonResponse(output, 500, JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleFileDownload(output: OutputStream, fileId: String, headers: Map<String, String>) {
        val fileItem = synchronized(sharedFilesMap) { sharedFilesMap[fileId] }
        if (fileItem == null) {
            sendRawResponse(output, 404, "Not Found", "text/plain", "File not found".toByteArray())
            return
        }

        try {
            val inputStream: InputStream = if (fileItem.path.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(fileItem.path)) ?: run {
                    sendRawResponse(output, 404, "Not Found", "text/plain", "Cannot open stream".toByteArray())
                    return
                }
            } else {
                val f = File(fileItem.path)
                if (!f.exists()) {
                    sendRawResponse(output, 404, "Not Found", "text/plain", "File not found on disk".toByteArray())
                    return
                }
                FileInputStream(f)
            }

            inputStream.use { fis ->
                val fileSize = fileItem.size
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
                headerBuilder.append("Content-Type: ${fileItem.mimeType}\r\n")
                headerBuilder.append("Content-Length: $fileSize\r\n")
                headerBuilder.append("Content-Disposition: attachment; filename=\"${fileItem.name}\"\r\n")
                headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                headerBuilder.append("Connection: close\r\n\r\n")

                output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))

                val buffer = ByteArray(128 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun serveWebPortal(output: OutputStream) {
        val files = synchronized(sharedFilesMap) { sharedFilesMap.values.toList() }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>Infinite Speed - Web Share</title>")
        sb.append("<style>")
        sb.append("body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#080d1a;color:#f8fafc;padding:20px;}")
        sb.append(".card{max-width:700px;margin:0 auto;background:#0f172a;border:1px solid #1e293b;border-radius:16px;padding:24px;box-shadow:0 10px 30px rgba(0,0,0,0.5);}")
        sb.append(".header{display:flex;align-items:center;gap:12px;margin-bottom:20px;}")
        sb.append(".logo{width:40px;height:40px;border-radius:8px;background:#00e5ff;display:flex;align-items:center;justify-content:center;font-weight:bold;color:#080d1a;font-size:20px;}")
        sb.append(".badge{background:#00e5ff22;color:#00e5ff;border:1px solid #00e5ff55;padding:4px 10px;border-radius:99px;font-size:12px;font-weight:600;}")
        sb.append(".file-row{display:flex;align-items:center;justify-content:space-between;padding:12px;background:#14203d;margin-bottom:8px;border-radius:10px;}")
        sb.append(".btn{background:#00e5ff;color:#080d1a;text-decoration:none;padding:8px 16px;border-radius:8px;font-weight:700;font-size:14px;display:inline-block;}")
        sb.append(".meta{color:#94a3b8;font-size:12px;}")
        sb.append("</style></head><body>")
        sb.append("<div class='card'>")
        sb.append("<div class='header'><div class='logo'>&infin;</div><div><h2 style='margin:0;'>Infinite Speed</h2><span class='meta'>Direct Web Share &bull; Local High-Speed Delivery</span></div></div>")
        sb.append("<p>Shared files from <b>$deviceName</b>. Tap below to download directly over local Wi-Fi without internet.</p>")
        sb.append("<div class='file-row'><div><div style='font-weight:600;'>Send files to this phone</div><div class='meta'>Choose files, then upload over the local Wi-Fi connection.</div></div><input id='filePicker' type='file' multiple onchange='uploadFiles(this.files)' /></div>")
        sb.append("<div id='uploadStatus' class='meta'></div>")

        if (files.isEmpty()) {
            sb.append("<div style='text-align:center;padding:40px;color:#94a3b8;'>No files are currently queued for sharing. Select files in the app to share with this browser.</div>")
        } else {
            for (f in files) {
                sb.append("<div class='file-row'>")
                sb.append("<div><div style='font-weight:600;'>${f.name}</div><div class='meta'>${f.formattedSize} &bull; ${f.category.displayName}</div></div>")
                sb.append("<a class='btn' href='/api/download?fileId=${f.id}'>Download</a>")
                sb.append("</div>")
            }
        }
        sb.append("<script>async function uploadFiles(files){const s=document.getElementById('uploadStatus');for(const f of files){s.textContent='Uploading '+f.name+'...';const r=await fetch('/api/web-upload',{method:'POST',headers:{'X-File-Name':encodeURIComponent(f.name),'Content-Type':f.type||'application/octet-stream'},body:f});if(!r.ok){s.textContent='Upload failed for '+f.name;return;}}s.textContent='Upload complete. Files are saved in Download/InfiniteSpeed.';}</script>")
        sb.append("</div></body></html>")

        val htmlBytes = sb.toString().toByteArray(Charsets.UTF_8)
        sendRawResponse(output, 200, "OK", "text/html; charset=utf-8", htmlBytes)
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var total = 0
        while (total < length) {
            val r = input.read(bytes, total, length - total)
            if (r == -1) break
            total += r
        }
        return String(bytes, 0, total, Charsets.UTF_8)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val current = input.read()
            if (current == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (current == '\n'.code) {
                val raw = bytes.toByteArray()
                val length = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, length, Charsets.ISO_8859_1)
            }
            bytes.write(current)
        }
    }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate {
            val parts = it.split("=")
            val key = parts[0]
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }

    private fun sendJsonResponse(output: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        sendRawResponse(output, status, if (status == 200) "OK" else "Error", "application/json", bytes)
    }

    private fun sendRawResponse(output: OutputStream, code: Int, reason: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }
}
