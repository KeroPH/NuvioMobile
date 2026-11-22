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
