package com.assistente.personale.commands

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings

/**
 * Comandi che l'assistente esegue direttamente sul telefono, senza chiedere all'AI:
 * "apri fotocamera", "apri gmail", "aiuto", "ricorda che...", ecc.
 */
object CommandRouter {

    private val packages = mapOf(
        "gmail" to "com.google.android.gm",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "youtube" to "com.google.android.youtube",
        "telegram" to "org.telegram.messenger",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "calendario" to "com.google.android.calendar",
        "contatti" to "com.google.android.contacts",
        "play store" to "com.android.vending",
        "spotify" to "com.spotify.music"
    )

    private val helpText = StringBuilder().apply {
        append("Ecco cosa so fare gia' da subito:\n\n")
        append("Email (dopo la configurazione nelle Impostazioni):\n")
        append("- \"leggi le email\"\n")
        append("- \"riassumi l'email 2\"\n")
        append("- \"rispondi alla email 1 dicendo che ci saro'\n")
        append("- \"scrivi una email a mario.rossi@gmail.com con oggetto Pranzo\"\n\n")
        append("App del telefono:\n")
        append("- \"apri fotocamera\", \"apri gmail\", \"apri whatsapp\", \"apri instagram\",\n")
        append("  \"apri youtube\" o \"apri\" qualsiasi app installata\n\n")
        append("Memoria:\n")
        append("- \"ricorda che l'email di Mario e' mario.rossi@gmail.com\"\n\n")
        append("Domande generiche: chiedimi qualsiasi cosa, tengo conto di cosa abbiamo detto prima.\n\n")
        append("Aggiornamenti: trovi il pulsante nelle Impostazioni.")
    }.toString()

    data class Result(val message: String, val launched: Boolean)

    /**
     * Prova a eseguire il testo come comando locale.
     * Restituisce true se il comando e' stato riconosciuto e gestito.
     */
    fun tryHandle(
        text: String,
        ctx: Context,
        onResult: (String) -> Unit,
        onRemember: (String) -> Unit
    ): Boolean {
        val t = text.trim().lowercase()

        if (t == "aiuto" || t == "help" || t.contains("cosa sai fare") || t.contains("come funzioni")) {
            onResult(helpText)
            return true
        }

        if (t.startsWith("ricorda che ") || t == "ricorda che") {
            val fact = text.trim().substringAfter("ricorda che").trim()
            if (fact.isNotBlank()) onRemember(fact)
            return true
        }

        val regex = Regex("^(apri|lancia|avvia)\\s+(la\\s+|il\\s+|l')?(.+)$")
        val m = regex.find(t)
        if (m != null) {
            val target = m.groupValues[3].trim().removeSuffix(".").trim()
            val res = openApp(target, ctx)
            onResult(res.message)
            return true
        }
        return false
    }

    /** Apre un'app per nome. Restituisce l'esito. */
    fun openApp(query: String, ctx: Context): Result {
        val q = query.trim().lowercase()

        if (q.contains("fotocamera") || q.contains("camera") || q == "foto") {
            return launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Fotocamera aperta.", ctx)
        }
        if (q.contains("impostazioni") || q.contains("configurazione")) {
            return launch(Intent(Settings.ACTION_SETTINGS), "Impostazioni aperte.", ctx)
        }

        val pkg = packages.entries.firstOrNull { q == it.key || q.contains(it.key) || it.key.contains(q) }?.value
        if (pkg != null) {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                return launch(i, "Fatto: ho aperto \"$query\".", ctx)
            }
        }

        // ricerca per nome fra le app installate
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)
        val match = activities.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(q)
        }
        if (match != null) {
            val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (i != null) {
                return launch(i, "Fatto: ho aperto " + match.loadLabel(pm) + ".", ctx)
            }
        }
        return Result("Non trovo un'app chiamata \"$query\" sul telefono.", false)
    }

    private fun launch(intent: Intent, okMessage: String, ctx: Context): Result {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Result(okMessage, true)
        } catch (e: Exception) {
            Result("Non riesco ad aprire l'app: " + (e.message ?: "errore"), false)
        }
    }
}
