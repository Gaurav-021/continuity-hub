package com.continuityhub.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.continuityhub.model.DiscoveredPeer
import java.util.ArrayDeque

class NsdDiscoveryManager(
    context: Context,
    private val myDeviceId: String
) {
    private val tag = "NsdDiscoveryManager"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, (DiscoveredPeer) -> Unit>>()
    private var isResolving = false

    companion object {
        const val SERVICE_TYPE = "_continuity._tcp"
    }

    fun registerService(port: Int, deviceName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$deviceName-${myDeviceId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("device_name", deviceName)
            setAttribute("device_id", myDeviceId)
            setAttribute("version", "1")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(tag, "NSD Service registered successfully: ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Service registration failed with error code: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(tag, "NSD Service unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Service unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "Exception during registerService", e)
        }
    }

    fun startDiscovery(onDeviceFound: (DiscoveredPeer) -> Unit) {
        stopDiscovery()

        try {
            multicastLock = wifiManager?.createMulticastLock("continuity_mdns_lock")?.apply {
                setReferenceCounted(true)
                acquire()
                Log.i(tag, "Acquired WifiManager.MulticastLock for mDNS discovery")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to acquire MulticastLock: ${e.message}")
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(tag, "NSD Service discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(tag, "NSD Service candidate found: ${service.serviceName}")
                if (service.serviceType.contains("_continuity")) {
                    queueResolve(service, onDeviceFound)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(tag, "NSD Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(tag, "NSD Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "NSD Start discovery failed with error: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "NSD Stop discovery failed with error: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(tag, "Exception during discoverServices", e)
        }
    }

    @Synchronized
    private fun queueResolve(serviceInfo: NsdServiceInfo, onDeviceFound: (DiscoveredPeer) -> Unit) {
        resolveQueue.add(serviceInfo to onDeviceFound)
        processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (isResolving || resolveQueue.isEmpty()) return
        val (serviceInfo, onDeviceFound) = resolveQueue.poll() ?: return
        isResolving = true
        resolveService(serviceInfo, onDeviceFound)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, onDeviceFound: (DiscoveredPeer) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(tag, "NSD Resolve failed for ${serviceInfo.serviceName} (error $errorCode)")
                synchronized(this@NsdDiscoveryManager) {
                    isResolving = false
                    processNextResolve()
                }
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                try {
                    val host = resolvedInfo.host
                    val port = resolvedInfo.port
                    val attributes = resolvedInfo.attributes

                    val devIdBytes = attributes["device_id"]
                    val peerDeviceId = if (devIdBytes != null) String(devIdBytes) else ""

                    // Ignore ourselves
                    if (peerDeviceId.isNotEmpty() && peerDeviceId != myDeviceId && host != null) {
                        val devNameBytes = attributes["device_name"]
                        val peerDeviceName = if (devNameBytes != null) String(devNameBytes) else resolvedInfo.serviceName

                        val peer = DiscoveredPeer(
                            deviceId = peerDeviceId,
                            deviceName = peerDeviceName,
                            host = host.hostAddress ?: host.toString(),
                            port = port
                        )
                        Log.i(tag, "Resolved Continuity peer: '${peer.deviceName}' (${peer.deviceId}) at ${peer.host}:${peer.port}")
                        onDeviceFound(peer)
                    }
                } finally {
                    synchronized(this@NsdDiscoveryManager) {
                        isResolving = false
                        processNextResolve()
                    }
                }
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(tag, "Exception attempting to resolve service", e)
            synchronized(this@NsdDiscoveryManager) {
                isResolving = false
                processNextResolve()
            }
        }
    }

    fun stopDiscovery() {
        multicastLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    Log.i(tag, "Released WifiManager.MulticastLock")
                }
            } catch (_: Exception) {}
            multicastLock = null
        }

        synchronized(this) {
            resolveQueue.clear()
            isResolving = false
        }

        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: Exception) {}
            discoveryListener = null
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (_: Exception) {}
            registrationListener = null
        }
    }
}
