package com.stefanorossano.pockettracker;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String TAG = "PocketTracker";

    // Livelli di navigazione dell'app (schermata attualmente mostrata).
    static final int SCREEN_SEASON_LIST = 0;   // Lista delle Stagioni
    static final int SCREEN_SEASON_DETAIL = 1; // Dettaglio Season: tab Gioca / Deck / Statistiche
    static final int SCREEN_SESSION_PLAY = 2;  // Schermata di registrazione partita (W/L, annulla)

    static final int DEFAULT_BASELINE = 810; // Punteggio di partenza standard per una nuova Stagione

    int screen = SCREEN_SEASON_LIST;
    TrackerView view;
    Store store;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        if (store.seasons.isEmpty()) {
            wizardStep1(true, null);
        } else {
            view = new TrackerView(this);
            setContentView(view);
            attachInsets(view);
            screen = SCREEN_SEASON_LIST;
        }
    }

    // Applica gli inset di sistema (status bar in alto, barra di navigazione in basso) come padding sulla
    // View: da Android 15 (targetSdk 35) il layout edge-to-edge e' attivo di default, quindi senza questo
    // il contenuto verrebbe disegnato dietro l'orologio/status bar e dietro i pulsanti di navigazione.
    void attachInsets(TrackerView v){
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    @Override protected void onPause() {
        super.onPause();
        // Safety net: assicura la persistenza anche se un salvataggio puntuale fosse saltato.
        if (store != null) store.save();
    }

    /** Naviga di un livello indietro nella gerarchia Lista Season -> Dettaglio -> Registrazione partita. */
    void goBack() {
        if (screen == SCREEN_SESSION_PLAY) { screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; }
        else if (screen == SCREEN_SEASON_DETAIL) { screen = SCREEN_SEASON_LIST; }
        view.invalidate();
    }

    @Override public void onBackPressed() {
        if (screen == SCREEN_SEASON_LIST) super.onBackPressed(); // livello radice: comportamento standard (esce dall'app)
        else goBack();
    }

    Season currentSeason(){ return store.seasons.get(store.current); }

    /**
     * Collega il positive button di un AlertDialog a un'azione che decide essa stessa
     * se il dialog puo' chiudersi. Evita che input non validi chiudano un dialog
     * obbligatorio (setCancelable(false)) lasciando l'app in uno stato non recuperabile.
     */
    interface Validated { boolean run(); }
    void showNonDismissing(AlertDialog dialog, Validated action, String errorMessage) {
        dialog.setOnShowListener(dd -> {
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (ok == null) return;
            ok.setOnClickListener(v -> {
                boolean success;
                try { success = action.run(); } catch (Exception e) { success = false; }
                if (success) dialog.dismiss();
                else Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            });
        });
    }

    // ===== Wizard di creazione Stagione, a step: Nome -> Hai già giocato prima del tracker? -> (Sì: punteggio
    // attuale, registrato come correzione manuale iniziale) oppure (No: si parte dallo standard 810/streak 0).
    // Non esiste piu' il concetto di sessione: la correzione iniziale e' semplicemente la prima "partita"
    // della lista, marcata come correzione (non una vittoria/sconfitta vera).

    void wizardStep1(boolean first, String prefillName){
        LinearLayout box = formBox();
        String defaultName = first ? "Stagione 1" : ("Stagione " + (store.seasons.size()+1));
        box.addView(label("Nome Stagione"));
        EditText name = field(defaultName);
        if (prefillName != null) name.setText(prefillName);
        box.addView(name);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(first ? "Crea la prima Stagione" : "Nuova Stagione")
            .setView(box).setCancelable(!first)
            .setPositiveButton("Avanti", (d,w) -> {
                String n = name.getText().toString().trim();
                wizardStep2(first, n.isEmpty() ? defaultName : n);
            });
        if (!first) b.setNegativeButton("Annulla", null); // solo se NON e' la primissima Stagione: qui c'e' gia' una lista a cui tornare
        b.show();
    }

    void wizardStep2(boolean first, String name){
        new AlertDialog.Builder(this).setTitle(name)
            .setMessage("Hai già giocato questa Stagione prima di usare il tracker?")
            .setCancelable(false)
            .setNeutralButton("Indietro", (d,w) -> wizardStep1(first, name))
            .setPositiveButton("Sì", (d,w) -> wizardStep3Yes(first, name))
            .setNegativeButton("No", (d,w) -> wizardStep3No(first, name))
            .show();
    }

    // "Sì": chiede solo lo stato ATTUALE (il baseline resta lo standard 810/streak 0) e registra la
    // differenza come un'unica partita di correzione (stesso meccanismo di "Aggiungi correzione manuale").
    void wizardStep3Yes(boolean first, String name){
        LinearLayout box = formBox();
        EditText points = numberField("Punti attuali", true);
        EditText streak = numberField("Vittorie consecutive attuali", true);
        box.addView(label("Punti attuali")); box.addView(points);
        box.addView(label("Vittorie consecutive attuali")); box.addView(streak);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name)
            .setView(box).setCancelable(false)
            .setPositiveButton("Crea Stagione", null)
            .setNeutralButton("Indietro", (d,w) -> wizardStep2(first, name))
            .create();
        showNonDismissing(dialog, () -> {
            try {
                int np = Integer.parseInt(points.getText().toString());
                int ns = Integer.parseInt(streak.getText().toString());
                if (ns < 0) return false;
                Season s = new Season(name);
                s.baseline = DEFAULT_BASELINE; s.initialStreak = 0;
                s.currentDeck = "Unknown";
                s.matches.add(Match.correction(DEFAULT_BASELINE, np, "Unknown"));
                s.points = np; s.streak = ns;
                store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
                if (view == null) { view = new TrackerView(this); setContentView(view); attachInsets(view); }
                screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
                return true;
            } catch (Exception e) { return false; }
        }, "Inserisci punti attuali e vittorie consecutive validi (streak >= 0).");
        dialog.show();
    }

    // "No": si parte dallo standard 810/streak 0, e si passa dritti alla scelta del deck di partenza
    // (con "Annulla" per decidere più avanti, come al solito).
    void wizardStep3No(boolean first, String name){
        Season s = new Season(name);
        s.baseline = DEFAULT_BASELINE; s.initialStreak = 0;
        s.points = DEFAULT_BASELINE; s.streak = 0;
        s.currentDeck = "Unknown";
        store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
        if (view == null) { view = new TrackerView(this); setContentView(view); attachInsets(view); }
        screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
        pickDeckFor(s, dn -> { s.currentDeck = dn; store.save(); view.invalidate(); });
    }


    boolean deckNameTaken(Season s, String n) {
        if ("Unknown".equalsIgnoreCase(n)) return true;
        for (Deck d : s.decks) if (d.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    // Selettore di deck condiviso: usato sia per scegliere il deck "attuale" (quello che verra' assegnato alla
    // PROSSIMA partita registrata) sia per cambiare retroattivamente il deck di una partita GIA' giocata.
    // onPicked riceve il nome del deck scelto (o appena creato) e decide lui cosa farne.
    void pickDeckFor(Season s, java.util.function.Consumer<String> onPicked) {
        ArrayList<String> names = new ArrayList<>();
        for (Deck d : s.decks) names.add(d.name);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) { createDeckAndAssignTo(s, onPicked); return; }

        LinearLayout box = formBox();
        box.addView(label("Deck"));
        Button deckSelector = new Button(this); styleSecondaryButton(deckSelector);
        deckSelector.setText(withBigArrow("Tocca per scegliere un deck  ▾"));
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selLp.topMargin = dp(6); deckSelector.setLayoutParams(selLp);
        box.addView(deckSelector);

        Button newDeckBtn = new Button(this); newDeckBtn.setText("+ Nuovo Deck..."); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newBtnLp.topMargin = dp(10); newDeckBtn.setLayoutParams(newBtnLp);
        box.addView(newDeckBtn);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Scegli il Deck").setView(box)
            .setNegativeButton("Annulla", null).create();
        deckSelector.setOnClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Scegli un Deck").setItems(names.toArray(new String[0]),(d2,which)->{
                onPicked.accept(names.get(which)); dialog.dismiss();
            }).show();
        });
        newDeckBtn.setOnClickListener(v -> { dialog.dismiss(); createDeckAndAssignTo(s, onPicked); });
        dialog.show();
    }

    void createDeckAndAssignTo(Season s, java.util.function.Consumer<String> onPicked) {
        LinearLayout box = formBox();
        EditText e = field("Nome Deck"); box.addView(e);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Nuovo Deck").setView(box)
            .setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            s.decks.add(new Deck(n)); store.save();
            onPicked.accept(n);
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    void renameDeckDialog(Deck d){
        if (d==null) return;
        Season s = store.seasons.get(store.current);
        String oldName = d.name;
        LinearLayout box = formBox();
        box.addView(label("Nome Deck"));
        EditText e = field(oldName); e.setText(oldName);
        box.addView(e);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Rinomina Deck").setView(box)
            .setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || n.equalsIgnoreCase("Deck sconosciuto") || n.equalsIgnoreCase("Unknown")) return false;
            for (Deck other: s.decks) if (other!=d && other.name.equalsIgnoreCase(n)) return false;
            d.name = n;
            for (Match m: s.matches) if (oldName.equals(m.deck)) m.deck = n;
            if (oldName.equals(s.currentDeck)) s.currentDeck = n;
            store.save(); view.invalidate();
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    // Cambia il deck "attuale" (quello che verra' usato per la PROSSIMA partita registrata).
    void chooseCurrentDeck() {
        Season s = store.seasons.get(store.current);
        pickDeckFor(s, name -> { s.currentDeck = name; store.save(); view.invalidate(); });
    }

    // Cambia retroattivamente il deck di una partita GIA' giocata: le statistiche per deck sono sempre
    // calcolate al volo dal campo 'deck' di ogni partita, quindi si aggiornano da sole.
    void changeMatchDeck(Match m) {
        Season s = store.seasons.get(store.current);
        pickDeckFor(s, name -> { m.deck = name; store.save(); view.invalidate(); });
    }

    void win() { play(true); }
    void loss() { play(false); }

    void play(boolean win) {
        Season s = store.seasons.get(store.current);
        int before = s.points;
        if (win) { s.streak++; s.points += reward(s.streak); }
        else { s.points -= 10; s.streak = 0; }
        Match m = new Match(win, before, s.points, s.streak, s.currentDeck!=null ? s.currentDeck : "Unknown");
        s.matches.add(m);
        store.save(); view.invalidate();
    }

    int reward(int streak) { return streak<=1?10:streak==2?13:streak==3?16:streak==4?19:22; }

    // Ricalcola punti/streak della Stagione a partire dall'ULTIMA partita rimasta (o dal baseline se non ce
    // ne sono piu'): usato dopo un annullamento.
    void recomputeSeasonState(Season s){
        if (s.matches.isEmpty()) { s.points = s.baseline; s.streak = s.initialStreak; }
        else { Match last = s.matches.get(s.matches.size()-1); s.points = last.after; s.streak = last.streak; }
    }

    // Annulla l'ultima partita registrata: richiede sempre conferma esplicita (e' un'azione distruttiva).
    // Niente piu' "ripeti": se l'annullamento stesso e' stato un errore, si registra di nuovo la partita
    // premendo W o L.
    void confirmUndo(){
        Season s = store.seasons.get(store.current);
        if (s.matches.isEmpty()) { Toast.makeText(this,"Nessuna partita da annullare.",Toast.LENGTH_SHORT).show(); return; }
        Match last = s.matches.get(s.matches.size()-1);
        String desc = last.unknown ? "l'ultima correzione manuale" : ("l'ultima partita ("+(last.win?"vittoria":"sconfitta")+")");
        new AlertDialog.Builder(this).setTitle("Annullare l'ultima partita?")
            .setMessage("Verrà eliminata definitivamente "+desc+". Se hai sbagliato ad annullare, registra di nuovo il risultato con W o L.")
            .setPositiveButton("Annulla partita", (d,w) -> {
                s.matches.remove(s.matches.size()-1);
                recomputeSeasonState(s);
                store.save(); view.invalidate();
            })
            .setNegativeButton("Chiudi", null)
            .show();
    }

    // Aggiunge una correzione manuale di punteggio (es. partite giocate senza registrarle una per una):
    // appare nella lista partite come le altre, ma non conta ai fini di W/L/streak.
    void addManualCorrection(){
        Season s = store.seasons.get(store.current);
        LinearLayout box = formBox();
        EditText p = numberField("Punti attuali", true);
        EditText st = numberField("Vittorie consecutive attuali", true);
        box.addView(label("Punti attuali")); box.addView(p);
        box.addView(label("Vittorie consecutive attuali")); box.addView(st);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Aggiungi correzione manuale")
            .setMessage("Usala per allineare i punti quando hai giocato senza registrare le singole partite.")
            .setView(box).setPositiveButton("Conferma", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            try {
                int np = Integer.parseInt(p.getText().toString());
                int ns = Integer.parseInt(st.getText().toString());
                if (ns < 0) return false;
                Match m = Match.correction(s.points, np, s.currentDeck!=null ? s.currentDeck : "Unknown");
                s.matches.add(m);
                s.points = np; s.streak = ns;
                store.save(); view.invalidate();
                return true;
            } catch (Exception e) { return false; }
        }, "Valori non validi (punti/streak non validi, streak >= 0).");
        dialog.show();
    }

    void newSeason(){ wizardStep1(false, null); }

    // Menu "⋮" della schermata di registrazione partita: azioni secondarie (modifica deck, correzione manuale).
    void playScreenKebabMenu(float rightEdgeX, float anchorY){
        view.showAnchoredMenu(rightEdgeX, anchorY,
            new String[]{"Modifica deck","Aggiungi correzione manuale"},
            new int[]{Color.WHITE, Color.WHITE},
            new Runnable[]{ this::chooseCurrentDeck, this::addManualCorrection });
    }

    // Colori/stile condivisi per i widget nativi dei dialog (Season/Deck), coerenti con la palette scura
    // del resto dell'app invece dei widget Android di default (che stonavano visivamente).
    static final int FIELD_BG = Color.rgb(10,18,30), FIELD_BORDER = Color.rgb(32,48,68), MUTED_TXT = Color.rgb(150,160,178);
    int dp(float v){ return Math.round(v * getResources().getDisplayMetrics().density); }
    GradientDrawable pill(int fill, Integer stroke){
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(14));
        if (stroke != null) d.setStroke(dp(1), stroke);
        return d;
    }
    void styleField(EditText e){
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(MUTED_TXT);
        e.setBackground(pill(FIELD_BG, FIELD_BORDER));
        e.setPadding(dp(14),dp(12),dp(14),dp(12));
    }
    void styleSecondaryButton(Button b){
        b.setTextColor(Color.WHITE);
        b.setBackground(pill(Color.rgb(20,32,48), FIELD_BORDER));
        b.setPadding(dp(14),dp(10),dp(14),dp(10));
        b.setAllCaps(false);
    }

    // Il glifo "▾" nel testo di un pulsante (es. selettore deck) risultava troppo piccolo rispetto al resto:
    // qui lo ingrandiamo SOLO lui (l'ultimo carattere), lasciando il resto del testo alla dimensione normale.
    CharSequence withBigArrow(String base){
        android.text.SpannableString sp = new android.text.SpannableString(base);
        int idx = base.length()-1;
        sp.setSpan(new android.text.style.RelativeSizeSpan(1.7f), idx, idx+1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    LinearLayout formBox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(6),dp(20),0);return l;}
    TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(MUTED_TXT);t.setTextSize(12);t.setPadding(0,dp(10),0,dp(4));return t;}
    EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine();styleField(e);return e;}
    EditText multilineField(String hint, String initial){
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        styleField(e);
        if (initial != null) e.setText(initial);
        return e;
    }

    int blueColor(){ return Color.rgb(55,120,255); }
    int red(){ return Color.rgb(245,70,60); }
    // Formato richiesto: dd/mm/yy hh:mm. Ritorna stringa vuota se il timestamp non e' disponibile (dati
    // vecchi salvati prima dell'introduzione di questo campo).
    String formatTimestamp(long ts){
        if (ts<=0) return "";
        return new java.text.SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY).format(new java.util.Date(ts));
    }
    // Solo la data, senza l'orario: usata nel grafico e nei raggruppamenti giornalieri.
    String formatDateOnly(long ts){
        if (ts<=0) return "Data sconosciuta";
        return new java.text.SimpleDateFormat("dd/MM/yy", Locale.ITALY).format(new java.util.Date(ts));
    }
    // Solo l'ora, usata nelle singole righe partita (la data e' gia' nell'intestazione del gruppo giornaliero).
    String formatTimeOnly(long ts){
        if (ts<=0) return "";
        return new java.text.SimpleDateFormat("HH:mm", Locale.ITALY).format(new java.util.Date(ts));
    }
    // Chiave univoca del "giorno" di un timestamp, per raggruppare le partite: le partite senza timestamp
    // reale (dati vecchi) finiscono tutte in un unico gruppo "sconosciuto".
    String dayKey(long ts){ return ts<=0 ? "?" : new java.text.SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new java.util.Date(ts)); }

    // Conteggio W/L unificato: esclude le correzioni manuali (unknown=true), che non sono vittorie/sconfitte
    // vere. Centralizzare qui evita il bug per cui una correzione con punti saliti veniva erroneamente
    // contata come una vittoria vera in alcuni punti dell'app.
    static int[] countWL(List<Match> matches){
        int w=0,l=0;
        for(Match m: matches) if(!m.unknown){ if(m.win) w++; else l++; }
        return new int[]{w,l};
    }

    EditText numberField(String hint, boolean signed){
        EditText e = field(hint);
        // Tastiera numerica...
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (signed ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        // ...e in piu' un filtro che rifiuta qualsiasi carattere non numerico (a prova di incolla/tastiere di terze parti):
        // solo cifre, e un singolo '-' iniziale se il campo accetta valori negativi.
        e.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                char ch = source.charAt(i);
                boolean minusAtStart = signed && ch=='-' && dstart==0 && dest.toString().indexOf('-') < 0;
                if (Character.isDigit(ch) || minusAtStart) sb.append(ch);
            }
            return sb.toString();
        }});
        return e;
    }

    // Cancella TUTTI i dati salvati (ogni Stagione, partita, deck). Richiede sempre conferma esplicita,
    // dato che e' un'azione distruttiva e irreversibile. Dopo la cancellazione, l'app si comporta come al
    // primissimo avvio: riparte dal wizard obbligatorio di creazione della prima Stagione.
    void resetAllData(){
        new AlertDialog.Builder(this).setTitle("Cancellare tutti i dati?")
            .setMessage("Questo eliminerà definitivamente ogni Stagione, partita e deck. L'azione non può essere annullata.")
            .setPositiveButton("Elimina tutto", (d,w) -> {
                store.seasons.clear();
                store.current = 0;
                store.save();
                if (view != null) view.invalidate();
                wizardStep1(true, null);
            })
            .setNegativeButton("Annulla", null)
            .show();
    }

    void renameSeason(){
        Season s=store.seasons.get(store.current);
        LinearLayout box = formBox();
        box.addView(label("Nome Stagione"));
        EditText e=field(s.name); e.setText(s.name);
        box.addView(e);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Rinomina Stagione").setView(box)
            .setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return false;
            s.name = n; store.save(); view.invalidate();
            return true;
        }, "Il nome della Stagione non può essere vuoto.");
        dialog.show();
    }

    // Elimina un deck: se e' usato in una o piu' partite, avvisa prima e, se confermato, imposta quelle
    // partite su "Deck sconosciuto" (Unknown) invece di lasciarle con un riferimento a un deck inesistente.
    void confirmDeleteDeck(Season s, Deck d){
        int usedCount = 0;
        for (Match m: s.matches) if (d.name.equals(m.deck)) usedCount++;
        String message = usedCount>0
            ? "Questo deck e' usato in "+usedCount+" "+(usedCount==1?"partita":"partite")+". Eliminandolo, "+(usedCount==1?"verra' impostata":"verranno impostate")+" su \"Deck sconosciuto\"."
            : "Eliminare definitivamente questo deck?";
        new AlertDialog.Builder(this).setTitle("Elimina "+d.name)
            .setMessage(message)
            .setPositiveButton("Elimina", (dlg,w)-> {
                for (Match m: s.matches) if (d.name.equals(m.deck)) m.deck = "Unknown";
                if (d.name.equals(s.currentDeck)) s.currentDeck = "Unknown";
                s.decks.remove(d);
                store.save(); if (view!=null) view.invalidate();
            })
            .setNegativeButton("Annulla", null)
            .show();
    }

    void addDeck(){
        Season s=store.seasons.get(store.current); LinearLayout box=formBox();
        // Tolta l'etichetta "Nome Deck" sopra il campo: il titolo del dialog e' gia' "Nuovo Deck" e il campo
        // ha comunque il placeholder "Nome Deck" — prima la scritta compariva 3 volte, troppa ripetizione.
        EditText e=field("Nome Deck"); box.addView(e);
        Button img=new Button(this); img.setText("Aggiungi screenshot (opzionale)"); styleSecondaryButton(img);
        // Margine e larghezza piena come negli altri dialog (prima il pulsante era attaccato al campo sopra,
        // senza respiro, e piu' stretto del contenuto — risultava piu' "povero" rispetto al dialog Nuova Sessione.
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        imgLp.topMargin = dp(14); img.setLayoutParams(imgLp);
        box.addView(img);
        img.setOnClickListener(v-> pickImageFor(null)); // null = immagine "in sospeso", verra' assegnata al Deck solo se il salvataggio va a buon fine
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Nuovo Deck").setView(box)
            .setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            String n=e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            Deck deck=new Deck(n); if(pendingImage!=null){deck.images.add(pendingImage.toString());pendingImage=null;}
            s.decks.add(deck);store.save();view.invalidate();
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    // Trova, all'interno della Stagione corrente, il Deck con questo nome (ogni Stagione ha i propri Deck:
    // uno stesso nome in due Stagioni diverse corrisponde a due oggetti Deck distinti, con screenshot distinti).
    Deck findDeck(Season s, String name){
        if (s==null || name==null) return null;
        for (Deck d : s.decks) if (d.name.equals(name)) return d;
        return null;
    }

    Uri pendingImage=null;            // immagine "in sospeso" per un Deck non ancora creato (flusso addDeck)
    Deck pendingImageTargetDeck=null; // Deck esistente a cui AGGIUNGERE l'immagine scelta (flusso di gestione screenshot)

    void pickImageFor(Deck target){
        pendingImageTargetDeck = target;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i,101); } catch(Exception ex) { Toast.makeText(this,"Nessuna app disponibile per selezionare immagini.",Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==101 && result==RESULT_OK && data!=null){
            Uri uri = data.getData();
            try{ getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(Exception e){ Log.w(TAG, "Impossibile ottenere il permesso persistente sull'immagine", e); }
            if (pendingImageTargetDeck != null) {
                // Aggiunge alla lista di un Deck gia' esistente: un Deck puo' avere piu' di uno screenshot associato.
                Deck targetDeck = pendingImageTargetDeck;
                targetDeck.images.add(uri.toString());
                pendingImageTargetDeck = null;
                store.save(); if (view != null) view.invalidate();
                // Dopo il caricamento, apre subito la galleria sull'immagine appena aggiunta (prima non dava
                // alcun feedback visivo immediato).
                showImageGallery(targetDeck, targetDeck.images.size()-1);
            } else {
                pendingImage = uri; // in attesa che l'utente completi la creazione del nuovo Deck
            }
        }
    }

    // Gestione screenshot di un Deck: aggiungi, visualizza o rimuovi ciascuno. Un Deck puo' avere piu' di
    // uno screenshot (es. varianti diverse, momenti diversi).
    // Apre direttamente la galleria se ci sono gia' immagini, altrimenti va dritto alla scelta di una nuova
    // immagine: niente piu' menu intermedio "Visualizza/Rimuovi/Aggiungi" da attraversare.
    void openDeckImages(Deck d){
        if (d==null) return;
        if (d.images.isEmpty()) pickImageFor(d);
        else showImageGallery(d, 0);
    }

    // Icona cestino disegnata su un piccolo Bitmap (per usarla in ImageView nei dialog nativi, dove non
    // possiamo disegnare direttamente su Canvas come nella UI principale dell'app).
    Bitmap makeTrashIcon(int color, int sizePx){
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(bmp);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = sizePx;
        pp.setColor(color); pp.setStyle(Paint.Style.FILL);
        // Geometria semplice e simmetrica (rettangoli arrotondati soltanto, niente piu' trapezio/curve custom
        // che a piccola scala risultavano storte): manico, coperchio, corpo.
        cc.drawRoundRect(s*0.40f, s*0.06f, s*0.60f, s*0.15f, s*0.02f, s*0.02f, pp);
        cc.drawRoundRect(s*0.18f, s*0.15f, s*0.82f, s*0.24f, s*0.02f, s*0.02f, pp);
        cc.drawRoundRect(s*0.24f, s*0.28f, s*0.76f, s*0.90f, s*0.06f, s*0.06f, pp);
        return bmp;
    }

    // Freccia "‹"/"›" disegnata su Bitmap (invece del glifo testuale via TextView): garantisce centratura
    // perfetta nel cerchio (i glifi di testo, per via delle metriche del font/line-height, non si centrano
    // mai in modo affidabile con setGravity(CENTER) da soli).
    Bitmap makeChevronIcon(int color, int sizePx, boolean pointRight){
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(bmp);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(color); pp.setStyle(Paint.Style.STROKE);
        pp.setStrokeWidth(sizePx*0.14f); pp.setStrokeCap(Paint.Cap.ROUND); pp.setStrokeJoin(Paint.Join.ROUND);
        float s = sizePx;
        android.graphics.Path p2 = new android.graphics.Path();
        if (pointRight){
            p2.moveTo(s*0.38f, s*0.22f); p2.lineTo(s*0.66f, s*0.5f); p2.lineTo(s*0.38f, s*0.78f);
        } else {
            p2.moveTo(s*0.62f, s*0.22f); p2.lineTo(s*0.34f, s*0.5f); p2.lineTo(s*0.62f, s*0.78f);
        }
        cc.drawPath(p2, pp);
        return bmp;
    }

    // Anteprima automatica del deck: ritaglia una regione a PERCENTUALI FISSE della prima immagine caricata
    // (il box del mazzo nella schermata profilo del gioco, che sta sempre grosso modo nella stessa posizione
    // relativa) — non e' un vero riconoscimento dell'immagine, solo una stima geometrica; funziona in modo
    // affidabile solo se lo screenshot e' sempre l'intera schermata, non gia' ritagliata diversamente.
    // Risultato salvato in cache sul Deck stesso, per non ridecodificare/ritagliare ad ogni ridisegno.
    Bitmap getDeckPreview(Deck d){
        if (d.images.isEmpty()) return null;
        String uri = d.images.get(0);
        if (d.cachedPreview!=null && uri.equals(d.cachedPreviewSourceUri)) return d.cachedPreview;
        try {
            java.io.InputStream is = getContentResolver().openInputStream(Uri.parse(uri));
            Bitmap full = BitmapFactory.decodeStream(is);
            int cropLeft=(int)(full.getWidth()*0.108f), cropRight=(int)(full.getWidth()*0.318f);
            int cropTop=(int)(full.getHeight()*0.208f), cropBottom=(int)(full.getHeight()*0.312f);
            int cw=cropRight-cropLeft, ch=cropBottom-cropTop;
            if (cw<=0 || ch<=0) return null;
            Bitmap cropped = Bitmap.createBitmap(full, cropLeft, cropTop, cw, ch);
            d.cachedPreview = cropped; d.cachedPreviewSourceUri = uri;
            return cropped;
        } catch (Exception e) { return null; }
    }

    // Visualizzatore in stile galleria: header in alto (chiudi/titolo/aggiungi), frecce di navigazione come
    // piccoli cerchi semi-trasparenti sovrapposti ai lati dell'immagine, ed "elimina" (cestino) accanto al
    // contatore "N / M" sotto — cosi' e' chiaro che si riferisce ALLO SCREENSHOT visualizzato in quel momento,
    // non a un'azione generica. Ritaglia automaticamente il 19% dall'alto e il 14% dal basso: gli screenshot
    // del gioco hanno spesso intestazioni/pulsanti di sistema poco utili in quelle zone.
    void showImageGallery(Deck d, int startIndex){
        if (d.images.isEmpty()) return;
        final int[] idx = {Math.max(0, Math.min(startIndex, d.images.size()-1))};
        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6),dp(6),dp(6),dp(6));
        TextView closeBtn = new TextView(this); closeBtn.setText("✕"); closeBtn.setTextColor(Color.WHITE); closeBtn.setTextSize(20);
        closeBtn.setPadding(dp(12),dp(6),dp(12),dp(6));
        TextView title = new TextView(this); title.setText(d.name); title.setTextColor(Color.WHITE); title.setTextSize(14); title.setGravity(Gravity.CENTER);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView addBtn = new TextView(this); addBtn.setText("+"); addBtn.setTextColor(blueColor()); addBtn.setTextSize(24);
        addBtn.setPadding(dp(12),dp(2),dp(12),dp(2));
        header.addView(closeBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(addBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        android.widget.FrameLayout imageFrame = new android.widget.FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageFrame.addView(iv, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        int arrowIconPx = dp(16);
        ImageView prevBtn = new ImageView(this);
        prevBtn.setImageBitmap(makeChevronIcon(Color.WHITE, arrowIconPx, false));
        prevBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable prevBg = new GradientDrawable(); prevBg.setShape(GradientDrawable.OVAL); prevBg.setColor(Color.argb(140,10,18,30));
        prevBtn.setBackground(prevBg);
        android.widget.FrameLayout.LayoutParams prevLp = new android.widget.FrameLayout.LayoutParams(dp(36), dp(36));
        prevLp.gravity = Gravity.START|Gravity.CENTER_VERTICAL; prevLp.leftMargin=dp(10);
        imageFrame.addView(prevBtn, prevLp);

        ImageView nextBtn = new ImageView(this);
        nextBtn.setImageBitmap(makeChevronIcon(Color.WHITE, arrowIconPx, true));
        nextBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable nextBg = new GradientDrawable(); nextBg.setShape(GradientDrawable.OVAL); nextBg.setColor(Color.argb(140,10,18,30));
        nextBtn.setBackground(nextBg);
        android.widget.FrameLayout.LayoutParams nextLp = new android.widget.FrameLayout.LayoutParams(dp(36), dp(36));
        nextLp.gravity = Gravity.END|Gravity.CENTER_VERTICAL; nextLp.rightMargin=dp(10);
        imageFrame.addView(nextBtn, nextLp);

        root.addView(imageFrame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottomRow = new LinearLayout(this); bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(dp(18),dp(8),dp(14),dp(12));
        TextView counter = new TextView(this); counter.setTextColor(Color.WHITE); counter.setTextSize(13);
        ImageView deleteIcon = new ImageView(this);
        int iconSizePx = dp(20);
        deleteIcon.setImageBitmap(makeTrashIcon(red(), iconSizePx));
        deleteIcon.setPadding(dp(10),dp(6),dp(10),dp(6));
        bottomRow.addView(counter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bottomRow.addView(deleteIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottomRow);

        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            try {
                java.io.InputStream is = getContentResolver().openInputStream(Uri.parse(d.images.get(idx[0])));
                Bitmap full = BitmapFactory.decodeStream(is);
                int cropTop=(int)(full.getHeight()*0.19f), cropBottom=(int)(full.getHeight()*0.14f);
                int newH=full.getHeight()-cropTop-cropBottom;
                iv.setImageBitmap(newH>0 ? Bitmap.createBitmap(full,0,cropTop,full.getWidth(),newH) : full);
            } catch(Exception e) {
                iv.setImageBitmap(null);
                Toast.makeText(this,"Impossibile caricare l'immagine (file non più disponibile).",Toast.LENGTH_SHORT).show();
            }
            counter.setText((idx[0]+1)+" / "+d.images.size());
            prevBtn.setVisibility(idx[0]>0 ? View.VISIBLE : View.INVISIBLE);
            nextBtn.setVisibility(idx[0]<d.images.size()-1 ? View.VISIBLE : View.INVISIBLE);
        };
        prevBtn.setOnClickListener(v -> { if(idx[0]>0){ idx[0]--; load[0].run(); } });
        nextBtn.setOnClickListener(v -> { if(idx[0]<d.images.size()-1){ idx[0]++; load[0].run(); } });
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        addBtn.setOnClickListener(v -> { dialog.dismiss(); pickImageFor(d); });
        deleteIcon.setOnClickListener(v -> {
            d.images.remove(idx[0]); store.save();
            if (d.images.isEmpty()) { dialog.dismiss(); view.invalidate(); return; }
            if (idx[0] >= d.images.size()) idx[0] = d.images.size()-1;
            load[0].run(); view.invalidate();
        });
        load[0].run();

        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    /** Area rettangolare cliccabile associata a un indice, usata per l'hit-test nelle liste. */
    static class Hit { float top, bottom; int index; Hit(float t, float b, int i){top=t;bottom=b;index=i;} }

    class TrackerView extends View {
        Paint p=new Paint(3);
        int detailTab=0; // 0 = Gioca, 1 = Deck, 2 = Statistiche (solo dentro SCREEN_SEASON_DETAIL)
        int bg=Color.rgb(7,11,18), card=Color.rgb(14,24,38), white=Color.WHITE, muted=Color.rgb(165,175,190), blue=Color.rgb(55,120,255), green=Color.rgb(70,205,75), red=Color.rgb(245,70,60);
        ArrayList<Hit> seasonHits=new ArrayList<>();
        ArrayList<Hit> matchHits=new ArrayList<>();
        // Tutti i numeri usati in questa classe (posizioni, dimensioni testo, ecc.) sono pensati come "dp"
        // (unita' indipendenti dalla densita' dello schermo), NON pixel reali. 'density' converte l'uno
        // nell'altro: senza, su un telefono moderno (densita' ~3x) tutto apparirebbe rimpicciolito a 1/3.
        final float density;
        // Scroll verticale: ogni schermata ha un header (e talvolta un footer) fissi, con il contenuto in
        // mezzo che scorre se supera l'altezza disponibile. bodyTop/bodyBottom delimitano la zona scrollabile
        // per la schermata corrente; lastContentBottom e' impostato da ciascun metodo di disegno a fine
        // contenuto, per calcolare quanto si puo' scorrere.
        float scrollY=0, maxScrollY=0, bodyTop=0, bodyBottom=0, lastContentBottom=0, resetLinkY=0;
        String scrollKey="";
        void resetScrollIfNeeded(String key){ if(!key.equals(scrollKey)){ scrollY=0; scrollKey=key; } }
        // Scroll indipendente per la lista dentro la card "Partite": la card ha un'altezza fissa (non cresce
        // con il numero di partite, che puo' arrivare anche a centinaia), con una sua scrollbar propria,
        // separata dallo scroll generale della schermata. matchInnerListTop/Bottom sono le coordinate di
        // contenuto (prima dello scroll interno) della zona visibile, impostate durante il disegno e lette
        // dal gestore del tocco per instradare correttamente i trascinamenti.
        float matchInnerScrollY=0, matchInnerMaxScrollY=0, matchInnerListTop=0, matchInnerListBottom=0;
        String matchInnerScrollKey="";
        void resetMatchInnerScrollIfNeeded(String key){ if(!key.equals(matchInnerScrollKey)){ matchInnerScrollY=0; matchInnerScrollKey=key; } }
        // Stesso meccanismo, per la card "DECK" nel tab Statistiche (anche qui il numero di deck puo'
        // crescere parecchio: altezza fissa, scroll e scrollbar propri, indipendenti da tutto il resto).
        float statsDeckInnerScrollY=0, statsDeckInnerMaxScrollY=0, statsDeckInnerListTop=0, statsDeckInnerListBottom=0;
        String statsDeckInnerScrollKey="";
        void resetStatsDeckInnerScrollIfNeeded(String key){ if(!key.equals(statsDeckInnerScrollKey)){ statsDeckInnerScrollY=0; statsDeckInnerScrollKey=key; } }
        void finishScroll(){
            maxScrollY = Math.max(0, lastContentBottom-(bodyBottom-bodyTop));
            if(scrollY>maxScrollY) scrollY=maxScrollY;
            if(scrollY<0) scrollY=0;
        }
        // Calcola la baseline necessaria per centrare verticalmente un testo di questa dimensione su una
        // riga di centro comune (usa le metriche reali del font, non un offset indovinato): cosi' elementi
        // di dimensioni diverse restano allineati sulla stessa linea centrale.
        float centeredBaseline(float centerY, float size){
            p.setTextSize(size);
            Paint.FontMetrics fm = p.getFontMetrics();
            return centerY - (fm.ascent+fm.descent)/2;
        }
        // Come centeredBaseline, ma per un blocco di PIU' righe impilate (etichetta sopra, valore sotto, ecc.):
        // calcola le baseline in modo che l'intero blocco risulti centrato verticalmente attorno a centerY,
        // usando le metriche reali dei font invece di offset fissi indovinati. Ritorna una baseline per ogni
        // size passata, in ordine.
        float[] centerLines(float centerY, float gap, float... sizes){
            Paint.FontMetrics fm;
            float[] lineH = new float[sizes.length];
            float totalH = 0;
            for(int i=0;i<sizes.length;i++){ p.setTextSize(sizes[i]); fm=p.getFontMetrics(); lineH[i]=fm.descent-fm.ascent; totalH+=lineH[i]; }
            totalH += gap*(sizes.length-1);
            float cursor = centerY - totalH/2;
            float[] baselines = new float[sizes.length];
            for(int i=0;i<sizes.length;i++){
                p.setTextSize(sizes[i]); fm=p.getFontMetrics();
                baselines[i] = cursor - fm.ascent;
                cursor += lineH[i]+gap;
            }
            return baselines;
        }
        // Icona "modifica" ricalcata sulla forma reale dell'icona Material Design "edit" (24x24, licenza
        // Apache 2.0): corpo+punta ed una piccola "ghiera" separata vicino alla cima.
        void drawEditIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            c.save();
            c.translate(cx-12*s, cy-12*s);
            c.scale(s,s);
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            android.graphics.Path body=new android.graphics.Path();
            body.moveTo(3,17.25f); body.lineTo(14.06f,6.19f); body.lineTo(17.81f,9.94f); body.lineTo(6.75f,21); body.lineTo(3,21); body.close();
            c.drawPath(body,p);
            android.graphics.Path ferrule=new android.graphics.Path();
            ferrule.moveTo(20.71f,7.04f); ferrule.lineTo(20.71f,5.63f); ferrule.lineTo(18.37f,3.29f); ferrule.lineTo(16.96f,3.29f); ferrule.lineTo(15.13f,5.12f); ferrule.lineTo(18.88f,8.87f); ferrule.close();
            c.drawPath(ferrule,p);
            c.restore();
        }

        // Icona "indietro" (chevron sottile, stile standard), disegnata a mano.
        void drawChevronBack(Canvas c, float cx, float cy, float size, int color){
            p.setColor(color); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size*0.14f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(cx+size*0.18f, cy-size*0.32f);
            path.lineTo(cx-size*0.18f, cy);
            path.lineTo(cx+size*0.18f, cy+size*0.32f);
            c.drawPath(path,p);
        }

        // Icona "altre opzioni" (tre puntini verticali), disegnata a mano invece che con il glifo Unicode "⋮".
        void drawKebabIcon(Canvas c, float cx, float cy, int color){
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            float r=2.4f, gap=7.5f;
            c.drawCircle(cx,cy-gap,r,p);
            c.drawCircle(cx,cy,r,p);
            c.drawCircle(cx,cy+gap,r,p);
        }

        // Icona "annulla": un arco curvo con la punta orientata nel verso di percorrenza (calcolata dalla
        // tangente all'arco), invece del glifo Unicode "↶".
        void drawUndoIcon(Canvas c, float cx, float cy, float size, int color){
            float r = size*0.34f, strokeW = size*0.13f;
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(strokeW); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.RectF oval = new android.graphics.RectF(cx-r,cy-r,cx+r,cy+r);
            float startAngle = -20, sweep = -250;
            android.graphics.Path arc = new android.graphics.Path();
            arc.addArc(oval, startAngle, sweep);
            c.drawPath(arc,p);
            float endAngleRad = (float)Math.toRadians(startAngle+sweep);
            float ex = cx + r*(float)Math.cos(endAngleRad), ey = cy + r*(float)Math.sin(endAngleRad);
            float tx = (float)Math.sin(endAngleRad), ty = -(float)Math.cos(endAngleRad);
            float nx = -ty, ny = tx;
            float ah = strokeW*2.4f;
            p.setStyle(Paint.Style.FILL);
            android.graphics.Path head = new android.graphics.Path();
            head.moveTo(ex+tx*ah, ey+ty*ah);
            head.lineTo(ex-nx*ah*0.6f, ey-ny*ah*0.6f);
            head.lineTo(ex+nx*ah*0.6f, ey+ny*ah*0.6f);
            head.close();
            c.drawPath(head,p);
        }

        // Mostra un menu "a tendina" ancorato esattamente sotto un punto del canvas (logicalX/logicalY sono
        // nello stesso sistema di coordinate usato per disegnare, gia' al netto dello scroll se applicabile),
        // invece di un AlertDialog centrato al centro dello schermo. Converte le coordinate logiche in pixel
        // reali di schermo (via density + padding + posizione della view) per usare PopupWindow.showAtLocation.
        // Il parametro e' la posizione desiderata del bordo DESTRO del menu (non quello sinistro): misuriamo
        // il contenuto PRIMA di posizionarlo, cosi' il margine dal bordo destro resta corretto qualunque sia
        // la larghezza effettiva.
        void showAnchoredMenu(float rightEdgeLogicalX, float topLogicalY, String[] labels, int[] colors, Runnable[] actions){
            LinearLayout box = new LinearLayout(getContext()); box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(pill(Color.rgb(14,24,38), Color.rgb(40,55,75)));
            box.setPadding(dp(4),dp(4),dp(4),dp(4));
            final PopupWindow[] popupRef = new PopupWindow[1];
            for(int i=0;i<labels.length;i++){
                final int idx=i;
                TextView t = new TextView(getContext());
                t.setText(labels[i]); t.setTextColor(colors[i]); t.setTextSize(15);
                t.setPadding(dp(20),dp(14),dp(20),dp(14));
                t.setOnClickListener(v -> { popupRef[0].dismiss(); actions[idx].run(); });
                box.addView(t);
            }
            PopupWindow popup = new PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
            popupRef[0]=popup;
            popup.setElevation(dp(8));
            // Mostrato dentro un post(): se calcolato subito, alla PRIMA apertura dopo aver caricato la
            // schermata la posizione (getLocationOnScreen/padding da inset) puo' non essere ancora stabilizzata.
            post(() -> {
                box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                float leftLogicalX = rightEdgeLogicalX - box.getMeasuredWidth()/density;
                int[] loc = new int[2]; getLocationOnScreen(loc);
                int screenX = loc[0] + getPaddingLeft() + (int)(leftLogicalX*density);
                int screenY = loc[1] + getPaddingTop() + (int)(topLogicalY*density);
                popup.showAtLocation(this, Gravity.NO_GRAVITY, screenX, screenY);
            });
        }

        void drawScrollbar(Canvas c, float w){
            if(maxScrollY<=1) return;
            float trackH=bodyBottom-bodyTop;
            float thumbH=Math.max(30, trackH*trackH/(trackH+maxScrollY));
            float thumbY=bodyTop+(trackH-thumbH)*(scrollY/maxScrollY);
            p.setColor(Color.rgb(70,85,105)); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(w-7,thumbY,w-3,thumbY+thumbH,2,2,p);
        }

        TrackerView(Context c){
            super(c);
            p.setTypeface(Typeface.create("sans",Typeface.NORMAL));
            setBackgroundColor(bg);
            density = c.getResources().getDisplayMetrics().density;
        }

        void txt(Canvas c,String s,float x,float y,float size,int col,Paint.Align align){p.setTextSize(size);p.setColor(col);p.setTextAlign(align);p.setStyle(Paint.Style.FILL);c.drawText(s,x,y,p);}
        void box(Canvas c,float l,float t,float rr,float b,int col){p.setColor(col);p.setStyle(Paint.Style.FILL);c.drawRoundRect(l,t,rr,b,18,18,p);}

        // Colore del win rate: verde se >50%, rosso se <50%, colore neutro (muted) se == 50% o 0 partite.
        int wrColor(float wr,int total){ return total==0?muted:(wr>50?green:(wr<50?red:muted)); }
        String deckDisplayShort(String deckName){ return "Unknown".equals(deckName) ? "Deck sconosciuto" : deckName; }

        float rowWidth(String[] parts,float size){ p.setTextSize(size); float w=0; for(String s:parts) w+=p.measureText(s); return w; }
        // Riga di testo composta da segmenti con colori diversi (es. "7W" verde + "3L" rosso + "70%" verde), allineata a sinistra.
        float txtRow(Canvas c,float x,float y,float size,String[] parts,int[] cols){
            p.setTextSize(size); p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.LEFT);
            float cx=x; for(int i=0;i<parts.length;i++){ p.setColor(cols[i]); c.drawText(parts[i],cx,y,p); cx+=p.measureText(parts[i]); }
            return cx;
        }
        // Come sopra ma centrata rispetto a cx.
        void txtRowCentered(Canvas c,float cx,float y,float size,String[] parts,int[] cols){
            txtRow(c,cx-rowWidth(parts,size)/2,y,size,parts,cols);
        }
        // Come sopra ma allineata a destra rispetto a rightX.
        void txtRowRight(Canvas c,float rightX,float y,float size,String[] parts,int[] cols){
            txtRow(c,rightX-rowWidth(parts,size),y,size,parts,cols);
        }

        void strokeBox(Canvas c,float l,float t,float rr,float b,int col){
            p.setColor(col); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
            c.drawRoundRect(l,t,rr,b,16,16,p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            // Scala tutto il canvas secondo la densita' dello schermo (vedi commento sul campo 'density'),
            // e sposta l'origine sotto la status bar (il padding e' impostato in onCreate in base agli
            // inset di sistema, cosi' il contenuto non finisce mai dietro l'orologio/status bar).
            c.save();
            c.scale(density, density);
            c.translate(getPaddingLeft()/density, getPaddingTop()/density);
            float w=(getWidth()-getPaddingLeft()-getPaddingRight())/density;
            float h=(getHeight()-getPaddingTop()-getPaddingBottom())/density;
            if (screen == SCREEN_SEASON_LIST) { seasonList(c,w,h); c.restore(); return; }
            Season s = store.seasons.get(store.current);
            if (screen == SCREEN_SESSION_PLAY) { playScreen(c,s,w,h); c.restore(); return; }
            // SCREEN_SEASON_DETAIL: header e barra tab in basso restano fissi, il contenuto in mezzo scorre.
            detailHeader(c,s,w);
            bodyTop=44; bodyBottom=h-58; // 44 e non 58: nel tab Deck la pillola "Ordina" parte da y=48
            resetScrollIfNeeded("detail:"+detailTab+":"+store.current);
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);
            if (detailTab==0) playTab(c,s,w,h);
            else if (detailTab==1) decks(c,s,w,h);
            else stats(c,s,w,h);
            c.restore();
            finishScroll(); drawScrollbar(c,w);
            detailNav(c,w,h);
            c.restore();
        }

        void seasonList(Canvas c, float w, float h){
            seasonHits.clear();
            txt(c,"Pocket Tracker",24,40,20,white,Paint.Align.LEFT);
            txt(c,"STAGIONI",24,74,12,muted,Paint.Align.LEFT);
            bodyTop=84; bodyBottom=h;
            resetScrollIfNeeded("seasonlist");
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);
            float y=92;
            for(int i=0;i<store.seasons.size();i++){
                Season s=store.seasons.get(i);
                boolean isCurrent = i==store.current;
                box(c,18,y,w-18,y+110, isCurrent?Color.rgb(20,44,80):card);
                if(isCurrent) txt(c,"ATTUALE",w-34,y+22,10,blue,Paint.Align.RIGHT);
                txt(c,s.name,34,y+28,18,white,Paint.Align.LEFT);
                int[] wl=countWL(s.matches); int W=wl[0],L=wl[1];
                float wr=(W+L)==0?0:100f*W/(W+L);
                txt(c,"Punti "+s.points+"   Vittorie consecutive "+s.streak,34,y+52,12,muted,Paint.Align.LEFT);
                txtRow(c,34,y+74,12,
                    new String[]{W+"W   ", L+"L   ", "WR "+String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
                txt(c,s.matches.size()+" partite",34,y+96,11,muted,Paint.Align.LEFT);
                seasonHits.add(new Hit(y,y+110,i));
                y+=120;
            }
            // Link discreto, ora parte del contenuto scrollabile (prima della prossima riga fissa): azione
            // distruttiva, quindi richiede sempre conferma esplicita (vedi resetAllData()).
            p.setTextSize(12); float resetTw=p.measureText("Cancella tutti i dati");
            strokeBox(c,w/2-(resetTw+32)/2,y+4,w/2+(resetTw+32)/2,y+36,Color.rgb(200,90,85));
            txt(c,"Cancella tutti i dati",w/2,y+24,12,Color.rgb(200,90,85),Paint.Align.CENTER);
            resetLinkY = y+24;
            lastContentBottom = y+60;
            c.restore();
            finishScroll(); drawScrollbar(c,w);
            // Pulsante "Nuova Stagione" in basso a destra (floating action button, sempre fisso): sempre
            // raggiungibile col pollice, non scorre via col resto del contenuto.
            box(c,w-166,h-104,w-18,h-54,blue); txt(c,"Nuova Stagione",w-92,h-73,14,white,Paint.Align.CENTER);
        }

        void detailHeader(Canvas c, Season s, float w){
            float centerY=28; // alzato: il testo arrivava quasi a toccare l'inizio del contenuto scrollabile (bodyTop=44)
            // Chevron "indietro" e matita, stesso colore (bianco) per tutte le icone dell'header.
            drawChevronBack(c,24,centerY,20,white);
            txt(c,s.name,44,centeredBaseline(centerY,20),20,white,Paint.Align.LEFT);
            p.setTextSize(20); float nameW=p.measureText(s.name);
            drawEditIcon(c,44+nameW+22,centerY,18,white);
        }

        void boxR(Canvas c,float l,float t,float rr,float b,float radius,int col){p.setColor(col);p.setStyle(Paint.Style.FILL);c.drawRoundRect(l,t,rr,b,radius,radius,p);}

        // ===== Tab "Gioca": card "Andamento Stagione" (punti, partite, grafico) + pulsante "Gioca" + card
        // "Partite" (raggruppata per giorno, ogni giorno e' una sottocard con intestazione data+statistiche
        // su sfondo leggermente diverso). Le correzioni (punti di partenza + correzioni manuali) restano nella
        // STESSA lista, nella loro posizione cronologica, ma con uno stile di riga distinto. =====
        void playTab(Canvas c, Season s, float w, float h){
            matchHits.clear();
            ArrayList<Match> all = s.matches; // gia' flat, una entita' di primo livello nella Season
            int[] wl=countWL(all); int W=wl[0],L=wl[1];
            float wr=(W+L)==0?0:100f*W/(W+L);

            // Colonne verticali del grafico aggregato: segnano un cambio di GIORNO.
            ArrayList<Integer> dayBoundaries = new ArrayList<>();
            String prevDay=null;
            for(int i=0;i<all.size();i++){
                String dk = dayKey(all.get(i).timestamp);
                if(prevDay!=null && !dk.equals(prevDay)) dayBoundaries.add(i);
                prevDay=dk;
            }

            // ===== Card "ANDAMENTO STAGIONE" =====
            box(c,18,58,w-18,342,card);
            txt(c,"ANDAMENTO STAGIONE",34,80,12,muted,Paint.Align.LEFT);
            float c1L=30, c1R=w/2-6, c2L=w/2+6, c2R=w-30;
            box(c,c1L,92,c1R,174,Color.rgb(10,18,30));
            float[] b1 = centerLines(133,6,11,26);
            txt(c,"PUNTI ATTUALI",(c1L+c1R)/2,b1[0],11,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,b1[1],26,white,Paint.Align.CENTER);
            box(c,c2L,92,c2R,174,Color.rgb(10,18,30));
            float[] b2 = centerLines(133,6,11,20,15);
            txt(c,"PARTITE TOTALI",(c2L+c2R)/2,b2[0],11,muted,Paint.Align.CENTER);
            txt(c,""+(W+L),(c2L+c2R)/2,b2[1],20,white,Paint.Align.CENTER);
            txtRowCentered(c,(c2L+c2R)/2,b2[2],15,
                new String[]{W+"W  ", L+"L  ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});
            drawChart(c,30,184,w-30,328,all,0,dayBoundaries);

            // ===== Pulsante "Gioca": entra nella schermata di registrazione partita. =====
            box(c,18,356,w-18,404,blue);
            drawBurstTabIcon(c,w/2-64,380,0.7f,white);
            txt(c,"Gioca",w/2+10,386,15,white,Paint.Align.CENTER);

            // ===== Card "PARTITE": altezza FISSA, lista scorrevole al suo interno con scrollbar propria. =====
            float listCardTop=418, listHeight=300;
            float listTop=listCardTop+42, listBottom=listTop+listHeight;
            float listCardBottom = listBottom+14;
            box(c,18,listCardTop,w-18,listCardBottom,card);
            txt(c,"PARTITE",34,listCardTop+26,12,muted,Paint.Align.LEFT);

            float headerH=32, rowH=64, groupGap=10;
            // Prima passata: conta quanti gruppi-giorno ci sono, per calcolare l'altezza totale del contenuto
            // (serve PRIMA di disegnare, per scrollbar e clip).
            int groupCount=0;
            { int idx=all.size()-1; while(idx>=0){ String dk=dayKey(all.get(idx).timestamp); int j=idx; while(j>=0 && dayKey(all.get(j).timestamp).equals(dk)) j--; groupCount++; idx=j; } }
            float totalRowsHeight = groupCount*(headerH+groupGap) + all.size()*rowH;
            resetMatchInnerScrollIfNeeded("matchinner:"+store.current);
            matchInnerMaxScrollY = Math.max(0, totalRowsHeight-listHeight);
            if(matchInnerScrollY>matchInnerMaxScrollY) matchInnerScrollY=matchInnerMaxScrollY;
            if(matchInnerScrollY<0) matchInnerScrollY=0;
            matchInnerListTop=listTop; matchInnerListBottom=listBottom;

            c.save(); c.clipRect(18,listTop,w-18,listBottom); c.translate(0,-matchInnerScrollY);
            float y=listTop+4;
            int i = all.size()-1;
            while(i>=0){
                String dk = dayKey(all.get(i).timestamp);
                int dayEndIdx = i;
                int j = i;
                while(j>=0 && dayKey(all.get(j).timestamp).equals(dk)) j--;
                int dayStartIdx = j+1;
                int dw=0, dl=0;
                for(int k=dayStartIdx;k<=dayEndIdx;k++){ Match m=all.get(k); if(!m.unknown){ if(m.win) dw++; else dl++; } }
                float dwr = (dw+dl)==0?0:100f*dw/(dw+dl);
                int rowsInGroup = dayEndIdx-dayStartIdx+1;
                float groupTop=y, groupBottom=y+headerH+rowsInGroup*rowH;

                // Sottocard del giorno: sfondo, poi fascia intestazione (data + W/L/% del giorno) su sfondo
                // piu' chiaro, cosi' la data non si perde piu' in mezzo alle righe.
                box(c,26,groupTop,w-26,groupBottom,Color.rgb(10,18,30));
                boxR(c,26,groupTop,w-26,groupTop+headerH,10,Color.rgb(21,34,56));
                txt(c, formatDateOnly(all.get(dayEndIdx).timestamp), 38, groupTop+20, 11, muted, Paint.Align.LEFT);
                txtRowRight(c,w-38,groupTop+20,11,
                    new String[]{dw+"W  ", dl+"L  ", String.format(Locale.US,"%.1f%%",dwr)},
                    new int[]{green, red, wrColor(dwr,dw+dl)});

                float ry = groupTop+headerH;
                for(int k=dayEndIdx;k>=dayStartIdx;k--){
                    Match m = all.get(k);
                    if(k!=dayEndIdx){ p.setColor(Color.rgb(20,30,46)); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE); c.drawLine(38,ry,w-38,ry,p); }
                    if(m.unknown){
                        // Correzione (punti di partenza se e' la primissima partita in assoluto, altrimenti
                        // correzione manuale successiva): niente deck ne' badge W/L, solo la variazione punti.
                        String title = (k==0) ? "Punti di partenza" : "Correzione manuale";
                        txt(c, title, 46,ry+26,15,white,Paint.Align.LEFT);
                        txt(c, formatTimeOnly(m.timestamp), 46,ry+48,12,muted,Paint.Align.LEFT);
                        int gain = m.after-m.before;
                        int gcol = gain>0?green:(gain<0?red:muted);
                        txt(c, (gain>0?"+":"")+gain, w-46, ry+26, 15, gcol, Paint.Align.RIGHT);
                        txt(c, m.before+" → "+m.after, w-46, ry+46, 11, muted, Paint.Align.RIGHT);
                    } else {
                        txt(c, deckDisplayShort(m.deck), 46,ry+26,15,white,Paint.Align.LEFT);
                        txt(c, "Partita "+(k+1)+"  •  "+formatTimeOnly(m.timestamp), 46,ry+48,12,muted,Paint.Align.LEFT);
                        txt(c, m.win?"W":"L", w-46, ry+26, 15, m.win?green:red, Paint.Align.RIGHT);
                        int gain = m.after-m.before;
                        int gcol = gain>0?green:(gain<0?red:muted);
                        txt(c, (gain>0?"+":"")+gain, w-46, ry+48, 12, gcol, Paint.Align.RIGHT);
                    }
                    matchHits.add(new Hit(ry,ry+rowH,k));
                    ry+=rowH;
                }
                y = groupBottom+groupGap;
                i = dayStartIdx-1;
            }
            c.restore();

            if(matchInnerMaxScrollY>1){
                float thumbH = Math.max(24, listHeight*(listHeight/totalRowsHeight));
                float thumbY = listTop + (listHeight-thumbH)*(matchInnerScrollY/matchInnerMaxScrollY);
                p.setColor(Color.rgb(45,60,85)); p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(w-22,thumbY,w-19,thumbY+thumbH,1.5f,1.5f,p);
            }

            lastContentBottom = listCardBottom+20;
        }

        int deckSortMode = 0; // 0=Win rate, 1=Games, 2=Best streak, 3=Name — condiviso tra tab Deck e Statistiche
        boolean deckSortAsc = false; // false = decrescente (default), true = crescente

        String deckSortLabel(){
            if (deckSortMode==3) return "Nome "+(deckSortAsc?"A→Z":"Z→A");
            String name = deckSortMode==0?"Win rate":deckSortMode==1?"Partite":"Miglior serie";
            return name+" "+(deckSortAsc?"↑":"↓");
        }
        // Menu a tendina con tutte le combinazioni criterio+direzione (invece di ciclare "alla cieca").
        void showDeckSortMenu(){
            String[] items = {
                "Win rate (dal più alto)","Win rate (dal più basso)",
                "Partite (dal più alto)","Partite (dal più basso)",
                "Miglior serie (dal più alto)","Miglior serie (dal più basso)",
                "Nome (A→Z)","Nome (Z→A)"
            };
            int[] modes = {0,0,1,1,2,2,3,3};
            boolean[] ascs = {false,true,false,true,false,true,true,false};
            new AlertDialog.Builder(MainActivity.this).setTitle("Ordina deck per").setItems(items,(d,which)->{
                deckSortMode = modes[which]; deckSortAsc = ascs[which];
                view.invalidate();
            }).show();
        }

        int[] deckWL(Season s, String name){
            int w=0,l=0; for(Match m: s.matches) if(name.equals(m.deck) && !m.unknown){ if(m.win) w++; else l++; }
            return new int[]{w,l};
        }

        // Statistiche aggregate (W/L) delle partite senza un deck assegnato ("Unknown") in questa Stagione:
        // ora e' semplicemente deckWL con nome "Unknown", visto che non c'e' piu' un caso speciale.
        int[] noDeckWL(Season s){ return deckWL(s, "Unknown"); }

        // Variazione netta di punti portata da un deck (o dalle partite senza deck assegnato, con name="Unknown"):
        // somma di (dopo-prima) su tutte le partite che usano quel deck.
        int deckGain(Season s, String name){
            int total=0;
            for(Match m: s.matches) if(name.equals(m.deck)) total += (m.after-m.before);
            return total;
        }

        // Longest win streak *davvero attribuibile* a un deck: attraversa la cronologia in ordine e mantiene
        // una serie SOLO finche' le vittorie consecutive sono state giocate tutte con questo stesso deck.
        // Una sconfitta, una vittoria con un deck diverso, o una correzione manuale interrompono la serie.
        int longestStreakForDeck(Season s, String deckName){
            int best=0, run=0;
            for(Match m: s.matches){
                if(m.unknown){ run=0; continue; }
                boolean thisDeck = deckName.equals(m.deck);
                if(m.win){ if(thisDeck){ run++; best=Math.max(best,run); } else run=0; }
                else run=0;
            }
            return best;
        }

        // Ordina i Deck della Season secondo deckSortMode/deckSortAsc (condivisi tra tab Deck e Statistiche).
        ArrayList<Deck> sortedDecks(Season s){
            ArrayList<Deck> list = new ArrayList<>(s.decks);
            Collections.sort(list, (a,b) -> {
                int cmp;
                if (deckSortMode==3) cmp = a.name.compareToIgnoreCase(b.name);
                else {
                    int[] wa=deckWL(s,a.name), wb=deckWL(s,b.name);
                    if (deckSortMode==1) cmp = Integer.compare(wa[0]+wa[1], wb[0]+wb[1]); // games
                    else if (deckSortMode==2) cmp = Integer.compare(longestStreakForDeck(s,a.name), longestStreakForDeck(s,b.name)); // best streak
                    else { // win rate — i deck senza partite finiscono sempre in fondo
                        float ra=(wa[0]+wa[1])==0?-1:100f*wa[0]/(wa[0]+wa[1]);
                        float rb=(wb[0]+wb[1])==0?-1:100f*wb[0]/(wb[0]+wb[1]);
                        cmp = Float.compare(ra, rb);
                    }
                }
                return deckSortAsc ? cmp : -cmp;
            });
            return list;
        }

        // Card di un deck: usata SIA nel tab Deck SIA nella sezione "DECK" delle Statistiche, cosi'
        // l'aspetto e' identico in entrambi i posti. Ritorna la nuova posizione y dopo aver disegnato la card.
        float deckCard(Canvas c, Deck deckObj, String name, boolean isUnknown, int W, int L, int best, int gain, float y, float w, boolean showDelete){
            // Sfondo piu' scuro della card che lo contiene, margine ai lati coerente con le righe delle partite.
            box(c,30,y,w-30,y+92,Color.rgb(10,18,30));
            // Anteprima automatica (ritagliata dalla prima immagine caricata, se presente): sposta il testo
            // a destra per farle spazio. Cornice grigio-blu dietro l'immagine (con un piccolo margine, "matting").
            Bitmap preview = deckObj!=null ? getDeckPreview(deckObj) : null;
            float textX = 46;
            if (preview!=null) {
                float thumbW=64, thumbH=80, thumbX=40, thumbY=y+6, pad=3;
                box(c, thumbX, thumbY, thumbX+thumbW, thumbY+thumbH, Color.rgb(58,74,94));
                float imgL=thumbX+pad, imgT=thumbY+pad, imgR=thumbX+thumbW-pad, imgB=thumbY+thumbH-pad;
                // Ritaglio "a copertura" (come object-fit:cover), non uno stiramento.
                float dstAspect = (imgR-imgL)/(imgB-imgT);
                int pw=preview.getWidth(), ph=preview.getHeight();
                float srcAspect = (float)pw/ph;
                int srcL,srcT,srcR,srcB;
                if (srcAspect > dstAspect) {
                    int srcW = Math.round(ph*dstAspect);
                    srcL=(pw-srcW)/2; srcR=srcL+srcW; srcT=0; srcB=ph;
                } else {
                    int srcH = Math.round(pw/dstAspect);
                    srcT=(ph-srcH)/2; srcB=srcT+srcH; srcL=0; srcR=pw;
                }
                android.graphics.Rect src = new android.graphics.Rect(srcL,srcT,srcR,srcB);
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new android.graphics.RectF(imgL,imgT,imgR,imgB), 5,5, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                android.graphics.Rect dst = new android.graphics.Rect((int)imgL,(int)imgT,(int)imgR,(int)imgB);
                c.drawBitmap(preview, src, dst, null);
                c.restore();
                textX = thumbX+thumbW+14;
            }
            txt(c, isUnknown?"Deck sconosciuto":name, textX,y+26,17, isUnknown?muted:white, Paint.Align.LEFT);
            float wr=(W+L)==0?0:100f*W/(W+L);
            txt(c,(W+L)+" partite",textX,y+46,12, isUnknown?muted:white, Paint.Align.LEFT);
            if (!isUnknown && showDelete) {
                Bitmap trash = makeTrashIcon(muted, 64);
                float ts=16, tx=w-30-10-ts, ty=y+8;
                android.graphics.Rect dst = new android.graphics.Rect((int)tx, (int)ty, (int)(tx+ts), (int)(ty+ts));
                c.drawBitmap(trash, null, dst, null);
            }
            if (isUnknown) {
                txtRow(c,textX,y+64,11,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
            } else {
                txtRow(c,textX,y+64,11,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)+"   ", "Max vittorie consecutive "+best},
                    new int[]{green, red, wrColor(wr,W+L), muted});
            }
            int gcol = gain>0?green:(gain<0?red:muted);
            txt(c, "Variazione: "+(gain>0?"+":"")+gain, textX, y+82, 11, gcol, Paint.Align.LEFT);
            return y+104;
        }

        void decks(Canvas c,Season s,float w,float h){
            box(c,w-165,48,w-18,76,card); txt(c,"Ordina: "+deckSortLabel()+" ▾",w-91,68,13,white,Paint.Align.CENTER);
            float y=90;
            for(Deck d: sortedDecks(s)){
                int[] wl=deckWL(s,d.name); int best=longestStreakForDeck(s,d.name); int gain=deckGain(s,d.name);
                y = deckCard(c, d, d.name, false, wl[0], wl[1], best, gain, y, w, true);
            }
            int[] nd = noDeckWL(s);
            if (nd[0]+nd[1] > 0) {
                int ndbest=longestStreakForDeck(s,"Unknown"); int ndgain=deckGain(s,"Unknown");
                y = deckCard(c, null, null, true, nd[0], nd[1], ndbest, ndgain, y, w, true);
            }
            box(c,18,y,w-18,y+58,blue);txt(c,"AGGIUNGI DECK",w/2,y+37,14,white,Paint.Align.CENTER);
            lastContentBottom = y+58+20;
        }

        void stats(Canvas c,Season s,float w,float h){
            ArrayList<Match> all=s.matches; int maxStreak=s.initialStreak,cur= s.initialStreak;
            for(Match m:all){if(m.unknown)continue;if(m.win){cur++;maxStreak=Math.max(maxStreak,cur);}else{cur=0;}}
            int[] wl=countWL(all); int W=wl[0],L=wl[1];
            float wr=(W+L)==0?0:100f*W/(W+L);
            int gain = s.points-s.baseline;
            ArrayList<Deck> sd = sortedDecks(s);
            int[] nd = noDeckWL(s);
            int deckPlayedCount = sd.size() + (nd[0]+nd[1]>0 ? 1 : 0);

            box(c,18,58,w-18,358,card);
            txt(c,"STATISTICHE STAGIONE",34,80,12,muted,Paint.Align.LEFT);
            float c1L=30, c1R=w/2-6, c2L=w/2+6, c2R=w-30;
            box(c,c1L,92,c1R,152,Color.rgb(10,18,30));
            box(c,c2L,92,c2R,152,Color.rgb(10,18,30));
            float[] r1 = centerLines(122,6,10,22);
            txt(c,"PUNTI",(c1L+c1R)/2,r1[0],10,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,r1[1],22,white,Paint.Align.CENTER);
            txt(c,"VARIAZIONE",(c2L+c2R)/2,r1[0],10,muted,Paint.Align.CENTER);
            txt(c, (gain>0?"+":"")+gain,(c2L+c2R)/2,r1[1],22, gain>0?green:(gain<0?red:white),Paint.Align.CENTER);
            box(c,c1L,160,c1R,220,Color.rgb(10,18,30));
            box(c,c2L,160,c2R,220,Color.rgb(10,18,30));
            float[] r2 = centerLines(190,6,10,22);
            txt(c,"PARTITE TOTALI",(c1L+c1R)/2,r2[0],10,muted,Paint.Align.CENTER);
            txt(c,""+(W+L),(c1L+c1R)/2,r2[1],22,white,Paint.Align.CENTER);
            txt(c,"W / L / %",(c2L+c2R)/2,r2[0],10,muted,Paint.Align.CENTER);
            txtRowCentered(c,(c2L+c2R)/2,r2[1],15,
                new String[]{W+"W  ", L+"L  ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});
            box(c,c1L,228,c1R,288,Color.rgb(10,18,30));
            float[] r3a = centerLines(258,6,10,22);
            txt(c,"VITTORIE CONSECUTIVE",(c1L+c1R)/2,r3a[0],10,muted,Paint.Align.CENTER);
            txt(c,""+s.streak,(c1L+c1R)/2,r3a[1],22,white,Paint.Align.CENTER);
            box(c,c2L,228,c2R,288,Color.rgb(10,18,30));
            float[] r3b = centerLines(258,6,10,22);
            txt(c,"MASSIME",(c2L+c2R)/2,r3b[0],10,muted,Paint.Align.CENTER);
            txt(c,""+maxStreak,(c2L+c2R)/2,r3b[1],22,white,Paint.Align.CENTER);
            String mostPlayedName = "-"; int mostPlayedCount = 0;
            for(Deck d: sd){
                int[] dwl = deckWL(s,d.name); int total=dwl[0]+dwl[1];
                if(total>mostPlayedCount){ mostPlayedCount=total; mostPlayedName=d.name; }
            }
            if(nd[0]+nd[1]>mostPlayedCount){ mostPlayedCount=nd[0]+nd[1]; mostPlayedName="Deck sconosciuto"; }
            box(c,c1L,296,c1R,344,Color.rgb(10,18,30));
            float[] r4 = centerLines(320,6,10,16);
            txt(c,"DECK GIOCATI",(c1L+c1R)/2,r4[0],10,muted,Paint.Align.CENTER);
            txt(c,""+deckPlayedCount,(c1L+c1R)/2,r4[1],16,white,Paint.Align.CENTER);
            box(c,c2L,296,c2R,344,Color.rgb(10,18,30));
            txt(c,"DECK PIU' GIOCATO",(c2L+c2R)/2,r4[0],10,muted,Paint.Align.CENTER);
            txt(c,mostPlayedName,(c2L+c2R)/2,r4[1],16,white,Paint.Align.CENTER);

            float deckCardTop=374, deckListHeight=300;
            float deckListTop=deckCardTop+52, deckListBottom=deckListTop+deckListHeight;
            float deckCardBottom = deckListBottom+14;
            box(c,18,deckCardTop,w-18,deckCardBottom,card);
            box(c,w-165,deckCardTop+12,w-18,deckCardTop+40,Color.rgb(10,18,30)); txt(c,"Ordina: "+deckSortLabel()+" ▾",w-91,deckCardTop+32,13,white,Paint.Align.CENTER);
            txt(c,"DECK",34,deckCardTop+31,12,muted,Paint.Align.LEFT);

            resetStatsDeckInnerScrollIfNeeded("statsdeckinner:"+store.current);
            float totalDeckRowsHeight = deckPlayedCount*104f;
            statsDeckInnerMaxScrollY = Math.max(0, totalDeckRowsHeight-deckListHeight);
            if(statsDeckInnerScrollY>statsDeckInnerMaxScrollY) statsDeckInnerScrollY=statsDeckInnerMaxScrollY;
            if(statsDeckInnerScrollY<0) statsDeckInnerScrollY=0;
            statsDeckInnerListTop=deckListTop; statsDeckInnerListBottom=deckListBottom;

            c.save(); c.clipRect(18,deckListTop,w-18,deckListBottom); c.translate(0,-statsDeckInnerScrollY);
            float dyy=deckListTop;
            for(Deck d: sd){
                int[] dwl=deckWL(s,d.name); int dbest=longestStreakForDeck(s,d.name); int dgain=deckGain(s,d.name);
                dyy = deckCard(c, d, d.name, false, dwl[0], dwl[1], dbest, dgain, dyy, w, false);
            }
            if (nd[0]+nd[1] > 0) {
                int ndbest=longestStreakForDeck(s,"Unknown"); int ndgain=deckGain(s,"Unknown");
                dyy = deckCard(c, null, null, true, nd[0], nd[1], ndbest, ndgain, dyy, w, false);
            }
            c.restore();

            if(statsDeckInnerMaxScrollY>1){
                float thumbH = Math.max(24, deckListHeight*(deckListHeight/totalDeckRowsHeight));
                float thumbY = deckListTop + (deckListHeight-thumbH)*(statsDeckInnerScrollY/statsDeckInnerMaxScrollY);
                p.setColor(Color.rgb(45,60,85)); p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(w-22,thumbY,w-19,thumbY+thumbH,1.5f,1.5f,p);
            }

            lastContentBottom = deckCardBottom+20;
        }

        // ===== Schermata di registrazione partita: deck attuale, punti/streak, W/L, annulla. Niente piu'
        // grafico qui (non ha senso su una singola partita): resta solo nella card "Andamento Stagione". =====
        void playScreen(Canvas c, Season s, float w, float h){
            float centerY=36;
            drawChevronBack(c,24,centerY,20,white);
            String deckTitle = ("Unknown".equals(s.currentDeck) || s.currentDeck==null) ? "Deck sconosciuto" : s.currentDeck;
            txt(c, deckTitle, 44, 30, 19, white, Paint.Align.LEFT);
            txt(c, "Partita "+(s.matches.size()+1), 44, 48, 12, muted, Paint.Align.LEFT);
            drawKebabIcon(c,w-24,centerY,white);

            bodyTop=58; bodyBottom=h;
            resetScrollIfNeeded("play:"+store.current);
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);

            float gap2=8; float colW=(w-36-gap2)/2;
            float c1L=18, c1R=18+colW, c2L=c1R+gap2, c2R=w-18;
            box(c,c1L,66,c1R,140,card); box(c,c2L,66,c2R,140,card);
            float[] g1 = centerLines(103,6,11,26);
            txt(c,"PUNTI ATTUALI",(c1L+c1R)/2,g1[0],11,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,g1[1],26,white,Paint.Align.CENTER);
            txt(c,"VITTORIE CONSECUTIVE",(c2L+c2R)/2,g1[0],11,muted,Paint.Align.CENTER);
            txt(c,""+s.streak,(c2L+c2R)/2,g1[1],26,white,Paint.Align.CENTER);

            float gL=18, gR=w/2-8, rL=w/2+8, rR=w-18;
            box(c,gL,148,gR,212,green); box(c,rL,148,rR,212,red);
            float[] wl2 = centerLines(180,6,22,13);
            txt(c,"W",(gL+gR)/2,wl2[0],22,Color.WHITE,Paint.Align.CENTER); txt(c,"(+"+reward(s.streak+1)+")",(gL+gR)/2,wl2[1],13,Color.WHITE,Paint.Align.CENTER);
            txt(c,"L",(rL+rR)/2,wl2[0],22,Color.WHITE,Paint.Align.CENTER); txt(c,"(−10)",(rL+rR)/2,wl2[1],13,Color.WHITE,Paint.Align.CENTER);

            box(c,18,224,w-18,270,card);
            drawUndoIcon(c,w/2-52,247,16,white);
            txt(c,"ANNULLA ULTIMA PARTITA",w/2+18,253,15,white,Paint.Align.CENTER);

            lastContentBottom = 270+20;
            c.restore();
            finishScroll(); drawScrollbar(c,w);
        }

        void drawChart(Canvas c,float l,float t,float rr,float b,List<Match> ms,long unusedTimestamp,List<Integer> dayBoundaries){
            box(c,l,t,rr,b,Color.rgb(10,18,30));
            int gridColor = Color.rgb(26,38,56);
            p.setColor(gridColor); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE);
            int gridLines=3;
            for(int i=1;i<=gridLines;i++){
                float gy = t+22 + i*(b-t-42)/(gridLines+1);
                c.drawLine(l+8,gy,rr-8,gy,p);
            }
            if(ms.isEmpty()){
                txt(c,"Nessuna partita ancora",(l+rr)/2,(t+b)/2,13,muted,Paint.Align.CENTER);
                return;
            }
            float min=ms.get(0).before,max=ms.get(0).before;for(Match m:ms){min=Math.min(min,m.after);max=Math.max(max,m.after);}
            min-=20;max+=20;if(max==min)max=min+1;
            int n=ms.size();

            // Colonne verticali SOLO in corrispondenza di un cambio di giorno: prima ce n'era una per OGNI
            // partita, troppo fitte e confuse visivamente.
            if(dayBoundaries!=null && !dayBoundaries.isEmpty()){
                p.setColor(Color.rgb(52,68,96)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
                for(int idx: dayBoundaries){
                    float gx = l+12+(idx+1)*(rr-l-24)/Math.max(1,n);
                    c.drawLine(gx,t+8,gx,b-8,p);
                }
            }

            p.setStrokeWidth(2);
            float prevX=l+12, prevY=b-20-(ms.get(0).before-min)/(max-min)*(b-t-36);
            float startX=prevX, startY=prevY;
            for(int i=0;i<ms.size();i++){
                Match m=ms.get(i);float x=l+12+(i+1)*(rr-l-24)/Math.max(1,ms.size());
                float y=b-20-(m.after-min)/(max-min)*(b-t-36);
                p.setColor(m.unknown?Color.GRAY:(m.win?green:red));p.setStyle(Paint.Style.STROKE);c.drawLine(prevX,prevY,x,y,p);prevX=x;prevY=y;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(muted); c.drawCircle(startX,startY,4,p);
            for(int i=0;i<ms.size();i++){
                Match m=ms.get(i);float x=l+12+(i+1)*(rr-l-24)/Math.max(1,ms.size());
                float y=b-20-(m.after-min)/(max-min)*(b-t-36);
                p.setColor(m.unknown?Color.GRAY:(m.win?green:red));
                c.drawCircle(x,y,4,p);
            }
            txt(c,"INIZIO: "+ms.get(0).before,l+10,t+34,12,white,Paint.Align.LEFT);
        }

        // Icona "scoppio" per il tab Gioca: stessa forma esatta usata nell'icona dell'app (solo riscalata).
        void drawBurstTabIcon(Canvas c, float cx, float cy, float scale, int color){
            float[][] pts = {
                {-10,0.91f},{-5.45f,-1.82f},{-7.27f,-6.36f},{-1.82f,-4.55f},{0,-10},
                {2.73f,-5.45f},{8.18f,-7.27f},{5.45f,-1.82f},{10,0.91f},{4.55f,1.82f},
                {6.36f,7.27f},{0.91f,4.55f},{-0.91f,10},{-2.73f,4.55f},{-8.18f,6.36f},{-6.36f,0.91f}
            };
            android.graphics.Path path = new android.graphics.Path();
            for(int i=0;i<pts.length;i++){
                float px=cx+pts[i][0]*scale, py=cy+pts[i][1]*scale;
                if(i==0) path.moveTo(px,py); else path.lineTo(px,py);
            }
            path.close();
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            c.drawPath(path,p);
        }
        // Icona "carta" per il tab Deck.
        void drawCardTabIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f);
            c.drawRoundRect(cx-6*s,cy-9*s,cx+6*s,cy+9*s,2.5f*s,2.5f*s,p);
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(cx-3.5f*s,cy-6*s,cx+3.5f*s,cy-1*s,1*s,1*s,p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f);
            c.drawLine(cx-3.5f*s,cy+2*s,cx+3.5f*s,cy+2*s,p);
            c.drawLine(cx-3.5f*s,cy+5*s,cx+1*s,cy+5*s,p);
        }
        // Icona "grafico a barre" per il tab Stats.
        void drawStatsTabIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(cx-8*s,cy+1*s,cx-4*s,cy+8*s,1*s,1*s,p);
            c.drawRoundRect(cx-2*s,cy-4*s,cx+2*s,cy+8*s,1*s,1*s,p);
            c.drawRoundRect(cx+4*s,cy-8*s,cx+8*s,cy+8*s,1*s,1*s,p);
        }

        void detailNav(Canvas c,float w,float h){
            float y=h-58; p.setColor(Color.rgb(9,15,25));p.setStyle(Paint.Style.FILL);c.drawRect(0,y,w,h,p);
            String[] n={"Gioca","Deck","Stats"};
            for(int i=0;i<3;i++){
                int col=i==detailTab?blue:muted;
                float cx=w*(i+.5f)/3, iconCy=y+18;
                if(i==0) drawBurstTabIcon(c,cx,iconCy,0.85f,col);
                else if(i==1) drawCardTabIcon(c,cx,iconCy,18,col);
                else drawStatsTabIcon(c,cx,iconCy,18,col);
                txt(c,n[i],cx,y+48,12,col,Paint.Align.CENTER);
            }
        }

        float touchDownX=0, touchDownY=0, touchStartScrollY=0, touchStartInnerScrollY=0; boolean isDragging=false, isDraggingInner=false;
        int innerDragTarget=0; // 0=nessuno, 1=lista partite (tab Gioca), 2=lista deck (tab Statistiche)

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            // Le coordinate del tocco arrivano in pixel reali dell'intera View: le convertiamo nello stesso
            // sistema "dp con origine sotto la status bar" usato in onDraw, altrimenti i tap non
            // corrisponderebbero piu' a quello che e' disegnato sullo schermo.
            float x=(e.getX()-getPaddingLeft())/density, y=(e.getY()-getPaddingTop())/density;
            float w=(getWidth()-getPaddingLeft()-getPaddingRight())/density;
            float h=(getHeight()-getPaddingTop()-getPaddingBottom())/density;

            if(e.getAction()==MotionEvent.ACTION_DOWN){
                touchDownX=x; touchDownY=y; touchStartScrollY=scrollY;
                isDragging=false; isDraggingInner=false; innerDragTarget=0;
                boolean overMatches = screen==SCREEN_SEASON_DETAIL && detailTab==0 && matchInnerMaxScrollY>0
                    && y>=(matchInnerListTop-scrollY) && y<=(matchInnerListBottom-scrollY) && x>=18 && x<=w-18;
                boolean overStatsDeck = screen==SCREEN_SEASON_DETAIL && detailTab==2 && statsDeckInnerMaxScrollY>0
                    && y>=(statsDeckInnerListTop-scrollY) && y<=(statsDeckInnerListBottom-scrollY) && x>=18 && x<=w-18;
                if(overMatches){ innerDragTarget=1; touchStartInnerScrollY=matchInnerScrollY; }
                else if(overStatsDeck){ innerDragTarget=2; touchStartInnerScrollY=statsDeckInnerScrollY; }
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                float dy = touchDownY - y;
                if(Math.abs(dy)>8){
                    if(innerDragTarget!=0) isDraggingInner=true;
                    else if(touchDownY>=bodyTop && touchDownY<=bodyBottom) isDragging=true;
                }
                if(isDraggingInner){
                    if(innerDragTarget==1){
                        matchInnerScrollY = touchStartInnerScrollY + dy;
                        if(matchInnerScrollY<0) matchInnerScrollY=0; if(matchInnerScrollY>matchInnerMaxScrollY) matchInnerScrollY=matchInnerMaxScrollY;
                    } else if(innerDragTarget==2){
                        statsDeckInnerScrollY = touchStartInnerScrollY + dy;
                        if(statsDeckInnerScrollY<0) statsDeckInnerScrollY=0; if(statsDeckInnerScrollY>statsDeckInnerMaxScrollY) statsDeckInnerScrollY=statsDeckInnerMaxScrollY;
                    }
                    invalidate();
                } else if(isDragging){
                    scrollY = touchStartScrollY + dy;
                    if(scrollY<0) scrollY=0; if(scrollY>maxScrollY) scrollY=maxScrollY;
                    invalidate();
                }
                return true;
            }
            if(e.getAction()!=MotionEvent.ACTION_UP) return true;
            if(isDragging || isDraggingInner){ isDragging=false; isDraggingInner=false; innerDragTarget=0; return true; }

            float contentY = (y>=bodyTop && y<=bodyBottom) ? y+scrollY : y;

            if(screen==SCREEN_SEASON_LIST){
                if(y>=h-104 && y<=h-54 && x>=w-166){ newSeason(); return true; }
                p.setTextSize(12); float resetTw=p.measureText("Cancella tutti i dati");
                if(contentY>=resetLinkY-16 && contentY<=resetLinkY+16 && x>=w/2-(resetTw+32)/2 && x<=w/2+(resetTw+32)/2){ resetAllData(); return true; }
                for(Hit hit: seasonHits){ if(contentY>=hit.top&&contentY<=hit.bottom){ store.current=hit.index; screen=SCREEN_SEASON_DETAIL; detailTab=0; store.save(); invalidate(); return true; } }
                return true;
            }

            Season s = store.seasons.get(store.current);

            if(screen==SCREEN_SESSION_PLAY){
                if(y<52){
                    if(x<60){ goBack(); return true; }
                    if(x>w-56){ playScreenKebabMenu(w-18, 52); return true; }
                    return true;
                }
                if(contentY>=148 && contentY<=212){ if(x<w/2) win(); else loss(); return true; }
                if(contentY>=224 && contentY<=270){ confirmUndo(); return true; }
                return true;
            }

            if(y<52){
                if(x<60){ goBack(); return true; }
                p.setTextSize(20); float nameW=p.measureText(s.name);
                if(x>=44+nameW+8){ renameSeason(); return true; }
                return true;
            }
            if(y>h-58){ detailTab=Math.min(2,(int)(x/(w/3))); invalidate(); return true; }
            if(detailTab==0){
                if(contentY>=356&&contentY<=404){
                    screen=SCREEN_SESSION_PLAY; invalidate();
                    return true;
                }
                float matchContentY = contentY + matchInnerScrollY;
                for(Hit hit: matchHits){ if(matchContentY>=hit.top&&matchContentY<=hit.bottom){ Match tapped=s.matches.get(hit.index); if(!tapped.unknown) changeMatchDeck(tapped); return true; } }
            } else if(detailTab==1){
                if(contentY>=48&&contentY<=76&&x>=w-165){ showDeckSortMenu(); return true; }
                float yy=90;
                for(Deck d: sortedDecks(s)){
                    if(contentY>=yy&&contentY<=yy+40&&x>=w-70){ confirmDeleteDeck(s,d); return true; }
                    if(contentY>=yy&&contentY<=yy+92){ openDeckImages(d); return true; }
                    yy+=104;
                }
                int[] nd = noDeckWL(s);
                if (nd[0]+nd[1] > 0) yy+=104;
                if(contentY>=yy&&contentY<=yy+58){ addDeck(); return true; }
            } else if(detailTab==2){
                float deckCardTop=374;
                if(contentY>=deckCardTop+12&&contentY<=deckCardTop+40&&x>=w-165){ showDeckSortMenu(); return true; }
                float statsDeckContentY = contentY + statsDeckInnerScrollY;
                float dyy=deckCardTop+52;
                for(Deck d: sortedDecks(s)){
                    if(statsDeckContentY>=dyy&&statsDeckContentY<=dyy+92){ openDeckImages(d); return true; }
                    dyy+=104;
                }
            }
            return true;
        }
    }

    static class Match {
        boolean win,unknown;int before,after,streak;long timestamp;String deck;
        Match(boolean w,int b,int a,int st,String deck){win=w;before=b;after=a;streak=st;timestamp=System.currentTimeMillis();this.deck=deck;}
        static Match correction(int b,int a,String deck){Match m=new Match(a>=b,b,a,0,deck);m.unknown=true;return m;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("w",win);o.put("u",unknown);o.put("b",before);o.put("a",after);o.put("s",streak);o.put("ts",timestamp);o.put("dk",deck!=null?deck:"Unknown");return o;}
        static Match from(JSONObject o)throws Exception{Match m=new Match(o.getBoolean("w"),o.getInt("b"),o.getInt("a"),o.optInt("s",0),o.optString("dk","Unknown"));m.unknown=o.optBoolean("u",false);m.timestamp=o.optLong("ts",0);return m;}
    }
    static class Deck {
        String name; ArrayList<String> images=new ArrayList<>(); Deck(String n){name=n;}
        // Cache dell'anteprima ritagliata automaticamente (non salvata su disco, ricalcolata al bisogno):
        // evita di ridecodificare/ritagliare l'immagine ad ogni singolo ridisegno.
        transient Bitmap cachedPreview; transient String cachedPreviewSourceUri;
        JSONObject json()throws Exception{
            JSONObject o=new JSONObject(); o.put("n",name);
            JSONArray imgs=new JSONArray(); for(String i:images) imgs.put(i); o.put("imgs",imgs);
            return o;
        }
        static Deck from(JSONObject o){
            Deck d=new Deck(o.optString("n"));
            JSONArray imgs=o.optJSONArray("imgs");
            if(imgs!=null) for(int i=0;i<imgs.length();i++) d.images.add(imgs.optString(i));
            else { String legacy=o.optString("i",null); if(legacy!=null) d.images.add(legacy); } // dati salvati dalla vecchia versione (un solo screenshot)
            return d;
        }
    }
    static class Season {
        String name;int baseline,initialStreak,points,streak;String currentDeck="Unknown";
        ArrayList<Deck> decks=new ArrayList<>();ArrayList<Match> matches=new ArrayList<>();
        Season(String n){name=n;}
        JSONObject json()throws Exception{
            JSONObject o=new JSONObject();o.put("n",name);o.put("b",baseline);o.put("is",initialStreak);o.put("p",points);o.put("s",streak);o.put("cd",currentDeck);
            JSONArray d=new JSONArray();for(Deck x:decks)d.put(x.json());o.put("d",d);
            JSONArray mm=new JSONArray();for(Match x:matches)mm.put(x.json());o.put("matches",mm);
            return o;
        }
        static Season from(JSONObject o)throws Exception{
            Season s=new Season(o.optString("n"));
            s.baseline=o.optInt("b");s.initialStreak=o.optInt("is");s.points=o.optInt("p");s.streak=o.optInt("s");
            s.currentDeck=o.optString("cd","Unknown");
            JSONArray d=o.optJSONArray("d");if(d!=null)for(int i=0;i<d.length();i++)s.decks.add(Deck.from(d.getJSONObject(i)));
            JSONArray mm=o.optJSONArray("matches");
            if(mm!=null){
                for(int i=0;i<mm.length();i++) s.matches.add(Match.from(mm.getJSONObject(i)));
            } else {
                // Dati salvati con lo schema VECCHIO (a sessioni): li appiattiamo in partite singole, ognuna
                // eredita il deck della sessione a cui apparteneva.
                JSONArray ss=o.optJSONArray("ss");
                if(ss!=null){
                    for(int i=0;i<ss.length();i++){
                        JSONObject so=ss.getJSONObject(i);
                        String deck=so.optString("d","Unknown");
                        JSONArray mArr=so.optJSONArray("m");
                        if(mArr!=null) for(int j=0;j<mArr.length();j++){
                            JSONObject mo=mArr.getJSONObject(j);
                            Match m=Match.from(mo);
                            if(m.deck==null || m.deck.isEmpty() || "Unknown".equals(m.deck)) m.deck=deck;
                            s.matches.add(m);
                        }
                    }
                }
                if(!s.matches.isEmpty()){
                    Match last=s.matches.get(s.matches.size()-1);
                    // Il vecchio schema teneva punti/streak sulla Season aggiornati man mano: gia' corretti in 'p'/'s'.
                }
            }
            return s;
        }
    }
    static class Store {
        SharedPreferences pref;ArrayList<Season> seasons=new ArrayList<>();int current=0;
        Store(Context c){pref=c.getSharedPreferences("tracker",0);load();}
        void save(){try{JSONObject o=new JSONObject();JSONArray a=new JSONArray();for(Season s:seasons)a.put(s.json());o.put("seasons",a);o.put("current",current);pref.edit().putString("data",o.toString()).apply();}catch(Exception e){Log.e(TAG,"Errore nel salvataggio dati",e);}}
        void load(){try{String z=pref.getString("data",null);if(z==null)return;JSONObject o=new JSONObject(z);current=o.optInt("current");JSONArray a=o.optJSONArray("seasons");if(a!=null)for(int i=0;i<a.length();i++)seasons.add(Season.from(a.getJSONObject(i)));boolean changed=clearFallbackTimestamps();save_if(changed);}catch(Exception e){Log.e(TAG,"Errore nel caricamento dati, si riparte da zero",e);}}
        void save_if(boolean changed){ if(changed) save(); }
        // Migrazione: pulisce i timestamp "fallback" rimasti da PRIMA della correzione (partite caricate
        // quando il campo non esisteva ancora ricevevano l'ora di caricamento come stima, finendo tutte con
        // lo stesso identico valore). Un timestamp genuino non puo' coincidere al millisecondo con un altro:
        // se piu' partite condividono esattamente lo stesso valore, e' quasi certamente un fallback vecchio.
        boolean clearFallbackTimestamps(){
            java.util.HashMap<Long,Integer> counts = new java.util.HashMap<>();
            for(Season s: seasons) for(Match m: s.matches) if(m.timestamp>0) counts.merge(m.timestamp,1,Integer::sum);
            boolean changed=false;
            for(Season s: seasons) for(Match m: s.matches){
                Integer cnt = counts.get(m.timestamp);
                if(m.timestamp>0 && cnt!=null && cnt>1){ m.timestamp=0; changed=true; }
            }
            return changed;
        }
    }
}
