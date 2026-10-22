package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.InfiniteSpeedApplication
import com.example.data.TransferEntity
import com.example.engine.ActiveTransferUiState
import com.example.model.FileCategory
import com.example.model.FileItem
import com.example.model.PeerDevice
import com.example.network.IncomingTransferRequest
import com.example.network.NetworkUtils
import com.example.storage.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen {
    HOME,
    SEND_FILE_PICKER,
    RADAR_DISCOVERY,
    RECEIVE,
    ACTIVE_TRANSFER,
    HISTORY,
    GROUP_SHARE,
    WEB_SHARE,
    SETTINGS,
    TROUBLESHOOTING
}

enum class SortOption {
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC,
    DATE_DESC
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as InfiniteSpeedApplication
    private val transferManager = app.transferManager
    private val mediaScanner = app.mediaScanner
    private val repository = app.repository

    // Navigation Stack
    private val _screenStack = MutableStateFlow(listOf(Screen.HOME))
    val currentScreen: StateFlow<Screen> = MutableStateFlow(Screen.HOME).apply {
        viewModelScope.launch {
            _screenStack.collect { stack ->
                value = stack.lastOrNull() ?: Screen.HOME
            }
        }
    }

    // Storage info
    private val _storageInfo = MutableStateFlow(mediaScanner.getStorageStats())
    val storageInfo: StateFlow<MediaScanner.StorageInfo> = _storageInfo.asStateFlow()

