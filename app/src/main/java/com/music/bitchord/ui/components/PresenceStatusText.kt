package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.presence.PresenceState
import com.music.bitchord.presence.SpotifyPresence

/** The Spotify activity's state in words, with whatever the presence process said about it. */
@Composable
fun presenceStatusText(status: SpotifyPresence.Status): String {
    val label = stringResource(
        when (status.state) {
            PresenceState.OFF -> R.string.presence_state_off
            PresenceState.STARTING -> R.string.presence_state_starting
            PresenceState.SIGN_IN_NEEDED -> R.string.presence_state_sign_in
            PresenceState.SEARCHING -> R.string.presence_state_searching
            PresenceState.PLAYING -> R.string.presence_state_playing
            PresenceState.PAUSED -> R.string.presence_state_paused
            PresenceState.NOT_FOUND -> R.string.presence_state_not_found
            PresenceState.ERROR -> R.string.presence_state_error
        },
    )
    return if (status.detail.isBlank()) label else "$label — ${status.detail}"
}
