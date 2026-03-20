package com.godmode.app.ui.dashboard

import androidx.lifecycle.*
import com.godmode.app.daemon.RootManager
import com.godmode.app.data.repository.GodModeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val repository: GodModeRepository,
    private val rootManager: RootManager
) : ViewModel() {

    private val _rootStatus = MutableLiveData<RootManager.RootStatus>()
    val rootStatus: LiveData<RootManager.RootStatus> = _rootStatus

    private val _deviceInfo = MutableLiveData<GodModeRepository.DeviceInfo>()
    val deviceInfo: LiveData<GodModeRepository.DeviceInfo> = _deviceInfo

    val recentLogs = repository.getRecentLogs(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalLogCount = repository.getTotalLogCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val spoofedCount = repository.getSpoofedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val topAccessingApps = repository.getTopAccessingApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accessCountByType = repository.getAccessCountByType()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeConfigCount = repository.getActiveConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _rootStatus.value = rootManager.getRootStatus()
            _deviceInfo.value = repository.getRealDeviceInfo()
        }
    }

    fun startDaemon() {
        viewModelScope.launch {
            rootManager.startDaemon()
            refreshStatus()
        }
    }

    fun stopDaemon() {
        viewModelScope.launch {
            rootManager.stopDaemon()
            refreshStatus()
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearAllLogs()
        }
    }
}

class DashboardViewModelFactory(
    private val repository: GodModeRepository,
    private val rootManager: RootManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(repository, rootManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
