package com.drazan.echoshow.octoprint.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "printer_display")

/**
 * Where OctoPrint lives, the key it issued to this display and, for an https
 * server with a self-signed certificate, the fingerprint of that certificate.
 */
data class Server(val url: String, val apiKey: String, val name: String, val cert: String? = null)

data class DisplayPrefs(
    /** The three colors everything else on screen is worked out from, as ARGB. */
    val ground: Int = 0xFF000000.toInt(),
    val ink: Int = 0xFFF3F1EC.toInt(),
    val accent: Int = 0xFFFF6B2C.toInt(),
    val filamentId: String = "bone",
    val clock24: Boolean = false,
    /** Minutes without a touch before the screen goes off; 0 keeps it on. */
    val offAfterMin: Int = 5,
    /** What the big panel on the status screen shows: "model" (the file's preview) or "camera". */
    val view: String = "model",
)

class Prefs(private val context: Context) {
    private val url = stringPreferencesKey("server_url")
    private val key = stringPreferencesKey("server_key")
    private val name = stringPreferencesKey("server_name")
    private val cert = stringPreferencesKey("server_cert")
    private val ground = intPreferencesKey("ground")
    private val ink = intPreferencesKey("ink")
    private val accent = intPreferencesKey("accent")
    private val filament = stringPreferencesKey("filament")
    private val clock24 = booleanPreferencesKey("clock_24h")
    // These two are stored under other names than the settings go by. Those are the names
    // choices already made on a display are saved under, and a rename would lose them.
    private val offAfter = intPreferencesKey("dim_after_min")
    private val view = stringPreferencesKey("picture")
    private val plugUrl = stringPreferencesKey("plug_url")

    val server: Flow<Server?> = context.store.data.map { p ->
        val u = p[url]
        val k = p[key]
        if (u.isNullOrEmpty() || k.isNullOrEmpty()) null else Server(u, k, p[name].orEmpty(), p[cert])
    }.distinctUntilChanged()

    val display: Flow<DisplayPrefs> = context.store.data.map { p ->
        DisplayPrefs(
            ground = p[ground] ?: 0xFF000000.toInt(),
            ink = p[ink] ?: 0xFFF3F1EC.toInt(),
            accent = p[accent] ?: 0xFFFF6B2C.toInt(),
            filamentId = p[filament] ?: "bone",
            clock24 = p[clock24] ?: false,
            offAfterMin = p[offAfter] ?: 5,
            view = p[view] ?: "model",
        )
    }.distinctUntilChanged()

    /** Where the smart plug that powers the printer and the Pi lives, if there is one. */
    val plug: Flow<String?> = context.store.data.map { it[plugUrl]?.takeIf(String::isNotEmpty) }.distinctUntilChanged()

    suspend fun saveServer(s: Server) = context.store.edit {
        it[url] = s.url
        it[key] = s.apiKey
        it[name] = s.name
        if (s.cert != null) it[cert] = s.cert else it.remove(cert)
    }

    suspend fun forgetServer() = context.store.edit {
        it.remove(url)
        it.remove(key)
        it.remove(name)
        it.remove(cert)
    }

    suspend fun setGround(argb: Int) = context.store.edit { it[ground] = argb }
    suspend fun setInk(argb: Int) = context.store.edit { it[ink] = argb }
    suspend fun setAccent(argb: Int) = context.store.edit { it[accent] = argb }
    suspend fun setFilament(id: String) = context.store.edit { it[filament] = id }
    suspend fun setClock24(v: Boolean) = context.store.edit { it[clock24] = v }
    suspend fun setOffAfter(min: Int) = context.store.edit { it[offAfter] = min }
    suspend fun setView(v: String) = context.store.edit { it[view] = v }
    suspend fun setPlug(url: String?) = context.store.edit { if (url != null) it[plugUrl] = url else it.remove(plugUrl) }
}
