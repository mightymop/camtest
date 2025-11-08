package local.test.camtest.ui.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.test.camtest.R
import local.test.camtest.databinding.FragmentHomeBinding
import local.test.camtest.protocol.CTPCommand
import local.test.camtest.protocol.CTPProtocol
import local.test.camtest.protocol.JFIFMJpegStreamReceiver
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "HomeFragment"
    }

    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!

    private lateinit var mjpegReceiver: JFIFMJpegStreamReceiver

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null

    private var use_hd: Boolean = false
    private var logCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textHome
        homeViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }

        binding.surfaceView.holder.addCallback(this)
        mjpegReceiver = JFIFMJpegStreamReceiver()

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                // Checkbox-Status setzen
                menu.findItem(R.id.action_quality_hd)?.isChecked = use_hd
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                val protocol = CTPProtocol()

                return when (menuItem.itemId) {
                    R.id.action_quality_hd -> {
                        menuItem.isChecked = !menuItem.isChecked
                        // HD Qualität auswählen
                        use_hd = menuItem.isChecked
                        true
                    }

                    R.id.action_reconnect -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            reconnect()
                        }
                        true
                    }

                    R.id.action_app_access -> {
                        val bytePacket = protocol.buildPacket(
                            CTPCommand.APP_ACCESS.command,
                            "PUT",
                            mapOf("ver" to "20701", "type" to "0")
                        )
                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_date_time -> {
                        val currentDateTime: String = SimpleDateFormat(
                            "yyyyMMddHHmmss",
                            Locale.getDefault()
                        ).format(Date())

                        val bytePacket = protocol.buildPacket(
                            CTPCommand.DATE_TIME.command,
                            "PUT",
                            mapOf("date" to currentDateTime)
                        )

                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_language -> {

                        val bytePacket = protocol.buildPacket(
                            CTPCommand.LANGUAGE.command,
                            "PUT",
                            mapOf("lag" to "7")
                        )

                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_keep_alive -> {

                        val bytePacket = protocol.buildPacket(
                            CTPCommand.CTP_KEEP_ALIVE.command,
                            "PUT",
                            emptyMap()
                        )

                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_video_param -> {

                        val h: Int = if (use_hd) {
                            resources.getInteger(R.integer.hd_height)
                        } else {
                            resources.getInteger(R.integer.sd_height)
                        }
                        val w: Int = if (use_hd) {
                            resources.getInteger(R.integer.hd_width)
                        } else {
                            resources.getInteger(R.integer.sd_width)
                        }

                        val bytePacket = protocol.buildPacket(
                            CTPCommand.VIDEO_PARAM.command,
                            "PUT",
                            mapOf(
                                "w" to w.toString(),
                                "h" to h.toString(),
                                "format" to "0",
                                "fps" to resources.getInteger(R.integer.frames).toString()
                            )
                        )

                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_video_ctrl -> {

                        val bytePacket = protocol.buildPacket(
                            CTPCommand.VIDEO_CTRL.command,
                            "GET",
                            emptyMap()
                        )

                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_open_rt_stream -> {

                        mjpegReceiver.stopStream()
                        mjpegReceiver.startStream()

                        val h: Int = if (use_hd) {
                            resources.getInteger(R.integer.hd_height)
                        } else {
                            resources.getInteger(R.integer.sd_height)
                        }
                        val w: Int = if (use_hd) {
                            resources.getInteger(R.integer.hd_width)
                        } else {
                            resources.getInteger(R.integer.sd_width)
                        }

                        val bytePacket = protocol.buildPacket(
                            CTPCommand.OPEN_RT_STREAM.command,
                            "PUT",
                            mapOf(
                                "w" to w.toString(),
                                "h" to h.toString(),
                                "format" to "0",
                                "fps" to resources.getInteger(R.integer.frames).toString()
                            )
                        )
                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_close_rt_stream -> {

                        mjpegReceiver.stopStream()


                        val bytePacket = protocol.buildPacket(
                            CTPCommand.CLOSE_RT_STREAM.command,
                            "PUT",
                            mapOf("status" to "1")
                        )
                        sendCommand(bytePacket)
                        true
                    }

                    R.id.action_close_connection -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            close()
                        }
                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        CoroutineScope(Dispatchers.IO).launch {
            connectToCamera()
        }

        return root
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
        log("Surface created - initializing RTP decoder")
        mjpegReceiver.initialize(holder, object : JFIFMJpegStreamReceiver.StreamListener {
            override fun onVideoStarted() {
                log("JFIF MJPEG stream started")
            }

            override fun onVideoStopped() {
                log("Stream Stopped")
            }

            override fun onError(error: String) {
                log("Error: $error")
            }

            override fun onFrameDecoded(width: Int, height: Int) {
                Log.v(TAG, "Frame decoded successfully")
            }

            override fun onStreamInfo(info: String) {
                log(info)
            }
        },requireContext())

    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        mjpegReceiver.release()
    }

    private suspend fun close() {
        log("Close connection...")

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        try {
            out?.close()
        } catch (_: Exception) {
        }

        try {
            input?.close()
        } catch (_: Exception) {
        }

        socket = null
        out = null
        input = null

        delay(500)
    }

    private suspend fun reconnect() {
        close()
        log("Reconnecting...")
        connectToCamera()
    }

    private fun sendCommand(bytePacket: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {

                out?.write(bytePacket, 0, bytePacket.size)
                out?.flush()
                log("Command sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.message}")
            }
        }
    }

    private suspend fun connectToCamera() {
        try {
            var cameraip: String = resources.getString(R.string.cameraip)
            var tcpPort: Int = resources.getInteger(R.integer.tcpport)
            var timeout: Int = resources.getInteger(R.integer.timeout)

            socket = Socket()
            socket?.connect(InetSocketAddress(cameraip, tcpPort), timeout)
            out = socket?.getOutputStream()
            input = socket?.getInputStream()
            log("Connected to camera $cameraip:$tcpPort")

            listenForPackets()

        } catch (e: Exception) {
            log("Connection failed: ${e.message}")
        }
    }

    private suspend fun listenForPackets() {
        val inputStream = input ?: return

        val protocol = CTPProtocol()

        while (true) {
            try {
                protocol.feed(inputStream) { message ->
                    log("${logCount}: ${message.command} - ${message.json}")
                    logCount++

                    if (message.op == "NOTIFY") {
                        if (message.command == CTPCommand.OPEN_RT_STREAM.command) {

                            log("MJPEG Stream started")
                        }

                        if (message.command == CTPCommand.CLOSE_RT_STREAM.command) {
                            log("MJPEG Stream stopped")
                        }
                    }
                }

            } catch (e: Exception) {
                log("Receive error: ${e.message}")
                break
            }
        }
    }

    private fun log(msg: String) {
        activity?.runOnUiThread {
            binding.textHome.append("\n$msg")
            Log.i(TAG, msg)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mjpegReceiver.stopStream()
        mjpegReceiver.release()

        _binding = null
    }

}