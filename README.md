# Pocket Tracker

Prima versione prototipale di un'app Android locale per tracking W/L.

## Caratteristiche incluse
- Season indipendenti, con baseline e streak iniziali proprie.
- Sessioni numerate.
- Deck obbligatorio per le sessioni normali.
- Deck mantenuti solo nella Season corrente.
- Screenshot associabile a un Deck.
- W/L con bonus streak: +10, +13, +16, +19, poi +22; L = -10 e streak a 0.
- Grafico locale con segmenti verdi/rossi.
- Win rate e statistiche per Season e Deck.
- Undo / Repeat di base.
- Sessione non tracciata con Deck "Sconosciuto".
- Persistenza locale tramite SharedPreferences/JSON.
- Nessun account, server o connessione necessaria.

## Build
Aprire la cartella in Android Studio, attendere il Gradle Sync e creare l'APK da:
Build > Build APK(s)

Nota: questa è una v0.1 funzionale/prototipale. L'interfaccia usa una View custom per mantenere il progetto leggero; la parte dati è già separata abbastanza da poter essere migrata in seguito a Room/Jetpack Compose.
