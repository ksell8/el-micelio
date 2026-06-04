package com.disturb.elmicelio

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = BeaconScanner(app)

    val nodes = scanner.nodes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    val isScanning = scanner.isScanning.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false
    )

    val isNodeConnected = WiFiConnector.isConnected.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false
    )

    val error = scanner.error.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
    fun startScanning() = scanner.startScanning()

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() = scanner.stopScanning()

    fun clearError() = scanner.clearError()

    fun disconnect() = WiFiConnector.disconnect(getApplication())

    @RequiresPermission(allOf = [
        Manifest.permission.CHANGE_NETWORK_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
    suspend fun connectToNode(node: SemillaNode, password: String? = null): WiFiConnectResult =
        WiFiConnector.connect(getApplication(), password = password)

    override fun onCleared() {
        super.onCleared()
        try { scanner.stopScanning() } catch (_: SecurityException) {}
        WiFiConnector.disconnect(getApplication())
    }
}
