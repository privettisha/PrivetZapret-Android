package ru.privetdns.zapret.net

import org.junit.Assert.*
import org.junit.Test

class TelegramWebProxyLinkTest {
    private val good = "https://t.me/webproxy?server=dns.privetdns.ru&secret=0123456789abcdef0123456789abcdef"

    @Test fun acceptsCabinetLink() {
        assertEquals(good.replace("https://t.me/", "tg://"), TelegramWebProxyLink.parse("  $good  "))
    }

    @Test fun rejectsOtherHostsAndMalformedCredentials() {
        listOf(
            good.replace("t.me", "evil.example"),
            good.replace("https:", "http:"),
            "$good&port=443",
            good.replace("secret=0123456789abcdef0123456789abcdef", "secret=bad"),
            "$good&secret=0123456789abcdef0123456789abcdef",
            "$good#fragment"
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { TelegramWebProxyLink.parse(it) } }
    }
}
