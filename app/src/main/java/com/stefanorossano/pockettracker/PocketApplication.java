package com.stefanorossano.pockettracker;

import android.app.Application;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

// Gestore di crash TEMPORANEO per diagnosticare il crash all'avvio della build release: scrive lo stack
// trace completo su file PRIMA che qualsiasi Activity (quindi anche prima di MainActivity.onCreate) venga
// creata — questo e' il punto piu' precoce disponibile in un'app Android normale. Da rimuovere una volta
// trovata e risolta la causa del crash.
public class PocketApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                File f = new File(getFilesDir(), "crash_log.txt");
                FileWriter fw = new FileWriter(f);
                fw.write(sw.toString());
                fw.close();
            } catch (Exception ignored) {}
            // Richiama comunque il comportamento di default (mostra "l'app si e' arrestata" e chiude il
            // processo) invece di forzare noi System.exit: piu' vicino al comportamento normale del sistema.
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, ex);
        });
    }
}
