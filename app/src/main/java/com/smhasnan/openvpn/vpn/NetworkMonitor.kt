package com.smhasnan.openvpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes Android's default network. The VPN service uses this to keep the
 * VpnService's underlying network current and to record network transitions.
 * Reconnect logic itself belongs to OpenVPN Core.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _currentNetwork = MutableStateFlow<Network?>(null)
    val currentNetwork: StateFlow<Network?> = _currentNetwork.asStateFlow()

    private val _lastChange = MutableStateFlow<NetworkChange?>(null)
    val lastChange: StateFlow<NetworkChange?> = _lastChange.asStateFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _currentNetwork.value = network
            _lastChange.value = NetworkChange(network, online = true, label = describe())
        }

        override fun onLost(network: Network) {
            if (_currentNetwork.value == network) {
                _currentNetwork.value = null
                _lastChange.value = NetworkChange(network, online = false, label = "disconnected")
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _currentNetwork.value = network
            _lastChange.value = NetworkChange(network, online = true, label = describe())
        }
    }

    fun start() {
        if (registered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            registered = true
            _currentNetwork.value = connectivityManager.activeNetwork
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun describe(): String {
        val network = _currentNetwork.value ?: connectivityManager.activeNetwork ?: return "offline"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "network"
        }
    }

    fun isOnline(): Boolean {
        val network = _currentNetwork.value ?: connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    data class NetworkChange(
        val network: Network,
        val online: Boolean,
        val label: String,
        val timestampMillis: Long = System.currentTimeMillis(),
    )
}
