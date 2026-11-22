package com.nuvio.app.features.streamcache

/** Android keeps using the ExoPlayer VOD cache; no persistent stream cache backend here. */
internal actual fun streamCacheBackend(): StreamCacheBackend? = null

internal actual fun streamCacheDownloadsDestination(fileName: String): StreamCacheDownloadsDestination? = null
