package ru.privetdns.zapret.net

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.util.concurrent.*

class GatewayTest {
    private fun connect(g: SocksGateway, command: Int, port: Int, secret: String = "test-secret"): Socket {
        val s = Socket("127.0.0.1", g.port); s.soTimeout = 3000
        val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
        out.write(byteArrayOf(5, 1, 2)); assertEquals(5, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
        out.write(byteArrayOf(1, 6) + "privet".toByteArray() + byteArrayOf(secret.length.toByte()) + secret.toByteArray())
        assertEquals(1, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
        out.write(byteArrayOf(5, command.toByte(), 0, 1, 10, 111, 0, 2, (port shr 8).toByte(), port.toByte()))
        return s
    }
    @Test fun tcpDnsSupportsMultipleFramedQueries() {
        SocksGateway(1, "test-secret", DnsWire::failure, true).use { g ->
            g.start(); connect(g, 1, 53).use { socket ->
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                val header = ByteArray(10); input.readFully(header); assertEquals(0, header[1].toInt())
                repeat(3) {
                    val query = DnsWire.query("example.com"); output.writeShort(query.size); output.write(query)
                    val response = ByteArray(input.readUnsignedShort()); input.readFully(response)
                    DnsWire.checkResponse(query, response)
                }
            }
        }
    }
    @Test fun udpDnsPreservesSourceAddressAndWorksAfterResolverFailure() {
        var calls = 0
        SocksGateway(1, "test-secret", { q -> calls++; if (calls == 1) throw IOException("offline") else DnsWire.failure(q) }, true).use { g ->
            g.start(); connect(g, 3, 0).use { control ->
                val header = ByteArray(10); DataInputStream(control.getInputStream()).readFully(header)
                val relayPort = (header[8].toInt().and(255) shl 8) or header[9].toInt().and(255)
                DatagramSocket().use { udp ->
                    udp.soTimeout = 3000
                    repeat(2) {
                        val q = DnsWire.query("example.com")
                        val prefix = byteArrayOf(0, 0, 0, 1, 10, 111, 0, 2, 0, 53)
                        val request = prefix + q; udp.send(DatagramPacket(request, request.size, InetAddress.getLoopbackAddress(), relayPort))
                        val response = DatagramPacket(ByteArray(4096), 4096); udp.receive(response)
                        assertArrayEquals(prefix, response.data.copyOfRange(0, 10))
                        DnsWire.checkResponse(q, response.data.copyOfRange(10, response.length))
                    }
                }
            }
        }
        assertEquals(2, calls)
    }
    @Test fun unauthenticatedSocksIsRejected() {
        SocksGateway(1, "test-secret", DnsWire::failure, true).use { g ->
            g.start(); Socket("127.0.0.1", g.port).use { s ->
                s.soTimeout = 2000; s.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, s.getInputStream().read()); assertEquals(255, s.getInputStream().read())
            }
        }
    }
    @Test fun closingGatewayInterruptsIdleClientsAndAllowsNewInstance() {
        repeat(2) {
            val g = SocksGateway(1, "test-secret", DnsWire::failure, false); g.start()
            connect(g, 1, 53).use { s ->
                val h = ByteArray(10); DataInputStream(s.getInputStream()).readFully(h)
                g.close(); assertEquals(-1, s.getInputStream().read())
            }
        }
    }
    @Test fun tcpRelayPreservesLargePayload() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { upstream ->
            val worker = Executors.newSingleThreadExecutor()
            val done = worker.submit {
                upstream.accept().use { s ->
                    val input = DataInputStream(s.getInputStream()); val output = s.getOutputStream()
                    val greeting = ByteArray(3); input.readFully(greeting); output.write(byteArrayOf(5, 0))
                    val request = ByteArray(10); input.readFully(request); output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                    val data = ByteArray(256 * 1024); input.readFully(data); output.write(data)
                }
            }
            SocksGateway(upstream.localPort, "test-secret", DnsWire::failure, false).use { g ->
                g.start(); connect(g, 1, 443).use { s ->
                    val input = DataInputStream(s.getInputStream()); val h = ByteArray(10); input.readFully(h)
                    val payload = ByteArray(256 * 1024) { (it % 239).toByte() }; s.getOutputStream().write(payload)
                    val answer = ByteArray(payload.size); input.readFully(answer); assertArrayEquals(payload, answer)
                }
            }
            done.get(3, TimeUnit.SECONDS); worker.shutdownNow()
        }
    }
}
