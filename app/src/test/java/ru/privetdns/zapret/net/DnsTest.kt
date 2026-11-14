package ru.privetdns.zapret.net

import org.junit.Test
import org.junit.Assert.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.net.ServerSocket

class DnsTest {
    @Test fun preservesPersonalDohPathAndQuery() {
        val e = DnsEndpoint.parse("https://dns.example/dns-query/personal?key=abc")
        assertEquals(DnsEndpoint.Kind.DOH, e.kind)
        assertEquals("https://dns.example/dns-query/personal?key=abc", e.value)
        assertEquals("DOH", e.toString())
    }
    @Test fun supportsAndroidPrivateDnsAndCustomPorts() {
        assertEquals(853, DnsEndpoint.parse("personal.dot.example").port)
        assertEquals(443, DnsEndpoint.parse("dot://dns.example:443").port)
        assertEquals("2001:db8::1", DnsEndpoint.parse("tls://[2001:db8::1]:853").host)
    }
    @Test fun rejectsUnsafeOrIncompleteUrls() {
        listOf("", "http://dns.example/dns-query", "https://dns.example", "https://a:b@dns.example/dns-query",
            "https://dns.example/dns-query#secret", "tls://dns.example/path", "tls://dns.example:99999",
            "dns.example abc", "tls://dns.example?secret=a").forEach {
            assertThrows("Must reject $it", IllegalArgumentException::class.java) { DnsEndpoint.parse(it) }
        }
    }
    @Test fun fallbackMustBeExplicitDoh() {
        val p = DnsProfile.parse("unique.dot.example", "", "")
        assertNull(p.fallback)
        assertThrows(IllegalArgumentException::class.java) { DnsProfile.parse("dns.example", "other.example", "") }
    }
    @Test fun bootstrapRejectsHostnamesAndSpecialAddresses() {
        listOf("dns.example", "127.0.0.1", "0.0.0.0", "999.1.1.1", "ff02::1").forEach {
            assertThrows(IllegalArgumentException::class.java) { DnsProfile.parse("dns.example", "", it) }
        }
        assertEquals(2, DnsProfile.parse("dns.example", "", "1.1.1.1, 2606:4700:4700::1111").bootstrap.size)
    }
    @Test fun rejectsResponseForAnotherQuestionOrTransaction() {
        val q = DnsWire.query("example.com")
        val good = q.clone().apply { this[2] = 129.toByte() }
        DnsWire.checkResponse(q, good)
        assertThrows(IllegalArgumentException::class.java) { DnsWire.checkResponse(q, good.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { DnsWire.checkResponse(q, good.clone().apply { this[13] = 'x'.code.toByte() }) }
    }
    @Test fun malformedQueriesCannotLoopOrReadPastPacket() {
        listOf(byteArrayOf(), ByteArray(12), ByteArray(17).apply { this[5] = 1; this[12] = 192.toByte(); this[13] = 12 }).forEach {
            assertThrows(Exception::class.java) { DnsWire.checkQuery(it) }
        }
    }
    @Test fun servfailKeepsQuestionAndTransaction() {
        val q = DnsWire.query("example.com")
        val failure = DnsWire.failure(q)
        DnsWire.checkResponse(q, failure)
        assertEquals(2, failure[3].toInt() and 15)
        assertFalse(DnsWire.hasAnswer(failure))
    }
    private fun tlsServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCert.sslSocketFactory(), false); server.start()
            block(server, OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build())
        }
    }
    @Test fun dohUsesPostBinaryAndChecksResponse() = tlsServer { server, client ->
        val q = DnsWire.query("example.com"); val a = DnsWire.failure(q)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message").setBody(Buffer().write(a)))
        EncryptedDns(DnsProfile.parse(server.url("/dns-query/private-key").toString(), "", ""), baseHttp = client).use {
            assertArrayEquals(a, it.resolve(q))
        }
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("application/dns-message", request.getHeader("Content-Type"))
        assertArrayEquals(q, request.body.readByteArray())
    }
    @Test fun blockedDotFallsBackToExplicitDohAndKeepsUsingIt() = tlsServer { server, client ->
        val q = DnsWire.query("example.com"); val a = DnsWire.failure(q)
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message").setBody(Buffer().write(a))) }
        val unused = ServerSocket(0).use { it.localPort }
        val profile = DnsProfile.parse("tls://127.0.0.1:$unused", server.url("/dns-query/independent-key").toString(), "")
        var status = ""
        EncryptedDns(profile, status = { status = it }, baseHttp = client).use {
            assertArrayEquals(a, it.resolve(q)); assertArrayEquals(a, it.resolve(q))
        }
        assertEquals("DoH · резерв", status); assertEquals(2, server.requestCount)
    }
    @Test fun refusesUntrustedTlsAndNeverFollowsRedirect() = tlsServer { server, client ->
        val profile = DnsProfile.parse(server.url("/dns-query").toString(), "", "")
        EncryptedDns(profile).use { dns -> assertThrows(Exception::class.java) { dns.resolve(DnsWire.query("example.com")) } }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1/leak"))
        EncryptedDns(profile, baseHttp = client).use { dns -> assertThrows(Exception::class.java) { dns.resolve(DnsWire.query("example.com")) } }
        assertEquals(1, server.requestCount)
    }
    @Test fun rejectsOversizedDohBody() = tlsServer { server, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message").setChunkedBody(Buffer().write(ByteArray(70000)), 4096))
        EncryptedDns(DnsProfile.parse(server.url("/dns-query").toString(), "", ""), baseHttp = client).use { dns ->
            assertThrows(Exception::class.java) { dns.resolve(DnsWire.query("example.com")) }
        }
    }
    @Test fun dotExchangesFramedDnsWithVerifiedTls() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        serverCert.sslContext().serverSocketFactory.createServerSocket(0).use { server ->
            val q = DnsWire.query("example.com"); val a = DnsWire.failure(q)
            val task = executor.submit {
                server.accept().use { s ->
                    val input = java.io.DataInputStream(s.getInputStream())
                    val received = ByteArray(input.readUnsignedShort()); input.readFully(received)
                    assertArrayEquals(q, received)
                    java.io.DataOutputStream(s.getOutputStream()).apply { writeShort(a.size); write(a); flush() }
                }
            }
            EncryptedDns(DnsProfile.parse("tls://localhost:${server.localPort}", "", ""), tlsFactory = clientCert.sslSocketFactory()).use {
                assertArrayEquals(a, it.resolve(q))
            }
            task.get(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        executor.shutdownNow()
    }
}
