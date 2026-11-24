package com.assistente.personale.brain

import android.content.SharedPreferences
import com.assistente.personale.memory.ChatMsg
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Errore "parlante" da mostrare all'utente */
class BrainException(message: String) : Exception(message)

/**
 * Il cervello dell'assistente: parla con l'API gratuita di Gemini di Google.
 * Invia la memoria delle conversazioni e riceve le risposte (o le azioni da eseguire).
 */
class AssistantBrain(private val prefs: SharedPreferences) {

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun apiKey(): String {
        val key = prefs.getString("api_key", "")?.trim().orEmpty()
        if (key.isBlank()) throw BrainException(
            "Chiave API di Gemini mancante: apri le Impostazioni e inseriscila (vedi guida)."
        )
        return key
    }

    private fun model(): String =
        prefs.getString("model", "")?.trim().orEmpty().ifBlank { "gemini-2.5-flash" }

    private fun systemPrompt(facts: List<String>, emailContext: String): String {
        val name = prefs.getString("user_name", "")?.trim().orEmpty().ifBlank { "l'utente" }
        val style = prefs.getString("email_style", "")?.trim().orEmpty()
            .ifBlank { "professionale ma amichevole, conciso e gentile" }
        val data = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALY).format(Date())

        val sb = StringBuilder()
        sb.append("Sei \"Assistente Personale\", l'assistente Android personale di ").append(name).append(".\n")
        sb.append("Rispondi SEMPRE in italiano, in modo chiaro, utile e conciso, senza simboli markdown come asterischi o cancelletti.\n")
        sb.append("Data odierna: ").append(data).append(".\n")
        sb.append("Quando scrivi email per conto di ").append(name).append(" usa questo stile: ").append(style).append(".\n")
        sb.append("Cose che devi ricordare su ").append(name).append(":\n")
        if (facts.isEmpty()) sb.append("(nessuna per ora)\n")
        else facts.forEach { sb.append("- ").append(it).append("\n") }
        if (emailContext.isNotBlank()) sb.append(emailContext).append("\n")
        sb.append(
            """
            |Hai a disposizione le seguenti AZIONI. Se la richiesta richiede un'azione, la PRIMA riga della tua risposta deve essere ESATTAMENTE una di queste righe (nient'altro sulla stessa riga); se l'azione prevede un corpo, scrivilo nelle righe successive:
            |AZIONE: LEGGI_EMAIL
            |AZIONE: RIEPILOGO_EMAIL|<numero oppure all>
            |AZIONE: RISPONDI_EMAIL|<numero email>
            |<corpo della risposta da inviare, nelle righe successive>
            |AZIONE: SCRIVI_EMAIL|<destinatario>|<oggetto>
            |<corpo dell'email, nelle righe successive>
            |AZIONE: APRI_APP|<nome dell'app, senza la parola apri>
            |AZIONE: RICORDA|<informazione da memorizzare per sempre>
            |
            |Regole:
            |- I numeri delle email si riferiscono all'elenco "Ultime email ricevute" presente nel contesto; la numero 1 e' la piu' recente.
            |- Se l'utente chiede di leggere o controllare le email: usa LEGGI_EMAIL.
            |- Se chiede un riassunto di una email gia' elencata: RIEPILOGO_EMAIL|<numero>. Per riassumere tutte insieme: RIEPILOGO_EMAIL|all.
            |- Per rispondere a una email gia' elencata: RISPONDI_EMAIL|<numero> seguito dal corpo della risposta.
            |- Per scrivere una nuova email: SCRIVI_EMAIL|destinatario|oggetto seguito dal corpo. Se il destinatario e' solo un nome senza indirizzo, metti comunque il nome: l'app chiedera' conferma all'utente.
            |- Dopo la riga AZIONE puoi aggiungere al massimo una breve frase rivolta all'utente.
            |- Se la richiesta NON richiede nessuna azione, rispondi normalmente e NON scrivere in nessun caso la parola AZIONE.
        """.trimMargin()
        )
        return sb.toString()
    }

    /** Conversazione completa: invia la storia e riceve la risposta */
    fun ask(history: List<ChatMsg>, facts: List<String>, emailContext: String): String {
        val contents = JSONArray()
        var lastRole = ""
        history.forEach { msg ->
            val role = if (msg.role == "assistant") "model" else "user"
            var text = msg.content
            if (msg.role == "system") text = "[Nota di sistema] $text"
            if (role == lastRole) {
                // unisci i messaggi consecutivi dello stesso ruolo
                val prev = contents.getJSONObject(contents.length() - 1)
                val parts = prev.getJSONArray("parts")
                parts.put(parts.getJSONObject(0).put("text", parts.getJSONObject(0).getString("text") + "\n" + text))
            } else {
                contents.put(
                    JSONObject().put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
                lastRole = role
            }
        }
        if (contents.length() == 0) {
            contents.put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "Ciao")))
            )
        }
        return callGemini(contents, systemPrompt(facts, emailContext), 0.6)
    }

    /** Una singola domanda senza memoria (usata per i riassunti e i test) */
    fun oneShot(instruction: String): String {
        val contents = JSONArray().put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", instruction)))
        )
        return callGemini(contents, null, 0.4)
    }

    private fun callGemini(contents: JSONArray, system: String?, temperature: Double): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent?key=${apiKey()}"
        val body = JSONObject().put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", 2048))
        if (system != null) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val req = Request.Builder()
            .url(url)
            .post(RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), body.toString()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                var msg = "Errore HTTP " + resp.code
                try {
                    val err = JSONObject(text).optJSONObject("error")
                    if (err != null) msg = err.optString("message", msg)
                } catch (_: Exception) {}
                if (resp.code == 429) msg = "Quota gratuita momentaneamente esaurita: riprova tra qualche minuto. Dettagli: $msg"
                if (resp.code == 400 && msg.contains("API key", true)) msg = "Chiave API non valida: controlla la chiave nelle Impostazioni. Dettagli: $msg"
                throw BrainException(msg)
            }
            val json = JSONObject(text)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val feedback = json.optJSONObject("promptFeedback")
                val reason = feedback?.optString("blockReason").orEmpty()
                throw BrainException(
                    if (reason.isNotBlank()) "Risposta bloccata dal filtro di sicurezza ($reason)." else "Risposta vuota dal modello."
                )
            }
            val parts = candidates.getJSONObject(0).getJSONObject("content").optJSONArray("parts")
            if (parts == null || parts.length() == 0) throw BrainException("Risposta vuota dal modello.")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.getJSONObject(i).optString("text", "")
                if (t.isNotBlank()) out.append(t)
            }
            val result = out.toString().trim()
            if (result.isBlank()) throw BrainException("Risposta vuota dal modello.")
            return result
        }
    }
}
