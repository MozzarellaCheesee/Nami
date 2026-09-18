package dev.nami.app.discord

import android.app.Activity
import com.discord.socialsdk.DiscordSocialSdkInit
import dev.nami.domain.DiscordPresence

internal object DiscordSdk {
    const val available = true
    var ready = false
        private set

    fun initialize(activity: Activity) {
        ready = runCatching {
            System.loadLibrary("nami_discord")
            DiscordSocialSdkInit.setEngineActivity(activity)
        }.isSuccess
    }

    fun open(applicationId: String) = nativeOpen(applicationId.toULong().toLong())
    fun publish(presence: DiscordPresence, paused: Boolean = false) = nativePublish(
        presence.title.toByteArray(Charsets.UTF_8), presence.description.toByteArray(Charsets.UTF_8),
        presence.startSeconds, presence.endSeconds ?: 0, paused,
    )
    fun poll(): Int = nativePoll()
    fun clear() = nativeClear()
    fun close() = nativeClose()

    private external fun nativeOpen(applicationId: Long)
    private external fun nativePublish(title: ByteArray, description: ByteArray, start: Long, end: Long, paused: Boolean)
    private external fun nativePoll(): Int
    private external fun nativeClear()
    private external fun nativeClose()
}
