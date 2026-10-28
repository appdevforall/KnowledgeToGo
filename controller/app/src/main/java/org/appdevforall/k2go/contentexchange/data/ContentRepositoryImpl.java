package org.appdevforall.k2go.contentexchange.data;

import android.util.Log;

import org.appdevforall.k2go.contentexchange.domain.AppUsage;
import org.appdevforall.k2go.contentexchange.domain.ContentItem;
import org.appdevforall.k2go.contentexchange.domain.ContentRepository;
import org.appdevforall.k2go.contentexchange.domain.StorageStatus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-layer implementation of the domain {@link ContentRepository} port. Maps the
 * conex-core JSON (via {@link ConexCoreDataSource}) into domain entities.
 *
 * <p>Per the port contract: {@link #fetchApps()} / {@link #fetchContent()} throw on
 * a real failure (box unreachable); {@link #fetchStorage()} never throws and returns
 * {@link StorageStatus#unavailable()} when the endpoint is missing or fails, so the
 * manager degrades gracefully.
 */
public final class ContentRepositoryImpl implements ContentRepository {

    private static final String TAG = "ConexContentRepository";

    private final ConexCoreDataSource dataSource;

    public ContentRepositoryImpl(ConexCoreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<String> fetchApps() throws Exception {
        JSONObject json = dataSource.getApps();
        JSONArray apps = json.optJSONArray("apps");
        List<String> out = new ArrayList<>();
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                String app = apps.optString(i, "");
                if (!app.isEmpty()) {
                    out.add(app);
                }
            }
        }
        return out;
    }

    @Override
    public List<ContentItem> fetchContent() throws Exception {
        JSONObject json = dataSource.getContent();
        JSONArray items = json.optJSONArray("items");
        List<ContentItem> out = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String app = o.optString("app", "");
                String kind = o.optString("kind", "");
                String title = o.optString("title", "");
                long bytes = o.optLong("bytes", 0L);
                // identity.localRef is the app's own handle to this item (export / delete).
                JSONObject identity = o.optJSONObject("identity");
                String localRef = identity != null ? identity.optString("localRef", "") : "";
                if (app.isEmpty() || localRef.isEmpty()) {
                    // No stable selection identity: skip rather than list an unactionable row.
                    continue;
                }
                out.add(new ContentItem(app, kind, title, bytes, localRef));
            }
        }
        return out;
    }

    @Override
    public StorageStatus fetchStorage() {
        try {
            JSONObject json = dataSource.getStorage();
            String volume = json.optString("volume", "");
            long total = json.optLong("totalBytes", 0L);
            long free = json.optLong("freeBytes", 0L);
            long usedByContent = json.optLong("usedByContentBytes", 0L);
            List<AppUsage> perApp = new ArrayList<>();
            JSONArray arr = json.optJSONArray("perApp");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String app = o.optString("app", "");
                    if (!app.isEmpty()) {
                        perApp.add(new AppUsage(app, o.optLong("bytes", 0L)));
                    }
                }
            }
            return StorageStatus.of(volume, total, free, usedByContent, perApp);
        } catch (Exception e) {
            // Optional endpoint (added by a parallel core change): degrade, do not fail.
            Log.i(TAG, "Storage summary unavailable: " + e.getMessage());
            return StorageStatus.unavailable();
        }
    }
}
