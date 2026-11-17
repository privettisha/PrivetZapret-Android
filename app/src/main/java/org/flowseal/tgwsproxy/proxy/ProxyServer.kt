package org.flowseal.tgwsproxy.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

/**
 * SOCKS5 proxy server that bridges Telegram traffic through WebSocket.
 * Adapted Android port with WebSocket coverage for configured Telegram DCs.
 */
class ProxyServer(
    private val host: String = "127.0.0.1",
    private val port: Int = 1080,
    private val dcOpt: Map<Int, String> = mapOf(
        1 to "149.154.175.50", 2 to "149.154.167.220", 3 to "149.154.175.100",
        // Upstream recommends DC4 -> DC2 for media on non-Premium accounts.
        4 to "149.154.167.220", 5 to "91.108.56.100", 203 to "149.154.167.220"
    ),
    // Idle WebSockets can be closed remotely while still looking open locally;
    // a fresh route is safer on mobile network handovers.
    private val poolSize: Int = 0,
    private val bufKb: Int = 256,
    private val onLog: ((String) -> Unit)? = null
) {
    private val TAG = "ProxyServer"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(64)
    private val wsPool = WsPool(poolSize)
    val stats: Stats get() = wsPool.stats

    // Failure cooldown tracking
    private val dcFailUntil = ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    private val successfulRoutes = ConcurrentHashMap<Pair<Int, Boolean>, Pair<String, String>>()
    private val DC_FAIL_COOLDOWN = 30_000L  // 30 seconds

    private val bufSize = bufKb * 1024

    fun start() {
        if (running.getAndSet(true)) return
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, port))
            }
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
        executor = Executors.newCachedThreadPool { r ->
            Thread(r).apply {
                isDaemon = true
                name = "proxy-worker"
            }
        }

        Thread({
            try {
                log("Telegram WS Bridge Proxy listening on $host:$port")
                log("Target DCs: ${dcOpt.entries.joinToString { "DC${it.key}:${it.value}" }}")

                while (running.get()) {
                    try {
                        val client = serverSocket!!.accept()
                        if (!slots.tryAcquire()) { client.close(); continue }
                        clients.add(client)
                        client.tcpNoDelay = true
                        client.soTimeout = 15000
                        client.setSendBufferSize(bufSize)
                        client.setReceiveBufferSize(bufSize)
                        executor?.execute {
                            try { handleClient(client) }
                            finally { clients.remove(client); client.close(); slots.release() }
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                        }
                    }
                }
            } catch (e: Exception) {
                log("Failed to start: $e")
            }
        }, "proxy-server").start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        wsPool.shutdown()
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        log("Proxy stopped")
    }

    /** Force Telegram to reconnect on the new underlying Wi-Fi/mobile network. */
    fun onNetworkChanged() {
        dcFailUntil.clear()
        successfulRoutes.clear()
        clients.forEach { runCatching { it.close() } }
    }

    val isRunning: Boolean get() = running.get()

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    // --- SOCKS5 reply helpers ---

    private fun socks5Reply(status: Int): ByteArray =
        byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    // --- Client handling ---

    private fun handleClient(client: Socket) {
        stats.connectionsTotal.incrementAndGet()
        val label = "${client.inetAddress.hostAddress}:${client.port}"

        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 greeting
            val hdr = readExactly(input, 2)
            if (hdr[0].toInt() != 5) {
                client.close()
                return
            }
            val nmethods = hdr[1].toInt() and 0xFF
            readExactly(input, nmethods)
            output.write(byteArrayOf(0x05, 0x00)) // no-auth
            output.flush()

            // SOCKS5 CONNECT request
            val req = readExactly(input, 4)
            val cmd = req[1].toInt() and 0xFF
            val atyp = req[3].toInt() and 0xFF

            if (cmd != 1) {
                output.write(socks5Reply(0x07))
                output.flush()
                client.close()
                return
            }

            val dst: String
            when (atyp) {
                1 -> { // IPv4
                    val raw = readExactly(input, 4)
                    dst = InetAddress.getByAddress(raw).hostAddress!!
                }
                3 -> { // domain
                    val dlen = (readExactly(input, 1)[0].toInt() and 0xFF)
                    dst = String(readExactly(input, dlen), Charsets.US_ASCII)
                }
                4 -> { // IPv6
                    val raw = readExactly(input, 16)
                    dst = InetAddress.getByAddress(raw).hostAddress!!
                }
                else -> {
                    output.write(socks5Reply(0x08))
                    output.flush()
                    client.close()
                    return
                }
            }

            val portBytes = readExactly(input, 2)
            val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // Telegram can prefer IPv6 on mobile data; rejecting all IPv6 here
            // prevented the local WebSocket bridge from ever seeing MTProto.
            if (!TelegramDC.isTelegramIp(dst)) {
                stats.connectionsPassthrough.incrementAndGet()
                try {
                    val remote = OutboundSocket.create()
                    OutboundSocket.connect(remote, dst, dstPort, 10000)
                    remote.tcpNoDelay = true

                    output.write(socks5Reply(0x00))
                    output.flush()

                    bridgeTcp(input, output, remote.getInputStream(), remote.getOutputStream(),
                        label, client, remote)
                } catch (e: Exception) {
                    output.write(socks5Reply(0x05))
                    output.flush()
                    client.close()
                }
                return
            }

            // Telegram DC — accept SOCKS, read init
            output.write(socks5Reply(0x00))
            output.flush()

            val init: ByteArray
            try {
                init = readExactly(input, 64)
                client.soTimeout = 0
            } catch (_: Exception) {
                client.close()
                return
            }

            // HTTP transport → reject
            if (TelegramDC.isHttpTransport(init)) {
                stats.connectionsHttpRejected.incrementAndGet()
                client.close()
                return
            }

            // Extract DC ID
            var result = TelegramDC.dcFromInit(init)
            var dc = result.dc
            var isMedia = result.isMedia
            val proto = result.proto
            var initData = init
            var initPatched = false

            // Android with useSecret=0 has random dc_id — patch it
            if (dc == null) TelegramDC.dcFromIp(dst)?.let { (resolvedDc, resolvedMedia) ->
                dc = resolvedDc
                isMedia = resolvedMedia
                if (dc in dcOpt) {
                    initData = TelegramDC.patchInitDc(init, if (isMedia) -dc else dc)
                    initPatched = true
                }
            }

            if (dc == null || dc !in dcOpt) {
                tcpFallback(input, output, dst, dstPort, initData, label, client, dc, isMedia)
                return
            }

            val dcKey = dc to isMedia
            val now = System.currentTimeMillis()
            val mediaTag = if (isMedia) " media" else ""

            // Try WebSocket
            val failUntil = dcFailUntil[dcKey] ?: 0L
            if (now < failUntil) {
                tcpFallback(input, output, dst, dstPort, initData, label, client, dc, isMedia)
                return
            }
            val wsTimeout = 4000

            val domains = TelegramDC.wsDomains(dc, isMedia)
            val target = dcOpt[dc]!!
            var ws: RawWebSocket? = null

            // Media creates many short connections. Reuse the last working route and
            // avoid a fresh DoH lookup for every WebSocket handshake.
            successfulRoutes[dcKey]?.let { (domain, address) ->
                ws = runCatching { RawWebSocket.connect(address, domain, timeoutMs = 2500) }.getOrNull()
                if (ws == null) successfulRoutes.remove(dcKey, domain to address)
            }

            // Try pool first
            if (ws == null) ws = wsPool.get(dc, isMedia, target, domains)
            if (ws != null) {
                log("[$label] DC$dc$mediaTag ($dst:$dstPort) -> reused route")
            } else {
                // Fresh connection
                for (domain in domains) {
                    val url = "wss://$domain/apiws"
                    // The configured DC IP is usually fastest. Only resolve the WSS
                    // name when that route fails; DNS can be slow on mobile networks.
                    log("[$label] DC$dc$mediaTag ($dst:$dstPort) -> $url via $target")
                    try {
                        ws = RawWebSocket.connect(target, domain, timeoutMs = wsTimeout)
                        successfulRoutes[dcKey] = domain to target
                    } catch (e: Exception) {
                        stats.wsErrors.incrementAndGet()
                    }
                    if (ws == null) {
                        val resolved = wsAddress(domain, target)
                        if (resolved != target) {
                            try {
                                ws = RawWebSocket.connect(resolved, domain, timeoutMs = wsTimeout)
                                successfulRoutes[dcKey] = domain to resolved
                            } catch (e: Exception) {
                                stats.wsErrors.incrementAndGet()
                            }
                        }
                    }
                    if (ws != null) break
                }
            }

            // WS failed → fallback
            if (ws == null) {
                // A redirect or timeout is transient: retry after handover/new DNS.
                dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN
                log("[$label] DC$dc$mediaTag WS cooldown for ${DC_FAIL_COOLDOWN / 1000}s")
                log("[$label] DC$dc$mediaTag -> TCP fallback to $dst:$dstPort")
                tcpFallback(input, output, dst, dstPort, initData, label, client, dc, isMedia)
                return
            }

            // WS success
            dcFailUntil.remove(dcKey)
            stats.connectionsWs.incrementAndGet()

            var splitter: MsgSplitter? = null
            if (proto != null && (initPatched || isMedia || proto != TelegramDC.PROTO_INTERMEDIATE)) {
                try {
                    splitter = MsgSplitter(initData, proto)
                } catch (_: Exception) {}
            }

            // Send init packet
            ws.send(initData)

            // Bidirectional bridge
            bridgeWs(input, output, ws, label, client, dc, dst, dstPort, isMedia, splitter)

        } catch (e: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun wsAddress(domain: String, fallback: String): String =
        runCatching { OutboundSocket.resolveName?.invoke(domain)?.hostAddress }.getOrNull() ?: fallback

    // --- TCP fallback ---

    private fun tcpFallback(
        clientIn: InputStream, clientOut: OutputStream,
        dst: String, port: Int, init: ByteArray, label: String,
        clientSocket: Socket, dc: Int?, isMedia: Boolean
    ) {
        try {
            val remote = OutboundSocket.create()
            OutboundSocket.connect(remote, dst, port, 10000)
            remote.tcpNoDelay = true

            stats.connectionsTcpFallback.incrementAndGet()
            remote.getOutputStream().write(init)
            remote.getOutputStream().flush()

            bridgeTcp(clientIn, clientOut, remote.getInputStream(), remote.getOutputStream(),
                label, clientSocket, remote)

            log("[$label] DC$dc${if (isMedia) "m" else ""} TCP fallback closed")
        } catch (e: Exception) {
        }
    }

    // --- Bidirectional TCP <-> TCP ---

    private fun bridgeTcp(
        clientIn: InputStream, clientOut: OutputStream,
        remoteIn: InputStream, remoteOut: OutputStream,
        label: String, clientSocket: Socket, remoteSocket: Socket
    ) {
        val done = AtomicBoolean(false)

        val t1 = Thread({
            try {
                pipe(clientIn, remoteOut, isUp = true)
            } catch (_: Exception) {
            } finally {
                done.set(true)
                try { remoteSocket.close() } catch (_: Exception) {}
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }, "tcp-c2r-$label")

        val t2 = Thread({
            try {
                pipe(remoteIn, clientOut, isUp = false)
            } catch (_: Exception) {
            } finally {
                done.set(true)
                try { clientSocket.close() } catch (_: Exception) {}
                try { remoteSocket.close() } catch (_: Exception) {}
            }
        }, "tcp-r2c-$label")

        t1.start()
        t2.start()
        t1.join()
        t2.join()
    }

    private fun pipe(src: InputStream, dst: OutputStream, isUp: Boolean) {
        val buf = ByteArray(65536)
        while (true) {
            val n = src.read(buf)
            if (n == -1) break
            if (isUp) stats.bytesUp.addAndGet(n.toLong())
            else stats.bytesDown.addAndGet(n.toLong())
            dst.write(buf, 0, n)
            dst.flush()
        }
    }

    // --- Bidirectional TCP <-> WebSocket ---

    private fun bridgeWs(
        clientIn: InputStream, clientOut: OutputStream,
        ws: RawWebSocket, label: String, clientSocket: Socket,
        dc: Int, dst: String, port: Int, isMedia: Boolean,
        splitter: MsgSplitter?
    ) {
        val dcTag = "DC$dc${if (isMedia) "m" else ""}"
        val startTime = System.currentTimeMillis()
        var upBytes = 0L
        var downBytes = 0L

        val t1 = Thread({
            try {
                val buf = ByteArray(65536)
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) {
                        splitter?.flush()?.firstOrNull()?.let { ws.send(it) }
                        break
                    }
                    val chunk = buf.copyOfRange(0, n)
                    stats.bytesUp.addAndGet(n.toLong())
                    upBytes += n

                    if (splitter != null) {
                        val parts = splitter.split(chunk)
                        if (parts.isEmpty()) continue
                        if (parts.size > 1) ws.sendBatch(parts) else ws.send(parts[0])
                    } else {
                        ws.send(chunk)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { ws.close() } catch (_: Exception) {}
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }, "ws-c2s-$label")

        val t2 = Thread({
            try {
                while (true) {
                    val data = ws.recv() ?: break
                    stats.bytesDown.addAndGet(data.size.toLong())
                    downBytes += data.size
                    clientOut.write(data)
                    clientOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
                try { ws.close() } catch (_: Exception) {}
            }
        }, "ws-s2c-$label")

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        log("[$label] $dcTag ($dst:$port) WS closed: ^${Stats.humanBytes(upBytes)} v${Stats.humanBytes(downBytes)} in %.1fs".format(elapsed))
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) throw java.io.EOFException("Connection closed reading $n bytes at offset $offset")
            offset += read
        }
        return buf
    }
}
