package ru.privetdns.zapret.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom

object DnsWire {
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt().and(255) shl 8) or b[p + 1].toInt().and(255)
    private fun question(b: ByteArray): String {
        require(b.size >= 12 && u16(b, 4) == 1)
        var p = 12
        var tail = -1
        val labels = ArrayList<String>()
        repeat(128) {
            require(p < b.size)
            val n = b[p++].toInt().and(255)
            when {
                n == 0 -> {
                    val end = if (tail < 0) p else tail
                    require(end + 4 <= b.size)
                    return labels.joinToString(".").lowercase(java.util.Locale.ROOT) + ":${u16(b, end)}:${u16(b, end + 2)}"
                }
                n and 192 == 192 -> {
                    require(p < b.size)
                    if (tail < 0) tail = p + 1
                    p = ((n and 63) shl 8) or b[p].toInt().and(255)
                }
                else -> {
                    require(n <= 63 && p + n <= b.size)
                    labels.add(b.copyOfRange(p, p + n).toString(Charsets.ISO_8859_1)); p += n
                }
            }
        }
        error("DNS name loop")
    }
    fun checkQuery(query: ByteArray) {
        require(query.size in 12..4096 && query[2].toInt() and 128 == 0 && query[2].toInt() and 120 == 0)
        question(query)
    }
    fun checkResponse(query: ByteArray, response: ByteArray) {
        require(response.size in 12..65535 && response[2].toInt() and 128 != 0)
        require(query[0] == response[0] && query[1] == response[1] && question(query) == question(response))
    }
    fun failure(query: ByteArray): ByteArray {
        // Preserve just the question, discard EDNS/additional data and clear their counts.
        var end = 12
        while (end < query.size && query[end].toInt() != 0) {
            val len = query[end].toInt().and(255)
            if (len > 63 || end + len + 1 >= query.size) return byteArrayOf()
            end += len + 1
        }
        if (end + 5 > query.size) return byteArrayOf()
        return query.copyOf(end + 5).also {
            it[2] = (query[2].toInt().and(1) or 128).toByte(); it[3] = 130.toByte()
            for (i in 6..11) it[i] = 0
        }
    }
    fun query(host: String, type: Int = 1): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(SecureRandom().nextInt(65536)); out.writeShort(0x100)
            out.writeShort(1); repeat(3) { out.writeShort(0) }
            host.split('.').forEach { label ->
                val b = label.toByteArray(Charsets.US_ASCII); require(b.size in 1..63)
                out.writeByte(b.size); out.write(b)
            }
            out.writeByte(0); out.writeShort(type); out.writeShort(1)
        }
        return bytes.toByteArray()
    }
    fun hasAnswer(response: ByteArray) = response.size >= 12 && response[3].toInt() and 15 == 0 && u16(response, 6) > 0
}
