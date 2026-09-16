package com.jake.duolauncher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.Closeable
import java.net.NetworkInterface

/** Discover only endpoints advertised by this phone. Never connect to another LAN device. */
@Suppress("DEPRECATION")
internal class LocalAdbDiscovery(context: Context, private val changed: (Boolean, Int?) -> Unit) : Closeable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val ports = mutableMapOf<Boolean, MutableMap<String, Int>>()
    @Volatile private var closed = false

    fun start() {
        listOf(true to "_adb-tls-pairing._tcp.", false to "_adb-tls-connect._tcp.").forEach { (pairing, type) ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(ports) {
                        ports[pairing]?.remove(info.serviceName)
                        if (!closed) changed(pairing, ports[pairing]?.values?.distinct()?.singleOrNull())
                    }
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (closed) return
                    runCatching { manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (closed) return
                            val address = serviceInfo.host ?: return
                            val local = address.isLoopbackAddress || runCatching {
                                NetworkInterface.getNetworkInterfaces().toList().any { address in it.inetAddresses.toList() }
                            }.getOrDefault(false)
                            if (!local || serviceInfo.port !in 1024..65535) return
                            synchronized(ports) {
                                ports.getOrPut(pairing) { mutableMapOf() }[serviceInfo.serviceName] = serviceInfo.port
                                if (!closed) changed(pairing, ports[pairing]?.values?.distinct()?.singleOrNull())
                            }
                        }
                    }) }
                }
            }
            runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { listeners += listener }
        }
    }

    override fun close() {
        closed = true
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
    }
}
