package cn.yunyoujun.cook.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

internal fun Context.validatedNetworkChanges(emitOnStart: Boolean = false): Flow<Unit> = callbackFlow {
    val manager = getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        if (emitOnStart) trySend(Unit)
        close()
        return@callbackFlow
    }

    // Suppress the registration callback for a connection that is already validated.
    val initial = runCatching {
        manager.activeNetwork?.takeIf { manager.getNetworkCapabilities(it)?.hasValidatedInternet == true }
    }.getOrNull()
    val callback = object : ConnectivityManager.NetworkCallback() {
        private var validated = initial

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.hasValidatedInternet) {
                if (validated == network) validated = null
            } else if (validated != network) {
                validated = network
                trySend(Unit)
            }
        }

        override fun onLost(network: Network) {
            if (validated == network) validated = null
        }
    }
    val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
    // Register before the initial check so a reconnect during it remains queued.
    if (emitOnStart) trySend(Unit)
    if (!registered) {
        close()
        return@callbackFlow
    }
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.conflate()

private val NetworkCapabilities.hasValidatedInternet: Boolean
    get() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
