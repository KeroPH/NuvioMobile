package com.nuvio.app.features.streamcache

/**
 * Bridge to the Swift persistent stream cache (iosApp/iosApp/StreamCache/).
 * Swift implements this protocol and registers it with [NuvioStreamCacheBridgeRegistry]
 * at app startup (next to NuvioPlayerRegistration). All methods are called on the main thread
 * and must return quickly (no network I/O; disk metadata only).
 */
interface NuvioStreamCacheBridge {
    /**
     * Returns the URL mpv should load. Returns [sourceUrl] unchanged when the cache does not
     * apply (disabled, non-http(s), loopback, HLS/DASH manifests, low disk space, ...).
     * [headersJson] is a JSON object of request headers for the upstream; [contextJson] is a
     * serialized StreamCacheContext (read `cacheKey` and `fileName`, store the rest verbatim).
     */
    fun preparePlaybackUrl(sourceUrl: String, headersJson: String, contextJson: String): String

    /** The player that loaded [playbackUrl] is gone; stop upstream work for it. */
    fun releasePlaybackUrl(playbackUrl: String)

    /** JSON matching StreamCacheConfig. */
    fun getConfigJson(): String
    fun setEnabled(enabled: Boolean)
    /** 0 = unlimited. */
    fun setMaxBytes(maxBytes: Long)
    /** Start a background "finish download" automatically whenever a cached stream starts playing. */
    fun setAutoFillWhilePlaying(enabled: Boolean)
    /** Player read-ahead in minutes (0 = mpv default). Applied by the Swift player on the next load. */
    fun setLookAheadMinutes(minutes: Int)

    /** JSON array matching List<StreamCacheEntry>. */
    fun listEntriesJson(): String
    fun deleteEntry(id: String)
    fun clearAll()
    fun setPinned(id: String, pinned: Boolean)

    /** Downloads the missing byte ranges of an entry in the background. */
    fun startFill(id: String)
    fun cancelFill(id: String)

    /**
     * Moves the file of a complete entry to [destinationPath] (an absolute file path; parent
     * directories are created) and removes the entry. Returns "" on success, else an error message.
     */
    fun exportEntry(id: String, destinationPath: String): String

    /** At most ~2 calls per second, always on the main thread. */
    fun setChangeListener(listener: StreamCacheChangeListener?)
}

interface StreamCacheChangeListener {
    fun onChanged()
}

object NuvioStreamCacheBridgeRegistry {
    var bridge: NuvioStreamCacheBridge? = null
        private set

    fun register(bridge: NuvioStreamCacheBridge) {
        this.bridge = bridge
    }
}
