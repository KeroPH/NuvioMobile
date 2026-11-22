package com.nuvio.app.features.streamcache.android

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

// Port of StreamCacheProxy.swift (Network.framework NWListener → java.net.ServerSocket).
// Owner: StreamCacheProxyServer.kt.

/**
 * One `preparePlaybackUrl` / `entryPlaybackUrl` call: maps a random token to an entry +
 * upstream request. Immutable.
 */
internal class StreamCacheSession(
    val token: String,
    val entryId: String,
    /**
     * Null when an entry is played from the cache without a stored upstream URL: cached ranges
     * are served, requests touching uncached ranges fail (502 / connection closed).
     */
    val upstreamUrl: String?,
    val headers: Map<String, String>,
    /** Entry file name (the last path segment of the playback URL, before percent-encoding). */
    val fileName: String,
)

/**
 * Loopback HTTP/1.1 server serving `http://127.0.0.1:<port>/s/<token>/<percent-encoded file>`.
 *
 * - Listener: `ServerSocket(port, BACKLOG, LOOPBACK_ADDRESS)` with reuseAddress = true, bound to
 *   127.0.0.1 ONLY (nothing outside the device can reach it). [LOOPBACK_ADDRESS] is built from
 *   raw bytes, not `InetAddress.getLoopbackAddress()`: on Android that may return `::1`, while the
 *   playback URLs (and PlayerEngine.android.kt `isLoopbackPlaybackSource`) use 127.0.0.1.
 * - One daemon accept thread ("NuvioStreamCache-accept"); each accepted socket gets a
 *   [StreamCacheProxyConnection] run on [connectionExecutor] (cached pool of daemon threads,
 *   thread-per-connection, blocking IO). The accept loop ends when the socket is closed; it then
 *   clears [serverSocket]/[port] (if still current) so the next [ensureListening] rebinds.
 * - The process keeps the listener while alive (no foreground/background teardown on Android);
 *   [restartIfNeeded] only rebinds if the accept loop died.
 * - All public methods are thread-safe ([lock] guards maps/port; [startLock] serialises
 *   (re)binds and is never taken while holding [lock]).
 */
