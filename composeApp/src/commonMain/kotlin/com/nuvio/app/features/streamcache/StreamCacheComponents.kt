package com.nuvio.app.features.streamcache

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.downloads.DownloadTextSeparator
import com.nuvio.app.features.downloads.DownloadThumbnail
import com.nuvio.app.features.downloads.formatBytes
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.stream_cache_action_finish
import nuvio.composeapp.generated.resources.stream_cache_action_pin
import nuvio.composeapp.generated.resources.stream_cache_action_save
import nuvio.composeapp.generated.resources.stream_cache_action_stop
import nuvio.composeapp.generated.resources.stream_cache_action_unpin
import nuvio.composeapp.generated.resources.stream_cache_cached_unknown_total
import nuvio.composeapp.generated.resources.stream_cache_disabled_note
import nuvio.composeapp.generated.resources.stream_cache_free_space
import nuvio.composeapp.generated.resources.stream_cache_items
import nuvio.composeapp.generated.resources.stream_cache_low_space
import nuvio.composeapp.generated.resources.stream_cache_status_complete
import nuvio.composeapp.generated.resources.stream_cache_status_filling
import nuvio.composeapp.generated.resources.stream_cache_status_pinned
import nuvio.composeapp.generated.resources.stream_cache_status_playing
import nuvio.composeapp.generated.resources.stream_cache_storage_label
import nuvio.composeapp.generated.resources.stream_cache_storage_of_limit
import nuvio.composeapp.generated.resources.stream_cache_storage_unlimited
import nuvio.composeapp.generated.resources.stream_cache_unknown_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// ---------------------------------------------------------------------------------------------
// Display helpers
// ---------------------------------------------------------------------------------------------

/** Formats a size-limit option without a trailing ".0" (e.g. "10 GB"); 0 is handled by callers. */
internal fun formatStreamCacheLimit(bytes: Long): String =
    if (bytes > 0L && bytes % StreamCacheDefaults.GB == 0L) {
        "${bytes / StreamCacheDefaults.GB} ${localizedByteUnit("GB")}"
    } else {
        formatBytes(bytes)
    }

internal fun StreamCacheItem.titleOrNull(): String? =
    context?.title?.trim()?.takeIf { it.isNotBlank() }
        ?: entry.fileName.trim().takeIf { it.isNotBlank() }

@Composable
internal fun StreamCacheItem.displayTitle(): String =
    titleOrNull() ?: stringResource(Res.string.stream_cache_unknown_title)

internal fun StreamCacheItem.episodeLabel(): String? {
    val context = context ?: return null
    val code = localizedSeasonEpisodeCode(context.seasonNumber, context.episodeNumber) ?: return null
    return listOfNotNull(code, context.episodeTitle?.trim()?.takeIf { it.isNotBlank() })
        .joinToString(DownloadTextSeparator)
}

internal fun StreamCacheItem.streamLabel(): String? {
    val context = context ?: return null
    return listOfNotNull(
        context.streamTitle.trim().takeIf { it.isNotBlank() },
        context.providerName.trim().takeIf { it.isNotBlank() },
    ).distinct().joinToString(DownloadTextSeparator).takeIf { it.isNotBlank() }
}

internal fun StreamCacheItem.landscapeArtwork(): String? {
    val context = context ?: return null
    return listOf(context.episodeThumbnail, context.background, context.poster)
        .firstOrNull { !it.isNullOrBlank() }
}

@Composable
internal fun StreamCacheEntry.sizeText(): String {
    val total = totalBytes.takeIf { it > 0L }
    return when {
        total == null -> stringResource(Res.string.stream_cache_cached_unknown_total, formatBytes(cachedBytes))
        isComplete -> formatBytes(total)
        else -> listOf(
            "${formatBytes(cachedBytes)} / ${formatBytes(total)}",
            "${(progressFraction * 100f).toInt()}%",
        ).joinToString(DownloadTextSeparator)
    }
}

