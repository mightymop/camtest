package de.mopsdom.rearview.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import de.mopsdom.rearview.R

class SettingsActivity : AppCompatActivity() {



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.preferences)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var wifiManager: WifiManager
        private lateinit var wifiListPreference: ListPreference

        private lateinit var qualityListPreference: ListPreference

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.fitsSystemWindows = true
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            wifiListPreference = findPreference("pref_wifi_network")!!

            qualityListPreference= findPreference("pref_wifi_network")!!

            loadAvailableWifiNetworks()

            setupPreferenceListeners()

        }

        override fun onResume() {
            super.onResume()
            loadAvailableWifiNetworks() // Immer aktualisieren wenn zurückgekehrt
        }

        private fun setupPreferenceListeners() {
            wifiListPreference.setOnPreferenceChangeListener { preference, newValue ->
                val selectedValue = newValue.toString()
                if (selectedValue != "none" && selectedValue != "permission_required") {
                    val parts = selectedValue.split("|")
                    val ssid = parts[0]
                    val bssid = if (parts.size > 1) parts[1] else ""

                    preference.summary = "Ausgewählt: $ssid"

                    // Speichere zusätzlich BSSID in separater Preference
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString("pref_wifi_bssid", bssid)
                        .apply()
                }
                true
            }
        }

        private fun loadAvailableWifiNetworks() {
            if (!hasWifiPermissions()) {
                showPermissionWarning()
                return
            }

            val availableNetworks = getAvailableWifiNetworks()
            updateWifiPreference(availableNetworks)
        }


        private fun showPermissionWarning() {
            wifiListPreference.entries = arrayOf("Berechtigung benötigt")
            wifiListPreference.entryValues = arrayOf("permission_required")
            wifiListPreference.summary = "Location-Berechtigung für WLAN-Scan erforderlich"

            wifiListPreference.setOnPreferenceClickListener {
                requestLocationPermission()
                true
            }
        }

        private fun requestLocationPermission() {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) {
            if (requestCode == 1001 && grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAvailableWifiNetworks()
            }
        }
        private fun hasWifiPermissions(): Boolean {
            return ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun getAvailableWifiNetworks(): List<String> {
            val results = wifiManager.scanResults
            val networks = mutableListOf<String>()

            results?.forEach { scanResult ->
                networks.add(
                    scanResult.SSID
                )
            }

            // Duplikate entfernen und nach Signalstärke sortieren
            return networks
                .distinctBy { it }
        }

        private fun updateWifiPreference(networks: List<String>) {
            if (networks.isEmpty()) {
                wifiListPreference.entries = arrayOf("Keine WLANs gefunden")
                wifiListPreference.entryValues = arrayOf("none")
                wifiListPreference.summary = "Keine Netzwerke verfügbar"
                return
            }

            val entries = networks.map { network ->
                network
            }.toTypedArray()

            val entryValues = networks.map { network ->
                network  // SSID und BSSID kombinieren
            }.toTypedArray()

            wifiListPreference.entries = entries
            wifiListPreference.entryValues = entryValues

            // Aktuell verbundenes WLAN als Summary anzeigen
            val connectedWifi = getConnectedWifiInfo()
            connectedWifi?.let {
                wifiListPreference.summary = "Aktuell: ${it}"
            }
        }

        private fun getConnectedWifiInfo(): String? {
            val wifiInfo = wifiManager.connectionInfo
            return if (wifiInfo != null && wifiInfo.networkId != -1) {
                wifiInfo.ssid.removeSurrounding("\"")
            } else {
                null
            }
        }

    }
}