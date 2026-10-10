package com.drazan.echoshow.octoprint.power

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// A plug on the same network answers at once or not at all.
private val PlugClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .build()

/**
 * A smart plug running Tasmota, spoken to through its web command page. The
 * printer and the Pi are powered through it, so off here means off at the wall.
 */
class Plug(val base: HttpUrl) {

    /** The address as someone would type it. */
    val address: String get() = base.toString().substringAfter("://").trimEnd('/')

    /** Whether the socket is live. Throws [IOException] when nothing answers as Tasmota. */
    suspend fun isOn(): Boolean = command("Power")

    /** Switches the socket and gives back the state the plug says it is now in. */
    suspend fun switch(on: Boolean): Boolean = command(if (on) "Power On" else "Power Off")

    private suspend fun command(cmnd: String): Boolean = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment("cm").addQueryParameter("cmnd", cmnd).build()
        PlugClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val reply = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
            // {"POWER":"ON"}, or "POWER1" on a strip with several sockets
            val key = reply?.keys()?.asSequence()?.firstOrNull { it.startsWith("POWER") }
                ?: throw IOException("Not a Tasmota plug")
            reply.optString(key).equals("ON", ignoreCase = true)
        }
    }
}
