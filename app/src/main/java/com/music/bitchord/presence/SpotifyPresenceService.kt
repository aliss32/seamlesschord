package com.music.bitchord.presence

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/**
 * Runs in the `:spotify` process (see the manifest) and is bound only while
 * the app's Spotify activity setting is on and something is playing. Unbinding
 * ends the session and, with nothing left running, lets the system reclaim the
 * whole process — the web page costs nothing while the feature is off.
 */
class SpotifyPresenceService : Service() {

    private var client: Messenger? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.replyTo != null) client = msg.replyTo
            val data = msg.data
            when (msg.what) {
                PresenceProtocol.MSG_CONFIGURE -> {
                    PresenceWebHost.configure(data.getString(PresenceProtocol.KEY_SPDC).orEmpty())
                }
                PresenceProtocol.MSG_TRACK -> {
                    PresenceWebHost.play(
                        title = data.getString(PresenceProtocol.KEY_TITLE).orEmpty(),
                        artist = data.getString(PresenceProtocol.KEY_ARTIST).orEmpty(),
                        durationSec = data.getInt(PresenceProtocol.KEY_DURATION_SEC, -1).takeIf { it > 0 },
                    )
                }
                PresenceProtocol.MSG_PAUSE -> PresenceWebHost.pause()
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(handler)

    private val report: (PresenceState, String) -> Unit = { state, detail ->
        val target = client
        if (target != null) {
            val message = Message.obtain(null, PresenceProtocol.MSG_STATUS).apply {
                data = Bundle().apply {
                    putString(PresenceProtocol.KEY_STATE, state.name)
                    putString(PresenceProtocol.KEY_DETAIL, detail)
                }
            }
            try {
                target.send(message)
            } catch (_: RemoteException) {
                client = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PresenceWebHost.init(applicationContext)
        PresenceWebHost.serviceActive = true
        // Nothing here is worth a core the player could use.
        PresenceWebHost.setLowPriority(true)
        PresenceWebHost.addObserver(report)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        PresenceWebHost.stop()
        return false
    }

    override fun onDestroy() {
        PresenceWebHost.removeObserver(report)
        PresenceWebHost.serviceActive = false
        PresenceWebHost.stop()
        super.onDestroy()
    }
}
