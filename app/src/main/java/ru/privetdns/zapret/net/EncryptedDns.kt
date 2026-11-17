package ru.privetdns.zapret.net

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** No DNS queries are sent to UDP/53 here. Only bootstrap may use the system resolver. */
class EncryptedDns(
    private val profile: DnsProfile,
    private val proxy: Proxy = Proxy.NO_PROXY,
    private val status: (String) -> Unit = {},
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    baseHttp: OkHttpClient = OkHttpClient()
) : Closeable {
    private val fallbackUntil = AtomicLong(0)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    private fun lookup(host: String): List<InetAddress> = when {
        host.equals(profile.primary.host, true) && profile.bootstrap.isNotEmpty() -> profile.bootstrap
        // Public bootstrap addresses belong to these well-known anycast resolvers only.
        host == "cloudflare-dns.com" -> listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
        host == "dns.google" -> listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
        else -> Dns.SYSTEM.lookup(host)
    }
    private val http = baseHttp.newBuilder().proxy(Proxy.NO_PROXY).socketFactory(object : SocketFactory() {
        override fun createSocket() = Socket(proxy)
        override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(lookup(host).first(), port), 5000) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket().apply {
            bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(lookup(host).first(), port), 5000)
        }
        override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port), 5000) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket().apply {
            bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port), 5000)
        }
    }).dns(object : Dns { override fun lookup(hostname: String) = this@EncryptedDns.lookup(hostname) })
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(true).build()

    fun resolve(query: ByteArray): ByteArray {
        DnsWire.checkQuery(query)
        check(!closed)
        val backup = profile.fallback
        if (backup != null && System.nanoTime() < fallbackUntil.get()) {
            try { return exchange(backup, query).also { status("DoH · резерв") } }
            catch (_: Exception) { fallbackUntil.set(0) }
        }
        try {
            return exchange(profile.primary, query).also { status(if (profile.primary.kind == DnsEndpoint.Kind.DOH) "DoH · HTTPS" else "DoT · TLS") }
        } catch (_: Exception) {
            if (backup != null && !closed) {
                val answer = exchange(backup, query)
                fallbackUntil.set(System.nanoTime() + TimeUnit.MINUTES.toNanos(5))
                status("DoH · резерв")
                return answer
            }
            throw IOException("Зашифрованный DNS недоступен")
        }
    }

    private fun exchange(endpoint: DnsEndpoint, query: ByteArray): ByteArray {
        check(!closed)
        val response = if (endpoint.kind == DnsEndpoint.Kind.DOH) doh(endpoint, query) else dot(endpoint, query)
        DnsWire.checkResponse(query, response)
        return response
    }
    private fun doh(endpoint: DnsEndpoint, query: ByteArray): ByteArray {
        // SocketFactory supplies a SOCKS socket; OkHttp resolves the bootstrap IP and still
        // checks the certificate and sends SNI for the original URL hostname.
        val request = Request.Builder().url(endpoint.value)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody("application/dns-message".toMediaType())).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful || r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "application/dns-message")
                throw IOException("DNS HTTP error")
            val body = r.body ?: throw IOException("Empty DNS response")
            if (body.contentLength() > 65535) throw IOException("DNS response too large")
            return body.byteStream().use { stream ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    if (result.size() + n > 65535) throw IOException("DNS response too large")
                    result.write(buffer, 0, n)
                }
                result.toByteArray()
            }
        }
    }
    private fun dot(endpoint: DnsEndpoint, query: ByteArray): ByteArray {
        // Try every configured/bootstrap address, keeping the original TLS name and certificate check.
        val addresses = lookup(endpoint.host)
        if (addresses.isEmpty()) throw IOException("DNS bootstrap failed")
        // A blocked DoT port must not delay the explicit DoH fallback once per IP.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        for (address in addresses) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).toInt()
            if (remainingMs <= 0) break
            val raw = Socket(proxy)
            sockets.add(raw)
            try {
                raw.connect(InetSocketAddress(address, endpoint.port), minOf(3000, remainingMs))
                raw.soTimeout = minOf(4000, remainingMs)
                (tlsFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket).use { tls ->
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    DataOutputStream(tls.outputStream).apply { writeShort(query.size); write(query); flush() }
                    val input = DataInputStream(tls.inputStream)
                    val length = input.readUnsignedShort()
                    if (length < 12) throw IOException("Invalid DNS frame")
                    return ByteArray(length).also { input.readFully(it) }
                }
            } catch (_: IOException) {
                if (closed) break
            } finally { sockets.remove(raw); runCatching { raw.close() } }
        }
        throw IOException("DoT server unavailable on all addresses")
    }
    fun onNetworkChanged() {
        fallbackUntil.set(0)
        http.connectionPool.evictAll()
    }
    override fun close() {
        closed = true
        http.dispatcher.cancelAll(); http.connectionPool.evictAll()
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
    }
}
