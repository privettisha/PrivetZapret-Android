package org.flowseal.tgwsproxy.proxy

import org.junit.Assert.*
import org.junit.Test

class TelegramDCTest {
    @Test fun recognizesOfficialIpv6Datacenters() {
        val addresses = listOf(
            "2001:b28:f23d:f001::a" to 1,
            "2001:67c:4e8:f002::a" to 2,
            "2001:b28:f23d:f003::e" to 3,
            "2001:67c:4e8:f004::a" to 4,
            "2001:b28:f23f:f005::a" to 5
        )
        for ((address, dc) in addresses) {
            assertTrue(address, TelegramDC.isTelegramIp(address))
            assertEquals(dc, TelegramDC.dcFromIp(address)?.first)
        }
    }

    @Test fun doesNotClassifyOtherIpv6OrDomainsAsTelegram() {
        assertFalse(TelegramDC.isTelegramIp("2606:4700:4700::1111"))
        assertFalse(TelegramDC.isTelegramIp("telegram.org"))
        assertNull(TelegramDC.dcFromIp("2001:db8::1"))
    }

    @Test fun preservesIpv4Detection() {
        assertTrue(TelegramDC.isTelegramIp("149.154.167.51"))
        assertEquals(2, TelegramDC.dcFromIp("149.154.167.51")?.first)
        assertFalse(TelegramDC.isTelegramIp("1.1.1.1"))
    }
}
