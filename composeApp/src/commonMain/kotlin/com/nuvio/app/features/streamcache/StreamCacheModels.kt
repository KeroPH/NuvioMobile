package com.nuvio.app.features.streamcache

import kotlinx.serialization.Serializable

/**
 * Describes the content being streamed so the persistent stream cache can recognise it again
 * (even after the stream link is refreshed) and so a fully cached entry can become a download.
 *
 * Serialized as JSON and handed to the platform cache verbatim; the platform only reads
 * [cacheKey] and [fileName] and returns the JSON unchanged in [StreamCacheEntry.contextJson].
 */
@Serializable
data class StreamCacheContext(
    /** Stable identity of the cached bytes: "<videoId>|<stream identity>". */
    val cacheKey: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val contentType: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    /** Original stream file name when known (used for the cached file's extension). */
    val fileName: String? = null,
)

/** JSON shape returned by the platform cache's `listEntriesJson()` (array of these). */
@Serializable
data class StreamCacheEntry(
    val id: String,
    val contextJson: String,
    val fileName: String,
    val mimeType: String? = null,
    /** -1 when the total size is still unknown. */
    val totalBytes: Long = -1L,
    val cachedBytes: Long = 0L,
    val isComplete: Boolean = false,
    val pinned: Boolean = false,
    /** A player is currently streaming through this entry. */
    val isActive: Boolean = false,
    val isFilling: Boolean = false,
    val fillError: String? = null,
    val createdAtMs: Long = 0L,
    val lastAccessMs: Long = 0L,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (cachedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

/** JSON shape returned by the platform cache's `getConfigJson()`. */
@Serializable
data class StreamCacheConfig(
    val enabled: Boolean = true,
    /** 0 means unlimited (still bounded by [minFreeBytes]). */
    val maxBytes: Long = StreamCacheDefaults.DEFAULT_MAX_BYTES,
    /** Caching pauses (pass-through only) when free disk space falls below this. */
    val minFreeBytes: Long = StreamCacheDefaults.MIN_FREE_BYTES,
    val usedBytes: Long = 0L,
    val freeDiskBytes: Long = -1L,
)

object StreamCacheDefaults {
    const val GB: Long = 1024L * 1024L * 1024L
    const val DEFAULT_MAX_BYTES: Long = 10L * GB
    const val MIN_FREE_BYTES: Long = 2L * GB
    /** Size-limit choices offered in the manager UI; 0 = unlimited. */
    val MAX_BYTES_OPTIONS: List<Long> = listOf(2L * GB, 5L * GB, 10L * GB, 20L * GB, 50L * GB, 100L * GB, 0L)
}

data class StreamCacheItem(
    val entry: StreamCacheEntry,
    /** Null when the context JSON could not be decoded. */
    val context: StreamCacheContext?,
)

data class StreamCacheUiState(
    val isSupported: Boolean = false,
    val config: StreamCacheConfig = StreamCacheConfig(),
    val items: List<StreamCacheItem> = emptyList(),
)

/**
 * Platform side of the stream cache. Only iOS provides one (see `streamCacheBackend()`);
 * Android already has its own ExoPlayer VOD cache.
 */
interface StreamCacheBackend {
    fun preparePlaybackUrl(sourceUrl: String, headers: Map<String, String>, context: StreamCacheContext): String
    fun releasePlaybackUrl(playbackUrl: String)
    fun getConfigJson(): String
    fun setEnabled(enabled: Boolean)
    fun setMaxBytes(maxBytes: Long)
    fun listEntriesJson(): String
    fun deleteEntry(id: String)
    fun clearAll()
    fun setPinned(id: String, pinned: Boolean)
    fun startFill(id: String)
    fun cancelFill(id: String)
    /** Returns null on success, otherwise an error message. */
    fun exportEntry(id: String, destinationPath: String): String?
    fun setChangeListener(listener: (() -> Unit)?)
}

/** Null on platforms without a persistent stream cache. */
internal expect fun streamCacheBackend(): StreamCacheBackend?