// ---------------------------------------------------------------------------------------------
// Storage header card
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StreamCacheStorageCard(
    config: StreamCacheConfig,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val used = config.usedBytes.coerceAtLeast(0L)
    val free = config.freeDiskBytes
    val progress = when {
        config.maxBytes > 0L -> used.toFloat() / config.maxBytes.toFloat()
        free > 0L -> used.toFloat() / (used + free).toFloat()
        else -> 0f
    }.coerceIn(0f, 1f)
    val lowOnSpace = free in 0L until config.minFreeBytes

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NuvioTokens.Space.s16),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTokens.Space.s40)
                        .clip(tokens.shapes.compactCard)
                        .background(tokens.colors.accent.copy(alpha = tokens.opacity.pressed)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier.size(NuvioTokens.Icon.md),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.stream_cache_storage_label),
                        style = MaterialTheme.nuvioTypeScale.bodySm,
                        color = tokens.colors.textMuted,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = formatBytes(used),
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (config.maxBytes > 0L) {
                                stringResource(Res.string.stream_cache_storage_of_limit, formatStreamCacheLimit(config.maxBytes))
                            } else {
                                stringResource(Res.string.stream_cache_storage_unlimited)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                            modifier = Modifier.padding(bottom = NuvioTokens.Space.s2),
                        )
                    }
                }
                Text(
                    text = pluralStringResource(Res.plurals.stream_cache_items, itemCount, itemCount),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textSecondary,
                )
            }
            NuvioProgressBar(
                progress = progress,
                height = NuvioTokens.Space.s6,
                trackColor = tokens.colors.borderSubtle,
                fillColor = if (config.maxBytes > 0L && progress >= 0.98f) tokens.colors.warning else tokens.colors.accent,
            )
            if (free >= 0L) {
                Text(
                    text = stringResource(Res.string.stream_cache_free_space, formatBytes(free)),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                )
            }
            if (!config.enabled) {
                Text(
                    text = stringResource(Res.string.stream_cache_disabled_note),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textSecondary,
                )
            } else if (lowOnSpace) {
                Text(
                    text = stringResource(Res.string.stream_cache_low_space, formatStreamCacheLimit(config.minFreeBytes)),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.warning,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamCacheRow(
    item: StreamCacheItem,
    onOpenActions: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val entry = item.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpenActions, onLongClick = onOpenActions),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadThumbnail(imageUrl = item.landscapeArtwork())
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.pinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = stringResource(Res.string.stream_cache_status_pinned),
                        tint = tokens.colors.accent,
                        modifier = Modifier.size(NuvioTokens.Icon.xs),
                    )
                }
                Text(
                    text = item.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.episodeLabel()?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.streamLabel()?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.isComplete && entry.totalBytes > 0L) {
                NuvioProgressBar(
                    progress = entry.progressFraction,
                    modifier = Modifier.padding(vertical = NuvioTokens.Space.s2),
                    height = NuvioTokens.Space.s3,
                    trackColor = tokens.colors.borderSubtle,
                    fillColor = tokens.colors.accent,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                Text(
                    text = entry.sizeText(),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
                if (entry.isComplete) {
                    StreamCacheBadge(
                        text = stringResource(Res.string.stream_cache_status_complete),
                        color = tokens.colors.success,
                    )
                }
                if (entry.isActive) {
                    StreamCacheBadge(
                        text = stringResource(Res.string.stream_cache_status_playing),
                        color = tokens.colors.accent,
                    )
                }
                if (entry.isFilling) {
                    StreamCacheBadge(
                        text = stringResource(Res.string.stream_cache_status_filling),
                        color = tokens.colors.accent,
                    )
                }
            }
            entry.fillError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenActions) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.details_actions_menu_label),
                tint = tokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun StreamCacheBadge(
    text: String,
    color: Color,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s1),
        style = MaterialTheme.nuvioTypeScale.bodySm,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
internal fun StreamCacheEmptyState(
    title: String,
    message: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s24, vertical = NuvioTokens.Space.s40),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = NuvioTokens.Space.s8)
                .size(NuvioTokens.Space.s64)
                .clip(tokens.shapes.avatar)
                .background(tokens.colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(NuvioTokens.Icon.xl),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Action sheet
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamCacheActionSheet(
    item: StreamCacheItem?,
    onDismiss: () -> Unit,
    onTogglePinned: () -> Unit,
    onStartFill: () -> Unit,
    onCancelFill: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onDelete: () -> Unit,
) {
    if (item == null) return
    val tokens = MaterialTheme.nuvio
    val entry = item.entry
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    fun dismissAfter(action: () -> Unit) {
        action()
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            StreamCacheSheetHeader(item = item)
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.PushPin,
                title = stringResource(
                    if (entry.pinned) Res.string.stream_cache_action_unpin else Res.string.stream_cache_action_pin,
                ),
                selected = entry.pinned,
                onClick = { dismissAfter(onTogglePinned) },
            )
            when {
                entry.isFilling -> NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.Stop,
                    title = stringResource(Res.string.stream_cache_action_stop),
                    onClick = { dismissAfter(onCancelFill) },
                )
                !entry.isComplete -> NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.Download,
                    title = stringResource(Res.string.stream_cache_action_finish),
                    onClick = { dismissAfter(onStartFill) },
                )
            }
            if (entry.isComplete) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.SaveAlt,
                    title = stringResource(Res.string.stream_cache_action_save),
                    onClick = { dismissAfter(onSaveToDownloads) },
                )
            }
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.DeleteOutline,
                title = stringResource(Res.string.action_delete),
                onClick = { dismissAfter(onDelete) },
            )
        }
    }
}

@Composable
private fun StreamCacheSheetHeader(item: StreamCacheItem) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s14),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadThumbnail(imageUrl = item.landscapeArtwork())
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                text = item.displayTitle(),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.episodeLabel()?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(item.entry.sizeText(), item.streamLabel()).joinToString(DownloadTextSeparator),
                style = MaterialTheme.nuvioTypeScale.bodySm,
                color = tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
