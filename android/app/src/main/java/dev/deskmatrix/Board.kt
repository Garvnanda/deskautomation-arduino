package dev.deskmatrix

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny HTTP client for the board's JSON API. */
object Board {
    private val io = Executors.newSingleThreadExecutor()

    fun address(ctx: Context): String =
        ctx.getSharedPreferences("dm", Context.MODE_PRIVATE).getString("address", "192.168.1.106:8080")!!

    fun setAddress(ctx: Context, a: String) =
        ctx.getSharedPreferences("dm", Context.MODE_PRIVATE).edit().putString("address", a.trim()).apply()

    /** Blocking call. Returns the parsed reply, or null if the board is unreachable. */
    fun call(ctx: Context, method: String, path: String, body: JSONObject? = null): JSONObject? = try {
        val c = URL("http://${address(ctx)}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 2000
        c.readTimeout = 3000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        JSONObject(text)
    } catch (e: Exception) {
        null
    }

    /** Fire-and-forget POST, used by the bridge for phone events. */
    fun post(ctx: Context, path: String, body: JSONObject) {
        io.execute { call(ctx, "POST", path, body) }
    }
}
