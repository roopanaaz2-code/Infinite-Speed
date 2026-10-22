package com.example.data

import com.example.model.TransferStatus
import kotlinx.coroutines.flow.Flow

class TransferRepository(private val dao: TransferDao) {
    val allTransfers: Flow<List<TransferEntity>> = dao.getAllTransfers()
    val activeTransfers: Flow<List<TransferEntity>> = dao.getActiveTransfers()
    val recentTransfers: Flow<List<TransferEntity>> = dao.getRecentTransfers()

    suspend fun getTransfer(transferId: String): TransferEntity? = dao.getTransferById(transferId)

    suspend fun recordTransfer(transfer: TransferEntity): Long = dao.insert(transfer)

    suspend fun updateTransfer(transfer: TransferEntity) = dao.update(transfer)

    suspend fun updateProgress(transferId: String, bytes: Long, speed: Long, status: TransferStatus) {
        dao.updateProgress(transferId, bytes, speed, status.name)
    }

    suspend fun deleteTransfer(id: Long) = dao.deleteById(id)

    suspend fun clearHistory() = dao.clearAll()
}
