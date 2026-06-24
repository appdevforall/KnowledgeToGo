/*
 * ============================================================================
 * Name        : BackupController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Backup/restore presentation logic carved out of DeployFragment
 *               (strangler-fig). Owns the backup selection state + SAF launchers
 *               and wires the backup/restore/import buttons + the backup menu.
 *               Being a non-Fragment class, it removes this large, deeply-nested
 *               call graph from the Fragment that the androidx
 *               UnsafeFragmentLifecycleObserverDetector walks (the lint hang;
 *               see controller/docs/TECH_DEBT_PLAN.md). No behaviour change.
 * ============================================================================
 */
package org.iiab.controller.backup.presentation;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.iiab.controller.MainActivity;
import org.iiab.controller.ProgressButton;
import org.iiab.controller.R;
import org.iiab.controller.TarExtractor;
import org.iiab.controller.util.ProcessRunner;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class BackupController {

    private static final String TAG = "IIAB-BackupController";
    private static final String[] IMPORT_SPINNER = {"\u28BF", "\u28FB", "\u28FD", "\u28FE", "\u28F7", "\u28EF", "\u28DF", "\u287F"};

    private final Fragment fragment;
    private final BackupHost host;

    // Set in bind() (cross-feature views are borrowed; the Fragment also touches some).
    private MainActivity mainAct;
    private File backupsDir;
    private File iiabRootDir;
    private Button btnImportBackup;
    private ProgressButton btnAdvancedBackup;
    private ProgressButton btnAdvancedRestore;
    private TextView txtSelectBackupTitle;
    private TextView txtBackupStatus;
    private LinearLayout containerBackupList;
    private LinearLayout restoreLogPanel;
    private TextView restoreLogText;
    private TextView restoreLogResult;
    private NestedScrollView restoreLogScroll;

    // Owned state (only the backup/restore feature touches these).
    private String selectedBackupFile = null;
    private ActivityResultLauncher<String[]> importBackupLauncher;
    private ActivityResultLauncher<String> exportBackupLauncher;
    private android.os.Handler importSpinnerHandler;
    private int importSpinnerFrame = 0;

    public BackupController(Fragment fragment, BackupHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Wire the backup/restore/import controls. Call from onViewCreated. */
    public void bind(MainActivity mainAct, File backupsDir, File iiabRootDir,
                     Button btnImportBackup, ProgressButton btnAdvancedBackup, ProgressButton btnAdvancedRestore,
                     TextView txtSelectBackupTitle, TextView txtBackupStatus, LinearLayout containerBackupList,
                     LinearLayout restoreLogPanel, TextView restoreLogText, TextView restoreLogResult,
                     NestedScrollView restoreLogScroll) {
        this.mainAct = mainAct;
        this.backupsDir = backupsDir;
        this.iiabRootDir = iiabRootDir;
        this.btnImportBackup = btnImportBackup;
        this.btnAdvancedBackup = btnAdvancedBackup;
        this.btnAdvancedRestore = btnAdvancedRestore;
        this.txtSelectBackupTitle = txtSelectBackupTitle;
        this.txtBackupStatus = txtBackupStatus;
        this.containerBackupList = containerBackupList;
        this.restoreLogPanel = restoreLogPanel;
        this.restoreLogText = restoreLogText;
        this.restoreLogResult = restoreLogResult;
        this.restoreLogScroll = restoreLogScroll;
        bindBackupButtonLogic();
        bindBackupMenuLogic();
        refreshRestoreButtonLogic();
    }

    public void registerLaunchers() {
        importBackupLauncher = fragment.registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importBackupSafely(uri);
        });

        exportBackupLauncher = fragment.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
            if (uri != null && selectedBackupFile != null)
                exportBackupSafely(uri, selectedBackupFile);
        });
    }

    private void bindBackupButtonLogic() {
        if (btnAdvancedBackup == null) return;
        btnAdvancedBackup.setOnClickListener(v -> {
            if (mainAct.isServerAlive) {
                Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (host.isSystemBusy() && !host.isBackupInProgress()) {
                Snackbar.make(v, host.getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                return;
            }
            if (host.isBackupInProgress()) {
                new android.app.AlertDialog.Builder(fragment.requireContext())
                        .setTitle(fragment.getString(R.string.install_msg_backup_in_progress_title))
                        .setMessage(fragment.getString(R.string.install_msg_backup_in_progress_body))
                        .setPositiveButton(fragment.getString(R.string.install_btn_force_stop_process), (dialog, which) -> {
                            host.setBackupInProgress(false);
                            btnAdvancedBackup.setText(fragment.getString(R.string.install_btn_backup)); btnAdvancedBackup.stopProgress();
                            Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_backup_aborted), Snackbar.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(fragment.getString(R.string.install_btn_let_finish), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return;
            }

            host.setBackupInProgress(true);
            btnAdvancedBackup.setText(fragment.getString(R.string.install_msg_compressing));
            btnAdvancedBackup.startProgress();
            Snackbar.make(v, fragment.getString(R.string.install_msg_creating_backup), Snackbar.LENGTH_LONG).show();

            new Thread(() -> {
                host.enableSystemProtection();
                try {
                    // Format: iiab-oa_rootfs_$year.$day_of_year_3_digits_$id_$arch.tar.gz
                    java.util.Calendar calendar = java.util.Calendar.getInstance();
                    int year = calendar.get(java.util.Calendar.YEAR);
                    int dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR);
                    String arch = host.getTermuxArch();

                    // --- AUTO-INCREMENTAL ID LOGIC ---
                    android.content.SharedPreferences prefs = fragment.requireContext().getSharedPreferences(fragment.getString(R.string.pref_file_internal), Context.MODE_PRIVATE);

                    // We check if we continue on the same day. If it is a new day, we reset the ID to 1
                    int lastSavedDay = prefs.getInt("backup_last_day", -1);
                    int currentId;

                    if (lastSavedDay == dayOfYear) {
                        // Same day, we increase the ID
                        currentId = prefs.getInt("backup_daily_id", 0) + 1;
                    } else {
                        // New day, we start from 1
                        currentId = 1;
                        prefs.edit().putInt("backup_last_day", dayOfYear).apply();
                    }

                    // We save the new ID in preferences for next time
                    prefs.edit().putInt("backup_daily_id", currentId).apply();

                    // We construct the final name with the ID
                    String fileName = String.format(java.util.Locale.US, "iiab-oa_%04d.%03d_%d_%s.tar.gz", year, dayOfYear, currentId, arch);
                    File backupFile = new File(backupsDir, fileName);

                    File staticTar = new File(fragment.requireContext().getApplicationInfo().nativeLibraryDir, "libtar.so");
                    File staticGzip = new File(fragment.requireContext().getApplicationInfo().nativeLibraryDir, "libgzip.so");
                    String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
                    String gzipBin = staticGzip.exists() ? staticGzip.getAbsolutePath() : "gzip";

                    // Stamp an identity manifest into the backup so a re-import is
                    // recognized (kind/arch) AND explicitly declares it carries NO
                    // integrity checksum (origin=device-backup) — we do NOT turn the
                    // phone into a builder. It is staged in a temp tree and packed
                    // FIRST (a second `-C`) so RootfsArchiveValidator reads it from
                    // the first tar header without decompressing the whole archive.
                    // See docs/ROOTFS_MANIFEST.md.
                    String manifestArg = null;
                    File mfStageRoot = new File(fragment.requireContext().getCacheDir(), "mfstage");
                    try {
                        if (mfStageRoot.exists()) {
                            ProcessRunner.run(new String[]{"rm", "-rf", mfStageRoot.getAbsolutePath()});
                        }
                        File iiabStage = new File(mfStageRoot, "installed-rootfs/iiab");
                        if (iiabStage.mkdirs()) {
                            String appAbi = org.iiab.controller.deploy.data.RootfsManifest.appAbiId();
                            String debArch = appAbi.contains("64") ? "arm64" : "armhf";
                            String built = String.format(java.util.Locale.US, "%04d.%03d", year, dayOfYear);
                            String identityJson = "{\"schema\":1,\"kind\":\"iiab-rootfs\",\"arch\":\""
                                    + appAbi + "\",\"deb_arch\":\"" + debArch + "\",\"built\":\""
                                    + built + "\",\"builder\":\"knowledgetogo-app\",\"origin\":\"device-backup\"}";
                            java.io.FileOutputStream mfo =
                                    new java.io.FileOutputStream(new File(iiabStage, ".iiab-rootfs.json"));
                            mfo.write(identityJson.getBytes("UTF-8"));
                            mfo.close();
                            manifestArg = "-C '" + mfStageRoot.getAbsolutePath()
                                    + "' 'installed-rootfs/iiab/.iiab-rootfs.json' ";
                        }
                    } catch (Exception mfe) {
                        Log.w(TAG, "Could not stage identity manifest for backup: " + mfe.getMessage());
                        manifestArg = null;
                    }

                    // D11: single-quote the interpolated paths so the backup pipe is robust
                    // even if a path ever contains spaces/metacharacters (app-internal today).
                    String cmd = "'" + tarBin + "' -cf - "
                            + (manifestArg != null ? manifestArg : "")
                            + "-C '" + iiabRootDir.getAbsolutePath()
                            + "' installed-rootfs | '" + gzipBin + "' > '" + backupFile.getAbsolutePath() + "'";
                    // D12: ProcessRunner drains stderr so a large backup with tar warnings
                    // cannot deadlock on a full pipe buffer.
                    ProcessRunner.Result backupResult = ProcessRunner.run(new String[]{"/system/bin/sh", "-c", cmd});
                    int exitCode = backupResult.exitCode;
                    if (exitCode != 0) {
                        Log.w(TAG, "Backup pipe failed (exit " + exitCode + "): " + backupResult.output);
                    }

                    mainAct.runOnUiThread(() -> {
                        if (host.isBackupInProgress()) {
                            if (exitCode == 0) {
                                Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_backup_complete, backupFile.getName()), Snackbar.LENGTH_LONG).show();
                                selectedBackupFile = backupFile.getName();
                            } else {
                                Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_backup_failed, exitCode), Snackbar.LENGTH_LONG).show();
                                if (backupFile.exists()) backupFile.delete();

                                // If it fails, we revert the ID so as not to waste numbers
                                prefs.edit().putInt("backup_daily_id", currentId - 1).apply();
                            }
                        } else {
                            if (backupFile.exists()) backupFile.delete();
                            prefs.edit().putInt("backup_daily_id", currentId - 1).apply();
                        }
                        host.setBackupInProgress(false);
                        btnAdvancedBackup.setText(fragment.getString(R.string.install_btn_backup)); btnAdvancedBackup.stopProgress();
                        host.updateDynamicButtons();
                        host.disableSystemProtection();
                    });
                } catch (Exception e) {
                    mainAct.runOnUiThread(() -> {
                        host.setBackupInProgress(false);
                        btnAdvancedBackup.setText(fragment.getString(R.string.install_btn_backup)); btnAdvancedBackup.stopProgress();
                        Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_backup_error, e.getMessage()), Snackbar.LENGTH_LONG).show();
                        host.updateDynamicButtons();
                        host.disableSystemProtection();
                    });
                }
            }).start();
        });

        if (btnImportBackup != null) {
            // 1. We load the native icon
            android.graphics.drawable.Drawable importIcon = ContextCompat.getDrawable(fragment.requireContext(), android.R.drawable.stat_sys_download);
            if (importIcon != null) {
                importIcon.setTint(ContextCompat.getColor(fragment.requireContext(), R.color.status_success));
                btnImportBackup.setCompoundDrawablesWithIntrinsicBounds(importIcon, null, null, null);
                btnImportBackup.setCompoundDrawablePadding(24);

                // 2. We center the content internally
                btnImportBackup.setGravity(android.view.Gravity.CENTER);
                btnImportBackup.setPadding(0, 0, 0, 0);

                // 3. We change the width to wrap_content and center the button in its container
                if (btnImportBackup.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) btnImportBackup.getLayoutParams();
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    params.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                    btnImportBackup.setLayoutParams(params);
                }
            }

            btnImportBackup.setOnClickListener(v -> {
                importBackupLauncher.launch(new String[]{"application/gzip", "application/x-gzip", "*/*"});
            });
        }
    }

    private void bindBackupMenuLogic() {
        if (txtSelectBackupTitle == null) return;
        txtSelectBackupTitle.setOnClickListener(v -> {
            boolean isCollapsed = containerBackupList.getVisibility() == View.GONE;
            if (isCollapsed) {
                containerBackupList.setVisibility(View.VISIBLE);
                txtSelectBackupTitle.setText(fragment.getString(R.string.install_adv_select_backup_open));
                containerBackupList.removeAllViews();
                selectedBackupFile = null;

                File[] backups = backupsDir.listFiles((dir, name) -> name.endsWith(".tar.gz") || name.endsWith(".tar.xz"));
                if (backups == null || backups.length == 0) {
                    TextView noBackups = new TextView(fragment.requireContext());
                    noBackups.setText(fragment.getString(R.string.install_msg_no_backups));
                    noBackups.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger));
                    containerBackupList.addView(noBackups);
                } else {
                    java.util.Arrays.sort(backups, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

                    LinearLayout listContainer = new LinearLayout(fragment.requireContext());
                    listContainer.setOrientation(LinearLayout.VERTICAL);

                    List<android.widget.RadioButton> radioButtons = new ArrayList<>();
                    int iconPadding = (int) (12 * fragment.getResources().getDisplayMetrics().density);

                    // Variable to alternate colors (Zebra Effect)
                    boolean isEvenRow = true;

                    for (File b : backups) {
                        String filename = b.getName();
                        String size = String.format(java.util.Locale.US, "%.2f MB", b.length() / (1024.0 * 1024.0));
                        String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(b.lastModified()));

                        // MAIN ROW
                        LinearLayout row = new LinearLayout(fragment.requireContext());
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                        // Apply subtle alternating background color
                        if (isEvenRow) {
                            row.setBackgroundColor(ContextCompat.getColor(fragment.requireContext(), R.color.surface_section)); // Slightly lighter
                        } else {
                            row.setBackgroundColor(Color.TRANSPARENT); // Normal dark
                        }
                        isEvenRow = !isEvenRow; // Alternar para la siguiente fila

                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rowParams.setMargins(0, 0, 0, 8); // Separation between cards
                        row.setLayoutParams(rowParams);
                        row.setPadding(8, 8, 8, 8);

                        // RADIO BUTTON AND TEXT
                        android.widget.RadioButton rb = new android.widget.RadioButton(fragment.requireContext());
                        rb.setText(fragment.getString(R.string.install_msg_backup_details, filename, size, date));
                        rb.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_primary));
                        rb.setPadding(0, 8, 0, 8);
                        rb.setTag(filename);

                        LinearLayout.LayoutParams rbParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        rb.setLayoutParams(rbParams);
                        radioButtons.add(rb);

                        // Selection logic (Applied to the ENTIRE row, not just the radio button)
                        View.OnClickListener selectRowListener = rowView -> {
                            for (android.widget.RadioButton other : radioButtons) {
                                other.setChecked(other == rb);
                            }
                            selectedBackupFile = rb.isChecked() ? filename : null;
                            refreshRestoreButtonLogic();
                        };

                        // We assign the click to both the RadioButton and the parent Layout
                        rb.setOnClickListener(selectRowListener);
                        row.setOnClickListener(selectRowListener);

                        // EXPORT BUTTON
                        android.widget.ImageButton btnExport = new android.widget.ImageButton(fragment.requireContext());
                        btnExport.setImageResource(android.R.drawable.stat_sys_upload);
                        btnExport.setBackgroundColor(Color.TRANSPARENT);
                        btnExport.setColorFilter(ContextCompat.getColor(fragment.requireContext(), R.color.status_success));
                        btnExport.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);

                        btnExport.setOnClickListener(btn -> {
                            selectedBackupFile = filename;
                            for (android.widget.RadioButton other : radioButtons) {
                                other.setChecked(other == rb);
                            }
                            refreshRestoreButtonLogic();
                            exportBackupLauncher.launch(selectedBackupFile);
                        });

                        // DELETE BUTTON
                        android.widget.ImageButton btnDelete = new android.widget.ImageButton(fragment.requireContext());
                        btnDelete.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                        btnDelete.setBackgroundColor(Color.TRANSPARENT);
                        btnDelete.setColorFilter(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger));
                        btnDelete.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);

                        btnDelete.setOnClickListener(btn -> {
                            new android.app.AlertDialog.Builder(fragment.requireContext())
                                    .setTitle(R.string.install_dialog_delete_backup_title)
                                    .setMessage(fragment.getString(R.string.install_dialog_delete_backup_msg, filename))
                                    .setPositiveButton(R.string.install_btn_delete_confirm, (dialog, which) -> {
                                        File toDelete = new File(backupsDir, filename);
                                        if (toDelete.delete()) {
                                            if (filename.equals(selectedBackupFile)) selectedBackupFile = null;
                                            txtSelectBackupTitle.performClick();
                                            txtSelectBackupTitle.performClick();
                                            Snackbar.make(fragment.getView(), R.string.install_msg_backup_deleted, Snackbar.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        });

                        row.addView(rb);
                        row.addView(btnExport);
                        row.addView(btnDelete);

                        listContainer.addView(row);
                    }
                    containerBackupList.addView(listContainer);
                }
                refreshRestoreButtonLogic();
            } else {
                containerBackupList.setVisibility(View.GONE);
                txtSelectBackupTitle.setText(fragment.getString(R.string.install_adv_select_backup));
            }
        });
    }

    private void refreshRestoreButtonLogic() {
        MainActivity mainAct = (MainActivity) fragment.getActivity();
        if (mainAct == null || btnAdvancedRestore == null) return;

        if (mainAct.isServerAlive) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
            return;
        }

        if (selectedBackupFile == null) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_select_backup_first, Snackbar.LENGTH_LONG).show());
        } else {
            btnAdvancedRestore.setAlpha(1.0f);
            btnAdvancedRestore.setOnClickListener(v -> {
                if (mainAct.isServerAlive) {
                    Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                    return;
                }
                if (host.isSystemBusy()) {
                    Snackbar.make(v, host.getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                    return;
                }

                host.setRestoring(true);
                host.updateDynamicButtons();
                Snackbar.make(v, fragment.getString(R.string.install_msg_restore_starting, selectedBackupFile), Snackbar.LENGTH_SHORT).show();
                mainAct.invalidateModuleStateTrust();

                File backupFile = new File(new File(fragment.requireContext().getFilesDir(), "rootfs/backups"), selectedBackupFile);
                if (!backupFile.exists()) {
                    host.setRestoring(false);
                    host.updateDynamicButtons();
                    Snackbar.make(v, R.string.install_error_backup_missing, Snackbar.LENGTH_SHORT).show();
                    return;
                }

                btnAdvancedRestore.setEnabled(false);
                btnAdvancedRestore.setText(fragment.getString(R.string.install_status_restoring));
                btnAdvancedRestore.startProgress();
                if (restoreLogPanel != null) {
                    restoreLogPanel.setVisibility(View.VISIBLE);
                    if (restoreLogText != null) {
                        // Transparency at restore (the meaningful moment): note when the
                        // backup carries no integrity checksum / no manifest.
                        org.iiab.controller.deploy.data.RootfsManifest.Identity rid =
                                org.iiab.controller.deploy.data.RootfsManifest.read(backupFile.getAbsolutePath());
                        if (!rid.present) {
                            restoreLogText.setText(fragment.getString(R.string.install_warn_manifest_missing) + "\n\n");
                        } else if ("device-backup".equals(rid.origin)) {
                            restoreLogText.setText(fragment.getString(R.string.install_warn_no_checksum) + "\n\n");
                        } else {
                            restoreLogText.setText("");
                        }
                    }
                    if (restoreLogResult != null) restoreLogResult.setText("");
                }
                File iiabRootDir = new File(fragment.requireContext().getFilesDir(), "rootfs");
                TarExtractor tarExtractor = new TarExtractor();

                host.enableSystemProtection();
                tarExtractor.startExtraction(fragment.requireContext(), backupFile.getAbsolutePath(), iiabRootDir.getAbsolutePath(), true, new TarExtractor.ExtractionListener() {
                    @Override
                    public void onComplete(String destDir) {
                        mainAct.runOnUiThread(() -> {
                            host.setRestoring(false);
                            host.disableSystemProtection();
                            btnAdvancedRestore.setEnabled(true);
                            btnAdvancedRestore.setText(fragment.getString(R.string.install_btn_restore));
                            Snackbar.make(fragment.getView(), R.string.install_success_restore, Snackbar.LENGTH_LONG).show();
                            if (restoreLogResult != null) { restoreLogResult.setText("\u2713"); restoreLogResult.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_success)); }
                            btnAdvancedRestore.stopProgress();
                            host.updateDynamicButtons();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainAct.runOnUiThread(() -> {
                            host.setRestoring(false);
                            host.disableSystemProtection();
                            btnAdvancedRestore.setEnabled(true);
                            btnAdvancedRestore.setText(fragment.getString(R.string.install_btn_restore));
                            Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_restore_failed) + " " + error, Snackbar.LENGTH_LONG).show();
                            if (restoreLogResult != null) { restoreLogResult.setText("\u2717"); restoreLogResult.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_warning)); }
                            btnAdvancedRestore.stopProgress();
                            host.updateDynamicButtons();
                        });
                    }

                    @Override
                    public void onProgress(String line) {
                        if (restoreLogText == null) return;
                        restoreLogText.append(line + "\n");
                        if (restoreLogScroll != null) {
                            restoreLogScroll.post(() -> restoreLogScroll.fullScroll(View.FOCUS_DOWN));
                        }
                    }
                });
            });
        }
    }

    private void startImportSpinner() {
        stopImportSpinner();
        importSpinnerFrame = 0;
        importSpinnerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable r = new Runnable() {
            @Override public void run() {
                if (btnImportBackup != null) {
                    String f = IMPORT_SPINNER[importSpinnerFrame++ % IMPORT_SPINNER.length];
                    btnImportBackup.setText(fragment.getString(R.string.install_msg_importing) + "  " + f);
                }
                if (importSpinnerHandler != null) importSpinnerHandler.postDelayed(this, 90);
            }
        };
        importSpinnerHandler.post(r);
    }

    private void stopImportSpinner() {
        if (importSpinnerHandler != null) {
            importSpinnerHandler.removeCallbacksAndMessages(null);
            importSpinnerHandler = null;
        }
    }

    /** Best-effort original filename from a SAF content:// URI (DISPLAY_NAME), or null. */
    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = fragment.requireContext().getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "queryDisplayName failed: " + e.getMessage());
        }
        return null;
    }

    /** Show a Snackbar whose visible time scales with the message length (reading time). */
    private void showImportSnackbar(CharSequence text) {
        View v = fragment.getView();
        if (v != null) {
            Snackbar.make(v, text,
                    org.iiab.controller.util.SnackbarDuration.millisForText(text.toString())).show();
        }
    }

    private void importBackupSafely(Uri sourceUri) {
        host.setImporting(true);
        host.updateDynamicButtons();
        btnImportBackup.setEnabled(false);
        startImportSpinner();
        Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_importing), Snackbar.LENGTH_LONG).show();

        new Thread(() -> {
            host.enableSystemProtection();
            try {
                File backupsDir = new File(fragment.requireContext().getFilesDir(), "rootfs/backups");
                if (!backupsDir.exists()) backupsDir.mkdirs();

                // Keep the imported file's EXACT name; disambiguate with -1/-2/... on collision.
                String desiredName = queryDisplayName(sourceUri);
                java.util.Set<String> existingNames = new java.util.HashSet<>();
                File[] existingFiles = backupsDir.listFiles();
                if (existingFiles != null) {
                    for (File f : existingFiles) existingNames.add(f.getName());
                }
                String fileName = org.iiab.controller.backup.domain.BackupNameResolver.resolve(desiredName, existingNames);
                File destFile = new File(backupsDir, fileName);

                InputStream is = fragment.requireContext().getContentResolver().openInputStream(sourceUri);
                OutputStream os = new java.io.FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();
                os.close();
                is.close();

                // Gate the import: must be a valid rootfs of THIS app's architecture
                // (ABI policy). Reject and delete otherwise.
                org.iiab.controller.deploy.data.RootfsArchiveValidator.Result vr =
                        org.iiab.controller.deploy.data.RootfsArchiveValidator
                                .validate(fragment.requireContext(), destFile.getAbsolutePath());
                boolean okValidated =
                        vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK;
                boolean okNoManifest =
                        vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK_NO_MANIFEST;
                boolean okNoChecksum =
                        vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK_NO_CHECKSUM;
                if (!okValidated && !okNoManifest && !okNoChecksum) {
                    if (destFile.exists()) destFile.delete();
                    final int errMsg;
                    if (vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.WRONG_ARCH) {
                        errMsg = R.string.install_error_wrong_arch;
                    } else if (vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.CORRUPT) {
                        errMsg = R.string.install_error_corrupt;
                    } else {
                        errMsg = R.string.install_error_not_rootfs;
                    }
                    if (fragment.getActivity() != null) {
                        fragment.getActivity().runOnUiThread(() -> {
                            host.setImporting(false);
                            stopImportSpinner(); // stop the braille spinner; rejection ends the import
                            host.updateDynamicButtons();
                            btnImportBackup.setEnabled(true);
                            btnImportBackup.setText(fragment.getString(R.string.install_btn_import_backup));
                            showImportSnackbar(fragment.getString(errMsg));
                        });
                    }
                    return;
                }
                // Soft phase: no identity manifest -> import is allowed, but warn the
                // user (a future version will validate silently). See docs/ROOTFS_MANIFEST.md.
                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        host.setImporting(false);
                        stopImportSpinner();
                        btnImportBackup.setEnabled(true);
                        btnImportBackup.setText(fragment.getString(R.string.install_btn_import_backup));
                        selectedBackupFile = fileName;
                        host.updateDynamicButtons();
                        // One snackbar only (Snackbar replaces, never queues): fold the
                        // no-checksum / no-manifest transparency into the final message.
                        showImportSnackbar(fragment.getString(
                                okNoChecksum ? R.string.install_warn_no_checksum
                                        : okNoManifest ? R.string.install_warn_manifest_missing
                                        : R.string.install_msg_import_success));
                    });
                }
            } catch (Exception e) {
                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        host.setImporting(false);
                        stopImportSpinner();
                        host.updateDynamicButtons();
                        btnImportBackup.setEnabled(true);
                        btnImportBackup.setText(fragment.getString(R.string.install_btn_import_backup));
                        showImportSnackbar(fragment.getString(R.string.install_msg_import_failed, e.getMessage()));
                    });
                }
            } finally {
                host.disableSystemProtection();
            }
        }).start();
    }

    private void exportBackupSafely(Uri destUri, String backupFileName) {
        Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_exporting, backupFileName), Snackbar.LENGTH_LONG).show();

        new Thread(() -> {
            host.enableSystemProtection();
            try {
                File sourceFile = new File(new File(fragment.requireContext().getFilesDir(), "rootfs/backups"), backupFileName);
                InputStream is = new java.io.FileInputStream(sourceFile);
                OutputStream os = fragment.requireContext().getContentResolver().openOutputStream(destUri);
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();
                os.close();
                is.close();

                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_export_success), Snackbar.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        Snackbar.make(fragment.getView(), fragment.getString(R.string.install_msg_export_failed, e.getMessage()), Snackbar.LENGTH_LONG).show();
                    });
                }
            } finally {
                host.disableSystemProtection();
            }
        }).start();
    }
}
