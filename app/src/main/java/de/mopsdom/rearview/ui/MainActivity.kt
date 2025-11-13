package de.mopsdom.rearview.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import de.mopsdom.rearview.R
import de.mopsdom.rearview.protocol.CTPProtocol
import de.mopsdom.rearview.protocol.RtpConvertProxy
import de.mopsdom.rearview.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val TAG: String = "MainActivity"

    private lateinit var commandstream : CTPProtocol

    private lateinit var networkUtils: NetworkUtils
    private lateinit var mjpegReceiver: RtpConvertProxy

    private var use_cam: Boolean = true

    private var trywificonnection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ImageView>(R.id.action_settings).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        findViewById<ImageView>(R.id.action_camera).setOnClickListener {
            use_cam = !use_cam
            if (!use_cam) {
                disconnect()
            }
            else
            {
                connect()
            }
        }

        findViewById<ImageView>(R.id.action_camera).setBackgroundResource(R.drawable.outline_videocam_off_24)

        findViewById<SurfaceView>(R.id.surfaceView).holder.addCallback(this)

        networkUtils= NetworkUtils(this)

        var self = this
        commandstream = CTPProtocol(this, object : CTPProtocol.CommandStreamListener {
            override fun onConnected() {
                Log.i(TAG, "Command stream connection established")
                runOnUiThread {
                    Toast.makeText(self,"Mit Kamera verbunden", Toast.LENGTH_SHORT).show()
                    findViewById<ImageView>(R.id.action_camera).setBackgroundResource(R.drawable.outline_videocam_24)
                    if (PreferenceManager.getDefaultSharedPreferences(self).getBoolean("pref_parking_lines",resources.getBoolean(R.bool.default_parkinglines)))
                    {
                        findViewById<ImageView>(R.id.parking_lines).visibility= View.VISIBLE
                    }
                    else
                    {
                        findViewById<ImageView>(R.id.parking_lines).visibility= View.GONE
                    }
                }
            }

            override fun onDisconnected() {
                Log.i(TAG, "Command stream connection disconnected")
                runOnUiThread {
                    Toast.makeText(self,"Verbindung zur Kamera geschlossen", Toast.LENGTH_SHORT).show()
                    findViewById<ImageView>(R.id.action_camera).setBackgroundResource(R.drawable.outline_videocam_off_24)
                    findViewById<ImageView>(R.id.parking_lines).visibility= View.GONE
                }
                mjpegReceiver.stopStream()

            }

            override fun onCommandSent(info: String) {
                Log.i(TAG, info)
            }

            override fun onCameraConnected(connected: Boolean) {
                if (connected) {
                    mjpegReceiver.stopStream()
                    mjpegReceiver.startStream(resources.getInteger(R.integer.udpport))
                } else {
                    mjpegReceiver.stopStream()
                }
            }

            override fun onError(error: String, e: Exception?) {
                Log.e(TAG, error)
            }

        })

        mjpegReceiver = RtpConvertProxy()

    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CoroutineScope(Dispatchers.IO).launch {
            disconnect()
            delay(3000)
            connect()
        }

        refreshLineMargins()
    }


    fun refreshLineMargins() {
        val orientation = resources.configuration.orientation
        when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {

                var layoutParams =
                    findViewById<ImageView>(R.id.parking_lines).layoutParams as ViewGroup.MarginLayoutParams
                var topDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_portrait_top",
                    -1
                ).toString()

                if (topDp=="-1")
                {
                    topDp = resources.getString(R.string.portrait_line_coords_top)
                }


                var rightDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_portrait_right",
                    -1
                ).toString()

                if (rightDp=="-1")
                {
                    rightDp = resources.getString(R.string.portrait_line_coords_right)
                }


                var bottomDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_portrait_bottom",
                    -1
                ).toString()
                if (bottomDp=="-1")
                {
                    bottomDp = resources.getString(R.string.portrait_line_coords_bottom)
                }


                var leftDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_portrait_left",
                    -1
                ).toString()
                if (leftDp=="-1")
                {
                    leftDp = resources.getString(R.string.portrait_line_coords_left)
                }

                layoutParams.setMargins(
                    (leftDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt(),
                    (topDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt(),
                    (rightDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt(),
                    (bottomDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt()
                )

                findViewById<ImageView>(R.id.parking_lines).layoutParams = layoutParams
            }

            Configuration.ORIENTATION_LANDSCAPE -> {
                var layoutParams =
                    findViewById<ImageView>(R.id.parking_lines).layoutParams as ViewGroup.MarginLayoutParams
                var topDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_landscape_top",
                    -1
                ).toString()

                if (topDp=="-1")
                {
                    topDp = resources.getString(R.string.landscape_line_coords_top)
                }


                var rightDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_landscape_right",
                    -1
                ).toString()

                if (rightDp=="-1")
                {
                    rightDp = resources.getString(R.string.landscape_line_coords_right)
                }


                var bottomDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_landscape_bottom",
                    -1
                ).toString()
                if (bottomDp=="-1")
                {
                    bottomDp = resources.getString(R.string.landscape_line_coords_bottom)
                }


                var leftDp = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                    "parking_lines_landscape_left",
                    -1
                ).toString()
                if (leftDp=="-1")
                {
                    leftDp = resources.getString(R.string.landscape_line_coords_left)
                }

                layoutParams.setMargins(
                    (leftDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt(),
                    (topDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt(),
                    (rightDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt(),
                    (bottomDp!!.replace("[^0-9]".toRegex(), "")
                        .toInt() * resources.displayMetrics.density).toInt()
                )

                findViewById<ImageView>(R.id.parking_lines).layoutParams = layoutParams
            }

            else -> {
                Log.d("Orientation", "Undefined Orientation")
            }
        }
    }

    override fun onResume() {
        super.onResume()

        refreshLineMargins()

        findViewById<ImageView>(R.id.parking_lines).visibility= View.GONE

        var autoWifi = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_autoconnect_wifi",resources.getBoolean(R.bool.default_autoconnect_wifi))

        if (autoWifi) {
            var wifi = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("pref_wifi_network", null)

            if (wifi != null) {
                Log.d(TAG, "Found Wifi in preferences: ${wifi}")

                if (!networkUtils.isConnectedToWifiContaining(wifi)) {
                    Log.d(TAG, "Try to connect to: ${wifi}")
                    if (!trywificonnection) {
                        trywificonnection = true
                        connectToWifi(wifi)
                    } else {
                        trywificonnection = false
                        Log.d(TAG, "Verbindung zu ${wifi} nicht möglich.")
                        Toast.makeText(
                            this,
                            resources.getString(R.string.alert_wifi_connection),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                } else {
                    trywificonnection = false
                    Log.d(TAG, "Allready connected to: ${wifi}")
                    CoroutineScope(Dispatchers.IO).launch {
                        disconnect()
                        delay(500)
                        connect()
                    }
                }
            } else {
                Toast.makeText(this, R.string.alert_wifi, Toast.LENGTH_LONG).show()
            }
        }
        else{
            CoroutineScope(Dispatchers.IO).launch {
                disconnect()
                delay(500)
                connect()
            }
        }
    }

    fun connectToWifi(ssid: String) {
        var self = this
        Log.d(TAG, "Build WIFI-Request")
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply {
            }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, resources.getString(R.string.wifi_ok))
                Toast.makeText(self,R.string.alert_wifi_connection,Toast.LENGTH_LONG).show()
                connectivityManager.bindProcessToNetwork(network)
            }

            override fun onUnavailable() {
                Log.d(TAG, resources.getString(R.string.wifi_not_found))
                Toast.makeText(self,R.string.alert_wifi_connection,Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun surfaceChanged(
        p0: SurfaceHolder,
        p1: Int,
        p2: Int,
        p3: Int
    ) {
        //not used
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG,"Surface created - initializing RTP decoder")
        mjpegReceiver.setSurface(findViewById<SurfaceView>(R.id.surfaceView))
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {

    }

    override fun onPause() {
        super.onPause()
        disconnect()
    }
    fun connect() {
        CoroutineScope(Dispatchers.IO).launch {
            commandstream.connect()
        }
    }

    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            commandstream.disconnect()
        }
    }

}