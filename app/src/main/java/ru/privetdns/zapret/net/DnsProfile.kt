package ru.privetdns.zapret.net

import java.net.URI
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Full endpoints may contain personal credentials. Never use them in logs or UI status. */
class DnsEndpoint private constructor(val kind: Kind, val value: String, val host: String, val port: Int) {
    enum class Kind { DOH, DOT }
    override fun toString() = kind.name
    companion object {
        fun parse(input: String): DnsEndpoint {
            val text = input.trim()
            require(text.isNotEmpty() && text.length <= 2048 && text.none { it.isWhitespace() }) {
                "Вставьте адрес DNS без пробелов"
            }
            if (text.startsWith("https://", true)) {
                val u = text.toHttpUrlOrNull()
                require(u != null && u.username.isEmpty() && u.password.isEmpty() && u.fragment == null) {
                    "Нужна полная HTTPS-ссылка DoH без имени пользователя и фрагмента"
                }
                require(u.encodedPath != "/") { "Добавьте путь DoH, например /dns-query" }
                return DnsEndpoint(Kind.DOH, u.toString(), u.host, u.port)
            }
            val normalized = when {
                text.startsWith("dot://", true) -> "tls://" + text.substringAfter("://")
                "://" !in text -> "tls://$text"
                else -> text
            }
            val uri = runCatching { URI(normalized) }.getOrNull()
            require(uri != null && uri.scheme.equals("tls", true) && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
                "DoH: https://сервер/dns-query; DoT: имя сервера или tls://сервер:853"
            }
            val port = if (uri.port == -1) 853 else uri.port
            require(port in 1..65535) { "Порт должен быть от 1 до 65535" }
            return DnsEndpoint(Kind.DOT, normalized, uri.host.removeSurrounding("[", "]"), port)
        }
    }
}

class DnsProfile(val primary: DnsEndpoint, val fallback: DnsEndpoint?, val bootstrap: List<InetAddress>) {
    companion object {
        fun parse(primary: String, fallback: String, bootstrap: String): DnsProfile {
            val first = DnsEndpoint.parse(primary)
            val second = fallback.trim().takeIf { it.isNotEmpty() }?.let { DnsEndpoint.parse(it) }
            require(second == null || second.kind == DnsEndpoint.Kind.DOH) { "Резервный адрес должен быть HTTPS-ссылкой DoH" }
            val ips = bootstrap.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.map {
                require(it.matches(Regex("[0-9.]+")) || (':' in it && it.matches(Regex("[0-9a-fA-F:]+")))) {
                    "Для начального подключения укажите только IP-адреса"
                }
                val ip = runCatching { InetAddress.getByName(it) }.getOrNull()
                require(ip != null && !ip.isAnyLocalAddress && !ip.isMulticastAddress && !ip.isLoopbackAddress) { "Неверный IP-адрес DNS-сервера" }
                ip
            }
            require(ips.size <= 8) { "Достаточно не более восьми IP-адресов" }
            return DnsProfile(first, second, ips)
        }
    }
}
