package io.github.poporpepopo.smbdocs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Connection settings. The password lives in an EncryptedSharedPreferences file so it is
 * not readable from a plain filesystem dump of the app's data directory.
 */
data class SmbConfigData(
    val host: String,
    val share: String,
    val user: String,
    val password: String,
    val domain: String = "",
) {
    val isComplete: Boolean get() = host.isNotBlank() && share.isNotBlank()
}

object SmbSettings {
    private const val FILE = "smb_settings"
    private const val KEY_HOST = "host"
    private const val KEY_SHARE = "share"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_DOMAIN = "domain"

    @Volatile private var cached: SmbConfigData? = null

    private fun prefs(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): SmbConfigData {
        cached?.let { return it }
        val p = prefs(context)
        val cfg = SmbConfigData(
            host = p.getString(KEY_HOST, "") ?: "",
            share = p.getString(KEY_SHARE, "") ?: "",
            user = p.getString(KEY_USER, "") ?: "",
            password = p.getString(KEY_PASS, "") ?: "",
            domain = p.getString(KEY_DOMAIN, "") ?: "",
        )
        cached = cfg
        return cfg
    }

    fun save(context: Context, cfg: SmbConfigData) {
        prefs(context).edit()
            .putString(KEY_HOST, cfg.host)
            .putString(KEY_SHARE, cfg.share)
            .putString(KEY_USER, cfg.user)
            .putString(KEY_PASS, cfg.password)
            .putString(KEY_DOMAIN, cfg.domain)
            .apply()
        cached = cfg
    }
}
