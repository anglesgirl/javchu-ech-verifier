package com.liar.han1meplus

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * JNI 类名必须保持 com.liar.han1meplus.EchHttpClient，与已编译 .so 一致。
 */
@Keep
object EchHttpClient {
    private const val maxLogEntries = 100
    private val logEntries = ArrayDeque<String>()

    @Volatile var isLoaded = false
        private set

    fun init(context: Context) {
        if (isLoaded) return
        // 兼容两种 asset 路径：han1meplus/arm64-v8a/ 和 lib/arm64-v8a/
        val abis = Build.SUPPORTED_ABIS
        var lastErr: Throwable? = null
        for (abi in abis) {
            for (assetPath in listOf("han1meplus/$abi/libhan1me_ech.so", "lib/$abi/libhan1me_ech.so")) {
                try {
                    val soFile = File(context.filesDir, "han1me_ech_${abi}.so")
                    context.assets.open(assetPath).use { input ->
                        soFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    System.load(soFile.absolutePath)
                    isLoaded = true
                    addLog("Loaded native library $abi from $assetPath")
                    return
                } catch (e: Throwable) { lastErr = e }
            }
        }
        addLog("Native library load failed: ${lastErr?.message}")
    }

    external fun request(method: String, url: String, headers: Array<String>, body: ByteArray?, dohUrl: String, dohResolve: String): String

    fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?, dohUrl: String, dohResolve: String): EchResponse {
        addLog("$method $url")
        val raw = request(method, url, headers.map { "${it.key}: ${it.value}" }.toTypedArray(), body, dohUrl, dohResolve)
        val response = JSONObject(raw)
        val responseHeaders = buildMap<String, List<String>> {
            response.getJSONArray("headers").forEachString { line ->
                val sep = line.indexOf('\t')
                if (sep > 0) {
                    val name = line.substring(0, sep)
                    put(name, get(name).orEmpty() + line.substring(sep + 1))
                }
            }
        }
        response.optJSONArray("echLogs")?.forEachString(::addLog)
        return EchResponse(
            statusCode = response.getInt("statusCode"),
            body = Base64.decode(response.getString("body"), Base64.DEFAULT),
            url = response.getString("url"),
            headers = responseHeaders,
            echStatus = response.optString("echStatus", "unavailable"),
        ).also { addLog("${it.statusCode} ${it.echStatus} ${it.url}") }
    }

    fun logs(): List<String> = synchronized(logEntries) { logEntries.toList().asReversed() }
    fun clearLogs() = synchronized(logEntries) { logEntries.clear() }
    fun addLog(message: String) {
        val entry = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $message"
        synchronized(logEntries) {
            while (logEntries.size >= maxLogEntries) logEntries.removeFirst()
            logEntries.addLast(entry)
        }
    }
}

data class EchResponse(val statusCode: Int, val body: ByteArray, val url: String, val headers: Map<String, List<String>>, val echStatus: String)

private inline fun JSONArray.forEachString(action: (String) -> Unit) {
    for (i in 0 until length()) action(getString(i))
}
