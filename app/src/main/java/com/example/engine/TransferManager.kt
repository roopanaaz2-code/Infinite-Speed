package com.example.engine

import android.content.Context
import android.os.Build
import com.example.data.TransferEntity
import com.example.data.TransferRepository
import com.example.model.FileItem
import com.example.model.PeerDevice
import com.example.model.TransferDirection
import com.example.model.TransferStatus
import com.example.network.ClientTransferProgress
import com.example.network.DiscoveryEngine
import com.example.network.IncomingTransferRequest
import com.example.network.TransferClient
import com.example.network.TransferServer
import com.example.security.SecurityUtils
import com.example.service.TransferForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ActiveTransferUiState(
    val isTransferring: Boolean = false,
    val direction: TransferDirection = TransferDirection.SEND,
    val peerName: String = "",
    val currentFileName: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileBytes: Long = 0L,
    val currentFileTotalBytes: Long = 0L,
    val overallBytesTransferred: Long = 0L,
    val overallTotalBytes: Long = 0L,
    val currentSpeedBps: Long = 0L,
    val averageSpeedBps: Long = 0L,
    val estimatedTimeRemainingSec: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val isVerified: Boolean = false,
    val errorMessage: String? = null
) {
    val progressPercent: Float
        get() = if (overallTotalBytes > 0) {
            (overallBytesTransferred.toFloat() / overallTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else if (currentFileTotalBytes > 0) {
            (currentFileBytes.toFloat() / currentFileTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val formattedSpeed: String
        get() {
            val mbps = currentSpeedBps.toDouble() / (1024.0 * 1024.0)
            return if (mbps >= 1.0) {
                String.format(java.util.Locale.US, "%.1f MB/s", mbps)
            } else {
                val kbps = currentSpeedBps.toDouble() / 1024.0
                String.format(java.util.Locale.US, "%.0f KB/s", kbps)
            }
        }

    val formattedEta: String
        get() {
            if (estimatedTimeRemainingSec <= 0) return "--"
            val mins = estimatedTimeRemainingSec / 60
            val secs = estimatedTimeRemainingSec % 60
            return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        }
}

class TransferManager(
    private val context: Context,
    private val repository: TransferRepository,
    private val scope: CoroutineScope
) {
    val deviceName: String = "Infinite-${Build.MODEL.replace(" ", "")}"
    val pairingPin: String = SecurityUtils.generatePairingPin()
    val deviceId: String = UUID.randomUUID().toString().take(8)

    val server = TransferServer(context, scope, deviceName = deviceName, requiredPin = pairingPin)
    val client = TransferClient(context)
    val discovery = DiscoveryEngine(context, scope)

    private val _activeTransferState = MutableStateFlow(ActiveTransferUiState())
    val activeTransferState: StateFlow<ActiveTransferUiState> = _activeTransferState.asStateFlow()

    private var currentTransferJob: Job? = null
    private var transferStartTime: Long = 0L

    init {
        // Observe server transfer progress
        scope.launch {
            server.transferProgress.collect { p ->
                if (p != null) {
                    val speed = p.speedBps
                    val durationSec = ((System.currentTimeMillis() - transferStartTime) / 1000L).coerceAtLeast(1)
                    val avgSpeed = if (durationSec > 0) p.bytesTransferred / durationSec else speed
                    val remainingBytes = (p.totalBytes - p.bytesTransferred).coerceAtLeast(0)
                    val eta = if (speed > 0) remainingBytes / speed else 0L

                    _activeTransferState.value = ActiveTransferUiState(
                        isTransferring = p.status == TransferStatus.IN_PROGRESS,
                        direction = TransferDirection.RECEIVE,
                        peerName = "Sender Device",
                        currentFileName = p.fileName,
                        currentFileIndex = 1,
                        totalFiles = 1,
                        currentFileBytes = p.bytesTransferred,
                        currentFileTotalBytes = p.totalBytes,
                        overallBytesTransferred = p.bytesTransferred,
                        overallTotalBytes = p.totalBytes,
                        currentSpeedBps = speed,
                        averageSpeedBps = avgSpeed,
                        estimatedTimeRemainingSec = eta,
                        status = p.status,
                        isVerified = p.isVerified,
                        errorMessage = p.error
                    )

                    if (p.status == TransferStatus.IN_PROGRESS) {
                        val progressInt = if (p.totalBytes > 0) ((p.bytesTransferred * 100) / p.totalBytes).toInt() else 0
                        TransferForegroundService.updateProgress(context, p.fileName, progressInt, _activeTransferState.value.formattedSpeed)
                    } else if (p.status == TransferStatus.COMPLETED || p.status == TransferStatus.FAILED) {
                        TransferForegroundService.stop(context)
                        // Save to Room DB
                        repository.recordTransfer(
                            TransferEntity(
                                transferId = p.fileId,
                                fileName = p.fileName,
                                filePath = p.fileName,
                                fileSize = p.totalBytes,
                                mimeType = "application/octet-stream",
                                direction = TransferDirection.RECEIVE,
                                peerName = "Sender Device",
                                peerAddress = server.serverSocketAddress(),
                                status = p.status,
                                bytesTransferred = p.bytesTransferred,
                                speedBytesPerSec = avgSpeed,
                                isVerified = p.isVerified,
                                errorMessage = p.error
                            )
                        )
                    }
                }
            }
        }
    }

    private fun TransferServer.serverSocketAddress(): String = "127.0.0.1"

    fun startReceiverMode(port: Int = 8990) {
        transferStartTime = System.currentTimeMillis()
        server.start(port)
        discovery.startBroadcasting(deviceName, deviceId, port)
    }

    fun stopReceiverMode() {
        server.stop()
        discovery.stopBroadcasting()
    }

    fun startSendingFiles(peer: PeerDevice, files: List<FileItem>, onComplete: (Boolean) -> Unit) {
        if (files.isEmpty()) return

        transferStartTime = System.currentTimeMillis()
        val totalSize = files.sumOf { it.size }

        _activeTransferState.value = ActiveTransferUiState(
            isTransferring = true,
            direction = TransferDirection.SEND,
            peerName = peer.name,
            currentFileName = files.first().name,
            currentFileIndex = 1,
            totalFiles = files.size,
            overallBytesTransferred = 0L,
            overallTotalBytes = totalSize,
            status = TransferStatus.IN_PROGRESS
        )

        TransferForegroundService.start(context, files.first().name)

        currentTransferJob = scope.launch(Dispatchers.IO) {
            val sessionToken = SecurityUtils.generateSessionToken()

            val pairedSessionToken = if (!peer.pairingPin.isNullOrBlank()) {
                client.pairWithPin(peer, peer.pairingPin, sessionToken).getOrNull()
            } else null
            if (peer.pairingPin != null && pairedSessionToken == null) {
                _activeTransferState.value = _activeTransferState.value.copy(
                    isTransferring = false,
                    status = TransferStatus.FAILED,
                    errorMessage = "QR pairing failed: incorrect PIN or receiver unavailable"
                )
                return@launch
            }

            val result = client.sendFiles(
                peer = peer,
                files = files,
                senderName = deviceName,
                sessionToken = pairedSessionToken ?: sessionToken
            ) { progress ->
                val durationSec = ((System.currentTimeMillis() - transferStartTime) / 1000L).coerceAtLeast(1)
                val avgSpeed = if (durationSec > 0) progress.overallBytesTransferred / durationSec else progress.speedBps
                val remainingBytes = (progress.overallTotalBytes - progress.overallBytesTransferred).coerceAtLeast(0)
                val eta = if (progress.speedBps > 0) remainingBytes / progress.speedBps else 0L

                val progressInt = if (progress.overallTotalBytes > 0) {
                    ((progress.overallBytesTransferred * 100) / progress.overallTotalBytes).toInt()
                } else 0

                _activeTransferState.value = ActiveTransferUiState(
                    isTransferring = progress.status == TransferStatus.IN_PROGRESS,
                    direction = TransferDirection.SEND,
                    peerName = peer.name,
                    currentFileName = progress.fileName,
                    currentFileIndex = progress.fileIndex,
                    totalFiles = progress.totalFiles,
                    currentFileBytes = progress.bytesTransferred,
                    currentFileTotalBytes = progress.totalBytes,
                    overallBytesTransferred = progress.overallBytesTransferred,
                    overallTotalBytes = progress.overallTotalBytes,
                    currentSpeedBps = progress.speedBps,
                    averageSpeedBps = avgSpeed,
                    estimatedTimeRemainingSec = eta,
                    status = progress.status,
                    isVerified = true,
                    errorMessage = progress.errorMessage
                )

                TransferForegroundService.updateProgress(
                    context,
                    progress.fileName,
                    progressInt,
                    _activeTransferState.value.formattedSpeed
                )
            }

            TransferForegroundService.stop(context)

            val isSuccess = result.isSuccess
            // Save records to Room DB
            val avgSpeed = _activeTransferState.value.averageSpeedBps
            for (f in files) {
                repository.recordTransfer(
                    TransferEntity(
                        transferId = UUID.randomUUID().toString(),
                        fileName = f.name,
                        filePath = f.path,
                        fileSize = f.size,
                        mimeType = f.mimeType,
                        direction = TransferDirection.SEND,
                        peerName = peer.name,
                        peerAddress = peer.ipAddress,
                        status = if (isSuccess) TransferStatus.COMPLETED else TransferStatus.FAILED,
                        bytesTransferred = f.size,
                        speedBytesPerSec = avgSpeed,
                        isVerified = true,
                        errorMessage = result.exceptionOrNull()?.message
                    )
                )
            }

            _activeTransferState.value = _activeTransferState.value.copy(
                isTransferring = false,
                status = if (isSuccess) TransferStatus.COMPLETED else TransferStatus.FAILED,
                errorMessage = result.exceptionOrNull()?.message
            )

            scope.launch(Dispatchers.Main) {
                onComplete(isSuccess)
            }
        }
    }

    fun pauseTransfer() {
        client.pause()
        _activeTransferState.value = _activeTransferState.value.copy(status = TransferStatus.PAUSED)
    }

    fun resumeTransfer() {
        client.resume()
        _activeTransferState.value = _activeTransferState.value.copy(status = TransferStatus.IN_PROGRESS)
    }

    fun cancelTransfer() {
        client.cancel()
        currentTransferJob?.cancel()
        currentTransferJob = null
        TransferForegroundService.stop(context)
        _activeTransferState.value = _activeTransferState.value.copy(
            isTransferring = false,
            status = TransferStatus.CANCELLED
        )
    }
}
