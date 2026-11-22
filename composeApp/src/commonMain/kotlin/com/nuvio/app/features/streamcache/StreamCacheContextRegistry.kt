package com.nuvio.app.features.streamcache

/**
 * Source URL -> [StreamCacheContext] for the streams the player is about to play. The player screen
 * registers the active stream before the platform surface is composed; the iOS surface looks the
 * context up to decide whether to route playback through the persistent stream cache. URLs without
 * a registered context (trailers, local files, P2P) are played directly. Main thread only.
 */
object StreamCacheContextRegistry {
    private const val MAX_ENTRIES = 8

    private val contexts = LinkedHashMap<String, StreamCacheContext>()

    fun register(sourceUrl: String, context: StreamCacheContext) {
        val key = sourceUrl.trim()
        if (key.isBlank()) return
        if (contexts[key] == context) return
        contexts.remove(key)
        contexts[key] = context
        while (contexts.size > MAX_ENTRIES) {
            val eldest = contexts.keys.firstOrNull() ?: break
            contexts.remove(eldest)
        }
    }

    fun unregister(sourceUrl: String) {
        contexts.remove(sourceUrl.trim())
    }

    fun get(sourceUrl: String): StreamCacheContext? = contexts[sourceUrl.trim()]

    /** Most recently registered source URL for [cacheKey], if still known. */
    fun lastSourceUrlFor(cacheKey: String): String? =
        contexts.entries.lastOrNull { it.value.cacheKey == cacheKey }?.key

    /** "<videoId>|<file name or stream title|provider>", trimmed and lower-cased. */
    fun buildCacheKey(
        videoId: String,
        fileName: String?,
        streamTitle: String,
        providerName: String,
    ): String {
        val identity = fileName?.trim()?.takeIf { it.isNotBlank() }
            ?: "${streamTitle.trim()}|${providerName.trim()}"
        return "${videoId.trim()}|$identity".trim().lowercase()
    }
}
