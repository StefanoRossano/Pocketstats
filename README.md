# Pocket Tracker

App Android locale per tenere traccia delle partite di Pokémon TCG Pocket (PvP): punti, streak di vittorie, deck usati e statistiche — tutto salvato solo sul telefono, nessun account o server.

## Come funziona

- **Stagioni**: ogni Stagione ha un proprio punteggio di partenza, streak e lista di deck. Le Stagioni passate restano consultabili ma bloccate: non si possono più registrare nuove partite, solo correzioni manuali.
- **Deck**: creati al volo mentre scegli con chi giocare, restano legati alla Stagione in cui li usi. Ogni deck ha un'anteprima colorata (6 stili grafici disponibili, più un'opzione arcobaleno) e tiene le proprie statistiche: partite, W/L, win rate, streak massima, variazione punti.
- **Partite**: un tap su W o L registra il risultato, aggiorna punti e streak automaticamente, e assegna un bonus crescente ogni vittoria consecutiva (fino a un tetto), azzerato da ogni sconfitta.
- **Correzioni manuali**: per allineare i conti se hai giocato senza registrare ogni singola partita — inserisci i totali attuali, la differenza viene calcolata da sola.
- **Liste**: puoi allegare screenshot a un deck per tenere traccia della sua composizione.
- **Statistiche**: grafico dell'andamento punti (filtrabile per 1 giorno / 3 giorni / tutta la Stagione), win rate, streak massima, ripartizione per deck.
- **Messaggi motivazionali**: frasi diverse a seconda di vittorie, sconfitte, streak lunghe o giornate no, più un saluto che cambia con l'ora del giorno — usano un sistema "shuffle bag" per non ripetersi finché non sono state usate tutte.
- **Localizzazione**: inglese e italiano al momento, con la struttura pronta per aggiungerne altre (`res/values-XX/strings.xml`).
- **Nessun account, server o connessione richiesta**: tutto resta sul dispositivo, persistenza tramite SharedPreferences + JSON.

## Struttura del progetto

- `app/src/main/java/.../MainActivity.java` — tutta la logica e l'interfaccia (Canvas custom, nessun layout XML per le schermate principali)
- `app/src/main/res/values/` e `values-it/` — testi dell'interfaccia (inglese e italiano)
- `app/src/main/res/drawable/`, `mipmap-anydpi-v26/` — icone e sfondi
- `.github/workflows/build.yml` — build automatica dell'APK debug tramite GitHub Actions a ogni push

## Build

La build avviene tramite GitHub Actions: ogni push su `main` compila automaticamente un APK di debug, scaricabile dalla tab **Actions** del repository (sezione "Artifacts").

In alternativa, in locale: aprire la cartella in Android Studio, attendere il Gradle Sync e creare l'APK da Build → Build APK(s), oppure da terminale con `./gradlew assembleDebug`.

## Stato del progetto

Sviluppo attivo e iterativo. L'interfaccia usa una View custom (Canvas) per restare leggera; la parte dati è separata a sufficienza da poter essere migrata in futuro, se necessario, verso Room o Jetpack Compose.
