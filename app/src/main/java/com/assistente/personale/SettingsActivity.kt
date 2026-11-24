package com.assistente.personale

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.assistente.personale.brain.AssistantBrain
import com.assistente.personale.databinding.ActivitySettingsBinding
import com.assistente.personale.email.EmailClient
import com.assistente.personale.memory.MemoryStore
import com.assistente.personale.update.UpdateChecker
import com.assistente.personale.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("assistente_prefs", MODE_PRIVATE)
        binding.apiKey.setText(prefs.getString("api_key", ""))
        binding.model.setText(prefs.getString("model", "gemini-2.5-flash"))
        binding.gmailAddr.setText(prefs.getString("gmail_addr", ""))
        binding.gmailPass.setText(prefs.getString("gmail_pass", ""))
        binding.userName.setText(prefs.getString("user_name", ""))
        binding.emailStyle.setText(prefs.getString("email_style", ""))
        binding.voiceOn.isChecked = prefs.getBoolean("voice_on", true)
        binding.githubRepo.setText(prefs.getString("github_repo", ""))

        binding.btnSave.setOnClickListener {
            prefs.edit()
                .putString("api_key", binding.apiKey.text.toString().trim())
                .putString("model", binding.model.text.toString().trim().ifBlank { "gemini-2.5-flash" })
                .putString("gmail_addr", binding.gmailAddr.text.toString().trim())
                .putString("gmail_pass", binding.gmailPass.text.toString().trim())
                .putString("user_name", binding.userName.text.toString().trim())
                .putString("email_style", binding.emailStyle.text.toString().trim())
                .putBoolean("voice_on", binding.voiceOn.isChecked)
                .putString("github_repo", binding.githubRepo.text.toString().trim())
                .apply()
            Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnTestAi.setOnClickListener { testAi() }
        binding.btnTestEmail.setOnClickListener { testEmail() }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnClearMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cancellare la memoria?")
                .setMessage("Verranno eliminate tutte le conversazioni e le cose che hai chiesto di ricordare.")
                .setPositiveButton("Cancella") { _, _ ->
                    MemoryStore(this).clearAll()
                    Toast.makeText(this, "Memoria cancellata", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun testAi() {
        val prefs = getSharedPreferences("assistente_prefs", MODE_PRIVATE)
        Toast.makeText(this, "Provo la connessione...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var err: String? = null
            try {
                withContext(Dispatchers.IO) { AssistantBrain(prefs).oneShot("Rispondi con la sola parola: OK") }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            val msg = if (err == null) "AI collegata correttamente" else "Errore AI: $err"
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun testEmail() {
        val prefs = getSharedPreferences("assistente_prefs", MODE_PRIVATE)
        val addr = binding.gmailAddr.text.toString().trim()
        val pwd = binding.gmailPass.text.toString().trim()
        if (addr.isBlank() || pwd.isBlank()) {
            Toast.makeText(this, "Inserisci prima indirizzo e password per le app", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Provo la connessione...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var err: String? = null
            var found = -1
            try {
                found = withContext(Dispatchers.IO) {
                    val emails = EmailClient(addr, pwd).fetchLatest(1)
                    emails.size
                }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            val msg = if (err == null) "Connessione email OK" else "Errore email: $err"
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkUpdate() {
        val repo = binding.githubRepo.text.toString().trim()
        if (!repo.contains("/") || repo.startsWith("TUO-") || repo.startsWith("utente")) {
            Toast.makeText(this, "Inserisci il repository nel formato utente/repo (vedi guida)", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Cerco aggiornamenti...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var info: UpdateInfo? = null
            var err: String? = null
            try {
                info = withContext(Dispatchers.IO) { UpdateChecker(repo).checkLatest() }
            } catch (e: Exception) {
                err = e.message ?: "errore sconosciuto"
            }
            val i = info
            if (i == null) {
                Toast.makeText(this@SettingsActivity, "Errore: $err", Toast.LENGTH_LONG).show()
            } else if (UpdateChecker(repo).isNewer(i)) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Aggiornamento disponibile")
                    .setMessage("È disponibile la nuova versione " + i.versionName + " (build " + i.versionCode + "). Scaricare e installare?")
                    .setPositiveButton("Aggiorna") { _, _ -> downloadAndInstall(i, repo) }
                    .setNegativeButton("Annulla", null)
                    .show()
            } else {
                Toast.makeText(this@SettingsActivity, "Hai già l'ultima versione (" + i.versionName + ")", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAndInstall(info: UpdateInfo, repo: String) {
        Toast.makeText(this, "Scarico l'aggiornamento...", Toast.LENGTH_SHORT).show()
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
            val a = apk
            if (a == null) {
                Toast.makeText(this@SettingsActivity, "Download non riuscito: $err", Toast.LENGTH_LONG).show()
            } else {
                try {
                    UpdateChecker.installApk(applicationContext, a)
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Installazione non avviata: " + (e.message ?: "errore"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