internal class StreamCacheProxyServer(
    private val store: StreamCacheStore,
    private val upstream: StreamCacheUpstream,
    private val connectionExecutor: ExecutorService,
) {
    private val lock = Any()
    private val startLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var port: Int = 0

    /** Last successfully bound port; reused on rebind so existing playback URLs keep working. */
    private var lastPort: Int = 0

    private val sessions: HashMap<String, StreamCacheSession> = HashMap()

    /**
     * `file://` playback URLs handed out for complete entries, keyed by the exact returned URL
     * string: entryId + number of players using it. Each holds an "active" mark on the entry
     * (no eviction/export) until [releasePlaybackUrl] with that string.
     */
    private val fileSessions: HashMap<String, FileSession> = HashMap()

    /** Live connections and the token each one serves (for cancellation on release). */
    private val connections: MutableSet<StreamCacheProxyConnection> = HashSet()
    private val connectionTokens: HashMap<StreamCacheProxyConnection, String> = HashMap()

    private data class FileSession(val entryId: String, val count: Int)

    // region Listener

    /**
     * Returns the listening port, binding if needed: first tries [lastPort] (if non-zero), then
     * an ephemeral port (0). Null when binding fails. Fast (no async ready wait on JVM).
     */
    fun ensureListening(): Int? {
        synchronized(startLock) {
            val stale: ServerSocket?
            val preferred: Int
            synchronized(lock) {
                val current = serverSocket
                val thread = acceptThread
                if (current != null && !current.isClosed && port != 0 && thread != null && thread.isAlive) {
                    return port
                }
                stale = current
                serverSocket = null
                acceptThread = null
                port = 0
                preferred = lastPort
            }
            closeQuietly(stale)

            if (preferred != 0) {
                startListener(preferred)?.let { return it }
            }
            return startListener(0)
        }
    }

    /** Rebinds on a background thread when sessions exist but the listener is gone. */
    fun restartIfNeeded() {
        if (!hasSessions) return
        val listening = synchronized(lock) {
            val current = serverSocket
            val thread = acceptThread
            current != null && !current.isClosed && port != 0 && thread != null && thread.isAlive
        }
        if (listening) return
        val thread = Thread({
            try {
                ensureListening()
            } catch (t: Throwable) {
                logW("proxy restart failed", t)
            }
        }, "NuvioStreamCache-restart")
        thread.isDaemon = true
        thread.start()
    }

    /** Binds a ServerSocket on [requestedPort] (0 = any) and starts the accept thread. */
    private fun startListener(requestedPort: Int): Int? {
        val socket: ServerSocket
        try {
            socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(LOOPBACK_ADDRESS, requestedPort), BACKLOG)
            } catch (e: IOException) {
                closeQuietly(socket)
                throw e
            }
        } catch (e: IOException) {
            logW("proxy listener bind on port $requestedPort failed: ${e.message}")
            return null
        } catch (e: SecurityException) {
            logW("proxy listener bind on port $requestedPort denied: ${e.message}")
            return null
        }

        val boundPort = socket.localPort
        if (boundPort <= 0) {
            closeQuietly(socket)
            return null
        }

        val thread = Thread({ acceptLoop(socket) }, "NuvioStreamCache-accept")
        thread.isDaemon = true
        synchronized(lock) {
            serverSocket = socket
            acceptThread = thread
            port = boundPort
            lastPort = boundPort
        }
        try {
            thread.start()
        } catch (t: Throwable) {
            logW("proxy accept thread start failed", t)
            synchronized(lock) {
                if (serverSocket === socket) {
                    serverSocket = null
                    acceptThread = null
                    port = 0
                }
            }
            closeQuietly(socket)
            return null
        }
        logI("proxy listening on $HOST:$boundPort")
        return boundPort
    }

    /** Accept loop body (runs on the accept thread) for [socket]. */
    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    // Closed by shutdown()/rebind, or the socket died.
                    if (!socket.isClosed) logW("proxy accept failed: ${e.message}")
                    break
                } catch (e: IOException) {
                    // Transient (e.g. out of file descriptors): back off briefly and retry.
                    logW("proxy accept error: ${e.message}")
                    try {
                        Thread.sleep(ACCEPT_ERROR_BACKOFF_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                accept(client)
            }
        } catch (t: Throwable) {
            logW("proxy accept loop crashed", t)
        } finally {
            synchronized(lock) {
                if (serverSocket === socket) {
                    serverSocket = null
                    acceptThread = null
                    port = 0
                }
            }
            closeQuietly(socket)
        }
    }

    /** Wraps [client] in a connection, registers it and submits it to [connectionExecutor]. */
    private fun accept(client: Socket) {
        try {
            client.tcpNoDelay = true
        } catch (_: IOException) {
        }
        val connection = StreamCacheProxyConnection(client, this, store, upstream)
        synchronized(lock) { connections.add(connection) }
        try {
            connectionExecutor.execute(connection)
        } catch (e: RejectedExecutionException) {
            logW("proxy connection rejected: ${e.message}")
            connectionClosed(connection)
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Closes the listener and all connections; sessions are kept (used by tests / shutdown). */
    fun shutdown() {
        synchronized(startLock) {
            val socket: ServerSocket?
            val live: List<StreamCacheProxyConnection>
            synchronized(lock) {
                socket = serverSocket
                serverSocket = null
                acceptThread = null
                port = 0
                live = connections.toList()
            }
            closeQuietly(socket)
            for (connection in live) {
                try {
                    connection.cancelFromOutside()
                } catch (t: Throwable) {
                    logW("proxy connection cancel failed", t)
                }
            }
        }
    }

    // endregion

    // region Sessions

    val hasSessions: Boolean
        get() = synchronized(lock) { sessions.isNotEmpty() }

    /**
     * Registers a direct-file playback URL (complete entry) and marks the entry active (+1).
     * Pair with [releasePlaybackUrl] of the same string.
     */
    fun registerFileSession(entryId: String, fileUrl: String) {
        synchronized(lock) {
            val existing = fileSessions[fileUrl]
            fileSessions[fileUrl] = if (existing != null) {
                existing.copy(count = existing.count + 1)
            } else {
                FileSession(entryId, 1)
            }
        }
        store.markActive(entryId, 1)
    }

    /**
     * Creates a session with token = random UUID without dashes (lower-case), stores it and
     * marks the entry active (+1).
     */
    fun registerSession(
        entryId: String,
        upstreamUrl: String?,
        headers: Map<String, String>,
        fileName: String,
    ): StreamCacheSession {
        val token = UUID.randomUUID().toString().replace("-", "").lowercase()
        val session = StreamCacheSession(
            token = token,
            entryId = entryId,
            upstreamUrl = upstreamUrl,
            headers = headers,
            fileName = fileName,
        )
        synchronized(lock) { sessions[token] = session }
        store.markActive(entryId, 1)
        return session
    }

    fun session(token: String): StreamCacheSession? = synchronized(lock) { sessions[token] }

    /**
     * `http://127.0.0.1:<port>/s/<token>/<encoded file name>`; the name is percent-encoded with
     * only `A-Za-z0-9-._~` unescaped (UTF-8), "video" if encoding fails.
     */
    fun playbackUrl(port: Int, session: StreamCacheSession): String {
        val encodedName = percentEncode(session.fileName) ?: "video"
        return "http://$HOST:$port/s/${session.token}/$encodedName"
    }

    /**
     * File session: decrements its count (removes at 0) and markActive(-1); done.
     * Otherwise: token from URL → removes the session, cancels its live connections
     * ([StreamCacheProxyConnection.cancelFromOutside]: closes socket + cancels upstream call),
     * markActive(-1). Unknown URLs are ignored. Safe from the main thread (no blocking IO).
     */
    fun releasePlaybackUrl(playbackUrl: String) {
        val releasedFileEntry: String? = synchronized(lock) {
            val fileSession = fileSessions[playbackUrl]
            if (fileSession != null) {
                if (fileSession.count <= 1) {
                    fileSessions.remove(playbackUrl)
                } else {
                    fileSessions[playbackUrl] = fileSession.copy(count = fileSession.count - 1)
                }
                fileSession.entryId
            } else {
                null
            }
        }
        if (releasedFileEntry != null) {
            store.markActive(releasedFileEntry, -1)
            return
        }

        val token = tokenFromPlaybackUrl(playbackUrl) ?: return
        val removed: StreamCacheSession?
        val toCancel: List<StreamCacheProxyConnection>
        synchronized(lock) {
            removed = sessions.remove(token)
            toCancel = connectionTokens.entries
                .filter { it.value == token && connections.contains(it.key) }
                .map { it.key }
        }
        for (connection in toCancel) {
            try {
                connection.cancelFromOutside()
            } catch (t: Throwable) {
                logW("proxy connection cancel failed", t)
            }
        }
        if (removed != null) {
            store.markActive(removed.entryId, -1)
        }
    }

    // endregion

    // region Connections (called by StreamCacheProxyConnection)

    /** The connection resolved its request to [token] (so release can cancel it). */
    fun associate(connection: StreamCacheProxyConnection, token: String) {
        synchronized(lock) { connectionTokens[connection] = token }
    }

    /** Removes bookkeeping for a finished connection. Idempotent. */
    fun connectionClosed(connection: StreamCacheProxyConnection) {
        synchronized(lock) {
            connections.remove(connection)
            connectionTokens.remove(connection)
        }
    }

    // endregion

    companion object {
        const val HOST: String = "127.0.0.1"
        const val BACKLOG: Int = 50

        private const val TAG = "StreamCache"
        private const val ACCEPT_ERROR_BACKOFF_MS = 200L
        private const val UNRESERVED =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

        /** 127.0.0.1 without DNS (see class KDoc for why not getLoopbackAddress()). */
        val LOOPBACK_ADDRESS: InetAddress = InetAddress.getByAddress(HOST, byteArrayOf(127, 0, 0, 1))

        /**
         * Extracts the session token from a playback URL produced by [playbackUrl]: host must be
         * 127.0.0.1 or localhost and the path `/s/<token>/...`. Null otherwise.
         */
        fun tokenFromPlaybackUrl(playbackUrl: String): String? {
            val uri = try {
                URI(playbackUrl)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            if (host != HOST && !host.equals("localhost", ignoreCase = true)) return null
            val path = uri.rawPath ?: return null
            val components = path.split('/').filter { it.isNotEmpty() }
            if (components.size < 2 || components[0] != "s") return null
            return components[1]
        }

        /** Percent-encodes [value] as UTF-8, leaving only RFC 3986 unreserved characters. */
        private fun percentEncode(value: String): String? {
            val bytes = try {
                value.toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            val out = StringBuilder(bytes.size * 3)
            for (b in bytes) {
                val c = (b.toInt() and 0xFF)
                if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) {
                    out.append(c.toChar())
                } else {
                    out.append('%')
                    out.append(HEX_DIGITS[c shr 4])
                    out.append(HEX_DIGITS[c and 0x0F])
                }
            }
            return out.toString()
        }

        /** android.util.Log, tolerant of JVM host tests where it is not mocked. */
        private fun logW(message: String, error: Throwable? = null) {
            try {
                if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
            } catch (_: Throwable) {
            }
        }

        private fun logI(message: String) {
            try {
                Log.i(TAG, message)
            } catch (_: Throwable) {
            }
        }
        private fun closeQuietly(socket: ServerSocket?) {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }
}
