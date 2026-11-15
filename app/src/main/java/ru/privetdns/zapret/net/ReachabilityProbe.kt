package ru.privetdns.zapret.net

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A small HTTPS transfer through ByeDPI, independent of Android's captive-portal verdict. */
class ReachabilityProbe(
    private val resolver: EncryptedDns,
    private val nativePort: Int,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) {
    private data class Target(val host: String, val path: String, val status: Int)
    private val targets = listOf(
        Target("speed.cloudflare.com", "/__down?bytes=4096", 200),
        Target("www.gstatic.com", "/generate_204", 204)
    )

    /** Returns the provider that answered, or throws after both independent probes fail. */
    fun check(): String {
        for (target in targets) {
            if (runCatching { check(target) }.getOrDefault(false)) return target.host
        }
        throw IOException("No HTTPS probe returned through the local engine")
    }

    private fun check(target: Target): Boolean {
        val query = DnsWire.query(target.host)
        val addresses = DnsWire.addresses(query, resolver.resolve(query))
        for (address in addresses.take(4)) {
            val ok = runCatching {
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", nativePort))).use { raw ->
                    raw.connect(InetSocketAddress(address, 443), 4000)
                    raw.soTimeout = 5000
                    (tlsFactory.createSocket(raw, target.host, 443, true) as SSLSocket).use { tls ->
                        tls.soTimeout = 5000
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tls.startHandshake()
                        tls.outputStream.write(("GET ${target.path} HTTP/1.1\r\nHost: ${target.host}\r\n" +
                            "Connection: close\r\nAccept: */*\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        tls.outputStream.flush()
                        val input = BufferedInputStream(tls.inputStream)
                        val status = readLine(input).split(' ').getOrNull(1)?.toIntOrNull()
                        if (status != target.status) return@use false
                        while (readLine(input).isNotEmpty()) { }
                        // A 204 proves HTTPS application data was exchanged. A 200 must carry data.
                        status == 204 || input.read() >= 0
                    }
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < 4096) {
            val value = input.read()
            if (value < 0) throw IOException("Truncated HTTPS response")
            if (value == 10) return bytes.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r')
            bytes.add(value.toByte())
        }
        throw IOException("Oversized HTTPS header")
    }
}
