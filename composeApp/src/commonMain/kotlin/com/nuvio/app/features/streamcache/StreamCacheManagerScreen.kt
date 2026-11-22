package com.nuvio.app.features.streamcache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.formatBytes
import com.nuvio.app.features.settings.SettingsChipRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.action_delete_confirm_message
import nuvio.composeapp.generated.resources.stream_cache_auto_fill_description
import nuvio.composeapp.generated.resources.stream_cache_auto_fill_title
import nuvio.composeapp.generated.resources.stream_cache_clear_all
import nuvio.composeapp.generated.resources.stream_cache_clear_all_message
import nuvio.composeapp.generated.resources.stream_cache_clear_all_title
import nuvio.composeapp.generated.resources.stream_cache_delete_message
import nuvio.composeapp.generated.resources.stream_cache_delete_title
import nuvio.composeapp.generated.resources.stream_cache_empty_message
import nuvio.composeapp.generated.resources.stream_cache_empty_title
import nuvio.composeapp.generated.resources.stream_cache_enable_description
import nuvio.composeapp.generated.resources.stream_cache_enable_title
import nuvio.composeapp.generated.resources.stream_cache_limit_description
import nuvio.composeapp.generated.resources.stream_cache_limit_footer
import nuvio.composeapp.generated.resources.stream_cache_limit_title
import nuvio.composeapp.generated.resources.stream_cache_limit_unlimited
import nuvio.composeapp.generated.resources.stream_cache_look_ahead_default
import nuvio.composeapp.generated.resources.stream_cache_look_ahead_description
import nuvio.composeapp.generated.resources.stream_cache_look_ahead_minutes
import nuvio.composeapp.generated.resources.stream_cache_look_ahead_title
import nuvio.composeapp.generated.resources.stream_cache_save_failed
import nuvio.composeapp.generated.resources.stream_cache_saved
import nuvio.composeapp.generated.resources.stream_cache_section_items
import nuvio.composeapp.generated.resources.stream_cache_section_settings
import nuvio.composeapp.generated.resources.stream_cache_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Standalone "Stream cache" manager (reached from Downloads via `StreamCacheRoute`).
 * Inside Settings the same content is rendered by the `PlaybackStreamCache` settings page.
 */
@Composable
fun StreamCacheManagerScreen(
    onBack: () -> Unit,
) {
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.stream_cache_title),
                onBack = onBack,
            )
        }
        item(key = "stream_cache_content") {
            StreamCacheManagerContent(isTablet = false)
        }
    }
}

/**
 * The whole manager (storage card, settings, cached stream list, sheet and dialogs) as a single
 * lazy-list item, so it can be hosted by [StreamCacheManagerScreen] and by the settings pages.
 */
