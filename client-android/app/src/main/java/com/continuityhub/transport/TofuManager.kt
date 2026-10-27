package com.continuityhub.transport

import android.content.Context
import android.util.Log
import com.continuityhub.model.KnownDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed class TofuResult {
    data class Trusted(val device: KnownDevice) : TofuResult()
    data class FirstUsePinned(val fingerprint: String) : TofuResult()
    data class Mismatch(val deviceId: String, val expected: String, val presented: String) : TofuResult()
    object Rejected : TofuResult()
}

class TofuManager(context: Context) {

    private val tag = "TofuManager"
    private val storeFile = File(context.filesDir, "known_devices.json")
    private val devices = ConcurrentHashMap<String, KnownDevice>()

    init {
        loadDevices()
    }

    @Synchronized
    private fun loadDevices() {
        if (!storeFile.exists()) return
        try {
            val jsonStr = storeFile.readText()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val dev = KnownDevice(
                    deviceId = obj.getString("deviceId"),
                    deviceName = obj.getString("deviceName"),
                    certFingerprint = obj.getString("certFingerprint"),
                    pinnedAt = obj.getString("pinnedAt"),
                    trusted = obj.getBoolean("trusted")
                )
                devices[dev.certFingerprint] = dev
            }
            Log.i(tag, "Loaded ${devices.size} known device(s) from storage")
        } catch (e: Exception) {
            Log.e(tag, "Error loading known_devices.json", e)
        }
    }

    @Synchronized
    private fun persistDevices() {
        try {
            val array = JSONArray()
            for (dev in devices.values) {
                val obj = JSONObject().apply {
                    put("deviceId", dev.deviceId)
                    put("deviceName", dev.deviceName)
                    put("certFingerprint", dev.certFingerprint)
                    put("pinnedAt", dev.pinnedAt)
                    put("trusted", dev.trusted)
                }
                array.put(obj)
            }
            storeFile.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.e(tag, "Error saving known_devices.json", e)
        }
    }

    @Synchronized
    fun verifyAndPin(
        fingerprint: String,
        deviceId: String? = null,
        deviceName: String? = null
    ): TofuResult {
        // 1. Check if fingerprint is already pinned
        devices[fingerprint]?.let { dev ->
            return if (dev.trusted) {
                TofuResult.Trusted(dev)
            } else {
                TofuResult.Rejected
            }
        }

        // 2. Check if deviceId is pinned under different fingerprint
        if (deviceId != null) {
            for (dev in devices.values) {
                if (dev.deviceId == deviceId && dev.certFingerprint != fingerprint) {
                    Log.w(tag, "TOFU Mismatch: Device '$deviceId' presented '$fingerprint' but was pinned as '${dev.certFingerprint}'")
                    return TofuResult.Mismatch(deviceId, dev.certFingerprint, fingerprint)
                }
            }
        }

        // 3. First Use: Pin fingerprint
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val newDev = KnownDevice(
            deviceId = deviceId ?: "unknown-peer",
            deviceName = deviceName ?: "Desktop Peer",
            certFingerprint = fingerprint,
            pinnedAt = isoFormat.format(Date()),
            trusted = true
        )
        devices[fingerprint] = newDev
        persistDevices()
        Log.i(tag, "TOFU: Pinned new trusted fingerprint: $fingerprint")
        return TofuResult.FirstUsePinned(fingerprint)
    }

    @Synchronized
    fun updateDeviceInfo(fingerprint: String, deviceId: String, deviceName: String) {
        devices[fingerprint]?.let { old ->
            val updated = old.copy(deviceId = deviceId, deviceName = deviceName)
            devices[fingerprint] = updated
            persistDevices()
            Log.i(tag, "Updated known device info for $fingerprint -> $deviceName ($deviceId)")
        }
    }

    fun getKnownDevices(): List<KnownDevice> = devices.values.toList()
}
