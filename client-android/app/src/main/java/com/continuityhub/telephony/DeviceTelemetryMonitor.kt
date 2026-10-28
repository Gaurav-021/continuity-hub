package com.continuityhub.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.continuityhub.proto.DeviceTelemetry
import com.continuityhub.service.ContinuityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceTelemetryMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "DeviceTelemetryMonitor"
    private var lastDispatchedLevel = -1
    private var lastChargingState = false
    private var periodicJob: Job? = null

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                evaluateAndDispatch(intent)
            }
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        registerNetworkCallback()
        startPeriodicDispatch()
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    dispatchCurrentTelemetry()
                }

                override fun onLost(network: Network) {
                    dispatchCurrentTelemetry()
                }
            })
        } catch (e: Exception) {
            Log.w(tag, "Failed registering network callback: ${e.message}")
        }
    }

    private fun startPeriodicDispatch() {
        periodicJob = scope.launch {
            while (isActive) {
                delay(30_000) // Heartbeat telemetry every 30 seconds
                dispatchCurrentTelemetry()
            }
        }
    }

    private fun evaluateAndDispatch(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        // If level changed or plugged/unplugged, broadcast immediately
        if (percentage != lastDispatchedLevel || isCharging != lastChargingState) {
            lastDispatchedLevel = percentage
            lastChargingState = isCharging
            dispatchTelemetryFromIntent(intent, percentage, isCharging)
        }
    }

    fun dispatchCurrentTelemetry() {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        if (batteryStatus != null) {
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else 0

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            dispatchTelemetryFromIntent(batteryStatus, percentage, isCharging)
        }
    }

    private fun dispatchTelemetryFromIntent(batteryIntent: Intent, percentage: Int, isCharging: Boolean) {
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val tempRaw = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempCelsius = tempRaw / 10f

        val chargingType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> DeviceTelemetry.ChargingType.CHARGING_AC
            BatteryManager.BATTERY_PLUGGED_USB -> DeviceTelemetry.ChargingType.CHARGING_USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> DeviceTelemetry.ChargingType.CHARGING_WIRELESS
            else -> if (isCharging) DeviceTelemetry.ChargingType.CHARGING_FAST else DeviceTelemetry.ChargingType.CHARGING_NONE
        }

        var netType = DeviceTelemetry.NetworkType.NETWORK_NONE
        var wifiSsid = ""
        var carrierName = telephonyManager?.networkOperatorName ?: ""

        val activeNet = connectivityManager?.activeNetwork
        val caps = connectivityManager?.getNetworkCapabilities(activeNet)

        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                netType = DeviceTelemetry.NetworkType.NETWORK_WIFI
                val info = wifiManager?.connectionInfo
                val ssid = info?.ssid ?: ""
                wifiSsid = ssid.replace("\"", "").let { if (it == "<unknown ssid>") "Connected" else it }
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                netType = DeviceTelemetry.NetworkType.NETWORK_CELLULAR_5G
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                netType = DeviceTelemetry.NetworkType.NETWORK_ETHERNET
            }
        }

        val telemetry = DeviceTelemetry.newBuilder()
            .setBatteryPercentage(percentage)
            .setIsCharging(isCharging)
            .setChargingType(chargingType)
            .setBatteryTemperatureC(tempCelsius)
            .setNetworkType(netType)
            .setWifiSsid(wifiSsid)
            .setCellularCarrier(carrierName)
            .setSignalBars(4)
            .setTimestampMs(System.currentTimeMillis())
            .build()

        ContinuityService.broadcastTelemetry(telemetry)
    }

    fun release() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        periodicJob?.cancel()
    }
}
