# Persistent stream cache (iOS)

Goal: everything the iOS player (mpv) downloads while streaming is kept on the device, so
re-watching / seeking back / resuming later reuses those bytes instead of downloading again.
A manager lets the user see, pin, finish, delete, and move cached streams into Downloads.

## Architecture

```
PlayerScreenRuntimeUi (common)  --builds-->  StreamCacheContext (cacheKey = videoId|stream identity)
        |  registers context for the source URL (StreamCacheContextRegistry, commonMain)
        v
PlatformPlayerSurface (iosMain, PlayerEngine.ios.kt)
        |  playbackUrl = StreamCacheRepository.preparePlaybackUrl(sourceUrl, headers, context)
        |  bridge.loadFileWithAudio(videoUrl = playbackUrl, ...) ; on dispose -> releasePlaybackUrl
        v
NuvioStreamCacheBridge (Kotlin interface, iosMain)  <== implemented in Swift
        v
iosApp/iosApp/StreamCache/*.swift
  StreamCacheStore   : entries on disk (Application Support/StreamCache/<id>/data.<ext> + meta.json),
                       sparse data file + merged byte-range set, LRU eviction, config in UserDefaults
  StreamCacheProxy   : loopback HTTP/1.1 server (Network.framework, 127.0.0.1, random port)
                       serving http://127.0.0.1:<port>/s/<token>/<filename>; Range-aware;
                       serves cached ranges from disk, fetches only missing ranges upstream
                       (with the original headers), writes them to the sparse file while
                       forwarding them to mpv.
  StreamCacheFiller  : optional "finish download" that fetches the missing ranges.
  StreamCacheBridgeImpl : NuvioStreamCacheBridge conformance + registration.
```

Contract files (already written, do not change signatures without coordinating):
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streamcache/StreamCacheModels.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streamcache/StreamCacheRepository.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/streamcache/StreamCacheBridge.kt`

## Rules
- Cache identity is `StreamCacheContext.cacheKey`, not the URL (debrid links expire/refresh).
  Entry id = first 24 hex chars of SHA-256(cacheKey). If upstream reports a different total
  length than the stored entry, the old data is discarded and the entry restarts.
- A complete entry must play fully offline (no upstream request needed).
- Not cached (URL returned unchanged): cache disabled, non-http(s), loopback hosts
  (P2P engine, local servers), `.m3u8`/`.mpd` URLs, free disk < minFreeBytes.
- If upstream doesn't honour Range or turns out to be an HLS/DASH manifest by content-type,
  the proxy passes bytes through without caching. Any other path under `/s/<token>/...`
  is resolved relative to the upstream URL and passed through (relative manifest refs).
- Size limit: LRU eviction of non-pinned, non-active, non-filling entries when used > maxBytes.
- Files are excluded from iCloud backup.

## Playing a cached entry directly (`entryPlaybackUrl(id)`, works offline)
- No stream lookup; works regardless of the "enabled" setting (with caching disabled or low
  disk the proxy serves cached bytes but stores nothing new). Returns "" when the entry is
  missing or has 0 cached bytes.
- Complete entry → `file://` URL of `data.<ext>` (stale tail beyond `totalBytes` is cut first);
  mpv reads it directly, no proxy, no network. The proxy keeps a "file session" keyed by that
  URL string which holds the entry's active mark (no eviction; export refuses with
  "Playing — try again after closing the player") until `releasePlaybackUrl(<same URL>)`.
  Delete/clear still proceed (mpv keeps its open fd).
- Partial entry → normal proxy session (same token URL, entry marked active) built from the
  stored `sourceUrl`/headers in meta. With a known `totalBytes` the proxy never probes upstream:
  cached spans are served with zero network, only gaps go upstream. Without a stored
  `sourceUrl`, gaps fail (502 before the head, or connection closed mid-body).
- Auto-fill is scheduled as usual (only with a stored `sourceUrl`); offline it stops at once
  with a single `fillError` ("No internet connection") instead of retrying.
- The proxy listener binds 127.0.0.1 only and has no reachability requirement.

## Offline playback of cached entries
- `StreamCacheRepository.playbackUrlForEntry(id)` gives a file:// URL (complete) or a loopback
  proxy URL (partial). Both bypass `preparePlaybackUrl` in PlayerEngine.ios.kt, and
  `activeStreamCacheContext` refuses loopback/file URLs (`isStreamCacheLocalUrl`), so they are
  never cached again and the surface never releases them.
