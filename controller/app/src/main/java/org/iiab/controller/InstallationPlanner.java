package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.iiab.controller.rootfs.data.RootfsCatalog;
import org.iiab.controller.rootfs.data.RootfsRemoteDataSource;
import org.iiab.controller.rootfs.data.RootfsRepositoryImpl;
import org.iiab.controller.rootfs.domain.GetRootfsSizeUseCase;
import org.iiab.controller.rootfs.domain.Rootfs;
import org.iiab.controller.rootfs.domain.RootfsRepository;
import org.iiab.controller.rootfs.domain.RootfsTier;
import org.iiab.controller.util.ByteFormatter;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstallationPlanner {
    private static final String TAG = "InstallationPlanner";
    private static final String KIWIX_URL = "https://download.kiwix.org/zim/wikipedia/";
    private static final String CACHE_FILE_NAME = "kiwix_catalog.json";

    // OS rootfs sizes are no longer hardcoded here. They are resolved live (with a
    // hardcoded fallback) through the layered "rootfs" slice — see resolveOsSizeGb()
    // and org.iiab.controller.rootfs.* . Fallback byte values live in RootfsCatalog.

    private static final double MAPS_BASIC_GB = 0.2;
    private static final double MAPS_STANDARD_GB = 11.0;
    private static final double MAPS_FULL_GB = 16.0;

    private static final double KIWIX_LIMIT_BASIC = 22.0;
    private static final double KIWIX_LIMIT_STANDARD = 35.0;
    private static final double KIWIX_LIMIT_FULL = 53.0;

    public enum Tier {BASIC, STANDARD, FULL}

    public static class StorageProjection {
        public double osSize;
        public double mapsSize;
        public double kiwixSize;
        public double totalSize;
        public String resolvedLang;
        public String resolvedFilename; // NEW: The exact filename to download!

        public StorageProjection(double osSize, double mapsSize, double kiwixSize, String resolvedLang, String resolvedFilename) {
            this.osSize = osSize;
            this.mapsSize = mapsSize;
            this.kiwixSize = kiwixSize;
            this.totalSize = osSize + mapsSize + kiwixSize;
            this.resolvedLang = resolvedLang;
            this.resolvedFilename = resolvedFilename;
        }
    }

    public interface PlanResultListener {
        void onCalculated(StorageProjection projection);

        void onError(String error);
    }

    public interface CacheListener {
        void onReady(JSONObject catalog);

        void onError(String error);
    }

    public static void wipeCache(Context context) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
        if (cacheFile.exists()) cacheFile.delete();
    }

    public static void getOrFetchCatalog(Context context, CacheListener listener) {
        new Thread(() -> {
            File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);

            if (cacheFile.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(cacheFile);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject db = new JSONObject(sb.toString());
                    new Handler(Looper.getMainLooper()).post(() -> listener.onReady(db));
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Cache read error", e);
                }
            }

            try {
                URL url = new URL(KIWIX_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) html.append(line);
                reader.close();

                Pattern pattern = Pattern.compile("wikipedia_([a-z\\-]+)_([a-z0-9_\\-]+)_(\\d{4}-\\d{2})\\.zim(?:</a>)?\\s+\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}\\s+([\\d\\.]+)([KMG])");
                Matcher matcher = pattern.matcher(html.toString());

                JSONObject database = new JSONObject();

                while (matcher.find()) {
                    String lang = matcher.group(1);
                    String variant = matcher.group(2);
                    String date = matcher.group(3);
                    String sizeStr = matcher.group(4);
                    String unit = matcher.group(5);

                    if (variant != null && variant.matches("all_maxi|all_nopic|all_mini|top_maxi|top_nopic|top_mini|top1m_maxi|top1m_nopic|top1m_mini")) {
                        double sizeVal = Double.parseDouble(sizeStr);
                        double sizeGb = 0;
                        if ("G".equals(unit)) sizeGb = sizeVal;
                        else if ("M".equals(unit)) sizeGb = sizeVal / 1024.0;
                        else if ("K".equals(unit)) sizeGb = sizeVal / (1024.0 * 1024.0);

                        if (!database.has(lang)) database.put(lang, new JSONObject());

                        // NEW: Store size AND exact filename
                        JSONObject variantData = new JSONObject();
                        variantData.put("size", sizeGb);
                        variantData.put("file", "wikipedia_" + lang + "_" + variant + "_" + date + ".zim");

                        database.getJSONObject(lang).put(variant, variantData);
                    }
                }

                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(database.toString().getBytes());
                fos.close();

                new Handler(Looper.getMainLooper()).post(() -> listener.onReady(database));

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }

    public static void calculateProjectedSize(Context context, Tier tier, boolean pullCompanionData, String langCode, String overrideVariant, PlanResultListener listener) {
        new Thread(() -> {
            // Live OS size (with offline fallback), resolved through the layered slice.
            // Safe to call synchronously here: calculateProjectedSize already runs
            // on a background Thread.
            double os = resolveOsSizeGb(tier);
            double maps = 0.0;

            if (!pullCompanionData) {
                StorageProjection res = new StorageProjection(os, 0, 0, "N/A", null);
                new Handler(Looper.getMainLooper()).post(() -> listener.onCalculated(res));
                return;
            }

            switch (tier) {
                case BASIC:
                    maps = MAPS_BASIC_GB;
                    break;
                case STANDARD:
                    maps = MAPS_STANDARD_GB;
                    break;
                case FULL:
                    maps = MAPS_FULL_GB;
                    break;
            }

            double kiwixLimit = 0.0;
            switch (tier) {
                case BASIC:
                    kiwixLimit = KIWIX_LIMIT_BASIC;
                    break;
                case STANDARD:
                    kiwixLimit = KIWIX_LIMIT_STANDARD;
                    break;
                case FULL:
                    kiwixLimit = KIWIX_LIMIT_FULL;
                    break;
            }

            final double finalOs = os;
            final double finalMaps = maps;
            final double finalLimit = kiwixLimit;

            getOrFetchCatalog(context, new CacheListener() {
                @Override
                public void onReady(JSONObject catalog) {
                    double kiwixSize = 0.0;
                    String resolvedLang = langCode;
                    String resolvedFile = null;

                    JSONObject langData = catalog.optJSONObject(langCode);

                    if (langData == null) {
                        langData = catalog.optJSONObject("en");
                        resolvedLang = "en";
                    }

                    if (langData != null) {
                        if (overrideVariant != null && langData.has(overrideVariant)) {
                            JSONObject vData = langData.optJSONObject(overrideVariant);
                            // VDATA NULL PROTECTION
                            if (vData != null) {
                                kiwixSize = vData.optDouble("size", 0.0);
                                resolvedFile = vData.optString("file", null);
                            }
                        } else {
                            String[] priorities = {"all_maxi", "top1m_maxi", "all_nopic", "top1m_nopic", "top_maxi", "all_mini", "top_nopic"};
                            for (String p : priorities) {
                                if (langData.has(p)) {
                                    JSONObject vData = langData.optJSONObject(p);
                                    // VDATA NULL PROTECTION
                                    if (vData != null) {
                                        double size = vData.optDouble("size", 0.0);
                                        if (size <= finalLimit) {
                                            kiwixSize = size;
                                            resolvedFile = vData.optString("file", null);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    StorageProjection res = new StorageProjection(finalOs, finalMaps, kiwixSize, resolvedLang, resolvedFile);
                    listener.onCalculated(res);
                }

                @Override
                public void onError(String error) {
                    listener.onError(error);
                }
            });
        }).start();
    }

    /**
     * Resolves the OS rootfs size for a tier, in GiB, using the layered rootfs
     * slice: live size from the Deploy server when reachable, hardcoded fallback
     * otherwise. The ABI is detected from the device.
     *
     * <p>Performs a (cached) network call; must run off the main thread — it does,
     * because every caller is inside {@link #calculateProjectedSize}'s worker thread.
     */
    private static double resolveOsSizeGb(Tier tier) {
        RootfsCatalog catalog = new RootfsCatalog();
        RootfsRepository repository =
                new RootfsRepositoryImpl(new RootfsRemoteDataSource(), catalog);
        GetRootfsSizeUseCase useCase = new GetRootfsSizeUseCase(repository);
        Rootfs rootfs = useCase.execute(toDomainTier(tier), catalog.detectAbi());
        return ByteFormatter.toGiB(rootfs.sizeBytes());
    }

    /** Maps the legacy {@link Tier} to the domain {@link RootfsTier}. */
    private static RootfsTier toDomainTier(Tier tier) {
        switch (tier) {
            case STANDARD:
                return RootfsTier.STANDARD;
            case FULL:
                return RootfsTier.FULL;
            case BASIC:
            default:
                return RootfsTier.BASIC;
        }
    }
}