package com.stefanorossano.pockettracker;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
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
    // Versione build: major.minor decisi da Stefano quando serve, build incrementato di 1 ad OGNI modifica
    // (anche piccola) che produce una nuova build — non solo per feature, e' un contatore di iterazioni.
    static final String APP_VERSION = "v0.2.186";

    // Livelli di navigazione dell'app (schermata attualmente mostrata).
    static final int SCREEN_SEASON_LIST = 0;   // Lista delle Stagioni
    static final int SCREEN_SEASON_DETAIL = 1; // Dettaglio Season: tab Gioca / Deck / Statistiche
    static final int SCREEN_SETTINGS = 2;      // Impostazioni: nome allenatore, cancella dati

    static final int DEFAULT_BASELINE = 810; // Punteggio di partenza standard per una nuova Stagione

    int screen = SCREEN_SEASON_LIST;
    TrackerView view;
    // Contenitore che avvolge la TrackerView (il canvas): serve per poter sovrapporre la barra di ricerca
    // deck, che essendo un vero campo di testo con tastiera/focus non puo' essere solo disegnata sul canvas.
    FrameLayout rootContainer;
    LinearLayout deckSearchBar;
    EditText deckSearchInput;
    TextView deckSearchClearBtn;
    Store store;

    // Lingue supportate: inglese di default (non l'italiano, e non la lingua di sistema) finche' l'utente
    // non sceglie diversamente da Impostazioni. "semplice, senza bandiera": solo il nome, niente icone.
    static final String[] LANGUAGE_CODES = {"en","de","it","fr","es"};
    static final String[] LANGUAGE_LABELS = {"English","Deutsch","Italiano","Français","Español"};

    // Applica la lingua salvata PRIMA che l'Activity venga creata (attachBaseContext e' chiamato prima di
    // onCreate) — legge le SharedPreferences direttamente, dato che l'oggetto Store normale non esiste
    // ancora a questo punto del ciclo di vita.
    @Override protected void attachBaseContext(Context base) {
        SharedPreferences prefs = base.getSharedPreferences("tracker", 0);
        String lang = prefs.getString("language", "en");
        java.util.Locale locale = new java.util.Locale(lang);
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        Context context = base.createConfigurationContext(config);
        super.attachBaseContext(context);
    }

    // Dialog di scelta lingua (Impostazioni): elenco semplice (nessuna bandiera), Annulla/Conferma. Se la
    // lingua scelta e' diversa da quella attuale, salva e richiama recreate() — l'unico modo pulito per far
    // riapplicare attachBaseContext() con la nuova configurazione senza uscire e rientrare dall'app a mano.
    void showLanguageDialog(){
        String[] selectedLang = { store.language };
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView title = new TextView(this); title.setText("Lingua / Language"); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.bottomMargin=dp(4);
        root.addView(title, titleLp);

        TextView[] rows = new TextView[LANGUAGE_CODES.length];
        for (int i=0;i<LANGUAGE_CODES.length;i++){
            final String code = LANGUAGE_CODES[i];
            TextView t = new TextView(this); t.setText(LANGUAGE_LABELS[i]); t.setTextSize(16);
            t.setPadding(dp(18),dp(14),dp(18),dp(14));
            rows[i]=t;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            root.addView(t, tlp);
            t.setOnClickListener(v -> {
                selectedLang[0]=code;
                for (int j=0;j<rows.length;j++) rows[j].setTextColor(LANGUAGE_CODES[j].equals(code)?blueColor():Color.WHITE);
            });
        }
        for (int i=0;i<rows.length;i++) rows[i].setTextColor(LANGUAGE_CODES[i].equals(selectedLang[0])?blueColor():Color.WHITE);

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(10),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText("Annulla"); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(14);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText("Conferma"); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(14);
        confirmBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(cancelBtn); footer.addView(confirmBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (!selectedLang[0].equals(store.language)) {
                store.language = selectedLang[0]; store.save();
                recreate();
            }
        });
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        // La view serve sempre come sfondo, anche solo per ospitare i dialog di onboarding: creata subito,
        // indipendentemente da quale flusso segue (onboarding, wizard Stagione, o ripristino normale).
        setupTrackerView();
        screen = SCREEN_SEASON_LIST;

        // L'onboarding ("come ti chiami, allenatore?") ha sempre la priorita': anche se l'app era gia'
        // installata con delle Stagioni, se non e' mai stato fatto lo si propone comunque una volta sola.
        if (!store.onboardingDone) {
            askTrainerName();
            return;
        }

        if (store.seasons.isEmpty()) {
            wizardStep1(true, null);
        } else if (b != null) {
            // Ripristina la schermata su cui si trovava l'utente, se questa Activity e' stata ricreata dal
            // sistema (es. processo terminato in background per liberare memoria, cosa che capita spesso
            // cambiando app): senza questo, si tornava sempre alla lista Stagioni, perdendo il contesto.
            int savedCurrent = b.getInt("seasonCurrent", 0);
            if (savedCurrent>=0 && savedCurrent<store.seasons.size()) store.current = savedCurrent;
            view.detailTab = b.getInt("detailTab", 0);
            view.partiteTab = b.getInt("partiteTab", 0);
            screen = b.getInt("screen", SCREEN_SEASON_LIST);
            if (screen==SCREEN_SEASON_DETAIL && store.seasons.isEmpty()) screen = SCREEN_SEASON_LIST;
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("screen", screen);
        outState.putInt("seasonCurrent", store!=null ? store.current : 0);
        if (view != null) { outState.putInt("detailTab", view.detailTab); outState.putInt("partiteTab", view.partiteTab); }
    }

    // Applica gli inset di sistema (status bar in alto, barra di navigazione in basso) come padding sulla
    // View: da Android 15 (targetSdk 35) il layout edge-to-edge e' attivo di default, quindi senza questo
    // il contenuto verrebbe disegnato dietro l'orologio/status bar e dietro i pulsanti di navigazione.
    // Crea la TrackerView, la avvolge in un FrameLayout insieme alla barra di ricerca deck (una vera
    // EditText nativa, non disegnabile sul solo canvas), e la imposta come vista dell'Activity. Centralizzato
    // qui perche' prima era duplicato in 3 punti diversi.
    void setupTrackerView(){
        view = new TrackerView(this);
        rootContainer = new FrameLayout(this);
        rootContainer.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        buildDeckSearchBar();
        setContentView(rootContainer);
        attachInsets(view);
        view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob) -> positionDeckSearchBar());
    }

    // Pillola di ricerca deck: icona lente (sempre visibile) + campo di testo (nascosto finche' non si tocca
    // la pillola) + "✕" cerchiata per azzerare e richiudere. Vera EditText nativa sovrapposta al canvas,
    // posizionata/dimensionata dinamicamente in positionDeckSearchBar().
    void buildDeckSearchBar(){
        deckSearchBar = new LinearLayout(this);
        deckSearchBar.setOrientation(LinearLayout.HORIZONTAL);
        deckSearchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(10,18,30)); bg.setCornerRadius(dp(16));
        deckSearchBar.setBackground(bg);

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageBitmap(makeSearchIcon(Color.WHITE, dp(16)));
        searchIcon.setPadding(dp(12),dp(8),dp(6),dp(8));
        deckSearchBar.addView(searchIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Sempre visibile, niente piu' pulsante che si espande: la tastiera si apre semplicemente toccando
        // il campo (comportamento normale di ogni EditText), con l'icona "cerca" al posto di "Fatto".
        deckSearchInput = new EditText(this);
        deckSearchInput.setSingleLine(); deckSearchInput.setBackground(null);
        deckSearchInput.setTextColor(Color.WHITE); deckSearchInput.setHintTextColor(MUTED_TXT);
        deckSearchInput.setHint("Cerca deck"); deckSearchInput.setTextSize(14);
        deckSearchInput.setPadding(0,0,0,0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        deckSearchBar.addView(deckSearchInput, inputLp);

        // "X" visibile SOLO quando c'e' del testo scritto (non il placeholder): azzera il campo, non "chiude"
        // piu' nulla dato che non c'e' piu' un pulsante collassato da tornare a essere.
        deckSearchClearBtn = new TextView(this); deckSearchClearBtn.setText("✕"); deckSearchClearBtn.setTextColor(MUTED_TXT);
        deckSearchClearBtn.setGravity(Gravity.CENTER); deckSearchClearBtn.setTextSize(13);
        GradientDrawable closeCircle = new GradientDrawable(); closeCircle.setShape(GradientDrawable.OVAL); closeCircle.setColor(Color.rgb(24,36,52));
        deckSearchClearBtn.setBackground(closeCircle);
        deckSearchClearBtn.setPadding(dp(2),dp(2),dp(2),dp(2));
        deckSearchClearBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        clearLp.leftMargin = dp(6); clearLp.rightMargin = dp(6);
        deckSearchBar.addView(deckSearchClearBtn, clearLp);

        deckSearchClearBtn.setOnClickListener(v -> {
            deckSearchInput.setText("");
            view.deckSearchQuery = ""; view.invalidate();
        });
        deckSearchInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){ deckSearchClearBtn.setVisibility(s.length()>0?View.VISIBLE:View.GONE); }
            public void afterTextChanged(android.text.Editable s){}
        });
        // Niente filtro live ad ogni tasto: tastiera con pulsante "Cerca" (invece di "Fatto"), il filtro si
        // applica solo quando lo si tocca — e a quel punto la tastiera si chiude da sola.
        deckSearchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        deckSearchInput.setOnEditorActionListener((tv,actionId,ev) -> {
            if (actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                view.deckSearchQuery = deckSearchInput.getText().toString();
                view.invalidate();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm!=null) imm.hideSoftInputFromWindow(deckSearchInput.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        rootContainer.addView(deckSearchBar, new FrameLayout.LayoutParams(dp(44), dp(44)));
    }

    // Richiamato ad ogni invalidate() della TrackerView (vedi override in TrackerView): tiene la barra
    // nativa sincronizzata con schermata/tab corrente — mostrata a larghezza piena, sempre uguale, niente
    // piu' distinzione espansa/collassata.
    void positionDeckSearchBar(){
        if (view == null || deckSearchBar == null) return;
        boolean shouldShow = screen==SCREEN_SEASON_DETAIL && view.detailTab==1;
        if (!shouldShow) {
            deckSearchBar.setVisibility(View.GONE);
            // Se si esce dal tab Deck con un filtro ancora scritto, lo si azzera: tornando indietro non
            // avrebbe senso ritrovare un filtro "invisibile" applicato senza che la barra lo mostri.
            if (!view.deckSearchQuery.isEmpty()) {
                view.deckSearchQuery=""; deckSearchInput.setText(""); deckSearchClearBtn.setVisibility(View.GONE);
            }
            return;
        }
        deckSearchBar.setVisibility(View.VISIBLE);
        float logicalW = (view.getWidth()-view.getPaddingLeft()-view.getPaddingRight())/view.density;
        if (logicalW<=0) return; // non ancora misurata: verra' richiamato dal listener di layout
        int top = view.getPaddingTop() + dp(44);
        int left = view.getPaddingLeft() + dp(18);
        int height = dp(44);
        int width = Math.round((logicalW-36)*view.density); // stesso margine (18) del pulsante "Nuovo Deck" sotto
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) deckSearchBar.getLayoutParams();
        lp.width = width; lp.height = height; lp.leftMargin = left; lp.topMargin = top;
        deckSearchBar.setLayoutParams(lp);
    }

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

    /** Naviga di un livello indietro nella gerarchia Lista Season -> Dettaglio/Impostazioni. */
    void goBack() {
        if (screen == SCREEN_SEASON_DETAIL || screen == SCREEN_SETTINGS) { screen = SCREEN_SEASON_LIST; }
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

    // ===== Onboarding "Come ti chiami, allenatore?": mostrato una sola volta, la primissima volta che l'app
    // viene aperta (anche se erano gia' presenti delle Stagioni salvate, se l'onboarding non era mai stato
    // fatto prima). Nome usato poi per personalizzare occasionalmente i messaggi motivazionali. =====
    // Lista minima di parole da evitare nel nome (italiano + inglese), controllo per sottostringa su
    // testo normalizzato (minuscolo, senza spazi/punteggiatura): non e' un filtro esaustivo, ma copre i
    // casi piu' comuni per un nome inserito in un'app personale.
    static final String[] BAD_WORDS = {
        "cazzo","cazzi","cazzone","minchia","stronzo","stronza","puttana","troia","vaffanculo",
        "bastardo","bastarda","merda","coglione","cogliona","porco","porca","zoccola","fottiti",
        "cornuto","cornuta","testadicazzo","figadimerda",
        "fuck","shit","bitch","asshole","bastard","dick","pussy","cunt","whore","slut","nigger","faggot","retard"
    };
    boolean containsBadWord(String name){
        String norm = name.toLowerCase(Locale.ITALIAN).replaceAll("[^a-zàèéìòù]", "");
        for (String bw : BAD_WORDS) if (norm.contains(bw)) return true;
        return false;
    }

    // Modifica del nome dopo l'onboarding iniziale (da Impostazioni): stesso controllo bad-words, ma qui il
    // campo e' precompilato col nome attuale e c'e' un'opzione per rimuoverlo del tutto ("torna anonimo").
    void editTrainerNameDialog(){
        LinearLayout box = formBox();
        TextView header = new TextView(this);
        header.setText("Nome allenatore"); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);
        EditText nameField = new EditText(this);
        nameField.setSingleLine();
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleField(nameField);
        nameField.setText(store.trainerName);
        box.addView(nameField);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Rimuovi nome", (d,w) -> {
                store.trainerName = ""; view.cachedGreeting=null; store.save(); view.invalidate();
            })
            .create();
        showNonDismissing(dialog, () -> {
            String n = nameField.getText().toString().trim();
            if (containsBadWord(n)) return false;
            store.trainerName = n; view.cachedGreeting=null; // il messaggio di benvenuto va rigenerato col nuovo nome
            store.save(); view.invalidate();
            return true;
        }, "Scegli un nome valido.");
        dialog.show();
    }

    void askTrainerName(){
        LinearLayout box = formBox();
        TextView header = new TextView(this);
        header.setText("Come ti chiami, allenatore?"); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);
        // Campo vuoto, NESSUN placeholder: tastiera con la prima lettera in maiuscolo.
        EditText nameField = new EditText(this);
        nameField.setSingleLine();
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleField(nameField);
        box.addView(nameField);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .setNeutralButton("Preferisco non rispondere", (d,w) -> confirmTrainerName(""))
            .create();
        showNonDismissing(dialog, () -> {
            String n = nameField.getText().toString().trim();
            if (n.isEmpty() || containsBadWord(n)) return false;
            confirmTrainerName(n);
            return true;
        }, "Scegli un nome valido, oppure tocca \"Preferisco non rispondere\".");
        dialog.show();
    }

    void confirmTrainerName(String name){
        if (name.isEmpty()) {
            store.trainerName = ""; store.onboardingDone = true; store.save();
            showWelcomeGuide();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Conferma")
            .setMessage("Ti chiami \""+name+"\", ho capito bene?")
            .setCancelable(false)
            .setPositiveButton("Sì", (d,w) -> {
                store.trainerName = name; store.onboardingDone = true; store.save();
                showWelcomeGuide();
            })
            .setNegativeButton("No", (d,w) -> askTrainerName())
            .show();
    }

    void showWelcomeGuide(){
        String title = store.trainerName.isEmpty() ? "Benvenuto su Pocket Tracker!" : ("Benvenuto su Pocket Tracker, "+store.trainerName+"!");
        String guide = "Ecco come funziona, in breve:\n\n"
            + "• Crea una Stagione per iniziare a tracciare i tuoi punteggi.\n"
            + "• Nel tab Gioca scegli il deck e registra ogni partita con W o L: punti e streak si aggiornano da soli.\n"
            + "• Il tab Deck tiene le statistiche di ogni mazzo, con le Liste (screenshot) che carichi.\n"
            + "• Il tab Stats ti mostra il quadro generale della Stagione.\n"
            + "• Hai giocato senza registrare ogni partita? Usa \"Aggiungi correzione manuale\" per allineare i conti.\n\n"
            + "Buon divertimento, e che le tue serie di vittorie siano lunghe!";
        new AlertDialog.Builder(this).setTitle(title)
            .setMessage(guide)
            .setCancelable(false)
            .setPositiveButton("Iniziamo!", (d,w) -> { if (store.seasons.isEmpty()) wizardStep1(true, null); })
            .show();
    }

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
    // Alla creazione di una nuova Stagione, importa i NOMI dei deck della Stagione che sta per bloccarsi
    // (l'ultima esistente prima di questa) — comodo per non doverli riscrivere ogni volta. Le Liste
    // (screenshot) NON vengono importate: ogni Stagione ha le proprie, ripartono vuote.
    void importDeckNamesFromPreviousSeason(Season newSeason){
        if (store.seasons.isEmpty()) return;
        Season prev = store.seasons.get(store.seasons.size()-1);
        for (Deck d : prev.decks) newSeason.decks.add(new Deck(d.name));
    }

    void wizardStep3Yes(boolean first, String name){
        LinearLayout box = formBox();
        EditText points = numberField("Punti attuali", true);
        EditText streak = numberField("Vittorie consecutive attuali", true);
        EditText wins = numberField("Vittorie totali", false);
        EditText losses = numberField("Sconfitte totali", false);
        box.addView(label("Punti attuali")); box.addView(points);
        box.addView(label("Vittorie consecutive attuali")); box.addView(streak);
        box.addView(label("Vittorie totali")); box.addView(wins);
        box.addView(label("Sconfitte totali")); box.addView(losses);
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
                s.baseline = DEFAULT_BASELINE; s.initialStreak = 0;
                s.currentDeck = "Unknown";
                Match m0 = Match.correction(DEFAULT_BASELINE, np, "Unknown");
                m0.correctionWins = nw; m0.correctionLosses = nl; m0.streak = ns; // altrimenti un annullamento successivo recupererebbe sempre streak=0 dalla correzione
                s.matches.add(m0);
                s.points = np; s.streak = ns;
                importDeckNamesFromPreviousSeason(s);
                store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
                if (view == null) { setupTrackerView(); }
                screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
                return true;
            } catch (Exception e) { return false; }
        }, "Inserisci valori validi (streak/vittorie/sconfitte >= 0).");
        dialog.show();
    }

    // "No": si parte dallo standard 810/streak 0, e si passa dritti alla scelta del deck di partenza
    // (con "Annulla" per decidere più avanti, come al solito).
    void wizardStep3No(boolean first, String name){
        Season s = new Season(name);
        s.baseline = DEFAULT_BASELINE; s.initialStreak = 0;
        s.points = DEFAULT_BASELINE; s.streak = 0;
        s.currentDeck = "Unknown";
        importDeckNamesFromPreviousSeason(s);
        store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
        if (view == null) { setupTrackerView(); }
        screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
        pickDeckFor(s, "Scegli il Deck", "Salta", dn -> { s.currentDeck = dn; store.save(); view.invalidate(); });
    }


    boolean deckNameTaken(Season s, String n) {
        if ("Unknown".equalsIgnoreCase(n)) return true;
        for (Deck d : s.decks) if (d.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    // Selettore di deck condiviso: usato sia per scegliere il deck "attuale" (quello che verra' assegnato alla
    // PROSSIMA partita registrata) sia per cambiare retroattivamente il deck di una partita GIA' giocata.
    // onPicked riceve il nome del deck scelto (o appena creato) e decide lui cosa farne.
    // Dialog "Scegli un Deck": lista con ricerca (stessa idea del tab Deck: lente che si espande in un
    // campo di testo con "X" per azzerare) e altezza limitata a 6 righe visibili (poi scrollbar) — prima
    // era un semplice setItems() nativo, senza ricerca e senza limite, che con tanti deck diventava
    // scomodamente lungo. Header con sfondo scuro (rgb(21,34,56)), lo stesso usato per l'header
    // grafico/lista/edit nella card Partite.
    void showDeckPickerDialog(ArrayList<String> allNames, java.util.function.Consumer<String> onPicked){
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(14,24,38));

        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.rgb(21,34,56));
        header.setPadding(dp(20),dp(16),dp(20),dp(12));
        TextView title = new TextView(this); title.setText("Scegli un Deck"); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        // Barra di ricerca: pillola con lente, si espande in un campo di testo con "X" cerchiata per
        // azzerare — nessun trucco di overlay necessario qui (e' un dialog nativo, non canvas).
        LinearLayout searchBar = new LinearLayout(this); searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable(); searchBg.setColor(Color.rgb(10,18,30)); searchBg.setCornerRadius(dp(16));
        searchBar.setBackground(searchBg);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(44), dp(44)); searchLp.topMargin = dp(12);
        ImageView searchIcon = new ImageView(this); searchIcon.setImageBitmap(makeSearchIcon(Color.WHITE, dp(16)));
        searchIcon.setPadding(dp(12),dp(8),dp(6),dp(8));
        searchBar.addView(searchIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText searchInput = new EditText(this); searchInput.setSingleLine(); searchInput.setBackground(null);
        searchInput.setTextColor(Color.WHITE); searchInput.setHintTextColor(MUTED_TXT); searchInput.setHint("Cerca deck"); searchInput.setTextSize(14);
        searchInput.setPadding(0,0,0,0); searchInput.setVisibility(View.GONE);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView clearBtn = new TextView(this); clearBtn.setText("✕"); clearBtn.setTextColor(MUTED_TXT); clearBtn.setGravity(Gravity.CENTER); clearBtn.setTextSize(13);
        GradientDrawable clearCircle = new GradientDrawable(); clearCircle.setShape(GradientDrawable.OVAL); clearCircle.setColor(Color.rgb(24,36,52));
        clearBtn.setBackground(clearCircle); clearBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(22), dp(22)); clearLp.leftMargin=dp(6); clearLp.rightMargin=dp(6);
        searchBar.addView(clearBtn, clearLp);
        header.addView(searchBar, searchLp);
        root.addView(header);

        // Lista filtrabile: altezza calcolata dinamicamente, massimo 6 righe visibili poi scrollbar.
        int rowHeightPx = dp(48);
        ListView listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(30,42,58))); listView.setDividerHeight(1);
        ArrayList<String> filtered = new ArrayList<>(allNames);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, 0, filtered){
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent){
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                tv.setText(getItem(position)); tv.setTextColor(Color.WHITE); tv.setTextSize(16);
                tv.setPadding(dp(20),0,dp(20),0); tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setHeight(rowHeightPx);
                return tv;
            }
        };
        listView.setAdapter(adapter);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.min(filtered.size(),6)*rowHeightPx);
        root.addView(listView, listLp);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).setNegativeButton("Annulla", null).create();
        listView.setOnItemClickListener((parent,v,position,id) -> { dialog.dismiss(); onPicked.accept(filtered.get(position)); });

        Runnable[] doFilter = new Runnable[1];
        doFilter[0] = () -> {
            String q = searchInput.getText().toString().trim().toLowerCase(Locale.ITALY);
            filtered.clear();
            for (String n: allNames) if (q.isEmpty() || n.toLowerCase(Locale.ITALY).contains(q)) filtered.add(n);
            adapter.notifyDataSetChanged();
            listLp.height = Math.min(filtered.size(),6)*rowHeightPx;
            listView.setLayoutParams(listLp);
        };
        searchBar.setOnClickListener(v -> {
            if (searchInput.getVisibility()==View.VISIBLE) return;
            searchInput.setVisibility(View.VISIBLE); clearBtn.setVisibility(View.VISIBLE);
            searchLp.width = LinearLayout.LayoutParams.MATCH_PARENT; searchBar.setLayoutParams(searchLp);
            // post(): la view va rimisurata alla nuova larghezza prima di chiedere focus/tastiera, altrimenti
            // la richiesta puo' fallire silenziosamente (stesso bug del tab Deck). Prima mancava anche la
            // chiamata esplicita a showSoftInput — ci si affidava al comportamento predefinito del focus, non
            // sempre affidabile dentro un dialog.
            searchInput.post(() -> {
                searchInput.requestFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm!=null) imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        });
        clearBtn.setOnClickListener(v -> {
            searchInput.setText(""); searchInput.setVisibility(View.GONE); clearBtn.setVisibility(View.GONE);
            searchLp.width = dp(44); searchBar.setLayoutParams(searchLp);
            doFilter[0].run();
        });
        // Niente piu' filtro live ad ogni tasto: tastiera con pulsante "Cerca" (invece di "Fatto"), il
        // filtro si applica solo quando lo si tocca — e a quel punto la tastiera si chiude da sola.
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((tv,actionId,ev) -> {
            if (actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doFilter[0].run();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm!=null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        dialog.show();
    }

    void pickDeckFor(Season s, String headerText, String negativeLabel, java.util.function.Consumer<String> onPicked) {
        ArrayList<String> names = new ArrayList<>();
        for (Deck d : s.decks) names.add(d.name);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        LinearLayout box = formBox();
        // Header dentro il box stesso (non piu' AlertDialog.setTitle): cosi' condivide lo stesso padding dei
        // pulsanti sotto ed e' allineato a sinistra come loro — il titolo nativo del dialog aveva un padding
        // diverso e non risultava allineato. Il messaggio di spiegazione sotto e' stato rimosso.
        TextView header = new TextView(this);
        header.setText(headerText); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);

        String[] selected = {null};
        Runnable[] refreshSelector = new Runnable[1];
        Button deckSelector = new Button(this); styleSecondaryButton(deckSelector);
        // Se non esiste ancora nessun deck (tipicamente: appena creata una nuova Stagione), il selettore non
        // ha alcun senso — proponeva un pulsante disabilitato con "Nessun deck esistente" che non portava a
        // nulla. In quel caso si mostra solo "Nuovo Deck".
        boolean hasAnyDeck = !names.isEmpty();
        Bitmap downArrow = makeDownArrowIcon(Color.WHITE, dp(12));
        BitmapDrawable downArrowDrawable = new BitmapDrawable(getResources(), downArrow);
        downArrowDrawable.setBounds(0,0,dp(12),dp(12));
        deckSelector.setCompoundDrawables(null,null,downArrowDrawable,null);
        deckSelector.setCompoundDrawablePadding(dp(8));
        refreshSelector[0] = () -> deckSelector.setText(selected[0] != null ? selected[0] : "Tocca per scegliere un deck");
        refreshSelector[0].run();
        if (hasAnyDeck) {
            LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            box.addView(deckSelector, selLp);
        }

        // "Nuovo Deck": si trasforma in un campo di testo con una "✕" sovrapposta per richiuderlo, invece
        // di aprire un secondo dialog separato — piu' rapido per la creazione al volo.
        Button newDeckBtn = new Button(this); newDeckBtn.setText("Nuovo Deck"); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newBtnLp.topMargin = dp(10); box.addView(newDeckBtn, newBtnLp);

        android.widget.FrameLayout newDeckSection = new android.widget.FrameLayout(this);
        EditText newDeckName = field("Nome nuovo deck"); newDeckName.setPadding(dp(14),dp(12),dp(44),dp(12));
        newDeckSection.addView(newDeckName, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        TextView closeNewDeck = new TextView(this); closeNewDeck.setText("✕"); closeNewDeck.setTextColor(MUTED_TXT); closeNewDeck.setGravity(Gravity.CENTER);
        GradientDrawable closeCircle = new GradientDrawable(); closeCircle.setShape(GradientDrawable.OVAL); closeCircle.setColor(Color.rgb(24,36,52));
        closeNewDeck.setBackground(closeCircle);
        android.widget.FrameLayout.LayoutParams closeLp = new android.widget.FrameLayout.LayoutParams(dp(28), dp(28));
        closeLp.gravity = Gravity.END|Gravity.CENTER_VERTICAL; closeLp.rightMargin = dp(8);
        newDeckSection.addView(closeNewDeck, closeLp);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dp(10); newDeckSection.setLayoutParams(sectionLp); newDeckSection.setVisibility(View.GONE);
        box.addView(newDeckSection);

        newDeckBtn.setOnClickListener(v -> { newDeckSection.setVisibility(View.VISIBLE); newDeckBtn.setVisibility(View.GONE); newDeckName.requestFocus(); });
        closeNewDeck.setOnClickListener(v -> { newDeckName.setText(""); newDeckSection.setVisibility(View.GONE); newDeckBtn.setVisibility(View.VISIBLE); });

        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
            .setPositiveButton("Conferma", null).setNegativeButton(negativeLabel, null).create();
        // Selezionare un deck ESISTENTE dalla lista conferma ed esce subito (chiude anche questo dialog
        // "genitore"): prima bisognava tornare qui e premere ancora "Conferma", un passaggio in piu' inutile
        // visto che la scelta e' gia' inequivocabile. Il pulsante negativo (Annulla/Salta) invece torna qui,
        // come gia' faceva.
        deckSelector.setOnClickListener(v -> {
            showDeckPickerDialog(names, name -> {
                dialog.dismiss();
                onPicked.accept(name);
            });
        });
        showNonDismissing(dialog, () -> {
            String newName = newDeckName.getText().toString().trim();
            if (!newName.isEmpty()) {
                if (deckNameTaken(s, newName)) return false;
                s.decks.add(new Deck(newName)); store.save();
                onPicked.accept(newName);
                return true;
            }
            if (selected[0] == null) return false;
            onPicked.accept(selected[0]);
            return true;
        }, "Seleziona un deck esistente o scrivi il nome di uno nuovo.");
        dialog.show();
    }

    void renameDeckDialog(Deck d){ renameDeckDialog(d, null); }

    // onChanged (opzionale): richiamato a rinomina avvenuta — vedi commento su showPreviewPicker per il motivo.
    void renameDeckDialog(Deck d, Runnable onChanged){
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
            if (onChanged!=null) onChanged.run();
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    // Riga di una card deck dentro il dialog "Cambia deck": stesso disegno esatto delle card del tab Deck
    // (deckCardVisual, nessun effetto collaterale sullo stato del tab Deck vero), con in piu' il bordo
    // arancione se e' il deck attualmente selezionato in QUESTO dialog (non ancora confermato).
    class DeckCardRowView extends View {
        Deck deckObj; boolean selected=false; float density_;
        DeckCardRowView(Context c, Deck d){ super(c); deckObj=d; density_=getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if (getWidth()==0) return;
            c.save(); c.scale(density_, density_);
            float w = getWidth()/density_;
            Season s = store.seasons.get(store.current);
            int[] wl = view.deckWL(s, deckObj.name);
            int best = view.longestStreakForDeck(s, deckObj.name);
            int gain = view.deckGain(s, deckObj.name);
            // Tutto disegnato a partire da y=1.5 (non 0): lo stroke di selezione (largo 3) sporge di 1.5 sopra
            // e sotto il rettangolo della card — con la card attaccata al bordo esatto della view (y=0..92,
            // altezza della riga=92) quello sporgere veniva tagliato. La riga stessa e' ora alta 95 (92+3)
            // per fargli spazio, esattamente quanto serve, non di piu'.
            view.deckCardVisual(c, deckObj, deckObj.name, false, wl[0], wl[1], best, gain, 1.5f, w, true);
            if (selected) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
                p.setColor(Color.rgb(255,138,61)); // stesso arancione usato per la Stagione attuale
                // Stesso rettangolo ESATTO della card (18,1.5,w-18,93.5) e stesso raggio (18) usato da box().
                c.drawRoundRect(new RectF(18,1.5f,w-18,93.5f), 18,18, p);
            }
            c.restore();
        }
    }

    // Menu "⋮" di una riga nel dialog "Cambia deck": stesse azioni disponibili altrove per un deck, con in
    // piu' "Vedi Lista" separata da "Aggiungi Lista" (prima un'unica voce faceva entrambe le cose in base
    // allo stato). onChanged: richiamato per far ridisegnare la riga (es. dopo rinomina).
    // Menu "⋮" adatto ai dialog nativi (a differenza di TrackerView.showAnchoredMenu, pensato solo per il
    // canvas dell'app: ancorare li' un popup mentre e' aperto un Dialog lo fa apparire nella finestra
    // sbagliata — dietro al dialog, invisibile e non toccabile. Qui il popup e' ancorato con showAsDropDown
    // DIRETTAMENTE alla view che l'ha aperto, quindi resta nella stessa finestra del dialog.
    void showDialogMenu(View anchorView, String[] labels, int[] colors, Runnable[] actions){
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(14,24,38));
        bg.setStroke(dp(1), Color.rgb(40,55,75)); bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        box.setPadding(dp(4),dp(4),dp(4),dp(4));
        final PopupWindow[] popupRef = new PopupWindow[1];
        for(int i=0;i<labels.length;i++){
            final int idx=i;
            TextView t = new TextView(this);
            t.setText(labels[i]); t.setTextColor(colors[i]); t.setTextSize(15);
            t.setPadding(dp(20),dp(14),dp(20),dp(14));
            t.setOnClickListener(v -> { popupRef[0].dismiss(); actions[idx].run(); });
            box.addView(t);
        }
        PopupWindow popup = new PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popupRef[0]=popup;
        popup.setElevation(dp(8));
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int menuW = box.getMeasuredWidth();
        // Regola per tutti i sottomenu "⋮": il bordo destro del menu deve combaciare col CENTRO orizzontale
        // dell'icona (non il bordo destro della zona di tocco, piu' larga dell'icona vera e propria).
        int xOff = anchorView.getWidth()/2 - menuW;
        popup.showAsDropDown(anchorView, xOff, 0);
    }

    void showDeckRowMenu(Season s, Deck d, View anchorView, Runnable onChanged){
        boolean hasLista = !d.images.isEmpty();
        ArrayList<String> labels = new ArrayList<>(java.util.Arrays.asList("Rinomina deck","Scegli anteprima"));
        ArrayList<Integer> colors = new ArrayList<>(java.util.Arrays.asList(Color.WHITE, Color.WHITE));
        ArrayList<Runnable> actions = new ArrayList<>(java.util.Arrays.asList(
            (Runnable)(() -> renameDeckDialog(d, onChanged)), (Runnable)(() -> showPreviewPicker(d, onChanged))));
        if (hasLista) { labels.add("Vedi Lista"); colors.add(Color.WHITE); actions.add(() -> showImageGallery(d,0)); }
        labels.add("Aggiungi Lista"); colors.add(Color.WHITE); actions.add(() -> pickImageFor(d));
        labels.add("Elimina deck"); colors.add(red()); actions.add(() -> confirmDeleteDeck(s,d,onChanged));
        int[] colArr = new int[colors.size()]; for(int i=0;i<colArr.length;i++) colArr[i]=colors.get(i);
        showDialogMenu(anchorView, labels.toArray(new String[0]), colArr, actions.toArray(new Runnable[0]));
    }

    // Cambia il deck "attuale" (quello che verra' usato per la PROSSIMA partita registrata). Un unico dialog
    // (niente piu' doppio passaggio): ricerca in alto, card deck selezionabili — le stesse del tab Deck,
    // comprensive di statistiche e anteprima — con bordo arancione sul deck scelto, "Nuovo Deck" in fondo
    // alla lista, Annulla/Conferma sotto.
    // Numero progressivo di una partita (conta solo le partite vere, non le correzioni manuali) — stesso
    // criterio usato per "Partita N" nella lista.
    int matchNumberOf(Season s, Match m){
        int cnt=0;
        for (Match x: s.matches){ if(!x.unknown) cnt++; if(x==m) return cnt; }
        return cnt;
    }

    // Dialog "Seleziona un deck" generico, condiviso da chooseCurrentDeck() (cambia il deck attuale della
    // Stagione) e changeMatchDeck() (cambia il deck di UNA partita gia' giocata) — stesse card, ricerca,
    // "Nuovo Deck", Annulla/Conferma; cambia solo il titolo, la selezione di partenza e cosa fare col deck
    // scelto (parametrizzato con onConfirm).
    void showDeckSelectorDialog(Season s, String headerText, Deck initialSelection, java.util.function.Consumer<Deck> onConfirm) {
        Deck[] selected = { initialSelection };
        ArrayList<Deck> allDecks = view.sortedDecks(s);
        ArrayList<Deck> filtered = new ArrayList<>(allDecks);
        // Dichiarato qui (assegnato piu' sotto): rebuildList lo referenzia nel listener del kebab, quindi
        // deve esistere gia' come variabile prima — l'assegnazione effettiva puo' avvenire dopo, l'importante
        // e' che sia pronta PRIMA che l'utente possa davvero toccare qualcosa (dialog.show() e' sempre l'ultimo passo).
        Runnable[] refreshFromSource = new Runnable[1];

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView title = new TextView(this); title.setText(headerText); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        // MATCH_PARENT (non piu' WRAP_CONTENT) con margine destro: un titolo troppo lungo va a capo invece
        // di sbordare fuori dallo schermo (come "Seleziona un deck diverso per la partita n.116").
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18);
        root.addView(title, titleLp);

        // Barra di ricerca (lente che si espande in un campo di testo con "X" per azzerare) — resta in alto.
        LinearLayout searchBar = new LinearLayout(this); searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable(); searchBg.setColor(Color.rgb(10,18,30)); searchBg.setCornerRadius(dp(16));
        searchBar.setBackground(searchBg);
        ImageView searchIcon = new ImageView(this); searchIcon.setImageBitmap(makeSearchIcon(Color.WHITE, dp(16)));
        searchIcon.setPadding(dp(12),dp(8),dp(6),dp(8));
        searchBar.addView(searchIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // Sempre visibile, niente piu' pulsante che si espande: la tastiera si apre semplicemente toccando
        // il campo (comportamento normale di ogni EditText).
        EditText searchInput = new EditText(this); searchInput.setSingleLine(); searchInput.setBackground(null);
        searchInput.setTextColor(Color.WHITE); searchInput.setHintTextColor(MUTED_TXT); searchInput.setHint("Cerca deck"); searchInput.setTextSize(14);
        searchInput.setPadding(0,0,0,0);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // "X" visibile SOLO quando c'e' del testo scritto (non il placeholder).
        TextView clearBtn = new TextView(this); clearBtn.setText("✕"); clearBtn.setTextColor(MUTED_TXT); clearBtn.setGravity(Gravity.CENTER); clearBtn.setTextSize(13);
        GradientDrawable clearCircle = new GradientDrawable(); clearCircle.setShape(GradientDrawable.OVAL); clearCircle.setColor(Color.rgb(24,36,52));
        clearBtn.setBackground(clearCircle); clearBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(22), dp(22)); clearLp.leftMargin=dp(6); clearLp.rightMargin=dp(6);
        searchBar.addView(clearBtn, clearLp);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        searchLp.topMargin=dp(14); searchLp.leftMargin=dp(18); searchLp.rightMargin=dp(18); searchLp.bottomMargin=dp(10);
        root.addView(searchBar, searchLp);

        // Lista scrollabile delle card deck.
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        // Solo padding verticale: quello orizzontale, insieme al margine di 18 unita' che la card disegna
        // già da sé (deckCardVisual), creava un doppio inset — le card risultavano piu' strette del pulsante
        // "Nuovo Deck" sotto.
        list.setPadding(0,dp(4),0,dp(4));
        // LayoutParams espliciti (MATCH_PARENT): una ScrollView di default da' ai suoi figli WRAP_CONTENT in
        // larghezza, non MATCH_PARENT — "list" (e quindi ogni card dentro) si misurava contro una larghezza
        // piu' piccola di quella reale dello schermo, da qui le card sempre troppo strette anche dopo aver
        // dato una larghezza esplicita alla finestra del dialog.
        scroll.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400));
        root.addView(scroll, scrollLp);

        Runnable[] rebuildList = new Runnable[1];
        rebuildList[0] = () -> {
            list.removeAllViews();
            for (Deck d: filtered) {
                android.widget.FrameLayout row = new android.widget.FrameLayout(this);
                DeckCardRowView cardView = new DeckCardRowView(this, d);
                cardView.selected = (d == selected[0]);
                row.addView(cardView, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(95)));
                View kebabHotspot = new View(this);
                android.widget.FrameLayout.LayoutParams khLp = new android.widget.FrameLayout.LayoutParams(dp(44), dp(44));
                // Margine destro ricalcolato per il nuovo margine card (18, non piu' 30): il kebab e' ora
                // disegnato a w-36 invece di w-48. topMargin=1.5 per seguire lo spostamento verticale della
                // card (disegnata ora a partire da y=1.5, non 0, per fare spazio allo stroke di selezione).
                khLp.gravity = Gravity.TOP|Gravity.END; khLp.rightMargin = dp(14); khLp.topMargin = dp(1.5f);
                row.addView(kebabHotspot, khLp);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(95));
                rowLp.bottomMargin = dp(10);
                list.addView(row, rowLp);

                cardView.setOnClickListener(v -> { selected[0]=d; rebuildList[0].run(); });
                kebabHotspot.setOnClickListener(v -> showDeckRowMenu(s, d, kebabHotspot, refreshFromSource[0]));
            }
        };
        rebuildList[0].run();

        // Scorre subito verso il deck di partenza (quello attuale, o quello appena creato se questo dialog
        // e' stato aperto giusto dopo — es. da changeMatchDeck), cosi' e' visibile senza dover scorrere a
        // mano se si trova piu' in basso nella lista ordinata.
        if (selected[0]!=null) {
            int idx = filtered.indexOf(selected[0]);
            if (idx>=0) {
                int rowH = dp(95)+dp(10); // altezza riga + margine sotto, stessi valori usati in rebuildList
                int targetY = Math.max(0, idx*rowH - dp(10));
                scroll.post(() -> scroll.scrollTo(0, targetY));
            }
        }

        Runnable[] doFilter = new Runnable[1];
        doFilter[0] = () -> {
            String q = searchInput.getText().toString().trim().toLowerCase(Locale.ITALY);
            filtered.clear();
            for (Deck d: allDecks) if (q.isEmpty() || d.name.toLowerCase(Locale.ITALY).contains(q)) filtered.add(d);
            rebuildList[0].run();
        };
        // Aggiornamento completo dopo rinomina/anteprima/elimina/creazione: ri-deriva SEMPRE allDecks dalla
        // fonte di verita' (s.decks), non solo un ridisegno — necessario per l'eliminazione (la riga va
        // rimossa dalla lista, non solo ridisegnata) e per la rinomina (l'ordine puo' cambiare). Prima
        // "Nuovo Deck" faceva questo a mano per conto suo; ora e' condiviso da tutte le azioni del menu "⋮".
        refreshFromSource[0] = () -> {
            allDecks.clear(); allDecks.addAll(view.sortedDecks(s));
            // Se il deck selezionato era proprio quello appena eliminato, si va sul primo della lista COMPLETA
            // (non filtrata da un'eventuale ricerca attiva) — coerente anche se la ricerca in corso non lo
            // includerebbe.
            if (selected[0]!=null && !allDecks.contains(selected[0])) {
                selected[0] = allDecks.isEmpty() ? null : allDecks.get(0);
            }
            doFilter[0].run();
        };
        // "X": azzera il campo E applica subito il filtro vuoto (mostra di nuovo tutti i deck) — niente piu'
        // logica di espansione, dato che la barra e' sempre a piena larghezza.
        clearBtn.setOnClickListener(v -> {
            searchInput.setText("");
            doFilter[0].run();
        });
        searchInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){ clearBtn.setVisibility(s.length()>0?View.VISIBLE:View.GONE); }
            public void afterTextChanged(android.text.Editable s){}
        });
        // Niente piu' filtro live ad ogni tasto: tastiera con pulsante "Cerca" (invece di "Fatto"), il
        // filtro si applica solo quando lo si tocca — e a quel punto la tastiera si chiude da sola.
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((tv,actionId,ev) -> {
            if (actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doFilter[0].run();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm!=null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        // "Nuovo Deck" resta in fondo alla lista, come nel vecchio dialog.
        Button newDeckBtn = new Button(this); newDeckBtn.setText("Nuovo Deck"); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newBtnLp.topMargin=dp(4); newBtnLp.leftMargin=dp(18); newBtnLp.rightMargin=dp(18); newBtnLp.bottomMargin=dp(10);
        root.addView(newDeckBtn, newBtnLp);

        // Footer: Annulla / Conferma.
        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(6),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText("Annulla"); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(14);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText("Conferma"); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(14);
        confirmBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(cancelBtn); footer.addView(confirmBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        // Rimuove l'area titolo che PocketDialogTheme (basato su Theme.Material.Dialog.Alert) riserva di
        // default anche senza alcun titolo impostato — invisibile nei dialog a schermo intero con sfondo
        // nero (nero su nero), ma ben visibile qui sopra lo sfondo colorato della card.
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        // Larghezza esplicita: senza, la finestra del dialog si dimensiona in modo ambiguo e le view a
        // MATCH_PARENT dentro si misurano storte (card strette, testo che sborda, tocchi che non registrano
        // sempre correttamente).
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        newDeckBtn.setOnClickListener(v -> addDeck(newDeck -> {
            // Creare un nuovo deck qui e' un'azione completa: lo applica subito (senza passare da
            // "Conferma") e chiude anche questo dialog, invece di lasciarlo aperto con la nuova card
            // evidenziata in attesa di un'ulteriore conferma.
            onConfirm.accept(newDeck);
            dialog.dismiss();
        }));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            if (selected[0]!=null) onConfirm.accept(selected[0]);
            dialog.dismiss();
        });
    }

    void chooseCurrentDeck() {
        Season s = store.seasons.get(store.current);
        showDeckSelectorDialog(s, "Seleziona un deck", findDeck(s, s.currentDeck), chosen -> {
            s.currentDeck = chosen.name; store.save(); view.invalidate();
        });
    }

    // Cambia retroattivamente il deck di una partita GIA' giocata: le statistiche per deck sono sempre
    // calcolate al volo dal campo 'deck' di ogni partita, quindi si aggiornano da sole.
    void changeMatchDeck(Match m) {
        Season s = store.seasons.get(store.current);
        int num = matchNumberOf(s, m);
        showDeckSelectorDialog(s, "Seleziona un deck diverso (partita n."+num+")", findDeck(s, m.deck), chosen -> {
            m.deck = chosen.name; store.save(); view.invalidate();
        });
    }

    void win() { play(true); }
    void loss() { play(false); }

    // Messaggi motivazionali a scomparsa dopo ogni partita registrata: vittoria con streak basso (1-2),
    // vittoria con streak alto (3+, "inarrestabile"), sconfitta.
    // Fascia dedicata alla PRIMISSIMA vittoria della serie (streak==1): frasi che parlano esplicitamente di
    // "inizio", non hanno senso ripetute a streak 2+.
    static final String[] WIN_MSGS_FIRST = {
        "Solo la prima di una lunga serie!",
        "Si parte bene!",
        "Ottimo lavoro!",
        "Una vittoria meritata!",
        "Bel colpo!"
    };
    // Streak==2: la serie sta iniziando a formarsi, ma non e' piu' "la prima" — frasi diverse.
    static final String[] WIN_MSGS_LOW = {
        "Continua così!",
        "Si comincia a carburare!",
        "Ottimo lavoro!",
        "Una vittoria meritata!",
        "Bel colpo!"
    };
    static final String[] WIN_MSGS_HIGH = {
        "Sei inarrestabile!",
        "Che striscia di vittorie!",
        "Nessuno può fermarti!",
        "Stai dominando!",
        "Vittoria dopo vittoria, complimenti!",
        "Sei in stato di grazia!",
        "Imbattibile in questo momento!"
    };
    static final String[] LOSS_MSGS_LOW = {
        "Capita a tutti, rialzati!",
        "La prossima è quella buona!",
        "Non mollare!",
        "Ricalibra e riparti!",
        "Un passo indietro, due avanti!",
        "Analizza e migliora!",
        "Il campione si vede nelle sconfitte!",
        "Testa alta, si riparte!",
        "Ogni sconfitta insegna qualcosa!",
        "Pazienza, il vento girerà!"
    };
    // Sconfitte consecutive (3+): il tono cambia, meglio suggerire una pausa che insistere.
    static final String[] LOSS_MSGS_HIGH = {
        "Forse è il momento di una pausa.",
        "Stacca un attimo, si torna più lucidi.",
        "Respira: una pausa non fa mai male.",
        "Prenditi qualche minuto, poi si riparte.",
        "Va benissimo fermarsi un attimo.",
        "Una brutta giornata capita: rilassati un po'.",
        "Ricaricare le energie non è mai tempo perso."
    };
    // Legata alla GIORNATA (non allo streak): quando il win rate di OGGI supera il 65%, con abbastanza
    // partite giocate perche' la percentuale sia significativa.
    static final String[] DAY_MSGS_HOT = {
        "Oggi sei inarrestabile!",
        "Oggi non ti ferma più nessuno!",
        "Giornata da campione!",
        "Stai dominando la giornata!",
        "Che giornata, continua così!",
        "Oggi hai il tocco magico!"
    };
    // Messaggio di benvenuto nella lista Stagioni, legato all'orario. Ogni riga e' una coppia [con nome,
    // senza nome] — coppie separate (non un singolo template con %s tolto a mano) per evitare frasi zoppe
    // tipo "Partitina notturna, ?" quando il nome non e' conosciuto.
    static final String[][] GREETING_NIGHT = {
        {"Partitina notturna, %s?", "Partitina notturna?"},
        {"Gli allenatori più tenaci giocano di notte, %s.", "Gli allenatori più tenaci giocano di notte."},
        {"A quest'ora, %s? Rispetto.", "A quest'ora? Rispetto."}
    };
    static final String[][] GREETING_MORNING = {
        {"Buongiorno %s, si comincia!", "Buongiorno, si comincia!"},
        {"Colazione e qualche partita, %s?", "Colazione e qualche partita?"},
        {"Si parte presto oggi, %s!", "Si parte presto oggi!"}
    };
    static final String[][] GREETING_AFTERNOON = {
        {"Pausa pranzo con qualche partita, %s?", "Pausa pranzo con qualche partita?"},
        {"Buon pomeriggio, %s! Pronto a giocare?", "Buon pomeriggio! Pronto a giocare?"},
        {"%s, si gioca nel pomeriggio!", "Si gioca nel pomeriggio!"}
    };
    static final String[][] GREETING_EVENING = {
        {"Buonasera %s, si comincia?", "Buonasera, si comincia?"},
        {"Serata di Pocket, %s?", "Serata di Pocket?"},
        {"%s, pronto per qualche partita stasera?", "Pronto per qualche partita stasera?"}
    };
    // "Shuffle bag" per non ripetere le stesse frasi finche' non sono state usate tutte (poi si rimescola):
    // una coda separata per ciascuna fascia, tenuta in memoria per la durata della sessione dell'app.
    java.util.HashMap<Object, ArrayList<Integer>> messagePoolQueues = new java.util.HashMap<>();
    String pickMessage(String[] pool){
        ArrayList<Integer> queue = messagePoolQueues.get(pool);
        if(queue==null || queue.isEmpty()){
            queue = new ArrayList<>();
            for(int i=0;i<pool.length;i++) queue.add(i);
            java.util.Collections.shuffle(queue);
            messagePoolQueues.put(pool, queue);
        }
        int idx = queue.remove(queue.size()-1);
        return pool[idx];
    }
    void showMotivationalMessage(boolean win, int streak){
        // Messaggio legato alla GIORNATA (non allo streak): se oggi il win rate supera il 65%, con almeno 5
        // partite giocate oggi perche' la percentuale sia significativa — non sempre, per non sovrapporsi
        // troppo spesso alla logica basata sullo streak.
        if(win){
            Season s = store.seasons.get(store.current);
            String todayKey = dayKey(System.currentTimeMillis());
            int tw=0, tl=0;
            for(Match m: s.matches){ if(m.unknown) continue; if(dayKey(m.timestamp).equals(todayKey)){ if(m.win) tw++; else tl++; } }
            int totalToday = tw+tl;
            if(totalToday>=5 && (100f*tw/totalToday)>65f && new java.util.Random().nextInt(5)<2){
                Toast.makeText(this, pickMessage(DAY_MSGS_HOT), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String[] pool;
        if(win) pool = (streak==1) ? WIN_MSGS_FIRST : (streak>=3 ? WIN_MSGS_HIGH : WIN_MSGS_LOW);
        else pool = streak>=3 ? LOSS_MSGS_HIGH : LOSS_MSGS_LOW;
        String msg;
        // "Distruggili tutti, NOME!" solo per streak alte (3+) e nome conosciuto, occasionalmente — il nome
        // e' parte della frase stessa, non solo anteposto come nel caso generico sotto.
        if (win && streak>=3 && !store.trainerName.isEmpty() && new java.util.Random().nextInt(4)==0) {
            msg = "Distruggili tutti, "+store.trainerName+"!";
        } else {
            msg = pickMessage(pool);
            // Se conosciamo il nome dell'allenatore, ogni tanto (non sempre, per non risultare ripetitivo)
            // personalizza il messaggio anteponendolo, es. "Marco, continua così!".
            if (!store.trainerName.isEmpty() && new java.util.Random().nextInt(3)==0) {
                msg = store.trainerName+", "+Character.toLowerCase(msg.charAt(0))+msg.substring(1);
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    void play(boolean win) {
        Season s = store.seasons.get(store.current);
        int before = s.points;
        if (win) { s.streak++; s.lossStreak=0; s.points += reward(s.streak); }
        else { s.points -= 10; s.streak = 0; s.lossStreak++; }
        Match m = new Match(win, before, s.points, s.streak, s.currentDeck!=null ? s.currentDeck : "Unknown");
        s.matches.add(m);
        store.save(); view.invalidate();
        showMotivationalMessage(win, win?s.streak:s.lossStreak);
    }

    int reward(int streak) { return streak<=1?10:streak==2?13:streak==3?16:streak==4?19:22; }

    // Ricalcola punti/streak della Stagione a partire dall'ULTIMA partita rimasta (o dal baseline se non ce
    // ne sono piu'): usato dopo un annullamento.
    void recomputeSeasonState(Season s){
        if (s.matches.isEmpty()) { s.points = s.baseline; s.streak = s.initialStreak; s.lossStreak = 0; return; }
        Match last = s.matches.get(s.matches.size()-1);
        s.points = last.after; s.streak = last.streak;
        // lossStreak non e' salvato per singola partita (solo il win-streak lo e'): lo ricalcoliamo scorrendo
        // all'indietro finche' troviamo sconfitte consecutive (le correzioni non vere partite non contano).
        int ls=0;
        for(int i=s.matches.size()-1;i>=0;i--){
            Match m=s.matches.get(i);
            if(m.unknown) continue;
            if(m.win) break;
            ls++;
        }
        s.lossStreak = ls;
    }

    // Annulla l'ultima partita registrata: richiede sempre conferma esplicita (e' un'azione distruttiva).
    // Niente piu' "ripeti": se l'annullamento stesso e' stato un errore, si registra di nuovo la partita
    // premendo W o L.
    void confirmUndo(){
        Season s = store.seasons.get(store.current);
        if (s.matches.isEmpty()) { Toast.makeText(this,"Nessuna partita da annullare.",Toast.LENGTH_SHORT).show(); return; }
        Match last = s.matches.get(s.matches.size()-1);
        String title, message, button;
        if (last.unknown) {
            // Una correzione: nessun senso parlare di "registrare di nuovo con W o L", non e' una partita.
            title = "Annullare la correzione?";
            message = "La correzione manuale verrà eliminata definitivamente.";
            button = "Annulla correzione";
        } else {
            // Sappiamo gia' se era una vittoria o una sconfitta: lo diciamo esplicitamente invece del generico
            // "registra di nuovo con W o L".
            String outcome = last.win ? "una vittoria (W)" : "una sconfitta (L)";
            title = "Annullare l'ultima partita?";
            message = "Hai registrato "+outcome+": verrà eliminata definitivamente. Se hai sbagliato ad annullare, registra di nuovo "+outcome+".";
            button = "Annulla partita";
        }
        new AlertDialog.Builder(this).setTitle(title)
            .setMessage(message)
            .setPositiveButton(button, (d,w) -> {
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
        int[] curWL = countWL(s.matches); // vittorie/sconfitte cumulate ATTUALI, prima di questa correzione
        LinearLayout box = formBox();
        EditText p = numberField(""+s.points, true); p.setText(""+s.points);
        EditText st = numberField(""+s.streak, true); st.setText(""+s.streak);
        EditText wf = numberField(""+curWL[0], false); wf.setText(""+curWL[0]);
        EditText lf = numberField(""+curWL[1], false); lf.setText(""+curWL[1]);
        box.addView(label("Punti attuali")); box.addView(p);
        box.addView(label("Vittorie consecutive attuali")); box.addView(st);
        box.addView(label("Vittorie totali")); box.addView(wf);
        box.addView(label("Sconfitte totali")); box.addView(lf);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Aggiungi correzione manuale")
            .setMessage("Usala per allineare i punti quando hai giocato senza registrare le singole partite. Inserisci i totali ATTUALI (non solo quelli di questo periodo): calcolo io la differenza.")
            .setView(box).setPositiveButton("Conferma", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            try {
                int np = Integer.parseInt(p.getText().toString());
                int ns = Integer.parseInt(st.getText().toString());
                int nw = Integer.parseInt(wf.getText().toString());
                int nl = Integer.parseInt(lf.getText().toString());
                if (ns < 0 || nw < 0 || nl < 0) return false;
                int deltaW = nw - curWL[0], deltaL = nl - curWL[1];
                if (deltaW < 0 || deltaL < 0) return false; // i totali non possono diminuire
                Match m = Match.correction(s.points, np, s.currentDeck!=null ? s.currentDeck : "Unknown");
                m.correctionWins = deltaW; m.correctionLosses = deltaL; m.streak = ns; // altrimenti un annullamento successivo recupererebbe sempre streak=0 dalla correzione
                s.matches.add(m);
                s.points = np; s.streak = ns;
                store.save(); view.invalidate();
                return true;
            } catch (Exception e) { return false; }
        }, "Valori non validi (i totali di vittorie/sconfitte non possono diminuire).");
        dialog.show();
    }

    void newSeason(){ wizardStep1(false, null); }

    // Menu "⋮" della schermata di registrazione partita: azioni secondarie (modifica deck, correzione manuale).

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

    LinearLayout formBox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(6),dp(20),0);return l;}
    TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(MUTED_TXT);t.setTextSize(12);t.setPadding(0,dp(10),0,dp(4));return t;}
    EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);styleField(e);return e;}
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
    // Mezzanotte (00:00) di "daysBack" giorni fa: usato per il filtro "1 giorno"/"3 giorni" del grafico —
    // giorno di CALENDARIO, non finestra scorrevole di 24/72 ore, coerente col resto dell'app (dayKey).
    long midnightNDaysAgo(int daysBack){
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysBack);
        cal.set(java.util.Calendar.HOUR_OF_DAY,0); cal.set(java.util.Calendar.MINUTE,0);
        cal.set(java.util.Calendar.SECOND,0); cal.set(java.util.Calendar.MILLISECOND,0);
        return cal.getTimeInMillis();
    }

    // Conteggio W/L unificato: esclude le correzioni manuali (unknown=true), che non sono vittorie/sconfitte
    // vere. Centralizzare qui evita il bug per cui una correzione con punti saliti veniva erroneamente
    // contata come una vittoria vera in alcuni punti dell'app.
    static int[] countWL(List<Match> matches){
        int w=0,l=0;
        for(Match m: matches){
            if(m.unknown){ w+=m.correctionWins; l+=m.correctionLosses; }
            else { if(m.win) w++; else l++; }
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
        TextView header = new TextView(this);
        header.setText("Rinomina Stagione"); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);
        EditText e=field(s.name); e.setText(s.name);
        box.addView(e);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
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
    // Menu "⋮" della card di un deck: rinomina, aggiungi immagine, elimina (in rosso, sempre con conferma).
    void deckActionsMenu(Season s, Deck d, float rightEdgeX, float anchorY){
        view.showAnchoredMenu(rightEdgeX, anchorY,
            new String[]{"Rinomina deck","Scegli anteprima","Aggiungi Lista","Elimina deck"},
            new int[]{Color.WHITE, Color.WHITE, Color.WHITE, red()},
            new Runnable[]{ () -> renameDeckDialog(d), () -> showPreviewPicker(d), () -> openDeckImages(d), () -> confirmDeleteDeck(s,d) });
    }

    // Menu "⋮" delle card Stagione (lista principale): rinomina o elimina.
    void seasonActionsMenu(int idx, float rightEdgeX, float anchorY){
        Season s = store.seasons.get(idx);
        view.showAnchoredMenu(rightEdgeX, anchorY,
            new String[]{"Rinomina Stagione","Elimina Stagione"},
            new int[]{Color.WHITE, red()},
            new Runnable[]{ () -> { store.current = idx; renameSeason(); }, () -> confirmDeleteSeason(idx) });
    }

    void confirmDeleteSeason(int idx){
        Season s = store.seasons.get(idx);
        new AlertDialog.Builder(this).setTitle("Elimina \""+s.name+"\"")
            .setMessage("Verranno eliminate definitivamente tutte le "+s.matches.size()+" partite e i deck di questa Stagione. Azione irreversibile.")
            .setPositiveButton("Elimina", (dlg,w) -> {
                store.seasons.remove(idx);
                if (store.current>=store.seasons.size()) store.current = Math.max(0, store.seasons.size()-1);
                else if (store.current>idx) store.current--;
                store.save(); view.invalidate();
            })
            .setNegativeButton("Annulla", null)
            .show();
    }

    void confirmDeleteDeck(Season s, Deck d){ confirmDeleteDeck(s, d, null); }

    // onChanged (opzionale): richiamato a eliminazione avvenuta — nel dialog "Cambia deck" serve per togliere
    // subito la riga dalla lista, non solo dal tab Deck vero.
    void confirmDeleteDeck(Season s, Deck d, Runnable onChanged){
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
                if (onChanged!=null) onChanged.run();
            })
            .setNegativeButton("Annulla", null)
            .show();
    }

    // Scorre il tab Deck (canvas) fino a rendere visibile un deck specifico, in base alla sua posizione
    // nell'elenco ordinato — stessa formula (y iniziale 162, ogni card 104 alta) usata da decks().
    void scrollDeckTabToShow(Season s, Deck target){
        ArrayList<Deck> sorted = view.sortedDecks(s);
        int idx = sorted.indexOf(target);
        if (idx<0) return;
        float y = 162 + idx*104;
        view.scrollY = Math.max(0, y-20); // un po' di margine sopra, non incollato al bordo
        view.invalidate(); // finishScroll() rifara' il clamp su scrollY al prossimo disegno, se serve
    }

    void addDeck(){ addDeck(null); }

    // onCreated (opzionale): richiamato col Deck appena creato, se il salvataggio va a buon fine — usato dal
    // dialog "Cambia deck" per aggiornare la lista e selezionare subito il nuovo deck.
    void addDeck(java.util.function.Consumer<Deck> onCreated){
        Season s=store.seasons.get(store.current); LinearLayout box=formBox();
        // Deck "in sospeso": non ancora creato/salvato, serve solo per tenere lo stile/colore scelto
        // nell'anteprima finche' il salvataggio non lo trasferisce sul Deck vero.
        Deck pendingDeck = new Deck("");
        pendingDeck.previewStyle = store.preferredCardStyle; // parte dallo stile preferito, non sempre "spine"

        DeckPreviewThumbView thumb = new DeckPreviewThumbView(this, pendingDeck);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(dp(64), dp(80));
        FrameLayout thumbFrame = new FrameLayout(this); thumbFrame.addView(thumb, thumbLp);
        LinearLayout.LayoutParams thumbBoxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thumbBoxLp.gravity = Gravity.CENTER_HORIZONTAL; thumbBoxLp.bottomMargin = dp(14);
        box.addView(thumbFrame, thumbBoxLp);
        thumb.setOnClickListener(v -> showPreviewPicker(pendingDeck, thumb::invalidate));

        // Tolta l'etichetta "Nome Deck" sopra il campo: il titolo del dialog e' gia' "Nuovo Deck" e il campo
        // ha comunque il placeholder "Nome Deck" — prima la scritta compariva 3 volte, troppa ripetizione.
        EditText e=field("Nome Deck"); box.addView(e);
        Button img=new Button(this); img.setText("Aggiungi Lista (opzionale)"); styleSecondaryButton(img);
        // Margine e larghezza piena come negli altri dialog (prima il pulsante era attaccato al campo sopra,
        // senza respiro, e piu' stretto del contenuto — risultava piu' "povero" rispetto al dialog Nuova Sessione.
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        imgLp.topMargin = dp(14); img.setLayoutParams(imgLp);
        box.addView(img);
        img.setOnClickListener(v-> pickImageFor(null)); // null = immagine "in sospeso", verra' assegnata al Deck solo se il salvataggio va a buon fine
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Nuovo Deck").setView(box)
            .setPositiveButton("Conferma", null).setNegativeButton("Annulla", null).create();
        showNonDismissing(dialog, () -> {
            String n=e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            Deck deck=new Deck(n);
            // Trasferisce sul Deck vero l'anteprima scelta sul Deck "in sospeso".
            deck.previewStyle = pendingDeck.previewStyle; deck.previewColor = pendingDeck.previewColor;
            if(pendingImage!=null){
                Uri imgUri = pendingImage; pendingImage=null;
                deck.images.add(imgUri.toString());
                s.decks.add(deck);store.save();view.invalidate();
                if (onCreated!=null) onCreated.accept(deck);
                return true;
            }
            s.decks.add(deck);store.save();view.invalidate();
            if (onCreated!=null) onCreated.accept(deck);
            return true;
        }, "Nome Deck non valido o già esistente.");
        dialog.show();
    }

    // Trova, all'interno della Stagione corrente, il Deck con questo nome (ogni Stagione ha i propri Deck:
    // uno stesso nome in due Stagioni diverse corrisponde a due oggetti Deck distinti, con Liste distinte).
    // Solo l'ULTIMA Stagione creata resta giocabile: crearne una nuova blocca automaticamente tutte le
    // precedenti (rimangono visibili/consultabili, ma non puoi piu' registrare partite). Basato sulla
    // posizione nell'elenco (non su un flag salvato): se l'ultima viene eliminata, quella "nuova ultima"
    // torna giocabile in automatico, senza bisogno di gestire nulla a mano.
    boolean isSeasonLocked(int idx){ return idx != store.seasons.size()-1; }

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
        try { startActivityForResult(i,101); } catch(Exception ex) { Toast.makeText(this,"Nessuna app disponibile per selezionare la Lista.",Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==101 && result==RESULT_OK && data!=null){
            Uri uri = data.getData();
            try{ getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(Exception e){ Log.w(TAG, "Impossibile ottenere il permesso persistente sulla Lista", e); }
            if (pendingImageTargetDeck != null) {
                // Aggiunge alla Lista di un Deck gia' esistente: un Deck puo' avere piu' di una Lista.
                Deck targetDeck = pendingImageTargetDeck;
                targetDeck.images.add(uri.toString());
                pendingImageTargetDeck = null;
                store.save(); if (view != null) view.invalidate();
                // Dopo il caricamento, apre subito la galleria sulla Lista appena aggiunta (prima non dava
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

    // Piccola anteprima cliccabile per un Deck non ancora creato (dialog "Nuovo Deck"): disegna semplicemente
    // l'anteprima corrente (preimpostata o personalizzata) di un oggetto Deck "in sospeso", usato solo per
    // tenere la scelta finche' il salvataggio non la trasferisce sul Deck vero.
    class DeckPreviewThumbView extends View {
        Deck d;
        DeckPreviewThumbView(Context c, Deck d){ super(c); this.d=d; }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            drawDeckPreview(c, d, 0, 0, getWidth(), getHeight());
        }
    }

    // Cella della griglia nel dialog "Scegli anteprima": disegna una card preimpostata (stile+colore
    // correnti) e, se selezionata, il bordo arancione — stesso arancione usato per la card Stagione attuale.
    class PreviewSwatchView extends View {
        String style; String colorKey; boolean selected=false;
        PreviewSwatchView(Context c, String style, String colorKey){ super(c); this.style=style; this.colorKey=colorKey; }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float pad = dp(4);
            drawPresetPreviewCard(c, pad, pad, getWidth()-pad, getHeight()-pad, style, colorKey);
            if (selected) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3));
                p.setColor(Color.rgb(255,138,61)); // arancione: stesso usato per lo stroke della Stagione attuale
                float half=dp(1.5f);
                // Il rettangolo del bordo e' leggermente piu' esterno di quello della card (inset "half" invece
                // di "pad"): per restare concentrico e con la stessa curvatura visiva, il suo raggio deve
                // essere quello della card PIU' il gap tra i due bordi, non lo stesso valore numerico — prima
                // usavo lo stesso valore, che sui bordi esterni (piu' larghi) sembrava un raggio maggiore.
                float cardW = getWidth()-2*pad;
                float cardRadius = 8f*cardW/64f;
                float gap = pad-half;
                float strokeRadius = cardRadius + gap;
                c.drawRoundRect(new RectF(half,half,getWidth()-half,getHeight()-half), strokeRadius, strokeRadius, p);
            }
        }
    }

    // Dialog "Stile preferito card" (Impostazioni): solo 3 opzioni di STILE, sempre nel colore "grigio
    // chiaro" (quello di default) — non c'e' nessun deck coinvolto, e' una preferenza globale usata come
    // punto di partenza per l'anteprima di ogni nuovo deck creato.
    void showCardStylePreferenceDialog(){
        String[] selectedStyle = { store.preferredCardStyle };

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView title = new TextView(this); title.setText("Stile preferito per le card"); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.bottomMargin=dp(4);
        root.addView(title, titleLp);

        String[] styleKeys = {"spine","gem","holo","prism","ring","fold"};
        PreviewSwatchView[] swatches = new PreviewSwatchView[6];
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout gridRow = null;
        for (int i=0;i<6;i++){
            if (i%3==0){
                gridRow = new LinearLayout(this); gridRow.setOrientation(LinearLayout.HORIZONTAL); gridRow.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams gridRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                gridRowLp.topMargin = i==0?0:dp(12);
                grid.addView(gridRow, gridRowLp);
            }
            PreviewSwatchView sw = new PreviewSwatchView(this, styleKeys[i], "grigiochiaro");
            sw.selected = styleKeys[i].equals(selectedStyle[0]);
            swatches[i]=sw;
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(84), dp(105));
            swLp.leftMargin=dp(8); swLp.rightMargin=dp(8);
            gridRow.addView(sw, swLp);
            final String sk = styleKeys[i];
            sw.setOnClickListener(v -> { selectedStyle[0]=sk; for(PreviewSwatchView s: swatches) s.selected=s.style.equals(sk); for(PreviewSwatchView s: swatches) s.invalidate(); });
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin=dp(10); rowLp.bottomMargin=dp(14);
        root.addView(grid, rowLp);

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(6),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText("Annulla"); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(14);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText("Conferma"); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(14);
        confirmBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(cancelBtn); footer.addView(confirmBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            store.preferredCardStyle = selectedStyle[0];
            store.save(); view.invalidate();
            dialog.dismiss();
        });
    }

    void showPreviewPicker(Deck d){ showPreviewPicker(d, null); }

    // onChanged (opzionale): richiamato a conferma avvenuta, per far ridisegnare la riga se il dialog che ha
    // aperto questo picker (es. "Cambia deck") ha una sua vista separata che altrimenti non si aggiorna da
    // sola — invalidate() sulla TrackerView principale non tocca le view native di ALTRI dialog aperti.
    void showPreviewPicker(Deck d, Runnable onChanged){
        String[] activeStyle = { d.previewStyle };
        String[] selectedColor = { d.previewColor };

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        // Header: 6 tab di stile, 3 per riga (uno solo non ci starebbe comodo con 6 etichette).
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.VERTICAL);
        tabs.setPadding(dp(14),dp(14),dp(14),dp(10));
        TextView[] tabViews = new TextView[6];
        String[] styleKeys = {"spine","gem","holo","prism","ring","fold"};
        String[] styleLabels = {"Stile 1","Stile 2","Stile 3","Stile 4","Stile 5","Stile 6"};
        Runnable[] refreshTabs = new Runnable[1];
        LinearLayout tabRow = null;
        for (int i=0;i<6;i++){
            if (i%3==0){
                tabRow = new LinearLayout(this); tabRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                trLp.topMargin = i==0?0:dp(6);
                tabs.addView(tabRow, trLp);
            }
            TextView t = new TextView(this); t.setText(styleLabels[i]); t.setGravity(Gravity.CENTER); t.setTextSize(13);
            t.setPadding(0,dp(8),0,dp(8));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            tlp.leftMargin = (i%3==0)?0:dp(6);
            tabRow.addView(t, tlp);
            tabViews[i]=t;
        }
        root.addView(tabs);

        // Griglia: 2 colonne, tutti i 10 colori — scrollabile, alta abbastanza da mostrarne almeno 6 (3 righe).
        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(10),dp(4),dp(10),dp(4));
        PreviewSwatchView[] swatches = new PreviewSwatchView[PREVIEW_COLOR_ORDER.length];
        LinearLayout row = null;
        for (int i=0;i<PREVIEW_COLOR_ORDER.length;i++){
            if (i%2==0){
                row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = i==0?0:dp(14);
                grid.addView(row, rowLp);
            }
            String colorKey = PREVIEW_COLOR_ORDER[i];
            PreviewSwatchView sw = new PreviewSwatchView(this, activeStyle[0], colorKey);
            swatches[i]=sw;
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(96), dp(120));
            swLp.leftMargin = dp(10); swLp.rightMargin = dp(10);
            row.addView(sw, swLp);
            sw.setOnClickListener(v -> { selectedColor[0]=colorKey; for(PreviewSwatchView s: swatches) s.selected=(s.colorKey.equals(colorKey)); for(PreviewSwatchView s: swatches) s.invalidate(); });
        }
        for (int i=0;i<swatches.length;i++) swatches[i].selected = swatches[i].colorKey.equals(selectedColor[0]);
        // Stesso fix di larghezza esplicita usato in chooseCurrentDeck() (vedi commento lì).
        scroll.addView(grid, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(410));
        root.addView(scroll, scrollLp);

        refreshTabs[0] = () -> {
            for (int i=0;i<6;i++){
                boolean active = styleKeys[i].equals(activeStyle[0]);
                tabViews[i].setTextColor(active?Color.WHITE:MUTED_TXT);
                GradientDrawable tbg = new GradientDrawable(); tbg.setCornerRadius(dp(8));
                tbg.setColor(active?Color.rgb(30,46,72):Color.TRANSPARENT);
                tabViews[i].setBackground(tbg);
            }
            for (PreviewSwatchView s: swatches) { s.style = activeStyle[0]; s.invalidate(); }
        };
        refreshTabs[0].run();
        for (int i=0;i<6;i++){ final String sk = styleKeys[i]; tabViews[i].setOnClickListener(v -> { activeStyle[0]=sk; refreshTabs[0].run(); }); }

        // Footer: Annulla/Conferma. ("Carica immagine" rimosso: l'anteprima da immagine personalizzata non
        // esiste piu', restano solo le card preimpostate.)
        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(10),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText("Annulla"); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(14);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText("Conferma"); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(14);
        confirmBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(cancelBtn); footer.addView(confirmBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        // Rimuove l'area titolo che PocketDialogTheme (basato su Theme.Material.Dialog.Alert) riserva di
        // default anche senza alcun titolo impostato — invisibile nei dialog a schermo intero con sfondo
        // nero (nero su nero), ma ben visibile qui sopra lo sfondo colorato della card.
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        // Larghezza esplicita: senza, la finestra del dialog si dimensiona in modo ambiguo e le view a
        // MATCH_PARENT dentro si misurano storte (card strette, testo che sborda, tocchi che non registrano
        // sempre correttamente).
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            if (selectedColor[0]!=null){
                d.previewStyle = activeStyle[0]; d.previewColor = selectedColor[0];
                store.save(); if (view!=null) view.invalidate();
                if (onChanged!=null) onChanged.run();
            }
            dialog.dismiss();
        });
    }

    // Tap sull'anteprima di un deck: se esiste almeno una Lista per questo deck (in questa Stagione), chiede
    // prima cosa fare (dialog a 2 pulsanti); altrimenti va dritto alla scelta dell'anteprima, dato che non
    // c'e' nessuna Lista da poter visualizzare comunque.
    void handlePreviewTap(Deck d){
        if (d==null) return;
        if (!d.images.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(d.name)
                .setPositiveButton("Scegli anteprima", (dlg,w) -> showPreviewPicker(d))
                .setNegativeButton("Visualizza Lista", (dlg,w) -> showImageGallery(d,0))
                .show();
        } else {
            showPreviewPicker(d);
        }
    }

    // Icona cestino disegnata su un piccolo Bitmap (per usarla in ImageView nei dialog nativi, dove non
    // possiamo disegnare direttamente su Canvas come nella UI principale dell'app).
    // Icona "condividi": il classico glifo a 3 nodi collegati (un pallino a sinistra, due a destra, uniti
    // da due linee), usato per condividere l'immagine corrente della galleria (es. su WhatsApp).
    // Icona "lente d'ingrandimento" standard: un cerchio (stroke) + un manico obliquo in basso a destra.
    Bitmap makeSearchIcon(int color, int sizePx){
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(bmp);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(color); pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(sizePx*0.12f); pp.setStrokeCap(Paint.Cap.ROUND);
        float s=sizePx, cx=s*0.42f, cy=s*0.42f, r=s*0.30f;
        cc.drawCircle(cx,cy,r,pp);
        float handleStartX = cx + r*0.70f, handleStartY = cy + r*0.70f;
        cc.drawLine(handleStartX,handleStartY, s*0.92f, s*0.92f, pp);
        return bmp;
    }

    Bitmap makeShareIcon(int color, int sizePx){
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(bmp);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(color); pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(sizePx*0.09f); pp.setStrokeCap(Paint.Cap.ROUND);
        float s=sizePx;
        float leftX=s*0.22f, leftY=s*0.5f, topX=s*0.78f, topY=s*0.22f, botX=s*0.78f, botY=s*0.78f;
        cc.drawLine(leftX,leftY,topX,topY,pp);
        cc.drawLine(leftX,leftY,botX,botY,pp);
        pp.setStyle(Paint.Style.FILL);
        float r=s*0.13f;
        cc.drawCircle(leftX,leftY,r,pp);
        cc.drawCircle(topX,topY,r,pp);
        cc.drawCircle(botX,botY,r,pp);
        return bmp;
    }

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

    // Icona freccia-in-giu' per i selettori "▾": prima era un carattere Unicode ingrandito con uno span nel
    // testo del pulsante, che disallineava verticalmente il glifo dal resto del testo (lo scaling di un
    // singolo carattere via Span non garantisce l'allineamento sulla stessa baseline). Come "compound
    // drawable" nativo, Android centra automaticamente icona e testo sulla stessa riga, senza disallineamenti.
    Bitmap makeDownArrowIcon(int color, int sizePx){
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(bmp);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(color); pp.setStyle(Paint.Style.STROKE);
        pp.setStrokeWidth(sizePx*0.14f); pp.setStrokeCap(Paint.Cap.ROUND); pp.setStrokeJoin(Paint.Join.ROUND);
        float s = sizePx;
        android.graphics.Path p2 = new android.graphics.Path();
        p2.moveTo(s*0.22f, s*0.38f); p2.lineTo(s*0.5f, s*0.66f); p2.lineTo(s*0.78f, s*0.38f);
        cc.drawPath(p2, pp);
        return bmp;
    }

    // Anteprima automatica del deck: ritaglia una regione a PERCENTUALI FISSE della prima immagine caricata
    // (il box del mazzo nella schermata profilo del gioco, che sta sempre grosso modo nella stessa posizione
    // relativa) — non e' un vero riconoscimento dell'immagine, solo una stima geometrica; funziona in modo
    // ===== Anteprime preimpostate: 3 stili (spine/gem/holo) x 9 colori selezionabili + arcobaleno
    // (usato per il colore "arcobaleno" stesso, e come placeholder quando nessun deck e' selezionato).
    // Ogni colore e' una tripla {light, mid, deep} (dal mockup HTML approvato). =====
    static final java.util.LinkedHashMap<String,int[]> PREVIEW_COLORS = new java.util.LinkedHashMap<>();
    static {
        PREVIEW_COLORS.put("verde",       new int[]{Color.rgb(0x5F,0xCB,0x8A), Color.rgb(0x2E,0x8F,0x5A), Color.rgb(0x1B,0x5C,0x39)});
        PREVIEW_COLORS.put("rosso",       new int[]{Color.rgb(0xE8,0x74,0x6A), Color.rgb(0xC2,0x3B,0x3B), Color.rgb(0x7E,0x23,0x23)});
        PREVIEW_COLORS.put("azzurro",     new int[]{Color.rgb(0x6F,0xD3,0xEF), Color.rgb(0x2F,0xA8,0xD9), Color.rgb(0x1B,0x6C,0x90)});
        PREVIEW_COLORS.put("giallo",      new int[]{Color.rgb(0xF4,0xD3,0x5E), Color.rgb(0xE0,0xB0,0x23), Color.rgb(0x9C,0x7A,0x16)});
        PREVIEW_COLORS.put("viola",       new int[]{Color.rgb(0xA8,0x83,0xE0), Color.rgb(0x7B,0x4F,0xC9), Color.rgb(0x4E,0x32,0x86)});
        PREVIEW_COLORS.put("marrone",     new int[]{Color.rgb(0xB9,0x83,0x5A), Color.rgb(0x8A,0x5A,0x34), Color.rgb(0x5A,0x3A,0x20)});
        PREVIEW_COLORS.put("grigioscuro", new int[]{Color.rgb(0x5A,0x62,0x70), Color.rgb(0x3A,0x40,0x48), Color.rgb(0x20,0x24,0x2A)});
        PREVIEW_COLORS.put("oro",         new int[]{Color.rgb(0xF2,0xD8,0x89), Color.rgb(0xD4,0xAF,0x37), Color.rgb(0x8C,0x6A,0x16)});
        PREVIEW_COLORS.put("grigiochiaro",new int[]{Color.rgb(0xC7,0xCD,0xD6), Color.rgb(0x9A,0xA3,0xAE), Color.rgb(0x6B,0x74,0x80)});
    }
    static final String[] PREVIEW_COLOR_ORDER = {"verde","rosso","azzurro","giallo","viola","marrone","grigioscuro","oro","grigiochiaro","arcobaleno"};
    static final String[] PREVIEW_COLOR_LABELS = {"Verde","Rosso","Azzurro","Giallo","Viola","Marrone","Grigio scuro","Oro","Grigio chiaro","Arcobaleno"};
    static final int[] RAINBOW_HUES = { Color.rgb(0xE8,0x74,0x6A), Color.rgb(0xE0,0xB0,0x23), Color.rgb(0x5F,0xCB,0x8A), Color.rgb(0x2F,0xA8,0xD9), Color.rgb(0x7B,0x4F,0xC9), Color.rgb(0xE8,0x74,0x6A) };

    // Punto d'ingresso unico per disegnare l'anteprima di un deck (o il placeholder "nessun deck"): sempre
    // una card preimpostata disegnata sul canvas — l'anteprima da immagine personalizzata e' stata rimossa
    // (causava troppi problemi), restano solo le Liste (screenshot) come funzione separata.
    void drawDeckPreview(Canvas c, Deck d, float l, float t, float r, float b){
        if (d==null) { drawPresetPreviewCard(c, l,t,r,b, "spine", "arcobaleno"); return; }
        drawPresetPreviewCard(c, l,t,r,b, d.previewStyle==null?"spine":d.previewStyle, d.previewColor==null?"grigiochiaro":d.previewColor);
    }

    // Disegna UNA card preimpostata (stile + colore) nel rettangolo dato. cornerRadius scalato in proporzione
    // alla dimensione della card, cosi' funziona sia per l'anteprima piccola (64x80) sia per le card grandi
    // del dialog di selezione.
    void drawPresetPreviewCard(Canvas c, float l, float t, float r, float b, String style, String colorKey){
        float cr = 8f*(r-l)/64f;
        Path clip = new Path(); clip.addRoundRect(new RectF(l,t,r,b), cr,cr, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        boolean rainbow = "arcobaleno".equals(colorKey);
        int[] shades = rainbow ? null : PREVIEW_COLORS.get(colorKey);
        if (shades==null && !rainbow) shades = PREVIEW_COLORS.get("grigiochiaro");
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (style==null?"spine":style) {
            case "gem": drawPreviewGem(c, pp, l,t,r,b, shades, rainbow); break;
            case "holo": drawPreviewHolo(c, pp, l,t,r,b, shades, rainbow); break;
            case "prism": drawPreviewPrism(c, pp, l,t,r,b, shades, rainbow); break;
            case "ring": drawPreviewRing(c, pp, l,t,r,b, shades, rainbow); break;
            case "fold": drawPreviewFold(c, pp, l,t,r,b, shades, rainbow); break;
            default: drawPreviewSpine(c, pp, l,t,r,b, shades, rainbow); break;
        }
        c.restore();
    }

    void drawPreviewSpine(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        float spineW = (r-l)*0.26f;
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) {
            pp.setShader(new SweepGradient((l+r)/2, (t+b)/2, RAINBOW_HUES, null));
            c.drawRect(l,t,r,b,pp);
            pp.setShader(null);
            pp.setColor(Color.argb(110,10,14,20)); // dorso: overlay scuro sulla fascia sinistra
            c.drawRect(l,t,l+spineW,b,pp);
        } else {
            pp.setColor(shades[2]); c.drawRect(l,t,l+spineW,b,pp); // dorso
            pp.setColor(shades[1]); c.drawRect(l+spineW,t,r,b,pp); // pannello
            pp.setShader(new LinearGradient(0,t,0,t+(b-t)*0.22f, Color.argb(40,255,255,255), Color.argb(0,255,255,255), Shader.TileMode.CLAMP));
            c.drawRect(l+spineW,t,r,t+(b-t)*0.22f,pp);
            pp.setShader(null);
        }
    }

    void drawPreviewGem(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        float cx=(l+r)/2, cy=(t+b)/2, w=r-l;
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(new SweepGradient(cx,cy, RAINBOW_HUES, null)); }
        else { pp.setShader(new RadialGradient(l+w*0.3f, t+w*0.2f, w*1.4f, shades[1], shades[2], Shader.TileMode.CLAMP)); }
        c.drawRect(l,t,r,b,pp); pp.setShader(null);
        float s=w*0.34f;
        c.save(); c.rotate(45,cx,cy);
        RectF facet = new RectF(cx-s/2,cy-s/2,cx+s/2,cy+s/2);
        pp.setColor(rainbow ? Color.argb(140,10,14,20) : shades[0]);
        if (!rainbow) pp.setShader(new LinearGradient(facet.left,facet.top,facet.right,facet.bottom, shades[0], shades[1], Shader.TileMode.CLAMP));
        c.drawRoundRect(facet, s*0.1f, s*0.1f, pp); pp.setShader(null);
        pp.setStyle(Paint.Style.STROKE); pp.setColor(Color.argb(90,255,255,255)); pp.setStrokeWidth(Math.max(1f, 1f*(r-l)/64f));
        c.drawRoundRect(facet, s*0.1f, s*0.1f, pp);
        c.restore();
    }

    void drawPreviewHolo(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(new SweepGradient((l+r)/2,(t+b)/2, RAINBOW_HUES, null)); }
        else { pp.setColor(shades[1]); }
        c.drawRect(l,t,r,b,pp); pp.setShader(null);
        pp.setShader(new LinearGradient(l,t,r,b,
            new int[]{Color.argb(0,255,255,255), Color.argb(0,255,255,255), Color.argb(95,255,255,255), Color.argb(20,255,255,255), Color.argb(0,255,255,255)},
            new float[]{0f,0.30f,0.45f,0.55f,0.68f}, Shader.TileMode.CLAMP));
        c.drawRect(l,t,r,b,pp); pp.setShader(null);
        pp.setStyle(Paint.Style.STROKE); pp.setColor(Color.argb(36,255,255,255)); pp.setStrokeWidth(Math.max(1f, 1f*(r-l)/64f));
        c.drawRect(l,t,r,b,pp);
    }

    // Stile 4 "Prism": la card divisa in due da un taglio diagonale netto (chiaro in alto/sinistra, scuro in
    // basso/destra), con una sottile cucitura chiara lungo il taglio — piu' angolare e deciso del rombo
    // centrale di "Gem".
    void drawPreviewPrism(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(new SweepGradient((l+r)/2,(t+b)/2, RAINBOW_HUES, null)); c.drawRect(l,t,r,b,pp); pp.setShader(null); }
        else { pp.setColor(shades[1]); c.drawRect(l,t,r,b,pp); }
        Path lightHalf = new Path(); lightHalf.moveTo(l,t); lightHalf.lineTo(r,t); lightHalf.lineTo(l,b); lightHalf.close();
        pp.setColor(rainbow ? Color.argb(70,255,255,255) : shades[0]);
        c.drawPath(lightHalf, pp);
        Path deepHalf = new Path(); deepHalf.moveTo(r,t); deepHalf.lineTo(r,b); deepHalf.lineTo(l,b); deepHalf.close();
        pp.setColor(rainbow ? Color.argb(100,10,14,20) : shades[2]);
        c.drawPath(deepHalf, pp);
        pp.setStyle(Paint.Style.STROKE); pp.setColor(Color.argb(130,255,255,255)); pp.setStrokeWidth(Math.max(1f, 1f*(r-l)/64f));
        c.drawLine(l,t,r,b,pp);
    }

    // Stile 5 "Ring": un medaglione — anello sottile chiaro attorno a un disco centrale, su sfondo scuro
    // (il colore/arcobaleno vive solo nell'anello) — silhouette circolare, ben distinta dai rettangoli di
    // "Spine" e dal rombo di "Gem".
    void drawPreviewRing(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        float cx=(l+r)/2, cy=(t+b)/2, side=Math.min(r-l,b-t);
        pp.setStyle(Paint.Style.FILL); pp.setColor(rainbow ? Color.rgb(18,24,34) : shades[2]);
        c.drawRect(l,t,r,b,pp);
        float outerR=side*0.38f, innerR=outerR*0.58f;
        if (rainbow) pp.setShader(new SweepGradient(cx,cy,RAINBOW_HUES,null)); else pp.setColor(shades[1]);
        c.drawCircle(cx,cy,outerR,pp); pp.setShader(null);
        pp.setColor(rainbow ? Color.rgb(18,24,34) : shades[2]);
        c.drawCircle(cx,cy,innerR,pp);
        pp.setColor(rainbow ? Color.WHITE : shades[0]);
        c.drawCircle(cx,cy,innerR*0.4f,pp);
        pp.setStyle(Paint.Style.STROKE); pp.setColor(Color.argb(80,255,255,255)); pp.setStrokeWidth(Math.max(1f, 1f*(r-l)/64f));
        c.drawCircle(cx,cy,outerR,pp);
    }

    // Stile 6 "Fold": un angolo "piegato" in alto a destra (come una pagina d'archivio con l'angolo
    // ripiegato), colore chiaro sulla piega con una leggera ombra lungo la piega stessa — silhouette
    // asimmetrica, diversa da tutte le altre.
    void drawPreviewFold(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(new SweepGradient((l+r)/2,(t+b)/2, RAINBOW_HUES, null)); c.drawRect(l,t,r,b,pp); pp.setShader(null); }
        else { pp.setColor(shades[1]); c.drawRect(l,t,r,b,pp); }
        float foldSize = (r-l)*0.42f;
        Path shadow = new Path();
        shadow.moveTo(r-foldSize,t); shadow.lineTo(r,t+foldSize); shadow.lineTo(r-foldSize,t+foldSize); shadow.close();
        pp.setColor(Color.argb(60,0,0,0));
        c.drawPath(shadow, pp);
        Path fold = new Path();
        fold.moveTo(r-foldSize,t); fold.lineTo(r,t); fold.lineTo(r,t+foldSize); fold.close();
        pp.setColor(rainbow ? Color.argb(210,255,255,255) : shades[0]);
        c.drawPath(fold, pp);
        pp.setStyle(Paint.Style.STROKE); pp.setColor(Color.argb(50,0,0,0)); pp.setStrokeWidth(Math.max(1f, 1f*(r-l)/64f));
        c.drawLine(r-foldSize,t,r,t+foldSize,pp);
    }

    // Visualizzatore in stile galleria: header in alto (chiudi/titolo/aggiungi), frecce di navigazione come
    // piccoli cerchi semi-trasparenti sovrapposti ai lati dell'immagine, ed "elimina" (cestino) accanto al
    // contatore "N / M" sotto — cosi' e' chiaro che si riferisce ALLO SCREENSHOT visualizzato in quel momento,
    // non a un'azione generica. Ritaglia automaticamente il 19% dall'alto e il 14% dal basso: gli screenshot
    // del gioco hanno spesso intestazioni/pulsanti di sistema poco utili in quelle zone.
    // Editor di ritaglio a 2 fasi per l'anteprima del deck: prima mostra l'immagine intera con il riquadro
    // (posizione di default, dimensione fissa ad aspect ratio 0.8 coerente con l'anteprima 64x80) e si puo'
    // spostare; finito il primo trascinamento passa a mostrare SOLO l'area ritagliata, zoomata, per
    // un'ultima rifinitura fine (sempre solo spostamento, mai ridimensionamento). Risolve il problema delle
    // percentuali fisse di ritaglio che con risoluzioni/screenshot diversi finivano spesso storte.
    // (CropEditorView e showCropEditor rimossi: anteprima da immagine personalizzata eliminata, causava troppi problemi — restano solo le anteprime preimpostate e le Liste)


    void showImageGallery(Deck d, int startIndex){
        if (d.images.isEmpty()) return;
        final int[] idx = {Math.max(0, Math.min(startIndex, d.images.size()-1))};
        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        // Rimuove l'area titolo che PocketDialogTheme (basato su Theme.Material.Dialog.Alert) riserva di
        // default anche senza alcun titolo impostato — invisibile nei dialog a schermo intero con sfondo
        // nero (nero su nero), ma ben visibile qui sopra lo sfondo colorato della card.
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
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
        ImageView shareIcon = new ImageView(this);
        int iconSizePx = dp(20);
        shareIcon.setImageBitmap(makeShareIcon(Color.WHITE, iconSizePx));
        shareIcon.setPadding(dp(10),dp(6),dp(10),dp(6));
        ImageView deleteIcon = new ImageView(this);
        deleteIcon.setImageBitmap(makeTrashIcon(red(), iconSizePx));
        deleteIcon.setPadding(dp(10),dp(6),dp(10),dp(6));
        bottomRow.addView(counter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bottomRow.addView(shareIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
                Toast.makeText(this,"Impossibile caricare la Lista (file non più disponibile).",Toast.LENGTH_SHORT).show();
            }
            counter.setText((idx[0]+1)+" / "+d.images.size());
            prevBtn.setVisibility(idx[0]>0 ? View.VISIBLE : View.INVISIBLE);
            nextBtn.setVisibility(idx[0]<d.images.size()-1 ? View.VISIBLE : View.INVISIBLE);
        };
        prevBtn.setOnClickListener(v -> { if(idx[0]>0){ idx[0]--; load[0].run(); } });
        nextBtn.setOnClickListener(v -> { if(idx[0]<d.images.size()-1){ idx[0]++; load[0].run(); } });
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        addBtn.setOnClickListener(v -> { dialog.dismiss(); pickImageFor(d); });
        // Condivisione diretta (es. su WhatsApp): l'URI e' gia' un content:// restituito dal selettore di
        // sistema, quindi non serve un FileProvider ne' alcun permesso runtime — l'app che riceve l'Intent
        // ottiene un accesso di lettura temporaneo grazie al flag sotto, gestito dal sistema stesso.
        shareIcon.setOnClickListener(v -> {
            try {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("image/*");
                shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(d.images.get(idx[0])));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Condividi Lista"));
            } catch (Exception e) {
                Toast.makeText(this,"Impossibile condividere questa Lista.",Toast.LENGTH_SHORT).show();
            }
        });
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
        int partiteTab=0; // 0 = Grafico, 1 = Lista partite/correzioni (tab dentro la card "PARTITE")
        int chartRange=0; // 0 = 1 giorno (da mezzanotte), 1 = 3 giorni, 2 = tutto
        // Barra "salta al giorno": scroll ORIZZONTALE indipendente da quello verticale della lista partite.
        // Il collasso/espandi per giorno e' stato rimosso: saltare direttamente al giorno risolve lo stesso
        // problema (navigare centinaia di partite) in modo piu' diretto.
        float dateBarScrollX=0, dateBarMaxScrollX=0, dateBarTop=0, dateBarBottom=0;
        // Zona di tocco delle pillole "1 giorno/3 giorni/Tutto" (tab Grafico), calcolata durante il disegno
        // esattamente come le coordinate di disegno stesse — MAI un numero fisso copiato a mano, che la
        // volta scorsa e' rimasto vecchio quando ho cambiato il calcolo di contentTop, causando un bug di
        // tocco reale (la zona cliccabile non corrispondeva piu' a dove le pillole erano davvero disegnate).
        float rangePillsTop=0, rangePillsBottom=0;
        // Posizione del badge "Annulla" flottante, calcolata durante il disegno e letta dal tocco — stesso
        // principio delle altre coordinate condivise, per non ripetere lo stesso bug di sfasamento.
        float undoBadgeCx=0, undoBadgeCy=0;
        // Stesso principio: margine sinistro della barra date condiviso tra disegno e tocco (era un numero
        // magico "10" duplicato a mano nel codice di tocco — stessa classe di bug, per sicurezza lo elimino).
        float dateBarPillLeftMargin=10;
        ArrayList<float[]> rangePillBounds=new ArrayList<>(); // [x,width] logici, stesso ordine di rangeLabels
        String dateBarScrollKey="";
        // Messaggio di benvenuto (lista Stagioni): scelto una sola volta per sessione app, non ad ogni
        // ridisegno — altrimenti cambierebbe a ogni frame durante uno scroll, sembrando un glitch.
        String cachedGreeting=null;
        String greetingMessage(){
            if(cachedGreeting!=null) return cachedGreeting;
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            String[][] pool = (hour<6) ? GREETING_NIGHT : (hour<12) ? GREETING_MORNING : (hour<18) ? GREETING_AFTERNOON : GREETING_EVENING;
            String[] pair = pool[new java.util.Random().nextInt(pool.length)];
            boolean hasName = store.trainerName!=null && !store.trainerName.isEmpty();
            cachedGreeting = hasName ? String.format(pair[0], store.trainerName) : pair[1];
            return cachedGreeting;
        }
        boolean isDraggingDateBar=false, dateBarDragCandidate=false; float touchStartDateBarScrollX=0;
        ArrayList<String> dateBarDayKeys=new ArrayList<>(); ArrayList<float[]> dateBarPillBounds=new ArrayList<>(); // [x,width] logici, stesso ordine di dateBarDayKeys
        java.util.HashMap<String,Float> dayYOffsetMap=new java.util.HashMap<>(); // dayKey -> offset Y (nel contenuto della lista) dove inizia quel gruppo
        void resetDateBarIfNeeded(String key, float totalW, float visibleW){
            dateBarMaxScrollX = Math.max(0, totalW-visibleW);
            if(!key.equals(dateBarScrollKey)){ dateBarScrollX = dateBarMaxScrollX; dateBarScrollKey = key; } // di default: mostra il piu' recente (a destra)
            if(dateBarScrollX>dateBarMaxScrollX) dateBarScrollX=dateBarMaxScrollX;
            if(dateBarScrollX<0) dateBarScrollX=0;
        }
        int bg=Color.rgb(7,11,18), card=Color.rgb(14,24,38), white=Color.WHITE, muted=Color.rgb(165,175,190), blue=Color.rgb(55,120,255), green=Color.rgb(70,205,75), red=Color.rgb(245,70,60);
        ArrayList<Hit> seasonHits=new ArrayList<>();
        // Posizione dei "⋮" nella lista Stagioni, calcolata durante lo stesso disegno (non un numero
        // duplicato a mano nel tocco — stessa lezione delle pillole del grafico).
        ArrayList<float[]> seasonKebabPos=new ArrayList<>(); // [x,y,index]
        // Posizione del "⋮" nella card "Deck Selezionato" (tab Gioca), calcolata durante lo stesso disegno.
        // Zona di tocco dell'anteprima nelle card del tab Deck: {x1,y1,x2,y2,Deck}, calcolata durante il disegno.
        ArrayList<Object[]> deckPreviewTapZones=new ArrayList<>();
        float currentDeckKebabX=-1000, currentDeckKebabY=-1000; // fuori schermo di default: nessun tap accidentale se non c'e' un deck selezionato
        ArrayList<Hit> matchHits=new ArrayList<>();
        // Tutti i numeri usati in questa classe (posizioni, dimensioni testo, ecc.) sono pensati come "dp"
        // (unita' indipendenti dalla densita' dello schermo), NON pixel reali. 'density' converte l'uno
        // nell'altro: senza, su un telefono moderno (densita' ~3x) tutto apparirebbe rimpicciolito a 1/3.
        final float density;
        // Ricerca deck (tab Deck): stato letto/scritto sia dal canvas (per il filtro) sia dalla EditText
        // nativa sovrapposta (per il testo digitato) — vedi buildDeckSearchBar() in MainActivity.
        String deckSearchQuery="";
        @Override public void invalidate(){
            super.invalidate();
            positionDeckSearchBar(); // tiene la pillola nativa sincronizzata con schermata/tab correnti
        }
        // Scroll verticale: ogni schermata ha un header (e talvolta un footer) fissi, con il contenuto in
        // mezzo che scorre se supera l'altezza disponibile. bodyTop/bodyBottom delimitano la zona scrollabile
        // per la schermata corrente; lastContentBottom e' impostato da ciascun metodo di disegno a fine
        // contenuto, per calcolare quanto si puo' scorrere.
        float scrollY=0, maxScrollY=0, bodyTop=0, bodyBottom=0, lastContentBottom=0;
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
        // Disegna un bitmap ritagliato "a copertura" (come object-fit:cover, non uno stiramento) dentro un
        // rettangolo con angoli arrotondati: fattorizzato qui perche' ora serve in due posti (card dei deck
        // e card "Deck Selezionato").
        void drawCoverImage(Canvas c, Bitmap bmp, float l, float t, float r, float b, float radius){
            float dstAspect = (r-l)/(b-t);
            int pw=bmp.getWidth(), ph=bmp.getHeight();
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
            clip.addRoundRect(new android.graphics.RectF(l,t,r,b), radius,radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            android.graphics.Rect dst = new android.graphics.Rect((int)l,(int)t,(int)r,(int)b);
            c.drawBitmap(bmp, src, dst, null);
            c.restore();
        }

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

        // Icona di aiuto ("?" dentro un cerchio) per riproporre la guida introduttiva dalla lista Stagioni.
        void drawHelpIcon(Canvas c, float cx, float cy, float size, int color){
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f);
            c.drawCircle(cx,cy,size*0.42f,p);
            txt(c,"?",cx,centeredBaseline(cy,size*0.55f),size*0.55f,color,Paint.Align.CENTER);
        }

        // Icona "ingranaggio" per l'accesso alle Impostazioni dalla lista Stagioni: un cerchio centrale con
        // 8 dentini attorno, disegnati come piccoli rettangoli ruotati.
        void drawGearIcon(Canvas c, float cx, float cy, float size, int color){
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            // Dentini RETTANGOLARI ad angoli vivi (drawRect, non drawRoundRect): prima l'arrotondamento era
            // quasi meta' della loro stessa larghezza, li faceva sembrare palline invece che rettangolini.
            int teeth=8;
            float ringR=size*0.30f, toothLen=size*0.16f, toothW=size*0.16f;
            for(int i=0;i<teeth;i++){
                c.save();
                c.rotate(i*(360f/teeth), cx, cy);
                c.drawRect(cx-toothW/2, cy-ringR-toothLen, cx+toothW/2, cy-ringR+1, p);
                c.restore();
            }
            c.drawCircle(cx,cy,ringR,p);
            p.setColor(bg); c.drawCircle(cx,cy,ringR*0.5f,p);
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

        // Palette di colori accento per deck: stesso deck = sempre stesso colore (hash deterministico del
        // nome), deck diversi = colori diversi nella maggior parte dei casi. Cosi' due serie ADIACENTI ma di
        // deck diversi non si fondono visivamente in un'unica striscia continua (bug con il colore unico
        // fisso di prima) — e la stessa palette e' pronta per essere riusata in futuro anche per il matting
        // delle anteprime.
        // Palette di colori accento per la N-esima SERIE incontrata (non per deck: con l'hash le collisioni
        // sono molto piu' probabili di quanto sembri, con solo 5-15 deck per Stagione). Colori tenui, non
        // gridano, ma restano ben distinguibili l'uno dall'altro tra serie consecutive.
        final int[] DECK_ACCENT_COLORS = {
            Color.rgb(224,138,79),  // arancione tenue
            Color.rgb(94,158,199),  // azzurro tenue
            Color.rgb(163,120,181), // viola tenue
            Color.rgb(107,172,133), // verde tenue
            Color.rgb(196,168,88),  // ocra tenue
            Color.rgb(190,110,130), // rosa tenue
        };

        // Spezza un testo su 2 righe se non entra nella larghezza data, scegliendo lo spazio piu' vicino al
        // centro come punto di rottura (mai a meta' di una parola). Se entra su una riga, o non c'e' nessuno
        // spazio utile, lo restituisce invariato.
        String[] wrapText(String text, float maxWidth, float textSize){
            p.setTextSize(textSize);
            if (p.measureText(text) <= maxWidth) return new String[]{text};
            int mid = text.length()/2, bestSpace=-1, bestDist=Integer.MAX_VALUE;
            for (int i=0;i<text.length();i++){
                if (text.charAt(i)==' '){
                    int dist = Math.abs(i-mid);
                    if (dist<bestDist){ bestDist=dist; bestSpace=i; }
                }
            }
            if (bestSpace==-1) return new String[]{text};
            return new String[]{text.substring(0,bestSpace), text.substring(bestSpace+1)};
        }

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
            c.drawRoundRect(l,t,rr,b,18,18,p); // 18, come box(): prima era 16, non corrispondeva al raggio dello sfondo che circonda
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
            if (screen == SCREEN_SETTINGS) { settingsScreen(c,w,h); c.restore(); return; }
            Season s = store.seasons.get(store.current);
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
            seasonHits.clear(); seasonKebabPos.clear();
            drawGearIcon(c, w-30, 56, 20, muted);
            drawHelpIcon(c, w-64, 56, 20, muted);
            // Niente piu' titolo "Pocket Tracker": il messaggio di benvenuto prende il suo posto e la sua
            // dimensione di font (20), con capo automatico se troppo lungo per una riga, centrato
            // verticalmente in una fascia dedicata — spostata piu' in basso (+24 su tutto, a cascata, rispetto
            // alla versione precedente).
            float headerZoneTop=76, headerZoneBottom=160, headerCenterY=(headerZoneTop+headerZoneBottom)/2;
            String[] greetLines = wrapText(greetingMessage(), w-64, 20);
            if (greetLines.length==1){
                txt(c,greetLines[0],w/2,centeredBaseline(headerCenterY,20),20,white,Paint.Align.CENTER);
            } else {
                float[] gl = centerLines(headerCenterY,6,20,20);
                txt(c,greetLines[0],w/2,gl[0],20,white,Paint.Align.CENTER);
                txt(c,greetLines[1],w/2,gl[1],20,white,Paint.Align.CENTER);
            }
            bodyTop=170; bodyBottom=h;
            resetScrollIfNeeded("seasonlist");
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);
            float y=178;

            int lastIdx = store.seasons.size()-1; // l'unica giocabile: solo l'ultima creata
            Season current = store.seasons.get(lastIdx);
            txt(c,"STAGIONE ATTUALE",24,y+8,12,muted,Paint.Align.LEFT);
            y+=16;
            box(c,18,y,w-18,y+110, Color.rgb(20,44,80));
            // Bordo arancione distintivo, solo su questa card — era usato in passato nell'app per segnalare
            // la sessione attiva, lo recupero qui per lo stesso concetto ("questa e' quella su cui giochi").
            strokeBox(c,18,y,w-18,y+110, Color.rgb(255,138,61));
            drawKebabIcon(c, w-40, y+22, muted);
            seasonKebabPos.add(new float[]{w-40, y+22, lastIdx});
            txt(c,current.name,34,y+28,18,white,Paint.Align.LEFT);
            {
                int[] wl=countWL(current.matches); int W=wl[0],L=wl[1];
                float wr=(W+L)==0?0:100f*W/(W+L);
                txt(c,"Punti "+current.points+"   Vittorie consecutive "+current.streak,34,y+52,12,muted,Paint.Align.LEFT);
                txtRow(c,34,y+74,12,
                    new String[]{W+"W   ", L+"L   ", "WR "+String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
                txt(c,current.matches.size()+" partite",34,y+96,11,muted,Paint.Align.LEFT);
            }
            seasonHits.add(new Hit(y,y+110,lastIdx));
            y+=110;

            if(lastIdx>0){
                y+=28;
                txt(c,"STAGIONI PASSATE",24,y,12,muted,Paint.Align.LEFT);
                y+=18;
                // Righe piu' compatte delle Stagioni non giocabili: ordine dalla piu' recente alla piu'
                // vecchia (indice decrescente), niente lucchetto — la sezione stessa in cui si trovano basta
                // a comunicare che sono "chiuse".
                for(int i=lastIdx-1;i>=0;i--){
                    Season s=store.seasons.get(i);
                    box(c,18,y,w-18,y+68, card);
                    drawKebabIcon(c, w-40, y+22, muted);
                    seasonKebabPos.add(new float[]{w-40, y+22, i});
                    txt(c,s.name,34,y+27,15,white,Paint.Align.LEFT);
                    int[] wl=countWL(s.matches); int W=wl[0],L=wl[1];
                    float wr=(W+L)==0?0:100f*W/(W+L);
                    txtRow(c,34,y+48,11,
                        new String[]{W+"W  ", L+"L  ", "WR "+String.format(Locale.US,"%.1f%%",wr)+"  ", s.matches.size()+" partite"},
                        new int[]{green, red, wrColor(wr,W+L), muted});
                    seasonHits.add(new Hit(y,y+68,i));
                    y+=78;
                }
            }
            lastContentBottom = y+20;
            c.restore();
            finishScroll(); drawScrollbar(c,w);
            // Pulsante "Nuova Stagione" in basso a destra (floating action button, sempre fisso): sempre
            // raggiungibile col pollice, non scorre via col resto del contenuto.
            box(c,w-166,h-104,w-18,h-54,blue); txt(c,"Nuova Stagione",w-92,h-73,14,white,Paint.Align.CENTER);
        }

        void settingsScreen(Canvas c, float w, float h){
            float centerY=28;
            drawChevronBack(c,24,centerY,20,white);
            txt(c,"Impostazioni",44,centeredBaseline(centerY,20),20,white,Paint.Align.LEFT);
            bodyTop=52; bodyBottom=h;
            resetScrollIfNeeded("settings");
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);

            box(c,18,64,w-18,144,card);
            txt(c,"NOME ALLENATORE",34,86,12,muted,Paint.Align.LEFT);
            String nameLabel = (store.trainerName==null || store.trainerName.isEmpty()) ? "Nessun nome impostato" : store.trainerName;
            txt(c,nameLabel,34,centeredBaseline(115,18),18, (store.trainerName==null||store.trainerName.isEmpty())?muted:white, Paint.Align.LEFT);
            drawEditIcon(c, w-40, 115, 18, white);

            // Stile preferito per le anteprime dei nuovi deck: mostrato in "grigio chiaro" come esempio
            // neutro (lo stesso usato di default), il tocco apre la scelta tra i 3 stili. Card ridotta (158-
            // 282, non piu' 158-308): tolto il tip "Tocca per cambiare" sotto, restava spazio vuoto inutile.
            box(c,18,158,w-18,282,card);
            txt(c,"STILE PREFERITO CARD",w/2,180,12,muted,Paint.Align.CENTER);
            drawPresetPreviewCard(c, w/2-32,192,w/2+32,272, store.preferredCardStyle, "grigiochiaro");

            // Lingua: stessa impostazione grafica di "Nome allenatore" sopra (etichetta + valore + matita).
            box(c,18,296,w-18,376,card);
            txt(c,"LINGUA",34,318,12,muted,Paint.Align.LEFT);
            int langIdx = java.util.Arrays.asList(LANGUAGE_CODES).indexOf(store.language);
            txt(c, langIdx>=0?LANGUAGE_LABELS[langIdx]:"English", 34, centeredBaseline(347,18), 18, white, Paint.Align.LEFT);
            drawEditIcon(c, w-40, 347, 18, white);

            box(c,18,390,w-18,438,Color.rgb(30,16,16));
            strokeBox(c,18,390,w-18,438,red());
            txt(c,"Cancella tutti i dati",w/2,centeredBaseline(414,15),15,red(),Paint.Align.CENTER);

            txt(c,APP_VERSION,w/2,462,11,muted,Paint.Align.CENTER);

            lastContentBottom = 482;
            c.restore();
            finishScroll(); drawScrollbar(c,w);
        }

        void detailHeader(Canvas c, Season s, float w){
            float centerY=28; // alzato: il testo arrivava quasi a toccare l'inizio del contenuto scrollabile (bodyTop=44)
            // Chevron "indietro" e matita, stesso colore (bianco) per tutte le icone dell'header.
            drawChevronBack(c,24,centerY,20,white);
            txt(c,s.name,44,centeredBaseline(centerY,20),20,white,Paint.Align.LEFT);
            p.setTextSize(20); float nameW=p.measureText(s.name);
            drawEditIcon(c,44+nameW+22,centerY,18,white);
        }

        // Rettangolo con SOLO gli angoli superiori arrotondati (quelli inferiori restano squadrati, per
        // fondersi visivamente col corpo sottostante): usato per la fascia di intestazione delle sottocard
        // giorno, che prima aveva erroneamente tutti e 4 gli angoli arrotondati.
        // Icona "espandi/collassa" (freccia verso il basso se aperto, verso destra se chiuso), allineata a
        // sinistra nell'intestazione delle sottocard giorno.
        void boxTopRounded(Canvas c,float l,float t,float rr,float b,float radius,int col){
            p.setColor(col); p.setStyle(Paint.Style.FILL);
            android.graphics.Path path = new android.graphics.Path();
            float[] radii = {radius,radius, radius,radius, 0,0, 0,0}; // TL,TR,BR,BL
            path.addRoundRect(l,t,rr,b,radii, android.graphics.Path.Direction.CW);
            c.drawPath(path,p);
        }

        // ===== Tab "Gioca": due card vere e proprie (Punti attuali / Partite totali, non piu' annidate in
        // una card "Andamento Stagione" involucro) + card "Deck Selezionato" (con anteprima grande, stessa
        // dimensione di quella nel tab Deck) + pulsante W/L + Annulla + card "Partite" con i due tab. =====
        void playTab(Canvas c, Season s, float w, float h){
            matchHits.clear();
            ArrayList<Match> all = s.matches; // gia' flat, una entita' di primo livello nella Season
            int[] wl=countWL(all); int W=wl[0],L=wl[1];
            float wr=(W+L)==0?0:100f*W/(W+L);

            // ===== "PUNTI ATTUALI" / "PARTITE TOTALI": stessa altezza (80) delle equivalenti in Statistiche
            // — prima erano 92, causando un brutto salto di dimensione visibile cambiando tab. Titoli ora
            // centrati orizzontalmente, come in tutte le altre card. =====
            float c1L=18, c1R=w/2-6, c2L=w/2+6, c2R=w-18;
            box(c,c1L,58,c1R,138,card);
            txt(c,"PUNTI ATTUALI",(c1L+c1R)/2,80,12,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,centeredBaseline(108,22),22,white,Paint.Align.CENTER);
            box(c,c2L,58,c2R,138,card);
            txt(c,"PARTITE TOTALI",(c2L+c2R)/2,80,12,muted,Paint.Align.CENTER); // riga 1: SEMPRE qui, sia con 2 che con 3 righe — stessa posizione della card gemella "PUNTI ATTUALI"
            // Margine reale (non baseline grezzo) tra il top della card e il vero bordo visivo della riga 1
            // (con le metriche del font, non indovinato a mano) — rispecchiato esattamente sul bordo inferiore
            // della riga 3. La riga 2 va centrata esattamente nello spazio tra il fondo della riga 1 e la
            // cima della riga 3.
            p.setTextSize(12); Paint.FontMetrics fm1 = p.getFontMetrics();
            float row1VisualBottom = 80 + fm1.descent;
            float topGap = (80 + fm1.ascent) - 58;
            p.setTextSize(15); Paint.FontMetrics fm3 = p.getFontMetrics();
            float row3VisualBottom = 138 - topGap;
            float row3Baseline = row3VisualBottom - fm3.descent;
            float row3VisualTop = row3Baseline + fm3.ascent;
            float row2Baseline = centeredBaseline((row1VisualBottom+row3VisualTop)/2, 20);
            txt(c,""+(W+L),(c2L+c2R)/2,row2Baseline,20,white,Paint.Align.CENTER);
            txtRowCentered(c,(c2L+c2R)/2,row3Baseline,15,
                new String[]{W+"W  ", L+"L  ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});

            // ===== Card "DECK SELEZIONATO": anteprima (preimpostata o personalizzata) sopra, nome sotto.
            // Placeholder arcobaleno se nessun deck e' ancora selezionato. Il tap sull'anteprima ha un
            // comportamento SPECIALE gestito nel touch handler (Scegli anteprima / Visualizza Lista), quello
            // sul resto della card cambia ancora il deck (invariato). =====
            boolean noDeck = s.currentDeck==null || "Unknown".equals(s.currentDeck);
            box(c,18,152,w-18,302,card);
            txt(c, noDeck?"NESSUN DECK SELEZIONATO":"DECK SELEZIONATO", w/2,174,12,muted,Paint.Align.CENTER);
            Deck curDeckObjForMenu = noDeck ? null : findDeck(s, s.currentDeck);
            if(curDeckObjForMenu!=null){ drawKebabIcon(c, w-34, 174, muted); currentDeckKebabX=w-34; currentDeckKebabY=174; }
            else { currentDeckKebabX=-1000; currentDeckKebabY=-1000; } // nessun deck selezionato: nessun tap accidentale su una coordinata di un disegno precedente
            float thumbW=64, thumbH=80, thumbX=w/2-thumbW/2, thumbY=186;
            drawDeckPreview(c, curDeckObjForMenu, thumbX, thumbY, thumbX+thumbW, thumbY+thumbH);
            if(noDeck){
                txt(c,"Tocca per selezionare un deck",w/2,centeredBaseline(284,13),13,muted,Paint.Align.CENTER);
            } else {
                txt(c,s.currentDeck,w/2,centeredBaseline(284,18),18,white,Paint.Align.CENTER);
            }

            boolean locked = isSeasonLocked(store.current);
            // ===== Pulsanti W/L (registrano la partita col deck selezionato sopra), o messaggio di chiusura
            // se la Stagione e' bloccata (solo l'ultima creata resta giocabile). Le correzioni manuali
            // restano SEMPRE permesse anche a Stagione bloccata (servono ad allineare i conti anche a
            // posteriori); e' solo la registrazione di nuove PARTITE a essere bloccata. Anche il badge
            // "Annulla" resta sempre attivo per lo stesso motivo (potresti voler annullare una correzione
            // appena aggiunta a una Stagione chiusa). =====
            float gL=18, gR=w/2-8, rL=w/2+8, rR=w-18;
            if(locked){
                box(c,18,322,w-18,386,card);
                txt(c,"Questa stagione è terminata.",w/2,centeredBaseline(354,15),15,white,Paint.Align.CENTER);
            } else {
                box(c,gL,322,gR,386,green); box(c,rL,322,rR,386,red);
                float[] wl2 = centerLines(354,6,22,13);
                txt(c,"W",(gL+gR)/2,wl2[0],22,Color.WHITE,Paint.Align.CENTER); txt(c,"(+"+reward(s.streak+1)+")",(gL+gR)/2,wl2[1],13,Color.WHITE,Paint.Align.CENTER);
                txt(c,"L",(rL+rR)/2,wl2[0],22,Color.WHITE,Paint.Align.CENTER); txt(c,"(−10)",(rL+rR)/2,wl2[1],13,Color.WHITE,Paint.Align.CENTER);
            }

            // Il badge "Annulla" non c'e' piu' su una Stagione bloccata: ci ho ripensato, se serve correggere
            // qualcosa su una Stagione terminata si usano correzioni manuali, non l'annullamento.
            boolean hasHistory = !all.isEmpty() && !locked;
            boolean lastIsCorrection = hasHistory && all.get(all.size()-1).unknown;
            // Inset=8 verificato esplicitamente: sopra "L" il bordo destro del badge arriva a w-12, la
            // scrollbar principale della pagina inizia a w-7 → 5 unita' di margine, non la tocca. Con
            // l'inset precedente (12.5) la sporgenza reale era di appena 1.5 unita' — quasi invisibile;
            // con 8 sporge di 6 unita' sopra E a destra del pulsante, chiaramente visibile.
            float badgeR = 14, cornerInset = 8; // ancorato all'angolo in alto a DESTRA del pulsante giusto,
            // non centrato sopra: cosi' non copre piu' la lettera "W"/"L", e restando "dentro" il pulsante
            // (non a cavallo) non rischia di sovrapporsi all'altro pulsante o di uscire dallo schermo.
            undoBadgeCy = 322+cornerInset;
            if(!hasHistory || lastIsCorrection) { undoBadgeCx = w/2; }
            else { undoBadgeCx = (all.get(all.size()-1).win ? gR : rR) - cornerInset; }
            if(hasHistory){
                p.setColor(Color.rgb(20,32,52)); p.setStyle(Paint.Style.FILL);
                c.drawCircle(undoBadgeCx, undoBadgeCy, badgeR, p);
                p.setColor(bg); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
                c.drawCircle(undoBadgeCx, undoBadgeCy, badgeR, p);
                drawUndoIcon(c, undoBadgeCx, undoBadgeCy, 14, white);
            }


            // ===== Card "PARTITE": due tab al suo interno — Grafico e Lista — altezza FISSA condivisa. =====
            float listCardTop=400, contentHeight=300;
            float contentTop=listCardTop+42+8, contentBottom=contentTop+contentHeight; // +8: margine sotto la fascia icone, prima assente
            float listCardBottom = contentBottom+14;
            box(c,18,listCardTop,w-18,listCardBottom,card);
            // Fascia di sfondo distinta per l'header (icone tab): prima le icone "fluttuavano" senza alcuna
            // demarcazione, confondendosi visivamente col resto della card. Angoli arrotondati solo in alto,
            // dato che tocca il bordo superiore della card stessa.
            boxTopRounded(c,18,listCardTop,w-18,listCardTop+42,18,Color.rgb(21,34,56));
            // Niente piu' scritta "PARTITE": i 2 tab (grafico/lista) sono centrati nell'header, l'icona
            // "modifica" (per aggiungere una correzione manuale) allineata a destra e visibile solo nel tab
            // Lista — colore neutro (non blu, per non sembrare un terzo tab che appare/scompare).
            float tabIconY = listCardTop+21; // vero centro della fascia (42 di altezza): prima era +26, 5 unita' piu' in basso del centro reale
            drawMiniChartTabIcon(c, w/2-23, tabIconY, 28, partiteTab==0?blue:muted);
            drawListTabIcon(c, w/2+23, tabIconY, 28, partiteTab==1?blue:muted);
            // Allineato al margine destro reale della card (w-18, la stessa convenzione usata da ogni altra
            // card dell'app), non piu' un offset indovinato a mano che ogni volta finiva storto quando la
            // dimensione dell'icona cambiava.
            if(partiteTab==1) drawEditIcon(c, w/2+90, tabIconY, 19, muted); // sempre attiva, correzioni permesse anche a Stagione bloccata

            if(partiteTab==0){
                // Pillole di selezione intervallo, sopra il grafico (1 giorno/3 giorni/tutto — una Stagione
                // dura in genere circa 2 settimane, "7g/30g" da app di finanza non avevano senso qui).
                String[] rangeLabels = {"1 giorno","3 giorni","Tutto"};
                float pillY=contentTop+16, pillH=26;
                rangePillsTop = pillY-pillH/2; rangePillsBottom = pillY+pillH/2;
                float pillGap=8; float pillX=30;
                rangePillBounds = new ArrayList<>();
                for(int ri=0; ri<3; ri++){
                    p.setTextSize(11); float tw=p.measureText(rangeLabels[ri]); float pw=tw+24;
                    rangePillBounds.add(new float[]{pillX,pw});
                    box(c,pillX,pillY-pillH/2,pillX+pw,pillY+pillH/2, ri==chartRange?blue:Color.rgb(10,18,30));
                    txt(c,rangeLabels[ri],pillX+pw/2,centeredBaseline(pillY,11),11, ri==chartRange?Color.WHITE:muted, Paint.Align.CENTER);
                    pillX += pw+pillGap;
                }
                float chartTop = contentTop+40;

                // Tab Grafico: il punteggio iniziale (correzione in posizione 0, se presente) non ha senso
                // qui — e' un dato di partenza, non un evento nel tempo. Le colonne verticali segnano un
                // cambio di GIORNO o di DECK.
                ArrayList<Match> chartMatches = all;
                if(!all.isEmpty() && all.get(0).unknown) chartMatches = new ArrayList<>(all.subList(1, all.size()));
                if(chartRange==0){ long cutoff=midnightNDaysAgo(0); ArrayList<Match> f=new ArrayList<>(); for(Match m: chartMatches) if(m.timestamp>=cutoff) f.add(m); chartMatches=f; }
                else if(chartRange==1){ long cutoff=midnightNDaysAgo(2); ArrayList<Match> f=new ArrayList<>(); for(Match m: chartMatches) if(m.timestamp>=cutoff) f.add(m); chartMatches=f; }
                ArrayList<Integer> dayBoundaries = new ArrayList<>();
                String prevDay=null;
                for(int idx=0;idx<chartMatches.size();idx++){
                    String dk = dayKey(chartMatches.get(idx).timestamp);
                    if(prevDay!=null && !dk.equals(prevDay)) dayBoundaries.add(idx);
                    prevDay=dk;
                }
                drawChart(c,30,chartTop,w-30,contentBottom,chartMatches,0,dayBoundaries);
                matchInnerMaxScrollY=0; // niente scroll interno nel tab Grafico
            } else {
                // Tab Lista: raggruppata per GIORNO, indipendentemente dal tipo (partita o correzione): una
                // correzione "vive" comunque sotto la data a cui appartiene, con uno stile di riga diverso
                // (una sua piccola sottocard interna, invece di una riga piatta).
                float headerH=32, matchRowH=64, corrRowH=64, groupGap=10;
                // Numerazione "Partita N": conta solo le partite VERE (esclude le correzioni), cosi' la
                // primissima partita vera e' sempre "Partita 1".
                int[] matchNumber = new int[all.size()];
                { int cnt=0; for(int idx=0; idx<all.size(); idx++){ if(!all.get(idx).unknown){ cnt++; matchNumber[idx]=cnt; } } }

                // Serie di partite consecutive con lo STESSO deck, calcolate sull'INTERA cronologia (non per
                // singolo giorno): cosi' una serie che continua da un giorno al successivo resta un'unica
                // serie con un solo colore, invece di "spezzarsi" e ripartire dal colore 1 a mezzanotte. Le
                // correzioni sono "trasparenti", non interrompono la serie. Ogni NUOVA serie incontrata (in
                // ordine cronologico) prende il colore successivo della palette, ciclicamente.
                int[] streakColorAt = new int[all.size()];
                int[] streakSizeAt = new int[all.size()];
                boolean[] isTopOfStreak = new boolean[all.size()];
                {
                    ArrayList<Integer> realOrder = new ArrayList<>();
                    for(int idx=0; idx<all.size(); idx++) if(!all.get(idx).unknown) realOrder.add(idx);
                    int streakN=0, ri=0;
                    while(ri<realOrder.size()){
                        int start=ri; String deckAtStart=all.get(realOrder.get(ri)).deck;
                        while(ri<realOrder.size() && deckAtStart.equals(all.get(realOrder.get(ri)).deck)) ri++;
                        int size=ri-start; int color=DECK_ACCENT_COLORS[streakN % DECK_ACCENT_COLORS.length];
                        for(int q=start;q<ri;q++){
                            int idx=realOrder.get(q);
                            streakColorAt[idx]=color; streakSizeAt[idx]=size;
                            isTopOfStreak[idx] = (q==ri-1); // l'ultimo in ordine CRONOLOGICO = il piu' recente (in cima nella visualizzazione)
                        }
                        streakN++;
                    }
                }

                // ===== Barra "salta al giorno": una pillola per ogni data distinta con almeno una voce,
                // ordine ASCENDENTE (piu' vecchia a sinistra, oggi a destra) — di default scrollata tutta a
                // destra, cosi' "oggi" e' subito visibile senza dover scorrere. Scroll orizzontale proprio,
                // indipendente da quello verticale della lista sotto.
                //
                // La pillola evidenziata NON e' piu' "quella toccata per ultima": segue lo scroll verticale
                // della lista in tempo reale (se scorri manualmente su una partita di un altro giorno, la
                // pillola di quel giorno si accende da sola). Toccare una pillola resta un modo per saltare
                // subito a quel giorno — dopodiche' e' di nuovo lo scroll a decidere quale sia evidenziata. =====
                float pillBandH=34, pillPadH=22, pillPadX=11, pillGap=6, pillLeftMargin=dateBarPillLeftMargin, pillRightMargin=10;
                float bandBottomMargin=8; // margine visibile tra la fascia e la lista sotto
                float dateBarH = pillBandH + bandBottomMargin;
                float listTop = contentTop+dateBarH, listHeight = contentHeight-dateBarH;

                // Calcolo di altezze/offset per giorno PRIMA di disegnare la barra (serve a sapere, dato lo
                // scroll attuale, quale giorno e' "in cima" alla vista — e quindi quale pillola accendere).
                float headerH2=headerH, matchRowH2=matchRowH, corrRowH2=corrRowH, groupGap2=groupGap; // copie per la lambda (headerH ecc. sono gia' effettivamente final, ma teniamo nomi distinti per chiarezza)
                java.util.function.IntToDoubleFunction rowHeightAt = idx -> all.get(idx).unknown ? corrRowH2 : matchRowH2;
                dayYOffsetMap = new java.util.HashMap<>();
                float totalRowsHeight = 0;
                {
                    int idx=all.size()-1;
                    while(idx>=0){
                        String dk=dayKey(all.get(idx).timestamp);
                        dayYOffsetMap.put(dk, totalRowsHeight); // offset ALL'INIZIO di questo gruppo
                        int j=idx; float groupRowsH=0;
                        while(j>=0 && dayKey(all.get(j).timestamp).equals(dk)){ groupRowsH+=(float)rowHeightAt.applyAsDouble(j); j--; }
                        totalRowsHeight += headerH2 + groupRowsH + groupGap2;
                        idx=j;
                    }
                }
                resetMatchInnerScrollIfNeeded("matchinner:"+store.current);
                matchInnerMaxScrollY = Math.max(0, totalRowsHeight-listHeight);
                if(matchInnerScrollY>matchInnerMaxScrollY) matchInnerScrollY=matchInnerMaxScrollY;
                if(matchInnerScrollY<0) matchInnerScrollY=0;
                matchInnerListTop=listTop; matchInnerListBottom=listTop+listHeight;

                String mostRecentDayKey = all.isEmpty() ? null : dayKey(all.get(all.size()-1).timestamp);
                // Giorno "in cima" alla vista attuale: quello con l'offset piu' grande che sia ancora <= allo
                // scroll corrente (lo stesso principio di una qualunque lista con intestazioni sticky).
                String currentVisibleDay = mostRecentDayKey;
                { float bestOffset=-1; for(java.util.Map.Entry<String,Float> en: dayYOffsetMap.entrySet()){ if(en.getValue()<=matchInnerScrollY+0.5f && en.getValue()>bestOffset){ bestOffset=en.getValue(); currentVisibleDay=en.getKey(); } } }

                ArrayList<String> distinctDays = new ArrayList<>();
                { String prevDk=null; for(int idx=0; idx<all.size(); idx++){ String dk=dayKey(all.get(idx).timestamp); if(!dk.equals(prevDk)){ distinctDays.add(dk); prevDk=dk; } } }
                dateBarDayKeys = distinctDays; dateBarPillBounds = new ArrayList<>();
                float pillCursor=0;
                for(String dk: distinctDays){
                    String label = dk.equals("?") ? "?" : dk.substring(6,8)+"/"+dk.substring(4,6);
                    p.setTextSize(11); float tw=p.measureText(label); float pw=tw+pillPadX*2;
                    dateBarPillBounds.add(new float[]{pillCursor,pw});
                    pillCursor += pw+pillGap;
                }
                float totalPillsWidth = Math.max(0, pillCursor-pillGap);
                float dateBarVisibleW = w-36-pillLeftMargin-pillRightMargin;
                resetDateBarIfNeeded("datebar:"+store.current, totalPillsWidth, dateBarVisibleW);
                float bandTop = contentTop, bandBottom = contentTop+pillBandH;
                dateBarTop = bandTop + (pillBandH-pillPadH)/2; dateBarBottom = dateBarTop+pillPadH;

                // Sfondo distinto per l'intera riga della barra date (altrimenti si confondeva col resto):
                // un rettangolo pieno, niente angoli arrotondati — non e' la prima ne' l'ultima sezione
                // della card, sta in mezzo tra le icone tab e la lista. Occupa ESATTAMENTE [bandTop,
                // bandBottom], senza margini negativi che la facevano sovrapporre alla fascia sopra.
                p.setColor(Color.rgb(15,25,40)); p.setStyle(Paint.Style.FILL);
                c.drawRect(18,bandTop,w-18,bandBottom,p);
                c.save(); c.clipRect(18,dateBarTop,w-18,dateBarBottom);
                c.translate(18+pillLeftMargin-dateBarScrollX, 0);
                for(int di=0; di<distinctDays.size(); di++){
                    String dk = distinctDays.get(di);
                    float[] b = dateBarPillBounds.get(di);
                    boolean isSelected = dk.equals(currentVisibleDay);
                    String label = dk.equals("?") ? "?" : dk.substring(6,8)+"/"+dk.substring(4,6);
                    box(c, b[0], dateBarTop, b[0]+b[1], dateBarTop+pillPadH, isSelected?blue:Color.rgb(10,18,30));
                    txt(c, label, b[0]+b[1]/2, centeredBaseline(dateBarTop+pillPadH/2f,11), 11, isSelected?Color.WHITE:muted, Paint.Align.CENTER);
                }
                c.restore();
                // Sfumature ai due bordi della barra, sullo stesso sfondo della fascia (non su una singola
                // pillola): a destra se c'e' altro da scorrere verso il futuro, a sinistra se c'e' altro da
                // scorrere verso il passato.
                if(dateBarScrollX < dateBarMaxScrollX-1){
                    android.graphics.LinearGradient grad = new android.graphics.LinearGradient(w-42,0,w-18,0, Color.argb(0,15,25,40), Color.argb(255,15,25,40), android.graphics.Shader.TileMode.CLAMP);
                    p.setShader(grad); p.setStyle(Paint.Style.FILL);
                    c.drawRect(w-42,dateBarTop,w-18,dateBarTop+pillPadH,p);
                    p.setShader(null);
                }
                if(dateBarScrollX > 1){
                    android.graphics.LinearGradient gradLeft = new android.graphics.LinearGradient(18,0,42,0, Color.argb(255,15,25,40), Color.argb(0,15,25,40), android.graphics.Shader.TileMode.CLAMP);
                    p.setShader(gradLeft); p.setStyle(Paint.Style.FILL);
                    c.drawRect(18,dateBarTop,42,dateBarTop+pillPadH,p);
                    p.setShader(null);
                }

                c.save(); c.clipRect(18,listTop,w-18,listTop+listHeight); c.translate(0,-matchInnerScrollY);
                float y=listTop+4;
                int i = all.size()-1;
                while(i>=0){
                    String dk = dayKey(all.get(i).timestamp);
                    int dayEndIdx = i;
                    int j = i;
                    while(j>=0 && dayKey(all.get(j).timestamp).equals(dk)) j--;
                    int dayStartIdx = j+1;
                    int dw=0, dl=0; int dgain=0;
                    for(int k=dayStartIdx;k<=dayEndIdx;k++){ Match m=all.get(k); if(!m.unknown){ if(m.win) dw++; else dl++; } dgain += (m.after-m.before); }
                    float dwr = (dw+dl)==0?0:100f*dw/(dw+dl);
                    float groupRowsH=0; for(int k=dayStartIdx;k<=dayEndIdx;k++) groupRowsH+=(float)rowHeightAt.applyAsDouble(k);
                    float groupTop=y, groupBottom=y+headerH+groupRowsH;

                    box(c,30,groupTop,w-30,groupBottom,Color.rgb(10,18,30));
                    boxTopRounded(c,30,groupTop,w-30,groupTop+headerH,10,Color.rgb(21,34,56));
                    txt(c, formatDateOnly(all.get(dayEndIdx).timestamp), 46, centeredBaseline(groupTop+20,11), 11, muted, Paint.Align.LEFT);
                    txtRowRight(c,w-46,centeredBaseline(groupTop+20,11),11,
                        new String[]{dw+"W  ", dl+"L  ", String.format(Locale.US,"%.1f%%",dwr)+"  ", (dgain>0?"+":"")+dgain},
                        new int[]{green, red, wrColor(dwr,dw+dl), dgain>0?green:(dgain<0?red:muted)});

                    float ry = groupTop+headerH;
                    for(int k=dayEndIdx;k>=dayStartIdx;k--){
                        Match m = all.get(k);
                        if(m.unknown){
                            String title = (k==0) ? "Punti di partenza" : "Correzione manuale";
                            box(c,38,ry+4,w-38,ry+corrRowH-4,Color.rgb(20,32,52));
                            txt(c, title, 50, ry+26, 15, white, Paint.Align.LEFT);
                            // Seconda riga: TUTTE le variazioni insieme (punti, vittorie consecutive, W, L)
                            // — e' una card diversa dalle partite, non deve seguire lo stesso schema
                            // "titolo a sinistra, valore isolato a destra".
                            String pointsStr; int pointsColor;
                            if(k==0){ pointsStr = m.after+" punti"; pointsColor = white; }
                            else {
                                int gain = m.after-m.before;
                                pointsStr = (gain>=0?"+":"")+gain+" punti";
                                pointsColor = gain>0?green:(gain<0?red:muted);
                            }
                            txtRow(c, 50, ry+46, 12,
                                new String[]{pointsStr+"   ", m.streak+"VC   ", "+"+m.correctionWins+"W  ", "+"+m.correctionLosses+"L"},
                                new int[]{pointsColor, muted, green, red});
                        } else {
                            if(k!=dayEndIdx){ p.setColor(Color.rgb(20,30,46)); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE); c.drawLine(46,ry,w-46,ry,p); }
                            // Barra colorata a sinistra per le serie di partite consecutive con lo stesso
                            // deck (le correzioni non le interrompono); "×N" solo sulla partita piu' recente
                            // della serie (quella in alto).
                            if(streakSizeAt[k]>=2){
                                p.setColor(streakColorAt[k]); p.setStyle(Paint.Style.FILL);
                                c.drawRect(30,ry,34,ry+matchRowH,p);
                            }
                            if(streakSizeAt[k]>=2 && isTopOfStreak[k]){
                                txtRow(c, 46, ry+26, 15, new String[]{deckDisplayShort(m.deck), "  ×"+streakSizeAt[k]}, new int[]{white, streakColorAt[k]});
                            } else {
                                txt(c, deckDisplayShort(m.deck), 46,ry+26,15,white,Paint.Align.LEFT);
                            }
                            txt(c, "Partita "+matchNumber[k]+"  •  "+formatTimeOnly(m.timestamp), 46,ry+48,12,muted,Paint.Align.LEFT);
                            txt(c, m.win?"W":"L", w-46, ry+26, 15, m.win?green:red, Paint.Align.RIGHT);
                            int gain = m.after-m.before;
                            int gcol = gain>0?green:(gain<0?red:muted);
                            txt(c, (gain>0?"+":"")+gain, w-46, ry+48, 12, gcol, Paint.Align.RIGHT);
                        }
                        matchHits.add(new Hit(ry,ry+(float)rowHeightAt.applyAsDouble(k),k));
                        ry+=(float)rowHeightAt.applyAsDouble(k);
                    }
                    y = groupBottom+groupGap;
                    i = dayStartIdx-1;
                }
                c.restore();

                if(matchInnerMaxScrollY>1){
                    float matchThumbH = Math.max(24, listHeight*(listHeight/totalRowsHeight));
                    float matchThumbY = listTop + (listHeight-matchThumbH)*(matchInnerScrollY/matchInnerMaxScrollY);
                    p.setColor(Color.rgb(45,60,85)); p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(w-22,matchThumbY,w-19,matchThumbY+matchThumbH,1.5f,1.5f,p);
                }
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
        // Disegno "puro" della card deck: nessun effetto collaterale (non registra alcuna zona di tocco) —
        // riusabile sia dal tab Deck (che poi registra le sue zone) sia dal nuovo dialog "Cambia deck" (che
        // gestisce le sue zone in modo indipendente, senza sporcare lo stato del tab Deck vero).
        float deckCardVisual(Canvas c, Deck deckObj, String name, boolean isUnknown, int W, int L, int best, int gain, float y, float w, boolean showKebab){
            // Margine ridotto da 30 a 18: prima le card erano piu' strette del pulsante "Nuovo Deck" (margine
            // 18) sotto — ora coincidono. Le posizioni interne (anteprima, testo, kebab) sono traslate di
            // conseguenza (-12) per mantenere gli stessi spazi relativi rispetto al nuovo bordo.
            box(c,18,y,w-18,y+92,Color.rgb(10,18,30));
            float textX = 34;
            if (!isUnknown) {
                float thumbW=64, thumbH=80, thumbX=28, thumbY=y+6;
                drawDeckPreview(c, deckObj, thumbX, thumbY, thumbX+thumbW, thumbY+thumbH);
                textX = thumbX+thumbW+14;
            }
            txt(c, isUnknown?"Deck sconosciuto":name, textX,y+26,17, isUnknown?muted:white, Paint.Align.LEFT);
            float wr=(W+L)==0?0:100f*W/(W+L);
            txt(c,(W+L)+" partite",textX,y+46,12, isUnknown?muted:white, Paint.Align.LEFT);
            if (!isUnknown && showKebab) {
                drawKebabIcon(c, w-18-10-8, y+22, muted);
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

        // Wrapper usato dal tab Deck vero: disegna la card E registra la zona di tocco dell'anteprima nella
        // lista condivisa che il touch handler del tab Deck legge — separato dal disegno puro sopra, cosi'
        // il nuovo dialog "Cambia deck" (che chiama solo deckCardVisual) non la sporca con le sue posizioni.
        float deckCard(Canvas c, Deck deckObj, String name, boolean isUnknown, int W, int L, int best, int gain, float y, float w, boolean showDelete){
            float result = deckCardVisual(c, deckObj, name, isUnknown, W, L, best, gain, y, w, showDelete);
            if (!isUnknown) {
                float thumbW=64, thumbH=80, thumbX=40, thumbY=y+6;
                deckPreviewTapZones.add(new Object[]{thumbX, thumbY, thumbX+thumbW, thumbY+thumbH, deckObj});
            }
            return result;
        }

        void decks(Canvas c,Season s,float w,float h){
            deckPreviewTapZones.clear();
            // Pillola "Ordina" rimossa del tutto: con la barra di ricerca ora sempre visibile (non piu' un
            // pulsante che si espande) non c'e' piu' spazio per farla convivere senza dare fastidio.
            // Stesso colore/bordo del pulsante "Nuovo Deck" nel dialog "Seleziona un deck" (styleSecondaryButton),
            // al posto del riempimento blu pieno di prima, per coerenza visiva tra i due.
            // Margine aumentato dalla barra di ricerca sopra (che finisce a y=88): prima "Nuovo Deck"
            // iniziava a y=90, appena 2 unita' dopo, sembravano attaccati.
            box(c,18,100,w-18,148,Color.rgb(20,32,48));
            strokeBox(c,18,100,w-18,148,FIELD_BORDER);
            txt(c,"Nuovo Deck",w/2,127,14,white,Paint.Align.CENTER);
            float y=162;
            String q = deckSearchQuery==null ? "" : deckSearchQuery.trim().toLowerCase(Locale.ITALY);
            for(Deck d: sortedDecks(s)){
                if (!q.isEmpty() && !d.name.toLowerCase(Locale.ITALY).contains(q)) continue;
                int[] wl=deckWL(s,d.name); int best=longestStreakForDeck(s,d.name); int gain=deckGain(s,d.name);
                y = deckCard(c, d, d.name, false, wl[0], wl[1], best, gain, y, w, true);
            }
            if (q.isEmpty()) {
                int[] nd = noDeckWL(s);
                if (nd[0]+nd[1] > 0) {
                    int ndbest=longestStreakForDeck(s,"Unknown"); int ndgain=deckGain(s,"Unknown");
                    y = deckCard(c, null, null, true, nd[0], nd[1], ndbest, ndgain, y, w, true);
                }
            }
            lastContentBottom = y+20;
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

            // Niente piu' card contenitore "STATISTICHE STAGIONE": ogni riga e' ora una coppia di card vere e
            // proprie (sfondo "card", titolo in alto a sinistra), stesso stile usato nel tab Gioca per Punti
            // attuali/Partite totali. La sezione "DECK" (con le sotto-card per singolo deck) resta rimossa:
            // era una pura ripetizione del tab Deck.
            float c1L=18, c1R=w/2-6, c2L=w/2+6, c2R=w-18;

            // Ogni riga ridotta a 80 di altezza (era 100, sproporzionata per una sola riga di contenuto sotto
            // il titolo): stesso schema label(top+22)/contenuto centrato usato nel tab Gioca.
            box(c,c1L,58,c1R,138,card);
            txt(c,"PUNTI ATTUALI",(c1L+c1R)/2,80,12,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,centeredBaseline(108,22),22,white,Paint.Align.CENTER);
            box(c,c2L,58,c2R,138,card);
            txt(c,"VARIAZIONE",(c2L+c2R)/2,80,12,muted,Paint.Align.CENTER);
            txt(c, (gain>0?"+":"")+gain,(c2L+c2R)/2,centeredBaseline(108,22),22, gain>0?green:(gain<0?red:white),Paint.Align.CENTER);

            box(c,c1L,152,c1R,232,card);
            txt(c,"PARTITE TOTALI",(c1L+c1R)/2,174,12,muted,Paint.Align.CENTER);
            txt(c,""+(W+L),(c1L+c1R)/2,centeredBaseline(202,20),20,white,Paint.Align.CENTER);
            box(c,c2L,152,c2R,232,card);
            txt(c,"W / L / %",(c2L+c2R)/2,174,12,muted,Paint.Align.CENTER);
            txtRowCentered(c,(c2L+c2R)/2,centeredBaseline(202,15),15,
                new String[]{W+"W  ", L+"L  ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});

            box(c,c1L,246,c1R,326,card);
            txt(c,"VITTORIE CONSECUTIVE",(c1L+c1R)/2,268,12,muted,Paint.Align.CENTER);
            txt(c,""+s.streak,(c1L+c1R)/2,centeredBaseline(296,22),22,white,Paint.Align.CENTER);
            box(c,c2L,246,c2R,326,card);
            txt(c,"MASSIME",(c2L+c2R)/2,268,12,muted,Paint.Align.CENTER);
            txt(c,""+maxStreak,(c2L+c2R)/2,centeredBaseline(296,22),22,white,Paint.Align.CENTER);

            String mostPlayedName = "-"; int mostPlayedCount = 0;
            for(Deck d: sd){
                int[] dwl = deckWL(s,d.name); int total=dwl[0]+dwl[1];
                if(total>mostPlayedCount){ mostPlayedCount=total; mostPlayedName=d.name; }
            }
            if(nd[0]+nd[1]>mostPlayedCount){ mostPlayedCount=nd[0]+nd[1]; mostPlayedName="Deck sconosciuto"; }
            box(c,c1L,340,c1R,420,card);
            txt(c,"DECK GIOCATI",(c1L+c1R)/2,362,12,muted,Paint.Align.CENTER);
            txt(c,""+deckPlayedCount,(c1L+c1R)/2,centeredBaseline(390,16),16,white,Paint.Align.CENTER);
            box(c,c2L,340,c2R,420,card);
            txt(c,"DECK PIU' GIOCATO",(c2L+c2R)/2,362,12,muted,Paint.Align.CENTER);
            txt(c,mostPlayedName,(c2L+c2R)/2,centeredBaseline(390,16),16,white,Paint.Align.CENTER);

            lastContentBottom = 420+20;
        }

        void drawChart(Canvas c,float l,float t,float rr,float b,List<Match> ms,long unusedTimestamp,List<Integer> dayBoundaries){
            box(c,l,t,rr,b,Color.rgb(10,18,30));
            // Zone dedicate: header per il testo "Punti iniziali/attuali" (prima si sovrapponeva quasi
            // esattamente alla prima riga della griglia), corpo per griglia+grafico, footer per le date
            // sull'asse x.
            float headerZoneH=44, footerZoneH=18;
            float gridTop=t+headerZoneH, gridBottom=b-footerZoneH;
            int gridColor = Color.rgb(26,38,56);
            p.setColor(gridColor); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE);
            int gridLines=3;
            for(int i=1;i<=gridLines;i++){
                float gy = gridTop + i*(gridBottom-gridTop)/(gridLines+1);
                c.drawLine(l+8,gy,rr-8,gy,p);
            }
            txt(c,"Punti iniziali: "+(ms.isEmpty()?0:ms.get(0).before),l+10,t+16,12,white,Paint.Align.LEFT);
            txt(c,"Punti attuali: "+(ms.isEmpty()?0:ms.get(ms.size()-1).after),l+10,t+34,12,white,Paint.Align.LEFT);
            if(ms.isEmpty()){
                txt(c,"Nessuna partita ancora",(l+rr)/2,(gridTop+gridBottom)/2,13,muted,Paint.Align.CENTER);
                return;
            }
            float min=ms.get(0).before,max=ms.get(0).before;for(Match m:ms){min=Math.min(min,m.after);max=Math.max(max,m.after);}
            min-=20;max+=20;if(max==min)max=min+1;
            int n=ms.size();

            // Colonne verticali per cambio GIORNO (una tonalita') e per cambio DECK (un'altra), cosi' si
            // distinguono a colpo d'occhio i due tipi di "confine" nel grafico.
            if(dayBoundaries!=null && !dayBoundaries.isEmpty()){
                p.setColor(Color.rgb(52,68,96)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
                for(int idx: dayBoundaries){
                    float gx = l+12+(idx+1)*(rr-l-24)/Math.max(1,n);
                    c.drawLine(gx,gridTop,gx,gridBottom,p);
                }
                for(int idx: dayBoundaries){
                    float gx = l+12+(idx+1)*(rr-l-24)/Math.max(1,n);
                    txt(c, formatDateOnly(ms.get(idx).timestamp), gx, gridBottom+13, 9, muted, Paint.Align.CENTER);
                }
            }
            // Cambio DECK: la linea segna la posizione dell'ULTIMA partita del deck che finisce, non la
            // prima del deck che inizia — cosi' se il cambio capita a essere l'ultimissima partita in
            // assoluto, la linea non finisce incollata al bordo destro del grafico (dove poteva sembrare una
            // linea finale sempre presente), ma resta un passo a sinistra, sulla vera partita di confine.
            {
                p.setColor(Color.rgb(120,90,190)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
                String prevDeck = null;
                for(int idx=0; idx<n; idx++){
                    Match m = ms.get(idx);
                    if(m.unknown) continue;
                    if(prevDeck!=null && !prevDeck.equals(m.deck)){
                        float gx = l+12+idx*(rr-l-24)/Math.max(1,n);
                        c.drawLine(gx,gridTop,gx,gridBottom,p);
                    }
                    prevDeck = m.deck;
                }
            }

            p.setStrokeWidth(2);
            float prevX=l+12, prevY=gridBottom-8-(ms.get(0).before-min)/(max-min)*(gridBottom-gridTop-16);
            float startX=prevX, startY=prevY;
            for(int i=0;i<ms.size();i++){
                Match m=ms.get(i);float x=l+12+(i+1)*(rr-l-24)/Math.max(1,ms.size());
                float y=gridBottom-8-(m.after-min)/(max-min)*(gridBottom-gridTop-16);
                p.setColor(m.unknown?Color.GRAY:(m.win?green:red));p.setStyle(Paint.Style.STROKE);c.drawLine(prevX,prevY,x,y,p);prevX=x;prevY=y;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(muted); c.drawCircle(startX,startY,4,p);
            for(int i=0;i<ms.size();i++){
                Match m=ms.get(i);float x=l+12+(i+1)*(rr-l-24)/Math.max(1,ms.size());
                float y=gridBottom-8-(m.after-min)/(max-min)*(gridBottom-gridTop-16);
                p.setColor(m.unknown?Color.GRAY:(m.win?green:red));
                c.drawCircle(x,y,4,p);
            }
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

        // Icona "grafico" per il selettore di tab dentro la card "PARTITE": una piccola spezzata con pallini
        // sui punti, come uno sparkline in miniatura.
        void drawMiniChartTabIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            float[][] pts = {{-8,4},{-3,-2},{2,2},{8,-6}};
            android.graphics.Path path = new android.graphics.Path();
            for(int i=0;i<pts.length;i++){
                float px=cx+pts[i][0]*s, py=cy+pts[i][1]*s;
                if(i==0) path.moveTo(px,py); else path.lineTo(px,py);
            }
            c.drawPath(path,p);
            p.setStyle(Paint.Style.FILL);
            for(float[] pt: pts) c.drawCircle(cx+pt[0]*s, cy+pt[1]*s, 2*s, p);
        }
        // Icona "lista" per il selettore di tab: tre righe con un pallino a sinistra di ciascuna.
        void drawListTabIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            float[] widths = {14,10,12};
            for(int i=0;i<3;i++){
                float yy = cy + (i-1)*7*s;
                c.drawCircle(cx-10*s, yy, 1.6f*s, p);
                c.drawRoundRect(cx-6*s, yy-1.2f*s, cx-6*s+widths[i]*s, yy+1.2f*s, 1*s,1*s, p);
            }
        }

        void detailNav(Canvas c,float w,float h){
            float y=h-58; p.setColor(Color.rgb(9,15,25));p.setStyle(Paint.Style.FILL);c.drawRect(0,y,w,h,p);
            String[] n={"Gioca","Deck","Stats"};
            for(int i=0;i<3;i++){
                int col=i==detailTab?blue:muted;
                float cx=w*(i+.5f)/3, iconCy=y+18;
                if(i==0) drawBurstTabIcon(c,cx,iconCy,0.95f,col);
                else if(i==1) drawCardTabIcon(c,cx,iconCy,20,col);
                else drawStatsTabIcon(c,cx,iconCy,20,col);
                txt(c,n[i],cx,y+48,12,col,Paint.Align.CENTER);
            }
        }

        float touchDownX=0, touchDownY=0, touchStartScrollY=0, touchStartInnerScrollY=0; boolean isDragging=false, isDraggingInner=false;
        int innerDragTarget=0; // 0=nessuno, 1=lista partite (tab Gioca)

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            // Le coordinate del tocco arrivano in pixel reali dell'intera View: le convertiamo nello stesso
            // sistema "dp con origine sotto la status bar" usato in onDraw, altrimenti i tap non
            // corrisponderebbero piu' a quello che e' disegnato sullo schermo.
            float x=(e.getX()-getPaddingLeft())/density, y=(e.getY()-getPaddingTop())/density;
            float w=(getWidth()-getPaddingLeft()-getPaddingRight())/density;
            float h=(getHeight()-getPaddingTop()-getPaddingBottom())/density;

            if(e.getAction()==MotionEvent.ACTION_DOWN){
                touchDownX=x; touchDownY=y; touchStartScrollY=scrollY;
                isDragging=false; isDraggingInner=false; innerDragTarget=0; isDraggingDateBar=false;
                boolean overDateBar = screen==SCREEN_SEASON_DETAIL && detailTab==0 && partiteTab==1 && dateBarMaxScrollX>0
                    && y>=(dateBarTop-scrollY) && y<=(dateBarBottom-scrollY) && x>=18 && x<=w-18;
                dateBarDragCandidate = overDateBar;
                if(overDateBar){ touchStartDateBarScrollX = dateBarScrollX; }
                boolean overMatches = !overDateBar && screen==SCREEN_SEASON_DETAIL && detailTab==0 && partiteTab==1 && matchInnerMaxScrollY>0
                    && y>=(matchInnerListTop-scrollY) && y<=(matchInnerListBottom-scrollY) && x>=18 && x<=w-18;
                if(overMatches){ innerDragTarget=1; touchStartInnerScrollY=matchInnerScrollY; }
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                float dy = touchDownY - y;
                float dx = touchDownX - x;
                if(dateBarDragCandidate){
                    // Un tocco iniziato sulla barra date non deve MAI far scorrere anche lo scroll verticale
                    // esterno della schermata: prima, se il piccolo movimento verticale (rumore naturale di un
                    // trascinamento quasi-orizzontale) superava la soglia PRIMA di quello orizzontale, il
                    // codice cadeva per errore nel ramo dello scroll esterno, facendolo avanzare di uno step.
                    if(Math.abs(dx)>8){
                        isDraggingDateBar = true;
                        dateBarScrollX = touchStartDateBarScrollX + dx;
                        if(dateBarScrollX<0) dateBarScrollX=0; if(dateBarScrollX>dateBarMaxScrollX) dateBarScrollX=dateBarMaxScrollX;
                        invalidate();
                    }
                    return true;
                }
                if(Math.abs(dy)>8){
                    if(innerDragTarget!=0) isDraggingInner=true;
                    else if(touchDownY>=bodyTop && touchDownY<=bodyBottom) isDragging=true;
                }
                if(isDraggingInner){
                    matchInnerScrollY = touchStartInnerScrollY + dy;
                    if(matchInnerScrollY<0) matchInnerScrollY=0; if(matchInnerScrollY>matchInnerMaxScrollY) matchInnerScrollY=matchInnerMaxScrollY;
                    invalidate();
                } else if(isDragging){
                    scrollY = touchStartScrollY + dy;
                    if(scrollY<0) scrollY=0; if(scrollY>maxScrollY) scrollY=maxScrollY;
                    invalidate();
                }
                return true;
            }
            if(e.getAction()!=MotionEvent.ACTION_UP) return true;
            if(isDragging || isDraggingInner || isDraggingDateBar){ isDragging=false; isDraggingInner=false; innerDragTarget=0; isDraggingDateBar=false; dateBarDragCandidate=false; return true; }

            float contentY = (y>=bodyTop && y<=bodyBottom) ? y+scrollY : y;

            if(screen==SCREEN_SEASON_LIST){
                if(y>=h-104 && y<=h-54 && x>=w-166){ newSeason(); return true; }
                if(Math.hypot(x-(w-30), y-56) <= 24){ screen=SCREEN_SETTINGS; invalidate(); return true; }
                if(Math.hypot(x-(w-64), y-56) <= 24){ showWelcomeGuide(); return true; }
                for(float[] kb: seasonKebabPos){
                    if(Math.hypot(x-kb[0], contentY-kb[1]) <= 22){ seasonActionsMenu((int)kb[2], kb[0], kb[1]-scrollY+16); return true; }
                }
                for(Hit hit: seasonHits){ if(contentY>=hit.top&&contentY<=hit.bottom){ store.current=hit.index; screen=SCREEN_SEASON_DETAIL; detailTab=0; store.save(); invalidate(); return true; } }
                return true;
            }

            if(screen==SCREEN_SETTINGS){
                if(y<52){ if(x<60){ screen=SCREEN_SEASON_LIST; invalidate(); return true; } return true; }
                if(contentY>=64&&contentY<=144){ editTrainerNameDialog(); return true; }
                if(contentY>=158&&contentY<=282){ showCardStylePreferenceDialog(); return true; }
                if(contentY>=296&&contentY<=376){ showLanguageDialog(); return true; }
                if(contentY>=390&&contentY<=438){ resetAllData(); return true; }
                return true;
            }

            Season s = store.seasons.get(store.current);

            if(y<52){
                if(x<60){ goBack(); return true; }
                p.setTextSize(20); float nameW=p.measureText(s.name);
                if(x>=44+nameW+8){ renameSeason(); return true; }
                return true;
            }
            if(y>h-58){ detailTab=Math.min(2,(int)(x/(w/3))); invalidate(); return true; }
            if(detailTab==0){
                boolean locked = isSeasonLocked(store.current);
                // Badge "Annulla" flottante: controllato PRIMA delle altre zone, dato che sta a cavallo tra
                // la card "Deck Selezionato" e la riga W/L (un cerchio, non un rettangolo, quindi serve un
                // test di distanza invece di un normale confronto di range).
                if(!locked && Math.hypot(x-undoBadgeCx, contentY-undoBadgeCy) <= 20){ confirmUndo(); return true; }
                // Menu "⋮" della card "Deck Selezionato": stessa priorita' del badge Annulla, controllato
                // prima del tap sull'anteprima/sul resto della card (che restano invariati: anteprima apre
                // la galleria, il resto della card cambia deck).
                if(Math.hypot(x-currentDeckKebabX, contentY-currentDeckKebabY) <= 22){
                    Deck curDeckObj=findDeck(s,s.currentDeck);
                    if(curDeckObj!=null){
                        view.showAnchoredMenu(currentDeckKebabX, contentY-scrollY+16,
                            new String[]{"Cambia deck","Rinomina deck","Scegli anteprima","Aggiungi Lista","Elimina deck"},
                            new int[]{Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, red()},
                            new Runnable[]{ () -> chooseCurrentDeck(), () -> renameDeckDialog(curDeckObj), () -> showPreviewPicker(curDeckObj), () -> openDeckImages(curDeckObj), () -> confirmDeleteDeck(s,curDeckObj) });
                        return true;
                    }
                }
                if(!locked && contentY>=152&&contentY<=302){ chooseCurrentDeck(); return true; }
                if(!locked && contentY>=322&&contentY<=386){ if(x<w/2) win(); else loss(); return true; }
                if(contentY>=400&&contentY<=442){
                    // Zone di tocco allargate (erano 22-32 unita' di larghezza, sotto lo standard consigliato
                    // di ~44dp per un tocco affidabile — spiega perche' a volte serviva ritoccare piu' volte).
                    if(x>=w/2-43 && x<w/2-3){ partiteTab=0; invalidate(); return true; } // icona grafico
                    if(x>=w/2+3 && x<w/2+43){ partiteTab=1; invalidate(); return true; } // icona lista
                    if(partiteTab==1 && x>=w/2+70 && x<w/2+110){ addManualCorrection(); return true; } // icona modifica (solo tab Lista) — resta attiva anche a Stagione bloccata
                }
                if(partiteTab==0 && contentY>=rangePillsTop && contentY<=rangePillsBottom){
                    for(int ri=0; ri<rangePillBounds.size(); ri++){
                        float[] b = rangePillBounds.get(ri);
                        if(x>=b[0] && x<=b[0]+b[1]){ chartRange=ri; invalidate(); return true; }
                    }
                }
                if(partiteTab==1){
                    // Tap sulla barra "salta al giorno" (non un trascinamento, gia' gestito sopra): trova la
                    // pillola toccata e scrolla la lista fino a dove inizia quel giorno.
                    if(contentY>=dateBarTop && contentY<=dateBarBottom && x>=18 && x<=w-18){
                        float tapXInBar = x-18-dateBarPillLeftMargin+dateBarScrollX; // stesso campo usato nel disegno, non piu' un numero duplicato a mano
                        for(int di=0; di<dateBarPillBounds.size(); di++){
                            float[] b = dateBarPillBounds.get(di);
                            if(tapXInBar>=b[0] && tapXInBar<=b[0]+b[1]){
                                String dk = dateBarDayKeys.get(di);
                                // "Jump" al giorno: la pillola evidenziata seguira' automaticamente lo scroll
                                // che sta per succedere, non serve piu' impostarla qui a mano.
                                Float off = dayYOffsetMap.get(dk);
                                if(off!=null){ matchInnerScrollY = Math.max(0, Math.min(off, matchInnerMaxScrollY)); invalidate(); }
                                return true;
                            }
                        }
                        return true;
                    }
                    // Guardia mancante: senza, un tocco sull'header fisso (icone/barra data) con la lista
                    // scorsa poteva "combaciare per caso" con lo Hit di una partita molto piu' in basso nel
                    // contenuto scorrevole (matchContentY = contentY + matchInnerScrollY, senza verificare che
                    // contentY fosse davvero dentro l'area scorrevole) — aprendo il dialog cambio deck anche
                    // toccando l'header, non solo una partita vera.
                    if (contentY >= dateBarBottom) {
                        float matchContentY = contentY + matchInnerScrollY;
                        for(Hit hit: matchHits){ if(matchContentY>=hit.top&&matchContentY<=hit.bottom){ Match tapped=s.matches.get(hit.index); if(!tapped.unknown) changeMatchDeck(tapped); return true; } }
                    }
                }
            } else if(detailTab==1){
                if(contentY>=100&&contentY<=148){ addDeck(newDeck -> scrollDeckTabToShow(s, newDeck)); return true; }
                float yy=152;
                for(Deck d: sortedDecks(s)){
                    if(contentY>=yy&&contentY<=yy+40&&x>=w-70){ deckActionsMenu(s,d,w-36,yy+36-scrollY); return true; }
                    yy+=104;
                }
                for(Object[] pz: deckPreviewTapZones){
                    float x1=(float)pz[0], y1=(float)pz[1], x2=(float)pz[2], y2=(float)pz[3];
                    if(x>=x1&&x<=x2&&contentY>=y1&&contentY<=y2){ handlePreviewTap((Deck)pz[4]); return true; }
                }
            }
            return true;
        }
    }

    static class Match {
        boolean win,unknown;int before,after,streak;long timestamp;String deck;
        // Solo per le correzioni (unknown=true): quante vittorie/sconfitte rappresenta il periodo non
        // tracciato — contano SOLO per le statistiche aggregate (W/L/win rate di Stagione), non per lo
        // streak (non sappiamo l'ordine esatto) e non per le statistiche di un deck specifico.
        int correctionWins=0, correctionLosses=0;
        Match(boolean w,int b,int a,int st,String deck){win=w;before=b;after=a;streak=st;timestamp=System.currentTimeMillis();this.deck=deck;}
        static Match correction(int b,int a,String deck){Match m=new Match(a>=b,b,a,0,deck);m.unknown=true;return m;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("w",win);o.put("u",unknown);o.put("b",before);o.put("a",after);o.put("s",streak);o.put("ts",timestamp);o.put("dk",deck!=null?deck:"Unknown");o.put("cw",correctionWins);o.put("cl",correctionLosses);return o;}
        static Match from(JSONObject o)throws Exception{Match m=new Match(o.getBoolean("w"),o.getInt("b"),o.getInt("a"),o.optInt("s",0),o.optString("dk","Unknown"));m.unknown=o.optBoolean("u",false);m.timestamp=o.optLong("ts",0);m.correctionWins=o.optInt("cw",0);m.correctionLosses=o.optInt("cl",0);return m;}
    }
    static class Deck {
        String name; ArrayList<String> images=new ArrayList<>(); Deck(String n){name=n;}
        // Anteprima preimpostata (stile+colore, disegnata direttamente sul canvas — nessuna immagine
        // personalizzata: quella possibilita' e' stata rimossa, causava troppi problemi). Default per ogni
        // nuovo deck: stile "spine", colore "grigiochiaro".
        String previewStyle="spine";   // "spine" | "gem" | "holo" | "prism" | "ring" | "fold"
        String previewColor="grigiochiaro";
        JSONObject json()throws Exception{
            JSONObject o=new JSONObject(); o.put("n",name);
            JSONArray imgs=new JSONArray(); for(String i:images) imgs.put(i); o.put("imgs",imgs);
            o.put("pstyle",previewStyle); o.put("pcolor",previewColor);
            return o;
        }
        static Deck from(JSONObject o){
            Deck d=new Deck(o.optString("n"));
            JSONArray imgs=o.optJSONArray("imgs");
            if(imgs!=null) for(int i=0;i<imgs.length();i++) d.images.add(imgs.optString(i));
            else { String legacy=o.optString("i",null); if(legacy!=null) d.images.add(legacy); } // dati salvati dalla vecchia versione (un solo screenshot)
            d.previewStyle = o.optString("pstyle","spine");
            d.previewColor = o.optString("pcolor","grigiochiaro");
            return d;
        }
    }
    static class Season {
        String name;int baseline,initialStreak,points,streak,lossStreak;String currentDeck="Unknown";
        ArrayList<Deck> decks=new ArrayList<>();ArrayList<Match> matches=new ArrayList<>();
        Season(String n){name=n;}
        JSONObject json()throws Exception{
            JSONObject o=new JSONObject();o.put("n",name);o.put("b",baseline);o.put("is",initialStreak);o.put("p",points);o.put("s",streak);o.put("ls",lossStreak);o.put("cd",currentDeck);
            JSONArray d=new JSONArray();for(Deck x:decks)d.put(x.json());o.put("d",d);
            JSONArray mm=new JSONArray();for(Match x:matches)mm.put(x.json());o.put("matches",mm);
            return o;
        }
        static Season from(JSONObject o)throws Exception{
            Season s=new Season(o.optString("n"));
            s.baseline=o.optInt("b");s.initialStreak=o.optInt("is");s.points=o.optInt("p");s.streak=o.optInt("s");s.lossStreak=o.optInt("ls");
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
                        // Il flag "sessione non tracciata" era il campo canonico e sempre affidabile nei
                        // dati vecchi; quello sulla singola partita ("u") non e' garantito essersi salvato
                        // correttamente in ogni versione precedente dell'app — qui il livello sessione ha
                        // sempre la priorita', per non perdere la marcatura "e' una correzione, non una vera
                        // vittoria/sconfitta" (causa del bug per cui la correzione iniziale appariva come
                        // una normale partita con tanto di badge "W").
                        boolean sessionUntracked = so.optBoolean("u", false);
                        JSONArray mArr=so.optJSONArray("m");
                        if(mArr!=null) for(int j=0;j<mArr.length();j++){
                            JSONObject mo=mArr.getJSONObject(j);
                            Match m=Match.from(mo);
                            if(sessionUntracked) m.unknown = true;
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
        String trainerName=""; boolean onboardingDone=false; // nome allenatore e flag "wizard di benvenuto gia' fatto"
        String preferredCardStyle="spine"; // stile preferito per le anteprime dei nuovi deck ("spine"|"gem"|"holo")
        String language="en"; // lingua dell'app: "en" (default) | "de" | "it" | "fr" | "es" — letta anche in attachBaseContext(), PRIMA che Store venga normalmente istanziato altrove, quindi con un accesso diretto alle SharedPreferences (vedi Companion piu' sotto)
        Store(Context c){pref=c.getSharedPreferences("tracker",0);load();}
        void save(){try{JSONObject o=new JSONObject();JSONArray a=new JSONArray();for(Season s:seasons)a.put(s.json());o.put("seasons",a);o.put("current",current);pref.edit().putString("data",o.toString()).putString("trainerName",trainerName).putBoolean("onboardingDone",onboardingDone).putString("preferredCardStyle",preferredCardStyle).putString("language",language).apply();}catch(Exception e){Log.e(TAG,"Errore nel salvataggio dati",e);}}
        void load(){
            trainerName = pref.getString("trainerName","");
            onboardingDone = pref.getBoolean("onboardingDone", false);
            preferredCardStyle = pref.getString("preferredCardStyle","spine");
            language = pref.getString("language","en");
            try{String z=pref.getString("data",null);if(z==null)return;JSONObject o=new JSONObject(z);current=o.optInt("current");JSONArray a=o.optJSONArray("seasons");if(a!=null)for(int i=0;i<a.length();i++)seasons.add(Season.from(a.getJSONObject(i)));boolean changed=clearFallbackTimestamps();if(repairMislabeledCorrections())changed=true;save_if(changed);}catch(Exception e){Log.e(TAG,"Errore nel caricamento dati, si riparte da zero",e);}
        }
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

        // Ripara le correzioni etichettate male nei dati vecchi (unknown=false quando dovrebbe essere true):
        // NON e' un'euristica incerta ma una certezza matematica. Il guadagno di una vittoria vera puo'
        // essere SOLO uno tra {10,13,16,19,22} (a seconda della serie), una sconfitta vera e' SEMPRE
        // esattamente -10 — sono gli unici valori che play() puo' produrre. Qualsiasi altro guadagno e'
        // impossibile per una partita vera: e' per forza una correzione.
        boolean repairMislabeledCorrections(){
            int[] validWinGains = {10,13,16,19,22};
            boolean changed=false;
            for(Season s: seasons){
                for(Match m: s.matches){
                    if(m.unknown) continue;
                    int gain = m.after-m.before;
                    boolean plausible;
                    if(m.win){ plausible=false; for(int g: validWinGains) if(gain==g){ plausible=true; break; } }
                    else plausible = (gain==-10);
                    if(!plausible){ m.unknown=true; changed=true; }
                }
            }
            return changed;
        }
    }
}
