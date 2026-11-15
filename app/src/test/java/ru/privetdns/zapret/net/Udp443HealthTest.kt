package ru.privetdns.zapret.net

import org.junit.Assert.*
import org.junit.Test

class Udp443HealthTest {
    private val initial = ByteArray(1200).apply { this[0] = 0xc0.toByte() }
    private val next = byteArrayOf(0x40)

    @Test fun failedHandshakeFallsBackTemporarilyAndResets() {
        var now = 1000L
        val health = Udp443Health { now }
        assertTrue(health.allow("cdn:443", initial))
        now += 1000
        assertTrue(health.allow("cdn:443", next))
        now += 1600
        assertFalse(health.allow("cdn:443", next))
        assertFalse(health.allow("cdn:443", initial))
        assertTrue(health.allow("other:443", initial))
        health.reset()
        assertTrue(health.allow("cdn:443", initial))
    }

    @Test fun answeredFlowIsNotMarkedBlocked() {
        var now = 1000L
        val health = Udp443Health { now }
        assertTrue(health.allow("cdn:443", initial))
        now += 2000
        health.received("cdn:443")
        assertTrue(health.allow("cdn:443", next))
        now += 1500
        assertTrue(health.allow("cdn:443", next))
        health.received("cdn:443")
        now += 5000
        assertTrue(health.allow("cdn:443", next))
    }

    @Test fun otherUdpTrafficIsUnchanged() {
        val health = Udp443Health { 10_000 }
        repeat(10) { assertTrue(health.allow("voice:443", byteArrayOf(1, 2, 3))) }
    }
}
