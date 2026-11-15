package ru.privetdns.zapret.net

import java.util.concurrent.ConcurrentHashMap

/** Let responsive QUIC through, but make a blackholed endpoint fall back to TCP. */
internal class Udp443Health(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Flow {
        var tracked = false
        var windowStart = 0L
        var sent = 0
        var blockedUntil = 0L
    }

    private val flows = ConcurrentHashMap<String, Flow>()

    fun allow(destination: String, packet: ByteArray): Boolean {
        val initial = packet.size >= 1200 && packet[0].toInt() and 0xC0 == 0xC0
        val flow = flows[destination] ?: if (initial) flows.computeIfAbsent(destination) { Flow() } else return true
        synchronized(flow) {
            val now = nowMs()
            if (flow.blockedUntil != 0L && now < flow.blockedUntil) return false
            if (!flow.tracked || now >= flow.blockedUntil && flow.blockedUntil != 0L) {
                flow.tracked = true
                flow.windowStart = now
                flow.sent = 0
                flow.blockedUntil = 0
            }
            if (flow.sent >= 2 && now - flow.windowStart >= 2500) {
                flow.blockedUntil = now + 60_000
                return false
            }
            flow.sent++
            return true
        }
    }

    fun received(destination: String) {
        flows[destination]?.let { flow ->
            synchronized(flow) {
                flow.windowStart = nowMs()
                flow.sent = 0
                flow.blockedUntil = 0
            }
        }
    }

    fun reset() = flows.clear()
}
