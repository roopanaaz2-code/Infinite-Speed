package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.TransferRepository
import com.example.engine.TransferManager
import com.example.storage.MediaScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class InfiniteSpeedApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { TransferRepository(database.transferDao()) }
    val transferManager by lazy { TransferManager(this, repository, applicationScope) }
    val mediaScanner by lazy { MediaScanner(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: InfiniteSpeedApplication
            private set
    }
}
