package com.nuvio.app.features.streamcache

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Plays a cached entry in the Nuvio player without any stream lookup. Provided by the app shell
 * (MainAppContent) for every route, so the manager can offer "Play" both from the standalone
 * screen and from the settings page. Null when playback can't be started from here.
 */
val LocalStreamCachePlayHandler = staticCompositionLocalOf<((StreamCacheItem) -> Unit)?> { null }

/** Whether the manager can start playback of [item] (needs its metadata). */
internal fun StreamCacheItem.isPlayable(): Boolean = context != null && entry.cachedBytes > 0L

/**
 * Tracks the URLs from [StreamCacheRepository.playbackUrlForEntry] used by a player session
 * (keyed by the player launch id) and releases each of them exactly once when the player route
 * is disposed. The iOS player surface never releases these itself: loopback and file:// URLs
 * bypass the cache's preparePlaybackUrl/releasePlaybackUrl pairing. Main thread only.
 */
object StreamCachePlaybackSessions {
    private val urlsByLaunch = mutableMapOf<Long, MutableList<String>>()

    fun attach(launchId: Long, playbackUrl: String) {
        urlsByLaunch.getOrPut(launchId) { mutableListOf() }.add(playbackUrl)
    }

    /** Releases every URL attached to [launchId]; later calls are no-ops. */
    fun release(launchId: Long) {
        val urls = urlsByLaunch.remove(launchId) ?: return
        urls.forEach { url -> runCatching { StreamCacheRepository.releasePlaybackUrl(url) } }
    }
}

/** True for URLs served by the stream cache itself / local files; never cached again. */
fun isStreamCacheLocalUrl(url: String): Boolean {
    val normalized = url.trim().lowercase()
    if (normalized.startsWith("file:")) return true
    val withoutScheme = when {
        normalized.startsWith("http://") -> normalized.removePrefix("http://")
        normalized.startsWith("https://") -> normalized.removePrefix("https://")
        else -> return false
    }
    val authority = withoutScheme.substringBefore('/').substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    return host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "0.0.0.0"
}
