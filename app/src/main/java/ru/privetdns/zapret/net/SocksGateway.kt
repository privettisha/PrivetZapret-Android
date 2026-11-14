package ru.privetdns.zapret.net

import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated loopback bridge: DNS -> encrypted resolver; TCP -> ByeDPI; other UDP -> direct. */
class SocksGateway(
    private val nativePort: Int,
    private val password: String,
    private val resolve: (ByteArray) -> ByteArray,
    private val blockQuic: Boolean
) : Closeable {
    private val alive = AtomicBoolean(true)
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val connections = ConcurrentHashMap.newKeySet<Closeable>()
    private val slots = Semaphore(96)
    private val udpSlots = Semaphore(64)
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(null, runnable, "pzp-io", 256 * 1024L).apply { isDaemon = true }
    }
    private val dnsWorkers = ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(null, r, "pzp-dns", 256 * 1024L).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun start() {
        workers.execute {
            while (alive.get()) {
                val client = try { server.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                connections.add(client)
                workers.execute {
                    try { serve(client) } catch (_: Exception) { /* No endpoint or DNS logs. */ }
                    finally { connections.remove(client); runCatching { client.close() }; slots.release() }
                }
            }
        }
    }
    private data class Target(val wire: ByteArray, val host: String, val port: Int)
    private fun target(input: DataInputStream): Target {
        val bytes = ByteArrayOutputStream()
        val type = input.readUnsignedByte(); bytes.write(type)
        val host = when (type) {
            1, 4 -> {
                val b = ByteArray(if (type == 1) 4 else 16); input.readFully(b); bytes.write(b)
                InetAddress.getByAddress(b).hostAddress!!
            }
            3 -> {
                val size = input.readUnsignedByte(); require(size > 0); bytes.write(size)
                val b = ByteArray(size); input.readFully(b); bytes.write(b); b.toString(Charsets.US_ASCII)
            }
            else -> throw IOException("Unknown address")
        }
        val port = input.readUnsignedShort(); bytes.write(port shr 8); bytes.write(port and 255)
        return Target(bytes.toByteArray(), host, port)
    }
    private fun reply(output: OutputStream, code: Int = 0, port: Int = 0) {
        output.write(byteArrayOf(5, code.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())); output.flush()
    }
    private fun serve(client: Socket) {
        client.soTimeout = 8000; client.tcpNoDelay = true
        val input = DataInputStream(client.getInputStream()); val output = client.getOutputStream()
        require(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
        if (2.toByte() !in methods) { output.write(byteArrayOf(5, -1)); return }
        output.write(byteArrayOf(5, 2)); output.flush()
        require(input.readUnsignedByte() == 1)
        val user = ByteArray(input.readUnsignedByte()); input.readFully(user)
        val pass = ByteArray(input.readUnsignedByte()); input.readFully(pass)
        if (!MessageDigest.isEqual(pass, password.toByteArray()) || !user.contentEquals("privet".toByteArray())) {
            output.write(byteArrayOf(1, 1)); return
        }
        output.write(byteArrayOf(1, 0)); output.flush()
        require(input.readUnsignedByte() == 5)
        val command = input.readUnsignedByte(); require(input.readUnsignedByte() == 0)
        val destination = target(input)
        when (command) {
            1 -> if (destination.port == 53) tcpDns(client, input, output) else tcp(client, destination)
            3 -> udp(client, destination.port)
            else -> reply(output, 7)
        }
    }
    private fun tcpDns(client: Socket, input: DataInputStream, output: OutputStream) {
        reply(output); client.soTimeout = 30000
        val framed = DataOutputStream(output)
        while (alive.get()) {
            val size = input.readUnsignedShort()
            require(size in 12..4096)
            val query = ByteArray(size); input.readFully(query)
            val response = answer(query)
            framed.writeShort(response.size); framed.write(response); framed.flush()
        }
    }
    private fun answer(query: ByteArray): ByteArray {
        return try { DnsWire.checkQuery(query); resolve(query) }
        catch (_: Exception) { DnsWire.failure(query) }
    }
    private fun tcp(client: Socket, target: Target) {
        Socket().use { upstream ->
            connections.add(upstream)
            try {
                upstream.connect(InetSocketAddress("127.0.0.1", nativePort), 4000)
                upstream.soTimeout = 10000; upstream.tcpNoDelay = true
                val output = upstream.getOutputStream(); val input = DataInputStream(upstream.getInputStream())
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
                // ByeDPI expects the complete SOCKS request in its first read.
                output.write(byteArrayOf(5, 1, 0) + target.wire); output.flush()
                require(input.readUnsignedByte() == 5)
                val code = input.readUnsignedByte(); input.readUnsignedByte(); target(input)
                reply(client.getOutputStream(), code)
                if (code != 0) return
                upstream.soTimeout = 300000; client.soTimeout = 300000
                val down = workers.submit {
                    try { input.copyTo(client.getOutputStream(), 32768) } catch (_: Exception) { }
                    finally { runCatching { client.shutdownOutput() }; runCatching { upstream.shutdownInput() } }
                }
                try { client.getInputStream().copyTo(output, 32768) } catch (_: Exception) { }
                finally { runCatching { upstream.shutdownOutput() } }
                runCatching { down.get(5, TimeUnit.SECONDS) }; down.cancel(true)
            } finally { connections.remove(upstream) }
        }
    }
    private fun udp(control: Socket, requestedPort: Int) {
        val relay = DatagramSocket(InetSocketAddress("127.0.0.1", 0)); connections.add(relay)
        val flows = ConcurrentHashMap<String, DatagramSocket>()
        reply(control.getOutputStream(), port = relay.localPort)
        control.soTimeout = 0
        val receiver = workers.submit {
            var clientAddress: InetSocketAddress? = null
            val bytes = ByteArray(65535)
            while (alive.get() && !relay.isClosed) {
                val packet = DatagramPacket(bytes, bytes.size)
                try { relay.receive(packet) } catch (_: IOException) { break }
                if (!packet.address.isLoopbackAddress || (requestedPort != 0 && packet.port != requestedPort)) continue
                val source = packet.socketAddress as InetSocketAddress
                if (clientAddress != null && clientAddress != source) continue
                try {
                    val input = DataInputStream(ByteArrayInputStream(packet.data, 0, packet.length))
                    if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) continue // fragments unsupported
                    val destination = target(input)
                    val payload = input.readBytes()
                    if (clientAddress == null) clientAddress = source
                    fun sendReply(data: ByteArray) {
                        if (data.isEmpty()) return
                        val response = byteArrayOf(0, 0, 0) + destination.wire + data
                        runCatching { relay.send(DatagramPacket(response, response.size, source)) }
                    }
                    if (destination.port == 53) {
                        try { dnsWorkers.execute { sendReply(answer(payload)) } } catch (_: RejectedExecutionException) { }
                        continue
                    }
                    if (blockQuic && destination.port == 443) continue
                    // Hev normally sends IP addresses. Refuse a domain UDP address rather than leak a DNS lookup.
                    if (destination.wire[0].toInt() == 3) continue
                    val key = "${destination.host}:${destination.port}"
                    var flow = flows[key]
                    if (flow == null) {
                        if (!udpSlots.tryAcquire()) continue
                        val created = try {
                            DatagramSocket().apply { connect(InetSocketAddress(destination.host, destination.port)); soTimeout = 30000 }
                        } catch (_: Exception) { udpSlots.release(); continue }
                        flow = created; flows[key] = created; connections.add(created)
                        workers.execute {
                            try {
                                val responseBytes = ByteArray(65507)
                                while (alive.get() && !created.isClosed) {
                                    val response = DatagramPacket(responseBytes, responseBytes.size)
                                    created.receive(response); sendReply(response.data.copyOf(response.length))
                                }
                            } catch (_: Exception) { }
                            finally { flows.remove(key, created); connections.remove(created); created.close(); udpSlots.release() }
                        }
                    }
                    runCatching { flow.send(DatagramPacket(payload, payload.size)) }
                } catch (_: Exception) { /* malformed packet, isolated from other flows */ }
            }
        }
        try { while (control.getInputStream().read() != -1) { } }
        finally {
            relay.close(); connections.remove(relay)
            flows.values.forEach { it.close() }; receiver.cancel(true)
        }
    }
    override fun close() {
        alive.set(false); server.close()
        connections.forEach { runCatching { it.close() } }; connections.clear()
        dnsWorkers.shutdownNow(); workers.shutdownNow()
    }
}
