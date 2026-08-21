package com.stefanorossano.pocketstats;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
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
    private static final String TAG = "PocketStats";
    // Versione build: major.minor decisi da Stefano quando serve, build incrementato di 1 ad OGNI modifica
    // (anche piccola) che produce una nuova build — non solo per feature, e' un contatore di iterazioni.
    static final String APP_VERSION = "v0.7.2";

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
    static final String[] LANGUAGE_CODES = {"en","it"};
    static final String[] LANGUAGE_LABELS = {"English","Italiano"};

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

        TextView title = new TextView(this); title.setText(getString(R.string.dialog_language_title)); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
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
        TextView cancelBtn = new TextView(this); cancelBtn.setText(getString(R.string.btn_cancel)); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(16); cancelBtn.setAllCaps(true);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText(getString(R.string.btn_confirm)); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(16); confirmBtn.setAllCaps(true);
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
            } else {
                Toast.makeText(this,getString(R.string.msg_no_language_change),Toast.LENGTH_SHORT).show();
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

        // Ripristina lo stato salvato (se presente) GIA' ORA, cosi' la schermata sotto e' pronta anche se
        // resta nascosta dietro lo splash — ma senza ancora avviare wizard/onboarding: quello avviene solo
        // dopo il tocco sullo splash, che ora appare SEMPRE a ogni avvio (non solo la primissima volta).
        if (b != null) {
            int savedCurrent = b.getInt("seasonCurrent", 0);
            if (savedCurrent>=0 && savedCurrent<store.seasons.size()) store.current = savedCurrent;
            view.detailTab = b.getInt("detailTab", 0);
            screen = b.getInt("screen", SCREEN_SEASON_LIST);
            if (screen==SCREEN_SEASON_DETAIL && store.seasons.isEmpty()) screen = SCREEN_SEASON_LIST;
        }

        showWelcomeScreen();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("screen", screen);
        outState.putInt("seasonCurrent", store!=null ? store.current : 0);
        if (view != null) { outState.putInt("detailTab", view.detailTab); }
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
        deckSearchInput.setHint(getString(R.string.hint_search_deck)); deckSearchInput.setTextSize(14);
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
        boolean shouldShow = screen==SCREEN_SEASON_DETAIL && view.detailTab==2;
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

    // ===== Onboarding getString(R.string.dialog_ask_name_title): mostrato una sola volta, la primissima volta che l'app
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
        header.setText(getString(R.string.label_trainer_name)); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);
        EditText nameField = new EditText(this);
        nameField.setSingleLine();
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleField(nameField);
        nameField.setText(store.trainerName);
        box.addView(nameField);
        applyMaxLength(box, nameField, 15);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
            .setPositiveButton(getString(R.string.btn_save), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setNeutralButton(getString(R.string.action_remove_name), (d,w) -> {
                store.trainerName = ""; view.cachedGreeting=null; store.save(); view.invalidate();
            })
            .create();
        showNonDismissing(dialog, () -> {
            String n = nameField.getText().toString().trim();
            if (containsBadWord(n)) return false;
            store.trainerName = n; view.cachedGreeting=null; // il messaggio di benvenuto va rigenerato col nuovo nome
            store.save(); view.invalidate();
            return true;
        }, getString(R.string.err_choose_valid_name));
        dialog.show();
    }

    // Schermata di ingresso vera e propria, mostrata UNA volta, prima ancora di chiedere il nome: prima
    // dell'aggiunta di questa schermata si entrava troppo diretti (dialog nome subito). Occupa TUTTO lo
    // schermo (non un box arrotondato flottante come gli altri dialog di onboarding): un vero e proprio
    // sfondo unito allo sfondo dell'app, per un effetto "schermata", non "popup".
    class WelcomeHeroView extends View {
        WelcomeHeroView(Context c){ super(c); }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(), h=getHeight();
            // Solo 2 card (falce di luna + sole), stessa disposizione dell'icona dell'app — non piu' 3, per
            // coerenza visiva tra le due.
            float cardW = w*0.36f, cardH = cardW*1.25f;
            String[] styles = {"crescent","sun"};
            float[] rot = {-8f, 8f};
            float[] offX = {-w*0.11f, w*0.11f};
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setColor(Color.argb(191,255,250,235)); // crema/bianco, semi-trasparente, piu' marcato
            strokePaint.setStrokeWidth(dp(3f)); // molto piu' spesso di prima: a dimensioni piccole (icona) un
                                                  // contorno troppo sottile risultava seghettato/tremolante.
            float cr = 8f*cardW/64f; // stesso raggio d'angolo usato dentro drawPresetPreviewCard
            // Il riflesso lucido ora e' dentro drawPresetPreviewCard stessa (si applica a ogni card
            // dell'app, non solo qui): niente piu' bisogno di ridisegnarlo separatamente in questo punto.
            for (int i=0;i<2;i++){
                c.save();
                c.translate(w/2f+offX[i], h/2f);
                c.rotate(rot[i]);
                drawPresetPreviewCard(c, -cardW/2, -cardH/2, cardW/2, cardH/2, styles[i], "grigioscuro", false); // matte, non piu' glossy
                c.drawRoundRect(-cardW/2, -cardH/2, cardW/2, cardH/2, cr, cr, strokePaint);
                c.restore();
            }
        }
    }

    // Grande grafico di sfondo, a piena larghezza, dietro al ventaglio di card e al resto del contenuto —
    // molto tenue (alpha basso) per non competere visivamente con quello che c'e' sopra, ma visibile a
    // sufficienza da dare l'idea di "taglio" attraverso lo schermo.
    // Grafico di sfondo condiviso: una linea di andamento sottile e tenue che attraversa tutto lo schermo,
    // usata come sfondo discreto sia nello screen iniziale (con sopra il ventaglio di card) sia in TUTTE le
    // altre schermate dell'app (qui invece SENZA nessuna card sopra, solo la linea).
    // Percorso condiviso: usato sia per disegnare la linea base (tenue) sia per calcolare il bagliore che
    // vi scorre sopra durante l'animazione (PathMeasure, stessa identica geometria).
    Path buildBackgroundChartPath(float w, float h){
        // Punti cumulativi realistici: 2 vittorie (streak, bonus crescente), 1 sconfitta (-10 fisso, azzera
        // lo streak), 4 vittorie (streak lungo), 1 sconfitta, 2 vittorie. Ampiezza verticale ridotta
        // (0.22 dell'altezza, non piu' 0.75): su uno schermo verticale molto alto, un'oscillazione cosi'
        // ampia risultava troppo "vertiginosa" per un elemento di sfondo che deve restare discreto.
        int[] cum = {0,10,22,12,22,34,48,64,54,64,76};
        int vmin=0, vmax=76, n=cum.length-1;
        Path chart = new Path();
        for (int i=0;i<cum.length;i++){
            float px = (i/(float)n)*w;
            float py = (0.58f - (cum[i]-vmin)/(float)(vmax-vmin)*0.22f)*h;
            if (i==0) chart.moveTo(px,py); else chart.lineTo(px,py);
        }
        return chart;
    }

    void drawBackgroundChartLine(Canvas c, float w, float h){
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Color.argb(38,90,98,112)); // grigioscuro chiaro, molto tenue
        c.drawPath(buildBackgroundChartPath(w,h), p);
        // Pallini su ogni punto, leggermente piu' marcati della linea.
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.argb(78,90,98,112));
        int[] cum = {0,10,22,12,22,34,48,64,54,64,76};
        int vmin=0, vmax=76, n=cum.length-1;
        for (int i=0;i<cum.length;i++){
            float px = (i/(float)n)*w;
            float py = (0.58f - (cum[i]-vmin)/(float)(vmax-vmin)*0.22f)*h;
            c.drawCircle(px, py, dp(3), dotPaint);
        }
    }

    class BackgroundChartView extends View {
        float glowProgress = -1f; // -1 = animazione non attiva
        BackgroundChartView(Context c){
            super(c);
            // Necessario per il BlurMaskFilter del bagliore: su canvas hardware-accelerato non renderizza
            // senza forzare questa view al livello software.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        void startGlowAnimation(){
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(600);
            anim.addUpdateListener(a -> { glowProgress = (float)a.getAnimatedValue(); invalidate(); });
            anim.start();
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(), h=getHeight();
            drawBackgroundChartLine(c, w, h);
            if (glowProgress >= 0f) {
                Path chart = buildBackgroundChartPath(w, h);
                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(chart, false);
                float len = pm.getLength();
                float glowWindow = len * 0.24f;
                // Il bagliore entra ed esce dai margini del percorso (centro va da -mezza finestra a
                // lunghezza+mezza finestra), non resta "tagliato" bruscamente ai due estremi.
                float centerD = glowProgress * (len + glowWindow) - glowWindow/2f;
                float startD = Math.max(0, centerD - glowWindow/2f);
                float endD = Math.min(len, centerD + glowWindow/2f);
                if (endD > startD) {
                    Path segment = new Path();
                    pm.getSegment(startD, endD, segment, true);
                    Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    glowPaint.setStyle(Paint.Style.STROKE);
                    glowPaint.setStrokeWidth(dp(4));
                    glowPaint.setStrokeCap(Paint.Cap.ROUND);
                    glowPaint.setStrokeJoin(Paint.Join.ROUND);
                    glowPaint.setColor(Color.rgb(0xFF,0xF3,0xD1)); // oro chiaro (non piu' il mid): richiesto piu' luminoso
                    glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(dp(7), android.graphics.BlurMaskFilter.Blur.NORMAL));
                    c.drawPath(segment, glowPaint);
                }
            }
        }
    }

    void showWelcomeScreen(){
        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        outer.setBackgroundColor(Color.rgb(7,11,18)); // stesso sfondo scuro di tutta l'app

        // Il grafico di sfondo appare insieme alle card (nessun ritardo su questo): dopo 300ms un bagliore
        // dorato lo percorre (durata 600ms, quindi finisce a 900ms), poi a 1200ms tutto dissolve nella
        // schermata successiva.
        BackgroundChartView bgChart = new BackgroundChartView(this);
        outer.addView(bgChart, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Solo le 2 card, centrate — niente piu' titolo/sottotitolo/testo "tocca per iniziare": questa
        // schermata e' ora puramente animata/automatica, non richiede piu' un tocco dell'utente.
        WelcomeHeroView hero = new WelcomeHeroView(this);
        android.widget.FrameLayout.LayoutParams heroLp = new android.widget.FrameLayout.LayoutParams(dp(260), dp(160));
        heroLp.gravity = Gravity.CENTER;
        outer.addView(hero, heroLp);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(outer);
        if (dialog.getWindow()!=null) {
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            // Sfondo pieno, non il drawable arrotondato/inset di PocketDialogTheme: qui vogliamo una vera
            // schermata a pieno schermo, non un box flottante su sfondo scuro.
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.rgb(7,11,18)));
        }
        dialog.show();

        // Sequenza temporizzata, leggermente piu' rilassata di un primo tentativo (300ms/600ms/1200ms):
        // per un'app di score-tracking aperta piu' volte al giorno un'attesa lunga stancherebbe, ma visto
        // che ora e' puramente automatica (nessun tocco richiesto) un margine in piu' regge bene.
        outer.postDelayed(bgChart::startGlowAnimation, 300);
        outer.postDelayed(() -> {
            android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(outer, "alpha", 1f, 0f);
            fade.setDuration(300);
            fade.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){
                    dialog.dismiss();
                    // Onboarding ("come ti chiami, allenatore?") ha sempre la priorita': se non ancora
                    // fatto, il primo step del wizard e' ora questa schermata titolo+sottotitolo+pulsante
                    // (prima viveva qui nello splash stesso), poi si chiede il nome. Altrimenti, se l'utente
                    // ha gia' un profilo ma nessuna Stagione (es. le ha cancellate tutte), il wizard
                    // Stagione parte diretto; se nessuno di questi casi, non serve altro — la schermata
                    // sotto e' gia' pronta (ripristinata in onCreate).
                    if (!store.onboardingDone) { showWizardIntroStep(); }
                    else if (store.seasons.isEmpty()) { wizardStep1(true, null); }
                }
            });
            fade.start();
        }, 1200);
    }

    // Primo step del wizard (solo primissimo avvio): titolo + sottotitolo + pulsante, prima vivevano nello
    // splash automatico; ora e' un vero step del wizard, con un pulsante esplicito su cui l'utente clicca
    // per procedere (invece dello splash, che ora e' del tutto automatico/temporizzato).
    void showWizardIntroStep(){
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(7,11,18));

        TextView title = new TextView(this); title.setText(getString(R.string.welcome_hero_title)); title.setTextColor(Color.WHITE); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tagline = new TextView(this); tagline.setText(getString(R.string.welcome_hero_tagline)); tagline.setTextColor(MUTED_TXT); tagline.setTextSize(15); tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams taglineLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        taglineLp.topMargin=dp(8); taglineLp.leftMargin=dp(32); taglineLp.rightMargin=dp(32);
        root.addView(tagline, taglineLp);

        TextView startBtn = new TextView(this); startBtn.setText(getString(R.string.btn_get_started)); startBtn.setTextColor(Color.WHITE); startBtn.setTextSize(16); startBtn.setTypeface(Typeface.DEFAULT_BOLD); startBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable(); btnBg.setColor(blueColor()); btnBg.setCornerRadius(dp(24));
        startBtn.setBackground(btnBg);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(dp(220), dp(48));
        startLp.topMargin = dp(36);
        root.addView(startBtn, startLp);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) {
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.rgb(7,11,18)));
        }
        dialog.show();

        startBtn.setOnClickListener(v -> { dialog.dismiss(); askTrainerName(); });
    }

    void askTrainerName(){
        LinearLayout box = formBox();
        TextView header = new TextView(this);
        header.setText(getString(R.string.dialog_ask_name_title)); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);
        // Campo vuoto, NESSUN placeholder: tastiera con la prima lettera in maiuscolo.
        EditText nameField = new EditText(this);
        nameField.setSingleLine();
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleField(nameField);
        box.addView(nameField);
        applyMaxLength(box, nameField, 15);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .setNeutralButton(getString(R.string.action_prefer_not_say), (d,w) -> confirmTrainerName(""))
            .create();
        showNonDismissing(dialog, () -> {
            String n = nameField.getText().toString().trim();
            if (n.isEmpty() || containsBadWord(n)) return false;
            confirmTrainerName(n);
            return true;
        }, getString(R.string.err_choose_valid_name_or_skip));
        dialog.show();
    }

    void confirmTrainerName(String name){
        if (name.isEmpty()) {
            store.trainerName = ""; store.onboardingDone = true; store.save();
            showOnboardingCardStyleDialog();
            return;
        }
        new AlertDialog.Builder(this).setTitle(getString(R.string.btn_confirm))
            .setMessage(getString(R.string.confirm_trainer_name_msg,name))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_yes), (d,w) -> {
                store.trainerName = name; store.onboardingDone = true; store.save();
                showOnboardingCardStyleDialog();
            })
            .setNegativeButton(getString(R.string.btn_no), (d,w) -> askTrainerName())
            .show();
    }

    // Nuovo step del wizard, tra la conferma del nome e la guida di benvenuto: primissima impressione
    // dell'app, deve sembrare curata. Le anteprime usano il colore ARCOBALENO (non il grigio della stessa
    // scelta rifatta poi nelle Impostazioni) apposta per un effetto piu' "premium" al primissimo avvio. Non
    // annullabile (come il resto dell'onboarding): sempre possibile cambiare stile dopo, dalle Impostazioni.
    void showOnboardingCardStyleDialog(){
        String[] selectedStyle = { store.preferredCardStyle };

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        String onboardingStyleTitle = store.trainerName.isEmpty() ? getString(R.string.dialog_onboarding_style_title) : getString(R.string.dialog_onboarding_style_title_named, store.trainerName);
        TextView title = new TextView(this); title.setText(onboardingStyleTitle); title.setTextColor(Color.WHITE); title.setTextSize(19); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(18); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18); titleLp.bottomMargin=dp(4);
        root.addView(title, titleLp);

        // Ciclo colore per l'anteprima (non per lo stile selezionato, che resta indipendente): stessi chevron
        // cerchiati gia' usati per navigare tra le Liste (stesso Bitmap, stesso sfondo circolare).
        String[] colorCycle = {"arcobaleno","verde","rosso","azzurro","giallo","viola","marrone","grigioscuro","oro","grigiochiaro"};
        int[] colorLabelRes = {R.string.color_rainbow,R.string.color_green,R.string.color_red,R.string.color_blue,R.string.color_yellow,R.string.color_purple,R.string.color_brown,R.string.color_dark_gray,R.string.color_gold,R.string.color_light_gray};
        int[] colorIdx = {0};

        LinearLayout colorRow = new LinearLayout(this); colorRow.setOrientation(LinearLayout.HORIZONTAL); colorRow.setGravity(Gravity.CENTER_VERTICAL);
        int arrowIconPx = dp(14);
        ImageView prevColorBtn = new ImageView(this);
        prevColorBtn.setImageBitmap(makeChevronIcon(Color.WHITE, arrowIconPx, false));
        prevColorBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable prevColorBg = new GradientDrawable(); prevColorBg.setShape(GradientDrawable.OVAL); prevColorBg.setColor(Color.rgb(24,36,52));
        prevColorBtn.setBackground(prevColorBg);
        LinearLayout.LayoutParams prevColorLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        colorRow.addView(prevColorBtn, prevColorLp);

        TextView colorLabel = new TextView(this); colorLabel.setText(getString(colorLabelRes[0])); colorLabel.setTextColor(Color.WHITE); colorLabel.setTextSize(13); colorLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams colorLabelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        colorRow.addView(colorLabel, colorLabelLp);

        ImageView nextColorBtn = new ImageView(this);
        nextColorBtn.setImageBitmap(makeChevronIcon(Color.WHITE, arrowIconPx, true));
        nextColorBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable nextColorBg = new GradientDrawable(); nextColorBg.setShape(GradientDrawable.OVAL); nextColorBg.setColor(Color.rgb(24,36,52));
        nextColorBtn.setBackground(nextColorBg);
        LinearLayout.LayoutParams nextColorLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        colorRow.addView(nextColorBtn, nextColorLp);

        LinearLayout.LayoutParams colorRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        colorRowLp.leftMargin=dp(18); colorRowLp.rightMargin=dp(18); colorRowLp.topMargin=dp(6);
        root.addView(colorRow, colorRowLp);

        String[] styleKeys = {"spine","gem","crescent","waves","sun","zigzag"};
        PreviewSwatchView[] swatches = new PreviewSwatchView[6];
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout gridRow = null;
        for (int i=0;i<6;i++){
            if (i%3==0){
                gridRow = new LinearLayout(this); gridRow.setOrientation(LinearLayout.HORIZONTAL); gridRow.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams gridRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                gridRowLp.topMargin = i==0?dp(10):dp(12);
                grid.addView(gridRow, gridRowLp);
            }
            PreviewSwatchView sw = new PreviewSwatchView(this, styleKeys[i], colorCycle[colorIdx[0]]);
            sw.selected = styleKeys[i].equals(selectedStyle[0]);
            swatches[i]=sw;
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(84), dp(105));
            swLp.leftMargin=dp(8); swLp.rightMargin=dp(8);
            gridRow.addView(sw, swLp);
            final String sk = styleKeys[i];
            sw.setOnClickListener(v -> { selectedStyle[0]=sk; for(PreviewSwatchView s: swatches) s.selected=s.style.equals(sk); for(PreviewSwatchView s: swatches) s.invalidate(); });
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.bottomMargin=dp(18);
        root.addView(grid, gridLp);

        // I chevron cambiano solo il COLORE mostrato in anteprima su tutte le 6 card contemporaneamente:
        // lo stile selezionato (selectedStyle[0]/sw.selected) resta invariato, e' del tutto indipendente.
        Runnable applyColor = () -> {
            String ck = colorCycle[colorIdx[0]];
            colorLabel.setText(getString(colorLabelRes[colorIdx[0]]));
            for (PreviewSwatchView s: swatches) { s.colorKey = ck; s.invalidate(); }
        };
        prevColorBtn.setOnClickListener(v -> { colorIdx[0] = (colorIdx[0]-1+colorCycle.length)%colorCycle.length; applyColor.run(); });
        nextColorBtn.setOnClickListener(v -> { colorIdx[0] = (colorIdx[0]+1)%colorCycle.length; applyColor.run(); });

        // Selettore Matte/Glossy, sotto le card: vale per QUALSIASI stile scelto, non e' legato a uno
        // stile specifico.
        String[] selectedFinish = { "matte".equals(store.preferredCardFinish) ? "matte" : "glossy" };
        LinearLayout finishRow = new LinearLayout(this); finishRow.setOrientation(LinearLayout.HORIZONTAL); finishRow.setGravity(Gravity.CENTER);
        String[] finishOptions = {"glossy","matte"};
        String[] finishLabels = {getString(R.string.finish_glossy), getString(R.string.finish_matte)};
        TextView[] finishPills = new TextView[2];
        for (int i=0;i<2;i++){
            TextView pill = new TextView(this); pill.setText(finishLabels[i]); pill.setTextSize(13); pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(20),dp(8),dp(20),dp(8));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = i==0?0:dp(10);
            finishRow.addView(pill, plp);
            finishPills[i]=pill;
        }
        LinearLayout.LayoutParams finishRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        finishRowLp.bottomMargin = dp(14);
        root.addView(finishRow, finishRowLp);
        Runnable[] refreshFinish = new Runnable[1];
        refreshFinish[0] = () -> {
            for (int i=0;i<2;i++){
                boolean active = finishOptions[i].equals(selectedFinish[0]);
                finishPills[i].setTextColor(active?Color.WHITE:MUTED_TXT);
                GradientDrawable pbg = new GradientDrawable(); pbg.setCornerRadius(dp(16));
                pbg.setColor(active?blueColor():Color.rgb(24,36,52));
                finishPills[i].setBackground(pbg);
            }
            boolean glossy = !"matte".equals(selectedFinish[0]);
            for (PreviewSwatchView s: swatches) { s.glossy = glossy; s.invalidate(); }
        };
        refreshFinish[0].run();
        for (int i=0;i<2;i++){ final String fo=finishOptions[i]; finishPills[i].setOnClickListener(v -> { selectedFinish[0]=fo; refreshFinish[0].run(); }); }

        TextView confirmBtn = new TextView(this); confirmBtn.setText(getString(R.string.btn_continue)); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(16); confirmBtn.setTypeface(Typeface.DEFAULT_BOLD); confirmBtn.setAllCaps(true);
        confirmBtn.setGravity(Gravity.CENTER); confirmBtn.setPadding(0,dp(6),0,dp(6));
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        confirmLp.leftMargin=dp(18); confirmLp.rightMargin=dp(18); confirmLp.bottomMargin=dp(14);
        root.addView(confirmBtn, confirmLp);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        confirmBtn.setOnClickListener(v -> {
            store.preferredCardStyle = selectedStyle[0];
            store.preferredCardFinish = selectedFinish[0];
            store.save();
            dialog.dismiss();
            showWelcomeGuide();
        });
    }

    // Guida di benvenuto ridisegnata: righe distinte (accento colorato + titolo in grassetto + descrizione
    // breve) invece di un unico paragrafo lungo — molto piu' scorrevole su schermo piccolo di un "muro di
    // testo".
    void showWelcomeGuide(){
        String title = store.trainerName.isEmpty() ? getString(R.string.dialog_welcome_title) : getString(R.string.dialog_welcome_title_named,store.trainerName);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView titleView = new TextView(this); titleView.setText(title); titleView.setTextColor(Color.WHITE); titleView.setTextSize(19); titleView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(18); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18); titleLp.bottomMargin=dp(8);
        root.addView(titleView, titleLp);

        String[][] rows = {
            {getString(R.string.guide_row1_title), getString(R.string.guide_row1_desc)},
            {getString(R.string.guide_row2_title), getString(R.string.guide_row2_desc)},
            {getString(R.string.guide_row3_title), getString(R.string.guide_row3_desc)},
            {getString(R.string.guide_row4_title), getString(R.string.guide_row4_desc, getString(R.string.action_add_correction))},
        };
        for (String[] row : rows) {
            LinearLayout rowLayout = new LinearLayout(this); rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            View accent = new View(this);
            GradientDrawable accentBg = new GradientDrawable(); accentBg.setColor(blueColor()); accentBg.setCornerRadius(dp(2));
            accent.setBackground(accentBg);
            LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT);
            accentLp.rightMargin=dp(12); accentLp.topMargin=dp(2); accentLp.bottomMargin=dp(2);
            rowLayout.addView(accent, accentLp);

            LinearLayout textCol = new LinearLayout(this); textCol.setOrientation(LinearLayout.VERTICAL);
            TextView rowTitle = new TextView(this); rowTitle.setText(row[0]); rowTitle.setTextColor(Color.WHITE); rowTitle.setTextSize(15); rowTitle.setTypeface(Typeface.DEFAULT_BOLD);
            TextView rowDesc = new TextView(this); rowDesc.setText(row[1]); rowDesc.setTextColor(MUTED_TXT); rowDesc.setTextSize(13);
            LinearLayout.LayoutParams rowDescLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowDescLp.topMargin=dp(2);
            textCol.addView(rowTitle);
            textCol.addView(rowDesc, rowDescLp);
            rowLayout.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.leftMargin=dp(18); rowLp.rightMargin=dp(18); rowLp.topMargin=dp(12);
            root.addView(rowLayout, rowLp);
        }

        TextView footerLine = new TextView(this); footerLine.setText(getString(R.string.dialog_welcome_footer)); footerLine.setTextColor(MUTED_TXT); footerLine.setTextSize(13); footerLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin=dp(18); footerLp.leftMargin=dp(18); footerLp.rightMargin=dp(18);
        root.addView(footerLine, footerLp);

        TextView startBtn = new TextView(this); startBtn.setText(getString(R.string.btn_lets_start)); startBtn.setTextColor(blueColor()); startBtn.setTextSize(15); startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setGravity(Gravity.CENTER); startBtn.setPadding(0,dp(6),0,dp(6));
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        startLp.topMargin=dp(14); startLp.bottomMargin=dp(14); startLp.leftMargin=dp(18); startLp.rightMargin=dp(18);
        root.addView(startBtn, startLp);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        startBtn.setOnClickListener(v -> { dialog.dismiss(); if (store.seasons.isEmpty()) wizardStep1(true, null); });
    }

    void wizardStep1(boolean first, String prefillName){
        LinearLayout box = formBox();
        String defaultName = first ? getString(R.string.label_season_default_name,1) : getString(R.string.label_season_default_name,store.seasons.size()+1);
        box.addView(label(getString(R.string.hint_season_name)));
        EditText name = field(defaultName);
        if (prefillName != null) name.setText(prefillName);
        box.addView(name);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(first ? getString(R.string.dialog_create_first_season_title) : getString(R.string.btn_new_season))
            .setView(box).setCancelable(!first)
            .setPositiveButton(getString(R.string.btn_next), (d,w) -> {
                String n = name.getText().toString().trim();
                wizardStep2(first, n.isEmpty() ? defaultName : n);
            });
        if (!first) b.setNegativeButton(getString(R.string.btn_cancel), null); // solo se NON e' la primissima Stagione: qui c'e' gia' una lista a cui tornare
        b.show();
    }

    void wizardStep2(boolean first, String name){
        new AlertDialog.Builder(this).setTitle(name)
            .setMessage(getString(R.string.dialog_already_played_question))
            .setCancelable(false)
            .setNeutralButton(getString(R.string.btn_back), (d,w) -> wizardStep1(first, name))
            .setPositiveButton(getString(R.string.btn_yes), (d,w) -> wizardStep3Yes(first, name))
            .setNegativeButton(getString(R.string.btn_no), (d,w) -> wizardStep3No(first, name))
            .show();
    }

    // getString(R.string.btn_yes): chiede solo lo stato ATTUALE (il baseline resta lo standard 810/streak 0) e registra la
    // differenza come un'unica partita di correzione (stesso meccanismo di getString(R.string.action_add_correction)).
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
        EditText points = numberField(getString(R.string.label_current_points_title), true);
        EditText streak = numberField(getString(R.string.label_current_streak), true);
        EditText wins = numberField(getString(R.string.label_total_wins), false);
        EditText losses = numberField(getString(R.string.label_total_losses), false);
        box.addView(label(getString(R.string.label_current_points_title))); box.addView(points);
        box.addView(label(getString(R.string.label_current_streak))); box.addView(streak);
        box.addView(label(getString(R.string.label_total_wins))); box.addView(wins);
        box.addView(label(getString(R.string.label_total_losses))); box.addView(losses);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name)
            .setView(box).setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_create_season_title), null)
            .setNeutralButton(getString(R.string.btn_back), (d,w) -> wizardStep2(first, name))
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
        }, getString(R.string.err_invalid_values));
        dialog.show();
    }

    // getString(R.string.btn_no): si parte dallo standard 810/streak 0, e si passa dritti alla scelta del deck di partenza
    // (con getString(R.string.btn_cancel) per decidere più avanti, come al solito).
    void wizardStep3No(boolean first, String name){
        Season s = new Season(name);
        s.baseline = DEFAULT_BASELINE; s.initialStreak = 0;
        s.points = DEFAULT_BASELINE; s.streak = 0;
        s.currentDeck = "Unknown";
        importDeckNamesFromPreviousSeason(s);
        store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
        if (view == null) { setupTrackerView(); }
        screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
        pickDeckFor(s, getString(R.string.dialog_choose_deck_title), "Salta", dn -> { s.currentDeck = dn; store.save(); view.invalidate(); });
    }


    boolean deckNameTaken(Season s, String n) {
        if ("Unknown".equalsIgnoreCase(n)) return true;
        for (Deck d : s.decks) if (d.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    // Selettore di deck condiviso: usato sia per scegliere il deck "attuale" (quello che verra' assegnato alla
    // PROSSIMA partita registrata) sia per cambiare retroattivamente il deck di una partita GIA' giocata.
    // onPicked riceve il nome del deck scelto (o appena creato) e decide lui cosa farne.
    // Dialog getString(R.string.dialog_pick_deck_title): lista con ricerca (stessa idea del tab Deck: lente che si espande in un
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
        TextView title = new TextView(this); title.setText(getString(R.string.dialog_pick_deck_title)); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
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
        searchInput.setTextColor(Color.WHITE); searchInput.setHintTextColor(MUTED_TXT); searchInput.setHint(getString(R.string.hint_search_deck)); searchInput.setTextSize(14);
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

        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).setNegativeButton(getString(R.string.btn_cancel), null).create();
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
        refreshSelector[0] = () -> deckSelector.setText(selected[0] != null ? selected[0] : getString(R.string.hint_tap_choose_deck));
        refreshSelector[0].run();
        if (hasAnyDeck) {
            LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            box.addView(deckSelector, selLp);
        }

        // "Nuovo Deck": si trasforma in un campo di testo con una "✕" sovrapposta per richiuderlo, invece
        // di aprire un secondo dialog separato — piu' rapido per la creazione al volo.
        Button newDeckBtn = new Button(this); newDeckBtn.setText(getString(R.string.btn_new_deck)); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newBtnLp.topMargin = dp(10); box.addView(newDeckBtn, newBtnLp);

        android.widget.FrameLayout newDeckSection = new android.widget.FrameLayout(this);
        EditText newDeckName = field(getString(R.string.hint_new_deck_name)); newDeckName.setPadding(dp(14),dp(12),dp(44),dp(12));
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
            .setPositiveButton(getString(R.string.btn_confirm), null).setNegativeButton(negativeLabel, null).create();
        // Selezionare un deck ESISTENTE dalla lista conferma ed esce subito (chiude anche questo dialog
        // "genitore"): prima bisognava tornare qui e premere ancora getString(R.string.btn_confirm), un passaggio in piu' inutile
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
        }, getString(R.string.err_select_or_new_deck));
        dialog.show();
    }

    void renameDeckDialog(Deck d){ renameDeckDialog(d, null); }

    // onChanged (opzionale): richiamato a rinomina avvenuta — vedi commento su showPreviewPicker per il motivo.
    void renameDeckDialog(Deck d, Runnable onChanged){
        if (d==null) return;
        Season s = store.seasons.get(store.current);
        String oldName = d.name;
        LinearLayout box = formBox();
        EditText e = field(oldName); e.setText(oldName);
        box.addView(e);
        applyMaxLength(box, e, 18);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_rename_deck_title)).setView(box)
            .setPositiveButton(getString(R.string.btn_save), null).setNegativeButton(getString(R.string.btn_cancel), null).create();
        showNonDismissing(dialog, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || n.equalsIgnoreCase(getString(R.string.label_unknown_deck)) || n.equalsIgnoreCase("Unknown")) return false;
            for (Deck other: s.decks) if (other!=d && other.name.equalsIgnoreCase(n)) return false;
            d.name = n;
            for (Match m: s.matches) if (oldName.equals(m.deck)) m.deck = n;
            if (oldName.equals(s.currentDeck)) s.currentDeck = n;
            store.save(); view.invalidate();
            if (onChanged!=null) onChanged.run();
            return true;
        }, getString(R.string.err_deck_name_invalid));
        dialog.show();
    }

    // Riga di una card deck dentro il dialog getString(R.string.action_change_deck): stesso disegno esatto delle card del tab Deck
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
                p.setColor(Color.argb(170,255,250,235)); // crema, stesso colore dello stroke del ventaglio nello screen iniziale
                // Stesso rettangolo ESATTO della card (18,1.5,w-18,93.5) e stesso raggio (18) usato da box().
                c.drawRoundRect(new RectF(18,1.5f,w-18,93.5f), 18,18, p);
            }
            c.restore();
        }
    }

    // Card deck AVVERSARIO nei dialog di selezione: stessa struttura di DeckCardRowView (stroke di
    // selezione identico), ma disegna opponentDeckCardVisual invece di deckCardVisual — cosi' le due
    // famiglie di card sono visivamente indistinguibili a parte l'assenza dell'anteprima grafica.
    class OpponentDeckCardRowView extends View {
        String oppName; int timesEncountered, wins, losses; boolean selected=false; float density_;
        OpponentDeckCardRowView(Context c, String name, int times, int w, int l){ super(c); oppName=name; timesEncountered=times; wins=w; losses=l; density_=getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if (getWidth()==0) return;
            c.save(); c.scale(density_, density_);
            float w = getWidth()/density_;
            view.opponentDeckCardVisual(c, oppName, timesEncountered, wins, losses, 1.5f, w);
            if (selected) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
                p.setColor(Color.argb(170,255,250,235));
                c.drawRoundRect(new RectF(18,1.5f,w-18,93.5f), 18,18, p);
            }
            c.restore();
        }
    }

    // Menu "⋮" di una riga nel dialog getString(R.string.action_change_deck): stesse azioni disponibili altrove per un deck, con in
    // piu' getString(R.string.action_view_lista) separata da getString(R.string.action_add_lista) (prima un'unica voce faceva entrambe le cose in base
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
        ArrayList<String> labels = new ArrayList<>(java.util.Arrays.asList(getString(R.string.action_rename_deck),getString(R.string.action_choose_preview)));
        ArrayList<Integer> colors = new ArrayList<>(java.util.Arrays.asList(Color.WHITE, Color.WHITE));
        ArrayList<Runnable> actions = new ArrayList<>(java.util.Arrays.asList(
            (Runnable)(() -> renameDeckDialog(d, onChanged)), (Runnable)(() -> showPreviewPicker(d, onChanged))));
        if (hasLista) { labels.add(getString(R.string.action_view_lista)); colors.add(Color.WHITE); actions.add(() -> showImageGallery(d,0)); }
        labels.add(getString(R.string.action_add_lista)); colors.add(Color.WHITE); actions.add(() -> pickImageFor(d));
        labels.add(getString(R.string.action_delete_deck)); colors.add(red()); actions.add(() -> confirmDeleteDeck(s,d,onChanged));
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

    // Dialog getString(R.string.dialog_select_deck_title) generico, condiviso da chooseCurrentDeck() (cambia il deck attuale della
    // Stagione) e changeMatchDeck() (cambia il deck di UNA partita gia' giocata) — stesse card, ricerca,
    // "Nuovo Deck", Annulla/Conferma; cambia solo il titolo, la selezione di partenza e cosa fare col deck
    // scelto (parametrizzato con onConfirm).
    void showDeckSelectorDialog(Season s, String headerText, Deck initialSelection, java.util.function.Consumer<Deck> onConfirm) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView title = new TextView(this); title.setText(headerText); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18);
        root.addView(title, titleLp);

        Dialog[] dialogHolder = new Dialog[1];
        Deck[] selected = buildOwnDeckPickerSection(root, s, initialSelection, newDeck -> {
            // Stesso comportamento di sempre per questo dialog generico (usato da chooseCurrentDeck ecc.):
            // creare un deck qui lo applica subito, senza passare da "Conferma".
            onConfirm.accept(newDeck);
            dialogHolder[0].dismiss();
        });

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(6),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText(getString(R.string.btn_cancel)); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(16); cancelBtn.setAllCaps(true);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText(getString(R.string.btn_confirm)); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(16); confirmBtn.setAllCaps(true);
        confirmBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(cancelBtn); footer.addView(confirmBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialogHolder[0] = dialog;
        dialog.show();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            if (selected[0]!=null) onConfirm.accept(selected[0]);
            dialog.dismiss();
        });
    }

    // Componente riusabile: ricerca + lista scrollabile di card DEL TUO deck (con anteprima, statistiche,
    // menu "⋮" per rinomina/anteprima/lista/elimina) + pulsante "Nuovo Deck" — usato sia dal dialog generico
    // sopra sia dalla Fase 1 del flusso a 2 fasi di changeMatchDeck(). Aggiunge tutto dentro "parent" (non
    // crea un proprio dialog): il chiamante decide header/footer/dialog attorno. onNewDeckCreated: il
    // chiamante decide cosa succede dopo la creazione di un deck nuovo (applicarlo subito e chiudere, oppure
    // solo selezionarlo e restare aperti — dipende dal flusso).
    Deck[] buildOwnDeckPickerSection(LinearLayout parent, Season s, Deck initialSelection, java.util.function.Consumer<Deck> onNewDeckCreated) {
        Deck[] selected = { initialSelection };
        ArrayList<Deck> allDecks = view.sortedDecks(s);
        ArrayList<Deck> filtered = new ArrayList<>(allDecks);
        Runnable[] refreshFromSource = new Runnable[1];

        LinearLayout searchBar = new LinearLayout(this); searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable(); searchBg.setColor(Color.rgb(10,18,30)); searchBg.setCornerRadius(dp(16));
        searchBar.setBackground(searchBg);
        ImageView searchIcon = new ImageView(this); searchIcon.setImageBitmap(makeSearchIcon(Color.WHITE, dp(16)));
        searchIcon.setPadding(dp(12),dp(8),dp(6),dp(8));
        searchBar.addView(searchIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText searchInput = new EditText(this); searchInput.setSingleLine(); searchInput.setBackground(null);
        searchInput.setTextColor(Color.WHITE); searchInput.setHintTextColor(MUTED_TXT); searchInput.setHint(getString(R.string.hint_search_deck)); searchInput.setTextSize(14);
        searchInput.setPadding(0,0,0,0);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView clearBtn = new TextView(this); clearBtn.setText("✕"); clearBtn.setTextColor(MUTED_TXT); clearBtn.setGravity(Gravity.CENTER); clearBtn.setTextSize(13);
        GradientDrawable clearCircle = new GradientDrawable(); clearCircle.setShape(GradientDrawable.OVAL); clearCircle.setColor(Color.rgb(24,36,52));
        clearBtn.setBackground(clearCircle); clearBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(22), dp(22)); clearLp.leftMargin=dp(6); clearLp.rightMargin=dp(6);
        searchBar.addView(clearBtn, clearLp);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        searchLp.topMargin=dp(14); searchLp.leftMargin=dp(18); searchLp.rightMargin=dp(18); searchLp.bottomMargin=dp(10);
        parent.addView(searchBar, searchLp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,dp(4),0,dp(4));
        scroll.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400));
        parent.addView(scroll, scrollLp);

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
                khLp.gravity = Gravity.TOP|Gravity.END; khLp.rightMargin = dp(14); khLp.topMargin = dp(1.5f);
                row.addView(kebabHotspot, khLp);
                View previewHotspot = new View(this);
                android.widget.FrameLayout.LayoutParams phLp = new android.widget.FrameLayout.LayoutParams(dp(64), dp(80));
                phLp.gravity = Gravity.TOP|Gravity.START; phLp.leftMargin = dp(28); phLp.topMargin = dp(7.5f);
                row.addView(previewHotspot, phLp);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(95));
                rowLp.bottomMargin = dp(10);
                list.addView(row, rowLp);

                cardView.setOnClickListener(v -> { selected[0]=d; rebuildList[0].run(); });
                previewHotspot.setOnClickListener(v -> showPreviewPicker(d, cardView::invalidate));
                kebabHotspot.setOnClickListener(v -> showDeckRowMenu(s, d, kebabHotspot, refreshFromSource[0]));
            }
            int rowHeightPx = dp(95)+dp(10);
            int wantedHeight = filtered.size()*rowHeightPx;
            scrollLp.height = Math.min(wantedHeight, dp(400));
            scroll.setLayoutParams(scrollLp);
        };
        rebuildList[0].run();

        if (selected[0]!=null) {
            int idx = filtered.indexOf(selected[0]);
            if (idx>=0) {
                int rowH = dp(95)+dp(10);
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
        refreshFromSource[0] = () -> {
            allDecks.clear(); allDecks.addAll(view.sortedDecks(s));
            if (selected[0]!=null && !allDecks.contains(selected[0])) {
                selected[0] = allDecks.isEmpty() ? null : allDecks.get(0);
            }
            doFilter[0].run();
        };
        clearBtn.setOnClickListener(v -> { searchInput.setText(""); doFilter[0].run(); });
        searchInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s2,int a,int b,int c){}
            public void onTextChanged(CharSequence s2,int a,int b,int c){ clearBtn.setVisibility(s2.length()>0?View.VISIBLE:View.GONE); }
            public void afterTextChanged(android.text.Editable s2){}
        });
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

        Button newDeckBtn = new Button(this); newDeckBtn.setText(getString(R.string.btn_new_deck)); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newBtnLp.topMargin=dp(4); newBtnLp.leftMargin=dp(19); newBtnLp.rightMargin=dp(19); newBtnLp.bottomMargin=dp(10); // 19 non 18: compensa il rigonfiamento visivo dello stroke (pill() ne aggiunge 1dp, che sporge leggermente oltre i bordi geometrici) rispetto a card/barra ricerca senza alcuno stroke
        parent.addView(newDeckBtn, newBtnLp);
        // Default sempre applicato: il deck appena creato viene selezionato e la lista aggiornata, cosi'
        // resta visibile ed evidenziato subito. onNewDeckCreated e' un'azione AGGIUNTIVA facoltativa del
        // chiamante (es. applicarlo subito e chiudere tutto il dialog) — non sostituisce il comportamento
        // di base, altrimenti chi non la fornisce si ritroverebbe un deck creato ma non selezionato.
        newDeckBtn.setOnClickListener(v -> addDeck(newDeck -> {
            selected[0] = newDeck;
            refreshFromSource[0].run();
            if (onNewDeckCreated!=null) onNewDeckCreated.accept(newDeck);
        }));

        return selected;
    }

    // Salva (o cancella, se lasciato vuoto) il deck avversario per la partita in modifica, se questo dialog
    // e' stato aperto in modalita' "modifica anche l'avversario" (matchForOpponentEdit non nullo).
    // Rinomina un deck avversario OVUNQUE compaia: su tutte le partite di tutte le Stagioni (l'identita' del
    // nome e' globale, come i suggerimenti stessi), non solo nella Stagione corrente — cosi' un errore di
    // battitura fatto una volta ("Altaris" invece di "Altaria") si corregge una volta sola, per sempre,
    // invece di doverselo tenere a vita.
    void renameOpponentDeck(String oldName, String newName){
        if (newName==null) return;
        newName = newName.trim();
        if (newName.isEmpty() || newName.equals(oldName)) return; // equals (non equalsIgnoreCase): un cambio di
        // sole maiuscole/minuscole ("Altaria" -> "altaria") DEVE essere permesso, non e' un "nome uguale".
        String oldKey = oldName.toLowerCase(Locale.US);
        for (Season sn: store.seasons) for (Match m: sn.matches)
            if (m.opponentDeck!=null && m.opponentDeck.toLowerCase(Locale.US).equals(oldKey)) m.opponentDeck = newName;
        String finalNewName = newName;
        store.knownOpponentDecks.removeIf(k -> k.equalsIgnoreCase(oldName));
        if (!store.knownOpponentDecks.contains(finalNewName)) store.knownOpponentDecks.add(finalNewName);
        store.save(); view.invalidate();
    }

    // Dialog di rinomina: nome attuale precompilato, la conferma applica renameOpponentDeck() e ricostruisce
    // subito la lista (onRenamed) cosi' il cambiamento e' visibile immediatamente, senza dover riaprire nulla.
    void promptRenameOpponentDeck(String currentName, Runnable onRenamed){
        LinearLayout box = formBox();
        EditText input = new EditText(this);
        input.setText(currentName); input.setTextColor(Color.WHITE);
        input.setSelection(currentName.length());
        box.addView(input);
        applyMaxLength(box, input, 18);
        new AlertDialog.Builder(this).setTitle(getString(R.string.action_rename_opponent_deck))
            .setView(box)
            .setPositiveButton(getString(R.string.btn_confirm), (d,w) -> {
                renameOpponentDeck(currentName, input.getText().toString());
                if (onRenamed!=null) onRenamed.run();
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    void chooseCurrentDeck() {
        Season s = store.seasons.get(store.current);
        showDeckSelectorDialog(s, getString(R.string.dialog_select_deck_title), findDeck(s, s.currentDeck), chosen -> {
            s.currentDeck = chosen.name; store.save(); view.invalidate();
        });
    }

    // Cambia retroattivamente il deck (tuo e/o avversario) di una partita GIA' giocata — flusso a 2 fasi:
    // Fase 1 sceglie il tuo deck, Fase 2 (dopo "Continua") sceglie quello avversario. Le statistiche per deck
    // sono sempre calcolate al volo dai campi di ogni partita, quindi si aggiornano da sole.
    void changeMatchDeck(Match m) {
        Season s = store.seasons.get(store.current);
        int num = matchNumberOf(s, m);
        showMatchDeckPhase1(s, m, num, findDeck(s, m.deck));
    }

    // Fase 1: solo il TUO deck. Titolo piu' grande del solito (era 18, qui 22) per marcare che e' il titolo
    // di un intero step, non un mini-titolo di sezione. Footer Annulla/Continua: niente si salva finche' non
    // si conferma la Fase 2 — "Annulla" qui chiude tutto senza applicare nulla.
    void showMatchDeckPhase1(Season s, Match m, int num, Deck currentOwnSelection){
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        // Titolo generico (con il numero partita, cosi' e' chiaro DI QUALE partita si sta parlando) +
        // sottotitolo che dice sia DI CHE PASSO si tratta sia QUANTI passi ci sono in totale — prima il
        // titolo era solo "Il tuo deck", senza contesto su cosa si stesse facendo o quanti step mancassero.
        TextView title = new TextView(this); title.setText(getString(R.string.dialog_edit_match_deck_title,num)); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18);
        root.addView(title, titleLp);

        TextView subtitle = new TextView(this); subtitle.setText(getString(R.string.label_your_deck_step_subtitle)); subtitle.setTextColor(MUTED_TXT); subtitle.setTextSize(15);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin=dp(2); subtitleLp.leftMargin=dp(18); subtitleLp.rightMargin=dp(18);
        root.addView(subtitle, subtitleLp);

        Deck[] selected = buildOwnDeckPickerSection(root, s, currentOwnSelection, null);

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(6),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText(getString(R.string.btn_cancel)); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(16); cancelBtn.setAllCaps(true);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView continueBtn = new TextView(this); continueBtn.setText(getString(R.string.btn_continue)); continueBtn.setTextColor(blueColor()); continueBtn.setTextSize(16); continueBtn.setAllCaps(true);
        continueBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(cancelBtn); footer.addView(continueBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        continueBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showMatchDeckPhase2(s, m, num, selected[0]);
        });
    }

    // Fase 2: solo il deck AVVERSARIO. Footer Indietro/Conferma: "Indietro" torna alla Fase 1 mantenendo la
    // scelta gia' fatta li' (non un "Annulla" che butterebbe via tutto), "Conferma" salva ENTRAMBI i campi
    // in un colpo solo (il tuo deck scelto in Fase 1 + quello avversario scelto qui).
    void showMatchDeckPhase2(Season s, Match m, int num, Deck chosenOwnDeck){
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView title = new TextView(this); title.setText(getString(R.string.dialog_edit_match_deck_title,num)); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18);
        root.addView(title, titleLp);

        TextView subtitle = new TextView(this); subtitle.setText(getString(R.string.label_opponent_deck_step_subtitle)); subtitle.setTextColor(MUTED_TXT); subtitle.setTextSize(15);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin=dp(2); subtitleLp.leftMargin=dp(18); subtitleLp.rightMargin=dp(18);
        root.addView(subtitle, subtitleLp);

        String[] selectedOpp = buildOpponentDeckPickerSection(root, s, m.opponentDeck);

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(6),dp(14),dp(14));
        TextView backBtn = new TextView(this); backBtn.setText(getString(R.string.btn_back)); backBtn.setTextColor(MUTED_TXT); backBtn.setTextSize(16); backBtn.setAllCaps(true);
        backBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText(getString(R.string.btn_confirm)); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(16); confirmBtn.setAllCaps(true);
        confirmBtn.setPadding(dp(10),dp(6),0,dp(6));
        footer.addView(backBtn); footer.addView(confirmBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        backBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showMatchDeckPhase1(s, m, num, chosenOwnDeck);
        });
        confirmBtn.setOnClickListener(v -> {
            m.deck = chosenOwnDeck.name;
            String opp = selectedOpp[0];
            if (opp!=null && !opp.trim().isEmpty()){
                m.opponentDeck = opp;
                if (!store.knownOpponentDecks.contains(opp)) store.knownOpponentDecks.add(opp);
            } else {
                m.opponentDeck = null;
            }
            store.save(); view.invalidate();
            dialog.dismiss();
        });
    }

    void win() { play(true); }
    void loss() { play(false); }

    // Messaggi motivazionali a scomparsa dopo ogni partita registrata: vittoria con streak basso (1-2),
    // vittoria con streak alto (3+, "inarrestabile"), sconfitta.
    // Fascia dedicata alla PRIMISSIMA vittoria della serie (streak==1): frasi che parlano esplicitamente di
    // "inizio", non hanno senso ripetute a streak 2+.
    // I 6 pool sotto sono usati con pickMessage()/messagePoolQueues, che identifica ogni pool tramite
    // l'IDENTITA' dell'array stesso (non il contenuto) per ricordare quali frasi sono già state usate.
    // getResources().getStringArray() restituisce un array NUOVO ad ogni chiamata: se lo richiamassimo
    // ogni volta, ogni pool sembrerebbe "nuovo" e la logica anti-ripetizione si romperebbe. Caricati
    // quindi una sola volta (pigro, alla prima vittoria/sconfitta registrata) e mantenuti in cache.
    String[] winMsgsFirst, winMsgsLow, winMsgsHigh, lossMsgsLow, lossMsgsHigh, dayMsgsHot;
    void ensureMessagePools(){
        if (winMsgsFirst != null) return;
        winMsgsFirst = new String[]{getString(R.string.msgs_win_first_0), getString(R.string.msgs_win_first_1), getString(R.string.msgs_win_first_2), getString(R.string.msgs_win_first_3), getString(R.string.msgs_win_first_4)};
        winMsgsLow = new String[]{getString(R.string.msgs_win_low_0), getString(R.string.msgs_win_low_1), getString(R.string.msgs_win_low_2), getString(R.string.msgs_win_low_3), getString(R.string.msgs_win_low_4)};
        winMsgsHigh = new String[]{getString(R.string.msgs_win_high_0), getString(R.string.msgs_win_high_1), getString(R.string.msgs_win_high_2), getString(R.string.msgs_win_high_3), getString(R.string.msgs_win_high_4), getString(R.string.msgs_win_high_5), getString(R.string.msgs_win_high_6)};
        lossMsgsLow = new String[]{getString(R.string.msgs_loss_low_0), getString(R.string.msgs_loss_low_1), getString(R.string.msgs_loss_low_2), getString(R.string.msgs_loss_low_3), getString(R.string.msgs_loss_low_4), getString(R.string.msgs_loss_low_5), getString(R.string.msgs_loss_low_6), getString(R.string.msgs_loss_low_7), getString(R.string.msgs_loss_low_8), getString(R.string.msgs_loss_low_9)};
        lossMsgsHigh = new String[]{getString(R.string.msgs_loss_high_0), getString(R.string.msgs_loss_high_1), getString(R.string.msgs_loss_high_2), getString(R.string.msgs_loss_high_3), getString(R.string.msgs_loss_high_4), getString(R.string.msgs_loss_high_5), getString(R.string.msgs_loss_high_6)};
        dayMsgsHot = new String[]{getString(R.string.msgs_day_hot_0), getString(R.string.msgs_day_hot_1), getString(R.string.msgs_day_hot_2), getString(R.string.msgs_day_hot_3), getString(R.string.msgs_day_hot_4), getString(R.string.msgs_day_hot_5)};
    }
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
        ensureMessagePools();
        // "PENTAKILL!" a 5 vittorie consecutive ESATTE: ha la precedenza su tutto il resto (messaggio del
        // giorno, pool normale), e' un traguardo preciso, non casuale — stessa parola in ogni lingua.
        if (win && streak==5) {
            Toast.makeText(this, getString(R.string.msg_pentakill), Toast.LENGTH_SHORT).show();
            return;
        }
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
                Toast.makeText(this, pickMessage(dayMsgsHot), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String[] pool;
        if(win) pool = (streak==1) ? winMsgsFirst : (streak>=3 ? winMsgsHigh : winMsgsLow);
        else pool = streak>=3 ? lossMsgsHigh : lossMsgsLow;
        String msg;
        // "Distruggili tutti, NOME!" solo per streak alte (3+) e nome conosciuto, occasionalmente — il nome
        // e' parte della frase stessa, non solo anteposto come nel caso generico sotto.
        if (win && streak>=3 && !store.trainerName.isEmpty() && new java.util.Random().nextInt(4)==0) {
            msg = getString(R.string.msg_destroy_them_all,store.trainerName);
        } else {
            msg = pickMessage(pool);
            // Se conosciamo il nome dell'allenatore, ogni tanto (non sempre, per non risultare ripetitivo)
            // personalizza il messaggio col nome — a volte anteponendolo ("Marco, continua così!"), a
            // volte in coda ("Continua così, Marco!"): sempre davanti suonava meno naturale, un po' ripetitivo.
            if (!store.trainerName.isEmpty() && new java.util.Random().nextInt(3)==0) {
                if (new java.util.Random().nextBoolean()) {
                    char last = msg.charAt(msg.length()-1);
                    if (last=='!'||last=='.'||last=='?') msg = msg.substring(0,msg.length()-1)+", "+store.trainerName+last;
                    else msg = msg+", "+store.trainerName;
                } else {
                    msg = store.trainerName+", "+Character.toLowerCase(msg.charAt(0))+msg.substring(1);
                }
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
        // Dopo la partita MAI mostrato-prima: propone il tracciamento del deck avversario. Una tantum — non
        // si ripresenta mai piu' dopo questa singola occasione. IMPORTANTE: niente piu' controllo "solo se e'
        // la primissima partita in assoluto" — chi aveva gia' uno storico di partite prima di questo
        // aggiornamento non avrebbe mai visto il popup (il conteggio totale sarebbe sempre stato >1). Ora
        // scatta semplicemente alla prossima partita loggata da chiunque non l'abbia ancora visto, nuovo
        // utente o gia' esistente che sia.
        if (!store.firstMatchTipShown) {
            showOpponentDeckTip(m);
        } else if (store.trackOpponentDeck) {
            showOpponentDeckPicker(m, false);
        }
    }

    // Popup "Lo sapevi?", mostrato una tantum dopo la primissima partita mai registrata: spiega che si puo'
    // tracciare anche il deck avversario. Se accettato, la preferenza si accende E si mostra SUBITO il vero
    // popup di scelta deck per la partita appena giocata — una dimostrazione dal vivo, non solo a parole, e
    // recupera anche il dato di quella primissima partita invece di perderlo per sempre.
    void showOpponentDeckTip(Match m){
        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_opponent_tip_title))
            .setMessage(getString(R.string.dialog_opponent_tip_body))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.btn_no_thanks), (d,w) -> {
                store.firstMatchTipShown = true; store.save();
            })
            .setPositiveButton(getString(R.string.btn_yes_enable), (d,w) -> {
                store.trackOpponentDeck = true; store.firstMatchTipShown = true; store.save();
                showOpponentDeckPicker(m, true);
            })
            .show();
    }

    // Popup di scelta del deck avversario per una specifica partita — sempre facoltativo/saltabile (pulsante
    // "Salta"), mostrato dopo OGNI partita una volta che store.trackOpponentDeck e' attivo. I nomi gia' usati
    // in passato appaiono come "chip" toccabili sopra il campo di testo, per non dover ridigitare ogni volta
    // lo stesso nome di deck avversario incontrato piu' volte.
    // Costruisce la sezione di scelta deck avversario (ricerca + lista di card statistiche — SOLO nome e
    // numeri, niente anteprima grafica: decisione presa esplicitamente, gli avversari non hanno una vera
    // "identita' visiva" nell'app) dentro il LinearLayout dato. Restituisce il campo di ricerca/nome: il
    // suo testo al momento della conferma E' il nome scelto (digitato o riempito toccando una card).
    // Elimina un deck avversario: stesso identico pattern usato per i tuoi deck (confirmDeleteDeck) — conteggio
    // partite di QUESTA Stagione, avviso singolare/plurale, il campo torna vuoto (non "Sconosciuto" come per
    // i tuoi, dato che qui il tracciamento e' facoltativo: vuoto = "non tracciato", non un valore segnaposto).
    void confirmDeleteOpponentDeck(Season s, String name, Runnable onChanged){
        int usedCount = 0;
        String key = name.toLowerCase(Locale.US);
        for (Match m: s.matches) if (m.opponentDeck!=null && m.opponentDeck.toLowerCase(Locale.US).equals(key)) usedCount++;
        String message = usedCount>0
            ? getString(usedCount==1 ? R.string.confirm_delete_opponent_deck_used_singular : R.string.confirm_delete_opponent_deck_used_plural, usedCount)
            : getString(R.string.confirm_delete_opponent_deck_msg);
        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_delete_deck_title_fmt, name))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_delete), (dlg,w) -> {
                for (Match m: s.matches) if (m.opponentDeck!=null && m.opponentDeck.toLowerCase(Locale.US).equals(key)) m.opponentDeck = null;
                store.knownOpponentDecks.removeIf(k -> k.equalsIgnoreCase(name));
                store.save(); if (view!=null) view.invalidate();
                if (onChanged!=null) onChanged.run();
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    // Menu "⋮" per una card deck avversario — stesse regole di apertura di showDeckRowMenu (ancorato alla
    // view toccata, via showDialogMenu): Rinomina / Elimina.
    void showOpponentDeckRowMenu(Season s, String name, View anchorView, Runnable onChanged){
        showDialogMenu(anchorView,
            new String[]{getString(R.string.action_rename_opponent_deck), getString(R.string.action_delete_opponent_deck)},
            new int[]{Color.WHITE, red()},
            new Runnable[]{ () -> promptRenameOpponentDeck(name, onChanged), () -> confirmDeleteOpponentDeck(s, name, onChanged) });
    }

    // Prompt minimo per creare un deck avversario nuovo: solo il nome (niente stile/colore/finitura, decisione
    // presa esplicitamente — un avversario non ha una vera identita' visiva nell'app).
    void promptNewOpponentDeck(java.util.function.Consumer<String> onCreated){
        LinearLayout box = formBox();
        EditText input = new EditText(this);
        input.setHint(getString(R.string.hint_opponent_deck));
        input.setTextColor(Color.WHITE); input.setHintTextColor(MUTED_TXT);
        box.addView(input);
        applyMaxLength(box, input, 18);
        new AlertDialog.Builder(this).setTitle(getString(R.string.btn_new_deck))
            .setView(box)
            .setPositiveButton(getString(R.string.btn_confirm), (d,w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) onCreated.accept(name);
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    boolean listContainsIgnoreCase(ArrayList<String> list, String val){
        for (String x: list) if (x.equalsIgnoreCase(val)) return true;
        return false;
    }

    // Componente riusabile per il deck AVVERSARIO — stessa fisionomia della Fase 1 (vera barra di ricerca,
    // non piu' un campo di testo che fa doppio uso da ricerca+valore): lista di card statistiche (solo nome,
    // niente anteprima grafica), selezione evidenziata con un bordo, menu "⋮" per rinomina/elimina, "Nuovo
    // Deck" con un prompt dedicato invece di dover digitare-e-sperare nel campo di ricerca.
    String[] buildOpponentDeckPickerSection(LinearLayout parent, Season s, String initialSelection){
        String[] selected = { initialSelection };
        java.util.LinkedHashMap<String,int[]> stats = view.opponentDeckStats(s);
        ArrayList<String> allNames = new ArrayList<>(stats.keySet());
        for (String k: store.knownOpponentDecks) if (!listContainsIgnoreCase(allNames,k)) allNames.add(k);
        for (Season sn: store.seasons) for (Deck d: sn.decks) if (!listContainsIgnoreCase(allNames,d.name)) allNames.add(d.name);
        ArrayList<String> filtered = new ArrayList<>(allNames);

        LinearLayout searchBar = new LinearLayout(this); searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable(); searchBg.setColor(Color.rgb(10,18,30)); searchBg.setCornerRadius(dp(16));
        searchBar.setBackground(searchBg);
        ImageView searchIcon = new ImageView(this); searchIcon.setImageBitmap(makeSearchIcon(Color.WHITE, dp(16)));
        searchIcon.setPadding(dp(12),dp(8),dp(6),dp(8));
        searchBar.addView(searchIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText searchInput = new EditText(this); searchInput.setSingleLine(); searchInput.setBackground(null);
        searchInput.setTextColor(Color.WHITE); searchInput.setHintTextColor(MUTED_TXT); searchInput.setHint(getString(R.string.hint_search_deck)); searchInput.setTextSize(14);
        searchInput.setPadding(0,0,0,0);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView clearBtn = new TextView(this); clearBtn.setText("✕"); clearBtn.setTextColor(MUTED_TXT); clearBtn.setGravity(Gravity.CENTER); clearBtn.setTextSize(13);
        GradientDrawable clearCircle = new GradientDrawable(); clearCircle.setShape(GradientDrawable.OVAL); clearCircle.setColor(Color.rgb(24,36,52));
        clearBtn.setBackground(clearCircle); clearBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(22), dp(22)); clearLp.leftMargin=dp(6); clearLp.rightMargin=dp(6);
        searchBar.addView(clearBtn, clearLp);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        searchLp.topMargin=dp(14); searchLp.leftMargin=dp(18); searchLp.rightMargin=dp(18); searchLp.bottomMargin=dp(10);
        parent.addView(searchBar, searchLp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,dp(4),0,dp(4));
        scroll.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400));
        parent.addView(scroll, scrollLp);

        int rowH = 95; // stessa altezza (92+3 per lo stroke di selezione) delle card dei tuoi deck

        Runnable[] rebuildList = new Runnable[1];
        Runnable[] refreshFromSource = new Runnable[1];
        rebuildList[0] = () -> {
            list.removeAllViews();
            for (String name: filtered) {
                int[] st = stats.get(name);
                int times = st!=null ? st[0] : 0, w2 = st!=null ? st[1] : 0, l2 = st!=null ? st[2] : 0;
                android.widget.FrameLayout row = new android.widget.FrameLayout(this);
                OpponentDeckCardRowView cardView = new OpponentDeckCardRowView(this, name, times, w2, l2);
                cardView.selected = name.equalsIgnoreCase(selected[0]);
                row.addView(cardView, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(95)));
                // Stessa identica zona di tocco del kebab "⋮" usata per i tuoi deck (stessa formula di
                // disegno w-18-10-8,y+22 in opponentDeckCardVisual/deckCardVisual: la card e' identica).
                View kebabHotspot = new View(this);
                android.widget.FrameLayout.LayoutParams khLp = new android.widget.FrameLayout.LayoutParams(dp(44), dp(44));
                khLp.gravity = Gravity.TOP|Gravity.END; khLp.rightMargin = dp(14); khLp.topMargin = dp(1.5f);
                row.addView(kebabHotspot, khLp);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(95));
                rowLp.bottomMargin = dp(10);
                list.addView(row, rowLp);
                cardView.setOnClickListener(v -> { selected[0]=name; rebuildList[0].run(); });
                kebabHotspot.setOnClickListener(v -> showOpponentDeckRowMenu(s, name, kebabHotspot, refreshFromSource[0]));
            }
            int rowHeightPx = dp(rowH)+dp(10);
            int wantedHeight = filtered.size()*rowHeightPx;
            scrollLp.height = Math.min(wantedHeight, dp(400));
            scroll.setLayoutParams(scrollLp);
        };
        rebuildList[0].run();

        if (selected[0]!=null) {
            int idx = -1;
            for (int i=0;i<filtered.size();i++) if (filtered.get(i).equalsIgnoreCase(selected[0])) { idx=i; break; }
            if (idx>=0) {
                int rp = dp(rowH)+dp(10);
                int targetY = Math.max(0, idx*rp - dp(10));
                scroll.post(() -> scroll.scrollTo(0, targetY));
            }
        }

        Runnable[] doFilter = new Runnable[1];
        doFilter[0] = () -> {
            String q = searchInput.getText().toString().trim().toLowerCase(Locale.ITALY);
            filtered.clear();
            for (String name: allNames) if (q.isEmpty() || name.toLowerCase(Locale.ITALY).contains(q)) filtered.add(name);
            rebuildList[0].run();
        };
        refreshFromSource[0] = () -> {
            java.util.LinkedHashMap<String,int[]> freshStats = view.opponentDeckStats(s);
            stats.clear(); stats.putAll(freshStats);
            allNames.clear(); allNames.addAll(freshStats.keySet());
            for (String k: store.knownOpponentDecks) if (!listContainsIgnoreCase(allNames,k)) allNames.add(k);
            for (Season sn: store.seasons) for (Deck d: sn.decks) if (!listContainsIgnoreCase(allNames,d.name)) allNames.add(d.name);
            if (selected[0]!=null && !listContainsIgnoreCase(allNames, selected[0])) selected[0] = null;
            doFilter[0].run();
        };
        clearBtn.setOnClickListener(v -> { searchInput.setText(""); doFilter[0].run(); });
        searchInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s2,int a,int b,int c){}
            public void onTextChanged(CharSequence s2,int a,int b,int c){ clearBtn.setVisibility(s2.length()>0?View.VISIBLE:View.GONE); }
            public void afterTextChanged(android.text.Editable s2){}
        });
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

        Button newDeckBtn = new Button(this); newDeckBtn.setText(getString(R.string.btn_new_deck)); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newBtnLp.topMargin=dp(4); newBtnLp.leftMargin=dp(19); newBtnLp.rightMargin=dp(19); newBtnLp.bottomMargin=dp(10); // 19 non 18: compensa il rigonfiamento visivo dello stroke (pill() ne aggiunge 1dp, che sporge leggermente oltre i bordi geometrici) rispetto a card/barra ricerca senza alcuno stroke
        parent.addView(newDeckBtn, newBtnLp);
        newDeckBtn.setOnClickListener(v -> promptNewOpponentDeck(newName -> {
            selected[0] = newName;
            if (!store.knownOpponentDecks.contains(newName)) store.knownOpponentDecks.add(newName);
            refreshFromSource[0].run();
        }));

        return selected;
    }

    void showOpponentDeckPicker(Match m, boolean showSettingsHint){
        Season s = store.seasons.get(store.current);
        LinearLayout box = formBox();
        String[] selected = buildOpponentDeckPickerSection(box, s, m.opponentDeck);

        if (showSettingsHint){
            TextView hint = new TextView(this); hint.setText(getString(R.string.hint_disable_in_settings)); hint.setTextColor(MUTED_TXT); hint.setTextSize(12);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = dp(14);
            box.addView(hint, hintLp);
        }

        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_opponent_deck_title))
            .setView(box)
            .setCancelable(true)
            .setNegativeButton(getString(R.string.btn_skip), null)
            .setNeutralButton(getString(R.string.btn_dont_ask_again), (d,w) -> {
                // Disattiva l'impostazione (non verra' piu' chiesto dopo ogni partita) e mostra un dialog
                // informativo che ricorda dove riattivarla, riusando lo stesso messaggio gia' visto la prima
                // volta che si e' attivata questa funzione (hint_disable_in_settings).
                store.trackOpponentDeck = false; store.save();
                showOpponentTrackingDisabledInfoDialog();
            })
            .setPositiveButton(getString(R.string.btn_confirm), (d,w) -> {
                if (selected[0]!=null && !selected[0].trim().isEmpty()){
                    m.opponentDeck = selected[0];
                    if (!store.knownOpponentDecks.contains(selected[0])) store.knownOpponentDecks.add(selected[0]);
                    store.save(); view.invalidate();
                }
            })
            .show();
    }

    // Dialog informativo mostrato dopo aver scelto "Non chiedermelo più": conferma che il tracciamento e'
    // stato disattivato e ricorda dove riattivarlo — un solo pulsante, nessuna azione ulteriore da fare.
    void showOpponentTrackingDisabledInfoDialog(){
        new AlertDialog.Builder(this).setTitle(getString(R.string.label_opponent_tracking_disabled_title))
            .setMessage(getString(R.string.label_opponent_tracking_disabled_msg))
            .setPositiveButton(getString(R.string.btn_got_it), null)
            .show();
    }

    // Dialog di filtro Matchup: 2 colonne (i tuoi deck / avversari), ognuna con pillole a scelta multipla +
    // "Tutti"/"Nessuno" rapidi. "Continua" richiede almeno 1 selezionato per lato (0 non avrebbe senso: la
    // lista sparirebbe del tutto) — altrimenti mostra un avviso e resta aperto.
    // Dialog di filtro Matchup PER LATO (non piu' un unico dialog a 2 colonne): titolo specifico che dice
    // chiaramente di cosa si tratta ("Filtra: i tuoi deck" / "Filtra: gli avversari"), aperto dall'icona
    // filtro corrispondente nell'header della sezione Matchup.
    void showMatchupFilterDialogForSide(Season s, boolean isMyDeckSide, Runnable onApplied){
        ArrayList<String> names;
        java.util.HashSet<String> current;
        String subtitle;
        if (isMyDeckSide) {
            names = new ArrayList<>(); for (Deck d: s.decks) names.add(d.name);
            current = view.matchupMyDeckFilter;
            subtitle = getString(R.string.label_your_decks_subtitle);
        } else {
            names = new ArrayList<>(view.opponentDeckStats(s).keySet());
            current = view.matchupOppDeckFilter;
            subtitle = getString(R.string.label_opponent_decks_subtitle);
        }
        // Pillole ordinate alfabeticamente (A-Z), non piu' nell'ordine "di apparizione" (creazione deck /
        // prima volta incontrato come avversario) — piu' facile trovare un nome specifico in una lista lunga.
        names.sort(String.CASE_INSENSITIVE_ORDER);
        java.util.HashSet<String> temp = current!=null ? new java.util.HashSet<>(current) : new java.util.HashSet<>(names);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        // Titolo sempre generico "Filtro" (non piu' "Filtra: i tuoi deck"/"Filtra: gli avversari" — poco
        // professionale): il SOTTOTITOLO sotto dice di quale lato si tratta, con una dimensione leggibile
        // (15sp, non piu' piccola di un'etichetta qualsiasi).
        TextView title = new TextView(this); title.setText(getString(R.string.dialog_filter_title)); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(18); titleLp.leftMargin=dp(18); titleLp.rightMargin=dp(18);
        root.addView(title, titleLp);

        TextView subtitleView = new TextView(this); subtitleView.setText(subtitle); subtitleView.setTextColor(MUTED_TXT); subtitleView.setTextSize(15);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin=dp(2); subtitleLp.leftMargin=dp(18); subtitleLp.rightMargin=dp(18);
        root.addView(subtitleView, subtitleLp);

        // "Tutti"/"Nessuno" rapidi, sopra un contenitore ben distinto (bordo+sfondo proprio) per le
        // pillole — prima galleggiavano senza nessuna struttura visiva attorno.
        LinearLayout quickRow = new LinearLayout(this); quickRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView allBtn = new TextView(this); allBtn.setText(getString(R.string.btn_select_all)); allBtn.setTextColor(blueColor()); allBtn.setTextSize(14); allBtn.setTypeface(Typeface.DEFAULT_BOLD);
        TextView noneBtn = new TextView(this); noneBtn.setText(getString(R.string.btn_select_none)); noneBtn.setTextColor(blueColor()); noneBtn.setTextSize(14); noneBtn.setTypeface(Typeface.DEFAULT_BOLD);
        noneBtn.setPadding(dp(18),0,0,0);
        quickRow.addView(allBtn); quickRow.addView(noneBtn);
        LinearLayout.LayoutParams quickRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        quickRowLp.topMargin=dp(14); quickRowLp.leftMargin=dp(18); quickRowLp.bottomMargin=dp(10);
        root.addView(quickRow, quickRowLp);

        LinearLayout pillsContainer = new LinearLayout(this); pillsContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pillsBg = new GradientDrawable(); pillsBg.setCornerRadius(dp(10)); pillsBg.setColor(Color.rgb(10,18,30));
        pillsContainer.setBackground(pillsBg);
        pillsContainer.setPadding(dp(12),dp(10),dp(12),dp(10));
        ScrollView scroll = new ScrollView(this);
        LinearLayout pillList = new LinearLayout(this); pillList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(pillList);
        pillsContainer.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        LinearLayout.LayoutParams pillsContainerLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillsContainerLp.leftMargin=dp(18); pillsContainerLp.rightMargin=dp(18);
        root.addView(pillsContainer, pillsContainerLp);

        TextView[] pills = new TextView[names.size()];
        Runnable[] refreshPills = new Runnable[1];
        refreshPills[0] = () -> {
            for (int i=0;i<names.size();i++){
                boolean sel = temp.contains(names.get(i));
                // Niente piu' riempimento blu pieno per la selezione (troppo "urlato"): stesso principio
                // usato per le card deck selezionate — nessun riempimento, solo uno stroke crema. Non
                // selezionata: stroke sottile e discreto, sfondo sempre trasparente (lo sfondo uniforme lo
                // da' il contenitore, non ogni singola pillola).
                GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16)); bg.setColor(Color.TRANSPARENT);
                bg.setStroke(dp(sel?2:1), sel?Color.argb(191,255,250,235):Color.rgb(45,60,85));
                pills[i].setBackground(bg);
                pills[i].setTextColor(sel?Color.WHITE:MUTED_TXT);
            }
        };
        for (int i=0;i<names.size();i++){
            String name = names.get(i);
            TextView pill = new TextView(this); pill.setText(name); pill.setTextSize(14);
            pill.setPadding(dp(16),dp(9),dp(16),dp(9));
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // Margine tra una pillola e la successiva SOLO se non e' l'ultima: altrimenti si sommava al
            // padding inferiore del contenitore (8+10), dando piu' spazio in fondo che tra le pillole stesse.
            if (i<names.size()-1) pillLp.bottomMargin=dp(8);
            pillList.addView(pill, pillLp);
            pills[i]=pill;
            pill.setOnClickListener(v -> {
                if (temp.contains(name)) temp.remove(name); else temp.add(name);
                refreshPills[0].run();
            });
        }
        refreshPills[0].run();
        allBtn.setOnClickListener(v -> { temp.clear(); temp.addAll(names); refreshPills[0].run(); });
        noneBtn.setOnClickListener(v -> { temp.clear(); refreshPills[0].run(); });

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(12),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText(getString(R.string.btn_cancel)); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(16); cancelBtn.setAllCaps(true);
        cancelBtn.setPadding(dp(12),dp(8),dp(12),dp(8));
        TextView continueBtn = new TextView(this); continueBtn.setText(getString(R.string.btn_continue)); continueBtn.setTextColor(blueColor()); continueBtn.setTextSize(16); continueBtn.setTypeface(Typeface.DEFAULT_BOLD); continueBtn.setAllCaps(true);
        continueBtn.setPadding(dp(12),dp(8),0,dp(8));
        footer.addView(cancelBtn); footer.addView(continueBtn);
        root.addView(footer);

        Dialog dialog = new Dialog(this, R.style.PocketDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        continueBtn.setOnClickListener(v -> {
            if (temp.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_select_at_least_one), Toast.LENGTH_SHORT).show();
                return;
            }
            if (isMyDeckSide) view.matchupMyDeckFilter = temp; else view.matchupOppDeckFilter = temp;
            dialog.dismiss();
            onApplied.run();
        });
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
        if (s.matches.isEmpty()) { Toast.makeText(this,getString(R.string.msg_no_match_to_undo),Toast.LENGTH_SHORT).show(); return; }
        Match last = s.matches.get(s.matches.size()-1);
        String title, message, button;
        if (last.unknown) {
            // Una correzione: nessun senso parlare di "registrare di nuovo con W o L", non e' una partita.
            title = getString(R.string.dialog_undo_correction_msg);
            message = getString(R.string.msg_correction_will_be_deleted);
            button = getString(R.string.dialog_undo_correction_title);
        } else {
            // Sappiamo gia' se era una vittoria o una sconfitta: lo diciamo esplicitamente invece del generico
            // "registra di nuovo con W o L".
            String outcome = last.win ? getString(R.string.label_outcome_win) : getString(R.string.label_outcome_loss);
            title = getString(R.string.dialog_undo_last_match_title);
            message = getString(R.string.confirm_undo_match_msg,outcome);
            button = getString(R.string.dialog_undo_match_title);
        }
        new AlertDialog.Builder(this).setTitle(title)
            .setMessage(message)
            .setPositiveButton(button, (d,w) -> {
                s.matches.remove(s.matches.size()-1);
                recomputeSeasonState(s);
                store.save(); view.invalidate();
            })
            .setNegativeButton(getString(R.string.btn_close), null)
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
        box.addView(label(getString(R.string.label_current_points_title))); box.addView(p);
        box.addView(label(getString(R.string.label_current_streak))); box.addView(st);
        box.addView(label(getString(R.string.label_total_wins))); box.addView(wf);
        box.addView(label(getString(R.string.label_total_losses))); box.addView(lf);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.action_add_correction))
            .setMessage(getString(R.string.info_manual_correction_help))
            .setView(box).setPositiveButton(getString(R.string.btn_confirm), null).setNegativeButton(getString(R.string.btn_cancel), null).create();
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
        }, getString(R.string.err_invalid_totals));
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

    // Applica un limite di caratteri a un campo (impedisce di digitarne oltre, invece di validare dopo) e
    // aggiunge un piccolo suggerimento sotto che lo dichiara, subito dopo il campo nel box del dialog.
    void applyMaxLength(LinearLayout box, EditText field, int maxLen){
        field.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(maxLen) });
        TextView hint = new TextView(this); hint.setText(getString(R.string.hint_max_chars, maxLen));
        hint.setTextColor(MUTED_TXT); hint.setTextSize(11);
        hint.setPadding(0,dp(4),0,0);
        box.addView(hint);
    }
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
        if (ts<=0) return getString(R.string.label_unknown_date);
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
        new AlertDialog.Builder(this).setTitle(getString(R.string.confirm_delete_all_title))
            .setMessage(getString(R.string.confirm_delete_all_msg))
            .setPositiveButton(getString(R.string.action_delete_all), (d,w) -> {
                store.seasons.clear();
                store.current = 0;
                store.save();
                if (view != null) view.invalidate();
                wizardStep1(true, null);
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    void renameSeason(){
        Season s=store.seasons.get(store.current);
        LinearLayout box = formBox();
        TextView header = new TextView(this);
        header.setText(getString(R.string.dialog_rename_season_title)); header.setTextColor(Color.WHITE); header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0,dp(10),0,dp(14));
        box.addView(header);
        EditText e=field(s.name); e.setText(s.name);
        box.addView(e);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
            .setPositiveButton(getString(R.string.btn_save), null).setNegativeButton(getString(R.string.btn_cancel), null).create();
        showNonDismissing(dialog, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return false;
            s.name = n; store.save(); view.invalidate();
            return true;
        }, getString(R.string.err_season_name_empty));
        dialog.show();
    }

    // Elimina un deck: se e' usato in una o piu' partite, avvisa prima e, se confermato, imposta quelle
    // partite su getString(R.string.label_unknown_deck) (Unknown) invece di lasciarle con un riferimento a un deck inesistente.
    // Menu "⋮" della card di un deck: rinomina, aggiungi immagine, elimina (in rosso, sempre con conferma).
    void deckActionsMenu(Season s, Deck d, float rightEdgeX, float anchorY){
        view.showAnchoredMenu(rightEdgeX, anchorY,
            new String[]{getString(R.string.action_rename_deck),getString(R.string.action_choose_preview),getString(R.string.action_add_lista),getString(R.string.action_delete_deck)},
            new int[]{Color.WHITE, Color.WHITE, Color.WHITE, red()},
            new Runnable[]{ () -> renameDeckDialog(d), () -> showPreviewPicker(d), () -> openDeckImages(d), () -> confirmDeleteDeck(s,d) });
    }

    // Menu "⋮" delle card Stagione (lista principale): rinomina o elimina.
    void seasonActionsMenu(int idx, float rightEdgeX, float anchorY){
        Season s = store.seasons.get(idx);
        view.showAnchoredMenu(rightEdgeX, anchorY,
            new String[]{getString(R.string.dialog_rename_season_title),getString(R.string.action_delete_season)},
            new int[]{Color.WHITE, red()},
            new Runnable[]{ () -> { store.current = idx; renameSeason(); }, () -> confirmDeleteSeason(idx) });
    }

    void confirmDeleteSeason(int idx){
        Season s = store.seasons.get(idx);
        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_delete_season_title_fmt, s.name))
            .setMessage(getString(R.string.confirm_delete_season_msg, s.matches.size()))
            .setPositiveButton(getString(R.string.btn_delete), (dlg,w) -> {
                store.seasons.remove(idx);
                if (store.current>=store.seasons.size()) store.current = Math.max(0, store.seasons.size()-1);
                else if (store.current>idx) store.current--;
                store.save(); view.invalidate();
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show();
    }

    void confirmDeleteDeck(Season s, Deck d){ confirmDeleteDeck(s, d, null); }

    // onChanged (opzionale): richiamato a eliminazione avvenuta — nel dialog getString(R.string.action_change_deck) serve per togliere
    // subito la riga dalla lista, non solo dal tab Deck vero.
    void confirmDeleteDeck(Season s, Deck d, Runnable onChanged){
        int usedCount = 0;
        for (Match m: s.matches) if (d.name.equals(m.deck)) usedCount++;
        String message = usedCount>0
            ? getString(usedCount==1 ? R.string.confirm_delete_deck_used_singular : R.string.confirm_delete_deck_used_plural, usedCount)
            : getString(R.string.confirm_delete_deck_msg);
        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_delete_deck_title_fmt, d.name))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_delete), (dlg,w)-> {
                for (Match m: s.matches) if (d.name.equals(m.deck)) m.deck = "Unknown";
                if (d.name.equals(s.currentDeck)) s.currentDeck = "Unknown";
                s.decks.remove(d);
                store.save(); if (view!=null) view.invalidate();
                if (onChanged!=null) onChanged.run();
            })
            .setNegativeButton(getString(R.string.btn_cancel), null)
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
    // dialog getString(R.string.action_change_deck) per aggiornare la lista e selezionare subito il nuovo deck.
    void addDeck(java.util.function.Consumer<Deck> onCreated){
        Season s=store.seasons.get(store.current); LinearLayout box=formBox();
        // Deck "in sospeso": non ancora creato/salvato, serve solo per tenere lo stile/colore scelto
        // nell'anteprima finche' il salvataggio non lo trasferisce sul Deck vero.
        Deck pendingDeck = new Deck("");
        pendingDeck.previewStyle = store.preferredCardStyle; // parte dallo stile preferito, non sempre "spine"
        pendingDeck.previewFinish = store.preferredCardFinish; // idem per la finitura, non sempre "glossy"
        // true solo se l'utente ha esplicitamente confermato una scelta in "Scegli anteprima" per QUESTO
        // deck: da quel momento in poi la digitazione del nome non sovrascrive piu' la sua scelta manuale
        // con un'eventuale corrispondenza nella memoria di aspetto (rispetta l'intento esplicito).
        boolean[] styleManuallyChosen = {false};

        DeckPreviewThumbView thumb = new DeckPreviewThumbView(this, pendingDeck);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(dp(64), dp(80));
        FrameLayout thumbFrame = new FrameLayout(this); thumbFrame.addView(thumb, thumbLp);
        LinearLayout.LayoutParams thumbBoxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thumbBoxLp.gravity = Gravity.CENTER_HORIZONTAL; thumbBoxLp.bottomMargin = dp(14);
        box.addView(thumbFrame, thumbBoxLp);
        thumb.setOnClickListener(v -> showPreviewPicker(pendingDeck, () -> { styleManuallyChosen[0]=true; thumb.invalidate(); }));

        // Tolta l'etichetta getString(R.string.hint_deck_name) sopra il campo: il titolo del dialog e' gia' "Nuovo Deck" e il campo
        // ha comunque il placeholder getString(R.string.hint_deck_name) — prima la scritta compariva 3 volte, troppa ripetizione.
        EditText e=field(getString(R.string.hint_deck_name)); box.addView(e);
        applyMaxLength(box, e, 18);
        // Memoria di aspetto: se il nome digitato corrisponde a un deck gia' usato in passato (in
        // qualunque Stagione) e l'utente non ha nel frattempo scelto manualmente uno stile per QUESTO
        // deck, l'anteprima si aggiorna da sola con l'aspetto ricordato — cosi' non serve ridisegnare da
        // zero un deck che hai gia' definito prima.
        e.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence cs,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence cs,int a,int b,int c){}
            @Override public void afterTextChanged(android.text.Editable ed){
                if (styleManuallyChosen[0]) return;
                String typed = ed.toString().trim();
                String[] remembered = store.deckAppearanceMemory.get(typed);
                if (remembered!=null){
                    pendingDeck.previewStyle=remembered[0]; pendingDeck.previewColor=remembered[1]; pendingDeck.previewFinish=remembered[2];
                    thumb.invalidate();
                }
            }
        });
        Button img=new Button(this); img.setText(getString(R.string.action_add_lista_optional)); styleSecondaryButton(img);
        // Margine e larghezza piena come negli altri dialog (prima il pulsante era attaccato al campo sopra,
        // senza respiro, e piu' stretto del contenuto — risultava piu' "povero" rispetto al dialog Nuova Sessione.
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        imgLp.topMargin = dp(14); img.setLayoutParams(imgLp);
        box.addView(img);
        img.setOnClickListener(v-> pickImageFor(null)); // null = immagine "in sospeso", verra' assegnata al Deck solo se il salvataggio va a buon fine
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.btn_new_deck)).setView(box)
            .setPositiveButton(getString(R.string.btn_confirm), null).setNegativeButton(getString(R.string.btn_cancel), null).create();
        showNonDismissing(dialog, () -> {
            String n=e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            Deck deck=new Deck(n);
            // Trasferisce sul Deck vero l'anteprima scelta sul Deck "in sospeso" (finitura inclusa: prima
            // mancava, un deck nuovo perdeva sempre la finitura scelta e tornava a "glossy" di default).
            deck.previewStyle = pendingDeck.previewStyle; deck.previewColor = pendingDeck.previewColor; deck.previewFinish = pendingDeck.previewFinish;
            // Aggiorna la memoria di aspetto per questo nome, cosi' la prossima volta (anche in un'altra
            // Stagione) l'aspetto si ricorda da solo.
            store.deckAppearanceMemory.put(n, new String[]{deck.previewStyle, deck.previewColor, deck.previewFinish});
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
        }, getString(R.string.err_deck_name_invalid));
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
        try { startActivityForResult(i,101); } catch(Exception ex) { Toast.makeText(this,getString(R.string.err_no_app_for_lista),Toast.LENGTH_SHORT).show(); }
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

    // Cella della griglia nel dialog getString(R.string.action_choose_preview): disegna una card preimpostata (stile+colore
    // correnti) e, se selezionata, il bordo arancione — stesso arancione usato per la card Stagione attuale.
    class PreviewSwatchView extends View {
        String style; String colorKey; boolean selected=false; boolean glossy=true;
        PreviewSwatchView(Context c, String style, String colorKey){ super(c); this.style=style; this.colorKey=colorKey; }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float pad = dp(4);
            drawPresetPreviewCard(c, pad, pad, getWidth()-pad, getHeight()-pad, style, colorKey, glossy);
            if (selected) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3));
                p.setColor(Color.argb(170,255,250,235)); // crema, stesso colore dello stroke del ventaglio nello screen iniziale
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
        // Stessa rete di sicurezza usata in showPreviewPicker: se la preferenza salvata e' una chiave di
        // stile ormai storica (rinominata nel tempo), nessuna tab corrisponderebbe piu' a nessuna di quelle
        // attuali, e nessuna risulterebbe selezionata all'apertura.
        String[] styleKeysCheck = {"spine","gem","crescent","waves","sun","zigzag"};
        String initialStyle = java.util.Arrays.asList(styleKeysCheck).contains(store.preferredCardStyle) ? store.preferredCardStyle : "spine";
        String[] selectedStyle = { initialStyle };
        String[] selectedFinish = { "matte".equals(store.preferredCardFinish) ? "matte" : "glossy" };

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        TextView title = new TextView(this); title.setText(getString(R.string.dialog_preferred_style_title)); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin=dp(16); titleLp.leftMargin=dp(18); titleLp.bottomMargin=dp(4);
        root.addView(title, titleLp);

        String[] styleKeys = {"spine","gem","crescent","waves","sun","zigzag"};
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

        // Selettore Matte/Glossy, sotto le card: vale per QUALSIASI stile scelto, non e' legato a uno
        // stile specifico.
        LinearLayout finishRow = new LinearLayout(this); finishRow.setOrientation(LinearLayout.HORIZONTAL); finishRow.setGravity(Gravity.CENTER);
        String[] finishOptions = {"glossy","matte"};
        String[] finishLabels = {getString(R.string.finish_glossy), getString(R.string.finish_matte)};
        TextView[] finishPills = new TextView[2];
        for (int i=0;i<2;i++){
            TextView pill = new TextView(this); pill.setText(finishLabels[i]); pill.setTextSize(13); pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(20),dp(8),dp(20),dp(8));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = i==0?0:dp(10);
            finishRow.addView(pill, plp);
            finishPills[i]=pill;
        }
        LinearLayout.LayoutParams finishRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        finishRowLp.bottomMargin = dp(14);
        root.addView(finishRow, finishRowLp);
        Runnable[] refreshFinish = new Runnable[1];
        refreshFinish[0] = () -> {
            for (int i=0;i<2;i++){
                boolean active = finishOptions[i].equals(selectedFinish[0]);
                finishPills[i].setTextColor(active?Color.WHITE:MUTED_TXT);
                GradientDrawable pbg = new GradientDrawable(); pbg.setCornerRadius(dp(16));
                pbg.setColor(active?blueColor():Color.rgb(24,36,52));
                finishPills[i].setBackground(pbg);
            }
            boolean glossy = !"matte".equals(selectedFinish[0]);
            for (PreviewSwatchView s: swatches) { s.glossy = glossy; s.invalidate(); }
        };
        refreshFinish[0].run();
        for (int i=0;i<2;i++){ final String fo=finishOptions[i]; finishPills[i].setOnClickListener(v -> { selectedFinish[0]=fo; refreshFinish[0].run(); }); }

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); footer.setPadding(dp(14),dp(6),dp(14),dp(14));
        TextView cancelBtn = new TextView(this); cancelBtn.setText(getString(R.string.btn_cancel)); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(16); cancelBtn.setAllCaps(true);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText(getString(R.string.btn_confirm)); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(16); confirmBtn.setAllCaps(true);
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
            store.preferredCardFinish = selectedFinish[0];
            store.save(); view.invalidate();
            dialog.dismiss();
        });
    }

    void showPreviewPicker(Deck d){ showPreviewPicker(d, null); }

    // onChanged (opzionale): richiamato a conferma avvenuta, per far ridisegnare la riga se il dialog che ha
    // aperto questo picker (es. getString(R.string.action_change_deck)) ha una sua vista separata che altrimenti non si aggiorna da
    // sola — invalidate() sulla TrackerView principale non tocca le view native di ALTRI dialog aperti.
    void showPreviewPicker(Deck d, Runnable onChanged){
        // Rete di sicurezza: se il deck ha ancora salvata una chiave di stile/colore ormai storica (nel
        // corso dello sviluppo alcuni stili sono stati rinominati piu' volte — es. "mountains"->"crescent"),
        // nessuna tab/card corrisponderebbe piu' a nessuna di quelle attuali, e nessuna risulterebbe
        // selezionata all'apertura. Si ricade sullo stesso default usato altrove ("spine"/"grigiochiaro").
        String[] styleKeysCheck = {"spine","gem","crescent","waves","sun","zigzag"};
        String initialStyle = java.util.Arrays.asList(styleKeysCheck).contains(d.previewStyle) ? d.previewStyle : "spine";
        String initialColor = ("arcobaleno".equals(d.previewColor) || PREVIEW_COLORS.containsKey(d.previewColor)) ? d.previewColor : "grigiochiaro";
        String initialFinish = "matte".equals(d.previewFinish) ? "matte" : "glossy";
        String[] activeStyle = { initialStyle };
        String[] selectedColor = { initialColor };
        String[] selectedFinish = { initialFinish };

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable(); rootBg.setColor(Color.rgb(14,24,38)); rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        // Header: 6 tab di stile, 3 per riga (uno solo non ci starebbe comodo con 6 etichette).
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.VERTICAL);
        tabs.setPadding(dp(14),dp(14),dp(14),dp(10));
        TextView[] tabViews = new TextView[6];
        String[] styleKeys = {"spine","gem","crescent","waves","sun","zigzag"};
        String[] styleLabels = {getString(R.string.style_1),getString(R.string.style_2),getString(R.string.style_3),getString(R.string.style_4),getString(R.string.style_5),getString(R.string.style_6)};
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

        // Selettore Matte/Glossy, sotto le card: vale per QUALSIASI stile scelto (non e' legato a uno
        // stile specifico), quindi sta qui fuori dalla griglia, non dentro ogni singola card.
        LinearLayout finishRow = new LinearLayout(this); finishRow.setOrientation(LinearLayout.HORIZONTAL); finishRow.setGravity(Gravity.CENTER);
        String[] finishOptions = {"glossy","matte"};
        String[] finishLabels = {getString(R.string.finish_glossy), getString(R.string.finish_matte)};
        TextView[] finishPills = new TextView[2];
        for (int i=0;i<2;i++){
            TextView pill = new TextView(this); pill.setText(finishLabels[i]); pill.setTextSize(13); pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(20),dp(8),dp(20),dp(8));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = i==0?0:dp(10);
            finishRow.addView(pill, plp);
            finishPills[i]=pill;
        }
        LinearLayout.LayoutParams finishRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        finishRowLp.topMargin = dp(12);
        root.addView(finishRow, finishRowLp);
        Runnable[] refreshFinish = new Runnable[1];
        refreshFinish[0] = () -> {
            for (int i=0;i<2;i++){
                boolean active = finishOptions[i].equals(selectedFinish[0]);
                finishPills[i].setTextColor(active?Color.WHITE:MUTED_TXT);
                GradientDrawable pbg = new GradientDrawable(); pbg.setCornerRadius(dp(16));
                pbg.setColor(active?blueColor():Color.rgb(24,36,52));
                finishPills[i].setBackground(pbg);
            }
            boolean glossy = !"matte".equals(selectedFinish[0]);
            for (PreviewSwatchView s: swatches) { s.glossy = glossy; s.invalidate(); }
        };
        refreshFinish[0].run();
        for (int i=0;i<2;i++){ final String fo=finishOptions[i]; finishPills[i].setOnClickListener(v -> { selectedFinish[0]=fo; refreshFinish[0].run(); }); }

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
        TextView cancelBtn = new TextView(this); cancelBtn.setText(getString(R.string.btn_cancel)); cancelBtn.setTextColor(MUTED_TXT); cancelBtn.setTextSize(16); cancelBtn.setAllCaps(true);
        cancelBtn.setPadding(dp(10),dp(6),dp(10),dp(6));
        TextView confirmBtn = new TextView(this); confirmBtn.setText(getString(R.string.btn_confirm)); confirmBtn.setTextColor(blueColor()); confirmBtn.setTextSize(16); confirmBtn.setAllCaps(true);
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
        // Scorre automaticamente fino alla card attualmente selezionata, se non e' gia' visibile (es. e'
        // una delle ultime nei 10 colori disponibili). Aspetta il primo layout (post) e usa la posizione
        // REALE misurata della card scelta — non un calcolo manuale di altezze/margini, che al minimo
        // disallineamento futuro (es. cambio spaziatura) romperebbe lo scroll con un salto storto.
        int selIdx = java.util.Arrays.asList(PREVIEW_COLOR_ORDER).indexOf(selectedColor[0]);
        if (selIdx >= 0) {
            PreviewSwatchView selSwatch = swatches[selIdx];
            scroll.post(() -> {
                View rowOfSwatch = (View) selSwatch.getParent();
                int targetY = Math.max(0, rowOfSwatch.getTop() - dp(20));
                scroll.scrollTo(0, targetY);
            });
        }
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            if (selectedColor[0]!=null){
                d.previewStyle = activeStyle[0]; d.previewColor = selectedColor[0]; d.previewFinish = selectedFinish[0];
                // Aggiorna la memoria di aspetto solo se il deck ha gia' un nome vero (il Deck "in sospeso"
                // di addDeck() e' ancora senza nome a questo punto — quel caso aggiorna la memoria da solo,
                // con il nome definitivo, al momento del salvataggio del nuovo deck).
                if (!d.name.isEmpty()) store.deckAppearanceMemory.put(d.name, new String[]{d.previewStyle, d.previewColor, d.previewFinish});
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
                .setPositiveButton(getString(R.string.action_choose_preview), (dlg,w) -> showPreviewPicker(d))
                .setNegativeButton(getString(R.string.action_view_lista2), (dlg,w) -> showImageGallery(d,0))
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
        PREVIEW_COLORS.put("oro",         new int[]{Color.rgb(0xFF,0xF3,0xD1), Color.rgb(0xFF,0xC7,0x4D), Color.rgb(0xAA,0x77,0x1C)});
        PREVIEW_COLORS.put("grigiochiaro",new int[]{Color.rgb(0xC7,0xCD,0xD6), Color.rgb(0x9A,0xA3,0xAE), Color.rgb(0x6B,0x74,0x80)});
    }
    static final String[] PREVIEW_COLOR_ORDER = {"verde","rosso","azzurro","giallo","viola","marrone","grigioscuro","oro","grigiochiaro","arcobaleno"};
    // PREVIEW_COLOR_LABELS rimosso: era codice morto (mai referenziato da nessuna parte, a differenza di
    // PREVIEW_COLOR_ORDER sopra) e causava un errore di compilazione reale (chiamava getString(), un
    // metodo di istanza, da un contesto static — non permesso in Java).
    static final int[] RAINBOW_HUES_UNIQUE = { Color.rgb(0xE8,0x74,0x6A), Color.rgb(0xE0,0xB0,0x23), Color.rgb(0x5F,0xCB,0x8A), Color.rgb(0x2F,0xA8,0xD9), Color.rgb(0x7B,0x4F,0xC9) };

    // Shader arcobaleno condiviso da tutti gli stili: origine spostata FUORI dalla card, un po' sotto il
    // bordo inferiore (0.30 dell'altezza) — cosi' il punto dove i colori si toccano non e' piu' visibile al
    // centro della card, da' l'idea di "raggi che salgono da sotto". La sequenza di colori e' ripetuta 3
    // volte sull'intero giro (360°): l'origine essendo fuori dall'area visibile, la card vede solo una
    // fetta d'angolo ristretta — senza questa ripetizione si vedrebbero solo 2-3 colori invece
    // dell'arcobaleno completo, perche' quella fetta conterrebbe solo una piccola porzione del giro intero.
    Shader rainbowShader(float l, float t, float r, float b){
        float cx=(l+r)/2, h=b-t;
        float originY = b + h*0.30f;
        int n = RAINBOW_HUES_UNIQUE.length, repeats = 3, totalSteps = n*repeats;
        int[] colors = new int[totalSteps+1];
        float[] positions = new float[totalSteps+1];
        for (int i=0;i<=totalSteps;i++){ colors[i]=RAINBOW_HUES_UNIQUE[i%n]; positions[i]=i/(float)totalSteps; }
        SweepGradient sg = new SweepGradient(cx, originY, colors, positions);
        Matrix m = new Matrix(); m.postRotate(-90, cx, originY);
        sg.setLocalMatrix(m);
        return sg;
    }

    // Punto d'ingresso unico per disegnare l'anteprima di un deck (o il placeholder "nessun deck"): sempre
    // una card preimpostata disegnata sul canvas — l'anteprima da immagine personalizzata e' stata rimossa
    // (causava troppi problemi), restano solo le Liste (screenshot) come funzione separata.
    void drawDeckPreview(Canvas c, Deck d, float l, float t, float r, float b){
        if (d==null) { drawPresetPreviewCard(c, l,t,r,b, "spine", "arcobaleno", true); return; }
        drawPresetPreviewCard(c, l,t,r,b, d.previewStyle==null?"spine":d.previewStyle, d.previewColor==null?"grigiochiaro":d.previewColor, !"matte".equals(d.previewFinish));
    }

    // Disegna UNA card preimpostata (stile + colore) nel rettangolo dato. cornerRadius scalato in proporzione
    // alla dimensione della card, cosi' funziona sia per l'anteprima piccola (64x80) sia per le card grandi
    // del dialog di selezione.
    void drawPresetPreviewCard(Canvas c, float l, float t, float r, float b, String style, String colorKey, boolean glossy){
        float cr = 8f*(r-l)/64f;
        Path clip = new Path(); clip.addRoundRect(new RectF(l,t,r,b), cr,cr, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        boolean rainbow = "arcobaleno".equals(colorKey);
        int[] shades = rainbow ? null : PREVIEW_COLORS.get(colorKey);
        if (shades==null && !rainbow) shades = PREVIEW_COLORS.get("grigiochiaro");
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (style==null?"spine":style) {
            case "gem": drawPreviewGem(c, pp, l,t,r,b, shades, rainbow); break;
            case "crescent": drawPreviewCrescent(c, pp, l,t,r,b, shades, rainbow); break;
            case "waves": drawPreviewWaves(c, pp, l,t,r,b, shades, rainbow); break;
            case "sun": drawPreviewSun(c, pp, l,t,r,b, shades, rainbow); break;
            case "zigzag": drawPreviewZigzag(c, pp, l,t,r,b, shades, rainbow); break;
            default: drawPreviewSpine(c, pp, l,t,r,b, shades, rainbow); break;
        }
        if (glossy) {
        // Riflesso lucido diagonale, su OGNI card (reali in-game, selettori, onboarding): senza questo un
        // colore piatto non si legge come una superficie lucida. Sfumatura piu' morbida di un primo
        // tentativo (5 fermate invece di 3, piu' distanziate): quella aveva un bordo troppo netto/visibile.
        Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float gw=r-l, gh=b-t;
        LinearGradient gloss = new LinearGradient(l-gw*0.1f, t-gh*0.1f, l+gw*1.1f, t+gh*1.1f,
            new int[]{Color.argb(0,255,255,255), Color.argb(35,255,255,255), Color.argb(80,255,255,255), Color.argb(35,255,255,255), Color.argb(0,255,255,255)},
            new float[]{0.18f, 0.36f, 0.50f, 0.64f, 0.82f}, Shader.TileMode.CLAMP);
        glossPaint.setShader(gloss);
        c.drawRect(l,t,r,b,glossPaint);
        }
        c.restore();
    }

    void drawPreviewSpine(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        float spineW = (r-l)*0.26f;
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) {
            pp.setShader(rainbowShader(l,t,r,b));
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
        if (rainbow) { pp.setShader(rainbowShader(l,t,r,b)); }
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

    // Stile 3 "Mountains": due montagne leggermente frastagliate — quella piccola in primo piano a
    // sinistra, quella grande dietro a destra — silhouette completamente diversa da tutte le altre.
    // Stile 3 "Crescent" (sostituisce "Mountains", mai risultato convincente in nessun tentativo): una falce
    // di luna — disco chiaro "morso" da un secondo cerchio che rivela lo sfondo esattamente com'era.
    // Proporzioni scelte insieme (offset/raggio del morso) per una sagoma davvero riconoscibile come luna
    // crescente, non un blob generico.
    void drawPreviewCrescent(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(rainbowShader(l,t,r,b)); }
        else { pp.setColor(shades[2]); }
        c.drawRect(l,t,r,b,pp); pp.setShader(null);
        float w=r-l, h=b-t, cy=(t+b)/2;
        float radius = Math.min(w,h)*0.30f;
        // Il disco (e il morso) sono spostati verso destra rispetto al centro esatto della card: la falce
        // visibile (disco meno morso) e' la parte SINISTRA del disco, quindi senza questo spostamento
        // risultava decentrata a sinistra. Spostando entrambi verso destra, la falce visibile finisce
        // centrata nella card.
        float discCx = (l+r)/2 + radius*0.28f;
        // Bianco PIENO (non semi-trasparente) anche in modalita' arcobaleno: con l'alpha usato prima, lo
        // sfondo arcobaleno trasparva attraverso l'intero disco (non solo nel morso), creando un cerchio
        // "fantasma" visibile oltre alla vera sagoma a falce. Pieno, il disco nasconde completamente lo
        // sfondo dov'e' disco, e solo il morso (ridisegnato con lo stesso shader) rivela l'arcobaleno.
        pp.setColor(rainbow ? Color.WHITE : shades[0]);
        c.drawCircle(discCx, cy, radius, pp);
        // Il morso: ridisegna lo sfondo (stesso shader/colore) dentro il secondo cerchio, rivelandolo con
        // precisione — cosi' la sagoma risultante e' una vera falce, non un semplice cerchio scurito.
        float biteX = discCx + radius*0.55f, biteY = cy - radius*0.20f, biteR = radius;
        if (rainbow) { pp.setShader(rainbowShader(l,t,r,b)); c.drawCircle(biteX, biteY, biteR, pp); pp.setShader(null); }
        else { pp.setColor(shades[2]); c.drawCircle(biteX, biteY, biteR, pp); }
    }

    // Stile 4 "Waves": onde orizzontali morbide (curve di Bezier), sovrapposte verso il basso della card —
    // richiamo acquatico minimal, ben distinto dai taglio netti degli altri stili.
    void drawPreviewWaves(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(rainbowShader(l,t,r,b)); c.drawRect(l,t,r,b,pp); pp.setShader(null); }
        else { pp.setColor(shades[2]); c.drawRect(l,t,r,b,pp); }
        float w=r-l, h=b-t;
        float[] waveY = {t+h*0.55f, t+h*0.72f, t+h*0.88f};
        int[] waveCol = rainbow
            ? new int[]{Color.argb(60,255,255,255), Color.argb(90,255,255,255), Color.argb(130,255,255,255)}
            : new int[]{shades[1], shades[0], shades[1]};
        for (int i=0;i<3;i++){
            float wy = waveY[i];
            Path wave = new Path();
            wave.moveTo(l, wy);
            wave.cubicTo(l+w*0.25f, wy-h*0.055f, l+w*0.25f, wy+h*0.055f, l+w*0.5f, wy);
            wave.cubicTo(l+w*0.75f, wy-h*0.055f, l+w*0.75f, wy+h*0.055f, r, wy);
            wave.lineTo(r,b); wave.lineTo(l,b); wave.close();
            pp.setColor(waveCol[i]);
            c.drawPath(wave, pp);
        }
    }

    // Stile 5 "Sun": sfondo a gradiente verticale (chiaro in alto, scuro in basso, tipo "cielo"), un sole
    // pieno appena sopra la linea dell'orizzonte — silhouette semplice, riconoscibile a colpo d'occhio.
    // Stile 5 "Sun": sfondo a gradiente verticale (chiaro in alto, scuro in basso, tipo "cielo"), un sole
    // pieno posizionato ESATTAMENTE a metà sulla linea dell'orizzonte — il "terreno" sotto la linea copre
    // davvero la meta' inferiore del sole (non solo una riga sopra), dando un vero effetto alba/tramonto.
    void drawPreviewSun(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(rainbowShader(l,t,r,b)); }
        else { pp.setShader(new LinearGradient(0,t,0,b, shades[0], shades[1], Shader.TileMode.CLAMP)); }
        c.drawRect(l,t,r,b,pp); pp.setShader(null);
        float cx=(l+r)/2, w=r-l, h=b-t;
        float horizonY = t+h*0.62f, sunR = w*0.26f;
        // Semicerchio, non un cerchio intero: prima disegnavo un cerchio pieno e affidavo al "terreno"
        // sottostante il compito di nasconderne la meta' inferiore — ma essendo il terreno semi-trasparente
        // (non piu' nero pieno), quella meta' si intravedeva comunque attraverso di esso. Disegnando solo la
        // meta' superiore, il problema non si pone: non c'e' nulla da nascondere sotto l'orizzonte.
        pp.setColor(rainbow ? Color.argb(150,255,255,255) : shades[0]);
        RectF sunRect = new RectF(cx-sunR, horizonY-sunR, cx+sunR, horizonY+sunR);
        c.drawArc(sunRect, 180, 180, true, pp);
        // "Terreno" sotto l'orizzonte: copre davvero la meta' inferiore del sole e dello sfondo, non solo
        // una riga sopra — questo e' quello che prima mancava per un vero effetto alba/tramonto.
        pp.setColor(rainbow ? Color.argb(130,10,14,22) : shades[2]);
        c.drawRect(l, horizonY, r, b, pp);
        pp.setStyle(Paint.Style.STROKE); pp.setColor(Color.argb(110,255,255,255)); pp.setStrokeWidth(Math.max(1f, 1f*(r-l)/64f));
        c.drawLine(l,horizonY,r,horizonY,pp);
    }

    // Stile 6 "Bolt": un fulmine stilizzato, silhouette a zigzag — energico e minimal, ben diverso da
    // tutti gli altri stili (nessuna curva, nessun angolo retto, nessun cerchio).
    // Stile 6 "Orbit" (proposta al posto di "Bolt", che non convinceva): un anello con un piccolo satellite
    // lungo la sua circonferenza — silhouette pulita, si legge bene anche in scala di grigi (nel selettore
    // stili), ben diversa da tutte le altre (nessun'altra ha una forma "cava").
    // Stile 6 "Zigzag" (terza proposta al posto di "Bolt" prima e "Orbit" poi, entrambi scartati): 3 linee
    // verticali a zigzag che scendono dall'alto verso il basso — silhouette angolare e dinamica, ben diversa
    // da tutte le altre (nessun'altra ha linee verticali spezzate).
    void drawPreviewZigzag(Canvas c, Paint pp, float l, float t, float r, float b, int[] shades, boolean rainbow){
        pp.setStyle(Paint.Style.FILL);
        if (rainbow) { pp.setShader(rainbowShader(l,t,r,b)); c.drawRect(l,t,r,b,pp); pp.setShader(null); }
        else { pp.setColor(shades[2]); c.drawRect(l,t,r,b,pp); }
        float w=r-l, h=b-t;
        pp.setStyle(Paint.Style.STROKE); pp.setStrokeCap(Paint.Cap.ROUND); pp.setStrokeJoin(Paint.Join.ROUND);
        float[][] lineX = {{0.20f,0.38f},{0.42f,0.60f},{0.64f,0.82f}};
        int segs = 6;
        for (int li=0; li<lineX.length; li++){
            boolean mid = (li==1); // la linea centrale un po' piu' spessa/marcata delle altre due
            pp.setStrokeWidth(Math.max(1f, (mid?1.7f:1.1f)*(r-l)/64f));
            pp.setColor(rainbow ? Color.argb(mid?170:110,255,255,255) : (mid?shades[0]:shades[1]));
            Path zig = new Path();
            for (int i=0;i<=segs;i++){
                float px = l + (i%2==0 ? lineX[li][0] : lineX[li][1]) * w;
                float py = t + (i/(float)segs)*h;
                if (i==0) zig.moveTo(px,py); else zig.lineTo(px,py);
            }
            c.drawPath(zig, pp);
        }
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
                Toast.makeText(this,getString(R.string.err_lista_load_failed),Toast.LENGTH_SHORT).show();
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
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_lista_title)));
            } catch (Exception e) {
                Toast.makeText(this,getString(R.string.err_lista_share_failed),Toast.LENGTH_SHORT).show();
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
        int detailTab=0; // 0 = Gioca, 1 = Partite, 2 = Deck, 3 = Statistiche (solo dentro SCREEN_SEASON_DETAIL)
        int chartRange=1; // 0 = 1 giorno (da mezzanotte), 1 = 3 giorni (default), 2 = tutto
        // Zona di tocco delle pillole "1 giorno/3 giorni/Tutto" (tab Grafico), calcolata durante il disegno
        // esattamente come le coordinate di disegno stesse — MAI un numero fisso copiato a mano, che la
        // volta scorsa e' rimasto vecchio quando ho cambiato il calcolo di contentTop, causando un bug di
        // tocco reale (la zona cliccabile non corrispondeva piu' a dove le pillole erano davvero disegnate).
        float rangePillsTop=0, rangePillsBottom=0;
        // Posizione del badge getString(R.string.btn_cancel) flottante, calcolata durante il disegno e letta dal tocco — stesso
        // principio delle altre coordinate condivise, per non ripetere lo stesso bug di sfasamento.
        float undoBadgeCx=0, undoBadgeCy=0;
        ArrayList<float[]> rangePillBounds=new ArrayList<>(); // [x,width] logici, stesso ordine di rangeLabels
        // Messaggio di benvenuto (lista Stagioni): scelto una sola volta per sessione app, non ad ogni
        // ridisegno — altrimenti cambierebbe a ogni frame durante uno scroll, sembrando un glitch.
        String cachedGreeting=null;
        String greetingMessage(){
            if(cachedGreeting!=null) return cachedGreeting;
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            String[] named, plain;
            if(hour<6){
                named = new String[]{getString(R.string.greeting_night_named_0), getString(R.string.greeting_night_named_1), getString(R.string.greeting_night_named_2)};
                plain = new String[]{getString(R.string.greeting_night_plain_0), getString(R.string.greeting_night_plain_1), getString(R.string.greeting_night_plain_2)};
            } else if(hour<12){
                named = new String[]{getString(R.string.greeting_morning_named_0), getString(R.string.greeting_morning_named_1), getString(R.string.greeting_morning_named_2)};
                plain = new String[]{getString(R.string.greeting_morning_plain_0), getString(R.string.greeting_morning_plain_1), getString(R.string.greeting_morning_plain_2)};
            } else if(hour<18){
                named = new String[]{getString(R.string.greeting_afternoon_named_0), getString(R.string.greeting_afternoon_named_1), getString(R.string.greeting_afternoon_named_2)};
                plain = new String[]{getString(R.string.greeting_afternoon_plain_0), getString(R.string.greeting_afternoon_plain_1), getString(R.string.greeting_afternoon_plain_2)};
            } else {
                named = new String[]{getString(R.string.greeting_evening_named_0), getString(R.string.greeting_evening_named_1), getString(R.string.greeting_evening_named_2)};
                plain = new String[]{getString(R.string.greeting_evening_plain_0), getString(R.string.greeting_evening_plain_1), getString(R.string.greeting_evening_plain_2)};
            }
            int idx = new java.util.Random().nextInt(plain.length); // stesso indice per la coppia [con nome, senza nome]
            boolean hasName = store.trainerName!=null && !store.trainerName.isEmpty();
            cachedGreeting = hasName ? String.format(named[idx], store.trainerName) : plain[idx];
            return cachedGreeting;
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
        // Drill-down Matchup: quale riga (indice nella lista ordinata) e' attualmente espansa, per mostrare
        // come si comporta CIASCUNO dei tuoi deck contro quello specifico avversario — un vero matchup e'
        // "deck mio A contro deck avversario B", non solo "io (con qualsiasi deck) contro B". Una matrice
        // completa A-per-B sarebbe illeggibile su schermo piccolo con potenzialmente 20x20 combinazioni,
        // quindi drill-down (espandi una riga alla volta) invece di mostrare tutto insieme.
        // Filtro Matchup: null = "tutti" (default, nessuna personalizzazione ancora fatta) — ricalcolato al
        // volo su tutti i deck della Stagione corrente ogni volta che serve, cosi' non c'e' bisogno di
        // reimpostarlo quando si cambia Stagione. Un set esplicito (dopo che l'utente ha usato il filtro)
        // resta valido anche se contiene nomi non piu' presenti: semplicemente non trovano corrispondenza.
        java.util.HashSet<String> matchupMyDeckFilter = null;
        java.util.HashSet<String> matchupOppDeckFilter = null;
        ArrayList<Hit> matchupFilterBtnHit = new ArrayList<>(); // 2 zone di tocco: index 0 = filtro "i tuoi deck", 1 = filtro "avversari"
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

        // Vera icona "filtro" (imbuto), disegnata a mano — non piu' un ingranaggio (⚙, che comunica
        // "impostazioni", non "filtro") ne' un carattere unicode dal rendering incoerente tra dispositivi.
        void drawFilterIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            c.save();
            c.translate(cx-12*s, cy-12*s);
            c.scale(s,s);
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            android.graphics.Path funnel=new android.graphics.Path();
            funnel.moveTo(3,6); funnel.lineTo(21,6); funnel.lineTo(14,14); funnel.lineTo(14,20); funnel.lineTo(10,20); funnel.lineTo(10,14); funnel.close();
            c.drawPath(funnel,p);
            c.restore();
        }

        // Semplice a-capo manuale (greedy word-wrap): usato quando un testo potrebbe sborare oltre la
        // larghezza disponibile (es. accanto a un interruttore) — restituisce le righe gia' spezzate.
        String[] wrapTextLines(String text, float maxWidth, float textSize){
            p.setTextSize(textSize);
            String[] words = text.split(" ");
            ArrayList<String> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String word: words){
                String tentative = cur.length()==0 ? word : cur+" "+word;
                if (p.measureText(tentative) > maxWidth && cur.length()>0){
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(tentative);
                }
            }
            if (cur.length()>0) lines.add(cur.toString());
            return lines.toArray(new String[0]);
        }

        // Come wrapTextLines, ma con un fallback per il caso raro di una singola PAROLA piu' larga della
        // colonna anche da sola (l'a-capo sugli spazi non basta): quella riga viene troncata con puntini di
        // sospensione, invece di sborare fuori dalla card.
        String[] wrapTextLinesTruncating(String text, float maxWidth, float textSize){
            String[] lines = wrapTextLines(text, maxWidth, textSize);
            p.setTextSize(textSize);
            for (int i=0;i<lines.length;i++){
                if (p.measureText(lines[i]) > maxWidth){
                    // Troncamento manuale (niente TextUtils.ellipsize: vuole un TextPaint, mentre qui usiamo
                    // un Paint normale): togliamo un carattere alla volta finche' testo+"…" non entra.
                    String s = lines[i];
                    while (s.length()>1 && p.measureText(s+"…") > maxWidth) s = s.substring(0, s.length()-1);
                    lines[i] = s+"…";
                }
            }
            return lines;
        }

        // Interruttore ON/OFF disegnato a mano (pillola + pallino), stile standard.
        void drawToggleSwitch(Canvas c, float cx, float cy, boolean on){
            float trackW=44, trackH=24;
            float l=cx-trackW/2, t=cy-trackH/2, r=cx+trackW/2, b=cy+trackH/2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on?blue:Color.rgb(50,58,70));
            c.drawRoundRect(l,t,r,b,trackH/2,trackH/2,p);
            float knobR=trackH/2-3;
            float knobCx = on? r-trackH/2 : l+trackH/2;
            p.setColor(Color.WHITE);
            c.drawCircle(knobCx,cy,knobR,p);
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
        String deckDisplayShort(String deckName){ return "Unknown".equals(deckName) ? getString(R.string.label_unknown_deck) : deckName; }

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
            // Rete di sicurezza: se per qualsiasi motivo si finisce qui senza Stagioni valide (es. stato
            // salvato che punta a una Stagione poi cancellata), si torna alla lista invece di crashare su
            // un get() fuori range.
            if (store.seasons.isEmpty() || store.current < 0 || store.current >= store.seasons.size()) {
                screen = SCREEN_SEASON_LIST; seasonList(c,w,h); c.restore(); return;
            }
            Season s = store.seasons.get(store.current);
            // SCREEN_SEASON_DETAIL: header e barra tab in basso restano fissi, il contenuto in mezzo scorre.
            detailHeader(c,s,w);
            bodyTop=44; bodyBottom=h-58; // 44 e non 58: nel tab Deck la pillola "Ordina" parte da y=48
            resetScrollIfNeeded("detail:"+detailTab+":"+store.current);
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);
            if (detailTab==0) playTab(c,s,w,h);
            else if (detailTab==1) matchesTab(c,s,w,h);
            else if (detailTab==2) decks(c,s,w,h);
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
            // Niente piu' titolo "Pocket Stats": il messaggio di benvenuto prende il suo posto e la sua
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
            // Guard indispensabile: al PRIMO avvio assoluto (nessuna Stagione ancora creata) lastIdx vale
            // -1, e store.seasons.get(-1) faceva crashare l'app appena la View si disegnava sotto il dialog
            // di onboarding. Con la lista vuota non c'e' nulla da disegnare qui: il wizard di creazione
            // della prima Stagione e' gia' aperto sopra, e appena la crea questo blocco torna valido.
            if(lastIdx >= 0){
            Season current = store.seasons.get(lastIdx);
            txt(c,getString(R.string.label_current_season),24,y+8,12,muted,Paint.Align.LEFT);
            y+=16;
            box(c,18,y,w-18,y+110, Color.rgb(20,44,80));
            // Bordo crema distintivo, solo su questa card — stesso colore dello stroke del ventaglio nello
            // screen iniziale, per lo stesso concetto ("questa e' quella su cui giochi").
            strokeBox(c,18,y,w-18,y+110, Color.argb(170,255,250,235));
            drawKebabIcon(c, w-40, y+22, muted);
            seasonKebabPos.add(new float[]{w-40, y+22, lastIdx});
            txt(c,current.name,34,y+28,18,white,Paint.Align.LEFT);
            {
                int[] wl=countWL(current.matches); int W=wl[0],L=wl[1];
                float wr=(W+L)==0?0:100f*W/(W+L);
                txt(c,getString(R.string.label_points_and_streak,current.points,current.streak),34,y+52,12,muted,Paint.Align.LEFT);
                txtRow(c,34,y+74,12,
                    new String[]{W+"W   ", L+"L   ", "WR "+String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
                txt(c,getString(R.string.label_matches_count,current.matches.size()),34,y+96,11,muted,Paint.Align.LEFT);
            }
            seasonHits.add(new Hit(y,y+110,lastIdx));
            y+=110;

            if(lastIdx>0){
                y+=28;
                txt(c,getString(R.string.label_past_seasons),24,y,12,muted,Paint.Align.LEFT);
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
                        new String[]{W+"W  ", L+"L  ", "WR "+String.format(Locale.US,"%.1f%%",wr)+"  ", getString(R.string.label_matches_count,s.matches.size())},
                        new int[]{green, red, wrColor(wr,W+L), muted});
                    seasonHits.add(new Hit(y,y+68,i));
                    y+=78;
                }
            }
            } // chiude il guard "if(lastIdx >= 0)": con nessuna Stagione non si disegna nulla qui
            lastContentBottom = y+20;
            c.restore();
            finishScroll(); drawScrollbar(c,w);
            // Pulsante getString(R.string.btn_new_season) in basso a destra (floating action button, sempre fisso): sempre
            // raggiungibile col pollice, non scorre via col resto del contenuto.
            box(c,w-166,h-104,w-18,h-54,blue); txt(c,getString(R.string.btn_new_season),w-92,h-73,14,white,Paint.Align.CENTER);
        }

        void settingsScreen(Canvas c, float w, float h){
            float centerY=28;
            drawChevronBack(c,24,centerY,20,white);
            txt(c,getString(R.string.settings_title),44,centeredBaseline(centerY,20),20,white,Paint.Align.LEFT);
            bodyTop=52; bodyBottom=h;
            resetScrollIfNeeded("settings");
            c.save(); c.clipRect(0,bodyTop,w,bodyBottom); c.translate(0,-scrollY);

            box(c,18,64,w-18,144,card);
            txt(c,getString(R.string.settings_trainer_name),34,86,12,muted,Paint.Align.LEFT);
            String nameLabel = (store.trainerName==null || store.trainerName.isEmpty()) ? getString(R.string.settings_no_name_set) : store.trainerName;
            txt(c,nameLabel,34,centeredBaseline(115,18),18, (store.trainerName==null||store.trainerName.isEmpty())?muted:white, Paint.Align.LEFT);
            drawEditIcon(c, w-40, 115, 18, white);

            // Stile preferito per le anteprime dei nuovi deck: mostrato in "grigio chiaro" come esempio
            // neutro (lo stesso usato di default), il tocco apre la scelta tra i 3 stili. Card ridotta (158-
            // 282, non piu' 158-308): tolto il tip "Tocca per cambiare" sotto, restava spazio vuoto inutile.
            box(c,18,158,w-18,282,card);
            txt(c,getString(R.string.settings_preferred_style),w/2,180,12,muted,Paint.Align.CENTER);
            drawPresetPreviewCard(c, w/2-32,192,w/2+32,272, store.preferredCardStyle, "grigiochiaro", !"matte".equals(store.preferredCardFinish));

            // Lingua: stessa impostazione grafica di getString(R.string.label_trainer_name) sopra (etichetta + valore + matita).
            box(c,18,296,w-18,376,card);
            txt(c,getString(R.string.settings_language),34,318,12,muted,Paint.Align.LEFT);
            int langIdx = java.util.Arrays.asList(LANGUAGE_CODES).indexOf(store.language);
            txt(c, langIdx>=0?LANGUAGE_LABELS[langIdx]:"English", 34, centeredBaseline(347,18), 18, white, Paint.Align.LEFT);
            drawEditIcon(c, w-40, 347, 18, white);

            // Traccia deck avversario: interruttore ON/OFF, tocco su tutta la riga la commuta subito (nessun
            // dialog intermedio, e' una preferenza binaria semplice). Descrizione con a-capo manuale: prima
            // sborava oltre l'interruttore su una singola riga troppo lunga.
            box(c,18,390,w-18,486,card);
            txt(c,getString(R.string.settings_track_opponent_deck),34,412,12,muted,Paint.Align.LEFT);
            float toggleCx = w-52, toggleHalfTrack=22;
            float descMaxWidth = (toggleCx-toggleHalfTrack-10) - 34;
            String[] descLines = wrapTextLines(getString(R.string.settings_track_opponent_deck_desc), descMaxWidth, 13);
            for (int li=0; li<descLines.length; li++){
                txt(c, descLines[li], 34, centeredBaseline(432+li*17,13), 13, white, Paint.Align.LEFT);
            }
            drawToggleSwitch(c, toggleCx, 438, store.trackOpponentDeck);

            box(c,18,500,w-18,548,Color.rgb(30,16,16));
            strokeBox(c,18,500,w-18,548,red());
            txt(c,getString(R.string.settings_delete_all_data),w/2,centeredBaseline(524,15),15,red(),Paint.Align.CENTER);

            txt(c,APP_VERSION,w/2,572,11,muted,Paint.Align.CENTER);

            lastContentBottom = 592;
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

            // ===== getString(R.string.label_current_points) / getString(R.string.label_total_matches): stessa altezza (80) delle equivalenti in Statistiche
            // — prima erano 92, causando un brutto salto di dimensione visibile cambiando tab. Titoli ora
            // centrati orizzontalmente, come in tutte le altre card. =====
            float c1L=18, c1R=w/2-6, c2L=w/2+6, c2R=w-18;
            box(c,c1L,58,c1R,138,card);
            // Etichetta "Punti" tornata semplicemente centrata: l'icona modifica non vive piu' qui — spostata
            // nel nuovo tab "Partite" (in alto a destra, sulla stessa riga delle pillole 1g/3g/Tutto), dato
            // che e' li' che si aggiunge una correzione manuale, non nella card Punti.
            txt(c,getString(R.string.label_current_points),(c1L+c1R)/2,80,12,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,centeredBaseline(108,22),22,white,Paint.Align.CENTER);
            box(c,c2L,58,c2R,138,card);
            txt(c,getString(R.string.label_total_matches),(c2L+c2R)/2,80,12,muted,Paint.Align.CENTER); // riga 1: SEMPRE qui, sia con 2 che con 3 righe — stessa posizione della card gemella getString(R.string.label_current_points)
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

            // ===== Card deck selezionato: ora la STESSA card completa usata nel tab Deck (deckCardVisual),
            // con tutte le statistiche — non piu' la card semplificata di prima. Uniche differenze: il menu
            // "⋮" include anche "Cambia deck" (in piu' rispetto al menu standard), e non c'e' il riquadro
            // arancione di selezione (qui non serve, non siamo dentro una lista di scelta). Altezza dimezzata
            // rispetto a prima (92 invece di 150): tutto cio' che segue (pulsanti W/L, badge Annulla, card
            // Partite) e' stato spostato in alto di conseguenza, a cascata. =====
            boolean noDeck = s.currentDeck==null || "Unknown".equals(s.currentDeck);
            Deck curDeckObjForMenu = noDeck ? null : findDeck(s, s.currentDeck);
            if(noDeck){
                box(c,18,152,w-18,244,Color.rgb(10,18,30));
                float thumbW=64, thumbH=80, thumbX=28, thumbY=152+6;
                drawDeckPreview(c, null, thumbX, thumbY, thumbX+thumbW, thumbY+thumbH); // null -> anteprima arcobaleno di default
                float textX = thumbX+thumbW+14;
                txt(c,getString(R.string.label_no_deck_selected),textX,178,17,muted,Paint.Align.LEFT);
                txt(c,getString(R.string.hint_tap_select_deck),textX,198,12,muted,Paint.Align.LEFT);
                currentDeckKebabX=-1000; currentDeckKebabY=-1000; // nessun deck selezionato: nessun tap accidentale su una coordinata di un disegno precedente
            } else {
                int[] curWl = deckWL(s, s.currentDeck);
                int curBest = longestStreakForDeck(s, s.currentDeck);
                int curGain = deckGain(s, s.currentDeck);
                deckCardVisual(c, curDeckObjForMenu, s.currentDeck, false, curWl[0], curWl[1], curBest, curGain, 152, w, true);
                currentDeckKebabX=w-36; currentDeckKebabY=152+22;
            }


            boolean locked = isSeasonLocked(store.current);
            // ===== Pulsanti W/L (registrano la partita col deck selezionato sopra), o messaggio di chiusura
            // se la Stagione e' bloccata (solo l'ultima creata resta giocabile). Le correzioni manuali
            // restano SEMPRE permesse anche a Stagione bloccata (servono ad allineare i conti anche a
            // posteriori); e' solo la registrazione di nuove PARTITE a essere bloccata. Anche il badge
            // getString(R.string.btn_cancel) resta sempre attivo per lo stesso motivo (potresti voler annullare una correzione
            // appena aggiunta a una Stagione chiusa). =====
            float gL=18, gR=w/2-8, rL=w/2+8, rR=w-18;
            if(locked){
                box(c,18,264,w-18,328,card);
                txt(c,getString(R.string.label_season_ended),w/2,centeredBaseline(296,15),15,white,Paint.Align.CENTER);
            } else {
                box(c,gL,264,gR,328,green); box(c,rL,264,rR,328,red);
                float[] wl2 = centerLines(296,6,22,13);
                txt(c,"W",(gL+gR)/2,wl2[0],22,Color.WHITE,Paint.Align.CENTER); txt(c,"(+"+reward(s.streak+1)+")",(gL+gR)/2,wl2[1],13,Color.WHITE,Paint.Align.CENTER);
                txt(c,"L",(rL+rR)/2,wl2[0],22,Color.WHITE,Paint.Align.CENTER); txt(c,"(−10)",(rL+rR)/2,wl2[1],13,Color.WHITE,Paint.Align.CENTER);
            }

            // Il badge getString(R.string.btn_cancel) non c'e' piu' su una Stagione bloccata: ci ho ripensato, se serve correggere
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
            undoBadgeCy = 264+cornerInset;
            if(!hasHistory || lastIsCorrection) { undoBadgeCx = w/2; }
            else { undoBadgeCx = (all.get(all.size()-1).win ? gR : rR) - cornerInset; }
            if(hasHistory){
                p.setColor(Color.rgb(20,32,52)); p.setStyle(Paint.Style.FILL);
                c.drawCircle(undoBadgeCx, undoBadgeCy, badgeR, p);
                p.setColor(bg); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
                c.drawCircle(undoBadgeCx, undoBadgeCy, badgeR, p);
                drawUndoIcon(c, undoBadgeCx, undoBadgeCy, 14, white);
            }


            // ===== Card "GRAFICO": ora SOLO il grafico — la Lista partite e' un tab a se' stante
            // ("Partite", tra Gioca e Deck): scorrere tra le partite in questo piccolo spazio era poco
            // pratico. Il grafico resta qui: e' il segnalatore piu' immediato dell'andamento insieme ai
            // punti. Le info del grafico stesso restano le stesse di sempre (pillole range, colonne
            // giorno/deck). =====
            float chartCardTop=342, chartContentHeight=300;
            float chartContentTop=chartCardTop+16, chartContentBottom=chartContentTop+chartContentHeight;
            float chartCardBottom = chartContentBottom+14;
            box(c,18,chartCardTop,w-18,chartCardBottom,card);

            String[] rangeLabels = {getString(R.string.label_range_1_day),getString(R.string.label_range_3_days),getString(R.string.label_range_all)};
            float pillY=chartContentTop+16, pillH=26;
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
            float chartTop = chartContentTop+40;

            // Il punteggio iniziale (correzione in posizione 0, se presente) non ha senso qui — e' un dato
            // di partenza, non un evento nel tempo. Le colonne verticali segnano un cambio di GIORNO o di DECK.
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
            drawChart(c,30,chartTop,w-30,chartContentBottom,chartMatches,0,dayBoundaries);

            lastContentBottom = chartCardBottom+20;
        }

        // ===== Tab "Partite" (nuovo, tra Gioca e Deck): tutto lo storico partite/correzioni, prima
        // compresso in una piccola card dentro Gioca (poco pratico per scorrere), ora in un vero tab a
        // schermo intero. Filtro semplificato a 3 pillole (stesso stile del grafico: 1 giorno/3 giorni/
        // tutto) al posto della barra "salta al giorno" con scroll orizzontale — molto piu' semplice, e con
        // tutto lo spazio di un tab intero lo scroll orizzontale complesso non serviva piu'. L'icona
        // "aggiungi correzione manuale" (prima di fianco a "Punti") vive ora qui, in alto a destra sulla
        // stessa riga delle pillole. =====
        int matchesListRange = 1; // 0 = 1 giorno, 1 = 3 giorni (default), 2 = tutto
        ArrayList<float[]> matchesRangePillBounds = new ArrayList<>();
        float matchesRangePillsTop=0, matchesRangePillsBottom=0;
        float matchesEditIconCx=0, matchesEditIconCy=0, matchesEditIconR=0;

        void matchesTab(Canvas c, Season s, float w, float h){
            matchHits.clear();
            ArrayList<Match> all = s.matches;

            String[] rangeLabels = {getString(R.string.label_range_1_day),getString(R.string.label_range_3_days),getString(R.string.label_range_all)};
            float pillY=74, pillH=26;
            matchesRangePillsTop = pillY-pillH/2; matchesRangePillsBottom = pillY+pillH/2;
            float pillGap=8; float pillX=18;
            matchesRangePillBounds = new ArrayList<>();
            for(int ri=0; ri<3; ri++){
                p.setTextSize(11); float tw=p.measureText(rangeLabels[ri]); float pw=tw+24;
                matchesRangePillBounds.add(new float[]{pillX,pw});
                box(c,pillX,pillY-pillH/2,pillX+pw,pillY+pillH/2, ri==matchesListRange?blue:Color.rgb(20,32,48));
                txt(c,rangeLabels[ri],pillX+pw/2,centeredBaseline(pillY,11),11, ri==matchesListRange?Color.WHITE:muted, Paint.Align.CENTER);
                pillX += pw+pillGap;
            }
            // Icona "aggiungi correzione manuale", allineata a destra rispetto al margine reale delle card
            // sotto (w-18), stessa riga delle pillole.
            matchesEditIconR = 15; matchesEditIconCx = w-18-matchesEditIconR; matchesEditIconCy = pillY;
            drawEditIcon(c, matchesEditIconCx, matchesEditIconCy, matchesEditIconR*1.3f, muted);

            ArrayList<Match> filtered;
            if (matchesListRange==2) filtered = all;
            else {
                long cutoff = midnightNDaysAgo(matchesListRange==0?0:2);
                filtered = new ArrayList<>();
                for (Match m: all) if (m.timestamp>=cutoff) filtered.add(m);
            }

            float listContentTop = 100;
            if (filtered.isEmpty()) {
                String placeholder = matchesListRange==0 ? getString(R.string.label_no_games_today)
                                    : matchesListRange==1 ? getString(R.string.label_no_games_3_days)
                                    : getString(R.string.label_no_games_all);
                txt(c, placeholder, w/2, listContentTop+30, 14, muted, Paint.Align.CENTER);
                lastContentBottom = listContentTop+60;
                return;
            }

            // Numerazione "Partita N" e serie "stesso deck consecutivo" calcolate su TUTTA la cronologia
            // (non su "filtered"): altrimenti, con un filtro attivo, i numeri e le serie sembrerebbero
            // ripartire da capo invece di riflettere la cronologia reale.
            float headerH=32, matchRowH=64, corrRowH=64, groupGap=10;
            int[] matchNumber = new int[all.size()];
            { int cnt=0; for(int idx=0; idx<all.size(); idx++){ if(!all.get(idx).unknown){ cnt++; matchNumber[idx]=cnt; } } }
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
                        isTopOfStreak[idx] = (q==ri-1);
                    }
                    streakN++;
                }
            }
            // Mappa identita'->indice reale in "all": matchNumber/streakColorAt/ecc sono indicizzati su
            // "all", ma disegnamo "filtered" — serve per tradurre l'uno nell'altro.
            java.util.HashMap<Match,Integer> indexInAll = new java.util.HashMap<>();
            for (int idx=0; idx<all.size(); idx++) indexInAll.put(all.get(idx), idx);

            float y = listContentTop;
            int i = filtered.size()-1;
            while(i>=0){
                String dk = dayKey(filtered.get(i).timestamp);
                int dayEndIdx = i;
                int j = i;
                while(j>=0 && dayKey(filtered.get(j).timestamp).equals(dk)) j--;
                int dayStartIdx = j+1;
                int dw=0, dl=0; int dgain=0;
                for(int k=dayStartIdx;k<=dayEndIdx;k++){ Match m=filtered.get(k); if(!m.unknown){ if(m.win) dw++; else dl++; } dgain += (m.after-m.before); }
                float dwr = (dw+dl)==0?0:100f*dw/(dw+dl);
                float groupRowsH=0; for(int k=dayStartIdx;k<=dayEndIdx;k++) groupRowsH += filtered.get(k).unknown?corrRowH:matchRowH;
                float groupTop=y, groupBottom=y+headerH+groupRowsH;

                box(c,18,groupTop,w-18,groupBottom,Color.rgb(10,18,30));
                boxTopRounded(c,18,groupTop,w-18,groupTop+headerH,10,Color.rgb(21,34,56));
                txt(c, formatDateOnly(filtered.get(dayEndIdx).timestamp), 34, centeredBaseline(groupTop+20,11), 11, muted, Paint.Align.LEFT);
                txtRowRight(c,w-34,centeredBaseline(groupTop+20,11),11,
                    new String[]{dw+"W  ", dl+"L  ", String.format(Locale.US,"%.1f%%",dwr)+"  ", (dgain>0?"+":"")+dgain},
                    new int[]{green, red, wrColor(dwr,dw+dl), dgain>0?green:(dgain<0?red:muted)});

                float ry = groupTop+headerH;
                for(int k=dayEndIdx;k>=dayStartIdx;k--){
                    Match m = filtered.get(k);
                    int realIdx = indexInAll.get(m);
                    if(m.unknown){
                        String title = (realIdx==0) ? getString(R.string.label_starting_points) : getString(R.string.label_manual_correction);
                        box(c,26,ry+4,w-26,ry+corrRowH-4,Color.rgb(20,32,52));
                        txt(c, title, 38, ry+26, 15, white, Paint.Align.LEFT);
                        String pointsStr; int pointsColor;
                        if(realIdx==0){ pointsStr = getString(R.string.label_points_count,m.after); pointsColor = white; }
                        else {
                            int gain = m.after-m.before;
                            pointsStr = getString(R.string.label_points_count_signed,(gain>=0?"+":"")+gain);
                            pointsColor = gain>0?green:(gain<0?red:muted);
                        }
                        txtRow(c, 38, ry+46, 12,
                            new String[]{pointsStr+"   ", m.streak+getString(R.string.label_win_streak_abbr)+"   ", "+"+m.correctionWins+"W  ", "+"+m.correctionLosses+"L"},
                            new int[]{pointsColor, muted, green, red});
                    } else {
                        if(k!=dayEndIdx){ p.setColor(Color.rgb(20,30,46)); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE); c.drawLine(34,ry,w-34,ry,p); }
                        if(streakSizeAt[realIdx]>=2){
                            p.setColor(streakColorAt[realIdx]); p.setStyle(Paint.Style.FILL);
                            c.drawRect(18,ry,22,ry+matchRowH,p);
                        }
                        if(streakSizeAt[realIdx]>=2 && isTopOfStreak[realIdx]){
                            txtRow(c, 34, ry+26, 15, new String[]{deckDisplayShort(m.deck), "  ×"+streakSizeAt[realIdx]}, new int[]{white, streakColorAt[realIdx]});
                        } else {
                            txt(c, deckDisplayShort(m.deck), 34,ry+26,15,white,Paint.Align.LEFT);
                        }
                        String matchLine = getString(R.string.label_match_number_time,matchNumber[realIdx],formatTimeOnly(m.timestamp));
                        if (m.opponentDeck!=null && !m.opponentDeck.isEmpty()) matchLine += " • "+getString(R.string.label_vs)+" "+m.opponentDeck;
                        txt(c, matchLine, 34,ry+48,12,muted,Paint.Align.LEFT);
                        txt(c, m.win?"W":"L", w-34, ry+26, 15, m.win?green:red, Paint.Align.RIGHT);
                        int gain = m.after-m.before;
                        int gcol = gain>0?green:(gain<0?red:muted);
                        txt(c, (gain>0?"+":"")+gain, w-34, ry+48, 12, gcol, Paint.Align.RIGHT);
                    }
                    matchHits.add(new Hit(ry, ry+(m.unknown?corrRowH:matchRowH), realIdx));
                    ry += m.unknown?corrRowH:matchRowH;
                }
                y = groupBottom+groupGap;
                i = dayStartIdx-1;
            }

            lastContentBottom = y+20;
        }


        int deckSortMode = 0; // 0=Win rate, 1=Games, 2=Best streak, 3=Name — condiviso tra tab Deck e Statistiche
        boolean deckSortAsc = false; // false = decrescente (default), true = crescente

        String deckSortLabel(){
            if (deckSortMode==3) return "Nome "+(deckSortAsc?"A→Z":"Z→A");
            String name = deckSortMode==0?getString(R.string.label_win_rate):deckSortMode==1?"Partite":getString(R.string.label_best_streak);
            return name+" "+(deckSortAsc?"↑":"↓");
        }
        // Menu a tendina con tutte le combinazioni criterio+direzione (invece di ciclare "alla cieca").
        void showDeckSortMenu(){
            String[] items = {
                getString(R.string.sort_winrate_desc),getString(R.string.sort_winrate_asc),
                getString(R.string.sort_matches_desc),getString(R.string.sort_matches_asc),
                getString(R.string.sort_best_streak_desc),getString(R.string.sort_best_streak_asc),
                getString(R.string.sort_name_az),getString(R.string.sort_name_za)
            };
            int[] modes = {0,0,1,1,2,2,3,3};
            boolean[] ascs = {false,true,false,true,false,true,true,false};
            new AlertDialog.Builder(MainActivity.this).setTitle(getString(R.string.label_sort_decks_by)).setItems(items,(d,which)->{
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

        // Statistiche per deck AVVERSARIO, raggruppate senza distinguere maiuscole/minuscole (a differenza
        // dei tuoi deck, il nome dell'avversario e' testo libero: "Charizard ex" e "charizard EX" devono
        // contare come lo stesso matchup, non spezzarsi in due righe distinte). Il nome mostrato e' quello
        // scritto nella partita PIU' RECENTE di quel gruppo (non forzato tutto minuscolo, resta leggibile).
        // Restituisce: nome canonico -> {partite giocate, vittorie, sconfitte, variazione punti totale}.
        java.util.LinkedHashMap<String,int[]> opponentDeckStats(Season s){
            java.util.LinkedHashMap<String,int[]> stats = new java.util.LinkedHashMap<>();
            java.util.HashMap<String,String> canonicalName = new java.util.HashMap<>(); // chiave lowercase -> ultima grafia vista
            java.util.HashMap<String,Long> lastSeen = new java.util.HashMap<>();
            for (Match m: s.matches){
                if (m.unknown || m.opponentDeck==null || m.opponentDeck.isEmpty()) continue;
                String key = m.opponentDeck.toLowerCase(Locale.US);
                Long prevSeen = lastSeen.get(key);
                if (prevSeen==null || m.timestamp>=prevSeen){ canonicalName.put(key, m.opponentDeck); lastSeen.put(key, m.timestamp); }
            }
            for (Match m: s.matches){
                if (m.unknown || m.opponentDeck==null || m.opponentDeck.isEmpty()) continue;
                String key = m.opponentDeck.toLowerCase(Locale.US);
                String canon = canonicalName.get(key);
                int[] st = stats.computeIfAbsent(canon, k -> new int[4]);
                st[0]++; // partite giocate
                if (m.win) st[1]++; else st[2]++;
                st[3] += (m.after-m.before); // variazione
            }
            return stats;
        }

        // Una singola coppia matchup: il tuo deck, il deck avversario, e il relativo record.
        class MatchupPair {
            String myDeck, oppDeck; int wins, losses;
            MatchupPair(String m, String o, int w, int l){ myDeck=m; oppDeck=o; wins=w; losses=l; }
            int total(){ return wins+losses; }
            float winRate(){ return total()>0 ? 100f*wins/total() : 0; }
        }

        // Ogni coppia (tuo deck × deck avversario) con almeno 1 partita in questa Stagione — il vero
        // matchup granulare, non piu' l'aggregato "tu (con qualsiasi deck) contro l'avversario".
        ArrayList<MatchupPair> allMatchupPairs(Season s){
            java.util.LinkedHashMap<String,MatchupPair> map = new java.util.LinkedHashMap<>();
            java.util.HashMap<String,String> canonicalOpp = new java.util.HashMap<>();
            java.util.HashMap<String,Long> lastSeen = new java.util.HashMap<>();
            for (Match m: s.matches){
                if (m.unknown || m.opponentDeck==null || m.opponentDeck.isEmpty()) continue;
                String key = m.opponentDeck.toLowerCase(Locale.US);
                Long prevSeen = lastSeen.get(key);
                if (prevSeen==null || m.timestamp>=prevSeen){ canonicalOpp.put(key, m.opponentDeck); lastSeen.put(key, m.timestamp); }
            }
            for (Match m: s.matches){
                if (m.unknown || m.opponentDeck==null || m.opponentDeck.isEmpty()) continue;
                String oppCanon = canonicalOpp.get(m.opponentDeck.toLowerCase(Locale.US));
                String myDeck = m.deck==null ? getString(R.string.label_unknown_deck) : m.deck;
                String pairKey = myDeck+"||"+oppCanon.toLowerCase(Locale.US);
                MatchupPair pair = map.computeIfAbsent(pairKey, k -> new MatchupPair(myDeck, oppCanon, 0, 0));
                if (m.win) pair.wins++; else pair.losses++;
            }
            return new ArrayList<>(map.values());
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
        // riusabile sia dal tab Deck (che poi registra le sue zone) sia dal nuovo dialog getString(R.string.action_change_deck) (che
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
            txt(c, isUnknown?getString(R.string.label_unknown_deck):name, textX,y+26,17, isUnknown?muted:white, Paint.Align.LEFT);
            float wr=(W+L)==0?0:100f*W/(W+L);
            txt(c,getString(R.string.label_matches_count,(W+L)),textX,y+46,12, isUnknown?muted:white, Paint.Align.LEFT);
            if (!isUnknown && showKebab) {
                drawKebabIcon(c, w-18-10-8, y+22, muted);
            }
            if (isUnknown) {
                txtRow(c,textX,y+64,11,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{green, red, wrColor(wr,W+L)});
            } else {
                txtRow(c,textX,y+64,11,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)+"   ", getString(R.string.label_max_win_streak,best)},
                    new int[]{green, red, wrColor(wr,W+L), muted});
            }
            int gcol = gain>0?green:(gain<0?red:muted);
            txt(c, getString(R.string.label_variation,(gain>0?"+":"")+gain), textX, y+82, 11, gcol, Paint.Align.LEFT);
            return y+104;
        }

        // Card deck AVVERSARIO — stessa identica fisionomia di deckCardVisual (stesso sfondo, stessa
        // dimensione, stesso stile W/L/%, stesso kebab "⋮"): l'unica differenza voluta e' l'assenza
        // dell'anteprima grafica (gli avversari non hanno stile/colore, solo un nome) e della riga
        // "Variazione" (non tracciata per un avversario) — lo spazio dove starebbe resta semplicemente vuoto,
        // per mantenere la STESSA altezza (92) della card gemella.
        float opponentDeckCardVisual(Canvas c, String name, int timesEncountered, int W, int L, float y, float w){
            box(c,18,y,w-18,y+92,Color.rgb(10,18,30));
            float textX = 34;
            txt(c, name, textX, y+26, 17, white, Paint.Align.LEFT);
            txt(c, getString(R.string.label_encountered_n_times,timesEncountered), textX, y+46, 12, white, Paint.Align.LEFT);
            drawKebabIcon(c, w-18-10-8, y+22, muted);
            float wr = (W+L)==0?0:100f*W/(W+L);
            txtRow(c, textX, y+64, 11,
                new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});
            // Quarta riga (prima vuota, la card gemella dei tuoi deck ne ha 4 mentre questa ne aveva solo 3):
            // un giudizio rapido sul matchup, in base al win rate — solo se esistono davvero partite,
            // altrimenti non c'e' ancora nulla su cui basare un giudizio.
            if (W+L>0) {
                String quality; int qcol;
                if (wr>56) { quality=getString(R.string.label_matchup_favorable); qcol=green; }
                else if (wr<44) { quality=getString(R.string.label_matchup_tough); qcol=red; }
                else { quality=getString(R.string.label_matchup_even); qcol=muted; }
                txt(c, quality, textX, y+82, 11, qcol, Paint.Align.LEFT);
            }
            return y+104;
        }

        // Wrapper usato dal tab Deck vero: disegna la card E registra la zona di tocco dell'anteprima nella
        // lista condivisa che il touch handler del tab Deck legge — separato dal disegno puro sopra, cosi'
        // il nuovo dialog getString(R.string.action_change_deck) (che chiama solo deckCardVisual) non la sporca con le sue posizioni.
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
            // Stesso colore/bordo del pulsante "Nuovo Deck" nel dialog getString(R.string.dialog_select_deck_title) (styleSecondaryButton),
            // al posto del riempimento blu pieno di prima, per coerenza visiva tra i due.
            // Margine aumentato dalla barra di ricerca sopra (che finisce a y=88): prima "Nuovo Deck"
            // iniziava a y=90, appena 2 unita' dopo, sembravano attaccati.
            box(c,18,100,w-18,148,Color.rgb(20,32,48));
            strokeBox(c,18,100,w-18,148,FIELD_BORDER);
            txt(c,getString(R.string.btn_new_deck),w/2,centeredBaseline(124,14),14,white,Paint.Align.CENTER);
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
            txt(c,getString(R.string.label_current_points),(c1L+c1R)/2,80,12,muted,Paint.Align.CENTER);
            txt(c,""+s.points,(c1L+c1R)/2,centeredBaseline(108,22),22,white,Paint.Align.CENTER);
            box(c,c2L,58,c2R,138,card);
            txt(c,getString(R.string.label_variation_header),(c2L+c2R)/2,80,12,muted,Paint.Align.CENTER);
            txt(c, (gain>0?"+":"")+gain,(c2L+c2R)/2,centeredBaseline(108,22),22, gain>0?green:(gain<0?red:white),Paint.Align.CENTER);

            box(c,c1L,152,c1R,232,card);
            txt(c,getString(R.string.label_total_matches),(c1L+c1R)/2,174,12,muted,Paint.Align.CENTER);
            txt(c,""+(W+L),(c1L+c1R)/2,centeredBaseline(202,20),20,white,Paint.Align.CENTER);
            box(c,c2L,152,c2R,232,card);
            txt(c,getString(R.string.label_wlp_header),(c2L+c2R)/2,174,12,muted,Paint.Align.CENTER);
            txtRowCentered(c,(c2L+c2R)/2,centeredBaseline(202,15),15,
                new String[]{W+"W  ", L+"L  ", String.format(Locale.US,"%.1f%%",wr)},
                new int[]{green, red, wrColor(wr,W+L)});

            box(c,c1L,246,c1R,326,card);
            txt(c,getString(R.string.label_win_streak),(c1L+c1R)/2,268,12,muted,Paint.Align.CENTER);
            txt(c,""+s.streak,(c1L+c1R)/2,centeredBaseline(296,22),22,white,Paint.Align.CENTER);
            box(c,c2L,246,c2R,326,card);
            txt(c,getString(R.string.label_maxima_header),(c2L+c2R)/2,268,12,muted,Paint.Align.CENTER);
            txt(c,""+maxStreak,(c2L+c2R)/2,centeredBaseline(296,22),22,white,Paint.Align.CENTER);

            String mostPlayedName = "-"; int mostPlayedCount = 0;
            for(Deck d: sd){
                int[] dwl = deckWL(s,d.name); int total=dwl[0]+dwl[1];
                if(total>mostPlayedCount){ mostPlayedCount=total; mostPlayedName=d.name; }
            }
            if(nd[0]+nd[1]>mostPlayedCount){ mostPlayedCount=nd[0]+nd[1]; mostPlayedName=getString(R.string.label_unknown_deck); }
            box(c,c1L,340,c1R,420,card);
            txt(c,getString(R.string.label_decks_played),(c1L+c1R)/2,362,12,muted,Paint.Align.CENTER);
            txt(c,""+deckPlayedCount,(c1L+c1R)/2,centeredBaseline(390,16),16,white,Paint.Align.CENTER);
            box(c,c2L,340,c2R,420,card);
            txt(c,getString(R.string.label_most_played_deck),(c2L+c2R)/2,362,12,muted,Paint.Align.CENTER);
            txt(c,mostPlayedName,(c2L+c2R)/2,centeredBaseline(390,16),16,white,Paint.Align.CENTER);

            float sectionBottom = 420;

            // ===== Matchup: mostrata se esiste almeno 1 coppia con dati, INDIPENDENTEMENTE dall'impostazione
            // "Traccia il deck avversario" — se l'hai disattivata ma hai gia' dello storico tracciato in
            // passato, quello storico resta comunque visibile qui, non sparisce. Ogni coppia (tuo deck ×
            // deck avversario) con almeno 1 partita, ordinate per win rate decrescente (il migliore in
            // cima). Tutto dentro UN contenitore unico, con un header a 2 colonne ("I TUOI DECK" /
            // "AVVERSARI") che stabilisce una volta sola quale lato e' quale — le singole righe sotto non
            // ripetono piu' l'etichetta. Un filtro per colonna (icona imbuto + indicatore "N/Tutti" visibile
            // gia' da fuori, senza dover aprire il dialog), centrato sia orizzontalmente sia verticalmente
            // nella propria meta' colonna. =====
            {
                ArrayList<MatchupPair> allPairs = allMatchupPairs(s);
                if (!allPairs.isEmpty()) {
                    ArrayList<MatchupPair> filteredPairs = new ArrayList<>();
                    for (MatchupPair mp: allPairs){
                        boolean myOk = matchupMyDeckFilter==null || matchupMyDeckFilter.contains(mp.myDeck);
                        boolean oppOk = matchupOppDeckFilter==null || matchupOppDeckFilter.contains(mp.oppDeck);
                        if (myOk && oppOk) filteredPairs.add(mp);
                    }
                    filteredPairs.sort((a,b) -> Float.compare(b.winRate(), a.winRate())); // decrescente: il migliore prima

                    // Conteggi per gli indicatori "N/Tutti" dei 2 filtri.
                    java.util.HashSet<String> allMyNames = new java.util.HashSet<>(); for (Deck d: s.decks) allMyNames.add(d.name);
                    java.util.HashSet<String> allOppNames = new java.util.HashSet<>(view.opponentDeckStats(s).keySet());
                    int myCount = matchupMyDeckFilter==null ? allMyNames.size() : matchupMyDeckFilter.size();
                    int oppCount = matchupOppDeckFilter==null ? allOppNames.size() : matchupOppDeckFilter.size();
                    String myCountLabel = (matchupMyDeckFilter==null || myCount>=allMyNames.size()) ? getString(R.string.btn_select_all) : myCount+"/"+allMyNames.size();
                    String oppCountLabel = (matchupOppDeckFilter==null || oppCount>=allOppNames.size()) ? getString(R.string.btn_select_all) : oppCount+"/"+allOppNames.size();

                    float sectionTop = sectionBottom+20;

                    // "MATCHUPS" non e' piu' un'etichetta esterna sopra la card: ora vive DENTRO l'header,
                    // su una riga propria a tutta larghezza, centrata — sopra la riga "Il tuo deck | filtro
                    // Avversario | filtro".
                    float titleRowH=30, colRowH=64, headerH=titleRowH+colRowH;
                    float boxTop = sectionTop+8, dividerY = boxTop+headerH, rowTop = dividerY;
                    float colRowTop = boxTop+titleRowH;
                    // Centro orizzontale di ciascuna meta' colonna (non il centro dell'intera card): la
                    // card va da 18 a w-18, la colonna sinistra da 18 a w/2, la destra da w/2 a w-18.
                    float colLeftCx = (18f + w/2f) / 2f;
                    float colRightCx = (w/2f + (w-18f)) / 2f;

                    // Zone di tocco: tutta l'altezza della riga colonne (non piu' l'intero header, che ora
                    // include anche la riga titolo sopra) — generosa, ben oltre il minimo di ~44dp
                    // consigliato per un tocco affidabile. La meta' (sinistra/destra) la decide il tocco
                    // stesso (x rispetto a w/2).
                    matchupFilterBtnHit.clear();
                    matchupFilterBtnHit.add(new Hit(colRowTop, dividerY, 0)); // sinistra: i tuoi deck
                    matchupFilterBtnHit.add(new Hit(colRowTop, dividerY, 1)); // destra: avversari

                    // ===== Righe matchup: "il tuo deck", il blocco W-L/% (centrato) e "deck avversario"
                    // condividono tutti lo stesso asse orizzontale centrale (non piu' nome in alto e
                    // stats sotto) — questo richiede un'altezza di riga VARIABILE, perche' un nome deck
                    // lungo puo' andare a capo su piu' righe. Una zona di sicurezza (centerColW) resta
                    // sempre riservata al blocco centrale, cosi' non viene mai schiacciata da un nome
                    // lungo: il nome va a capo sugli spazi (raro, la maggior parte dei nomi e' breve tipo
                    // "Charizard" o "Metagross EX"), e nel caso limite di una singola parola piu' larga
                    // anche da sola viene troncata con puntini di sospensione. =====
                    float centerColW = 100, sideGap = 8, lineH = 18;
                    float sideColW = (w-36-centerColW)/2 - sideGap;
                    int n = filteredPairs.size();
                    String[][] myLinesArr = new String[n][], oppLinesArr = new String[n][];
                    float[] rowHeights = new float[n];
                    float totalRowsH = 0;
                    for (int i=0;i<n;i++){
                        MatchupPair mp = filteredPairs.get(i);
                        myLinesArr[i] = wrapTextLinesTruncating(mp.myDeck, sideColW, 15);
                        oppLinesArr[i] = wrapTextLinesTruncating(mp.oppDeck, sideColW, 15);
                        int nameLines = Math.max(myLinesArr[i].length, oppLinesArr[i].length);
                        int contentLines = Math.max(nameLines, 2); // il blocco centrale ha sempre 2 righe (W-L e %)
                        rowHeights[i] = contentLines*lineH + 20;
                        totalRowsH += rowHeights[i];
                    }

                    float totalH = headerH + (n==0 ? 40 : totalRowsH);
                    box(c, 18, boxTop, w-18, boxTop+totalH, card);
                    p.setColor(Color.rgb(20,30,46)); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE);
                    c.drawLine(18,dividerY,w-18,dividerY,p);

                    txt(c, getString(R.string.label_matchups), w/2, centeredBaseline(boxTop+titleRowH/2,13), 13, muted, Paint.Align.CENTER);

                    // Riga "Il tuo deck | Avversario": etichetta + gruppo(icona+conteggio) centrati come un
                    // blocco unico, sia orizzontalmente (sul centro della propria meta' colonna) sia
                    // verticalmente (le 2 righe insieme centrate nell'altezza colRowH).
                    float iconSize=22, iconGap=6;
                    p.setTextSize(13);
                    float myTextW = p.measureText(myCountLabel), oppTextW = p.measureText(oppCountLabel);
                    float myGroupLeft = colLeftCx - (iconSize+iconGap+myTextW)/2;
                    float oppGroupLeft = colRightCx - (iconSize+iconGap+oppTextW)/2;
                    float labelY = colRowTop+24, filterRowY = colRowTop+44;
                    txt(c, getString(R.string.label_your_deck), colLeftCx, labelY, 14, muted, Paint.Align.CENTER);
                    txt(c, getString(R.string.label_opponent_deck), colRightCx, labelY, 14, muted, Paint.Align.CENTER);
                    // Baseline calcolata dalle metriche vere del font (non un offset "+4" a occhio): il
                    // centro VISIVO del testo cade esattamente sul centro dell'icona (filterRowY), qualunque
                    // sia la dimensione del font — prima icona e testo apparivano leggermente disassati.
                    Paint.FontMetrics countFm = p.getFontMetrics();
                    float countBaselineY = filterRowY - (countFm.ascent+countFm.descent)/2;
                    drawFilterIcon(c, myGroupLeft+iconSize/2, filterRowY, iconSize, blue);
                    txt(c, myCountLabel, myGroupLeft+iconSize+iconGap, countBaselineY, 13, blue, Paint.Align.LEFT);
                    drawFilterIcon(c, oppGroupLeft+iconSize/2, filterRowY, iconSize, blue);
                    txt(c, oppCountLabel, oppGroupLeft+iconSize+iconGap, countBaselineY, 13, blue, Paint.Align.LEFT);

                    if (n>0) {
                        float ry = rowTop;
                        for (int i=0;i<n;i++){
                            MatchupPair mp = filteredPairs.get(i);
                            if (i>0) { p.setColor(Color.rgb(20,30,46)); p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE); c.drawLine(18,ry,w-18,ry,p); }
                            float rh = rowHeights[i];
                            float rowCenterY = ry + rh/2;
                            String[] myLines = myLinesArr[i], oppLines = oppLinesArr[i];

                            // Ogni blocco (nome mio, nome avversario, W-L/%) e' centrato verticalmente sullo
                            // STESSO centro riga (rowCenterY) — questo e' cio' che li mette "sulla stessa
                            // linea di allineamento", anche se un nome va a capo su piu' righe dell'altro.
                            float myBlockH = myLines.length*lineH;
                            float myFirstBaseline = rowCenterY - myBlockH/2 + lineH*0.7f;
                            for (int li=0; li<myLines.length; li++) txt(c, myLines[li], 32, myFirstBaseline+li*lineH, 15, white, Paint.Align.LEFT);

                            float oppBlockH = oppLines.length*lineH;
                            float oppFirstBaseline = rowCenterY - oppBlockH/2 + lineH*0.7f;
                            for (int li=0; li<oppLines.length; li++) txt(c, oppLines[li], w-32, oppFirstBaseline+li*lineH, 15, white, Paint.Align.RIGHT);

                            float pairWr = mp.winRate();
                            float centerFirstBaseline = rowCenterY - lineH + lineH*0.7f; // blocco centrale sempre 2 righe
                            txtRowCentered(c, w/2, centerFirstBaseline, 12, new String[]{mp.wins+"W  ", mp.losses+"L"}, new int[]{green, red});
                            txt(c, Math.round(pairWr)+"%", w/2, centerFirstBaseline+lineH, 16, wrColor(pairWr,mp.total()), Paint.Align.CENTER);

                            ry += rh;
                        }
                    } else {
                        // Il filtro ha escluso tutto: lo si segnala invece di mostrare una sezione vuota e
                        // silenziosa (che sembrerebbe un bug, non una scelta del filtro).
                        txt(c, getString(R.string.label_no_matchups_filtered), 18, dividerY+26, 13, muted, Paint.Align.LEFT);
                    }
                    sectionBottom = boxTop + totalH;
                }
            }

            lastContentBottom = sectionBottom+20;
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
            txt(c,getString(R.string.label_starting_points_fmt,(ms.isEmpty()?0:ms.get(0).before)),l+10,t+16,12,white,Paint.Align.LEFT);
            txt(c,getString(R.string.label_current_points_fmt,(ms.isEmpty()?0:ms.get(ms.size()-1).after)),l+10,t+34,12,white,Paint.Align.LEFT);
            if(ms.isEmpty()){
                txt(c,getString(R.string.label_no_matches_yet),(l+rr)/2,(gridTop+gridBottom)/2,13,muted,Paint.Align.CENTER);
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
            float cw=11*s, ch=15*s, r=2*s, offX=2.5f*s, offY=2.5f*s;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f);
            // Card di dietro, spostata leggermente a destra e in basso — disegnata per prima, cosi' resta sotto.
            p.setColor(color);
            c.drawRoundRect(cx-cw/2+offX, cy-ch/2+offY, cx+cw/2+offX, cy+ch/2+offY, r, r, p);
            // "Punzona" via la parte della card di dietro coperta da quella di davanti (altrimenti si
            // vedrebbero le 2 linee sovrapposte) — stesso colore di sfondo della barra tab.
            p.setColor(Color.rgb(9,15,25)); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(cx-cw/2, cy-ch/2, cx+cw/2, cy+ch/2, r, r, p);
            // Card di davanti: solo contorno.
            p.setColor(color); p.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(cx-cw/2, cy-ch/2, cx+cw/2, cy+ch/2, r, r, p);
        }
        // Icona "grafico a barre" per il tab Stats.
        void drawStatsTabIcon(Canvas c, float cx, float cy, float size, int color){
            float s = size/24f;
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(cx-8*s,cy+1*s,cx-4*s,cy+8*s,1*s,1*s,p);
            c.drawRoundRect(cx-2*s,cy-4*s,cx+2*s,cy+8*s,1*s,1*s,p);
            c.drawRoundRect(cx+4*s,cy-8*s,cx+8*s,cy+8*s,1*s,1*s,p);
        }

        // Icona "lista" per il selettore di tab: tre righe con un pallino a sinistra di ciascuna. Riusata
        // per il tab "Partite" della barra di navigazione (prima serviva per il toggle interno, ormai
        // rimosso — l'icona gemella "grafico" (drawMiniChartTabIcon) e' stata rimossa perche' non serve piu'
        // nessun toggle: il grafico e' sempre visibile in Gioca).
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
            String[] n={getString(R.string.tab_play),getString(R.string.tab_matches),getString(R.string.tab_deck),getString(R.string.tab_stats)};
            for(int i=0;i<4;i++){
                int col=i==detailTab?blue:muted;
                float cx=w*(i+.5f)/4, iconCy=y+18;
                if(i==0) drawBurstTabIcon(c,cx,iconCy,0.95f,col);
                else if(i==1) drawListTabIcon(c,cx,iconCy,20,col); // stessa icona usata prima nel toggle interno, ridimensionata (era 28) per coerenza con le altre 3 icone della barra
                else if(i==2) drawCardTabIcon(c,cx,iconCy,20,col);
                else drawStatsTabIcon(c,cx,iconCy,20,col);
                txt(c,n[i],cx,y+48,12,col,Paint.Align.CENTER);
            }
        }

        float touchDownX=0, touchDownY=0, touchStartScrollY=0; boolean isDragging=false;

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            // Le coordinate del tocco arrivano in pixel reali dell'intera View: le convertiamo nello stesso
            // sistema "dp con origine sotto la status bar" usato in onDraw, altrimenti i tap non
            // corrisponderebbero piu' a quello che e' disegnato sullo schermo.
            float x=(e.getX()-getPaddingLeft())/density, y=(e.getY()-getPaddingTop())/density;
            float w=(getWidth()-getPaddingLeft()-getPaddingRight())/density;
            float h=(getHeight()-getPaddingTop()-getPaddingBottom())/density;

            if(e.getAction()==MotionEvent.ACTION_DOWN){
                touchDownX=x; touchDownY=y; touchStartScrollY=scrollY;
                isDragging=false;
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                float dy = touchDownY - y;
                if(Math.abs(dy)>8 && touchDownY>=bodyTop && touchDownY<=bodyBottom) isDragging=true;
                if(isDragging){
                    scrollY = touchStartScrollY + dy;
                    if(scrollY<0) scrollY=0; if(scrollY>maxScrollY) scrollY=maxScrollY;
                    invalidate();
                }
                return true;
            }
            if(e.getAction()!=MotionEvent.ACTION_UP) return true;
            if(isDragging){ isDragging=false; return true; }

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
                if(contentY>=390&&contentY<=486){ store.trackOpponentDeck = !store.trackOpponentDeck; store.save(); invalidate(); return true; }
                if(contentY>=500&&contentY<=548){ resetAllData(); return true; }
                return true;
            }

            Season s = store.seasons.get(store.current);

            if(y<52){
                if(x<60){ goBack(); return true; }
                p.setTextSize(20); float nameW=p.measureText(s.name);
                if(x>=44+nameW+8){ renameSeason(); return true; }
                return true;
            }
            if(y>h-58){ detailTab=Math.min(3,(int)(x/(w/4))); invalidate(); return true; }
            if(detailTab==0){
                boolean locked = isSeasonLocked(store.current);
                // Badge getString(R.string.btn_cancel) flottante: controllato PRIMA delle altre zone, dato che sta a cavallo tra
                // la card "Deck Selezionato" e la riga W/L (un cerchio, non un rettangolo, quindi serve un
                // test di distanza invece di un normale confronto di range).
                if(!locked && Math.hypot(x-undoBadgeCx, contentY-undoBadgeCy) <= 20){ confirmUndo(); return true; }
                // Menu "⋮" della card "Deck Selezionato": stessa priorita' del badge Annulla, controllato
                // prima del tap sull'anteprima/sul resto della card (che restano invariati: anteprima apre
                // la galleria, il resto della card cambia deck).
                if(Math.hypot(x-currentDeckKebabX, contentY-currentDeckKebabY) <= 22){
                    Deck curDeckObj=findDeck(s,s.currentDeck);
                    if(curDeckObj!=null){
                        view.showAnchoredMenu(currentDeckKebabX, currentDeckKebabY-scrollY+16,
                            new String[]{getString(R.string.action_change_deck),getString(R.string.action_rename_deck),getString(R.string.action_choose_preview),getString(R.string.action_add_lista),getString(R.string.action_delete_deck)},
                            new int[]{Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, red()},
                            new Runnable[]{ () -> chooseCurrentDeck(), () -> renameDeckDialog(curDeckObj), () -> showPreviewPicker(curDeckObj), () -> openDeckImages(curDeckObj), () -> confirmDeleteDeck(s,curDeckObj) });
                        return true;
                    }
                }
                if(!locked && contentY>=152&&contentY<=244){ chooseCurrentDeck(); return true; }
                if(!locked && contentY>=264&&contentY<=328){ if(x<w/2) win(); else loss(); return true; }
                // Pillole range del grafico (1 giorno/3 giorni/Tutto): il grafico e' sempre visibile ora,
                // niente piu' toggle grafico/lista da controllare qui.
                if(contentY>=rangePillsTop && contentY<=rangePillsBottom){
                    for(int ri=0; ri<rangePillBounds.size(); ri++){
                        float[] b = rangePillBounds.get(ri);
                        if(x>=b[0] && x<=b[0]+b[1]){ chartRange=ri; invalidate(); return true; }
                    }
                }
            } else if(detailTab==1){
                // Tab "Partite": pillole range (1 giorno/3 giorni/Tutto) + icona "aggiungi correzione
                // manuale" (in alto a destra, stessa riga) + tocco su una riga per cambiarne il deck.
                if(contentY>=matchesRangePillsTop && contentY<=matchesRangePillsBottom){
                    for(int ri=0; ri<matchesRangePillBounds.size(); ri++){
                        float[] b = matchesRangePillBounds.get(ri);
                        if(x>=b[0] && x<=b[0]+b[1]){ matchesListRange=ri; invalidate(); return true; }
                    }
                    if(Math.hypot(x-matchesEditIconCx, contentY-matchesEditIconCy) <= 22){ addManualCorrection(); return true; }
                }
                for(Hit hit: matchHits){ if(contentY>=hit.top&&contentY<=hit.bottom){ Match tapped=s.matches.get(hit.index); if(!tapped.unknown) changeMatchDeck(tapped); return true; } }
            } else if(detailTab==2){
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
            } else if(detailTab==3){
                // Icona filtro Matchup: 2 zone separate (sinistra = i tuoi deck, destra = avversari), ognuna
                // apre il proprio dialog di filtro a colonna singola.
                for (Hit hit: matchupFilterBtnHit){
                    if (contentY>=hit.top && contentY<=hit.bottom){
                        boolean isLeftHalf = x < w/2;
                        if (hit.index==0 && isLeftHalf){ showMatchupFilterDialogForSide(s, true, this::invalidate); return true; }
                        if (hit.index==1 && !isLeftHalf){ showMatchupFilterDialogForSide(s, false, this::invalidate); return true; }
                    }
                }
            }
            return true;
        }
    }

    static class Match {
        boolean win,unknown;int before,after,streak;long timestamp;String deck;
        // Deck dell'avversario (facoltativo, solo se l'utente ha attivato il tracciamento nelle
        // Impostazioni e ha scelto di comunque compilarlo per questa specifica partita — sempre saltabile).
        String opponentDeck;
        // Solo per le correzioni (unknown=true): quante vittorie/sconfitte rappresenta il periodo non
        // tracciato — contano SOLO per le statistiche aggregate (W/L/win rate di Stagione), non per lo
        // streak (non sappiamo l'ordine esatto) e non per le statistiche di un deck specifico.
        int correctionWins=0, correctionLosses=0;
        Match(boolean w,int b,int a,int st,String deck){win=w;before=b;after=a;streak=st;timestamp=System.currentTimeMillis();this.deck=deck;}
        static Match correction(int b,int a,String deck){Match m=new Match(a>=b,b,a,0,deck);m.unknown=true;return m;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("w",win);o.put("u",unknown);o.put("b",before);o.put("a",after);o.put("s",streak);o.put("ts",timestamp);o.put("dk",deck!=null?deck:"Unknown");o.put("cw",correctionWins);o.put("cl",correctionLosses);if(opponentDeck!=null)o.put("odk",opponentDeck);return o;}
        static Match from(JSONObject o)throws Exception{Match m=new Match(o.getBoolean("w"),o.getInt("b"),o.getInt("a"),o.optInt("s",0),o.optString("dk","Unknown"));m.unknown=o.optBoolean("u",false);m.timestamp=o.optLong("ts",0);m.correctionWins=o.optInt("cw",0);m.correctionLosses=o.optInt("cl",0);m.opponentDeck=o.optString("odk",null);return m;}
    }
    static class Deck {
        String name; ArrayList<String> images=new ArrayList<>(); Deck(String n){name=n;}
        // Anteprima preimpostata (stile+colore, disegnata direttamente sul canvas — nessuna immagine
        // personalizzata: quella possibilita' e' stata rimossa, causava troppi problemi). Default per ogni
        // nuovo deck: stile "spine", colore "grigiochiaro".
        String previewStyle="spine";   // "spine" | "gem" | "crescent" | "waves" | "sun" | "zigzag"
        String previewColor="grigiochiaro";
        String previewFinish="glossy"; // "glossy" | "matte" — deck vecchi (mai salvato questo campo) restano
                                        // "glossy" di default, cosi' il loro aspetto non cambia da solo.
        JSONObject json()throws Exception{
            JSONObject o=new JSONObject(); o.put("n",name);
            JSONArray imgs=new JSONArray(); for(String i:images) imgs.put(i); o.put("imgs",imgs);
            o.put("pstyle",previewStyle); o.put("pcolor",previewColor); o.put("pfinish",previewFinish);
            return o;
        }
        static Deck from(JSONObject o){
            Deck d=new Deck(o.optString("n"));
            JSONArray imgs=o.optJSONArray("imgs");
            if(imgs!=null) for(int i=0;i<imgs.length();i++) d.images.add(imgs.optString(i));
            else { String legacy=o.optString("i",null); if(legacy!=null) d.images.add(legacy); } // dati salvati dalla vecchia versione (un solo screenshot)
            d.previewStyle = o.optString("pstyle","spine");
            d.previewColor = o.optString("pcolor","grigiochiaro");
            d.previewFinish = o.optString("pfinish","glossy");
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
        String preferredCardStyle="spine"; // stile preferito per le anteprime dei nuovi deck ("spine"|"gem"|"crescent"|...)
        String preferredCardFinish="glossy"; // finitura preferita per le anteprime dei nuovi deck ("glossy"|"matte")
        boolean trackOpponentDeck=false; // preferenza silenziosa: di default spenta per ogni nuovo utente,
                                          // nessuna domanda nel wizard (vedi discussione: farla nel wizard
                                          // avrebbe aggiunto attrito prima ancora che l'utente sapesse se
                                          // l'app gli piace). Si accende dalle Impostazioni, o dal popup
                                          // "Lo sapevi?" dopo la primissima partita mai registrata.
        boolean firstMatchTipShown=false; // una tantum: il popup "Lo sapevi?" non deve ripresentarsi mai piu'
        ArrayList<String> knownOpponentDecks=new ArrayList<>(); // cresce organicamente: ogni nome digitato
                                                                  // dall'utente per il deck avversario, per
                                                                  // suggerimenti rapidi (chip) le volte dopo.
        // Memoria di aspetto per i TUOI deck: nome -> {stile,colore,finitura}, persistente tra Stagioni.
        // Un deck e' due cose diverse: la sua IDENTITA' (nome+aspetto, che ha senso non sparisca mai) e le
        // sue STATISTICHE (che giustamente si azzerano a ogni Stagione, un meta diverso). Season.decks
        // resta intoccato (solo statistiche, per Stagione) — questa mappa serve solo a NON dover ridisegnare
        // l'aspetto da zero quando ricrei un deck con un nome gia' usato in passato.
        java.util.LinkedHashMap<String,String[]> deckAppearanceMemory=new java.util.LinkedHashMap<>();
        String language="en"; // lingua dell'app: "en" (default) | "it" — letta anche in attachBaseContext(), PRIMA che Store venga normalmente istanziato altrove, quindi con un accesso diretto alle SharedPreferences (vedi Companion piu' sotto)
        Store(Context c){pref=c.getSharedPreferences("tracker",0);load();}
        void save(){try{JSONObject o=new JSONObject();JSONArray a=new JSONArray();for(Season s:seasons)a.put(s.json());o.put("seasons",a);o.put("current",current);JSONArray koa=new JSONArray();for(String k:knownOpponentDecks)koa.put(k);o.put("knownOpponentDecks",koa);JSONObject dam=new JSONObject();for(java.util.Map.Entry<String,String[]> en:deckAppearanceMemory.entrySet()){JSONArray triple=new JSONArray();triple.put(en.getValue()[0]);triple.put(en.getValue()[1]);triple.put(en.getValue()[2]);dam.put(en.getKey(),triple);}o.put("deckAppearanceMemory",dam);pref.edit().putString("data",o.toString()).putString("trainerName",trainerName).putBoolean("onboardingDone",onboardingDone).putString("preferredCardStyle",preferredCardStyle).putString("preferredCardFinish",preferredCardFinish).putBoolean("trackOpponentDeck",trackOpponentDeck).putBoolean("firstMatchTipShown",firstMatchTipShown).putString("language",language).apply();}catch(Exception e){Log.e(TAG,"Errore nel salvataggio dati",e);}}
        void load(){
            trainerName = pref.getString("trainerName","");
            onboardingDone = pref.getBoolean("onboardingDone", false);
            preferredCardStyle = pref.getString("preferredCardStyle","spine");
            preferredCardFinish = pref.getString("preferredCardFinish","glossy");
            trackOpponentDeck = pref.getBoolean("trackOpponentDeck", false);
            firstMatchTipShown = pref.getBoolean("firstMatchTipShown", false);
            language = pref.getString("language","en");
            try{String z=pref.getString("data",null);if(z==null)return;JSONObject o=new JSONObject(z);current=o.optInt("current");JSONArray a=o.optJSONArray("seasons");if(a!=null)for(int i=0;i<a.length();i++)seasons.add(Season.from(a.getJSONObject(i)));JSONArray koa=o.optJSONArray("knownOpponentDecks");if(koa!=null)for(int i=0;i<koa.length();i++)knownOpponentDecks.add(koa.getString(i));JSONObject dam=o.optJSONObject("deckAppearanceMemory");if(dam!=null){java.util.Iterator<String> keys=dam.keys();while(keys.hasNext()){String k=keys.next();JSONArray triple=dam.getJSONArray(k);deckAppearanceMemory.put(k,new String[]{triple.getString(0),triple.getString(1),triple.getString(2)});}}boolean changed=clearFallbackTimestamps();if(repairMislabeledCorrections())changed=true;save_if(changed);}catch(Exception e){Log.e(TAG,"Errore nel caricamento dati, si riparte da zero",e);}
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
