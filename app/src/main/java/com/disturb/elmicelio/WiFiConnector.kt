package com.disturb.elmicelio

import android.Manifest
import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "WiFiConnector"

sealed class WiFiConnectResult {
    object Connected : WiFiConnectResult()
    object Unavailable : WiFiConnectResult()
    data class Failed(val reason: String) : WiFiConnectResult()
}

object WiFiConnector {

    const val STATIC_SSID = "disturb"
    const val SERVER_URL = "https://cafebabe"
    val STATIC_PASSWORD: String? = null

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @RequiresPermission(allOf = [
        Manifest.permission.CHANGE_NETWORK_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
    suspend fun connect(
        context: Context,
        ssid: String = STATIC_SSID,
        password: String? = STATIC_PASSWORD
    ): WiFiConnectResult {
        val useWpa3 = if (!password.isNullOrEmpty()) isWpa3(context, ssid) else false
        return attemptConnect(context, ssid, password, useWpa3)
    }

    fun disconnect(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        activeCallback = null
        _isConnected.value = false
        cm.bindProcessToNetwork(null)
    }

    @Suppress("DEPRECATION")
    private fun isWpa3(context: Context, ssid: String): Boolean {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val match = wifiManager.scanResults.firstOrNull { it.SSID == ssid }
        if (match == null) {
            Log.w(TAG, "\"$ssid\" not found in scan results (${wifiManager.scanResults.size} total)")
            wifiManager.scanResults.forEach { Log.w(TAG, "  found: \"${it.SSID}\" caps=${it.capabilities}") }
        } else {
            Log.d(TAG, "\"$ssid\" capabilities: ${match.capabilities}")
        }
        return match?.capabilities?.contains("SAE") == true
    }

    @RequiresPermission(Manifest.permission.CHANGE_NETWORK_STATE)
    private suspend fun attemptConnect(
        context: Context,
        ssid: String,
        password: String?,
        wpa3: Boolean
    ): WiFiConnectResult {
        val cm = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .also { connectivityManager = it }

        // Clean up any previous connection
        activeCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        activeCallback = null

        Log.d(TAG, "attemptConnect ssid=\"$ssid\" wpa3=$wpa3 hasPassword=${!password.isNullOrEmpty()}")
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (!password.isNullOrEmpty()) {
            if (wpa3) specifierBuilder.setWpa3Passphrase(password)
            else specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        return suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "onAvailable: $network")
                    cm.bindProcessToNetwork(network)
                    _isConnected.value = true
                    if (continuation.isActive) continuation.resume(WiFiConnectResult.Connected)
                }

                override fun onUnavailable() {
                    Log.w(TAG, "onUnavailable: connection failed or timed out")
                    _isConnected.value = false
                    activeCallback = null
                    if (continuation.isActive) continuation.resume(WiFiConnectResult.Unavailable)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "onLost: $network")
                    cm.bindProcessToNetwork(null)
                    _isConnected.value = false
                    activeCallback = null
                }
            }

            activeCallback = callback
            cm.requestNetwork(request, callback)

            // Only cancel the coroutine wait — do NOT unregister the callback on cancellation
            // so the connection persists after the coroutine scope (screen) goes away.
        }
    }
}
