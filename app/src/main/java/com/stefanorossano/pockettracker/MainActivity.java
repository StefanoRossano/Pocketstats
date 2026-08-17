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
    static final int SCREEN_SEASON_DETAIL = 1; // Dettaglio Season: tab Sessioni / Deck / Statistiche
    static final int SCREEN_SESSION_PLAY = 2;  // Sessione attiva (W/L, undo/redo, grafico)

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

    /** Naviga di un livello indietro nella gerarchia Lista Season -> Dettaglio -> Sessione. */
    void goBack() {
        if (screen == SCREEN_SESSION_PLAY) { screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; }
        else if (screen == SCREEN_SEASON_DETAIL) { screen = SCREEN_SEASON_LIST; }
        view.invalidate();
    }

    @Override public void onBackPressed() {
        if (screen == SCREEN_SEASON_LIST) super.onBackPressed(); // livello radice: comportamento standard (esce dall'app)
        else goBack();
    }

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
    // attuale -> sessione non tracciata) oppure (No: scelta deck -> Session 1). Ogni step e' un dialog a se',
    // mai impilato sopra un altro: si passa da uno step al successivo chiudendo quello corrente, cosi'
    // "Indietro" puo' sempre tornare allo step precedente senza restare mai "intrappolati" in un dialog.

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
    // differenza come un'unica sessione non tracciata, esattamente come una correzione manuale di punteggio.
    void wizardStep3Yes(boolean first, String name){
        LinearLayout box = formBox();
        EditText points = numberField("Punti attuali", true);
        EditText streak = numberField("Vittorie consecutive attuali", true);
        EditText wins = numberField("Partite vinte finora", false);
        EditText losses = numberField("Partite perse finora", false);
        box.addView(label("Punti attuali")); box.addView(points);
        box.addView(label("Vittorie consecutive attuali")); box.addView(streak);
        box.addView(label("Partite vinte finora")); box.addView(wins);
        box.addView(label("Partite perse finora")); box.addView(losses);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name)
            .setView(box).setCancelable(false)
            .setPositiveButton("Crea Stagione", null)
            .setNeutralButton("Indietro", (d,w) -> wizardStep2(first, name))
            .create();
        showNonDismissing(dialog, () -> {
            try {
                int np = Integer.parseInt(points.getText().toString());
                int ns = Integer.parseInt(streak.getText().toString());
                int nw = Integer.parseInt(wins.getText().toString());
                int nl = Integer.parseInt(losses.getText().toString());
                if (ns < 0 || nw < 0 || nl < 0) return false;
                Season s = new Season(name);
                s.points = DEFAULT_BASELINE; s.baseline = DEFAULT_BASELINE;
                s.streak = 0; s.initialStreak = 0;
                Session sess = new Session("Sessione di gioco 1", "Unknown");
                sess.untracked = true;
                sess.startPoints = s.points; sess.endPoints = np;
                sess.startStreak = s.streak; sess.endStreak = ns;
                sess.untrackedWins = nw; sess.untrackedLosses = nl;
                sess.matches.add(Match.untracked(s.points, np));
                s.sessions.add(sess); s.currentSession = 0;
                s.points = np; s.streak = ns;
                store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
                if (view == null) { view = new TrackerView(this); setContentView(view); attachInsets(view); }
                screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
                return true;
            } catch (Exception e) { return false; }
        }, "Inserisci punti attuali e vittorie consecutive validi (streak >= 0).");
        dialog.show();
    }

    // "No": niente da chiedere sul punteggio (parte dallo standard 810/streak 0), si passa dritti alla
    // scelta del deck per la prima sessione (con "Salta" per andare veloci, come al solito).
    void wizardStep3No(boolean first, String name){
        Season s = new Season(name);
        s.points = DEFAULT_BASELINE; s.baseline = DEFAULT_BASELINE;
        s.streak = 0; s.initialStreak = 0;
        s.sessions.add(new Session("Sessione di gioco 1", "Unknown")); // placeholder: showNewDeckAndSession(true) la sostituisce
        store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
        if (view == null) { view = new TrackerView(this); setContentView(view); attachInsets(view); }
        showNewDeckAndSession(true);
    }


    boolean deckNameTaken(Season s, String n) {
        if ("Unknown".equalsIgnoreCase(n)) return true;
        for (Deck d : s.decks) if (d.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    void showNewDeckAndSession(boolean first) {
        final Season s = store.seasons.get(store.current);
        LinearLayout box = formBox();
        box.addView(label("Seleziona un deck, creane uno nuovo o salta."));

        // Selettore compatto (mostra solo il deck scelto), non piu' una lista con tutti i deck gia' srotolati:
        // toccandolo si apre un menu a tendina con l'elenco ordinato alfabeticamente. Cosi' anche con molti
        // deck il dialog resta compatto, invece di mostrarli tutti in cascata.
        ArrayList<String> sortedNames = new ArrayList<>();
        for (Deck d : s.decks) sortedNames.add(d.name);
        Collections.sort(sortedNames, String.CASE_INSENSITIVE_ORDER);
        final String[] selected = {sortedNames.isEmpty() ? null : sortedNames.get(0)};

        Button deckSelector = new Button(this); styleSecondaryButton(deckSelector);
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selLp.topMargin = dp(6); deckSelector.setLayoutParams(selLp);
        final Runnable[] refreshSelector = new Runnable[1];
        refreshSelector[0] = () -> deckSelector.setText(withBigArrow((selected[0] != null ? selected[0] : "Tocca per scegliere un deck") + "  ▾"));
        refreshSelector[0].run();
        box.addView(deckSelector);
        deckSelector.setOnClickListener(v -> {
            ArrayList<String> names = new ArrayList<>();
            for (Deck d : s.decks) names.add(d.name);
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            if (names.isEmpty()) return;
            new AlertDialog.Builder(this).setTitle("Scegli un Deck").setItems(names.toArray(new String[0]), (dlg,which)->{
                selected[0] = names.get(which);
                refreshSelector[0].run();
            }).show();
        });

        // Sezione "nuovo deck": invece di un pulsante che rivela sotto un campo + pulsante "Crea" (ridondante,
        // visto che "Conferma" in fondo al dialog crea gia' il deck in sospeso), ora il pulsante "Nuovo Deck"
        // si TRASFORMA in un campo di testo con una crocetta DENTRO al campo (non affiancata fuori), allineata
        // a destra con un po' di padding, per richiuderlo. Stile scuro coerente con il tema dell'app (prima
        // era chiara, ricalcata per errore su uno screenshot di riferimento a tema chiaro).
        android.widget.FrameLayout newDeckSection = new android.widget.FrameLayout(this);
        newDeckSection.setVisibility(View.GONE);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dp(14); newDeckSection.setLayoutParams(sectionLp);
        EditText newDeckName = field("Nome Deck");
        newDeckName.setPadding(dp(14),dp(12),dp(44),dp(12)); // padding destro maggiore: lascia spazio alla crocetta sovrapposta, il testo non ci finisce sotto
        newDeckSection.addView(newDeckName, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        TextView closeNewDeck = new TextView(this);
        closeNewDeck.setText("✕"); closeNewDeck.setTextColor(MUTED_TXT); closeNewDeck.setTextSize(15);
        closeNewDeck.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable(); closeBg.setShape(GradientDrawable.OVAL); closeBg.setColor(Color.rgb(24,36,52));
        closeNewDeck.setBackground(closeBg);
        android.widget.FrameLayout.LayoutParams closeLp = new android.widget.FrameLayout.LayoutParams(dp(28), dp(28));
        closeLp.gravity = Gravity.END|Gravity.CENTER_VERTICAL; closeLp.rightMargin = dp(8);
        newDeckSection.addView(closeNewDeck, closeLp);
        box.addView(newDeckSection);
        TextView newDeckError = new TextView(this); newDeckError.setTextColor(red()); newDeckError.setTextSize(12); newDeckError.setVisibility(View.GONE); newDeckError.setPadding(0,dp(4),0,0);
        box.addView(newDeckError);

        Button newDeckBtn = new Button(this); newDeckBtn.setText("Nuovo Deck"); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newDeckBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newDeckBtnLp.topMargin = dp(14); newDeckBtn.setLayoutParams(newDeckBtnLp);
        box.addView(newDeckBtn);
        newDeckBtn.setOnClickListener(v -> { newDeckSection.setVisibility(View.VISIBLE); newDeckBtn.setVisibility(View.GONE); newDeckName.requestFocus(); });
        closeNewDeck.setOnClickListener(v -> { newDeckName.setText(""); newDeckError.setVisibility(View.GONE); newDeckSection.setVisibility(View.GONE); newDeckBtn.setVisibility(View.VISIBLE); });

        // "Salta" spostato qui (era un terzo pulsante in fondo al dialog, ANNULLA/CONFERMA/SALTA tutti insieme):
        // ora e' un pulsante nel flusso principale, subito sotto "Nuovo Deck", coerente con le altre azioni.
        Button skipBtn = new Button(this); skipBtn.setText("Salta"); styleSecondaryButton(skipBtn);
        LinearLayout.LayoutParams skipLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        skipLp.topMargin = dp(10); skipBtn.setLayoutParams(skipLp);
        box.addView(skipBtn);

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this).setTitle(first ? "Scegli il Deck iniziale" : "Nuova Sessione")
            .setView(box).setCancelable(!first)
            .setPositiveButton("Conferma", null);
        if (!first) builder.setNegativeButton("Annulla", null);
        AlertDialog dialog = builder.create();
        skipBtn.setOnClickListener(v -> { dialog.dismiss(); createSessionWithDeck(s, first, "Unknown"); });
        // Bug corretto: se l'utente apre la sezione "Nuovo Deck", digita un nome ma preme "Conferma" invece
        // di "Crea", il nome digitato veniva silenziosamente ignorato e la sessione partiva con un deck
        // diverso (quello di default). Ora "Conferma" crea automaticamente il deck in sospeso, se presente.
        showNonDismissing(dialog, () -> {
            String pendingName = newDeckName.getText().toString().trim();
            if (newDeckSection.getVisibility() == View.VISIBLE && !pendingName.isEmpty()) {
                if (deckNameTaken(s, pendingName)) return false;
                s.decks.add(new Deck(pendingName)); store.save();
                selected[0] = pendingName;
            }
            String deck = selected[0] != null ? selected[0] : "Unknown";
            createSessionWithDeck(s, first, deck);
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    int blueColor(){ return Color.rgb(55,120,255); }
    int red(){ return Color.rgb(245,70,60); }
    // Formato richiesto: dd/mm/yy hh:mm. Ritorna stringa vuota se il timestamp non e' disponibile (dati
    // vecchi salvati prima dell'introduzione di questo campo).
    String formatTimestamp(long ts){
        if (ts<=0) return "";
        return new java.text.SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY).format(new java.util.Date(ts));
    }
    // Solo la data, senza l'orario: usata nel grafico, dove l'ora non serve.
    String formatDateOnly(long ts){
        if (ts<=0) return "";
        return new java.text.SimpleDateFormat("dd/MM/yy", Locale.ITALY).format(new java.util.Date(ts));
    }

    void createSessionWithDeck(Season s, boolean first, String deck) {
        if (first) {
            s.sessions.clear(); s.sessions.add(new Session("Sessione di gioco 1",deck)); s.currentSession=0;
        } else {
            s.sessions.add(new Session("Sessione di gioco "+(s.sessions.size()+1),deck)); s.currentSession=s.sessions.size()-1;
        }
        // Dopo aver scelto (o saltato) il deck si entra sempre direttamente nella sessione di gioco.
        screen = SCREEN_SESSION_PLAY;
        store.save(); view.invalidate();
    }

    String[] deckNames(Season s) {
        ArrayList<String> r=new ArrayList<>();
        for(Deck d:s.decks) r.add(d.name);
        return r.toArray(new String[0]);
    }

    // Crea un nuovo Deck e lo assegna subito alla sessione attualmente aperta (usato da chooseDeck()).
    void createDeckAndAssign(Season s) {
        LinearLayout box = formBox();
        box.addView(label("Nome Deck"));
        EditText e = field("Nome Deck");
        box.addView(e);
        AlertDialog nd = new AlertDialog.Builder(this).setTitle("Nuovo Deck").setView(box)
            .setPositiveButton("Crea", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(nd, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            s.decks.add(new Deck(n));
            s.sessions.get(s.currentSession).deck = n;
            store.save(); view.invalidate();
            return true;
        }, "Nome Deck non valido o già esistente.");
        nd.show();
    }

    // Cambia il deck della sessione ATTUALMENTE APERTA (funziona anche su sessioni passate: le statistiche
    // per deck sono sempre calcolate al volo dal nome del deck, quindi si aggiornano da sole).
    // Menu con due opzioni per il deck della sessione corrente: cambiarlo (scegliere un deck diverso o
    // "Nessun deck"), oppure rinominare quello attualmente assegnato (propagato a tutte le sessioni che lo usano).
    // Menu unificato "⋮" nell'header di session play: raccoglie tutte le azioni secondarie della sessione
    // (prima erano pulsanti sempre visibili in fondo, ora appaiono solo quando applicabili). Ordine: azione
    // piu' frequente (Nuova sessione) in cima, poi Modifica deck, poi il caso raro (Segna come non tracciata),
    // e per ultima — isolata e in rosso — quella distruttiva (Elimina sessione).
    void sessionOptionsMenu(Session sess, boolean isLast, boolean canConvert, float rightEdgeX, float anchorY){
        boolean showNewSessionItem = isLast && !sess.untracked;
        boolean showDeckEditItem = !sess.untracked;
        boolean showEditUntrackedItem = sess.untracked;
        boolean showDeleteItem = isLast;

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> colors = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (showNewSessionItem) { labels.add("Nuova sessione"); colors.add(Color.WHITE); actions.add(this::showNewSession); }
        if (showDeckEditItem) { labels.add("Modifica deck"); colors.add(Color.WHITE); actions.add(() -> deckOptionsMenu(sess)); }
        if (showEditUntrackedItem) { labels.add("Modifica sessione"); colors.add(Color.WHITE); actions.add(this::editUntrackedSession); }
        if (canConvert) { labels.add("Segna come non tracciata"); colors.add(Color.WHITE); actions.add(this::convertToUntracked); }
        if (showDeleteItem) { labels.add("Elimina sessione"); colors.add(red()); actions.add(this::deleteCurrentSession); }

        view.showAnchoredMenu(rightEdgeX, anchorY, labels.toArray(new String[0]), toIntArray(colors), actions.toArray(new Runnable[0]));
    }

    int[] toIntArray(ArrayList<Integer> list){
        int[] a = new int[list.size()];
        for (int i=0;i<a.length;i++) a[i]=list.get(i);
        return a;
    }

    void deckOptionsMenu(Session sess){
        Season s = store.seasons.get(store.current);
        boolean hasRealDeck = !"Unknown".equals(sess.deck);
        String[] options = hasRealDeck ? new String[]{"Cambia deck","Rinomina questo deck"} : new String[]{"Cambia deck"};
        new AlertDialog.Builder(this).setTitle("Deck").setItems(options,(dlg,which)->{
            if (which==0) chooseDeck();
            else renameDeckDialog(findDeck(s, sess.deck));
        }).show();
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
            for (Session se: s.sessions) if (oldName.equals(se.deck)) se.deck = n;
            store.save(); view.invalidate();
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    void chooseDeck() {
        Season s=store.seasons.get(store.current);
        Session sess = s.sessions.get(s.currentSession);
        ArrayList<String> names=new ArrayList<>();
        for (Deck d : s.decks) names.add(d.name);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if(names.isEmpty()){ createDeckAndAssign(s); return; }

        // Selettore compatto (come nella scelta iniziale del deck): mostra solo un pulsante che apre un menu
        // a tendina, invece di elencare tutti i deck gia' srotolati nel dialog principale.
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

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Cambia Deck").setView(box)
            .setNegativeButton("Annulla", null).create();
        deckSelector.setOnClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Scegli un Deck").setItems(names.toArray(new String[0]),(d2,which)->{
                sess.deck = names.get(which); store.save(); view.invalidate(); dialog.dismiss();
            }).show();
        });
        newDeckBtn.setOnClickListener(v -> { dialog.dismiss(); createDeckAndAssign(s); });
        dialog.show();
    }

    void showNewSession() { showNewDeckAndSession(false); }

    void showUntracked() {
        Season s=store.seasons.get(store.current);
        LinearLayout box=formBox();
        EditText p=numberField("Punti attuali", true);
        EditText st=numberField("Vittorie consecutive attuali", true);
        EditText w=numberField("Partite vinte in questo periodo", false);
        EditText l=numberField("Partite perse in questo periodo", false);
        box.addView(label("Punti attuali")); box.addView(p);
        box.addView(label("Vittorie consecutive attuali")); box.addView(st);
        box.addView(label("Partite vinte in questo periodo")); box.addView(w);
        box.addView(label("Partite perse in questo periodo")); box.addView(l);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Sessione non tracciata").setMessage("Inserisci solo lo stato attuale. L'app collegherà automaticamente il nuovo punteggio all'ultimo conosciuto.")
            .setView(box).setPositiveButton("Conferma", null).setNegativeButton("Annulla",null).create();
        showNonDismissing(dialog, () -> {
            try {
                int np=Integer.parseInt(p.getText().toString()), ns=Integer.parseInt(st.getText().toString());
                int nw=Integer.parseInt(w.getText().toString()), nl=Integer.parseInt(l.getText().toString());
                if (ns < 0 || nw < 0 || nl < 0) return false;
                Session x=new Session("Sessione di gioco "+(s.sessions.size()+1),"Unknown");
                x.untracked=true; x.startPoints=s.points; x.endPoints=np; x.startStreak=s.streak; x.endStreak=ns;
                x.untrackedWins=nw; x.untrackedLosses=nl;
                x.matches.add(Match.untracked(s.points,np));
                s.sessions.add(x); s.currentSession=s.sessions.size()-1; s.points=np; s.streak=ns;
                // Resta sulla lista sessioni: una sessione non tracciata non si "gioca" col W/L.
                store.save(); view.invalidate();
                return true;
            } catch(Exception e) { return false; }
        }, "Valori non validi (streak/vittorie/sconfitte >= 0).");
        dialog.show();
    }

    void win() { play(true); }
    void loss() { play(false); }

    void play(boolean win) {
        Season s=store.seasons.get(store.current);
        Session x=s.sessions.get(s.currentSession);
        if(x.untracked){ Toast.makeText(this,"Crea una nuova sessione per continuare.",Toast.LENGTH_SHORT).show(); return; }
        int before=s.points, beforeStreak=s.streak;
        if(win){ s.streak++; s.points += reward(s.streak); }
        else { s.points -= 10; s.streak=0; }
        Match m = new Match(win, before, s.points, s.streak);
        x.matches.add(m);
        // L'azione (State) porta con se' il match generato: undo/redo lo rimuovono/reinseriscono
        // esattamente, invece di affidarsi alla sola posizione "ultimo elemento della lista".
        s.undo.push(new State(before, beforeStreak, s.currentSession, m));
        s.redo.clear();
        store.save(); view.invalidate();
    }

    int reward(int streak) { return streak<=1?10:streak==2?13:streak==3?16:streak==4?19:22; }

    void undo() {
        Season s=store.seasons.get(store.current);
        if(s.undo.empty()){Toast.makeText(this,"Niente da annullare.",Toast.LENGTH_SHORT).show();return;}
        State prev=s.undo.pop();
        if (prev.sessionIndex >= 0 && prev.sessionIndex < s.sessions.size() && prev.match != null) {
            s.sessions.get(prev.sessionIndex).matches.remove(prev.match);
        }
        s.redo.push(prev);
        s.points=prev.points; s.streak=prev.streak; s.currentSession=prev.sessionIndex;
        store.save(); view.invalidate();
    }

    void redo() {
        Season s=store.seasons.get(store.current);
        if(s.redo.empty()){Toast.makeText(this,"Niente da ripetere.",Toast.LENGTH_SHORT).show();return;}
        State next=s.redo.pop();
        if (next.match != null && next.sessionIndex >= 0 && next.sessionIndex < s.sessions.size()) {
            Session x = s.sessions.get(next.sessionIndex);
            if (!x.matches.contains(next.match)) x.matches.add(next.match);
            s.points = next.match.after;
            s.streak = next.match.streak;
        } else {
            s.points = next.points; s.streak = next.streak;
        }
        s.currentSession = next.sessionIndex;
        s.undo.push(next);
        store.save(); view.invalidate();
    }

    // Elimina la sessione CORRENTE (l'ultima, quella attiva/riprendibile). Se non ha partite, elimina subito;
    // altrimenti chiede conferma. Dopo l'eliminazione si torna alla lista sessioni e quella precedente
    // (il nuovo ultimo indice) diventa la nuova sessione corrente.
    void deleteCurrentSession(){
        Season s=store.seasons.get(store.current);
        int idx=s.currentSession;
        if(s.sessions.size()<=1){ Toast.makeText(this,"Non puoi eliminare l'unica sessione di questa Stagione.",Toast.LENGTH_SHORT).show(); return; }
        Session sess=s.sessions.get(idx);
        Runnable doDelete = () -> {
            s.sessions.remove(idx);
            s.currentSession = Math.max(0, s.sessions.size()-1);
            screen = SCREEN_SEASON_DETAIL; view.detailTab = 0;
            store.save(); view.invalidate();
        };
        if(sess.matches.isEmpty()){ doDelete.run(); return; }
        new AlertDialog.Builder(this).setTitle("Eliminare la sessione?")
            .setMessage("Questo eliminerà definitivamente \""+sess.name+"\" e le sue "+sess.matches.size()+" partita/e. L'azione non può essere annullata.")
            .setPositiveButton("Elimina", (d,w)-> doDelete.run())
            .setNegativeButton("Annulla", null)
            .show();
    }

    // Converte la sessione CORRENTE (qualunque essa sia) in una sessione non tracciata, sostituendo il suo
    // stato con una correzione manuale punti/streak. Permesso solo se non ha ancora partite: altrimenti
    // si perderebbero silenziosamente delle partite gia' giocate.
    void convertToUntracked(){
        Season s=store.seasons.get(store.current);
        Session sess=s.sessions.get(s.currentSession);
        if(!sess.matches.isEmpty()){ Toast.makeText(this,"Solo una sessione senza ancora partite può essere segnata come non tracciata.",Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box=formBox();
        EditText p=numberField("Punti attuali", true);
        EditText st=numberField("Vittorie consecutive attuali", true);
        EditText w=numberField("Partite vinte in questo periodo", false);
        EditText l=numberField("Partite perse in questo periodo", false);
        box.addView(label("Punti attuali")); box.addView(p);
        box.addView(label("Vittorie consecutive attuali")); box.addView(st);
        box.addView(label("Partite vinte in questo periodo")); box.addView(w);
        box.addView(label("Partite perse in questo periodo")); box.addView(l);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Segna come non tracciata")
            .setMessage("Questa sessione non ha ancora partite, quindi può essere trasformata in una correzione di punteggio non tracciata.")
            .setView(box).setPositiveButton("Conferma", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            try {
                int np=Integer.parseInt(p.getText().toString()), ns=Integer.parseInt(st.getText().toString());
                int nw=Integer.parseInt(w.getText().toString()), nl=Integer.parseInt(l.getText().toString());
                if (ns < 0 || nw < 0 || nl < 0) return false;
                sess.untracked = true; sess.deck = "Unknown";
                sess.startPoints = s.points; sess.endPoints = np; sess.startStreak = s.streak; sess.endStreak = ns;
                sess.untrackedWins = nw; sess.untrackedLosses = nl;
                sess.matches.clear();
                sess.matches.add(Match.untracked(s.points, np));
                s.points = np; s.streak = ns;
                store.save(); view.invalidate();
                return true;
            } catch(Exception e) { return false; }
        }, "Valori non validi (streak/vittorie/sconfitte >= 0).");
        dialog.show();
    }

    void newSeason(){ wizardStep1(false, null); }

    // Modifica i dati di una sessione non tracciata GIA' ESISTENTE. Permesso solo se e' la sessione corrente
    // (l'ultima): modificare una correzione passata romperebbe la coerenza numerica di tutto cio' che viene
    // dopo (i punti "before" delle sessioni successive erano gia' calcolati sul valore vecchio).
    // Modifica i dati di una sessione non tracciata GIA' ESISTENTE — QUALUNQUE sessione non tracciata, non solo
    // quella corrente: un errore di inserimento dati sulla correzione iniziale (o su una a meta' stagione) puo'
    // disallineare per sempre il tracker dal gioco reale, quindi dev'essere sempre correggibile.
    // Se si cambiano punti o vittorie consecutive (non solo W/L), tutte le sessioni successive vengono
    // ricalcolate di conseguenza per restare coerenti — con un avviso esplicito prima di procedere.
    void editUntrackedSession(){
        Season s = store.seasons.get(store.current);
        int idx = s.currentSession;
        Session sess = s.sessions.get(idx);
        LinearLayout box=formBox();
        EditText p=numberField("Punti attuali", true); p.setText(String.valueOf(sess.endPoints));
        EditText st=numberField("Vittorie consecutive attuali", true); st.setText(String.valueOf(sess.endStreak));
        EditText w=numberField("Partite vinte in questo periodo", false); w.setText(String.valueOf(sess.untrackedWins));
        EditText l=numberField("Partite perse in questo periodo", false); l.setText(String.valueOf(sess.untrackedLosses));
        box.addView(label("Punti attuali")); box.addView(p);
        box.addView(label("Vittorie consecutive attuali")); box.addView(st);
        box.addView(label("Partite vinte in questo periodo")); box.addView(w);
        box.addView(label("Partite perse in questo periodo")); box.addView(l);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Modifica sessione non tracciata")
            .setView(box).setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create();
        // Gestione manuale (non showNonDismissing): se serve un avviso di ricalcolo, vogliamo chiudere PRIMA
        // questo dialog e SOLO DOPO aprirne un altro, mai due dialog sovrapposti insieme.
        dialog.setOnShowListener(dd -> {
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                int np, ns, nw, nl;
                try {
                    np=Integer.parseInt(p.getText().toString()); ns=Integer.parseInt(st.getText().toString());
                    nw=Integer.parseInt(w.getText().toString()); nl=Integer.parseInt(l.getText().toString());
                } catch(Exception e){ np=ns=nw=nl=-1; }
                if (ns<0 || nw<0 || nl<0) { Toast.makeText(this,"Valori non validi (streak/vittorie/sconfitte >= 0).",Toast.LENGTH_LONG).show(); return; }
                boolean pointsOrStreakChanged = np!=sess.endPoints || ns!=sess.endStreak;
                int fnp=np, fns=ns, fnw=nw, fnl=nl;
                dialog.dismiss();
                Runnable apply = () -> {
                    sess.endPoints=fnp; sess.endStreak=fns; sess.untrackedWins=fnw; sess.untrackedLosses=fnl;
                    recalcForward(s, idx);
                    store.save(); view.invalidate();
                };
                if (pointsOrStreakChanged && idx < s.sessions.size()-1) {
                    new AlertDialog.Builder(this).setTitle("Ricalcolare le sessioni successive?")
                        .setMessage("Hai cambiato punti o vittorie consecutive: tutte le sessioni successive verranno ricalcolate di conseguenza per restare coerenti con il resto della cronologia.")
                        .setPositiveButton("Conferma", (d,w2)-> apply.run())
                        .setNegativeButton("Annulla", null)
                        .show();
                } else {
                    apply.run();
                }
            });
        });
        dialog.show();
    }

    // Ricalcola in avanti punti/streak/partite di tutte le sessioni DOPO fromIndex, a partire dal nuovo stato
    // (gia' aggiornato) della sessione a fromIndex. Le sessioni tracciate vengono ricalcolate partita per
    // partita (prevalgono le regole normali di gioco); le sessioni non tracciate successive mantengono il loro
    // punteggio finale cosi' come l'utente lo aveva osservato (e' un valore assoluto, non va spostato), ma il
    // loro punto di partenza viene aggiornato per restare coerente con la correzione appena fatta.
    void recalcForward(Season s, int fromIndex){
        if (fromIndex<0 || fromIndex>=s.sessions.size()) return;
        Session first = s.sessions.get(fromIndex);
        int runPoints, runStreak;
        if (first.untracked) { runPoints=first.endPoints; runStreak=first.endStreak; }
        else if (!first.matches.isEmpty()) { Match last=first.matches.get(first.matches.size()-1); runPoints=last.after; runStreak=last.streak; }
        else { runPoints=first.startPoints; runStreak=first.startStreak; }
        for (int j=fromIndex+1; j<s.sessions.size(); j++){
            Session se = s.sessions.get(j);
            se.startPoints = runPoints; se.startStreak = runStreak;
            if (se.untracked) {
                if (!se.matches.isEmpty()) {
                    Match m = se.matches.get(0);
                    m.before = runPoints; m.after = se.endPoints; m.win = se.endPoints>=runPoints; m.unknown = true;
                }
                runPoints = se.endPoints; runStreak = se.endStreak; // valore assoluto osservato dall'utente: non si sposta
            } else {
                for (Match m : se.matches) {
                    int before = runPoints, newStreak, after;
                    if (m.win) { newStreak = runStreak+1; after = before + reward(newStreak); }
                    else { newStreak = 0; after = before - 10; }
                    m.before = before; m.after = after; m.streak = newStreak;
                    runPoints = after; runStreak = newStreak;
                }
                se.endPoints = runPoints; se.endStreak = runStreak;
            }
        }
        s.points = runPoints; s.streak = runStreak;
    }

    // Cancella TUTTI i dati salvati (ogni Stagione, sessione, deck, nota). Richiede sempre conferma esplicita,
    // dato che e' un'azione distruttiva e irreversibile. Dopo la cancellazione, l'app si comporta come al
    // primissimo avvio: riparte dal wizard obbligatorio di creazione della prima Stagione.
    void resetAllData(){
        new AlertDialog.Builder(this).setTitle("Cancellare tutti i dati?")
            .setMessage("Questo eliminerà definitivamente ogni Stagione, sessione, deck e nota. L'azione non può essere annullata.")
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
        EditText e=field(s.name);
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

    // Elimina un deck: se e' usato in una o piu' sessioni, avvisa prima e, se confermato, imposta quelle
    // sessioni su "Deck sconosciuto" (Unknown) invece di lasciarle con un riferimento a un deck inesistente.
    void confirmDeleteDeck(Season s, Deck d){
        int usedCount = 0;
        for (Session se: s.sessions) if (d.name.equals(se.deck)) usedCount++;
        String message = usedCount>0
            ? "Questo deck e' usato in "+usedCount+" "+(usedCount==1?"sessione":"sessioni")+". Eliminandolo, "+(usedCount==1?"verra' impostata":"verranno impostate")+" su \"Deck sconosciuto\"."
            : "Eliminare definitivamente questo deck?";
        new AlertDialog.Builder(this).setTitle("Elimina "+d.name)
            .setMessage(message)
            .setPositiveButton("Elimina", (dlg,w)-> {
                for (Session se: s.sessions) if (d.name.equals(se.deck)) se.deck = "Unknown";
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
                // Aggiunge alla lista di un Deck gia' esistente (da tab Deck o dalla Sessione in gioco):
                // un Deck puo' avere piu' di uno screenshot associato.
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
    // uno screenshot (es. varianti diverse, momenti diversi). Richiamabile sia dal tab Deck sia dalla
    // Sessione in gioco: agisce sempre sull'oggetto Deck della Season corrente.
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

    // Visualizzatore in stile galleria: header in alto (chiudi/titolo/aggiungi), frecce di navigazione come
    // piccoli cerchi semi-trasparenti sovrapposti ai lati dell'immagine, ed "elimina" (cestino) accanto al
    // contatore "N / M" sotto — cosi' e' chiaro che si riferisce ALLO SCREENSHOT visualizzato in quel momento,
    // non a un'azione generica. Prima erano 5 pulsanti testuali impilati sotto l'immagine, senza alcun header.
    // Ritaglia automaticamente il 17% dall'alto e il 14% dal basso: gli screenshot del gioco hanno spesso
    // intestazioni/pulsanti di sistema poco utili in quelle zone, cosi' il contenuto rilevante riempie meglio lo schermo.
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

        // Contatore + cestino nella STESSA riga: cosi' e' inequivocabile che "elimina" si riferisce a
        // QUESTO screenshot (N/M), non a un'azione generica sul deck.
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

    // Colori/stile condivisi per i widget nativi dei dialog (Season/Deck/Sessione), coerenti con la palette
    // scura del resto dell'app invece dei widget Android di default (che stonavano visivamente).
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


    // Conteggio W/L unificato: per le sessioni non tracciate usa i conteggi aggregati inseriti dall'utente
    // (untrackedWins/untrackedLosses), per le altre conta le singole partite escludendo quelle "unknown".
    // Centralizzare qui evita il bug per cui una correzione "unknown" con win=true (solo perche' i punti
    // sono saliti) veniva erroneamente contata come una vittoria vera in alcuni punti dell'app.
    static int[] countWL(List<Session> sessions){
        int w=0,l=0;
        for(Session se: sessions){
            if(se.untracked){ w+=se.untrackedWins; l+=se.untrackedLosses; continue; }
            for(Match m: se.matches) if(!m.unknown){ if(m.win) w++; else l++; }
        }
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

    /** Area rettangolare cliccabile associata a un indice (season o sessione), usata per l'hit-test nelle liste. */
    static class Hit { float top, bottom; int index; Hit(float t, float b, int i){top=t;bottom=b;index=i;} }

    class TrackerView extends View {
        Paint p=new Paint(3);
        int detailTab=0; // 0 = Sessioni, 1 = Deck, 2 = Statistiche (solo dentro SCREEN_SEASON_DETAIL)
        int bg=Color.rgb(7,11,18), card=Color.rgb(14,24,38), white=Color.WHITE, muted=Color.rgb(165,175,190), blue=Color.rgb(55,120,255), green=Color.rgb(70,205,75), red=Color.rgb(245,70,60);
        ArrayList<Hit> seasonHits=new ArrayList<>();
        ArrayList<Hit> sessionHits=new ArrayList<>();
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
        // Scroll indipendente per la lista dentro la card "Sessioni di gioco": la card ha un'altezza fissa
        // (non cresce con il numero di sessioni, che puo' arrivare anche a 50+), con una sua scrollbar propria,
        // separata dallo scroll generale della schermata. sessInnerListTop/Bottom sono le coordinate di
        // contenuto (prima dello scroll interno) della zona visibile, impostate durante il disegno e lette
        // dal gestore del tocco per instradare correttamente i trascinamenti.
        float sessInnerScrollY=0, sessInnerMaxScrollY=0, sessInnerListTop=0, sessInnerListBottom=0;
        String sessInnerScrollKey="";
        void resetSessInnerScrollIfNeeded(String key){ if(!key.equals(sessInnerScrollKey)){ sessInnerScrollY=0; sessInnerScrollKey=key; } }
        // Stesso meccanismo, per la card "PER DECK" nel tab Statistiche (anche qui il numero di deck puo'
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
        // di dimensioni diverse (es. la freccia "←" e il titolo) restano allineati sulla stessa linea centrale.
        // Disegna una freccia sinistra specchiando il glifo "→" invece di usare "←": sono due caratteri
        // Unicode DIVERSI, che con lo stesso font risultano di peso/dimensione visiva differenti (bug scoperto
        // dopo vari tentativi di "compensare" con size diverse). Specchiando lo stesso identico glifo lo stile
        // e' garantito identico in entrambe le direzioni. Allineata a sinistra: il bordo sinistro resta a 'leftX'.
        // Icona "altre opzioni" (tre puntini verticali), disegnata a mano invece che con il glifo Unicode "⋮":
        // stessa lezione delle frecce "←"/"→" — meglio disegnare da soli che affidarsi a un carattere il cui
        // rendering puo' variare da font a font.
        void drawKebabIcon(Canvas c, float cx, float cy, int color){
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            float r=2.4f, gap=7.5f;
            c.drawCircle(cx,cy-gap,r,p);
            c.drawCircle(cx,cy,r,p);
            c.drawCircle(cx,cy+gap,r,p);
        }
        // Mostra un menu "a tendina" ancorato esattamente sotto un punto del canvas (logicalX/logicalY sono
        // nello stesso sistema di coordinate usato per disegnare, gia' al netto dello scroll se applicabile),
        // invece di un AlertDialog centrato al centro dello schermo. Converte le coordinate logiche in pixel
        // reali di schermo (via density + padding + posizione della view) per usare PopupWindow.showAtLocation.
        // Il parametro e' la posizione desiderata del bordo DESTRO del menu (non quello sinistro): misuriamo
        // il contenuto PRIMA di posizionarlo, cosi' il margine dal bordo destro resta corretto qualunque sia
        // la larghezza effettiva (che varia in base al testo delle voci — "Segna come non tracciata" e' molto
        // piu' lunga di "Elimina sessione"). Prima si indovinava un offset fisso per il bordo sinistro, che
        // con le voci piu' lunghe finiva per sbattere (o addirittura uscire) dal bordo destro dello schermo.
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
            // schermata la posizione (getLocationOnScreen/padding da inset) puo' non essere ancora stabilizzata
            // — da cui il menu che compariva nel posto sbagliato solo la prima volta, corretto da solo dopo
            // qualunque altro ridisegno (es. cambiando tab). post() garantisce che il layout sia gia' assestato.
            post(() -> {
                box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                float leftLogicalX = rightEdgeLogicalX - box.getMeasuredWidth()/density;
                int[] loc = new int[2]; getLocationOnScreen(loc);
                int screenX = loc[0] + getPaddingLeft() + (int)(leftLogicalX*density);
                int screenY = loc[1] + getPaddingTop() + (int)(topLogicalY*density);
                popup.showAtLocation(this, Gravity.NO_GRAVITY, screenX, screenY);
            });
        }
        float centeredBaseline(float centerY, float size){
            p.setTextSize(size);
            Paint.FontMetrics fm = p.getFontMetrics();
            return centerY - (fm.ascent+fm.descent)/2;
        }
        // Come centeredBaseline, ma per un blocco di PIU' righe impilate (etichetta sopra, valore sotto, ecc.):
        // calcola le baseline in modo che l'intero blocco risulti centrato verticalmente attorno a centerY,
        // usando le metriche reali dei font invece di offset fissi indovinati (causa dello sbilanciamento
        // verso il basso nelle card statistiche). Ritorna una baseline per ogni size passata, in ordine.
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
        // Icona "modifica" disegnata a mano (invece del glifo Unicode ✎, che su alcuni font ha troppi tratti
        // sottili ed è poco leggibile piccolo): una semplice matita diagonale, come nelle icone standard.
        // Matita rastremata verso la punta (piu' stretta in fondo, con la cima arrotondata a semicerchio),
        // invece del corpo a larghezza costante + triangolo di prima: silhouette piu' pulita e riconoscibile.
        // Matita ricalcata sulla forma reale dell'icona Material Design "edit" (24x24, licenza Apache 2.0):
        // corpo+punta ed una piccola "ghiera" separata vicino alla cima, invece di una forma disegnata a mano —
        // molto piu' naturale e riconoscibile (gli archi minuscoli dell'originale sono approssimati con linee
        // rette, differenza impercettibile a questa scala).
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
            if (screen == SCREEN_SESSION_PLAY) { sessionPlay(c,s,w,h); c.restore(); return; }
            // SCREEN_SEASON_DETAIL: header e barra tab in basso restano fissi, il contenuto in mezzo scorre.
            detailHeader(c,s,w);
            bodyTop=44; bodyBottom=h-58; // 44 e non 58: nel tab Deck la pillola "Ordina" parte da y=48
            resetScrollIfNeeded("detail:"+detailTab+":"+store.current);
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);
            if (detailTab==0) sessionsList(c,s,w,h);
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
                int[] wl=countWL(s.sessions); int W=wl[0],L=wl[1];
                float wr=(W+L)==0?0:100f*W/(W+L);
                // Su due righe con un po' piu' di interlinea: "Vittorie consecutive" per esteso non ci
                // starebbe su un'unica riga assieme al resto.
                txt(c,"Punti "+s.points+"   Vittorie consecutive "+s.streak,34,y+52,12,muted,Paint.Align.LEFT);
                txtRow(c,34,y+74,12,
                    new String[]{W+"W   ", L+"L   ", "WR "+String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
                txt(c,s.sessions.size()+" sessions",34,y+96,11,muted,Paint.Align.LEFT);
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
            float centerY=28; // alzato (era 36): il testo arrivava quasi a toccare l'inizio del contenuto scrollabile (bodyTop=44)
            // Freccia "indietro" rimossa: si torna sempre con il pulsante di sistema Android, non serve
            // duplicarla nell'interfaccia.
            txt(c,s.name,24,centeredBaseline(centerY,20),20,white,Paint.Align.LEFT);
            // Matita spostata di fianco al nome (prima era isolata all'estremita' destra dello schermo).
            p.setTextSize(20); float nameW=p.measureText(s.name);
            drawEditIcon(c,24+nameW+22,centerY,18,muted);
        }

        void sessionsList(Canvas c, Season s, float w, float h){
            sessionHits.clear();
            // Costruisce la lista aggregata di tutte le partite E, allo stesso tempo, gli indici in cui inizia
            // ogni nuova sessione: servono al grafico per disegnare una linea verticale piu' marcata in
            // corrispondenza dell'inizio di ciascuna sessione (la primissima non serve segnarla, coincide
            // gia' con l'inizio del grafico). La prima sessione della Stagione, se non tracciata (tipicamente
            // la correzione iniziale del wizard "avevo gia' giocato prima"), e' esclusa: e' un salto artificiale
            // dal baseline standard, non una serie di partite reali.
            ArrayList<Match> all = new ArrayList<>();
            ArrayList<Integer> sessionStarts = new ArrayList<>();
            for(int i=0;i<s.sessions.size();i++){
                Session se = s.sessions.get(i);
                if(i==0 && se.untracked) continue;
                if(se.matches.isEmpty()) continue;
                if(!all.isEmpty()) sessionStarts.add(all.size());
                all.addAll(se.matches);
            }
            int[] wl=countWL(s.sessions); int W=wl[0],L=wl[1];
            float wr=(W+L)==0?0:100f*W/(W+L);

            // ===== Card "ANDAMENTO STAGIONE": punti, partite e grafico aggregato SEMPRE in cima, non piu'
            // in fondo a una lista di sessioni che puo' diventare lunga. =====
            box(c,18,58,w-18,342,card);
            txt(c,"ANDAMENTO STAGIONE",34,80,12,muted,Paint.Align.LEFT);
            float c1L=30, c1R=w/2-6, c2L=w/2+6, c2R=w-30;
            // Box piu' alte e font piu' grandi (prima erano compresse e la riga W/L/% quasi illeggibile),
            // centrate verticalmente con centerLines() invece di offset fissi indovinati.
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
            drawChart(c,30,184,w-30,328,all,s,0,sessionStarts);

            // ===== Pulsante "Riprendi a giocare": scorciatoia immediata all'ultima sessione (stesso risultato
            // di aprire l'ultima card qui sotto), fuori da qualunque card, per l'azione piu' frequente. =====
            box(c,18,356,w-18,404,blue);
            drawBurstTabIcon(c,w/2-64,380,0.7f,white);
            txt(c,"Riprendi a giocare",w/2+10,386,15,white,Paint.Align.CENTER);

            // ===== Card "SESSIONI DI GIOCO": intestazione con "+" (nuova sessione) e "⋮" (azioni piu' rare,
            // es. sessione non tracciata). Altezza FISSA (non cresce con il numero di sessioni, che puo'
            // arrivare anche a 50+): la lista scorre al suo interno, con una scrollbar propria separata da
            // quella della schermata. =====
            float sessCardTop=418, sessListHeight=300;
            float sessListTop=sessCardTop+42, sessListBottom=sessListTop+sessListHeight;
            float sessCardBottom = sessListBottom+14;
            box(c,18,sessCardTop,w-18,sessCardBottom,card);
            txt(c,"SESSIONI DI GIOCO",34,sessCardTop+26,12,muted,Paint.Align.LEFT);
            // Solo il "+" ora (il menu "⋮" e' stato tolto): apre un piccolo menu con le due varianti di nuova
            // sessione. Spostato un pelo piu' a destra rispetto a prima (il "⋮" aveva un margine troppo stretto).
            txt(c,"+",w-32,sessCardTop+30,22,blue,Paint.Align.CENTER);

            resetSessInnerScrollIfNeeded("sessinner:"+store.current);
            float totalRowsHeight = s.sessions.size()*94f;
            sessInnerMaxScrollY = Math.max(0, totalRowsHeight-sessListHeight);
            if(sessInnerScrollY>sessInnerMaxScrollY) sessInnerScrollY=sessInnerMaxScrollY;
            if(sessInnerScrollY<0) sessInnerScrollY=0;
            sessInnerListTop=sessListTop; sessInnerListBottom=sessListBottom;

            c.save(); c.clipRect(18,sessListTop,w-18,sessListBottom); c.translate(0,-sessInnerScrollY);
            float y=sessListTop;
            for(int i=s.sessions.size()-1;i>=0;i--){
                Session se=s.sessions.get(i);
                boolean isLast = i==s.sessions.size()-1;
                boolean isActive = isLast && !se.untracked;
                // Bordo arancione (Variante A) intorno alla card della sessione attiva, invece dello sfondo
                // leggermente diverso di prima.
                box(c,30,y,w-30,y+86, Color.rgb(10,18,30));
                if(isActive) strokeBox(c,30,y,w-30,y+86,Color.rgb(255,138,61));
                // Il deck e' l'informazione piu' importante a colpo d'occhio (che mazzo si sta usando),
                // quindi ora e' lui il testo primario; il nome della sessione passa in secondo piano.
                txt(c, se.untracked ? "Sessione non tracciata" : deckDisplayShort(se.deck), 46,y+26,15,white,Paint.Align.LEFT);
                txt(c, se.name, 46,y+48,12,muted,Paint.Align.LEFT);
                // Timestamp iniziale della sessione (quando e' stata creata), non l'ultimo aggiornamento.
                txt(c, formatTimestamp(se.timestamp), 46,y+66,10,muted,Paint.Align.LEFT);
                int sw,sl;
                if(se.untracked){ sw=se.untrackedWins; sl=se.untrackedLosses; }
                else { sw=0; sl=0; for(Match m:se.matches)if(!m.unknown){if(m.win)sw++;else sl++;} }
                txtRowRight(c,w-46,y+30,13,new String[]{sw+"W ", ""+sl+"L"},new int[]{green,red});
                // Variazione di questa sessione: quanti punti netti ha portato, per capire a colpo d'occhio
                // quali sessioni sono state piu' fruttuose.
                int sessGain = se.untracked ? (se.endPoints-se.startPoints)
                    : (se.matches.isEmpty() ? 0 : (se.matches.get(se.matches.size()-1).after - se.matches.get(0).before));
                int sgcol = sessGain>0?green:(sessGain<0?red:muted);
                txt(c, (sessGain>0?"+":"")+sessGain, w-46, y+66, 12, sgcol, Paint.Align.RIGHT);
                sessionHits.add(new Hit(y,y+86,i));
                y+=94;
            }
            c.restore();

            // Scrollbar propria della card (separata da quella della schermata): solo se serve davvero.
            if(sessInnerMaxScrollY>1){
                float thumbH = Math.max(24, sessListHeight*(sessListHeight/totalRowsHeight));
                float thumbY = sessListTop + (sessListHeight-thumbH)*(sessInnerScrollY/sessInnerMaxScrollY);
                p.setColor(Color.rgb(45,60,85)); p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(w-22,thumbY,w-19,thumbY+thumbH,1.5f,1.5f,p);
            }

            lastContentBottom = sessCardBottom+20;
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
            int w=0,l=0; for(Session se:s.sessions) if(name.equals(se.deck)) for(Match m:se.matches) if(!m.unknown){ if(m.win) w++; else l++; }
            return new int[]{w,l};
        }

        // Variazione netta di punti portata da un deck (o dalle sessioni senza deck assegnato, con name="Unknown"):
        // somma di (dopo-prima) su tutte le partite di tutte le sessioni che usano quel deck, cosi' non conta
        // solo l'ultima sessione ma tutto il contributo storico, anche se ci sono state altre sessioni/deck di mezzo.
        int deckGain(Season s, String name){
            int total=0;
            for(Session se:s.sessions) if(name.equals(se.deck)) for(Match m:se.matches) total += (m.after-m.before);
            return total;
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

        // Card di un deck: usata SIA nel tab Deck SIA nella sezione "PER DECK" delle Statistiche, cosi'
        // l'aspetto e' identico in entrambi i posti. Ritorna la nuova posizione y dopo aver disegnato la card.
        float deckCard(Canvas c, String name, boolean isUnknown, int W, int L, int best, int gain, float y, float w, boolean showDelete){
            // Sfondo piu' scuro della card che lo contiene (prima era lo stesso colore: sembrava tutt'uno,
            // non una "sottocard" come le righe delle sessioni) e margine ai lati coerente con quello schema.
            box(c,30,y,w-30,y+92,Color.rgb(10,18,30));
            txt(c, isUnknown?"Deck sconosciuto":name, 46,y+26,17, isUnknown?muted:white, Paint.Align.LEFT);
            float wr=(W+L)==0?0:100f*W/(W+L);
            txt(c,(W+L)+" partite",46,y+46,12, isUnknown?muted:white, Paint.Align.LEFT);
            // Icona cestino per eliminare il deck: solo dove ha senso farlo (tab Deck), non in Statistiche
            // (assente anche per "Deck sconosciuto": e' un aggregato, non un deck vero).
            if (!isUnknown && showDelete) {
                Bitmap trash = makeTrashIcon(muted, 64);
                float ts=16, tx=w-30-10-ts, ty=y+8;
                android.graphics.Rect dst = new android.graphics.Rect((int)tx, (int)ty, (int)(tx+ts), (int)(ty+ts));
                c.drawBitmap(trash, null, dst, null);
            }
            // "Deck sconosciuto" e' un aggregato di sessioni senza un deck reale assegnato: la "serie di
            // vittorie" non ha senso concettualmente qui (mischia sessioni scollegate), quindi si omette.
            if (isUnknown) {
                txtRow(c,46,y+64,11,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
            } else {
                txtRow(c,46,y+64,11,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)+"   ", "Max vittorie consecutive "+best},
                    new int[]{green, red, wrColor(wr,W+L), muted});
            }
            // Variazione: quanti punti netti ha portato questo deck (o le sessioni senza deck), per capire
            // a colpo d'occhio dove si sono fatti piu' punti.
            int gcol = gain>0?green:(gain<0?red:muted);
            txt(c, "Variazione: "+(gain>0?"+":"")+gain, 46, y+82, 11, gcol, Paint.Align.LEFT);
            return y+104;
        }

        void decks(Canvas c,Season s,float w,float h){
            // Etichetta "Deck" rimossa: ridondante (il tab in basso mostra gia' "Deck" come sezione attiva).
            box(c,w-165,48,w-18,76,card); txt(c,"Ordina: "+deckSortLabel()+" ▾",w-91,68,13,white,Paint.Align.CENTER);
            float y=90;
            for(Deck d: sortedDecks(s)){
                int[] wl=deckWL(s,d.name); int best=longestStreakForDeck(s,d.name); int gain=deckGain(s,d.name);
                y = deckCard(c, d.name, false, wl[0], wl[1], best, gain, y, w, true);
            }
            // Sessioni senza un deck assegnato (create "al volo" con Salta): le statistiche restano comunque visibili
            // qui, cosi' l'utente puo' verificarle finche' non assegna un deck vero (le stats si aggiornano da sole).
            int[] nd = noDeckWL(s);
            if (nd[0]+nd[1] > 0) {
                int ndbest=longestStreakForDeck(s,"Unknown"); int ndgain=deckGain(s,"Unknown");
                y = deckCard(c, null, true, nd[0], nd[1], ndbest, ndgain, y, w, true);
            }
            box(c,18,y,w-18,y+58,blue);txt(c,"AGGIUNGI DECK",w/2,y+37,14,white,Paint.Align.CENTER);
            lastContentBottom = y+58+20;
        }

        // Statistiche aggregate (W/L) delle sessioni senza un deck assegnato in questa Stagione.
        int[] noDeckWL(Season s){
            int w=0,l=0;
            for(Session se:s.sessions) if("Unknown".equals(se.deck)){
                if(se.untracked){ w+=se.untrackedWins; l+=se.untrackedLosses; }
                else for(Match m:se.matches) if(!m.unknown){ if(m.win) w++; else l++; }
            }
            return new int[]{w,l};
        }

        // Longest win streak "attribuibile" a un deck: usa il valore m.streak gia' calcolato globalmente dalla
        // sessione di gioco (che persiste correttamente tra una sessione e la successiva, si azzera solo su una
        // sconfitta). Cosi' se lo stesso deck continua a vincere su piu' sessioni consecutive, lo streak si somma
        // automaticamente; se in mezzo c'e' una sconfitta (con qualsiasi deck), la catena si interrompe correttamente.
        // Longest win streak *davvero attribuibile* a un deck: attraversa la cronologia in ordine e mantiene
        // una serie SOLO finche' le vittorie consecutive sono state giocate tutte con questo stesso deck.
        // Una sconfitta, una vittoria con un deck diverso, o una sessione non tracciata interrompono la serie:
        // non possiamo sapere con quale deck (se non tracciato) o se con questo deck siano state ottenute le
        // vittorie precedenti, quindi non gliele attribuiamo.
        int longestStreakForDeck(Season s, String deckName){
            int best=0, run=0;
            for(Session se: s.sessions){
                if(se.untracked){ run=0; continue; }
                boolean thisDeck = deckName.equals(se.deck);
                for(Match m: se.matches){
                    if(m.unknown) continue;
                    if(m.win){ if(thisDeck){ run++; best=Math.max(best,run); } else { run=0; } }
                    else { run=0; }
                }
            }
            return best;
        }

        void stats(Canvas c,Season s,float w,float h){
            // Riorganizzato in card, stessa coerenza grafica del tab Gioca (card "Andamento Stagione" e
            // "Sessioni di gioco"): prima era una lista di testo grezza senza alcun raggruppamento visivo.
            ArrayList<Match> all=s.allMatches();int maxStreak=s.initialStreak,cur= s.initialStreak;
            for(Match m:all){if(m.unknown)continue;if(m.win){cur++;maxStreak=Math.max(maxStreak,cur);}else{cur=0;}}
            int[] wl=countWL(s.sessions); int W=wl[0],L=wl[1];
            float wr=(W+L)==0?0:100f*W/(W+L);
            int gain = s.points-s.baseline;
            ArrayList<Deck> sd = sortedDecks(s);
            int[] nd = noDeckWL(s);
            int deckPlayedCount = sd.size() + (nd[0]+nd[1]>0 ? 1 : 0);

            // ===== Card "STATISTICHE STAGIONE": griglia con tutti i dati aggregati, margine ai lati delle
            // sotto-card (prima mancava, restavano attaccate al bordo della card esterna) e font piu' leggibili,
            // centrate con centerLines() invece di offset fissi indovinati. =====
            box(c,18,58,w-18,358,card);
            txt(c,"STATISTICHE STAGIONE",34,80,12,muted,Paint.Align.LEFT);
            float c1L=30, c1R=w/2-6, c2L=w/2+6, c2R=w-30;
            // Riga 1: Partite totali | W/L/%
            box(c,c1L,92,c1R,152,Color.rgb(10,18,30));
            float[] r1a = centerLines(122,6,10,22);
            txt(c,"PARTITE TOTALI",(c1L+c1R)/2,r1a[0],10,muted,Paint.Align.CENTER);
            txt(c,""+(W+L),(c1L+c1R)/2,r1a[1],22,white,Paint.Align.CENTER);
            box(c,c2L,92,c2R,152,Color.rgb(10,18,30));
            float[] r1b = centerLines(122,6,10,15);
            txt(c,"W / L / %",(c2L+c2R)/2,r1b[0],10,muted,Paint.Align.CENTER);
            txtRowCentered(c,(c2L+c2R)/2,r1b[1],15,
                new String[]{W+"W  ", L+"L  ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});
            // Riga 2: Vittorie consecutive attuali | massime (prima erano nella stessa card)
            box(c,c1L,160,c1R,220,Color.rgb(10,18,30));
            float[] r2a = centerLines(190,6,10,22);
            txt(c,"VITTORIE CONSECUTIVE",(c1L+c1R)/2,r2a[0],10,muted,Paint.Align.CENTER);
            txt(c,""+s.streak,(c1L+c1R)/2,r2a[1],22,white,Paint.Align.CENTER);
            box(c,c2L,160,c2R,220,Color.rgb(10,18,30));
            float[] r2b = centerLines(190,6,10,22);
            txt(c,"MASSIME",(c2L+c2R)/2,r2b[0],10,muted,Paint.Align.CENTER);
            txt(c,""+maxStreak,(c2L+c2R)/2,r2b[1],22,white,Paint.Align.CENTER);
            // Riga 3: Punti | Variazione
            box(c,c1L,228,c1R,288,Color.rgb(10,18,30));
            float[] r3a = centerLines(258,6,10,22);
            txt(c,"PUNTI",(c1L+c1R)/2,r3a[0],10,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,r3a[1],22,white,Paint.Align.CENTER);
            box(c,c2L,228,c2R,288,Color.rgb(10,18,30));
            float[] r3b = centerLines(258,6,10,22);
            txt(c,"VARIAZIONE",(c2L+c2R)/2,r3b[0],10,muted,Paint.Align.CENTER);
            txt(c, (gain>0?"+":"")+gain,(c2L+c2R)/2,r3b[1],22, gain>0?green:(gain<0?red:white),Paint.Align.CENTER);
            // Riga 4: Deck giocati (nuova, a tutta larghezza — conta anche "Deck sconosciuto" se ha partite)
            box(c,c1L,296,c2R,344,Color.rgb(10,18,30));
            float[] r4 = centerLines(320,6,10,20);
            txt(c,"DECK GIOCATI",w/2,r4[0],10,muted,Paint.Align.CENTER);
            txt(c,""+deckPlayedCount,w/2,r4[1],20,white,Paint.Align.CENTER);

            // ===== Card "PER DECK": altezza FISSA (come "Sessioni di gioco" nel tab Gioca), lista scorrevole
            // al suo interno con scrollbar propria — qui i deck non sono eliminabili (a differenza del tab Deck). =====
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
                dyy = deckCard(c, d.name, false, dwl[0], dwl[1], dbest, dgain, dyy, w, false);
            }
            if (nd[0]+nd[1] > 0) {
                int ndbest=longestStreakForDeck(s,"Unknown"); int ndgain=deckGain(s,"Unknown");
                dyy = deckCard(c, null, true, nd[0], nd[1], ndbest, ndgain, dyy, w, false);
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

        // Icona "indietro" (chevron sottile, stile standard), disegnata a mano per lo stesso motivo delle
        // altre icone custom dell'app: coerenza garantita, non dipende dal rendering di un font.
        void drawChevronBack(Canvas c, float cx, float cy, float size, int color){
            p.setColor(color); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size*0.14f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(cx+size*0.18f, cy-size*0.32f);
            path.lineTo(cx-size*0.18f, cy);
            path.lineTo(cx+size*0.18f, cy+size*0.32f);
            c.drawPath(path,p);
        }

        void sessionPlay(Canvas c, Season s, float w, float h){
            Session x=s.sessions.get(s.currentSession);
            int idx=s.currentSession;
            boolean isLast = idx==s.sessions.size()-1;
            boolean canConvert = x.matches.isEmpty() && !x.untracked; // solo se la sessione non ha ancora partite
            // Le azioni secondarie (Nuova sessione, Modifica deck, Segna come non tracciata, Elimina sessione)
            // sono ora tutte raccolte nel menu "⋮", invece di essere pulsanti sempre visibili in fondo: il
            // menu compare solo se almeno una di queste azioni e' applicabile alla sessione corrente.
            boolean showNewSessionItem = isLast && !x.untracked;
            boolean showDeckEditItem = !x.untracked;
            boolean showEditUntrackedItem = x.untracked; // "Modifica sessione": prima era una matita/pallini dentro la card, ora unificata qui
            boolean showDeleteItem = isLast;
            boolean showKebab = showNewSessionItem || showDeckEditItem || canConvert || showDeleteItem || showEditUntrackedItem;
            float centerY=36;
            // Chevron "indietro": si torna alla lista sessioni (niente piu' passaggio diretto tra sessioni
            // con prev/next, rimosso — per aprirne un'altra si torna alla lista e se ne sceglie una).
            drawChevronBack(c,24,centerY,20,white);
            // Titolo = deck (l'informazione piu' importante a colpo d'occhio), sottotitolo = nome sessione
            // (prima era il contrario, con una card DECK separata sotto — ora rimossa, il deck vive nel titolo).
            String deckTitle = x.untracked ? "Sessione non tracciata" : ("Unknown".equals(x.deck) ? "Deck sconosciuto" : x.deck);
            txt(c, deckTitle, 44, 30, 19, white, Paint.Align.LEFT);
            txt(c, x.name, 44, 48, 12, muted, Paint.Align.LEFT);
            if(showKebab) drawKebabIcon(c,w-24,centerY,muted);

            // Header fisso sopra, il resto scorre. Niente piu' barra fissa in fondo (era solo prev/next,
            // rimossi): tutto lo spazio verticale torna disponibile per il contenuto.
            bodyTop=58; bodyBottom=h;
            resetScrollIfNeeded("play:"+store.current+":"+idx);
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);

            if(x.untracked){
                box(c,18,58,w-18,140,card);
                txt(c,"SESSIONE NON TRACCIATA",32,80,11,muted,Paint.Align.LEFT);
                txt(c,"Punti "+x.startPoints+" → "+x.endPoints,32,104,15,white,Paint.Align.LEFT);
                txtRow(c,32,128,12,new String[]{x.untrackedWins+"W  ", ""+x.untrackedLosses+"L"},new int[]{green,red});
                netGainRow(c,18,146,220,w-18,x.endPoints-x.startPoints);
                txt(c,"Crea una nuova sessione per continuare a giocare.",18,244,13,muted,Paint.Align.LEFT);
                drawChart(c,18,260,w-18,260+260,x.matches,s,x.timestamp,null);
                lastContentBottom = 260+260+20;
            } else {
                // Card DECK rimossa: il deck e' ora nel titolo dell'header, non serve piu' ripeterlo qui.
                int gain = x.matches.isEmpty()?0:(x.matches.get(x.matches.size()-1).after - x.matches.get(0).before);
                // Griglia 2x2: Punti attuali/Statistiche sopra, Vittorie consecutive/Variazione sotto
                // (prima le 3 card affiancate avevano fatto sparire W/L/% della sessione corrente: ora c'e'
                // una card dedicata "STATISTICHE" che le mostra di nuovo).
                int[] sessWL = countWL(java.util.Collections.singletonList(x));
                int sw=sessWL[0], sl=sessWL[1];
                float swr = (sw+sl)==0 ? 0 : 100f*sw/(sw+sl);
                float gap2=8; float colW=(w-36-gap2)/2;
                float c1L=18, c1R=18+colW, c2L=c1R+gap2, c2R=w-18;
                box(c,c1L,66,c1R,140,card);
                float[] g1 = centerLines(103,6,11,26);
                txt(c,"PUNTI ATTUALI",(c1L+c1R)/2,g1[0],11,muted,Paint.Align.CENTER);
                txt(c,""+s.points,(c1L+c1R)/2,g1[1],26,white,Paint.Align.CENTER);
                box(c,c2L,66,c2R,140,card);
                float[] g2 = centerLines(103,6,11,14);
                txt(c,"STATISTICHE",(c2L+c2R)/2,g2[0],11,muted,Paint.Align.CENTER);
                txtRowCentered(c,(c2L+c2R)/2,g2[1],14,
                    new String[]{sw+"W   ", sl+"L   ", String.format(Locale.US,"%.1f%%",swr)},
                    new int[]{green,red,white});
                box(c,c1L,148,c1R,222,card);
                float[] g3 = centerLines(185,6,11,26);
                txt(c,"VITTORIE CONSECUTIVE",(c1L+c1R)/2,g3[0],11,muted,Paint.Align.CENTER);
                txt(c,""+s.streak,(c1L+c1R)/2,g3[1],26,white,Paint.Align.CENTER);
                netGainRow(c,c2L,148,222,c2R,gain);

                if(!isLast){
                    box(c,18,230,w-18,276,card); txt(c,"Sessione conclusa",w/2,258,13,muted,Paint.Align.CENTER);
                    drawChart(c,18,290,w-18,290+260,x.matches,s,x.timestamp,null);
                    lastContentBottom = 290+260+20;
                } else {
                    float gL=18, gR=w/2-8, rL=w/2+8, rR=w-18;
                    box(c,gL,230,gR,294,green); box(c,rL,230,rR,294,red);
                    txt(c,"W",(gL+gR)/2,266,22,Color.WHITE,Paint.Align.CENTER); txt(c,"(+"+reward(s.streak+1)+")",(gL+gR)/2,286,13,Color.WHITE,Paint.Align.CENTER);
                    txt(c,"L",(rL+rR)/2,266,22,Color.WHITE,Paint.Align.CENTER); txt(c,"(−10)",(rL+rR)/2,286,13,Color.WHITE,Paint.Align.CENTER);
                    box(c,18,306,w/2-8,352,card); box(c,w/2+8,306,w-18,352,card);
                    txt(c,"↶  ANNULLA",w/4,335,16,white,Paint.Align.CENTER); txt(c,"↷  RIPETI",w*3/4,335,16,white,Paint.Align.CENTER);
                    drawChart(c,18,364,w-18,364+260,x.matches,s,x.timestamp,null);
                    lastContentBottom = 364+260+20;
                }
            }
            c.restore();
            finishScroll(); drawScrollbar(c,w);
        }

        // Riga con il guadagno netto della sessione (ultimo punteggio - punteggio di partenza di QUESTA sessione).
        // Ora centrata verticalmente (etichetta sopra, numero grande sotto), come le altre card "punti attuali"
        // e "vittorie consecutive" — non piu' una riga orizzontale con etichetta piccola a sinistra.
        // 'left'/'right' espliciti invece di assumere sempre tutta larghezza: serve sia a tutta larghezza
        // (sessione non tracciata) sia in una singola colonna (fianco a fianco con Vittorie consecutive).
        void netGainRow(Canvas c, float left, float top, float bottom, float right, int gain){
            box(c,left,top,right,bottom,card);
            float cx=(left+right)/2;
            float[] g = centerLines((top+bottom)/2,6,11,26);
            txt(c,"VARIAZIONE",cx,g[0],11,muted,Paint.Align.CENTER);
            int col = gain>0?green:(gain<0?red:white);
            txt(c, (gain>0?"+":"")+gain, cx, g[1], 26, col, Paint.Align.CENTER);
        }

        void strokeBox(Canvas c,float l,float t,float rr,float b,int col){
            p.setColor(col); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
            c.drawRoundRect(l,t,rr,b,16,16,p);
            p.setStyle(Paint.Style.FILL);
        }

        // Riga NOTE separata dal box DECK: prima la nota condivideva lo spazio con lo screenshot del deck ed era
        // facile confonderla con "cambia deck". Ora e' una riga a se', con la sua etichetta, cosi' non c'e' ambiguita'.

        void drawChart(Canvas c,float l,float t,float rr,float b,List<Match> ms,Season s,long sessionTimestamp,List<Integer> sessionStarts){
            box(c,l,t,rr,b,Color.rgb(10,18,30));
            // Griglia orizzontale, colore vicino allo sfondo (schiarito un pelo rispetto a prima): solo un
            // riferimento visivo, poche righe (3) per non risultare fastidiosa ne' troppo fitta.
            int gridColor = Color.rgb(26,38,56);
            p.setColor(gridColor); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE);
            int gridLines=3;
            for(int i=1;i<=gridLines;i++){
                float gy = t+22 + i*(b-t-42)/(gridLines+1);
                c.drawLine(l+8,gy,rr-8,gy,p);
            }
            // Timestamp della sessione (quando e' stata creata): assente (0) per il grafico aggregato di
            // piu' sessioni, dove non avrebbe senso un singolo orario.
            if (sessionTimestamp>0) txt(c, formatDateOnly(sessionTimestamp), (l+rr)/2, t+16, 10, muted, Paint.Align.CENTER);
            if(ms.isEmpty()){
                txt(c,"Nessuna partita ancora",(l+rr)/2,(t+b)/2,13,muted,Paint.Align.CENTER);
                return;
            }
            float min=ms.get(0).before,max=ms.get(0).before;for(Match m:ms){min=Math.min(min,m.after);max=Math.max(max,m.after);}
            min-=20;max+=20;if(max==min)max=min+1;
            int n=ms.size();

            // Griglia verticale completa: una colonna per OGNI partita (prima solo su 4 punti scelti), disegnata
            // PRIMA della linea/pallini del grafico, cosi' il plot resta sempre sopra. Nel grafico aggregato,
            // le colonne in corrispondenza dell'inizio di una sessione restano piu' marcate delle altre.
            // Colonne verticali SOLO in corrispondenza dell'inizio di una nuova sessione (nel grafico
            // aggregato): prima ce n'era una per OGNI partita, troppo fitte e confuse visivamente.
            if(sessionStarts!=null && !sessionStarts.isEmpty()){
                p.setColor(Color.rgb(52,68,96)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
                for(int idx: sessionStarts){
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
            // Pallini su ogni punto del grafico, cosi' ogni singola partita resta chiaramente visibile.
            p.setStyle(Paint.Style.FILL);
            p.setColor(muted); c.drawCircle(startX,startY,4,p);
            for(int i=0;i<ms.size();i++){
                Match m=ms.get(i);float x=l+12+(i+1)*(rr-l-24)/Math.max(1,ms.size());
                float y=b-20-(m.after-min)/(max-min)*(b-t-36);
                p.setColor(m.unknown?Color.GRAY:(m.win?green:red));
                c.drawCircle(x,y,4,p);
            }
            // Rimosse: etichetta asse Y (valore minimo), "PARTITE" e "ORA: N" — resta solo "INIZIO: N".
            txt(c,"INIZIO: "+ms.get(0).before,l+10,t+34,12,white,Paint.Align.LEFT);
        }

        // Icona "scoppio" per il tab Gioca: stessa forma esatta usata nell'icona dell'app (solo riscalata),
        // per coerenza visiva tra icona e navigazione interna.
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
        int innerDragTarget=0; // 0=nessuno, 1=lista sessioni (tab Gioca), 2=lista deck (tab Statistiche)

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            // Le coordinate del tocco arrivano in pixel reali dell'intera View: le convertiamo nello stesso
            // sistema "dp con origine sotto la status bar" usato in onDraw, altrimenti i tap non
            // corrisponderebbero piu' a quello che e' disegnato sullo schermo.
            float x=(e.getX()-getPaddingLeft())/density, y=(e.getY()-getPaddingTop())/density;
            float w=(getWidth()-getPaddingLeft()-getPaddingRight())/density;
            float h=(getHeight()-getPaddingTop()-getPaddingBottom())/density;

            // Drag verticale per lo scroll: solo se il dito parte dentro la zona di contenuto scrollabile
            // (bodyTop/bodyBottom, calcolati dall'ultimo onDraw). Se il movimento supera una piccola soglia,
            // il gesto diventa uno scroll e NON viene piu' interpretato come tap al rilascio.
            // Se il dito parte invece dentro la lista di una delle card ad altezza fissa (Sessioni di gioco
            // nel tab Gioca, Per Deck nel tab Statistiche) e c'e' davvero qualcosa da scorrere li' dentro, il
            // trascinamento va allo scroll INTERNO di quella card, non a quello generale della schermata.
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                touchDownX=x; touchDownY=y; touchStartScrollY=scrollY;
                isDragging=false; isDraggingInner=false; innerDragTarget=0;
                boolean overSessions = screen==SCREEN_SEASON_DETAIL && detailTab==0 && sessInnerMaxScrollY>0
                    && y>=(sessInnerListTop-scrollY) && y<=(sessInnerListBottom-scrollY) && x>=18 && x<=w-18;
                boolean overStatsDeck = screen==SCREEN_SEASON_DETAIL && detailTab==2 && statsDeckInnerMaxScrollY>0
                    && y>=(statsDeckInnerListTop-scrollY) && y<=(statsDeckInnerListBottom-scrollY) && x>=18 && x<=w-18;
                if(overSessions){ innerDragTarget=1; touchStartInnerScrollY=sessInnerScrollY; }
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
                        sessInnerScrollY = touchStartInnerScrollY + dy;
                        if(sessInnerScrollY<0) sessInnerScrollY=0; if(sessInnerScrollY>sessInnerMaxScrollY) sessInnerScrollY=sessInnerMaxScrollY;
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
            if(isDragging || isDraggingInner){ isDragging=false; isDraggingInner=false; innerDragTarget=0; return true; } // era uno scroll: nessuna azione di tap

            // Se il tocco cade nella zona di contenuto scrollabile, la convertiamo in coordinate "di
            // contenuto" (aggiungendo lo scroll corrente), perche' i box disegnati li' sono stati spostati
            // in alto di scrollY. Fuori da quella zona (header, barre fisse) restano le coordinate dello schermo.
            float contentY = (y>=bodyTop && y<=bodyBottom) ? y+scrollY : y;

            if(screen==SCREEN_SEASON_LIST){
                if(y>=h-104 && y<=h-54 && x>=w-166){ newSeason(); return true; } // pulsante fisso, non scrolla
                p.setTextSize(12); float resetTw=p.measureText("Cancella tutti i dati");
                if(contentY>=resetLinkY-16 && contentY<=resetLinkY+16 && x>=w/2-(resetTw+32)/2 && x<=w/2+(resetTw+32)/2){ resetAllData(); return true; }
                for(Hit hit: seasonHits){ if(contentY>=hit.top&&contentY<=hit.bottom){ store.current=hit.index; screen=SCREEN_SEASON_DETAIL; detailTab=0; store.save(); invalidate(); return true; } }
                return true;
            }

            Season s = store.seasons.get(store.current);

            if(screen==SCREEN_SESSION_PLAY){
                Session sess = s.sessions.get(s.currentSession);
                int idx = s.currentSession;
                boolean isLast = idx==s.sessions.size()-1;
                boolean canConvert = sess.matches.isEmpty() && !sess.untracked;
                boolean showNewSessionItem = isLast && !sess.untracked;
                boolean showDeckEditItem = !sess.untracked;
                boolean showEditUntrackedItem = sess.untracked;
                boolean showDeleteItem = isLast;
                boolean showKebab = showNewSessionItem || showDeckEditItem || canConvert || showDeleteItem || showEditUntrackedItem;
                if(y<52){
                    if(x<60){ goBack(); return true; } // chevron "indietro": torna alla lista sessioni
                    if(showKebab && x>w-56){ sessionOptionsMenu(sess, isLast, canConvert, w-18, 52); return true; } // icona "⋮": menu ancorato subito sotto l'icona, margine destro standard (w-18) anziche' un offset sinistro indovinato
                    return true;
                }
                // Da qui in giu' siamo nel contenuto scrollabile: usa 'contentY'.
                if(sess.untracked){
                    return true; // il tocco sul glifo "⋮" e' ora gestito dall'header (stessa posizione della sessione normale)
                }
                // Card DECK rimossa (il deck e' ora nel titolo dell'header): nessuna zona di tocco dedicata qui.
                if(isLast && contentY>=230 && contentY<=294){ if(x<w/2) win(); else loss(); return true; }
                if(isLast && contentY>=306 && contentY<=352){ if(x<w/2) undo(); else redo(); return true; }
                return true;
            }

            // SCREEN_SEASON_DETAIL: header (y<52) e barra tab in basso (y>h-58) sono fissi, usa 'y' grezza;
            // tutto il resto e' contenuto scrollabile, usa 'contentY'.
            if(y<52){
                p.setTextSize(20); float nameW=p.measureText(s.name);
                if(x>=24+nameW+8){ renameSeason(); return true; } // matita: ora di fianco al nome, non piu' isolata a destra
                return true;
            }
            if(y>h-58){ detailTab=Math.min(2,(int)(x/(w/3))); invalidate(); return true; }
            if(detailTab==0){
                if(contentY>=356&&contentY<=404){ // "Riprendi a giocare": stesso comportamento di aprire l'ultima sessione
                    if(!s.sessions.isEmpty()){ s.currentSession=s.sessions.size()-1; screen=SCREEN_SESSION_PLAY; invalidate(); }
                    return true;
                }
                float sessCardTop=418;
                if(contentY>=sessCardTop&&contentY<=sessCardTop+40&&x>=w-46){
                    // "+": apre un menu con le due varianti di nuova sessione (prima era un'azione diretta,
                    // con "Sessione non tracciata" solo nel menu "⋮" separato — ora tutto qui).
                    showAnchoredMenu(w-30, sessCardTop+40, new String[]{"Nuova sessione","Sessione non tracciata"}, new int[]{Color.WHITE,Color.WHITE}, new Runnable[]{MainActivity.this::showNewSession, MainActivity.this::showUntracked});
                    return true;
                }
                // I box delle sessioni sono disegnati dentro lo scroll INTERNO della card: bisogna sommare
                // sessInnerScrollY a contentY per tornare alle stesse coordinate usate nel disegno.
                float sessContentY = contentY + sessInnerScrollY;
                for(Hit hit: sessionHits){ if(sessContentY>=hit.top&&sessContentY<=hit.bottom){ s.currentSession=hit.index; screen=SCREEN_SESSION_PLAY; invalidate(); return true; } }
            } else if(detailTab==1){
                if(contentY>=48&&contentY<=76&&x>=w-165){ showDeckSortMenu(); return true; }
                float yy=90;
                for(Deck d: sortedDecks(s)){
                    if(contentY>=yy&&contentY<=yy+40&&x>=w-70){ confirmDeleteDeck(s,d); return true; } // icona cestino: priorita' sul tap generico della card
                    if(contentY>=yy&&contentY<=yy+92){ openDeckImages(d); return true; }
                    yy+=104;
                }
                int[] nd = noDeckWL(s);
                if (nd[0]+nd[1] > 0) yy+=104; // riga "Deck sconosciuto": nessuna azione, ma avanza comunque (bug corretto: prima disallineava il pulsante sotto)
                if(contentY>=yy&&contentY<=yy+58){ addDeck(); return true; }
            } else if(detailTab==2){
                float deckCardTop=374;
                if(contentY>=deckCardTop+12&&contentY<=deckCardTop+40&&x>=w-165){ showDeckSortMenu(); return true; }
                // I deck sono disegnati dentro lo scroll INTERNO di questa card: sommare statsDeckInnerScrollY
                // per tornare alle stesse coordinate usate nel disegno (niente cestino qui: non eliminabili).
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

    static class State {
        int points,streak,sessionIndex;
        Match match; // match associato all'azione, per un undo/redo esatto (puo' essere null)
        State(int p,int s,int i){this(p,s,i,null);}
        State(int p,int s,int i,Match m){points=p;streak=s;sessionIndex=i;match=m;}
    }
    static class Match {
        boolean win,unknown;int before,after,streak;long timestamp;
        Match(boolean w,int b,int a,int st){win=w;before=b;after=a;streak=st;timestamp=System.currentTimeMillis();}
        static Match untracked(int b,int a){Match m=new Match(a>=b,b,a,0);m.unknown=true;return m;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("w",win);o.put("u",unknown);o.put("b",before);o.put("a",after);o.put("s",streak);o.put("ts",timestamp);return o;}
        static Match from(JSONObject o)throws Exception{Match m=new Match(o.getBoolean("w"),o.getInt("b"),o.getInt("a"),o.optInt("s",0));m.timestamp=o.optLong("ts",0);return m;}
    }
    static class Deck {
        String name; ArrayList<String> images=new ArrayList<>(); Deck(String n){name=n;}
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
    static class Session {String name,deck,notes;boolean untracked;int startPoints,endPoints,startStreak,endStreak,untrackedWins,untrackedLosses;long timestamp;ArrayList<Match> matches=new ArrayList<>();Session(String n,String d){name=n;deck=d;timestamp=System.currentTimeMillis();}JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("n",name);o.put("d",deck);o.put("u",untracked);o.put("sp",startPoints);o.put("ep",endPoints);o.put("ss",startStreak);o.put("es",endStreak);o.put("uw",untrackedWins);o.put("ul",untrackedLosses);o.put("ts",timestamp);if(notes!=null)o.put("notes",notes);JSONArray a=new JSONArray();for(Match m:matches)a.put(m.json());o.put("m",a);return o;}static Session from(JSONObject o)throws Exception{Session s=new Session(o.optString("n"),o.optString("d"));s.untracked=o.optBoolean("u");s.startPoints=o.optInt("sp");s.endPoints=o.optInt("ep");s.startStreak=o.optInt("ss");s.endStreak=o.optInt("es");s.untrackedWins=o.optInt("uw");s.untrackedLosses=o.optInt("ul");s.timestamp=o.optLong("ts",0);s.notes=o.optString("notes",null);JSONArray a=o.optJSONArray("m");if(a!=null)for(int i=0;i<a.length();i++)s.matches.add(Match.from(a.getJSONObject(i)));return s;}}
    static class Season {
        String name;int baseline,initialStreak,points,streak,currentSession=0;ArrayList<Deck> decks=new ArrayList<>();ArrayList<Session> sessions=new ArrayList<>();Stack<State> undo=new Stack<>(),redo=new Stack<>();
        Season(String n){name=n;}
        ArrayList<Match> allMatches(){ArrayList<Match>a=new ArrayList<>();for(Session s:sessions)a.addAll(s.matches);return a;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("n",name);o.put("b",baseline);o.put("is",initialStreak);o.put("p",points);o.put("s",streak);o.put("cs",currentSession);JSONArray d=new JSONArray();for(Deck x:decks)d.put(x.json());o.put("d",d);JSONArray ss=new JSONArray();for(Session x:sessions)ss.put(x.json());o.put("ss",ss);return o;}
        static Season from(JSONObject o)throws Exception{Season s=new Season(o.optString("n"));s.baseline=o.optInt("b");s.initialStreak=o.optInt("is");s.points=o.optInt("p");s.streak=o.optInt("s");s.currentSession=o.optInt("cs");JSONArray d=o.optJSONArray("d");if(d!=null)for(int i=0;i<d.length();i++)s.decks.add(Deck.from(d.getJSONObject(i)));JSONArray ss=o.optJSONArray("ss");if(ss!=null)for(int i=0;i<ss.length();i++)s.sessions.add(Session.from(ss.getJSONObject(i)));return s;}
    }
    static class Store {
        SharedPreferences pref;ArrayList<Season> seasons=new ArrayList<>();int current=0;
        Store(Context c){pref=c.getSharedPreferences("tracker",0);load();}
        void save(){try{JSONObject o=new JSONObject();JSONArray a=new JSONArray();for(Season s:seasons)a.put(s.json());o.put("seasons",a);o.put("current",current);pref.edit().putString("data",o.toString()).apply();}catch(Exception e){Log.e(TAG,"Errore nel salvataggio dati",e);}}
        void load(){try{String z=pref.getString("data",null);if(z==null)return;JSONObject o=new JSONObject(z);current=o.optInt("current");JSONArray a=o.optJSONArray("seasons");if(a!=null)for(int i=0;i<a.length();i++)seasons.add(Season.from(a.getJSONObject(i)));if(migrateSessionNames())save();}catch(Exception e){Log.e(TAG,"Errore nel caricamento dati, si riparte da zero",e);}}
        // Migrazione: sessioni create con build precedenti alla traduzione italiana erano rimaste nominate
        // "Session N" (inglese) invece di "Sessione di gioco N". Le rinomina automaticamente al primo
        // caricamento dopo l'aggiornamento, cosi' l'utente non deve farlo a mano.
        boolean migrateSessionNames(){
            boolean changed=false;
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^Session (\\d+)$");
            for(Season s: seasons){
                for(Session se: s.sessions){
                    java.util.regex.Matcher m = pat.matcher(se.name);
                    if(m.matches()){ se.name = "Sessione di gioco "+m.group(1); changed=true; }
                }
            }
            return changed;
        }
    }
}
