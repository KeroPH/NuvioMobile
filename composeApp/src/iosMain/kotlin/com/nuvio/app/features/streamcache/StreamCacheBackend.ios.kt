package com.nuvio.app.features.streamcache

import com.nuvio.app.features.downloads.iosDownloadsDirectoryPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSURL

/**
 * Returns an adapter only once Swift has registered its bridge; until then the repository keeps
 * asking again (see StreamCacheRepository.backend).
 */
internal actual fun streamCacheBackend(): StreamCacheBackend? =
    if (NuvioStreamCacheBridgeRegistry.bridge != null) IosStreamCacheBackend else null

internal actual fun streamCacheDownloadsDestination(fileName: String): StreamCacheDownloadsDestination? {
    val safeName = fileName.trim().substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
    val path = "${iosDownloadsDirectoryPath()}/$safeName"
    val uri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
    return StreamCacheDownloadsDestination(path = path, fileUri = uri)
}

/** Adapter over the Swift bridge, looked up on every call so a re-registered bridge is used. */
private object IosStreamCacheBackend : StreamCacheBackend {
    private val bridge: NuvioStreamCacheBridge?
        get() = NuvioStreamCacheBridgeRegistry.bridge

    override fun preparePlaybackUrl(
        sourceUrl: String,
        headers: Map<String, String>,
        context: StreamCacheContext,
    ): String {
        val bridge = bridge ?: return sourceUrl
        val headersJson = JsonObject(headers.mapValues { (_, value) -> JsonPrimitive(value) }).toString()
        val contextJson = StreamCacheRepository.json.encodeToString(StreamCacheContext.serializer(), context)
        return bridge.preparePlaybackUrl(sourceUrl, headersJson, contextJson).ifBlank { sourceUrl }
    }

    override fun releasePlaybackUrl(playbackUrl: String) {
        bridge?.releasePlaybackUrl(playbackUrl)
    }

    override fun getConfigJson(): String = bridge?.getConfigJson() ?: "{}"

    override fun setEnabled(enabled: Boolean) {
        bridge?.setEnabled(enabled)
    }

    override fun setMaxBytes(maxBytes: Long) {
        bridge?.setMaxBytes(maxBytes)
    }

    override fun listEntriesJson(): String = bridge?.listEntriesJson() ?: "[]"

    override fun deleteEntry(id: String) {
        bridge?.deleteEntry(id)
    }

    override fun clearAll() {
        bridge?.clearAll()
    }

    override fun setPinned(id: String, pinned: Boolean) {
        bridge?.setPinned(id, pinned)
    }

    override fun startFill(id: String) {
        bridge?.startFill(id)
    }

    override fun cancelFill(id: String) {
        bridge?.cancelFill(id)
    }

    override fun exportEntry(id: String, destinationPath: String): String? {
        val bridge = bridge ?: return "Stream cache is not available."
        return bridge.exportEntry(id, destinationPath).takeIf { it.isNotBlank() }
    }

    override fun setChangeListener(listener: (() -> Unit)?) {
        val bridge = bridge ?: return
        if (listener == null) {
            bridge.setChangeListener(null)
        } else {
            bridge.setChangeListener(
                object : StreamCacheChangeListener {
                    override fun onChanged() {
                        listener()
                    }
                },
            )
        }
    }
}
