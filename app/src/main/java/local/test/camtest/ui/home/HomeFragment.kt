package local.test.camtest.ui.home

import MinimalSdpServer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.test.camtest.R
import local.test.camtest.databinding.FragmentHomeBinding
import local.test.camtest.protocol.CTPCommand
import local.test.camtest.protocol.CTPProtocol
import local.test.camtest.protocol.JFIFMJpegStreamReceiver
import local.test.camtest.protocol.RtpConvertProxy
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
    private var use_dump: Boolean = false

    private var use_pcapplayer: Boolean = false

    private var logCount = 0

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null

    private val nativeConnection = RtpConvertProxy()

    private lateinit var sdpServer: MinimalSdpServer

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        //binding.surfaceView.holder.addCallback(this)
        mjpegReceiver = JFIFMJpegStreamReceiver()

        sdpServer = MinimalSdpServer(requireContext())

        val options = arrayListOf(
            "--network-caching=0",
            "--vout=android-display",
       //     "--avcodec-codec=mjpeg",
            "--verbose",
            "--verbose=3",
            //     "--codec=avcodec",
            //      "--file-caching=150",
            //      "--clock-jitter=0",
            //      "--live-caching=150",
            //       "--drop-late-frames",
            //     "--skip-frames",

            //   "--sout-transcode-vb=20",
            "--no-audio",
        )

        try {
            videoLayout = binding.videoLayout
            libVLC = LibVLC(context, options)
            mediaPlayer = MediaPlayer(libVLC).apply {
                setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                attachViews(videoLayout!!, null, false, false)
            }
            mediaPlayer!!.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Opening -> Log.d("VLC", "Opening...")
                    MediaPlayer.Event.Playing -> Log.d("VLC", "Playing!")
                    MediaPlayer.Event.Paused -> Log.d("VLC", "Paused")
                    MediaPlayer.Event.Stopped -> Log.d("VLC", "Stopped")
                    MediaPlayer.Event.EndReached -> Log.d("VLC", "End reached")
                    MediaPlayer.Event.EncounteredError -> Log.e("VLC", "Error: ${event.type}")
                    else -> Log.d("VLC", "Event: ${event.type}")

                }
            }


        } catch (e: Exception) {
            Log.e(TAG,e.message,e)
        }


        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                // Checkbox-Status setzen
                menu.findItem(R.id.action_quality_hd)?.isChecked = use_hd
                menu.findItem(R.id.action_udp_pcap_dump)?.isChecked = use_dump
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                val protocol = CTPProtocol()

                return when (menuItem.itemId) {

                    R.id.action_quality_hd -> {
                        menuItem.isChecked = !menuItem.isChecked
                        // HD Qualität auswählen
                        use_hd = !use_hd
                        true
                    }
                    R.id.action_udp_pcap_dump -> {
                        menuItem.isChecked = !menuItem.isChecked
                        // HD Qualität auswählen
                        use_dump = !use_dump
                        true
                    }
                    R.id.action_udp_pcapplayer -> {
                        menuItem.isChecked = !menuItem.isChecked
                        // HD Qualität auswählen
                        use_pcapplayer = !use_pcapplayer
                        true
                    }

                    R.id.action_test -> {
                        stopVLC()
                        playvideo()
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

                        //mjpegReceiver.stopStream()
                        //mjpegReceiver.startStream(use_dump)

                        if (!use_pcapplayer)
                        {
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
                        }
                        else
                        {
                            //Simply listen on udp:2224 for debugging purposes
                        }

                        true
                    }

                    R.id.action_close_rt_stream -> {

                        mjpegReceiver.stopStream()
                        stopVLC()

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

    fun startVLC() {
        try {


            val uri = Uri.parse( sdpServer.getSdpUrl(getLocalWifiLikeIp()))  //Uri.parse("rtp://192.168.1.2:6666") //
            log("Starting stream on: "+uri.toString())
            val media = Media(libVLC, uri).apply {
                addOption(":network-caching=0")
              //  addOption(":file-caching=1500")
                //    addOption(":live-caching=500")
                addOption(":no-audio")
                //   addOption(":rtp-ipv4=yes")
                //   addOption(":ipv4-timeout=5000")
                addOption(":verbose=3")
                addOption(":log-verbose=3")
                //     addOption(":no-drop-late-frames")
                //     addOption(":no-skip-frames")
            }

            mediaPlayer?.apply {
                setMedia(media)
                play()
            }

            media.release()
        } catch (e: Exception) {
            Log.e(TAG,e.message,e)
        }
    }

    fun getLocalWifiLikeIp(): String {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address) {
                    val ip = addr.hostAddress
                    // Prüfe, ob IP wie typische private WLAN-IP aussieht
                    if (ip.startsWith("192.168.")) {
                        return ip
                    }
                }
            }
        }
        return "127.0.0.1"
    }

    private fun copyAssetToCache(assetName: String): File {
        val tempFile = File(requireContext().cacheDir, assetName)

        // Nur kopieren wenn nicht bereits vorhanden
        if (!tempFile.exists()) {
            requireContext().assets.open(assetName).use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d("VLC", "Asset copied to: ${tempFile.absolutePath}")
        }

        return tempFile
    }

    fun playvideo()
    {
        try {
            var file = copyAssetToCache("file_example_MP4_640_3MG.mp4")

            var media = Media(libVLC, Uri.fromFile(file));
            media.setHWDecoderEnabled(true, false);

            mediaPlayer!!.setMedia(media);
            media.release();
            mediaPlayer!!.play();

        } catch (e : Exception) {
            Log.e(TAG,e.message,e)
        }
    }

    fun stopVLC() {
        mediaPlayer?.stop()
        mediaPlayer?.setMedia(null)
        sdpServer.stopServer()
    }

    fun releaseVLC() {
        mediaPlayer?.detachViews()
        mediaPlayer?.release()
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
        videoLayout = null
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

            override fun onPcapDumpStarted(filePath: String) {
                log("capturing to file: "+filePath)
            }

            override fun onPcapDumpStopped(filePath: String, packetCount: Int) {
                log(filePath+" created, captured: "+packetCount.toString()+" packets")
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

                            var json = JSONObject(message.json)
                            var param = json.getJSONObject("param")

                            val ip = getLocalWifiLikeIp()

                            nativeConnection.stop()

                            var destPort = 5000
                            nativeConnection.start(ip, destPort, true, param.getInt("fps"))

                            stopVLC()
                            sdpServer.startServer(param.getInt("w"),param.getInt("h"),destPort, param.getInt("fps"))
                            startVLC()

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
/*
        mjpegReceiver.stopStream()
        mjpegReceiver.release()
        _binding = null
        stopVLC()
        nativeConnection.stop()
        releaseVLC()*/
    }

}