package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer_history WHERE status = 'IN_PROGRESS' OR status = 'QUEUED' ORDER BY timestamp ASC")
    fun getActiveTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC LIMIT 5")
    fun getRecentTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer_history WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransferById(transferId: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity): Long

    @Update
    suspend fun update(transfer: TransferEntity)

    @Query("UPDATE transfer_history SET bytesTransferred = :bytes, speedBytesPerSec = :speed, status = :status WHERE transferId = :transferId")
    suspend fun updateProgress(transferId: String, bytes: Long, speed: Long, status: String)

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()
}