- MainAppContent owns playback: `openStreamCacheItem` (manager "Play"/"Play saved part", exposed
  to the manager through `LocalStreamCachePlayHandler`) and `launchPlaybackWithDownloadPreference`
  (details play, episode list, continue watching: download first, then a complete cache entry via
  `findCompleteEntryForVideo`, then the normal stream lookup; skipped for manual source selection).
  Both go through `launchStreamCachePlayback`, which always uses the internal player and attaches
  the URL to the player launch id (`StreamCachePlaybackSessions`).
- `disposeRouteResources(PlayerRoute)` calls `StreamCachePlaybackSessions.release(launchId)`, which
  releases each attached URL exactly once.

## Fill while playing (auto-fill)
- Setting `autoFillWhilePlaying` (UserDefaults `nuvio.streamcache.autoFill`, default on).
- When `preparePlaybackUrl` returns a proxy URL and the setting is on, a fill is scheduled
  ~8 s later (so mpv's first requests get the bandwidth). It only starts if caching is still
  enabled, the setting is still on, disk space is above the reserve, and the entry is still
  playing, has a known size (ranged probe succeeded), is not complete and not already filling.
- The fill keeps running after playback stops (the goal is the whole file). Turning the
  setting off doesn't cancel running fills; the manager's cancel does.
- Ordering: the proxy records the byte offset it is serving per entry (in memory). The filler
  takes the first gap at/after `playhead + 32 MiB`, then wraps around to earlier gaps.
- No double downloads: the filler recomputes the gap from the cached ranges before every
  ≤ 8 MiB chunk and abandons a request as soon as it reaches bytes already cached by the
  proxy; the proxy likewise stops an upstream segment when it reaches bytes the filler
  cached and continues from disk.

## Look-ahead (mpv read-ahead)
- Setting `lookAheadMinutes` (UserDefaults `nuvio.streamcache.lookAheadMinutes`, default 10,
  UI values 0/5/10/20/30, clamped to 0...60). Applied in `MPVPlayerBridge.startLoad` before
  every `loadfile`.
- minutes > 0: `cache=yes`, `cache-on-disk=yes`, `demuxer-cache-dir=<tmp>/mpv-cache`,
  `cache-secs` = `demuxer-readahead-secs` = minutes·60, `demuxer-max-bytes` ≈ 4 MB/s × window
  (512 MiB…6 GiB, capped to half the free space above the 2 GiB reserve),
  `demuxer-max-back-bytes=256MiB`. The on-disk demuxer cache is a temp file mpv unlinks; it
  is not the persistent cache (but through the proxy, everything mpv reads ahead is also
  written to the persistent cache).
- 0: mpv defaults (`cache=auto`, `cache-on-disk=no`, `cache-secs=1000`,
  `demuxer-readahead-secs=1`, `demuxer-max-bytes=150MiB`, `demuxer-max-back-bytes=50MiB`).
- Property-set failures are logged, never fatal.

# Android port

Status: skeleton only (bodies are `TODO("implement")`). The shared UI stays hidden on Android
until `AndroidStreamCacheBackend.IMPLEMENTATION_READY` is flipped to `true` by the integrator
(checked in `streamCacheBackend()` before anything else, so the skeleton is never reached).

Same design as iOS, ported to Kotlin/JVM in
`composeApp/src/androidMain/kotlin/com/nuvio/app/features/streamcache/android/`:
loopback HTTP/1.1 proxy on `127.0.0.1` (`ServerSocket`, thread-per-connection blocking IO),
OkHttp 4.12 for upstream ranged GETs, sparse `RandomAccessFile` data file + merged range set
persisted in `meta.json` (kotlinx.serialization, same field names as the Swift `Codable`),
config in SharedPreferences `nuvio_stream_cache` (same keys as the iOS UserDefaults keys),
storage root `context.filesDir/stream_cache/<id>/{data.<ext>,meta.json}` (persistent, not
cacheDir). Entry ids are identical to iOS (first 24 hex of SHA-256(cacheKey)).

Package note: the sub-package is literally `...streamcache.android`; always `import android.*`
classes, never write inline `android.foo.Bar` FQNs inside these files.

## File ownership (one owner per file)

| File | Swift counterpart | Owns |
|---|---|---|
| `StreamCacheRangeSet.kt` | StreamCacheRangeSet.swift | `StreamCacheSpan`, `StreamCacheRangeSet` (not thread-safe) |
| `StreamCacheStore.kt` | StreamCacheStore.swift | `StreamCacheMeta` (@Serializable), `StreamCacheEntryState`, `StreamCacheFillSource`, `StreamCacheServeInfo`, `StreamCacheEntryPlaybackInfo`, `StreamCacheNotifier`, `StreamCacheStore` (entries/meta/config/free space/eviction/export/playhead/listing JSON) |
| `StreamCacheHttp.kt` | request parsing + range helpers of ProxyConnection.swift, response helpers of StreamCacheHTTP | `StreamCacheHttpRequest`, `StreamCacheRangeRequest` (Whole/From/Suffix), `StreamCacheByteRange`, `StreamCacheHttp` (readRequestHead, parseRange, resolveRange, reasonPhrase, mimeTypeForFileName, writeResponseHead, writeSimpleResponse, rangedResponseHeaders, rangedStatus, unsatisfiableHeaders) |
| `StreamCacheUpstream.kt` | StreamCacheUpstream.swift + StreamCacheHTTP parse helpers | `StreamCacheContentRange`, `StreamCacheUpstreamException`, `StreamCacheUpstreamResponse` (status/headers/body stream, `cancel()` from any thread), `StreamCacheUpstream.open(url, headers, range, method)`, `parseContentRange`, `isManifest`, `isEncoded`, `isOfflineError`, `defaultClient` |
| `StreamCacheProxyServer.kt` | StreamCacheProxy.swift | `StreamCacheSession`, `StreamCacheProxyServer` (listener lifecycle, tokens, URL building, file sessions, release, connection bookkeeping) |
| `StreamCacheProxyConnection.kt` | StreamCacheProxyConnection.swift | per-connection serve loop (probe / cached spans / segments / pass-through) |
| `StreamCacheFiller.kt` | StreamCacheFiller.swift + BridgeImpl.scheduleAutoFill | `StreamCacheFiller`, `StreamCacheFillJob` |
| `AndroidStreamCacheBackend.kt` | StreamCacheBridgeImpl.swift + StreamCacheBackend.ios.kt | implements `StreamCacheBackend`; wiring, lifecycle observer, singleton |
| `../StreamCacheBackend.android.kt` | — | `actual streamCacheBackend()`, `actual streamCacheDownloadsDestination()` (implemented: `<filesDir>/downloads/<name>`, `File.toURI().toString()`, same as AndroidDownloadScheduler) |

## Wiring and threads

```
AndroidStreamCacheBackend (main-thread API, singleton via getOrNull(); Context from
                           PlayerSettingsStorage.applicationContext(), set in MainActivity.onCreate)
  ├─ maintenance  : ScheduledExecutorService, 1 daemon thread (debounced meta saves 2 s,
  │                 eviction checks 3 s, auto-fill delay 8 s, fill retry back-off)
  ├─ notifier     : StreamCacheNotifier(Handler(mainLooper))  ≤ 2 Hz, trailing call guaranteed
  ├─ store        : StreamCacheStore(rootDir, prefs, notifier, maintenance)
  ├─ upstream     : StreamCacheUpstream(OkHttpClient: no cache, 30 s timeouts, IPv4FirstDns,
  │                 Accept-Encoding: identity forced)
  ├─ proxy        : StreamCacheProxyServer(store, upstream, connectionExecutor)
  │                   accept thread → StreamCacheProxyConnection.run() on a cached daemon pool
  └─ filler       : StreamCacheFiller(store, upstream, maintenance) → 1 daemon thread per job
```

Locks: store lock (`Any`, `synchronized`) guards the entry map and entry fields; each entry's
`ioLock` (`ReentrantLock`) guards its `RandomAccessFile`. Order is always `ioLock → store lock`.
Proxy: `startLock` (binds) is never taken while holding `lock` (maps).

## Cross-file API (who calls what)

- Backend → Store: `isEnabled`, `hasEnoughFreeSpace`, `prepareEntry`, `fileName`,
  `entryPlaybackInfo`, `prepareFilePlayback`, `configJson`, `listEntriesJson`, setters,
  `deleteEntry`, `clearAll`, `setPinned`, `exportEntry`, `flushAll`, `lookAheadMinutes`.
- Backend → Proxy: `ensureListening`, `registerSession`, `registerFileSession`, `playbackUrl`,
  `releasePlaybackUrl`, `restartIfNeeded`.
- Backend → Filler: `startFill`, `cancelFill`, `cancelAll`, `scheduleAutoFill`.
- Proxy → Store: `markActive(+1/-1)` on register/release. Proxy → Connection: `run`,
  `cancelFromOutside`. Connection → Proxy: `session(token)`, `associate`, `connectionClosed`.
- Connection → Store: `touch`, `serveInfo`, `setTotal`, `cachedEnd`, `nextCachedStart`,
  `read`, `write`, `notePlayhead`, `isEnabled`. Connection → Http: `readRequestHead`,
  `parseRange`, `resolveRange`, `writeResponseHead`, `writeSimpleResponse`,
  `rangedResponseHeaders`, `rangedStatus`, `unsatisfiableHeaders`, `mimeTypeForFileName`.
  Connection → Upstream: `open`, response `body`/`cancel`/`close`, `isManifest`, `isEncoded`.
- Filler → Store: `beginFill`, `endFill`, `fillSource`, `setTotal`, `write`, `cachedEnd`,
  `serveInfo`, `entryExists`, `hasEnoughFreeSpace`, `canAutoFill`, `isEnabled`,
  `autoFillWhilePlaying`. Filler → Upstream: `open`, `isOfflineError`.
- Store → Notifier: `notifyChanged` after every visible change.
- JSON for `getConfigJson`/`listEntriesJson` is produced by encoding the shared
  `StreamCacheConfig` / `List<StreamCacheEntry>` with `StreamCacheRepository.json`, so field
  names can never drift from commonMain.

Android-specific differences from iOS:
- Blocking IO replaces callbacks: backpressure is the blocking socket write; release closes the
  client socket and calls `Call.cancel()` so blocked reads/writes throw.
- Client disconnect (player seek/stop) surfaces as an IOException on write → close.
- Listener binds `127.0.0.1` from raw bytes (Android's `getLoopbackAddress()` may be `::1`).
- Complete entries play from `Uri.fromFile(data).toString()` (`file:///...`); ExoPlayer and
  libmpv (`toMpvSource`) both read it directly.
- Offline detection: `UnknownHostException` / unreachable network → "No internet connection".

## Integration notes

### (a) PlayerEngine.android.kt: prepare/release (mirror PlayerEngine.ios.kt)

Do it ONCE in `PlatformPlayerSurface` (above the `when (activeEngine)`), so the ExoPlayer →
libmpv fallback reuses the same proxy session (a session serves any number of connections):

```kotlin
val sanitizedHeadersForCache = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
val streamCacheLease = remember(sourceUrl, sourceAudioUrl, sanitizedHeadersForCache, useYoutubeChunkedPlayback, streamType) {
    StreamCachePlaybackLease(   // small private RememberObserver class in PlayerEngine.android.kt
        sourceUrl = sourceUrl,
        eligible = sourceAudioUrl == null && !useYoutubeChunkedPlayback &&
            !isAdaptiveStreamType(streamType),      // HLS/DASH/SS by streamType or mime
        headers = sanitizedHeadersForCache,
    )
}
val playbackUrl = streamCacheLease.playbackUrl
val usesStreamCache = playbackUrl != sourceUrl
```

`StreamCachePlaybackLease : RememberObserver` computes `playbackUrl =
StreamCacheRepository.preparePlaybackUrl(sourceUrl, headers, StreamCacheContextRegistry.get(sourceUrl))`
in `onRemembered()` (not during composition, so an abandoned composition never leaks a session;
until then `playbackUrl == sourceUrl` — make it a `mutableStateOf` so the surface recomposes),
and calls `StreamCacheRepository.releasePlaybackUrl(playbackUrl)` exactly once in
`onForgotten()` / `onAbandoned()` when it differs from `sourceUrl`. Entry URLs from
`playbackUrlForEntry` (file:// / loopback) get no context (`activeStreamCacheContext` refuses
them) so they pass through unchanged and are released by `StreamCachePlaybackSessions`.

Pass `playbackUrl` as a NEW parameter to both engine surfaces; keep `sourceUrl` as is.
- ExoPlayerSurface — use `playbackUrl` for: `playbackMediaItemFromUrl(url = …)` in
  `resolvedMediaItem` (the proxy URL ends in `/<fileName>`, so extension-based MIME inference
  still works), the `dataSourceFactory` (`isLoopbackPlaybackSource(playbackUrl)` is then true →
  VodCache skipped (no double caching) + long read timeout), and its `remember` keys / the
  media-item `remember` keys. Keep `sourceUrl` for: `.setMediaId(sourceUrl)`,
  `AutoSyncExtractorsFactory(factory, sourceUrl)`, `probeMimeType(sourceUrl, …)`,
  `latestChapterSourceUrl` (MatroskaChapterLoader can go direct), diagnostics, and the
  `exoPlayer` remember keys (sourceUrl changes already rebuild). The proxy sends the original
  headers upstream itself; ExoPlayer's headers to 127.0.0.1 are ignored.
- LibmpvPlayerSurface — `view.loadSource(sourceUrl = playbackUrl, requestHeaders = if
  (usesStreamCache) emptyMap() else sanitizedSourceHeaders, …)` and add `playbackUrl` to that
  LaunchedEffect's keys. `rememberLibmpvAutoSyncCoordinator(sourceUrl = sourceUrl, …)` keeps the
  original (AutoSync / hashing / subtitle lookups use the original URL, like iOS).
- Stall reopen (`PlaybackReopenSession`) and mime-probe retries reuse the same `playbackUrl`.

### (b) lookAheadMinutes on Android

Read `AndroidStreamCacheBackend.getOrNull()?.lookAheadMinutes() ?: 0` (helper exposed for the
engine; apply ONLY when `usesStreamCache`, i.e. the source is the proxy — never for P2P /
direct / downloads).
- ExoPlayer (RAM buffer): add `lookAheadMs: Int = 0` to
  `NuvioExoPlayerPerformanceHelper.buildLoadControl` and use `maxBufferMs = max(maxBufferMs,
  lookAheadMs)` ONLY on paths with `setPrioritizeTimeOverSizeThresholds(false)` (native and
  default paths): the existing `targetBufferBytes` cap (device-RAM based) still wins, so memory
  stays within today's safe limits; the time window just stops being the limiting factor. Do
  not raise it on the heap path (`prioritizeTimeOverSize = true`), where time beats bytes.
  Add `lookAheadMs` to the `exoPlayer` remember keys. Deep look-ahead beyond RAM comes from the
  proxy + auto-fill (everything fetched lands in the persistent cache).
- libmpv (disk buffer, like iOS `MPVPlayerBridge.applyLookAhead`): in `NuvioLibmpvView`
  before `loadfile` in `loadCurrentSourceNow` (mpv thread), when minutes > 0:
  `cache=yes`, `cache-on-disk=yes`, `demuxer-cache-dir=<context.cacheDir>/mpv-cache`,
  `cache-secs` = `demuxer-readahead-secs` = minutes·60, `demuxer-max-bytes` ≈ 4 MB/s × window
  clamped to 512 MiB…6 GiB and ≤ half of (StatFs free on cacheDir − 2 GiB reserve),
  `demuxer-max-back-bytes=256MiB`. When 0 or not using the proxy, restore the CURRENT Android
  values: `cache=auto`, `cache-on-disk=no`, `cache-secs=1000`, `demuxer-readahead-secs=1`,
  `demuxer-max-bytes` = `demuxer-max-back-bytes` = `libmpvCacheBytes()`. Use
  `setPropertyString` (runtime) and `logIfMpvError`; failures are never fatal.

### (c) Android lifecycle

- The proxy, fills and maintenance run in-process on daemon threads; nothing is torn down when
  the app is backgrounded (Android keeps a bound `ServerSocket` alive while the process lives,
  unlike iOS). Background audio / PiP keep streaming through the proxy while the
  `PlayerNowPlayingService` foreground service keeps the process alive.
- `ProcessLifecycleOwner` observer in the backend: ON_STOP → `store.flushAll()` (process may
  be killed any time after); ON_START → `proxy.restartIfNeeded()` (rebinds only if the accept
  loop died; reuses the last port so existing URLs keep working).
- Process death: entries reload from `meta.json` on next start (ranges clamped to the real file
  length); `isFilling`/`activeSessions` are memory-only, so a killed fill just stops. It is not
  resumed automatically (auto-fill starts again on next playback; the manager can resume).
- Background fills without the player may lose network under Doze / app standby; they then end
  with a `fillError` (offline → single "No internet connection"). No WorkManager in v1.
- Cleartext to `http://127.0.0.1` is already allowed (manifest + network_security_config).
