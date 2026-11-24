package com.assistente.personale.email

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** Una email letta dalla casella di posta */
data class EmailSummary(
    val seq: Int,        // numero progressivo della email nella casella
    val from: String,    // mittente
    val subject: String, // oggetto
    val date: String,    // data formattata
    val snippet: String  // inizio del testo
)

/**
 * Client email per Gmail: legge la posta via IMAP e la invia via SMTP,
 * usando l'indirizzo Gmail e la "password per le app" salvate nelle impostazioni.
 */
class EmailClient(private val address: String, private val password: String) {

    private fun session(): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.timeout", "25000")
            put("mail.imaps.connectiontimeout", "25000")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.connectiontimeout", "25000")
            put("mail.smtp.timeout", "40000")
        }
        return Session.getInstance(props)
    }

    private fun openInbox(): Pair<Store, Folder> {
        val store = session().getStore("imaps")
        store.connect("imap.gmail.com", address, password)
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        return store to inbox
    }

    private fun closeQuietly(store: Store, inbox: Folder) {
        try { if (inbox.isOpen) inbox.close(false) } catch (_: Exception) {}
        try { store.close() } catch (_: Exception) {}
    }

    /** Legge le ultime [n] email della posta in arrivo (la prima della lista e' la piu' recente) */
    fun fetchLatest(n: Int): List<EmailSummary> {
        val (store, inbox) = openInbox()
        try {
            val count = inbox.messageCount
            if (count == 0) return emptyList()
            val from = maxOf(1, count - n + 1)
            val msgs: Array<Message> = inbox.getMessages(from, count)
            val fp = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) }
            inbox.fetch(msgs, fp)
            return msgs.mapIndexed { i, m ->
                val mm = m as MimeMessage
                EmailSummary(
                    seq = from + i,
                    from = fromAddress(mm),
                    subject = mm.subject ?: "(senza oggetto)",
                    date = dateStr(mm.sentDate),
                    snippet = snippetOf(mm)
                )
            }.sortedByDescending { it.seq }
        } finally {
            closeQuietly(store, inbox)
        }
    }

    /** Legge il testo completo della email con il numero progressivo indicato */
    fun readBody(seq: Int): String {
        val (store, inbox) = openInbox()
        try {
            val m = inbox.getMessage(seq) as MimeMessage
            return fullText(m)
        } finally {
            closeQuietly(store, inbox)
        }
    }

    /** Invia una email via SMTP di Gmail */
    fun send(to: String, subject: String, body: String) {
        val sess = session()
        val msg = MimeMessage(sess).apply {
            setFrom(InternetAddress(address))
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            setSubject(subject)
            setText(body, "UTF-8")
        }
        val t = sess.getTransport("smtp")
        t.connect("smtp.gmail.com", address, password)
        try {
            t.sendMessage(msg, msg.getAllRecipients())
        } finally {
            try { t.close() } catch (_: Exception) {}
        }
    }

    private fun fromAddress(m: MimeMessage): String {
        val f = m.from ?: return "(sconosciuto)"
        if (f.isEmpty()) return "(sconosciuto)"
        val a = f[0] as? InternetAddress
        val personal = a?.personal
        val addr = a?.address ?: "(sconosciuto)"
        return if (!personal.isNullOrBlank()) "$personal <$addr>" else addr
    }

    private fun dateStr(d: Date?): String =
        if (d == null) "" else SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(d)

    private fun snippetOf(m: MimeMessage): String {
        val text = fullText(m)
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length > 140) one.substring(0, 140) + "..." else one
    }

    private fun fullText(part: Part): String {
        return try {
            if (part.isMimeType("text/plain")) {
                part.content.toString()
            } else if (part.isMimeType("multipart/*")) {
                val mp = part.content as MimeMultipart
                val plain = StringBuilder()
                var html: String? = null
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (bp.isMimeType("text/plain")) {
                        plain.append(bp.content.toString()).append("\n")
                    } else if (bp.isMimeType("text/html") && html == null) {
                        html = bp.content.toString()
                    } else if (bp.isMimeType("multipart/*")) {
                        plain.append(fullText(bp)).append("\n")
                    }
                }
                val p = plain.toString().trim()
                if (p.isNotBlank()) p
                else html?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }
}
