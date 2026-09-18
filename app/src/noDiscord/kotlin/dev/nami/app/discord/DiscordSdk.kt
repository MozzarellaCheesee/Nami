package dev.nami.app.discord

import android.app.Activity
import dev.nami.domain.DiscordPresence

/** SDK-free development builds keep playback usable and explicitly report missing support. */
internal object DiscordSdk {
    const val available = false
    const val ready = false
    fun initialize(activity: Activity) = Unit
    fun open(applicationId: String) = Unit
    fun publish(presence: DiscordPresence, paused: Boolean = false) = Unit
    fun poll(): Int = -1
    fun clear() = Unit
    fun close() = Unit
}
