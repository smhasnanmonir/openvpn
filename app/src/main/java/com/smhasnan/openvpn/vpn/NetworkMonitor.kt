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
 *
 * Default-network callbacks fire far more often than the network actually
 * changes: onCapabilitiesChanged reports every signal-strength, bandwidth or
 * validation update. Publishing each of those would make the service re-apply
 * the underlying network, rebuild the notification and wake the tile for no
 * user-visible difference, so a change is only published when the network
 * identity, the online state or the transport description actually differs
 * from the last published change.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _currentNetwork = MutableStateFlow<Network?>(null)
    val currentNetwork: StateFlow<Network?> = _currentNetwork.asStateFlow()

    private val _lastChange = MutableStateFlow<NetworkChange?>(null)
    val lastChange: StateFlow<NetworkChange?> = _lastChange.asStateFlow()

    private var registered = false
    private var lastPublished: NetworkChange? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = capabilitiesOf(network)
            _currentNetwork.value = network
            publish(NetworkChange(network, online = isOnline(capabilities), label = describe(capabilities)))
        }

        override fun onLost(network: Network) {
            if (_currentNetwork.value == network) {
                _currentNetwork.value = null
                publish(NetworkChange(network, online = false, label = "disconnected"))
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _currentNetwork.value = network
            publish(NetworkChange(network, online = isOnline(capabilities), label = describe(capabilities)))
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
        return describe(capabilities)
    }

    fun isOnline(): Boolean {
        val network = _currentNetwork.value ?: connectivityManager.activeNetwork ?: return false
        return isOnline(connectivityManager.getNetworkCapabilities(network))
    }

    /** Deduplicates against the last published change so collectors stay quiet. */
    private fun publish(change: NetworkChange) {
        val previous = lastPublished
        if (previous != null &&
            previous.network == change.network &&
            previous.online == change.online &&
            previous.label == change.label
        ) {
            return
        }
        lastPublished = change
        _lastChange.value = change
    }

    private fun capabilitiesOf(network: Network): NetworkCapabilities? =
        runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()

    private fun isOnline(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun describe(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "network"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "network"
    }

    data class NetworkChange(
        val network: Network,
        val online: Boolean,
        val label: String,
        val timestampMillis: Long = System.currentTimeMillis(),
    )
}
