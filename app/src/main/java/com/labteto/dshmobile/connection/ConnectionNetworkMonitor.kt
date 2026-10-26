package com.labteto.dshmobile.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide default-network identity. Local networks need not pass Internet validation. */
@Singleton
class ConnectionNetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow(connectivity?.activeNetwork)
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Identity changes wake backoff even when handover has no offline interval.
            _network.value = network
        }

        override fun onLost(network: Network) {
            _network.compareAndSet(network, null)
        }
    }

    init {
        connectivity?.registerDefaultNetworkCallback(callback)
    }
}
