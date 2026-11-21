package com.music.bitchord.presence

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * A screen that exists only in memory, so the Spotify app can be opened
 * somewhere the listener never sees it. Nothing is ever drawn to a real
 * display; the frames it produces are thrown away as they arrive.
 *
 * It lives exactly as long as a Spotify session: [close] removes it, and with
 * it anything that was open on it. If the system refuses to put Spotify on it,
 * [RootSpotify] falls back to opening Spotify on the real screen for a moment.
 */
internal object VirtualScreen {

    private const val WIDTH = 540
    private const val HEIGHT = 960
    private const val DPI = 240

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null

    /** The id of the screen, creating it if needed; null if the system would not make one. */
    @Synchronized
    fun open(context: Context): Int? {
        display?.display?.let { return it.displayId }
        return runCatching {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val thread = HandlerThread("presence-screen").also { it.start() }
            val images = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
            images.setOnImageAvailableListener(
                { source -> runCatching { source.acquireLatestImage()?.close() } },
                Handler(thread.looper),
            )
            val created = manager.createVirtualDisplay(
                "SeamlessChord presence",
                WIDTH,
                HEIGHT,
                DPI,
                images.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            if (created == null) {
                images.close()
                thread.quitSafely()
                RootSpotify.log("virtual screen: the system returned none")
                return@runCatching null
            }
            worker = thread
            reader = images
            display = created
            RootSpotify.log("virtual screen: id ${created.display.displayId}")
            created.display.displayId
        }.getOrElse {
            RootSpotify.log("virtual screen: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    @Synchronized
    fun close() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { worker?.quitSafely() }
        display = null
        reader = null
        worker = null
    }
}
