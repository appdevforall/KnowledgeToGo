package org.iiab.controller.feedback.crash;

import androidx.annotation.NonNull;

import org.iiab.controller.feedback.crash.data.CrashReportStore;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Captures uncaught exceptions into {@link CrashReportStore} and then delegates to the
 * previously-installed default handler (so the OS still tears the process down). Installed
 * once from {@code IIABApplication}.
 */
public final class K2GoUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final CrashReportStore store;
    private final Thread.UncaughtExceptionHandler previous;

    public K2GoUncaughtExceptionHandler(CrashReportStore store,
                                        Thread.UncaughtExceptionHandler previous) {
        this.store = store;
        this.previous = previous;
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            store.save(sw.toString());
        } catch (Throwable ignored) {
            // never let crash-capture mask the original crash
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }
}