@Composable
internal fun StreamCacheManagerContent(
    isTablet: Boolean,
) {
    val uiState by StreamCacheRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        StreamCacheRepository.ensureObserving()
    }

    var actionsItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearAllDialog by rememberSaveable { mutableStateOf(false) }

    val config = uiState.config
    val items = uiState.items
    val coroutineScope = rememberCoroutineScope()
    val playHandler = LocalStreamCachePlayHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) NuvioTokens.Space.s18 else NuvioTokens.Space.s16),
    ) {
        StreamCacheStorageCard(config = config, itemCount = items.size)

        SettingsSection(
            title = stringResource(Res.string.stream_cache_section_settings),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.stream_cache_enable_title),
                    description = stringResource(Res.string.stream_cache_enable_description),
                    checked = config.enabled,
                    isTablet = isTablet,
                    onCheckedChange = StreamCacheRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChipRow(
                    title = stringResource(Res.string.stream_cache_limit_title),
                    description = stringResource(Res.string.stream_cache_limit_description),
                    isTablet = isTablet,
                    footer = stringResource(
                        Res.string.stream_cache_limit_footer,
                        formatStreamCacheLimit(config.minFreeBytes),
                    ),
                ) {
                    StreamCacheDefaults.MAX_BYTES_OPTIONS.forEach { option ->
                        Chip(
                            label = if (option <= 0L) {
                                stringResource(Res.string.stream_cache_limit_unlimited)
                            } else {
                                formatStreamCacheLimit(option)
                            },
                            selected = config.maxBytes == option,
                            onClick = { StreamCacheRepository.setMaxBytes(option) },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.stream_cache_auto_fill_title),
                    description = stringResource(Res.string.stream_cache_auto_fill_description),
                    checked = config.autoFillWhilePlaying,
                    enabled = config.enabled,
                    isTablet = isTablet,
                    onCheckedChange = StreamCacheRepository::setAutoFillWhilePlaying,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChipRow(
                    title = stringResource(Res.string.stream_cache_look_ahead_title),
                    description = stringResource(Res.string.stream_cache_look_ahead_description),
                    isTablet = isTablet,
                ) {
                    StreamCacheDefaults.LOOK_AHEAD_MINUTES_OPTIONS.forEach { option ->
                        Chip(
                            label = if (option <= 0) {
                                stringResource(Res.string.stream_cache_look_ahead_default)
                            } else {
                                stringResource(Res.string.stream_cache_look_ahead_minutes, option)
                            },
                            selected = config.lookAheadMinutes == option,
                            onClick = { StreamCacheRepository.setLookAheadMinutes(option) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NuvioTokens.Space.s8, start = NuvioTokens.Radius.card, end = NuvioTokens.Radius.card),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioSectionLabel(text = stringResource(Res.string.stream_cache_section_items))
            if (items.isNotEmpty()) {
                NuvioActionLabel(
                    text = stringResource(Res.string.stream_cache_clear_all),
                    onClick = { showClearAllDialog = true },
                )
            }
        }

        if (items.isEmpty()) {
            StreamCacheEmptyState(
                title = stringResource(Res.string.stream_cache_empty_title),
                message = stringResource(Res.string.stream_cache_empty_message),
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap),
            ) {
                items.forEach { item ->
                    StreamCacheRow(
                        item = item,
                        onPlay = if (playHandler != null && item.isPlayable()) {
                            { playHandler(item) }
                        } else {
                            null
                        },
                        onOpenActions = { actionsItemId = item.entry.id },
                    )
                }
            }
        }
    }

    val actionsItem = actionsItemId?.let { id -> items.firstOrNull { it.entry.id == id } }
    StreamCacheActionSheet(
        item = actionsItem,
        onDismiss = { actionsItemId = null },
        onPlay = if (playHandler != null && actionsItem != null && actionsItem.isPlayable()) {
            { playHandler(actionsItem) }
        } else {
            null
        },
        onTogglePinned = {
            actionsItem?.let { StreamCacheRepository.setPinned(it.entry.id, !it.entry.pinned) }
        },
        onStartFill = { actionsItem?.let { StreamCacheRepository.startFill(it.entry.id) } },
        onCancelFill = { actionsItem?.let { StreamCacheRepository.cancelFill(it.entry.id) } },
        onSaveToDownloads = {
            actionsItem?.let {
                val error = StreamCacheRepository.saveToDownloads(it.entry.id)
                coroutineScope.launch {
                    NuvioToastController.show(
                        if (error == null) {
                            getString(Res.string.stream_cache_saved)
                        } else {
                            getString(Res.string.stream_cache_save_failed, error)
                        },
                    )
                }
            }
        },
        onDelete = { deleteItemId = actionsItem?.entry?.id },
    )

    val deleteItem = deleteItemId?.let { id -> items.firstOrNull { it.entry.id == id } }
    if (deleteItem != null) {
        val name = listOfNotNull(deleteItem.displayTitle(), deleteItem.episodeLabel()).joinToString(" ")
        val sizeBytes = deleteItem.entry.cachedBytes
        NuvioStatusModal(
            title = stringResource(Res.string.stream_cache_delete_title, name),
            message = if (sizeBytes > 0L) {
                stringResource(Res.string.stream_cache_delete_message, formatBytes(sizeBytes))
            } else {
                stringResource(Res.string.action_delete_confirm_message)
            },
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_delete),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                StreamCacheRepository.delete(deleteItem.entry.id)
                deleteItemId = null
            },
            onDismiss = { deleteItemId = null },
        )
    } else if (deleteItemId != null) {
        // The entry disappeared (e.g. evicted) while the dialog was pending.
        LaunchedEffect(deleteItemId) { deleteItemId = null }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.stream_cache_clear_all_title),
        message = stringResource(
            Res.string.stream_cache_clear_all_message,
            formatBytes(maxOf(config.usedBytes, items.sumOf { it.entry.cachedBytes })),
        ),
        isVisible = showClearAllDialog,
        destructive = true,
        confirmText = stringResource(Res.string.stream_cache_clear_all),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            StreamCacheRepository.clearAll()
            showClearAllDialog = false
        },
        onDismiss = { showClearAllDialog = false },
    )
}
