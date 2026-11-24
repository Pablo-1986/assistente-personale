package com.assistente.personale

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.assistente.personale.brain.Action
import com.assistente.personale.brain.ActionParser
import com.assistente.personale.brain.AssistantBrain
import com.assistente.personale.commands.CommandRouter
import com.assistente.personale.databinding.ActivityMainBinding
import com.assistente.personale.email.EmailClient
import com.assistente.personale.email.EmailSummary
import com.assistente.personale.memory.MemoryStore
import com.assistente.personale.update.UpdateChecker
import com.assistente.personale.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var memory: MemoryStore
    private lateinit var brain: AssistantBrain

    private val lastEmails = mutableListOf<EmailSummary>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("assistente_prefs", MODE_PRIVATE)
        memory = MemoryStore(this)
        brain = AssistantBrain(prefs)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSend.setOnClickListener { onSendClicked() }
        binding.btnMic.setOnClickListener { onMicClicked() }

        initTts()
        askPermissions()
        greetIfFirstTime()
        checkUpdatesSilently()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    // ---------- Voce ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.language = Locale.ITALIAN
                    ttsReady = true
                } catch (_: Exception) {
                    ttsReady = false
                }
            }
        }
    }

    private fun speak(text: String) {
        if (!prefs.getBoolean("voice_on", true) || !ttsReady) return
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        tts?.speak(clean.take(600), TextToSpeech.QUEUE_ADD, null, "risposta")
    }

    private fun onMicClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addSystemBubble("Il riconoscimento vocale non è disponibile su questo telefono.")
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    binding.thinking.text = "Ascolto... (parla ora)"
                    binding.thinking.visibility = View.VISIBLE
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    listening = false
                    binding.thinking.text = "Sto interpretando..."
                }
                override fun onError(error: Int) {
                    listening = false
                    binding.thinking.visibility = View.GONE
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Non ho capito, riprova."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Non ho sentito nulla: riprova."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Serve il permesso del microfono."
                        else -> "Errore vocale (codice $error). Riprova."
                    }
                    addSystemBubble(msg)
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    binding.thinking.visibility = View.GONE
                    val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!txt.isNullOrBlank()) {
                        binding.input.setText(txt)
                        onSendClicked()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.ITALIAN.toString())
        }
        rec.startListening(intent)
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    // ---------- Invio messaggi ----------

    private fun onSendClicked() {
        val text = binding.input.text.toString().trim()
        if (text.isBlank()) return
        binding.input.setText("")
        addUserBubble(text)
        memory.add("user", text)

        val handled = CommandRouter.tryHandle(
            text, this,
            onResult = { msg ->
                addSystemBubble(msg)
                memory.add("system", msg)
            },
            onRemember = { fact -> handleRemember(fact) }
        )
        if (handled) return

        binding.thinking.text = "Sto pensando..."
        binding.thinking.visibility = View.VISIBLE
        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    brain.ask(memory.lastN(20), memory.facts(), emailContext())
                }
            } catch (e: Exception) {
                "ERROR::" + (e.message ?: "errore di rete")
            }
            binding.thinking.visibility = View.GONE
            if (reply.startsWith("ERROR::")) {
                val msg = "Non riesco a contattare l'assistente AI. Controlla la connessione e le impostazioni. Dettaglio: " + reply.removePrefix("ERROR::")
                addSystemBubble(msg)
                memory.add("system", msg)
            } else {
                handleBrainReply(reply)
            }
        }
    }

    private fun handleBrainReply(reply: String) {
        val parsed = ActionParser.parse(reply)
        val visible = parsed.visibleText
        if (visible.isNotBlank()) {
            addAssistantBubble(visible)
            memory.add("assistant", visible)
            speak(visible)
        }
        when (val action = parsed.action) {
            is Action.LeggiEmail -> runEmailFetch()
            is Action.RiepilogoEmail -> runEmailSummary(action.num)
            is Action.RispondiEmail -> runReplyEmail(action.num, action.body)
            is Action.ScriviEmail -> runComposeEmail(action.to, action.subject, action.body)
            is Action.ApriApp -> {
                val res = CommandRouter.openApp(action.name, this)
                addSystemBubble(res.message)
            }
            is Action.Ricorda -> handleRemember(action.fact)
            null -> {}
        }
    }

    private fun handleRemember(fact: String) {
        if (fact.isBlank()) return
        val ok = memory.addFact(fact)
        val msg = if (ok) "Registrato: $fact" else "Lo sapevo già, non l'ho duplicato."
        addSystemBubble(msg)
        memory.add("system", msg)
        speak("Fatto.")
    }

    // ---------- Email ----------

    private fun emailContext(): String {
        if (lastEmails.isEmpty()) return ""
        val sb = StringBuilder("Ultime email ricevute (usa i numeri per riassumere o rispondere, la 1 è la più recente):\n")
        lastEmails.forEachIndexed { idx, e ->
            sb.append(idx + 1).append(". Da: ").append(e.from)
                .append(" | Oggetto: ").append(e.subject)
                .append(" | Data: ").append(e.date)
                .append(" | Inizio: ").append(e.snippet).append("\n")
        }
        return sb.toString()
    }

    private fun gmailCredentials(): Pair<String, String> {
        val addr = prefs.getString("gmail_addr", "")?.trim().orEmpty()
        val pwd = prefs.getString("gmail_pass", "")?.trim().orEmpty()
        return addr to pwd
    }

    private fun runEmailFetch() {
        val (addr, pwd) = gmailCredentials()
        if (addr.isBlank() || pwd.isBlank()) {
            val msg = "Per leggere le email devi prima configurare indirizzo Gmail e password per le app nelle Impostazioni (icona ingranaggio)."
            addSystemBubble(msg)
            memory.add("system", msg)
            return
        }
        binding.thinking.text = "Sto leggendo le email..."
        binding.thinking.visibility = View.VISIBLE
        lifecycleScope.launch {
            var emails: List<EmailSummary>? = null
            var err: String? = null
            try {
                emails = withContext(Dispatchers.IO) { EmailClient(addr, pwd).fetchLatest(10) }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            binding.thinking.visibility = View.GONE
            if (emails == null) {
                val msg = "Non riesco a leggere le email. Controlla indirizzo e password nelle Impostazioni. Dettaglio: $err"
                addSystemBubble(msg)
                memory.add("system", msg)
                return@launch
            }
            lastEmails.clear()
            lastEmails.addAll(emails)
            if (emails.isEmpty()) {
                val msg = "La casella è vuota: nessuna email da mostrare."
                addSystemBubble(msg)
                memory.add("system", msg)
            } else {
                val sb = StringBuilder("Ecco le ultime ").append(emails.size).append(" email:\n\n")
                emails.forEachIndexed { idx, e ->
                    sb.append(idx + 1).append(". ").append(e.from)
                        .append(" - ").append(e.subject)
                        .append(" (").append(e.date).append(")\n")
                }
                sb.append("\nDimmi ad esempio: \"riassumi l'email 2\" oppure \"rispondi alla email 1\".")
                addSystemBubble(sb.toString())
                memory.add("system", "Ho mostrato all'utente le ultime " + emails.size + " email (numeri 1-" + emails.size + ", la 1 è la più recente).\n" + emailContext())
                speak("Ho trovato " + emails.size + " email.")
            }
        }
    }

    private fun runEmailSummary(num: Int?) {
        if (lastEmails.isEmpty()) {
            addSystemBubble("Devo prima leggere le email: dimmi \"leggi le email\".")
            return
        }
        val targets: List<EmailSummary> = when {
            num == null -> lastEmails.toList()
            num in 1..lastEmails.size -> listOf(lastEmails[num - 1])
            else -> {
                addSystemBubble("Non c'è l'email numero $num: al momento le email vanno da 1 a " + lastEmails.size + ".")
                return
            }
        }
        val (addr, pwd) = gmailCredentials()
        binding.thinking.text = "Sto riassumendo..."
        binding.thinking.visibility = View.VISIBLE
        lifecycleScope.launch {
            var text: String? = null
            var err: String? = null
            try {
                text = withContext(Dispatchers.IO) {
                    val client = EmailClient(addr, pwd)
                    val bodies = targets.map { s ->
                        "Email da " + s.from + " - oggetto: " + s.subject + " - data: " + s.date + "\n\n" + client.readBody(s.seq)
                    }
                    val joined = bodies.joinToString("\n\n---\n\n")
                    val instruction = "Riassumi in italiano il seguente contenuto email in 3-5 punti chiari e brevi. " +
                        "Se c'è una richiesta o una scadenza importante, evidenziala alla fine con una riga che inizia con \"Da fare:\".\n\n" + joined
                    brain.oneShot(instruction)
                }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            binding.thinking.visibility = View.GONE
            if (text == null) {
                addSystemBubble("Non riesco a leggere l'email per il riassunto. Dettaglio: $err")
            } else {
                addAssistantBubble(text)
                memory.add("assistant", text)
                speak(text)
            }
        }
    }

    private fun runReplyEmail(num: Int, body: String) {
        if (lastEmails.isEmpty() || num < 1 || num > lastEmails.size) {
            addSystemBubble("Non so a quale email rispondere (numero $num). Dimmi prima \"leggi le email\".")
            return
        }
        val target = lastEmails[num - 1]
        val to = extractAddress(target.from)
        val subj = if (target.subject.startsWith("Re:", true)) target.subject else "Re: " + target.subject
        confirmAndSend(to, subj, body)
    }

    private fun runComposeEmail(to: String, subject: String, body: String) {
        confirmAndSend(to, subject, body)
    }

    private fun confirmAndSend(toIn: String, subject: String, body: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val toEdit = EditText(this).apply { setText(toIn); hint = "Destinatario (indirizzo email)" }
        val subjEdit = EditText(this).apply { setText(subject); hint = "Oggetto" }
        val bodyEdit = EditText(this).apply {
            setText(body)
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        container.addView(toEdit)
        container.addView(subjEdit)
        container.addView(bodyEdit)

        AlertDialog.Builder(this)
            .setTitle("Conferma invio email")
            .setView(container)
            .setPositiveButton("Invia") { _, _ ->
                doSend(toEdit.text.toString().trim(), subjEdit.text.toString(), bodyEdit.text.toString())
            }
            .setNegativeButton("Annulla") { d, _ ->
                d.dismiss()
                addSystemBubble("Invio annullato.")
            }
            .show()
    }

    private fun doSend(to: String, subject: String, body: String) {
        val (addr, pwd) = gmailCredentials()
        if (to.isBlank() || !to.contains("@")) {
            addSystemBubble("L'indirizzo destinatario non è valido: \"$to\". Correggilo e riprova.")
            return
        }
        if (addr.isBlank() || pwd.isBlank()) {
            addSystemBubble("Configura prima l'email nelle Impostazioni.")
            return
        }
        binding.thinking.text = "Sto inviando l'email..."
        binding.thinking.visibility = View.VISIBLE
        lifecycleScope.launch {
            var err: String? = null
            try {
                withContext(Dispatchers.IO) { EmailClient(addr, pwd).send(to, subject, body) }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            binding.thinking.visibility = View.GONE
            if (err != null) {
                addSystemBubble("Invio non riuscito: $err")
            } else {
                val msg = "Email inviata a $to con oggetto \"$subject\"."
                addSystemBubble(msg)
                memory.add("system", msg)
                speak("Email inviata.")
            }
        }
    }

    private fun extractAddress(from: String): String {
        val m = Regex("<([^>]+)>").find(from)
        return m?.groupValues?.get(1) ?: from.trim()
    }

    // ---------- Aggiornamenti ----------

    private fun checkUpdatesSilently() {
        val repo = prefs.getString("github_repo", "")?.trim().orEmpty()
        if (!repo.contains("/") || repo.contains("TUO-UTENTE") || repo.contains("utente/repo")) return
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { UpdateChecker(repo).checkLatest() }
                if (UpdateChecker(repo).isNewer(info)) proposeUpdate(info)
            } catch (_: Exception) {
                // controllo silenzioso: ignora gli errori
            }
        }
    }

    private fun proposeUpdate(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("Aggiornamento disponibile")
            .setMessage("È disponibile la nuova versione " + info.versionName + " (build " + info.versionCode + "). Scaricare e installare adesso?")
            .setPositiveButton("Aggiorna") { _, _ -> downloadAndInstall(info) }
            .setNegativeButton("Più tardi", null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        val repo = prefs.getString("github_repo", "")?.trim().orEmpty()
        binding.thinking.text = "Scarico l'aggiornamento..."
        binding.thinking.visibility = View.VISIBLE
        lifecycleScope.launch {
            var apk: File? = null
            var err: String? = null
            try {
                apk = withContext(Dispatchers.IO) {
                    val u = UpdateChecker(repo)
                    u.downloadApk(applicationContext, info)
                }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            binding.thinking.visibility = View.GONE
            if (apk == null) {
                addSystemBubble("Download dell'aggiornamento non riuscito: $err")
            } else {
                try {
                    UpdateChecker.installApk(applicationContext, apk)
                    addSystemBubble("Aggiornamento scaricato: conferma l'installazione nella finestra di Android.")
                } catch (e: Exception) {
                    addSystemBubble("Installazione non avviata: " + (e.message ?: "errore") + ". Se Android lo richiede, consenti l'installazione per questa app.")
                }
            }
        }
    }

    // ---------- Chat (interfaccia) ----------

    private fun greetIfFirstTime() {
        val apiKey = prefs.getString("api_key", "")?.trim().orEmpty()
        if (apiKey.isBlank()) {
            addSystemBubble(
                "Ciao! Per iniziare, apri le Impostazioni (icona ingranaggio in alto) e inserisci:\n\n" +
                    "1) La chiave API gratuita di Gemini\n" +
                    "2) Il tuo indirizzo Gmail e la \"password per le app\"\n\n" +
                    "Poi torna qui e dimmi ad esempio: \"leggi le email\".\n\n" +
                    "Scrivi \"aiuto\" per la lista completa dei comandi."
            )
        } else if (memory.lastN(1).isEmpty()) {
            addSystemBubble(
                "Ciao! Sono pronto. Prova: \"leggi le email\", \"apri fotocamera\", " +
                    "\"scrivi una email a...\" oppure chiedimi qualsiasi cosa."
            )
        }
    }

    private fun addBubble(textMsg: String, backgroundRes: Int, textColorRes: Int, alignEnd: Boolean, small: Boolean) {
        val tv = TextView(this)
        tv.text = textMsg
        tv.textSize = if (small) 13f else 15f
        tv.setTextColor(ContextCompat.getColor(this, textColorRes))
        tv.background = ContextCompat.getDrawable(this, backgroundRes)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        tv.maxWidth = dp(300)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        lp.gravity = if (alignEnd) Gravity.END else Gravity.START
        tv.layoutParams = lp
        binding.chatContainer.addView(tv)
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addUserBubble(t: String) = addBubble(t, R.drawable.user_bubble, R.color.on_primary, true, false)
    private fun addAssistantBubble(t: String) = addBubble(t, R.drawable.assistant_bubble, R.color.text_main, false, false)
    private fun addSystemBubble(t: String) = addBubble(t, R.drawable.system_bubble, R.color.text_secondary, false, true)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
