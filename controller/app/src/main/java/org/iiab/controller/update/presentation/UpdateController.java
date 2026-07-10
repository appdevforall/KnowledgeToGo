/*
 * ============================================================================
 * Name        : UpdateController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : OTA self-updater, carved out of MainActivity (ADFA-4576, F1,
 *               first slice of the MainActivity god-class carve). Owns the
 *               update check, the download (staged in the app's PRIVATE external
 *               dir), same-certificate signature verification, the in-app
 *               progress dialog and the install hand-off. Sits on the layered
 *               update/ slice (UpdateViewModel / ApkVerifier from F15).
 *               Activity-scoped; MainActivity forwards onResume/onPause and the
 *               version-footer tap. Behaviour-preserving.
 * ============================================================================
 */
package org.iiab.controller.update.presentation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import org.iiab.controller.ui.dialog.BrandDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.R;
import org.iiab.controller.update.data.ApkVerifier;
import org.iiab.controller.util.AppExecutors;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class UpdateController {

    private static final String TAG = "IIAB-UpdateController";
    private static final long COOLDOWN_MS = 10_000L;
    private static final String UPDATE_JSON = "https://iiab.switnet.org/android/apk/update.json";
    private static final String APK_BASE_URL = "https://iiab.switnet.org/android/apk/";

    private final AppCompatActivity activity;

    private long updateDownloadId = -1;
    private AlertDialog updateProgressDialog;
    private UpdateViewModel updateViewModel;
    private long lastUpdateCheckTime = 0;

    public UpdateController(AppCompatActivity activity) {
        this.activity = activity;
    }

    // --- lifecycle: forwarded from MainActivity.onResume/onPause ---------------

    public void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
    }

    public void unregisterDownloadReceiver() {
        try {
            activity.unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException e) {
            // Ignore if it wasn't registered
        }
    }

    /** Manual trigger (version-footer tap/click) with a 10s cooldown + toast. */
    public void checkForUpdatesManual() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheckTime < COOLDOWN_MS) {
            Toast.makeText(activity, R.string.ota_toast_cooldown, Toast.LENGTH_SHORT).show();
            return;
        }
        lastUpdateCheckTime = now;
        checkForUpdates(true);
    }

    public void checkForUpdates(boolean isManual) {
        if (isManual) {
            activity.runOnUiThread(() -> Toast.makeText(activity, R.string.ota_toast_checking, Toast.LENGTH_SHORT).show());
        }

        AppExecutors.get().io().execute(() -> {
            try {
                Log.d(TAG, "OTA: Connecting to " + UPDATE_JSON);
                URL url = new URL(UPDATE_JSON);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "OTA: HTTP response code: " + responseCode);

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    org.json.JSONObject json = new org.json.JSONObject(response.toString());

                    int serverVersionCodeBase = json.getInt("versionCodeBase");
                    String serverVersionName = json.getString("versionName");
                    String changelog = json.getString("changelog");

                    int currentVersionCode = 0;
                    try {
                        currentVersionCode = activity.getPackageManager()
                                .getPackageInfo(activity.getPackageName(), 0).versionCode;
                    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "OTA: Could not get local version code", e);
                    }
                    int localVersionCodeBase = currentVersionCode / 10;

                    Log.d(TAG, "OTA: Server Base=" + serverVersionCodeBase + " | Local Base=" + localVersionCodeBase + " (Raw Local: " + currentVersionCode + ")");

                    if (serverVersionCodeBase > localVersionCodeBase) {
                        String deviceArch = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
                        String apkKey = "apk_universal";
                        if (deviceArch.contains("arm64") || deviceArch.contains("aarch64")) {
                            apkKey = "apk_arm64_v8a";
                        } else if (deviceArch.contains("armeabi") || deviceArch.contains("armv7")) {
                            apkKey = "apk_armeabi_v7a";
                        }
                        String apkName = json.optString(apkKey, json.optString("apk_universal"));
                        String downloadUrl = APK_BASE_URL + apkName;
                        activity.runOnUiThread(() -> showUpdateDialog(serverVersionName, changelog, downloadUrl));
                    } else if (isManual) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, R.string.ota_toast_latest, Toast.LENGTH_LONG).show());
                    }
                } else if (isManual) {
                    final int rc = responseCode;
                    activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.ota_toast_error_server, rc), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "OTA: Critical error checking for updates", e);
                if (isManual) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, R.string.ota_toast_error_network, Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void showUpdateDialog(String versionName, String changelog, String downloadUrl) {
        new BrandDialog(activity)
                .setTitle(activity.getString(R.string.update_dialog_title, versionName))
                .setMessage(activity.getString(R.string.update_dialog_message, changelog))
                .setPositive(R.string.update_dialog_positive, () -> startDownload(downloadUrl))
                .setNegative(R.string.update_dialog_negative, null)
                .setCancelable(false)
                .show();
    }

    private void startDownload(String downloadUrl) {
        String apkName = android.net.Uri.parse(downloadUrl).getLastPathSegment();
        if (apkName == null || !apkName.endsWith(".apk")) {
            apkName = "iiab_update.apk";
        }

        activity.getSharedPreferences(activity.getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .edit().putString("ota_apk_name", apkName).apply();

        File oldApk = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName);
        if (oldApk.exists()) {
            oldApk.delete();
        }

        android.app.DownloadManager.Request request =
                new android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl));
        request.setTitle(activity.getString(R.string.download_title));
        request.setDescription(activity.getString(R.string.download_description));
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        // F15: stage in the app's PRIVATE external dir (not public Downloads).
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, apkName);

        android.app.DownloadManager manager =
                (android.app.DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            updateDownloadId = manager.enqueue(request);
            getUpdateViewModel().track(updateDownloadId);
            showUpdateProgressDialog();
        }
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != updateDownloadId) {
                return;
            }
            // F15: only install if the download actually SUCCEEDED. DownloadManager
            // reports completion even when the server returned an error/HTML page.
            if (isDownloadSuccessful(id)) {
                File apk = verifyDownloadedApk();
                if (apk != null) {
                    getUpdateViewModel().onReady();
                } else {
                    getUpdateViewModel().onError(activity.getString(R.string.ota_error_verify_failed));
                    Toast.makeText(context, R.string.ota_error_verify_failed, Toast.LENGTH_LONG).show();
                }
            } else {
                getUpdateViewModel().onError(activity.getString(R.string.ota_error_download_failed));
                Log.e(TAG, "OTA: download did not complete successfully; not installing.");
                Toast.makeText(context, R.string.ota_error_download_failed, Toast.LENGTH_LONG).show();
            }
        }
    };

    /** Did the DownloadManager job with this id finish with STATUS_SUCCESSFUL? */
    private boolean isDownloadSuccessful(long id) {
        android.app.DownloadManager manager =
                (android.app.DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return false;
        }
        try (android.database.Cursor c =
                     manager.query(new android.app.DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                return idx >= 0 && c.getInt(idx) == android.app.DownloadManager.STATUS_SUCCESSFUL;
            }
        } catch (Exception e) {
            Log.e(TAG, "OTA: error querying download status", e);
        }
        return false;
    }

    /** Verify the staged APK exists and is signed by this app's certificate. Returns the file, or null. */
    private File verifyDownloadedApk() {
        String apkName = activity.getSharedPreferences(activity.getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .getString("ota_apk_name", "iiab_update.apk");

        File apkFile = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName);

        if (!apkFile.exists()) {
            Log.e(TAG, "OTA: Downloaded APK file not found at " + apkFile.getAbsolutePath());
            return null;
        }
        // F15: verify the APK is signed by the SAME certificate as this app before installing.
        if (!ApkVerifier.isSignedBySameCertAsApp(activity, apkFile)) {
            Log.e(TAG, "OTA: APK failed signature verification; deleting and aborting install.");
            apkFile.delete();
            return null;
        }
        return apkFile;
    }

    /** Launch the system installer for the (re-)verified APK. Invoked from the dialog's Install button. */
    private void launchInstaller() {
        File apkFile = verifyDownloadedApk();
        if (apkFile == null) {
            getUpdateViewModel().onError(activity.getString(R.string.ota_error_verify_failed));
            Toast.makeText(activity, R.string.ota_error_verify_failed, Toast.LENGTH_LONG).show();
            return;
        }

        // F15: on API 26+ the user must allow this app to install packages.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.ota_msg_enable_unknown_sources, Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                Log.e(TAG, "OTA: could not open unknown-sources settings", e);
            }
            return;
        }

        getUpdateViewModel().onInstalling();

        Intent intent = new Intent(Intent.ACTION_VIEW);
        android.net.Uri apkUri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".provider", apkFile);

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        List<android.content.pm.ResolveInfo> resInfoList = activity.getPackageManager()
                .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            activity.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "OTA: Error launching installer", e);
            Toast.makeText(activity, R.string.ota_error_launching_installer, Toast.LENGTH_LONG).show();
        }
    }

    // ---- in-app OTA progress dialog (PR B) ------------------------------------

    private UpdateViewModel getUpdateViewModel() {
        if (updateViewModel == null) {
            updateViewModel = new ViewModelProvider(activity, new UpdateViewModelFactory(activity))
                    .get(UpdateViewModel.class);
            updateViewModel.state().observe(activity, this::renderUpdateState);
        }
        return updateViewModel;
    }

    private void showUpdateProgressDialog() {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_ota_progress, null);
        AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle(R.string.ota_progress_title)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(R.string.ota_btn_install, null)
                .setNegativeButton(R.string.ota_btn_cancel, null)
                .create();
        d.setOnShowListener(dlg -> {
            Button install = d.getButton(AlertDialog.BUTTON_POSITIVE);
            Button cancel = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (install != null) install.setOnClickListener(v -> launchInstaller());
            if (cancel != null) cancel.setOnClickListener(v -> {
                UpdateUiState st = getUpdateViewModel().state().getValue();
                boolean terminal = st != null && (st.status == UpdateUiState.Status.READY
                        || st.status == UpdateUiState.Status.ERROR
                        || st.status == UpdateUiState.Status.INSTALLING);
                if (!terminal) {
                    getUpdateViewModel().cancel();
                }
                d.dismiss();
            });
            renderUpdateState(getUpdateViewModel().state().getValue());
        });
        updateProgressDialog = d;
        d.show();
    }

    private void renderUpdateState(UpdateUiState s) {
        if (updateProgressDialog == null || s == null || !updateProgressDialog.isShowing()) {
            return;
        }
        ProgressBar bar = updateProgressDialog.findViewById(R.id.ota_progress);
        TextView status = updateProgressDialog.findViewById(R.id.ota_status);
        TextView percent = updateProgressDialog.findViewById(R.id.ota_percent);
        Button install = updateProgressDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button cancel = updateProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        boolean determinate = s.status == UpdateUiState.Status.DOWNLOADING && !s.indeterminate && s.percent >= 0;
        if (bar != null) {
            bar.setIndeterminate(!determinate);
            if (determinate) bar.setProgress(s.percent);
        }
        if (percent != null) {
            percent.setVisibility(determinate ? View.VISIBLE : View.GONE);
            if (determinate) percent.setText(activity.getString(R.string.ota_progress_percent, s.percent));
        }

        switch (s.status) {
            case DOWNLOADING:
                if (status != null) status.setText(R.string.ota_status_downloading);
                if (install != null) install.setEnabled(false);
                if (cancel != null) cancel.setEnabled(true);
                break;
            case VERIFYING:
                if (status != null) status.setText(R.string.ota_status_verifying);
                if (install != null) install.setEnabled(false);
                if (cancel != null) cancel.setEnabled(true);
                break;
            case READY:
                if (status != null) status.setText(R.string.ota_status_ready);
                if (install != null) install.setEnabled(true);
                if (cancel != null) cancel.setEnabled(true);
                break;
            case INSTALLING:
                if (status != null) status.setText(R.string.ota_status_installing);
                if (install != null) install.setEnabled(false);
                if (cancel != null) cancel.setEnabled(false);
                break;
            case ERROR:
                if (status != null) status.setText(s.message != null ? s.message : activity.getString(R.string.ota_status_error));
                if (install != null) install.setEnabled(false);
                if (cancel != null) cancel.setEnabled(true);
                break;
            default:
                break;
        }
    }
}
