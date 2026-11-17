package ru.privetdns.zapret.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validate a cabinet-issued Telegram WEB Proxy link before passing it to Telegram. */
object TelegramWebProxyLink {
    fun parse(raw: String): String {
        val text = raw.trim()
        require(text.length in 1..2048 && text.none { it.isWhitespace() }) { "Вставьте полную ссылку WEB Proxy из кабинета" }
        val url = text.toHttpUrlOrNull()
        require(url != null && url.scheme == "https" && url.host == "t.me" && url.encodedPath == "/webproxy" &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            url.queryParameterNames == setOf("server", "secret") && url.querySize == 2) {
            "Нужна ссылка https://t.me/webproxy из кабинета"
        }
        val server = url.queryParameter("server") ?: ""
        val secret = url.queryParameter("secret") ?: ""
        require(server.length in 1..253 && server.matches(Regex("[A-Za-z0-9.-]+")) &&
            !server.startsWith('.') && !server.endsWith('.') && ".." !in server &&
            secret.matches(Regex("[0-9a-fA-F]{32}"))) {
            "В ссылке неверный адрес или ключ WEB Proxy"
        }
        // The Telegram app handles the direct scheme even if the t.me website
        // has not registered the WEB Proxy landing route.
        return "tg://webproxy?${url.encodedQuery}"
    }
}
