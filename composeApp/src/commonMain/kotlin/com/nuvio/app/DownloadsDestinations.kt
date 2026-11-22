package com.nuvio.app

import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.features.streamcache.StreamCacheManagerScreen
import com.nuvio.app.features.streamcache.StreamCacheRepository
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadShowRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.StreamCacheRoute
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.stream_cache_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsDestination(
    route: DownloadsRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val streamCacheTitle = stringResource(Res.string.stream_cache_title)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onOpenDetails = navController::openDownloadDetails,
        onNavigateToShow = if (useNativeNavigation) {
            { showId, title -> navController.navigate(DownloadShowRoute(showId, title)) }
        } else {
            null
        },
        onOpenStreamCache = if (StreamCacheRepository.isSupported) {
            { navController.navigate(StreamCacheRoute(streamCacheTitle)) }
        } else {
            null
        },
    )
}

@Composable
internal fun DownloadShowDestination(
    route: DownloadShowRoute,
    navController: NuvioNavigator,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onOpenDetails = navController::openDownloadDetails,
        initialShowId = route.showId,
        onBackFromShow = onBack,
    )
}

@Composable
internal fun StreamCacheDestination(
    route: StreamCacheRoute,
    navController: NuvioNavigator,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    StreamCacheManagerScreen(onBack = onBack)
}

private fun NuvioNavigator.openDownloadDetails(item: DownloadItem) {
    navigate(
        DetailRoute(
            type = item.parentMetaType,
            id = item.parentMetaId,
            title = item.title,
        ),
    )
}
