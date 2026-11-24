package com.assistente.personale.brain

/**
 * Analizza la risposta del modello AI ed estrae l'azione da eseguire (se presente).
 * L'azione e' la prima riga nel formato "AZIONE: VERBO|argomenti".
 */
sealed class Action {
    object LeggiEmail : Action()
    data class RiepilogoEmail(val num: Int?) : Action()
    data class RispondiEmail(val num: Int, val body: String) : Action()
    data class ScriviEmail(val to: String, val subject: String, val body: String) : Action()
    data class ApriApp(val name: String) : Action()
    data class Ricorda(val fact: String) : Action()
}

object ActionParser {

    data class Result(val visibleText: String, val action: Action?)

    fun parse(text: String): Result {
        val lines = text.lines()
        var actionIdx = -1
        for (i in 0 until minOf(lines.size, 6)) {
            if (lines[i].trim().startsWith("AZIONE:")) {
                actionIdx = i
                break
            }
        }
        if (actionIdx == -1) return Result(text.trim(), null)

        val head = lines[actionIdx].trim().removePrefix("AZIONE:").trim()
        val verb = head.substringBefore("|").trim().uppercase()
        val args = if (head.contains("|")) head.substringAfter("|").trim() else ""
        val restLines = lines.drop(actionIdx + 1).joinToString("\n").trim()
        val consumesBody = verb == "RISPONDI_EMAIL" || verb == "SCRIVI_EMAIL"

        val action: Action? = when (verb) {
            "LEGGI_EMAIL" -> Action.LeggiEmail
            "RIEPILOGO_EMAIL" -> {
                val n = args.trim()
                if (n.isBlank() || n.equals("all", true) || n.equals("tutte", true)) {
                    Action.RiepilogoEmail(null)
                } else {
                    Action.RiepilogoEmail(n.filter { it.isDigit() }.toIntOrNull())
                }
            }
            "RISPONDI_EMAIL" -> Action.RispondiEmail(args.filter { it.isDigit() }.toIntOrNull() ?: 1, restLines)
            "SCRIVI_EMAIL" -> {
                val parts = args.split("|")
                val to = parts.getOrNull(0)?.trim().orEmpty()
                val subj = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "(senza oggetto)" }
                Action.ScriviEmail(to, subj, restLines)
            }
            "APRI_APP" -> {
                val name = args.trim()
                if (name.isBlank()) null else Action.ApriApp(name)
            }
            "RICORDA" -> {
                val fact = args.trim()
                if (fact.isBlank()) null else Action.Ricorda(fact)
            }
            else -> null
        }

        if (action == null) {
            // azione non riconosciuta: mostro il testo senza la riga AZIONE
            val visible = lines.toMutableList().also { it.removeAt(actionIdx) }.joinToString("\n").trim()
            return Result(visible, null)
        }
        val visible = if (consumesBody) {
            lines.take(actionIdx).joinToString("\n").trim()
        } else {
            lines.toMutableList().also { it.removeAt(actionIdx) }.joinToString("\n").trim()
        }
        return Result(visible, action)
    }
}
