package de.mopsdom.rearview.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
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

            initSeekbars()

        }

        fun initSeekbars() {

            var topDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_portrait_top",
                resources.getString(R.string.portrait_line_coords_top)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            var rightDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_portrait_right",
                resources.getString(R.string.portrait_line_coords_right)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            var bottomDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_portrait_bottom",
                resources.getString(R.string.portrait_line_coords_bottom)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            var leftDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_portrait_left",
                resources.getString(R.string.portrait_line_coords_left)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            findPreference<SeekBarPreference>("parking_lines_portrait_top")!!.setValue(topDp)
            findPreference<SeekBarPreference>("parking_lines_portrait_right")!!.setValue(rightDp)
            findPreference<SeekBarPreference>("parking_lines_portrait_bottom")!!.setValue(bottomDp)
            findPreference<SeekBarPreference>("parking_lines_portrait_left")!!.setValue(leftDp)

            var ltopDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_landscape_top",
                resources.getString(R.string.landscape_line_coords_top)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            var lrightDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_landscape_right",
                resources.getString(R.string.landscape_line_coords_right)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            var lbottomDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_landscape_bottom",
                resources.getString(R.string.landscape_line_coords_bottom)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            var lleftDp = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(
                "parking_lines_landscape_left",
                resources.getString(R.string.landscape_line_coords_left)
                    .replace("[^0-9]".toRegex(), "").toInt()
            )

            findPreference<SeekBarPreference>("parking_lines_landscape_top")!!.setValue(ltopDp)
            findPreference<SeekBarPreference>("parking_lines_landscape_right")!!.setValue(lrightDp)
            findPreference<SeekBarPreference>("parking_lines_landscape_bottom")!!.setValue(lbottomDp)
            findPreference<SeekBarPreference>("parking_lines_landscape_left")!!.setValue(lleftDp)
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