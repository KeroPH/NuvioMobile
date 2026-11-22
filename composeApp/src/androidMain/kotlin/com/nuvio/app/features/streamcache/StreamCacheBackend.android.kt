package com.nuvio.app.features.streamcache

import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.streamcache.android.AndroidStreamCacheBackend
import java.io.File

/**
 * Android persistent stream cache (see Docs/stream-cache.md, "Android port"). Returns null until
 * the app Context is available or while AndroidStreamCacheBackend.IMPLEMENTATION_READY is false;
 * StreamCacheRepository asks again on the next access.
 */
@Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
internal actual fun streamCacheBackend(): StreamCacheBackend? =
    // Checked here too so the TODO() skeleton is never reached while the gate is off.
    if (AndroidStreamCacheBackend.IMPLEMENTATION_READY) AndroidStreamCacheBackend.getOrNull() else null

/**
 * Same location and URI format as completed Android downloads (AndroidDownloadScheduler):
 * `<filesDir>/downloads/<fileName>` and `File.toURI().toString()` ("file:/data/user/0/...").
 */
internal actual fun streamCacheDownloadsDestination(fileName: String): StreamCacheDownloadsDestination? {
    val context = PlayerSettingsStorage.applicationContext() ?: return null
    val safeName = fileName.trim().substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
        ?: return null
    val file = File(File(context.filesDir, "downloads"), safeName)
    return StreamCacheDownloadsDestination(path = file.absolutePath, fileUri = file.toURI().toString())
}
