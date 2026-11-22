package com.nuvio.app.features.streamcache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Persistent stream cache ("what you streamed stays on the device") and its manager state.
 * All functions must be called from the main thread.
 */
object StreamCacheRepository {
    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private var resolvedBackend: StreamCacheBackend? = null

    /**
     * Resolved on demand: the iOS bridge is registered by Swift at app start and may not exist yet
     * the first time this object is touched, so a missing backend is looked up again next time.
     */
    private val backend: StreamCacheBackend?
        get() = resolvedBackend ?: streamCacheBackend()?.also { resolvedBackend = it }

    private val _uiState = MutableStateFlow(StreamCacheUiState())
    val uiState: StateFlow<StreamCacheUiState> = _uiState.asStateFlow()

    private var listenerInstalled = false

    val isSupported: Boolean get() = backend != null

    /** Starts observing the platform cache. Idempotent. */
    fun ensureObserving() {
        val backend = backend ?: return
        if (!listenerInstalled) {
            listenerInstalled = true
            backend.setChangeListener { refresh() }
        }
        refresh()
    }

    fun refresh() {
        val backend = backend ?: run {
            _uiState.value = StreamCacheUiState(isSupported = false)
            return
        }
        val config = runCatching { json.decodeFromString<StreamCacheConfig>(backend.getConfigJson()) }
            .getOrDefault(StreamCacheConfig())
        val entries = runCatching { json.decodeFromString<List<StreamCacheEntry>>(backend.listEntriesJson()) }
            .getOrDefault(emptyList())
        val items = entries
            .map { entry ->
                StreamCacheItem(
                    entry = entry,
                    context = runCatching { json.decodeFromString<StreamCacheContext>(entry.contextJson) }.getOrNull(),
                )
            }
            .sortedByDescending { it.entry.lastAccessMs }
        _uiState.value = StreamCacheUiState(isSupported = true, config = config, items = items)
    }

    /**
     * Returns the URL the player should load for [sourceUrl]: a local caching proxy URL when the
     * cache applies, otherwise [sourceUrl] unchanged. Pair every call with [releasePlaybackUrl].
     */
    fun preparePlaybackUrl(
        sourceUrl: String,
        headers: Map<String, String>,
        context: StreamCacheContext?,
    ): String {
        val backend = backend ?: return sourceUrl
        if (context == null) return sourceUrl
        return runCatching { backend.preparePlaybackUrl(sourceUrl, headers, context) }.getOrDefault(sourceUrl)
    }

    /**
     * URL that plays cached entry [id] without any stream lookup (offline-capable):
     * a file:// URL when complete, otherwise a proxy URL that must be released with
     * [releasePlaybackUrl] when playback ends. Null when the entry isn't playable.
     */
    fun playbackUrlForEntry(id: String): String? = backend?.entryPlaybackUrl(id)

    /** The fully cached entry for [videoId], if any (used to play offline without stream lookup). */
    fun findCompleteEntryForVideo(videoId: String?): StreamCacheItem? {
        val target = videoId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (backend == null) return null
        refresh()
        return _uiState.value.items
            .filter { it.entry.isComplete && it.context?.videoId?.trim() == target }
            .maxByOrNull { it.entry.lastAccessMs }
    }

    fun releasePlaybackUrl(playbackUrl: String) {
        backend?.releasePlaybackUrl(playbackUrl)
    }

    fun setEnabled(enabled: Boolean) {
        backend?.setEnabled(enabled)
        refresh()
    }

    fun setMaxBytes(maxBytes: Long) {
        backend?.setMaxBytes(maxBytes)
        refresh()
    }

    fun setAutoFillWhilePlaying(enabled: Boolean) {
        backend?.setAutoFillWhilePlaying(enabled)
        refresh()
    }

    fun setLookAheadMinutes(minutes: Int) {
        backend?.setLookAheadMinutes(minutes)
        refresh()
    }

    fun delete(id: String) {
        backend?.deleteEntry(id)
        refresh()
    }

    fun clearAll() {
        backend?.clearAll()
        refresh()
    }

    fun setPinned(id: String, pinned: Boolean) {
        backend?.setPinned(id, pinned)
        refresh()
    }

    fun startFill(id: String) {
        backend?.startFill(id)
        refresh()
    }

    fun cancelFill(id: String) {
        backend?.cancelFill(id)
        refresh()
    }

    /**
     * Moves a fully cached entry into Downloads as a completed, offline-playable download.
     * Returns null on success, otherwise an error message.
     */
    fun saveToDownloads(id: String): String? {
        // Implemented by the downloads integration (see StreamCacheDownloadsExport.kt).
        val result = exportStreamCacheEntryToDownloads(id, backend)
        refresh()
        return result
    }
}
