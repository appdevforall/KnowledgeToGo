package org.iiab.controller.rootfs.data;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data source that reads the exact rootfs size from the Deploy server.
 *
 * <p>It fetches the {@code latest_*.meta4} Metalink file and reads its
 * {@code <size>} element (bytes). The {@code .meta4} is the stable pointer that
 * does not embed the build version/hash in its name, so it survives new builds.
 *
 * <p>Successful results are cached in memory for the process lifetime so that
 * re-calculating the storage projection (e.g. when the user switches tiers) does
 * not hit the network repeatedly. Failures are not cached, so connectivity can
 * recover on a later attempt.
 *
 * <p>This is the only place in the slice that touches {@link HttpURLConnection}.
 * It must be called off the main thread.
 */
public class RootfsRemoteDataSource {

    private static final String TAG = "RootfsRemoteDataSource";
    private static final int TIMEOUT_MS = 6000;
    private static final Pattern SIZE_PATTERN = Pattern.compile("<size>(\\d+)</size>");

    private static final ConcurrentHashMap<String, Long> CACHE = new ConcurrentHashMap<>();

    /**
     * Reads the size in bytes from a Metalink URL.
     *
     * @return the size in bytes, or {@code -1} on any failure (offline, timeout,
     *         HTTP error or missing/invalid {@code <size>}).
     */
    public long fetchSizeBytes(String metaUrl) {
        Long cached = CACHE.get(metaUrl);
        if (cached != null) {
            return cached;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(metaUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Unexpected HTTP " + code + " for " + metaUrl);
                return -1;
            }

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = SIZE_PATTERN.matcher(line);
                    if (m.find()) {
                        long bytes = Long.parseLong(m.group(1));
                        if (bytes > 0) {
                            CACHE.put(metaUrl, bytes);
                        }
                        return bytes;
                    }
                }
            }
            Log.w(TAG, "No <size> element in " + metaUrl);
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "Live rootfs size fetch failed: " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
