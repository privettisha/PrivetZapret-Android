package ru.privetdns.zapret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ru.privetdns.zapret.net.DnsProfile

class Settings(
    val dns: String = "https://cloudflare-dns.com/dns-query",
    val fallback: String = "",
    val bootstrap: String = "",
    val dnsOnly: Boolean = false,
    val strategy: String = "auto",
    val blockQuic: Boolean = false,
    val desktopQuic: Boolean = true
) {
    fun profile() = DnsProfile.parse(dns, fallback, bootstrap)
    companion object {
        private const val ALIAS = "privetzapret.profiles.v1"
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        fun load(context: Context): Settings {
            val file = java.io.File(context.filesDir, "profile.bin")
            if (!file.exists()) return Settings()
            val bytes = file.readBytes(); require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            // v1 enabled QUIC blocking by default. That broke video and Telegram media, so
            // old profiles migrate to UDP enabled while v2 still respects an explicit choice.
            val blockQuic = if (data.optInt("schema", 1) >= 2) data.optBoolean("blockQuic", false) else false
            return Settings(data.getString("dns"), data.optString("fallback"), data.optString("bootstrap"),
                data.optBoolean("dnsOnly"), data.optString("strategy", "auto"), blockQuic,
                // Older builds saved the experimental switch as false by default. Move
                // those profiles to a real QUIC Initial; v4 preserves later user choice.
                if (data.optInt("schema", 1) >= 4) data.optBoolean("desktopQuic", true) else true)
        }
        fun save(context: Context, settings: Settings) {
            settings.profile()
            val data = JSONObject().put("dns", settings.dns).put("fallback", settings.fallback)
                .put("bootstrap", settings.bootstrap).put("dnsOnly", settings.dnsOnly)
                .put("strategy", settings.strategy).put("blockQuic", settings.blockQuic)
                .put("desktopQuic", settings.desktopQuic).put("schema", 4)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = cipher.doFinal(data.toString().toByteArray())
            val file = android.util.AtomicFile(java.io.File(context.filesDir, "profile.bin"))
            val stream = file.startWrite()
            try { stream.write(cipher.iv + bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
}
