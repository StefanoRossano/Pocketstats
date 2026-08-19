package com.stefanorossano.pockettracker;

import android.app.Application;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

// Gestore di crash TEMPORANEO per diagnosticare il crash all'avvio della build release: scrive lo stack
// trace completo su un file nella cartella "esterna" specifica dell'app (accessibile da qualsiasi file
// manager, senza permessi speciali) — questo e' il punto piu' precoce disponibile in un'app Android
// normale, e non richiede di riaprire l'app che crasha per leggere l'errore. Da rimuovere una volta
// trovata e risolta la causa del crash.
public class PocketApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                // Cartella esterna specifica dell'app: es. /storage/emulated/0/Android/data/
                // com.stefanorossano.pockettracker/files/ — leggibile da qualsiasi file manager,
                // senza bisogno di permessi di storage speciali su Android moderno.
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir(); // fallback se lo storage esterno non e' disponibile
                File f = new File(dir, "crash_log.txt");
                FileWriter fw = new FileWriter(f, true); // append: se crasha piu' volte, si accumulano in ordine
                fw.write("=== Crash " + new java.util.Date() + " ===\n");
                fw.write(sw.toString());
                fw.write("\n\n");
                fw.close();
            } catch (Exception ignored) {}
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, ex);
        });
    }
}
