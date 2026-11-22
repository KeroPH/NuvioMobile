package com.nuvio.app.features.player

import com.nuvio.app.features.streamcache.StreamCacheContext
import com.nuvio.app.features.streamcache.StreamCacheContextRegistry
import com.nuvio.app.features.streamcache.isStreamCacheLocalUrl

/**
 * Describes the active stream for the persistent stream cache, or null when it must not be
 * cached: P2P playback, local/downloaded files, separate audio tracks, or no video id.
 */
internal fun PlayerScreenRuntime.activeStreamCacheContext(sourceUrl: String): StreamCacheContext? {
    if (activeTorrentInfoHash != null) return null
    val normalizedUrl = sourceUrl.trim().lowercase()
    if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) return null
    // Already served from the stream cache (loopback proxy) or another local server: never cache again.
    if (isStreamCacheLocalUrl(sourceUrl)) return null
    if (activeSourceAudioUrl != null) return null
    val videoId = activeVideoId?.trim()?.takeIf { it.isNotBlank() } ?: return null

    val fileName = (activeTorrentFilename ?: activeSourceFilename ?: activeStreamFilename)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val streamTitle = activeStreamTitle
    val providerName = activeProviderName
    return StreamCacheContext(
        cacheKey = StreamCacheContextRegistry.buildCacheKey(
            videoId = videoId,
            fileName = fileName,
            streamTitle = streamTitle,
            providerName = providerName,
        ),
        videoId = videoId,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType?.takeIf { it.isNotBlank() } ?: parentMetaType,
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        streamTitle = streamTitle,
        streamSubtitle = activeStreamSubtitle,
        providerName = providerName,
        providerAddonId = activeProviderAddonId,
        fileName = fileName,
    )
}

/** Registers the active stream's cache context for [sourceUrl] (no-op when it must not be cached). */
internal fun PlayerScreenRuntime.registerActiveStreamCacheContext(sourceUrl: String) {
    val context = activeStreamCacheContext(sourceUrl)
    if (context == null) {
        StreamCacheContextRegistry.unregister(sourceUrl)
    } else {
        StreamCacheContextRegistry.register(sourceUrl, context)
    }
}