    // File Browser State
    private val _currentCategory = MutableStateFlow(FileCategory.ALL)
    val currentCategory: StateFlow<FileCategory> = _currentCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.DATE_DESC)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _browserFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val browserFiles: StateFlow<List<FileItem>> = _browserFiles.asStateFlow()

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles.asStateFlow()

    // Selected files cart
    private val _selectedFiles = MutableStateFlow<Map<String, FileItem>>(emptyMap())
    val selectedFiles: StateFlow<Map<String, FileItem>> = _selectedFiles.asStateFlow()

    // Nearby Discovery
    val discoveredDevices: StateFlow<List<PeerDevice>> = transferManager.discovery.discoveredDevices
    val isScanning: StateFlow<Boolean> = transferManager.discovery.isScanning

    // Active Transfer State from TransferManager
    val activeTransfer: StateFlow<ActiveTransferUiState> = transferManager.activeTransferState

    // Transfer History from Room
    val transferHistory: StateFlow<List<TransferEntity>> = repository.allTransfers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTransfers: StateFlow<List<TransferEntity>> = repository.recentTransfers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Incoming Request Dialog State
    private val _incomingRequest = MutableStateFlow<IncomingTransferRequest?>(null)
    val incomingRequest: StateFlow<IncomingTransferRequest?> = _incomingRequest.asStateFlow()

    // Settings
    val deviceName: String = transferManager.deviceName
    val deviceId: String = transferManager.deviceId
    val pairingPin: String = transferManager.pairingPin
    val localIp: String get() = NetworkUtils.getLocalIpAddress()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    init {
        // Collect incoming requests from server
        viewModelScope.launch {
            transferManager.server.incomingRequests.collect { req ->
                _incomingRequest.value = req
            }
        }
        loadFilesForCategory(FileCategory.ALL)
    }

    fun navigateTo(screen: Screen) {
        val stack = _screenStack.value.toMutableList()
        if (stack.lastOrNull() != screen) {
            stack.add(screen)
            _screenStack.value = stack
        }
    }

    fun navigateBack(): Boolean {
        val stack = _screenStack.value.toMutableList()
        return if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            _screenStack.value = stack
            true
        } else {
            false
        }
    }

    fun setCategory(category: FileCategory) {
        _currentCategory.value = category
        loadFilesForCategory(category)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        loadFilesForCategory(_currentCategory.value, query)
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
        applySorting()
    }

    fun loadFilesForCategory(category: FileCategory, query: String = _searchQuery.value) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingFiles.value = true
            val items = mediaScanner.queryFilesByCategory(category, query)
            _browserFiles.value = sortItems(items, _sortOption.value)
            _isLoadingFiles.value = false
            _storageInfo.value = mediaScanner.getStorageStats()
        }
    }

    fun refreshFiles() {
        loadFilesForCategory(_currentCategory.value, _searchQuery.value)
    }

    private fun applySorting() {
        _browserFiles.value = sortItems(_browserFiles.value, _sortOption.value)
    }

    private fun sortItems(items: List<FileItem>, sort: SortOption): List<FileItem> {
        return when (sort) {
            SortOption.NAME_ASC -> items.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
            SortOption.SIZE_DESC -> items.sortedByDescending { it.size }
            SortOption.SIZE_ASC -> items.sortedBy { it.size }
            SortOption.DATE_DESC -> items.sortedByDescending { it.dateModified }
        }
    }

    fun toggleFileSelection(file: FileItem) {
        val current = _selectedFiles.value.toMutableMap()
        if (current.containsKey(file.id)) {
            current.remove(file.id)
        } else {
            current[file.id] = file.copy(isSelected = true)
        }
        _selectedFiles.value = current
    }

    fun selectAllFiles() {
        val current = _selectedFiles.value.toMutableMap()
        _browserFiles.value.forEach { f ->
            current[f.id] = f.copy(isSelected = true)
        }
        _selectedFiles.value = current
    }

    fun clearSelection() {
        _selectedFiles.value = emptyMap()
    }

    // Discovery & Transfer
    fun startRadarScanning() {
        transferManager.discovery.startDiscovery()
    }

    fun stopRadarScanning() {
        transferManager.discovery.stopDiscovery()
    }

    fun startReceiving() {
        transferManager.startReceiverMode()
    }

    fun stopReceiving() {
        transferManager.stopReceiverMode()
    }

    fun sendToPeer(peer: PeerDevice) {
        val files = _selectedFiles.value.values.toList()
        if (files.isEmpty()) return

        navigateTo(Screen.ACTIVE_TRANSFER)
        transferManager.startSendingFiles(peer, files) { success ->
            // Clear selection on completion
            if (success) {
                clearSelection()
            }
        }
    }

    fun connectFromQr(rawValue: String): Result<Unit> {
        return try {
            val uri = Uri.parse(rawValue)
            if (uri.scheme != "infinitespeed" || uri.host != "pair") {
                return Result.failure(IllegalArgumentException("Not an Infinite Speed pairing QR"))
            }
            val deviceId = uri.getQueryParameter("device").orEmpty()
            val discovered = transferManager.discovery.discoveredDevices.value
                .firstOrNull { it.id == deviceId }
                ?: return Result.failure(IllegalArgumentException("Peer is not visible on the same Wi-Fi or hotspot"))
            sendToPeer(PeerDevice(
                id = discovered.id,
                name = uri.getQueryParameter("name") ?: "Infinite Speed Peer",
                ipAddress = discovered.ipAddress,
                port = discovered.port,
                connectionType = "QR / Wi-Fi LAN",
                pairingPin = uri.getQueryParameter("pin")
            ))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun acceptIncomingTransfer() {
        val req = _incomingRequest.value ?: return
        transferManager.server.approveSession(req.sessionToken)
        _incomingRequest.value = null
        navigateTo(Screen.ACTIVE_TRANSFER)
    }

    fun declineIncomingTransfer() {
        _incomingRequest.value = null
    }

    fun pauseTransfer() = transferManager.pauseTransfer()
    fun resumeTransfer() = transferManager.resumeTransfer()
    fun cancelTransfer() = transferManager.cancelTransfer()

    // History
    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteTransfer(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // Web share files setup
    fun prepareWebShare() {
        val files = _selectedFiles.value.values.toList()
        transferManager.server.setSharedFiles(files)
        transferManager.server.start()
    }

    fun toggleTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }
}
