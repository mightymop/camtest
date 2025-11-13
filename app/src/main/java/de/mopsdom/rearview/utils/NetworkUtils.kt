package de.mopsdom.rearview.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo
import android.util.Log

class NetworkUtils(private val context: Context) {

    companion object {
        private const val TAG = "NetworkUtils"
    }

    /**
     * Prüft ob überhaupt WLAN verbunden ist (egal welches Netzwerk)
     */
    fun isConnectedToWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            connectivityManager.allNetworks.forEach { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    return true
                }
            }
            return false
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }

    /**
     * Prüft ob mit einem bestimmten WLAN verbunden (SSID)
     */
    fun isConnectedToSpecificWifi(targetSsid: String, targetBssid: String? = null): Boolean {
        if (!isConnectedToWifi()) return false

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo

        return wifiInfo?.let { info ->
            val connectedSsid = removeQuotes(info.ssid)
            val connectedBssid = info.bssid

            val ssidMatches = connectedSsid.equals(targetSsid, ignoreCase = true)
            val bssidMatches = targetBssid == null || connectedBssid.equals(targetBssid, ignoreCase = true)

            Log.d(TAG, "SSID Match: $ssidMatches, BSSID Match: $bssidMatches")
            ssidMatches && bssidMatches
        } ?: false
    }

    /**
     * Prüft ob mit einem WLAN verbunden das bestimmten Namen enthält
     */
    fun isConnectedToWifiContaining(ssidPart: String): Boolean {
        if (!isConnectedToWifi()) return false

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo

        return wifiInfo?.let { info ->
            val connectedSsid = removeQuotes(info.ssid)
            connectedSsid.contains(ssidPart, ignoreCase = true)
        } ?: false
    }

    /**
     * Gibt die SSID des aktuell verbundenen WLANs zurück
     */
    fun getConnectedWifiSsid(): String? {
        if (!isConnectedToWifi()) return null

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo

        return wifiInfo?.ssid?.let { removeQuotes(it) }
    }

    /**
     * Entfernt Anführungszeichen von SSID (Android fügt diese manchmal hinzu)
     */
    private fun removeQuotes(ssid: String): String {
        return ssid.removeSurrounding("\"")
    }

    interface WifiConnectionListener {
        fun onWifiConnected(ssid: String)
        fun onWifiDisconnected()
        fun onSpecificWifiConnected(ssid: String)
        fun onSpecificWifiDisconnected(ssid: String)
    }

    private var targetSsid: String? = null
    private var listener: WifiConnectionListener? = null
    private var wasConnectedToTarget = false

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkWifiConnection()
        }

        override fun onLost(network: Network) {
            checkWifiConnection()
        }
    }

    fun startMonitoring(targetSsid: String? = null, listener: WifiConnectionListener) {
        this.targetSsid = targetSsid
        this.listener = listener

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }

        // Initial check
        checkWifiConnection()
    }

    fun stopMonitoring() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        listener = null
    }

    private fun checkWifiConnection() {
        val utils = NetworkUtils(context)

        if (utils.isConnectedToWifi()) {
            val ssid = utils.getConnectedWifiSsid()
            ssid?.let {
                listener?.onWifiConnected(it)

                // Prüfe spezifisches WLAN
                targetSsid?.let { target ->
                    val isConnectedToTarget = utils.isConnectedToSpecificWifi(target)

                    if (isConnectedToTarget && !wasConnectedToTarget) {
                        listener?.onSpecificWifiConnected(target)
                        wasConnectedToTarget = true
                    } else if (!isConnectedToTarget && wasConnectedToTarget) {
                        listener?.onSpecificWifiDisconnected(target)
                        wasConnectedToTarget = false
                    }
                }
            }
        } else {
            listener?.onWifiDisconnected()
            if (wasConnectedToTarget) {
                targetSsid?.let { listener?.onSpecificWifiDisconnected(it) }
                wasConnectedToTarget = false
            }
        }
    }
}