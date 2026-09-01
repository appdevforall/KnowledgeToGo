package org.appdevforall.k2go.delivery.data;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.delivery.domain.OutboundEnvelope;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Durable, offline-surviving outbound queue backed by a JSON-lines file in filesDir.
 * Low-volume (feedback + operational analytics), so simple readAll/writeAll is fine.
 * All methods are synchronized. Data layer.
 */
public final class OutboundQueue {

    private static final String TAG = "IIAB-OutboundQueue";
    private static final String FILE_NAME = "delivery_queue.jsonl";

    private final File file;

    public OutboundQueue(Context ctx) {
        this.file = new File(ctx.getFilesDir(), FILE_NAME);
    }

    public synchronized void enqueue(OutboundEnvelope e) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            w.write(e.toJson());
            w.write("\n");
        } catch (IOException | JSONException ex) {
            Log.e(TAG, "enqueue failed", ex);
        }
    }

    public synchronized List<OutboundEnvelope> peekBatch(int max) {
        List<OutboundEnvelope> all = readAll();
        return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
    }

    public synchronized int count() {
        return readAll().size();
    }

    public synchronized void deleteByIds(Set<String> ids) {
        List<OutboundEnvelope> keep = new ArrayList<>();
        for (OutboundEnvelope e : readAll()) {
            if (!ids.contains(e.id())) {
                keep.add(e);
            }
        }
        writeAll(keep);
    }

    public synchronized void dropOlderThan(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        List<OutboundEnvelope> keep = new ArrayList<>();
        for (OutboundEnvelope e : readAll()) {
            if (e.createdAt() >= cutoff) {
                keep.add(e);
            }
        }
        writeAll(keep);
    }

    public synchronized void trimToMax(int max) {
        List<OutboundEnvelope> all = readAll();
        if (all.size() > max) {
            writeAll(new ArrayList<>(all.subList(all.size() - max, all.size())));
        }
    }

    private List<OutboundEnvelope> readAll() {
        List<OutboundEnvelope> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    out.add(OutboundEnvelope.fromJson(line));
                } catch (JSONException ignore) {
                    // skip a corrupt line rather than lose the whole queue
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "readAll failed", e);
        }
        return out;
    }

    private void writeAll(List<OutboundEnvelope> items) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            for (OutboundEnvelope e : items) {
                try {
                    w.write(e.toJson());
                    w.write("\n");
                } catch (JSONException ignore) {
                    // drop an unserializable item rather than fail the rewrite
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "writeAll failed", e);
        }
    }
}
