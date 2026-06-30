package org.iiab.controller.feedback.crash.data;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Durable, single-slot store for the last uncaught-crash stack trace. Written from the
 * crashing thread (best-effort) and read on the next launch to offer a report. Data layer.
 */
public final class CrashReportStore {

    private static final String FILE_NAME = "last_crash.txt";

    private final File file;

    public CrashReportStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), FILE_NAME);
    }

    public synchronized void save(String trace) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(trace);
        } catch (IOException ignored) {
            // best-effort: we're already crashing
        }
    }

    public boolean hasPending() {
        return file.exists() && file.length() > 0;
    }

    public synchronized String readPending() {
        if (!file.exists()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (!first) {
                    sb.append('\n');
                }
                sb.append(line);
                first = false;
            }
        } catch (IOException e) {
            return null;
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public synchronized void clear() {
        if (file.exists()) {
            file.delete();
        }
    }
}
