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
    static final int SCREEN_SEASON_LIST = 0;   // Lista delle Season
    static final int SCREEN_SEASON_DETAIL = 1; // Dettaglio Season: tab Sessioni / Deck / Statistiche
    static final int SCREEN_SESSION_PLAY = 2;  // Sessione attiva (W/L, undo/redo, grafico)

    static final int DEFAULT_BASELINE = 810; // Punteggio di partenza standard per una nuova Season

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

    // ===== Wizard di creazione Season, a step: Nome -> Hai già giocato prima del tracker? -> (Sì: punteggio
    // attuale -> sessione non tracciata) oppure (No: scelta deck -> Session 1). Ogni step e' un dialog a se',
    // mai impilato sopra un altro: si passa da uno step al successivo chiudendo quello corrente, cosi'
    // "Back" puo' sempre tornare allo step precedente senza restare mai "intrappolati" in un dialog.

    void wizardStep1(boolean first, String prefillName){
        LinearLayout box = formBox();
        String defaultName = first ? "Season 1" : ("Season " + (store.seasons.size()+1));
        box.addView(label("Season name"));
        EditText name = field(defaultName);
        if (prefillName != null) name.setText(prefillName);
        box.addView(name);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(first ? "Create your first Season" : "New Season")
            .setView(box).setCancelable(!first)
            .setPositiveButton("Next", (d,w) -> {
                String n = name.getText().toString().trim();
                wizardStep2(first, n.isEmpty() ? defaultName : n);
            });
        if (!first) b.setNegativeButton("Cancel", null); // solo se NON e' la primissima Season: qui c'e' gia' una lista a cui tornare
        b.show();
    }

    void wizardStep2(boolean first, String name){
        new AlertDialog.Builder(this).setTitle(name)
            .setMessage("Have you already been playing this Season before using the tracker?")
            .setCancelable(false)
            .setNeutralButton("Back", (d,w) -> wizardStep1(first, name))
            .setPositiveButton("Yes", (d,w) -> wizardStep3Yes(first, name))
            .setNegativeButton("No", (d,w) -> wizardStep3No(first, name))
            .show();
    }

    // "Sì": chiede solo lo stato ATTUALE (il baseline resta lo standard 810/streak 0) e registra la
    // differenza come un'unica sessione non tracciata, esattamente come una correzione manuale di punteggio.
    void wizardStep3Yes(boolean first, String name){
        LinearLayout box = formBox();
        EditText points = numberField("Current points", true);
        EditText streak = numberField("Current win streak", true);
        box.addView(label("Current points")); box.addView(points);
        box.addView(label("Current win streak")); box.addView(streak);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name)
            .setView(box).setCancelable(false)
            .setPositiveButton("Create Season", null)
            .setNeutralButton("Back", (d,w) -> wizardStep2(first, name))
            .create();
        showNonDismissing(dialog, () -> {
            try {
                int np = Integer.parseInt(points.getText().toString());
                int ns = Integer.parseInt(streak.getText().toString());
                if (ns < 0) return false;
                Season s = new Season(name);
                s.points = DEFAULT_BASELINE; s.baseline = DEFAULT_BASELINE;
                s.streak = 0; s.initialStreak = 0;
                Session sess = new Session("Session 1", "Unknown");
                sess.untracked = true;
                sess.startPoints = s.points; sess.endPoints = np;
                sess.startStreak = s.streak; sess.endStreak = ns;
                sess.matches.add(Match.untracked(s.points, np));
                s.sessions.add(sess); s.currentSession = 0;
                s.points = np; s.streak = ns;
                store.seasons.add(s); store.current = store.seasons.size()-1; store.save();
                if (view == null) { view = new TrackerView(this); setContentView(view); attachInsets(view); }
                screen = SCREEN_SEASON_DETAIL; view.detailTab = 0; view.invalidate();
                return true;
            } catch (Exception e) { return false; }
        }, "Enter valid current points and win streak (streak >= 0).");
        dialog.show();
    }

    // "No": niente da chiedere sul punteggio (parte dallo standard 810/streak 0), si passa dritti alla
    // scelta del deck per la prima sessione (con "Skip" per andare veloci, come al solito).
    void wizardStep3No(boolean first, String name){
        Season s = new Season(name);
        s.points = DEFAULT_BASELINE; s.baseline = DEFAULT_BASELINE;
        s.streak = 0; s.initialStreak = 0;
        s.sessions.add(new Session("Session 1", "Unknown")); // placeholder: showNewDeckAndSession(true) la sostituisce
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
        box.addView(label("Deck (tap to select, or \"Skip\" to go fast)"));

        // Lista di righe selezionabili al posto dello Spinner nativo: lo Spinner, senza uno stile esplicito
        // sul tema scuro dell'app, risultava praticamente invisibile (solo una freccina fluttuante) e con
        // un'area di tocco poco chiara. Una riga per deck e' molto piu' affidabile su mobile.
        LinearLayout deckList = new LinearLayout(this); deckList.setOrientation(LinearLayout.VERTICAL);
        box.addView(deckList);
        final String[] selected = {s.decks.isEmpty() ? null : s.decks.get(0).name};
        final Runnable[] refreshRef = new Runnable[1];
        Runnable refresh = () -> {
            deckList.removeAllViews();
            for (Deck d : s.decks) {
                TextView row = new TextView(this);
                row.setText(d.name);
                row.setTextColor(Color.WHITE);
                row.setPadding(dp(14),dp(10),dp(14),dp(10));
                boolean isSel = d.name.equals(selected[0]);
                row.setBackground(pill(isSel?Color.rgb(28,48,78):FIELD_BG, isSel?blueColor():FIELD_BORDER));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                row.setLayoutParams(lp);
                row.setOnClickListener(v -> { selected[0]=d.name; refreshRef[0].run(); });
                deckList.addView(row);
            }
        };
        refreshRef[0] = refresh;
        refresh.run();

        // Sezione "nuovo deck" inline: nascosta finche' non richiesta, MAI un dialog separato sopra questo
        // stesso dialog (impilare dialog era la causa sia del bug "deck non selezionabile" sia del brutto
        // effetto visivo con il popup precedente che traspariva dietro).
        LinearLayout newDeckSection = new LinearLayout(this); newDeckSection.setOrientation(LinearLayout.VERTICAL);
        newDeckSection.setVisibility(View.GONE);
        EditText newDeckName = field("Deck name");
        TextView newDeckError = new TextView(this); newDeckError.setTextColor(red()); newDeckError.setTextSize(12); newDeckError.setVisibility(View.GONE); newDeckError.setPadding(0,dp(4),0,0);
        Button createDeckBtn = new Button(this); createDeckBtn.setText("Create"); styleSecondaryButton(createDeckBtn);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(8); createDeckBtn.setLayoutParams(btnLp);
        newDeckSection.addView(newDeckName);
        newDeckSection.addView(newDeckError);
        newDeckSection.addView(createDeckBtn);
        box.addView(newDeckSection);

        Button newDeckBtn = new Button(this); newDeckBtn.setText("New Deck"); styleSecondaryButton(newDeckBtn);
        LinearLayout.LayoutParams newDeckBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newDeckBtnLp.topMargin = dp(10); newDeckBtn.setLayoutParams(newDeckBtnLp);
        box.addView(newDeckBtn);
        newDeckBtn.setOnClickListener(v -> { newDeckSection.setVisibility(View.VISIBLE); newDeckBtn.setVisibility(View.GONE); });
        createDeckBtn.setOnClickListener(v -> {
            String n = newDeckName.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) {
                newDeckError.setText("Invalid or already existing deck name.");
                newDeckError.setVisibility(View.VISIBLE);
                return;
            }
            s.decks.add(new Deck(n)); store.save();
            selected[0] = n; refresh.run();
            newDeckName.setText(""); newDeckError.setVisibility(View.GONE);
            newDeckSection.setVisibility(View.GONE); newDeckBtn.setVisibility(View.VISIBLE);
        });

        new AlertDialog.Builder(MainActivity.this).setTitle(first ? "Choose starting Deck" : "New Session")
            .setView(box).setCancelable(false)
            .setPositiveButton("Confirm", (d,w)->{
                String deck = selected[0] != null ? selected[0] : (s.decks.isEmpty() ? "Unknown" : s.decks.get(0).name);
                createSessionWithDeck(s, first, deck);
            })
            .setNeutralButton("Skip", (d,w)-> createSessionWithDeck(s, first, "Unknown"))
            .show();
    }

    int blueColor(){ return Color.rgb(55,120,255); }
    int red(){ return Color.rgb(245,70,60); }

    void createSessionWithDeck(Season s, boolean first, String deck) {
        if (first) {
            s.sessions.clear(); s.sessions.add(new Session("Session 1",deck)); s.currentSession=0;
        } else {
            s.sessions.add(new Session("Session "+(s.sessions.size()+1),deck)); s.currentSession=s.sessions.size()-1;
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
        EditText e = field("Deck name");
        AlertDialog nd = new AlertDialog.Builder(this).setTitle("New Deck").setView(e)
            .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create();
        showNonDismissing(nd, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            s.decks.add(new Deck(n));
            s.sessions.get(s.currentSession).deck = n;
            store.save(); view.invalidate();
            return true;
        }, "Invalid or already existing deck name.");
        nd.show();
    }

    // Cambia il deck della sessione ATTUALMENTE APERTA (funziona anche su sessioni passate: le statistiche
    // per deck sono sempre calcolate al volo dal nome del deck, quindi si aggiornano da sole).
    void chooseDeck() {
        Season s=store.seasons.get(store.current);
        String[] names=deckNames(s);
        if(names.length==0){ createDeckAndAssign(s); return; }
        String[] items = Arrays.copyOf(names, names.length+1);
        items[names.length] = "+ New Deck...";
        new AlertDialog.Builder(this).setTitle("Change Deck")
            .setItems(items,(d,which)->{
                if (which==names.length) createDeckAndAssign(s);
                else { s.sessions.get(s.currentSession).deck=names[which]; store.save(); view.invalidate(); }
            }).show();
    }

    void showNewSession() { showNewDeckAndSession(false); }

    void showUntracked() {
        Season s=store.seasons.get(store.current);
        LinearLayout box=formBox();
        EditText p=numberField("Current points", true);
        EditText st=numberField("Current win streak", true);
        box.addView(label("Current points")); box.addView(p);
        box.addView(label("Current win streak")); box.addView(st);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Untracked session").setMessage("Enter just the current state. The app will link the new score to the last known one automatically.")
            .setView(box).setPositiveButton("Confirm", null).setNegativeButton("Cancel",null).create();
        showNonDismissing(dialog, () -> {
            try {
                int np=Integer.parseInt(p.getText().toString()), ns=Integer.parseInt(st.getText().toString());
                if (ns < 0) return false;
                Session x=new Session("Session "+(s.sessions.size()+1),"Unknown");
                x.untracked=true; x.startPoints=s.points; x.endPoints=np; x.startStreak=s.streak; x.endStreak=ns;
                x.matches.add(Match.untracked(s.points,np));
                s.sessions.add(x); s.currentSession=s.sessions.size()-1; s.points=np; s.streak=ns;
                // Resta sulla lista sessioni: una sessione non tracciata non si "gioca" col W/L.
                store.save(); view.invalidate();
                return true;
            } catch(Exception e) { return false; }
        }, "Invalid values (streak >= 0).");
        dialog.show();
    }

    void win() { play(true); }
    void loss() { play(false); }

    void play(boolean win) {
        Season s=store.seasons.get(store.current);
        Session x=s.sessions.get(s.currentSession);
        if(x.untracked){ Toast.makeText(this,"Start a new session to continue.",Toast.LENGTH_SHORT).show(); return; }
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
        if(s.undo.empty()){Toast.makeText(this,"Nothing to undo.",Toast.LENGTH_SHORT).show();return;}
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
        if(s.redo.empty()){Toast.makeText(this,"Nothing to redo.",Toast.LENGTH_SHORT).show();return;}
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
        if(s.sessions.size()<=1){ Toast.makeText(this,"Can't delete the only session in this Season.",Toast.LENGTH_SHORT).show(); return; }
        Session sess=s.sessions.get(idx);
        Runnable doDelete = () -> {
            s.sessions.remove(idx);
            s.currentSession = Math.max(0, s.sessions.size()-1);
            screen = SCREEN_SEASON_DETAIL; view.detailTab = 0;
            store.save(); view.invalidate();
        };
        if(sess.matches.isEmpty()){ doDelete.run(); return; }
        new AlertDialog.Builder(this).setTitle("Delete session?")
            .setMessage("This will permanently delete \""+sess.name+"\" and its "+sess.matches.size()+" game(s). This can't be undone.")
            .setPositiveButton("Delete", (d,w)-> doDelete.run())
            .setNegativeButton("Cancel", null)
            .show();
    }

    // Converte la sessione CORRENTE (qualunque essa sia) in una sessione non tracciata, sostituendo il suo
    // stato con una correzione manuale punti/streak. Permesso solo se non ha ancora partite: altrimenti
    // si perderebbero silenziosamente delle partite gia' giocate.
    void convertToUntracked(){
        Season s=store.seasons.get(store.current);
        Session sess=s.sessions.get(s.currentSession);
        if(!sess.matches.isEmpty()){ Toast.makeText(this,"Only a session with no games yet can be marked as untracked.",Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box=formBox();
        EditText p=numberField("Current points", true);
        EditText st=numberField("Current win streak", true);
        box.addView(label("Current points")); box.addView(p);
        box.addView(label("Current win streak")); box.addView(st);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Mark as untracked")
            .setMessage("This session has no games yet, so it can be safely turned into an untracked score correction.")
            .setView(box).setPositiveButton("Confirm", null).setNegativeButton("Cancel", null).create();
        showNonDismissing(dialog, () -> {
            try {
                int np=Integer.parseInt(p.getText().toString()), ns=Integer.parseInt(st.getText().toString());
                if (ns < 0) return false;
                sess.untracked = true; sess.deck = null;
                sess.startPoints = s.points; sess.endPoints = np; sess.startStreak = s.streak; sess.endStreak = ns;
                sess.matches.clear();
                sess.matches.add(Match.untracked(s.points, np));
                s.points = np; s.streak = ns;
                store.save(); view.invalidate();
                return true;
            } catch(Exception e) { return false; }
        }, "Invalid values (streak >= 0).");
        dialog.show();
    }

    void newSeason(){ wizardStep1(false, null); }
    void renameSeason(){
        Season s=store.seasons.get(store.current); EditText e=field(s.name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Rename Season").setView(e)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        showNonDismissing(dialog, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return false;
            s.name = n; store.save(); view.invalidate();
            return true;
        }, "Season name can't be empty.");
        dialog.show();
    }

    void addDeck(){
        Season s=store.seasons.get(store.current); LinearLayout box=formBox();
        EditText e=field("Deck name"); box.addView(label("Deck name")); box.addView(e);
        Button img=new Button(this); img.setText("Add screenshot (optional)"); styleSecondaryButton(img); box.addView(img);
        img.setOnClickListener(v-> pickImageFor(null)); // null = immagine "in sospeso", verra' assegnata al Deck solo se il salvataggio va a buon fine
        EditText notes=multilineField("Notes (optional)", null); box.addView(label("Notes")); box.addView(notes);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("New Deck").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        showNonDismissing(dialog, () -> {
            String n=e.getText().toString().trim();
            if (n.isEmpty() || deckNameTaken(s, n)) return false;
            Deck deck=new Deck(n); if(pendingImage!=null){deck.image=pendingImage.toString();pendingImage=null;}
            String noteText=notes.getText().toString().trim(); if(!noteText.isEmpty()) deck.notes=noteText;
            s.decks.add(deck);store.save();view.invalidate();
            return true;
        }, "Invalid or already existing deck name.");
        dialog.show();
    }

    // Trova, all'interno della Season corrente, il Deck con questo nome (ogni Season ha i propri Deck:
    // uno stesso nome in due Season diverse corrisponde a due oggetti Deck distinti, con screenshot distinti).
    Deck findDeck(Season s, String name){
        if (s==null || name==null) return null;
        for (Deck d : s.decks) if (d.name.equals(name)) return d;
        return null;
    }

    Uri pendingImage=null;            // immagine "in sospeso" per un Deck non ancora creato (flusso addDeck)
    Deck pendingImageTargetDeck=null; // Deck esistente a cui assegnare l'immagine scelta (flusso di modifica)

    void pickImageFor(Deck target){
        pendingImageTargetDeck = target;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i,101); } catch(Exception ex) { Toast.makeText(this,"No app available to pick images.",Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==101 && result==RESULT_OK && data!=null){
            Uri uri = data.getData();
            try{ getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(Exception e){ Log.w(TAG, "Couldn't get persistent permission for the image", e); }
            if (pendingImageTargetDeck != null) {
                // Modifica diretta di un Deck gia' esistente (da tab Deck o dalla Sessione in gioco).
                pendingImageTargetDeck.image = uri.toString();
                pendingImageTargetDeck = null;
                store.save(); if (view != null) view.invalidate();
            } else {
                pendingImage = uri; // in attesa che l'utente completi la creazione del nuovo Deck
            }
        }
    }

    // Menu contestuale per lo screenshot di un Deck: visualizza / cambia / rimuovi (o aggiunge subito se non ce n'e' uno).
    // Richiamabile sia dal tab Deck sia dalla Sessione in gioco: agisce sempre sull'oggetto Deck della Season corrente.
    void editDeckScreenshot(Deck d){
        if (d==null) return;
        if (d.image==null) { pickImageFor(d); return; }
        String[] options = {"View screenshot","Change screenshot","Remove screenshot"};
        new AlertDialog.Builder(this).setTitle(d.name).setItems(options,(dlg,which)->{
            if (which==0) viewDeck(d);
            else if (which==1) pickImageFor(d);
            else { d.image=null; store.save(); view.invalidate(); }
        }).show();
    }

    // Note libere su un Deck (es. tecniche, matchup, promemoria). Richiamabile dal tab Deck.
    void editDeckNotes(Deck d){
        if (d==null) return;
        LinearLayout box = formBox();
        box.addView(label("Deck notes"));
        EditText e = multilineField("E.g. techniques, matchups, reminders...", d.notes);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(d.name).setView(box)
            .setPositiveButton("Save", (dlg,w)->{
                String n = e.getText().toString().trim();
                d.notes = n.isEmpty() ? null : n;
                store.save(); view.invalidate();
            }).setNegativeButton("Cancel", null).show();
    }

    // Note libere su una Sessione (es. avversari, mulligan, momenti chiave). Funziona su qualunque sessione,
    // anche passata: agisce sempre sulla sessione ATTUALMENTE APERTA in Session Play.
    void editSessionNotes(Session se){
        if (se==null) return;
        LinearLayout box = formBox();
        box.addView(label("Session notes"));
        EditText e = multilineField("E.g. opponents, key moments, how you felt...", se.notes);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(se.name).setView(box)
            .setPositiveButton("Save", (dlg,w)->{
                String n = e.getText().toString().trim();
                se.notes = n.isEmpty() ? null : n;
                store.save(); view.invalidate();
            }).setNegativeButton("Cancel", null).show();
    }

    void viewDeck(Deck d){
        if(d.image==null){Toast.makeText(this,"No screenshot attached.",Toast.LENGTH_SHORT).show();return;}
        try {
            Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(Uri.parse(d.image),"image/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open the screenshot for deck " + d.name, e);
            Toast.makeText(this,"Couldn't open the screenshot (file no longer available).",Toast.LENGTH_SHORT).show();
        }
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
    static boolean hasNotes(String s){ return s!=null && !s.trim().isEmpty(); }
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
        String deckDisplayShort(String deckName){ return "Unknown".equals(deckName) ? "No deck" : deckName; }

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
            // SCREEN_SEASON_DETAIL
            detailHeader(c,s,w);
            if (detailTab==0) sessionsList(c,s,w,h);
            else if (detailTab==1) decks(c,s,w,h);
            else stats(c,s,w,h);
            detailNav(c,w,h);
            c.restore();
        }

        void seasonList(Canvas c, float w, float h){
            seasonHits.clear();
            txt(c,"Pokémon Pocket Tracker",24,40,20,white,Paint.Align.LEFT);
            box(c,w-150,20,w-18,58,blue); txt(c,"+ Season",w-84,44,13,white,Paint.Align.CENTER);
            txt(c,"SEASONS",24,74,12,muted,Paint.Align.LEFT);
            float y=92;
            for(int i=0;i<store.seasons.size();i++){
                Season s=store.seasons.get(i);
                boolean isCurrent = i==store.current;
                box(c,18,y,w-18,y+94, isCurrent?Color.rgb(20,44,80):card);
                if(isCurrent) txt(c,"CURRENT",w-34,y+22,10,blue,Paint.Align.RIGHT);
                txt(c,s.name,34,y+30,18,white,Paint.Align.LEFT);
                ArrayList<Match> all=s.allMatches();
                int W=0,L=0;for(Match m:all)if(m.win)W++;else if(!m.unknown)L++;
                float wr=(W+L)==0?0:100f*W/(W+L);
                txtRow(c,34,y+58,12,
                    new String[]{"POINTS "+s.points+"   WS "+s.streak+"   ", W+"W ", L+"L   ", "WR "+String.format(Locale.US,"%.1f%%",wr)},
                    new int[]{muted, green, red, wrColor(wr,W+L)});
                txt(c,s.sessions.size()+" sessions",34,y+80,11,muted,Paint.Align.LEFT);
                seasonHits.add(new Hit(y,y+94,i));
                y+=104;
            }
        }

        void detailHeader(Canvas c, Season s, float w){
            txt(c,"←",24,38,26,white,Paint.Align.LEFT);
            txt(c,s.name,60,34,20,white,Paint.Align.LEFT);
            txt(c,"✎",w-24,34,18,muted,Paint.Align.RIGHT);
        }

        void sessionsList(Canvas c, Season s, float w, float h){
            sessionHits.clear();
            ArrayList<Match> all=s.allMatches();
            int W=0,L=0;for(Match m:all)if(m.win)W++;else if(!m.unknown)L++;
            float wr=(W+L)==0?0:100f*W/(W+L);
            box(c,18,58,w-18,108,card);
            txtRowCentered(c,w/2,80,12,
                new String[]{"GAMES "+all.size()+"   ", "W "+W+"   ", "L "+L+"   ", "WR "+String.format(Locale.US,"%.1f%%",wr)},
                new int[]{white, green, red, wrColor(wr,W+L)});
            txt(c,"POINTS "+s.points+"   WS "+s.streak,w/2,100,11,muted,Paint.Align.CENTER);
            box(c,18,118,w-18,160,blue);
            txt(c,"NEW SESSION",w/2,144,14,white,Paint.Align.CENTER);
            box(c,18,166,w-18,196,card);
            txt(c,"Untracked session",w/2,185,12,muted,Paint.Align.CENTER);
            float y=204;
            for(int i=s.sessions.size()-1;i>=0;i--){
                Session se=s.sessions.get(i);
                boolean isLast = i==s.sessions.size()-1;
                box(c,18,y,w-18,y+72,card);
                txt(c, se.name + (hasNotes(se.notes)?"  ✎":""), 34,y+26,15,white,Paint.Align.LEFT);
                txt(c, se.untracked ? "Untracked session" : deckDisplayShort(se.deck), 34,y+48,12,muted,Paint.Align.LEFT);
                int sw=0,sl=0;for(Match m:se.matches)if(!m.unknown){if(m.win)sw++;else sl++;}
                txtRowRight(c,w-34,y+30,13,new String[]{sw+"W ", ""+sl+"L"},new int[]{green,red});
                if(isLast && !se.untracked) txt(c,"CONTINUE →",w-34,y+52,11,blue,Paint.Align.RIGHT);
                sessionHits.add(new Hit(y,y+72,i));
                y+=80;
            }
            y+=8;
            txt(c,"OVERALL CHART",18,y,12,muted,Paint.Align.LEFT);
            drawChart(c,18,y+14,w-18,y+14+220,all,s);
        }

        int deckSortMode = 0; // 0=Win rate, 1=Games, 2=Best streak, 3=Name — condiviso tra tab Deck e Statistiche
        boolean deckSortAsc = false; // false = decrescente (default), true = crescente

        String deckSortLabel(){
            if (deckSortMode==3) return "Name "+(deckSortAsc?"A→Z":"Z→A");
            String name = deckSortMode==0?"Win rate":deckSortMode==1?"Games":"Best streak";
            return name+" "+(deckSortAsc?"↑":"↓");
        }
        // Menu a tendina con tutte le combinazioni criterio+direzione (invece di ciclare "alla cieca").
        void showDeckSortMenu(){
            String[] items = {
                "Win rate (High to Low)","Win rate (Low to High)",
                "Games (High to Low)","Games (Low to High)",
                "Best streak (High to Low)","Best streak (Low to High)",
                "Name (A to Z)","Name (Z to A)"
            };
            int[] modes = {0,0,1,1,2,2,3,3};
            boolean[] ascs = {false,true,false,true,false,true,true,false};
            new AlertDialog.Builder(MainActivity.this).setTitle("Sort decks by").setItems(items,(d,which)->{
                deckSortMode = modes[which]; deckSortAsc = ascs[which];
                view.invalidate();
            }).show();
        }

        int[] deckWL(Season s, String name){
            int w=0,l=0; for(Session se:s.sessions) if(name.equals(se.deck)) for(Match m:se.matches) if(!m.unknown){ if(m.win) w++; else l++; }
            return new int[]{w,l};
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

        void decks(Canvas c,Season s,float w,float h){
            txt(c,"Deck",18,64,15,muted,Paint.Align.LEFT);
            box(c,w-150,48,w-18,76,card); txt(c,"Sort: "+deckSortLabel()+" ▾",w-84,67,11,white,Paint.Align.CENTER);
            float y=90;
            for(Deck d: sortedDecks(s)){
                box(c,18,y,w-18,y+78,card);
                txt(c,d.name,34,y+26,17,white,Paint.Align.LEFT);
                int[] wl=deckWL(s,d.name); int W=wl[0],L=wl[1];
                float wr=(W+L)==0?0:100f*W/(W+L);
                int best=longestStreakForDeck(s,d.name);
                txt(c,(W+L)+" games",34,y+46,12,white,Paint.Align.LEFT);
                txtRow(c,34,y+64,12,
                    new String[]{W+"W   ", L+"L   ", String.format(Locale.US,"%.1f%%",wr)+"   ", "BEST "+best},
                    new int[]{green, red, wrColor(wr,W+L), muted});
                txt(c, d.image!=null?"◉":"＋", w-38,y+43,22, d.image!=null?white:muted, Paint.Align.CENTER);
                txt(c, "✎", w-76,y+43,20, hasNotes(d.notes)?blue:muted, Paint.Align.CENTER);
                y+=90;
            }
            // Sessioni senza un deck assegnato (create "al volo" con Salta): le statistiche restano comunque visibili
            // qui, cosi' l'utente puo' verificarle finche' non assegna un deck vero (le stats si aggiornano da sole).
            int[] nd = noDeckWL(s);
            if (nd[0]+nd[1] > 0) {
                box(c,18,y,w-18,y+78,card);
                txt(c,"No deck",34,y+26,17,muted,Paint.Align.LEFT);
                float ndwr=100f*nd[0]/(nd[0]+nd[1]);
                int ndbest=longestStreakForDeck(s,"Unknown");
                txt(c,(nd[0]+nd[1])+" games",34,y+46,12,muted,Paint.Align.LEFT);
                txtRow(c,34,y+64,12,
                    new String[]{nd[0]+"W   ", nd[1]+"L   ", String.format(Locale.US,"%.1f%%",ndwr)+"   ", "BEST "+ndbest},
                    new int[]{green, red, wrColor(ndwr,nd[0]+nd[1]), muted});
                y+=90;
            }
            box(c,18,y,w-18,y+58,blue);txt(c,"+  ADD DECK",w/2,y+37,14,white,Paint.Align.CENTER);
        }

        // Statistiche aggregate (W/L) delle sessioni senza un deck assegnato in questa Season.
        int[] noDeckWL(Season s){
            int w=0,l=0;
            for(Session se:s.sessions) if("Unknown".equals(se.deck)) for(Match m:se.matches) if(!m.unknown){ if(m.win) w++; else l++; }
            return new int[]{w,l};
        }

        // Longest win streak "attribuibile" a un deck: usa il valore m.streak gia' calcolato globalmente dalla
        // sessione di gioco (che persiste correttamente tra una sessione e la successiva, si azzera solo su una
        // sconfitta). Cosi' se lo stesso deck continua a vincere su piu' sessioni consecutive, lo streak si somma
        // automaticamente; se in mezzo c'e' una sconfitta (con qualsiasi deck), la catena si interrompe correttamente.
        int longestStreakForDeck(Season s, String deckName){
            int best=0;
            for(Session se:s.sessions) if(deckName.equals(se.deck)) for(Match m:se.matches) if(!m.unknown && m.win) best=Math.max(best,m.streak);
            return best;
        }

        void stats(Canvas c,Season s,float w,float h){
            txt(c,"Stats",18,64,15,muted,Paint.Align.LEFT);
            box(c,18,80,w-18,250,card);
            ArrayList<Match> all=s.allMatches();int W=0,L=0,maxStreak=s.initialStreak,cur= s.initialStreak;
            for(Match m:all){if(m.unknown)continue;if(m.win){W++;cur++;maxStreak=Math.max(maxStreak,cur);}else{L++;cur=0;}}
            float wr=(W+L)==0?0:100f*W/(W+L);
            int yy=108;
            txt(c,"Total games: "+(W+L),34,yy,14,white,Paint.Align.LEFT);yy+=16;
            txtRow(c,34,yy,14,new String[]{"Wins: ", ""+W},new int[]{white,green});yy+=16;
            txtRow(c,34,yy,14,new String[]{"Losses: ", ""+L},new int[]{white,red});yy+=16;
            txtRow(c,34,yy,14,new String[]{"Win rate: ", String.format(Locale.US,"%.1f%%",wr)},new int[]{white,wrColor(wr,W+L)});yy+=16;
            String[] rest={"Current win streak: "+s.streak,"Longest win streak: "+maxStreak,"Starting points: "+s.baseline,"Current points: "+s.points,"Net gain: "+(s.points-s.baseline)};
            for(String z:rest){txt(c,z,34,yy,14,white,Paint.Align.LEFT);yy+=16;}
            box(c,w-150,262,w-18,290,card); txt(c,"Sort: "+deckSortLabel()+" ▾",w-84,281,11,white,Paint.Align.CENTER);
            txt(c,"BY DECK",18,278,14,muted,Paint.Align.LEFT);yy=304;
            for(Deck d: sortedDecks(s)){
                int[] wl=deckWL(s,d.name); int dw=wl[0],dl=wl[1];
                float dwr=dw+dl==0?0:100f*dw/(dw+dl);
                int dbest=longestStreakForDeck(s,d.name);
                txtRow(c,18,yy,13,
                    new String[]{d.name+"   ", (dw+dl)+" games   ", dw+"W  ", dl+"L  ", String.format(Locale.US,"%.1f%%",dwr)+"  ", "Best "+dbest},
                    new int[]{white, muted, green, red, wrColor(dwr,dw+dl), muted});
                yy+=20;
            }
            int[] nd = noDeckWL(s);
            if (nd[0]+nd[1] > 0) {
                float ndwr=100f*nd[0]/(nd[0]+nd[1]);
                int ndbest=longestStreakForDeck(s,"Unknown");
                txtRow(c,18,yy,13,
                    new String[]{"No deck   ", (nd[0]+nd[1])+" games   ", nd[0]+"W  ", nd[1]+"L  ", String.format(Locale.US,"%.1f%%",ndwr)+"  ", "Best "+ndbest},
                    new int[]{muted, muted, green, red, wrColor(ndwr,nd[0]+nd[1]), muted});
                yy+=20;
            }
        }

        static final float BOTTOM_UI_HEIGHT = 150; // spazio riservato in fondo per prev/next + eventuali link testuali sotto

        void sessionPlay(Canvas c, Season s, float w, float h){
            Session x=s.sessions.get(s.currentSession);
            int idx=s.currentSession;
            boolean isLast = idx==s.sessions.size()-1;
            boolean hasPrev = idx>0, hasNext = idx<s.sessions.size()-1;
            boolean canConvert = x.matches.isEmpty() && !x.untracked; // solo se la sessione non ha ancora partite
            txt(c,"←",24,38,26,white,Paint.Align.LEFT);
            txt(c,x.name,60,34,16,white,Paint.Align.LEFT);
            if(isLast && !x.untracked){ box(c,w-58,16,w-18,52,blue); txt(c,"＋",w-38,40,20,white,Paint.Align.CENTER); }

            float chartBottom = h-BOTTOM_UI_HEIGHT;

            if(x.untracked){
                box(c,18,58,w-18,118,card);
                txt(c,"UNTRACKED SESSION",32,80,11,muted,Paint.Align.LEFT);
                txt(c,"Points "+x.startPoints+" → "+x.endPoints,32,104,15,white,Paint.Align.LEFT);
                notesRow(c,124,166,w,x.notes);
                netGainRow(c,172,214,w,x.endPoints-x.startPoints);
                txt(c,"Start a new session to keep playing.",18,238,13,muted,Paint.Align.LEFT);
                drawChart(c,18,254,w-18,Math.min(254+430,chartBottom),x.matches,s);
                bottomNav(c,w,h,hasPrev,hasNext,isLast,canConvert, idx>0?s.sessions.get(idx-1).name:null, idx<s.sessions.size()-1?s.sessions.get(idx+1).name:null);
                return;
            }

            box(c,18,58,w-18,118,card); txt(c,"DECK",32,80,11,muted,Paint.Align.LEFT);
            txt(c, "Unknown".equals(x.deck) ? "No deck — tap to choose" : x.deck, 32,104, "Unknown".equals(x.deck)?13:18, "Unknown".equals(x.deck)?muted:white, Paint.Align.LEFT);
            Deck linkedDeck = findDeck(s, x.deck);
            boolean hasShot = linkedDeck!=null && linkedDeck.image!=null;
            txt(c, hasShot?"◉":"＋", w-40, 94, 20, hasShot?white:muted, Paint.Align.CENTER);
            notesRow(c,124,166,w,x.notes);
            int gain = x.matches.isEmpty()?0:(x.matches.get(x.matches.size()-1).after - x.matches.get(0).before);
            netGainRow(c,172,214,w,gain);

            if(!isLast){
                box(c,18,222,w-18,268,card); txt(c,"Session ended",w/2,250,13,muted,Paint.Align.CENTER);
                drawChart(c,18,282,w-18,Math.min(282+610,chartBottom),x.matches,s);
                bottomNav(c,w,h,hasPrev,hasNext,isLast,canConvert, idx>0?s.sessions.get(idx-1).name:null, idx<s.sessions.size()-1?s.sessions.get(idx+1).name:null);
                return;
            }

            box(c,18,222,w/2-8,296,card); box(c,w/2+8,222,w-18,296,card);
            txt(c,"CURRENT POINTS",w/4,243,10,muted,Paint.Align.CENTER); txt(c,""+s.points,w/4,279,28,white,Paint.Align.CENTER);
            txt(c,"WIN STREAK",w*3/4,243,9,muted,Paint.Align.CENTER); txt(c,""+s.streak,w*3/4,279,28,white,Paint.Align.CENTER);
            box(c,18,308,w/2-8,384,green); box(c,w/2+8,308,w-18,384,red);
            txt(c,"W",w/4,344,26,Color.WHITE,Paint.Align.CENTER); txt(c,"+"+reward(s.streak+1),w/4,370,14,Color.WHITE,Paint.Align.CENTER);
            txt(c,"L",w*3/4,344,26,Color.WHITE,Paint.Align.CENTER); txt(c,"−10",w*3/4,370,14,Color.WHITE,Paint.Align.CENTER);
            box(c,18,396,w/2-8,442,card); box(c,w/2+8,396,w-18,442,card);
            txt(c,"↶  UNDO",w/4,425,14,white,Paint.Align.CENTER); txt(c,"↷  REDO",w*3/4,425,14,white,Paint.Align.CENTER);
            drawChart(c,18,454,w-18,Math.min(454+610,chartBottom),x.matches,s);
            bottomNav(c,w,h,hasPrev,hasNext,isLast,canConvert, idx>0?s.sessions.get(idx-1).name:null, idx<s.sessions.size()-1?s.sessions.get(idx+1).name:null);
        }

        // Riga con il guadagno netto della sessione (ultimo punteggio - punteggio di partenza di QUESTA sessione).
        void netGainRow(Canvas c, float top, float bottom, float w, int gain){
            box(c,18,top,w-18,bottom,card);
            txt(c,"NET GAIN",32,top+18,10,muted,Paint.Align.LEFT);
            int col = gain>0?green:(gain<0?red:white);
            txt(c, (gain>0?"+":"")+gain, w-32, top+28, 20, col, Paint.Align.RIGHT);
        }

        // Pulsanti Precedente/Successivo per scorrere le sessioni della Season senza dover tornare alla lista.
        void bottomNav(Canvas c, float w, float h, boolean hasPrev, boolean hasNext, boolean isLast, boolean canConvert, String prevName, String nextName){
            float navY = h-BOTTOM_UI_HEIGHT+8;
            if(hasPrev){ box(c,18,navY,w/2-8,navY+40,card); txtRow(c,32,navY+26,13,new String[]{"← ", prevName},new int[]{muted,white}); }
            if(hasNext){ box(c,w/2+8,navY,w-18,navY+40,card); txtRowRight(c,w-32,navY+26,13,new String[]{nextName, " →"},new int[]{white,muted}); }
            float ty = navY+68;
            if(canConvert){ txt(c,"Mark as untracked",w/2,ty,13,muted,Paint.Align.CENTER); ty+=34; }
            if(isLast){ txt(c,"Delete session",w/2,ty,13,red,Paint.Align.CENTER); }
        }

        // Riga NOTE separata dal box DECK: prima la nota condivideva lo spazio con lo screenshot del deck ed era
        // facile confonderla con "cambia deck". Ora e' una riga a se', con la sua etichetta, cosi' non c'e' ambiguita'.
        void notesRow(Canvas c, float top, float bottom, float w, String notes){
            box(c,18,top,w-18,bottom,card);
            txt(c,"NOTES",32,top+16,10,muted,Paint.Align.LEFT);
            txt(c, hasNotes(notes) ? notes : "Tap to add notes", 32, top+34, 13, hasNotes(notes)?white:muted, Paint.Align.LEFT);
            txt(c, "✎", w-32, top+27, 18, hasNotes(notes)?blue:muted, Paint.Align.CENTER);
        }

        void drawChart(Canvas c,float l,float t,float rr,float b,List<Match> ms,Season s){
            box(c,l,t,rr,b,Color.rgb(10,18,30)); if(ms.isEmpty())return;
            float min=ms.get(0).before,max=ms.get(0).before;for(Match m:ms){min=Math.min(min,m.after);max=Math.max(max,m.after);}
            min-=20;max+=20;if(max==min)max=min+1;
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
            txt(c,""+Math.round(min),l+8,b-8,10,muted,Paint.Align.LEFT);
            txt(c,"GAMES",rr-8,b-8,10,muted,Paint.Align.RIGHT);
            // Etichette ben visibili con il punteggio di partenza e quello attuale di questo grafico.
            txt(c,"START: "+ms.get(0).before,l+10,t+18,12,white,Paint.Align.LEFT);
            txt(c,"NOW: "+ms.get(ms.size()-1).after,rr-10,t+18,12,white,Paint.Align.RIGHT);
        }

        void detailNav(Canvas c,float w,float h){
            float y=h-72; p.setColor(Color.rgb(9,15,25));p.setStyle(Paint.Style.FILL);c.drawRect(0,y,w,h,p);
            String[] n={"Sessions","Deck","Stats"};
            for(int i=0;i<3;i++){int col=i==detailTab?blue:muted; txt(c,n[i],w*(i+.5f)/3,y+48,12,col,Paint.Align.CENTER);}
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;
            // Le coordinate del tocco arrivano in pixel reali dell'intera View: le convertiamo nello stesso
            // sistema "dp con origine sotto la status bar" usato in onDraw, altrimenti i tap non
            // corrisponderebbero piu' a quello che e' disegnato sullo schermo.
            float x=(e.getX()-getPaddingLeft())/density, y=(e.getY()-getPaddingTop())/density;
            float w=(getWidth()-getPaddingLeft()-getPaddingRight())/density;
            float h=(getHeight()-getPaddingTop()-getPaddingBottom())/density;

            if(screen==SCREEN_SEASON_LIST){
                if(y<58 && x>w-150){ newSeason(); return true; }
                for(Hit hit: seasonHits){ if(y>=hit.top&&y<=hit.bottom){ store.current=hit.index; screen=SCREEN_SEASON_DETAIL; detailTab=0; store.save(); invalidate(); return true; } }
                return true;
            }

            Season s = store.seasons.get(store.current);

            if(screen==SCREEN_SESSION_PLAY){
                Session sess = s.sessions.get(s.currentSession);
                int idx = s.currentSession;
                boolean isLast = idx==s.sessions.size()-1;
                boolean hasPrev = idx>0, hasNext = idx<s.sessions.size()-1;
                boolean canConvert = sess.matches.isEmpty() && !sess.untracked;
                float navY = h-BOTTOM_UI_HEIGHT+8;
                if(y<52){
                    if(x<60){ goBack(); return true; }
                    if(isLast && !sess.untracked && x>w-70){ showNewSession(); return true; }
                    return true;
                }
                if(y>=navY && y<=navY+40){
                    if(hasPrev && x<w/2){ s.currentSession=idx-1; view.invalidate(); return true; }
                    if(hasNext && x>=w/2){ s.currentSession=idx+1; view.invalidate(); return true; }
                    return true;
                }
                { // stessa logica di accumulo verticale usata in bottomNav(), per restare allineati al disegno
                    float ty = navY+68;
                    if(canConvert){ if(y>=ty-16 && y<=ty+16){ convertToUntracked(); return true; } ty+=34; }
                    if(isLast){ if(y>=ty-16 && y<=ty+16){ deleteCurrentSession(); return true; } }
                }
                if(y>=124 && y<=166){ editSessionNotes(sess); return true; } // riga NOTE: sempre attiva, sessione tracciata o no
                if(sess.untracked){
                    return true;
                }
                if(y>=58 && y<=118){
                    if(x>=w-70){ editDeckScreenshot(findDeck(s, sess.deck)); return true; } // zona screenshot: sempre attiva
                    chooseDeck(); return true; // zona nome deck: modificabile anche su sessioni passate (skip -> assegnazione retroattiva)
                }
                if(isLast && y>=308 && y<=384){ if(x<w/2) win(); else loss(); return true; }
                if(isLast && y>=396 && y<=442){ if(x<w/2) undo(); else redo(); return true; }
                return true;
            }

            // SCREEN_SEASON_DETAIL
            if(y<52){
                if(x<60){ goBack(); return true; }
                if(x>w-60){ renameSeason(); return true; }
                return true;
            }
            if(y>h-72){ detailTab=Math.min(2,(int)(x/(w/3))); invalidate(); return true; }
            if(detailTab==0){
                if(y>=118&&y<=160){ showNewSession(); return true; }
                if(y>=166&&y<=196){ showUntracked(); return true; }
                for(Hit hit: sessionHits){ if(y>=hit.top&&y<=hit.bottom){ s.currentSession=hit.index; screen=SCREEN_SESSION_PLAY; invalidate(); return true; } }
            } else if(detailTab==1){
                if(y>=48&&y<=76&&x>=w-150){ showDeckSortMenu(); return true; }
                float yy=90;
                for(Deck d: sortedDecks(s)){
                    if(y>=yy&&y<=yy+78){
                        if(x>=w-56){ editDeckScreenshot(d); return true; }
                        if(x>=w-92){ editDeckNotes(d); return true; }
                    }
                    yy+=90;
                }
                if(y>=yy&&y<=yy+58){ addDeck(); return true; }
            } else if(detailTab==2){
                if(y>=262&&y<=290&&x>=w-150){ showDeckSortMenu(); return true; }
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
        boolean win,unknown;int before,after,streak;
        Match(boolean w,int b,int a,int st){win=w;before=b;after=a;streak=st;}
        static Match untracked(int b,int a){Match m=new Match(a>=b,b,a,0);m.unknown=true;return m;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("w",win);o.put("u",unknown);o.put("b",before);o.put("a",after);o.put("s",streak);return o;}
        static Match from(JSONObject o)throws Exception{return new Match(o.getBoolean("w"),o.getInt("b"),o.getInt("a"),o.optInt("s",0));}
    }
    static class Deck {String name,image,notes;Deck(String n){name=n;} JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("n",name);if(image!=null)o.put("i",image);if(notes!=null)o.put("notes",notes);return o;}static Deck from(JSONObject o){Deck d=new Deck(o.optString("n"));d.image=o.optString("i",null);d.notes=o.optString("notes",null);return d;}}
    static class Session {String name,deck,notes;boolean untracked;int startPoints,endPoints,startStreak,endStreak;ArrayList<Match> matches=new ArrayList<>();Session(String n,String d){name=n;deck=d;}JSONObject json()throws Exception{JSONObject o=new JSONObject();o.put("n",name);o.put("d",deck);o.put("u",untracked);o.put("sp",startPoints);o.put("ep",endPoints);o.put("ss",startStreak);o.put("es",endStreak);if(notes!=null)o.put("notes",notes);JSONArray a=new JSONArray();for(Match m:matches)a.put(m.json());o.put("m",a);return o;}static Session from(JSONObject o)throws Exception{Session s=new Session(o.optString("n"),o.optString("d"));s.untracked=o.optBoolean("u");s.startPoints=o.optInt("sp");s.endPoints=o.optInt("ep");s.startStreak=o.optInt("ss");s.endStreak=o.optInt("es");s.notes=o.optString("notes",null);JSONArray a=o.optJSONArray("m");if(a!=null)for(int i=0;i<a.length();i++)s.matches.add(Match.from(a.getJSONObject(i)));return s;}}
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
        void save(){try{JSONObject o=new JSONObject();JSONArray a=new JSONArray();for(Season s:seasons)a.put(s.json());o.put("seasons",a);o.put("current",current);pref.edit().putString("data",o.toString()).apply();}catch(Exception e){Log.e(TAG,"Error saving data",e);}}
        void load(){try{String z=pref.getString("data",null);if(z==null)return;JSONObject o=new JSONObject(z);current=o.optInt("current");JSONArray a=o.optJSONArray("seasons");if(a!=null)for(int i=0;i<a.length();i++)seasons.add(Season.from(a.getJSONObject(i)));}catch(Exception e){Log.e(TAG,"Error loading data, starting fresh",e);}}
    }
}
