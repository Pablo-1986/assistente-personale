package com.assistente.personale.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.assistente.personale.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Informazioni sull'ultima versione pubblicata su GitHub */
data class UpdateInfo(val versionName: String, val versionCode: Int, val apkUrl: String)

/**
 * Controllo aggiornamenti: legge l'ultima "release" dal repository GitHub,
 * scarica il nuovo APK e avvia l'installazione.
 */
class UpdateChecker(private val repo: String) {

    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "assistente-personale-app")
        .build()

    fun checkLatest(): UpdateInfo {
        val url = "https://api.github.com/repos/$repo/releases/latest"
        client.newCall(request(url)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val extra = if (resp.code == 404) " (controlla \"utente/repo\" nelle Impostazioni e assicurati che il repository sia pubblico)" else ""
                throw Exception("GitHub ha risposto con errore " + resp.code + extra)
            }
            val json = JSONObject(text)
            val tag = json.optString("tag_name", "")
            val body = json.optString("body", "")
            val vc = Regex("versionCode:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: tag.removePrefix("v").filter { it.isDigit() }.toIntOrNull() ?: 0
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) throw Exception("Nell'ultima release non e' presente un file APK.")
            return UpdateInfo(tag, vc, apkUrl)
        }
    }

    fun isNewer(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    fun downloadApk(ctx: Context, info: UpdateInfo): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "aggiornamento.apk")
        client.newCall(request(info.apkUrl)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Download non riuscito (errore " + resp.code + ").")
            val src = resp.body?.byteStream() ?: throw Exception("Download non riuscito.")
            src.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    companion object {
        /** Avvia l'installazione di un APK scaricato (Android chiede la conferma all'utente) */
        fun installApk(ctx: Context, apk: File) {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        }
    }
}
