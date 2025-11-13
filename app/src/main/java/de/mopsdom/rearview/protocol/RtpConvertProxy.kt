package de.mopsdom.rearview.protocol

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.view.SurfaceView

class RtpConvertProxy {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private lateinit var surfaceView: SurfaceView

    private var streamThread: Thread? = null
    @Volatile private var running = false

    public fun startStream(destPort: Int) : Boolean {

        if (!start(destPort)) return false  // JNI start

        running = true
        streamThread = Thread {
            run()
        }.also { it.start() }

        return true
    }
    private external fun start(destPort: Int): Boolean

    public fun stopStream() {
        running = false
        streamThread?.join()
        streamThread = null
        stop() // JNI stop
    }
    private external fun stop()

    private external fun getNextFrame(): ByteArray;

    public fun setSurface(view: SurfaceView) {
        surfaceView = view
    }
    private fun run() {
        while (running) {
            if (surfaceView!=null) {
                val jpegBytes = getNextFrame() ?: continue
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    surfaceView.post {
                        val canvas = surfaceView.holder.lockCanvas()
                        canvas?.let {
                            it.drawColor(Color.BLACK)
                            // Breite voll, Höhe proportional
                            val scale = it.width.toFloat() / bitmap.width.toFloat()
                            val scaledHeight = bitmap.height * scale

                            // Vertikal zentrieren
                            val top = (it.height - scaledHeight) / 2f
                            val destRect = RectF(0f, top, it.width.toFloat(), top + scaledHeight)

                            it.drawBitmap(bitmap, null, destRect, null)
                            surfaceView.holder.unlockCanvasAndPost(it)
                        }
                    }
                }
            }
            else
            {
                break
            }
        }
    }
}