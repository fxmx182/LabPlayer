package com.mauricio.libertyx.smb

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mauricio.libertyx.core.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Um servidor SMB salvo.
 *
 * A senha **não** mora aqui. Este objeto vai para as preferências comuns, que
 * saem em texto claro no backup do aparelho; a senha vai para o arquivo
 * cifrado, referenciada pelo [id] — o equivalente do Keychain do iOS.
 */
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 445,
    val username: String = "",
    val isGuest: Boolean = false,
) {
    val displayHost: String get() = if (port == 445) host else "$host:$port"
}

class SmbServerStore private constructor(context: Context) {

    private val prefs = Prefs.get(context)
    private val secret: SharedPreferences = abrirCofre(context)

    private val _servers = mutableListOf<SmbServer>()
    val servers: List<SmbServer> get() = _servers.toList()

    init {
        runCatching {
            val raiz = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until raiz.length()) {
                val o = raiz.getJSONObject(i)
                _servers += SmbServer(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", 445),
                    username = o.optString("username"),
                    isGuest = o.optBoolean("guest"),
                )
            }
        }
    }

    fun save(server: SmbServer, password: String?) {
        val i = _servers.indexOfFirst { it.id == server.id }
        if (i >= 0) _servers[i] = server else _servers += server
        if (password != null) secret.edit().putString(server.id, password).apply()
        persist()
    }

    fun remove(server: SmbServer) {
        _servers.removeAll { it.id == server.id }
        secret.edit().remove(server.id).apply()
        persist()
    }

    fun password(server: SmbServer): String? =
        if (server.isGuest) null else secret.getString(server.id, null)

    fun byId(id: String): SmbServer? = _servers.firstOrNull { it.id == id }

    private fun persist() {
        val raiz = JSONArray()
        for (s in _servers) {
            raiz.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("host", s.host)
                put("port", s.port); put("username", s.username); put("guest", s.isGuest)
            })
        }
        prefs.edit().putString(KEY, raiz.toString()).apply()
    }

    /**
     * O cofre de senhas, com queda para as preferências comuns.
     *
     * A cifra depende do chaveiro do sistema, e há aparelho — ROM alternativa,
     * emulador, usuário secundário — em que ele simplesmente falha ao abrir.
     * Recusar-se a funcionar ali deixaria o app inútil por causa do modo de
     * guardar a senha; a queda é anunciada no README e não silenciosa.
     */
    private fun abrirCofre(context: Context): SharedPreferences = runCatching {
        val chave = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "libertyx.secrets",
            chave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.applicationContext.getSharedPreferences("libertyx.secrets.plain", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY = "libertyx.smbServers"

        @Volatile private var instance: SmbServerStore? = null

        fun get(context: Context): SmbServerStore =
            instance ?: synchronized(this) {
                instance ?: SmbServerStore(context.applicationContext).also { instance = it }
            }
    }
}
