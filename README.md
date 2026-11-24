# Assistente Personale (Android)

Assistente personale per Android con comandi vocali e scritti, email Gmail con
riassunti e risposte in stile, apertura delle app, memoria delle conversazioni
e aggiornamenti automatici via GitHub. Uso personale, costo zero.

## Cosa sa fare (versione 1)

- Conversazione scritta e vocale (microfono, risposte lette ad alta voce)
- Email Gmail: lettura, elenco, riassunti, risposte in tuo stile, scrittura con conferma
- Comandi rapidi: "apri fotocamera", "apri gmail", "apri whatsapp", ecc.
- Memoria: ricorda conversazioni e informazioni ("ricorda che ...")
- Aggiornamenti: controlla le release di GitHub e si aggiorna con un tap

## Come si compila

Non serve compilare sul tuo PC: GitHub lo fa per te.

1. Crea un repository su GitHub (pubblico) e carica tutti i file di questo progetto
2. La scheda "Actions" compila l'app automaticamente ad ogni caricamento
3. Nella sezione "Releases" trovi il file APK da installare sul telefono

Tutti i passaggi sono descritti nella guida PDF inclusa nel pacchetto.

## Configurazione nell'app (Impostazioni)

- Chiave API di Gemini (gratuita): https://aistudio.google.com/apikey
- Indirizzo Gmail + "password per le app" (generata da myaccount.google.com)
- Nome e stile per le tue email
- Repository GitHub (formato utente/repo) per gli aggiornamenti

## Struttura del progetto

```
app/src/main/java/com/assistente/personale/
  MainActivity.kt        schermata chat (testo, voce, azioni)
  SettingsActivity.kt    impostazioni (chiavi, email, voce, aggiornamenti)
  brain/AssistantBrain.kt  collegamento all'API Gemini
  brain/ActionParser.kt    riconoscimento delle azioni richieste dall'AI
  email/EmailClient.kt     lettura IMAP e invio SMTP di Gmail
  memory/MemoryStore.kt    memoria locale (SQLite)
  commands/CommandRouter.kt  comandi diretti: apri app, aiuto, ricorda
  update/UpdateChecker.kt  controllo e installazione aggiornamenti
.github/workflows/build.yml  compilazione automatica su GitHub
```

## Prossimi aggiornamenti (già pianificati)

- Lettura messaggi WhatsApp/Telegram/Instagram/Facebook dalle notifiche
- Risposta ai messaggi social dalla notifica
