package org.flowseal.tgwsproxy.proxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.InetAddress

/** Send Telegram bridge connections through the local ByeDPI listener. */
internal object OutboundSocket {
    @Volatile var byeDpiPort: Int = 0
    @Volatile var resolveName: ((String) -> InetAddress)? = null

    fun create(): Socket = if (byeDpiPort > 0)
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", byeDpiPort)))
    else Socket()

    fun connect(socket: Socket, host: String, port: Int, timeout: Int) {
        val address = if (host.contains(':') || host.matches(Regex("[0-9.]+"))) InetAddress.getByName(host)
            else resolveName?.invoke(host) ?: throw java.io.IOException("Encrypted DNS unavailable")
        socket.connect(InetSocketAddress(address, port), timeout)
    }
}
