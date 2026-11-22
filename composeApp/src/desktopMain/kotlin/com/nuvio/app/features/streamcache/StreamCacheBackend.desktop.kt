package com.nuvio.app.features.streamcache

// Note: desktopMain is not currently a configured target in composeApp/build.gradle.kts;
// this actual only exists so the source set stays complete if a desktop target returns.
internal actual fun streamCacheBackend(): StreamCacheBackend? = null

internal actual fun streamCacheDownloadsDestination(fileName: String): StreamCacheDownloadsDestination? = null
