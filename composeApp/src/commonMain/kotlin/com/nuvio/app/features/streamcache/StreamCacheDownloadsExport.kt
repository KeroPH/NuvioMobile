package com.nuvio.app.features.streamcache

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests

/** Where an exported cache file goes: an absolute file path and the matching download URI. */
internal data class StreamCacheDownloadsDestination(
    val path: String,
    /** Same format the platform downloader stores in DownloadItem.localFileUri. */
    val fileUri: String,
)

/** Null on platforms without a downloads directory for the stream cache. */
internal expect fun streamCacheDownloadsDestination(fileName: String): StreamCacheDownloadsDestination?

/**
 * Moves a fully cached stream into Downloads as a completed, offline-playable download.
 * Returns null on success, otherwise a user-facing error message. Main thread only.
 */
internal fun exportStreamCacheEntryToDownloads(id: String, backend: StreamCacheBackend?): String? {
    backend ?: return "Stream cache is not available on this device."
    val entries = runCatching {
        StreamCacheRepository.json.decodeFromString<List<StreamCacheEntry>>(backend.listEntriesJson())
    }.getOrElse { return "Could not read the stream cache." }
    val entry = entries.firstOrNull { it.id == id } ?: return "This cached stream no longer exists."
    if (!entry.isComplete) return "This stream is not fully cached yet."
    if (entry.isActive) return "This stream is playing right now. Close the player and try again."
    val context = runCatching {
        StreamCacheRepository.json.decodeFromString<StreamCacheContext>(entry.contextJson)
    }.getOrNull() ?: return "This cached stream has no title information and cannot be saved."

    val (downloadId, fileName) = DownloadsRepository.reserveImportedDownload(
        title = context.title,
        seasonNumber = context.seasonNumber,
        episodeNumber = context.episodeNumber,
        episodeTitle = context.episodeTitle,
        fallbackTitle = context.streamTitle,
        fileExtension = exportFileExtension(entry, context),
    )
    val destination = streamCacheDownloadsDestination(fileName)
        ?: return "Downloads are not available on this device."

    backend.exportEntry(id, destination.path)?.takeIf { it.isNotBlank() }?.let { return it }

    val now = DownloadsClock.nowEpochMs()
    val size = entry.totalBytes.takeIf { it > 0L } ?: entry.cachedBytes.takeIf { it > 0L }
    DownloadsRepository.importCompletedDownload(
        DownloadItem(
            id = downloadId,
            contentType = context.contentType,
            parentMetaId = context.parentMetaId,
            parentMetaType = context.parentMetaType,
            videoId = context.videoId,
            title = context.title,
            logo = context.logo,
            poster = context.poster,
            background = context.background,
            seasonNumber = context.seasonNumber,
            episodeNumber = context.episodeNumber,
            episodeTitle = context.episodeTitle,
            episodeThumbnail = context.episodeThumbnail,
            streamTitle = context.streamTitle,
            streamSubtitle = context.streamSubtitle,
            providerName = context.providerName,
            providerAddonId = context.providerAddonId,
            // The original link is only metadata for a completed download (never re-fetched);
            // fall back to the local file when the stream URL is no longer known.
            sourceUrl = StreamCacheContextRegistry.lastSourceUrlFor(context.cacheKey) ?: destination.fileUri,
            // Lets the offline player still fetch addon subtitles, like stream downloads do.
            subtitleRequests = addonSubtitleRequests(
                type = context.contentType,
                videoId = context.videoId,
                extras = SubtitleRequestExtras(filename = context.fileName, videoSize = size)
                    .takeIf { it.toPathSegment() != null },
            ),
            localFileUri = destination.fileUri,
            fileName = fileName,
            status = DownloadStatus.Completed,
            downloadedBytes = size ?: 0L,
            totalBytes = size,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        ),
    )
    return null
}

private fun exportFileExtension(entry: StreamCacheEntry, context: StreamCacheContext): String =
    listOfNotNull(entry.fileName, context.fileName)
        .map { it.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().trim() }
        .firstOrNull { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
        ?: "mp4"
