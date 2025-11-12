package de.mopsdom.rearview.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import de.mopsdom.rearview.R
import de.mopsdom.rearview.protocol.CTPProtocol
import de.mopsdom.rearview.protocol.JFIFMJpegStreamReceiver
import de.mopsdom.rearview.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.prefs.Preferences

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val TAG: String = "MainActivity"

    private lateinit var commandstream : CTPProtocol

    private lateinit var networkUtils: NetworkUtils
    private lateinit var mjpegReceiver: JFIFMJpegStreamReceiver

    private var use_cam: Boolean = true

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

            override fun onCameraConnected(connected: Boolean, use_hd: Boolean) {
                if (connected) {
                    mjpegReceiver.stopStream()
                    mjpegReceiver.startStream(false)
                } else {
                    mjpegReceiver.stopStream()
                }
            }

            override fun onError(error: String, e: Exception?) {
                Log.e(TAG, error)
            }

        })

        mjpegReceiver = JFIFMJpegStreamReceiver()
    }

    override fun onResume() {
        super.onResume()

        CoroutineScope(Dispatchers.IO).launch {
            disconnect()
            delay(500)
            connect()
        }
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
        mjpegReceiver.initialize(holder, object : JFIFMJpegStreamReceiver.StreamListener {
            override fun onVideoStarted() {
                Log.d(TAG,"JFIF MJPEG stream started")
            }

            override fun onVideoStopped() {
                Log.d(TAG,"Stream Stopped")
            }

            override fun onError(error: String) {
                Log.e(TAG,"Error: $error")
            }

            override fun onFrameDecoded(width: Int, height: Int) {
                Log.v(TAG,"Frame decoded successfully")
            }

            override fun onStreamInfo(info: String) {
                Log.v(TAG,info)
            }

            override fun onPcapDumpStarted(filePath: String) {

            }

            override fun onPcapDumpStopped(filePath: String, packetCount: Int) {

            }
        },this)

    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {

    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegReceiver.release()
    }

    override fun onPause() {
        super.onPause()
        disconnect()
     //   networkUtils.stopMonitoring()
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