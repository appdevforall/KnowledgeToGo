/*
 * ============================================================================
 * Name        : TerminalController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The "ninja terminal" carved out of MainActivity (ADFA-4577).
 *               Owns the Termux TerminalView, the multi-session list + drawer
 *               adapter, the bottom-sheet, font size and lock state. Bound once
 *               from MainActivity.onCreate via bind(); openFullTerminal() is the
 *               version-footer long-press entry point. Activity-scoped; the two
 *               Activity-side callbacks (addToLog, vibrateDevice) come through
 *               Host. Behaviour-preserving.
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.iiab.controller.util.AppExecutors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class TerminalController {

    /** Activity-side callbacks the terminal needs. */
    public interface Host {
        void addToLog(String message);
        void vibrateDevice();
    }

    private static final String TAG = "IIAB-Terminal";

    private final AppCompatActivity activity;
    private final Host host;

    private float currentTerminalFontSize = 32f;
    private com.termux.view.TerminalView terminalView;
    private com.termux.terminal.TerminalSession terminalSession;
    private com.google.android.material.bottomsheet.BottomSheetBehavior<View> bottomSheetBehavior;
    private boolean isTerminalLocked = true;
    private List<com.termux.terminal.TerminalSession> terminalSessionsList = new ArrayList<>();
    private android.widget.ArrayAdapter<com.termux.terminal.TerminalSession> sessionsAdapter;

    public TerminalController(AppCompatActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    /** Wires the terminal views/adapters/listeners. Call once from onCreate. */
    public void bind() {
        // Configure hidden bottom sheet
        View bottomSheet = activity.findViewById(R.id.terminal_bottom_sheet);
        terminalView = activity.findViewById(R.id.terminal_view);
        bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
        bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);

        // MULTI-SESSION DRAWER SETUP
        // =========================================================
        androidx.drawerlayout.widget.DrawerLayout drawerLayout = activity.findViewById(R.id.drawer_layout);
        android.widget.ListView drawerList = activity.findViewById(R.id.left_drawer_list);
        android.widget.Button newSessionBtn = activity.findViewById(R.id.new_session_button);

        // Custom Adapter to display session names (e.g. "Session 1", "Session 2")
        sessionsAdapter = new android.widget.ArrayAdapter<com.termux.terminal.TerminalSession>(
                activity,
                android.R.layout.simple_list_item_1,
                terminalSessionsList
        ) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = view.findViewById(android.R.id.text1);
                text.setTextColor(android.graphics.Color.WHITE);
                text.setText("[" + (position + 1) + "]");
                return view;
            }
        };

        if (drawerList != null) {
            drawerList.setAdapter(sessionsAdapter);

            // Switch session when clicked
            drawerList.setOnItemClickListener((parent, view, position, id) -> {
                terminalSession = terminalSessionsList.get(position);
                if (terminalView != null) {
                    terminalView.attachSession(terminalSession);

                    // Termux style indicator
                    Toast toast = Toast.makeText(activity, "[" + (position + 1) + "]", Toast.LENGTH_SHORT);
                    toast.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, 150);
                    toast.show();
                }
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
                }
            });
        }

        if (newSessionBtn != null) {
            newSessionBtn.setOnClickListener(btn -> {
                addNewSession(); // Spawn new shell
                if (terminalView != null) {
                    terminalView.attachSession(terminalSession); // Switch to the new one immediately

                    // Termux style indicator for the newly created session
                    int newSessionNumber = terminalSessionsList.size();
                    Toast toast = Toast.makeText(activity, "[" + newSessionNumber + "]", Toast.LENGTH_SHORT);
                    toast.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, 150);
                    toast.show();
                }
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
                }
            });
        }

        // =========================================================
        // TERMINAL LOCK/UNLOCK LOGIC
        // =========================================================
        View terminalDragHandle = activity.findViewById(R.id.terminal_drag_handle_area);
        if (terminalDragHandle != null) {
            terminalDragHandle.setOnLongClickListener(v -> {
                // Vibrate to provide tactile feedback
                host.vibrateDevice();

                // Toggle the lock state
                isTerminalLocked = !isTerminalLocked;

                // Lock means it CANNOT be dragged (scroll is safe). Unlock means it CAN be dragged.
                bottomSheetBehavior.setDraggable(!isTerminalLocked);

                // Notify the user
                int msgResId = isTerminalLocked ? R.string.terminal_locked : R.string.terminal_unlocked;
                Toast.makeText(activity, msgResId, Toast.LENGTH_SHORT).show();

                return true; // Event consumed, prevents normal click processing
            });
        }

    }

    /** Version-footer long-press: reset sessions, spawn one, expand + lock the sheet. */
    public void openFullTerminal() {
                try {
                    // 3 seconds reached! Expand terminal.
                    host.vibrateDevice();

                    // --- TERMINAL RESET NUKE ---
                    if (terminalSessionsList != null) {
                        for (com.termux.terminal.TerminalSession s : terminalSessionsList) {
                            s.finishIfRunning();
                        }
                        terminalSessionsList.clear();
                    }
                    terminalSession = null;

                    // Spawn the first session
                    addNewSession();

                    // 1. Force the view to be VISIBLE before expanding
                    View targetSheet = activity.findViewById(R.id.terminal_bottom_sheet);
                    if (targetSheet.getVisibility() != View.VISIBLE) {
                        targetSheet.setVisibility(View.VISIBLE);
                    }

                    // 2. Bring view to front
                    targetSheet.bringToFront();
                    targetSheet.requestLayout();

                    // 3. Change to EXPANDED (100% screen)
                    bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                    // --- APPLY LOCK BY DEFAULT ---
                    isTerminalLocked = true;
                    bottomSheetBehavior.setDraggable(false);

                    terminalView.requestFocus();

                } catch (Throwable t) {
                    // CATCH ABSOLUTELY EVERYTHING (Even native JNI crashes)
                    new androidx.appcompat.app.AlertDialog.Builder(activity)
                            .setTitle(R.string.terminal_crash_title)
                            .setMessage(android.util.Log.getStackTraceString(t))
                            .setPositiveButton("OK", null)
                            .show();
                }
    }

    public void addNewSession() {
        com.termux.terminal.TerminalSessionClient client = new com.termux.terminal.TerminalSessionClient() {
            @Override
            public void onTextChanged(com.termux.terminal.TerminalSession session) {
                activity.runOnUiThread(() -> terminalView.invalidate());
            }

            @Override
            public void onTitleChanged(com.termux.terminal.TerminalSession session) {
            }

            // --- THE CLEAN SHUTDOWN (When Bash dies and tells us) ---
            @Override
            public void onSessionFinished(com.termux.terminal.TerminalSession session) {
                activity.runOnUiThread(() -> {
                    // 1. Remove the dead session from our list and update the UI Drawer
                    if (terminalSessionsList != null) {
                        terminalSessionsList.remove(session);
                        if (sessionsAdapter != null) sessionsAdapter.notifyDataSetChanged();
                    }

                    // 2. Are we looking at the session that just died?
                    if (terminalSession == session) {
                        if (terminalSessionsList != null && !terminalSessionsList.isEmpty()) {
                            // Fallback: Jump to the last available active session
                            terminalSession = terminalSessionsList.get(terminalSessionsList.size() - 1);
                            if (terminalView != null) terminalView.attachSession(terminalSession);
                        } else {
                            // No sessions left! Close the terminal panel entirely.
                            terminalSession = null;
                            View bottomSheet = activity.findViewById(R.id.terminal_bottom_sheet);
                            if (bottomSheet != null) {
                                com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
                            }
                        }
                    }
                });
            }

            @Override
            public void onCopyTextToClipboard(com.termux.terminal.TerminalSession session, String text) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("terminal", text);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    activity.runOnUiThread(() -> Toast.makeText(activity, R.string.terminal_copied_toast, Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onPasteTextFromClipboard(com.termux.terminal.TerminalSession session) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                    // ADFA-4519: only write non-empty text. An empty clipboard item yields an
                    // empty string, and TerminalSession.write("") reaches ByteQueue.write with
                    // length 0, which throws IllegalArgumentException("length <= 0") and crashes.
                    if (text != null && text.length() > 0 && terminalSession != null) {
                        terminalSession.write(text.toString());
                    }
                }
            }

            @Override
            public void onBell(com.termux.terminal.TerminalSession session) {
            }

            @Override
            public void onColorsChanged(com.termux.terminal.TerminalSession session) {
            }

            @Override
            public void onTerminalCursorStateChange(boolean state) {
            }

            @Override
            public Integer getTerminalCursorStyle() {
                return 0;
            }

            @Override
            public void setTerminalShellPid(com.termux.terminal.TerminalSession session, int pid) {
            }

            @Override
            public void logError(String tag, String message) {
                Log.e(tag, message);
            }

            @Override
            public void logWarn(String tag, String message) {
                Log.w(tag, message);
            }

            @Override
            public void logInfo(String tag, String message) {
                Log.i(tag, message);
            }

            @Override
            public void logDebug(String tag, String message) {
                Log.d(tag, message);
            }

            @Override
            public void logVerbose(String tag, String message) {
                Log.v(tag, message);
            }

            @Override
            public void logStackTraceWithMessage(String tag, String message, Exception e) {
                Log.e(tag, message, e);
            }

            @Override
            public void logStackTrace(String tag, Exception e) {
                Log.e(tag, "Stack trace", e);
            }
        };

        try {
            // --- DUAL SYSTEM ARCHITECTURE: THE HOST SHELL ---
            // Instead of diving directly into a fragile PRoot guest environment,
            // we drop the expert user into the native Android Host Shell.
            // From here, they can debug, clear directories, or manually trigger PRoot.

            String hostShell = "/system/bin/sh";
            File workingDirectory = activity.getFilesDir(); // Start in the app's secure root

            try {
                File hostBinDir = new File(activity.getFilesDir(), "usr/bin");
                if (!hostBinDir.exists()) hostBinDir.mkdirs();

                // =========================================================
                // 0.5 EXTRACT BUNDLED CA-CERTIFICATES (The Browser Model)
                // =========================================================
                File caCertFile = new File(activity.getFilesDir(), "cacert.pem");
                if (!caCertFile.exists()) {
                    try {
                        java.io.InputStream in = activity.getAssets().open("cacert.pem");
                        java.io.FileOutputStream out = new java.io.FileOutputStream(caCertFile);
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.close();
                        in.close();
                        Log.i(TAG, "cacert.pem extracted successfully.");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to extract bundled cacert.pem", e);
                    }
                }

                // =========================================================
                // LINK NINJA BINARIES (Expose .so files as normal commands)
                // =========================================================
                String nativeDir = activity.getApplicationInfo().nativeLibraryDir;
                String[] ninjaBinaries = {"aria2c", "tar", "xz", "gzip", "rsync", "proot", "nano", "less"};

                for (String bin : ninjaBinaries) {
                    File soFile = new File(nativeDir, "lib" + bin + ".so");
                    File binFile = new File(hostBinDir, bin);

                    if (soFile.exists()) {
                        // We always delete the old link to avoid "Dangling Symlinks"
                        // caused by APK updates that change the path hash.
                        binFile.delete();

                        try {
                            android.system.Os.symlink(soFile.getAbsolutePath(), binFile.getAbsolutePath());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to symlink " + bin, e);
                        }
                    } else {
                        // Shout out if the binary is not on disk!
                        Log.e(TAG, "CRITICAL: Native library missing! " + soFile.getAbsolutePath());
                        activity.runOnUiThread(() -> host.addToLog(activity.getString(R.string.log_critical_binary_missing, bin)));
                    }
                }

                // -------------------------------------------------------------
                // GENERATE ORCHESTRATOR CLI 'iiab' (Monolithic Tool)
                // -------------------------------------------------------------
                File rootfsDir = new File(activity.getFilesDir(), "rootfs/installed-rootfs/iiab");
                File libproot = new File(activity.getApplicationInfo().nativeLibraryDir, "libproot.so");
                File prootLoader = new File(activity.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
                File prootLoader32 = new File(activity.getApplicationInfo().nativeLibraryDir, "libproot-loader32.so");
                File tmpDir = new File(activity.getFilesDir(), "proot_tmp");
                if (!tmpDir.exists()) tmpDir.mkdirs();

                File iiabCliScript = new File(hostBinDir, "iiab");
                StringBuilder cliStr = new StringBuilder();
                cliStr.append("#!/system/bin/sh\n\n");

                // Global script variables
                File backupsDir = new File(activity.getFilesDir(), "rootfs/backups");
                cliStr.append("ROOTFS_DIR=\"").append(rootfsDir.getAbsolutePath()).append("\"\n");
                cliStr.append("BACKUPS_DIR=\"").append(backupsDir.getAbsolutePath()).append("\"\n");
                cliStr.append("export PROOT_TMP_DIR=\"").append(tmpDir.getAbsolutePath()).append("\"\n");
                cliStr.append("export PROOT_LOADER=\"").append(prootLoader.getAbsolutePath()).append("\"\n");
                cliStr.append("export PROOT_LOADER_32=\"").append(prootLoader32.getAbsolutePath()).append("\"\n\n");

                // Initialize Mount Flags
                cliStr.append("MOUNT_SDCARD=false\n");
                cliStr.append("MOUNT_BACKUPS=false\n\n");

                // Reusable login function (Purified with env PATH, explicit fake sysdata, and dynamic mounts)
                cliStr.append("do_login() {\n");
                cliStr.append("    echo -e '\\033[32mPreparing Debian Environment...\\033[0m'\n");

//                // --- SECURITY CHECK FOR SDCARD ---
//                cliStr.append("    if [ \"$MOUNT_SDCARD\" = true ]; then\n");
//                cliStr.append("        echo -e '\\033[33m[Security] Requesting biometric unlock for SD Card access...\\033[0m'\n");
//                cliStr.append("        # Clean previous flags\n");
//                cliStr.append("        rm -f \"$PROOT_TMP_DIR/.auth_success\" \"$PROOT_TMP_DIR/.auth_failed\"\n");
//                cliStr.append("        # Trigger UI Authentication\n");
//                cliStr.append("        am broadcast --user 0 -a org.iiab.ACTION_UNLOCK_SDCARD -p org.iiab.controller >/dev/null 2>&1\n");
//                cliStr.append("        \n");
//                cliStr.append("        # Wait for Java to write the result flag (Timeout after 30s)\n");
//                cliStr.append("        WAIT_TIME=0\n");
//                cliStr.append("        while [ ! -f \"$PROOT_TMP_DIR/.auth_success\" ] && [ ! -f \"$PROOT_TMP_DIR/.auth_failed\" ]; do\n");
//                cliStr.append("            sleep 1\n");
//                cliStr.append("            WAIT_TIME=$((WAIT_TIME + 1))\n");
//                cliStr.append("            if [ $WAIT_TIME -ge 30 ]; then\n");
//                cliStr.append("                echo -e '\\033[31m[Error] Authentication timed out.\\033[0m'\n");
//                cliStr.append("                exit 1\n");
//                cliStr.append("            fi\n");
//                cliStr.append("        done\n");
//                cliStr.append("        \n");
//                cliStr.append("        if [ -f \"$PROOT_TMP_DIR/.auth_failed\" ]; then\n");
//                cliStr.append("            echo -e '\\033[31m[Error] Authentication failed or cancelled. Access denied.\\033[0m'\n");
//                cliStr.append("            exit 1\n");
//                cliStr.append("        fi\n");
//                cliStr.append("        echo -e '\\033[32m[Success] SD Card access granted.\\033[0m'\n");
//                cliStr.append("    fi\n\n");

                // 1. Calculate native Android btime & uptime directly in Bash
                cliStr.append("    up_sec=$(awk '{print $1}' /proc/uptime 2>/dev/null || echo 1000)\n");
                cliStr.append("    now_sec=$(date +%s 2>/dev/null || echo 1716000000)\n");
                cliStr.append("    btime=$(awk -v up=\"$up_sec\" -v now=\"$now_sec\" 'BEGIN {printf \"%d\", now - up}')\n");

                // 2. Inject fresh data in-sync to bypass Android proc restrictions cleanly
                cliStr.append("    mkdir -p \"$ROOTFS_DIR/proc\" 2>/dev/null\n");
                cliStr.append("    echo \"$up_sec $up_sec\" > \"$ROOTFS_DIR/proc/.uptime\"\n");
                cliStr.append("    echo \"cpu  1000 0 1000 10000 0 0 0 0 0 0\" > \"$ROOTFS_DIR/proc/.stat\"\n");
                cliStr.append("    echo \"btime $btime\" >> \"$ROOTFS_DIR/proc/.stat\"\n");
                cliStr.append("    echo \"Linux version 6.17.0-PRoot-IIAB (builder@iiab) (Android NDK) #1 SMP PREEMPT Thu Apr 30 20:00:00 UTC 2026\" > \"$ROOTFS_DIR/proc/.version\"\n");
                cliStr.append("    echo \"0.00 0.00 0.00 1/1 1\" > \"$ROOTFS_DIR/proc/.loadavg\"\n");

                // 3. Build the PRoot Command dynamically based on flags
                cliStr.append("    PROOT_CMD=\"").append(libproot.getAbsolutePath()).append(" --sysvipc -0 --link2symlink -k 6.1.0 -r \\\"$ROOTFS_DIR\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b /dev -b /proc -b /sys\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.stat:/proc/stat\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.uptime:/proc/uptime\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.version:/proc/version\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.loadavg:/proc/loadavg\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$PROOT_TMP_DIR\\\":/tmp\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$PROOT_TMP_DIR\\\":/dev/shm\"\n");

                // Conditionally append risky mounts
                cliStr.append("    if [ \"$MOUNT_SDCARD\" = true ]; then\n");
                cliStr.append("        PROOT_CMD=\"$PROOT_CMD -b /storage/emulated/0:/sdcard\"\n");
                cliStr.append("    fi\n");
                cliStr.append("    if [ \"$MOUNT_BACKUPS\" = true ]; then\n");
                cliStr.append("        PROOT_CMD=\"$PROOT_CMD -b \\\"$BACKUPS_DIR:/backups\\\"\"\n");
                cliStr.append("    fi\n");

                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -w /root /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color /bin/bash -l -i\"\n");

                // Execute!
                cliStr.append("    echo -e '\\033[32mEntering Jail...\\033[0m'\n");
                cliStr.append("    eval \"$PROOT_CMD\"\n");
                cliStr.append("}\n\n");

                // CLI arguments handling (Now loops to parse multiple flags)
                cliStr.append("ACTION=\"login\"\n");
                cliStr.append("while [ $# -gt 0 ]; do\n");
                cliStr.append("  case \"$1\" in\n");
                cliStr.append("    --reset)\n");
                cliStr.append("      ACTION=\"reset\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --backup-rootfs)\n");
                cliStr.append("      ACTION=\"backup\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --restore-rootfs)\n");
                cliStr.append("      ACTION=\"restore\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    -l|--login)\n");
                cliStr.append("      ACTION=\"login\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --mount-sdcard)\n");
                cliStr.append("      MOUNT_SDCARD=true\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --mount-backups)\n");
                cliStr.append("      MOUNT_BACKUPS=true\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    -h|--help)\n");
                cliStr.append("      echo 'Knowledge To Go CLI'\n");
                cliStr.append("      echo 'Usage: iiab [COMMAND] [OPTIONS]'\n");
                cliStr.append("      echo 'Commands:'\n");
                cliStr.append("      echo '  -l, --login        Enter the Debian environment'\n");
                cliStr.append("      echo '  --reset            Wipe system and reinstall Debian base'\n");
                cliStr.append("      echo '  --backup-rootfs    Trigger a system backup'\n");
                cliStr.append("      echo '  --restore-rootfs   Trigger a system restore'\n");
                cliStr.append("      echo 'Options for login:'\n");
                cliStr.append("      echo '  --mount-backups    Mount the app backups directory at /backups'\n");
                cliStr.append("      echo '  --mount-sdcard     Mount the Android SD Card at /sdcard (Requires Biometrics)'\n");
                cliStr.append("      exit 0\n");
                cliStr.append("      ;;\n");
                cliStr.append("    *)\n");
                cliStr.append("      # Unknown flag\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("  esac\n");
                cliStr.append("done\n\n");

                // Execute based on ACTION
                cliStr.append("if [ \"$ACTION\" = \"reset\" ]; then\n");
                cliStr.append("    echo -e '\\033[31m[WARNING] This will DESTROY the current Debian installation!\\033[0m'\n");
                cliStr.append("    echo -n 'Are you sure you want to reset the system? [y/N]: '\n");
                cliStr.append("    read ans\n");
                cliStr.append("    if [ \"$ans\" != \"y\" ] && [ \"$ans\" != \"Y\" ]; then echo 'Aborted.'; exit 0; fi\n\n");
                cliStr.append("    DL_DIR=\"/storage/emulated/0/Download\"\n");
                cliStr.append("    ARCH=$(uname -m)\n");
                cliStr.append("    if [ \"$ARCH\" = \"aarch64\" ]; then TERMUX_ARCH=\"aarch64\"; else TERMUX_ARCH=\"arm\"; fi\n");
                cliStr.append("    TARBALL=\"debian-trixie-${TERMUX_ARCH}-pd-v4.29.0.tar.xz\"\n");
                cliStr.append("    URL=\"https://iiab.switnet.org/android/rootfs/proot-distro-v4.29.0/${TARBALL}\"\n");
                cliStr.append("    CA_CERT=\"").append(caCertFile.getAbsolutePath()).append("\"\n\n");
                cliStr.append("    echo -e '\\n\\033[36m[1/4] Wiping current environment...\\033[0m'\n");
                cliStr.append("    rm -rf \"$ROOTFS_DIR\" 2>/dev/null || true\n");
                cliStr.append("    mkdir -p \"$ROOTFS_DIR\"\n\n");
                cliStr.append("    echo -e '\\033[36m[2/4] Downloading clean Debian base...\\033[0m'\n");
                cliStr.append("    if [ ! -f \"$DL_DIR/$TARBALL\" ]; then\n");
                cliStr.append("        aria2c --ca-certificate=\"$CA_CERT\" --dir=\"$DL_DIR\" --out=\"$TARBALL\" \"$URL\" || { echo -e '\\033[31mDownload failed!\\033[0m'; exit 1; }\n");
                cliStr.append("    else\n");
                cliStr.append("        echo 'Base tarball found in Downloads. Skipping download.'\n");
                cliStr.append("    fi\n\n");
                cliStr.append("    echo -e '\\033[36m[3/4] Extracting Debian (This may take a minute)...\\033[0m'\n");
                cliStr.append("    tar --exclude='*/dev/*' --strip-components=1 -xJf \"$DL_DIR/$TARBALL\" -C \"$ROOTFS_DIR\" || true\n\n");
                cliStr.append("    echo -e '\\033[36m[4/4] Bootstrapping environment via PRoot...\\033[0m'\n");
                cliStr.append("    rm -f \"$ROOTFS_DIR/etc/resolv.conf\" 2>/dev/null || true\n");
                cliStr.append("    echo 'nameserver 1.1.1.1' > \"$ROOTFS_DIR/etc/resolv.conf\"\n");
                cliStr.append("    echo 'nameserver 8.8.8.8' >> \"$ROOTFS_DIR/etc/resolv.conf\"\n");
                cliStr.append("    echo '127.0.0.1 localhost' > \"$ROOTFS_DIR/etc/hosts\"\n");
                cliStr.append("    ").append(libproot.getAbsolutePath()).append(" --sysvipc -0 --link2symlink -k 6.1.0 -r \"$ROOTFS_DIR\" \\\n");
                cliStr.append("      -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard \\\n");
                cliStr.append("      -b \"$PROOT_TMP_DIR\":/tmp \\\n");
                cliStr.append("      -b \"$PROOT_TMP_DIR\":/dev/shm \\\n");
                cliStr.append("      -w /root /bin/bash -c '" +
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                        "export DEBIAN_FRONTEND=noninteractive && " +
                        "apt-get update && apt-get install -y curl ca-certificates nano sudo && " +
                        "curl -fsSL https://raw.githubusercontent.com/appdevforall/KnowledgeToGo/main/iiab-android -o /usr/local/sbin/iiab-android && " +
                        "chmod +x /usr/local/sbin/iiab-android && " +
                        "apt-get clean && apt-get autoremove -y && " +
                        "rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache'\n\n");
                cliStr.append("    echo -e '\\n\\033[32m[SUCCESS] System is clean and ready!\\033[0m'\n");

                cliStr.append("elif [ \"$ACTION\" = \"backup\" ]; then\n");
                cliStr.append("    echo -e '\\033[33m[iiab]\\033[0m Triggering backup in UI...'\n");
                cliStr.append("    am broadcast -a org.iiab.ACTION_BACKUP_ROOTFS -p org.iiab.controller >/dev/null 2>&1\n");
                cliStr.append("elif [ \"$ACTION\" = \"restore\" ]; then\n");
                cliStr.append("    echo -e '\\033[33m[iiab]\\033[0m Triggering restore in UI...'\n");
                cliStr.append("    am broadcast -a org.iiab.ACTION_RESTORE_ROOTFS -p org.iiab.controller >/dev/null 2>&1\n");
                cliStr.append("else\n");
                cliStr.append("    if [ ! -f \"$ROOTFS_DIR/usr/bin/env\" ]; then\n");
                cliStr.append("        echo -e \"\\033[1;31m[ERROR]\\033[0m Debian is not installed or rootfs is missing!\"\n");
                cliStr.append("        exit 1\n");
                cliStr.append("    fi\n");
                cliStr.append("    do_login\n");
                cliStr.append("fi\n");

                java.io.FileOutputStream fosCli = new java.io.FileOutputStream(iiabCliScript);
                fosCli.write(cliStr.toString().getBytes());
                fosCli.close();
                iiabCliScript.setExecutable(true);

            } catch (Exception e) {
                Log.e(TAG, "Failed to create host scripts", e);
            }
            // =========================================================
            // GENERATE MOTD (.profile)
            // =========================================================
            File profileFile = new File(workingDirectory, ".profile");
            try {
                java.io.FileOutputStream fosProfile = new java.io.FileOutputStream(profileFile);
                StringBuilder profile = new StringBuilder();

                // 1. Header (ASCII Art)
                profile.append("clear\n");
                profile.append("echo -e \"\\033[1;36m\"\n");
                profile.append("echo \" _  ______   ____ \"\n");
                profile.append("echo \"| |/ /___ \\ / ___| ___ \"\n");
                profile.append("echo \"| ' /  __) | |  _ / _ \\ \"\n");
                profile.append("echo \"| . \\ / __/| |_| | (_) | \"\n");
                profile.append("echo \"|_|\\_\\_____|\\____|\\___/ \"\n");
                profile.append("echo -e \"\\033[0m\"\n");
                profile.append("echo -e \"\\033[1;32m  T E R M I N A L\\033[0m\\n\"\n");

                // 2. The Mission / Welcome
                profile.append("echo -e \"Welcome to the native \\033[1;36mK2Go Host Shell\\033[0m.\"\n");
                profile.append("echo \"\"\n");
                profile.append("echo -e \"\\033[1mKnowledge To Go\\033[0m will allow millions of\"\n");
                profile.append("echo \"people worldwide to build their own family\"\n");
                profile.append("echo \"libraries, inside their own phones!\"\n");
                profile.append("echo \"\"\n");
                profile.append("echo \"This terminal helps you build and transform\"\n");
                profile.append("echo \"your Android device as an Offline Learning\"\n");
                profile.append("echo \"Environment powered by Internet-in-a-Box.\"\n");
                profile.append("echo \"\"\n");

                // 3. Context & Warnings
                profile.append("echo -e \"\\033[1;33mNOTE:\\033[0m You are currently OUTSIDE the Debian\"\n");
                profile.append("echo \"environment.\"\n");
                profile.append("echo \"Package managers (apt/pkg) are NOT available here.\"\n");
                profile.append("echo \"To install packages and manage the server, login\"\n");
                profile.append("echo \"to Debian using the command below:\"\n");
                profile.append("echo \"\"\n");

                // 4. Helpful Commands
                profile.append("echo -e \"  \\033[1;32miiab --login\\033[0m  Login to Debian PRoot (Default)\"\n");
                profile.append("echo -e \"  \\033[1;32miiab --help\\033[0m   Show orchestrator commands\"\n");
                profile.append("echo \"\"\n");

                // 5. Links and Resources
                profile.append("echo \"Online resources:\"\n");
                profile.append("echo -e \"\\033[1;33m*\\033[0m 🌐: \\033[1mhttps://appdevforall.org\\033[0m\"\n");
                profile.append("echo -e \"\\033[1;33m*\\033[0m 📖: \\033[1mhttps://github.com/appdevforall/KnowledgeToGo\\033[0m\"\n");
                profile.append("echo -e \"\\033[1;33m*\\033[0m 🔗: \\033[1mhttps://internet-in-a-box.org\\033[0m\"\n");
                profile.append("echo \"\"\n");

                // 6. Custom Prompt (PS1)
                profile.append("export PS1=\"\\033[1;36m[Host]\\033[0m:~\\$ \"\n");

                fosProfile.write(profile.toString().getBytes());
                fosProfile.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create .profile MOTD", e);
            }

            // =========================================================
            // GENERATE MKSHRC (To defeat Android's default prompt override)
            // =========================================================
            File mkshrcFile = new File(workingDirectory, ".mkshrc");
            try {
                java.io.FileOutputStream fosMkshrc = new java.io.FileOutputStream(mkshrcFile);
                StringBuilder mkshrc = new StringBuilder();
                // 1. Load system defaults first (crucial to keep backspace and history working)
                mkshrc.append("[ -f /system/etc/mkshrc ] && . /system/etc/mkshrc\n");
                // 2. Crush the system prompt with our custom one
                mkshrc.append("export PS1=\"~$ \"\n");
                fosMkshrc.write(mkshrc.toString().getBytes());
                fosMkshrc.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create .mkshrc", e);
            }

            // Minimal, bulletproof environment variables
            String[] env = new String[]{
                    "TERM=xterm-256color",
                    "HOME=" + workingDirectory.getAbsolutePath(),
                    "ENV=" + mkshrcFile.getAbsolutePath(),
                    // Include the system bins and our W^X fake prefix bins
                    "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:" + workingDirectory.getAbsolutePath() + "/usr/bin"
            };

            // Launch the native Android shell!
            terminalSession = new com.termux.terminal.TerminalSession(
                    hostShell,
                    workingDirectory.getAbsolutePath(),
                    new String[]{"-l"}, // Login shell flag
                    env,
                    5000, // <--- Careful to increase, all lines (per session) are stored in RAM
                    client
            );
            terminalView.setTextSize((int) currentTerminalFontSize);
            // Add to our multi-session list
            terminalSessionsList.add(terminalSession);
            if (sessionsAdapter != null) {
                activity.runOnUiThread(() -> sessionsAdapter.notifyDataSetChanged());
            }

            // --- VIEW CLIENT (Touches, Zoom & Keyboard) ---
            terminalView.setTerminalViewClient(new com.termux.view.TerminalViewClient() {
                @Override
                public float onScale(float scale) {
                    currentTerminalFontSize *= scale;

                    if (currentTerminalFontSize < 10f) currentTerminalFontSize = 10f;
                    if (currentTerminalFontSize > 80f) currentTerminalFontSize = 80f;

                    terminalView.setTextSize((int) currentTerminalFontSize);
                    return 1.0f;
                }

                // --- THE ESCAPE TAP (Crucial for Debugging Limbo Sessions) ---
                @Override
                public void onSingleTapUp(android.view.MotionEvent e) {
                    // If the terminal process is dead (e.g., has crashed or shown "[Process completed]"),
                    // a single tap on the black screen hides the panel and nullifies the session.
                    // If the CURRENT terminal process is dead, close the panel and kill ALL sessions.
                    if (terminalSession != null && !terminalSession.isRunning()) {
                        activity.runOnUiThread(() -> {
                            View bottomSheet = activity.findViewById(R.id.terminal_bottom_sheet);
                            if (bottomSheet != null) {
                                com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
                            }

                            // The Guillotine: Kill all active sessions securely
                            if (terminalSessionsList != null) {
                                for (com.termux.terminal.TerminalSession s : terminalSessionsList) {
                                    s.finishIfRunning();
                                }
                                terminalSessionsList.clear();
                            }
                            terminalSession = null;
                            if (sessionsAdapter != null) sessionsAdapter.notifyDataSetChanged();
                        });
                        return; // Event consumed.
                    }

                    // --- NORMAL BEHAVIOR FOR ALIVE SESSIONS (Focus and Keyboard) ---
                    terminalView.setFocusable(true);
                    terminalView.setFocusableInTouchMode(true);
                    terminalView.requestFocus();

                    terminalView.post(() -> {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(terminalView, 0);
                    });
                }

                @Override
                public boolean onLongPress(android.view.MotionEvent e) {
                    return false;
                }

                @Override
                public boolean shouldBackButtonBeMappedToEscape() {
                    return false;
                }

                @Override
                public boolean shouldEnforceCharBasedInput() {
                    return false;
                }

                @Override
                public boolean shouldUseCtrlSpaceWorkaround() {
                    return false;
                }

                @Override
                public boolean isTerminalViewSelected() {
                    return true;
                }

                @Override
                public void copyModeChanged(boolean copyMode) {
                }

                // --- THE ESCAPE HATCH: Listen for hardware ENTER key on dead sessions ---
                @Override
                public boolean onKeyDown(int keyCode, android.view.KeyEvent e, com.termux.terminal.TerminalSession session) {
                    // Return false to let the terminal emulator handle keys internally.
                    // This allows onSessionFinished to trigger if keys are processed.
                    return false;
                }

                @Override
                public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
                    return false;
                }

                @Override
                public boolean readControlKey() {
                    com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView = activity.findViewById(R.id.extra_keys_view);
                    if (extraKeysView != null) {
                        // Polling the CTRL state natively from the view!
                        Boolean state = extraKeysView.readSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.CTRL, true);
                        return state != null && state;
                    }
                    return false;
                }

                @Override
                public boolean readAltKey() {
                    com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView = activity.findViewById(R.id.extra_keys_view);
                    if (extraKeysView != null) {
                        // Polling the ALT state natively from the view!
                        Boolean state = extraKeysView.readSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.ALT, true);
                        return state != null && state;
                    }
                    return false;
                }

                @Override
                public boolean readShiftKey() {
                    return false;
                }

                @Override
                public boolean readFnKey() {
                    return false;
                }

                @Override
                public boolean onCodePoint(int codePoint, boolean ctrlDown, com.termux.terminal.TerminalSession session) {
                    return false;
                }

                @Override
                public void onEmulatorSet() {
                }

                @Override
                public void logError(String tag, String message) {
                }

                @Override
                public void logWarn(String tag, String message) {
                }

                @Override
                public void logInfo(String tag, String message) {
                }

                @Override
                public void logDebug(String tag, String message) {
                }

                @Override
                public void logVerbose(String tag, String message) {
                }

                @Override
                public void logStackTraceWithMessage(String tag, String message, Exception e) {
                }

                @Override
                public void logStackTrace(String tag, Exception e) {
                }
            });
            // We wait for the view to be drawn before attaching the session to ensure the terminal is ready.
            terminalView.post(() -> {
                if (terminalSession != null) {
                    terminalView.attachSession(terminalSession);

                    // =========================================================
                    // IIAB NATIVE KEYBOARD INTEGRATION
                    // =========================================================
                    try {
                        com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView =
                                activity.findViewById(R.id.extra_keys_view);

                        if (extraKeysView != null) {
                            IIABExtraKeys.apply(extraKeysView);

                            // Listen for normal keys (ESC, TAB, UP, etc)
                            extraKeysView.setExtraKeysViewClient(new com.termux.shared.termux.extrakeys.ExtraKeysView.IExtraKeysView() {
                                @Override
                                public void onExtraKeyButtonClick(View view, com.termux.shared.termux.extrakeys.ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
                                    if (terminalSession != null) {
                                        String key = buttonInfo.getKey();
                                        switch (key) {
                                            case "ESC":
                                                terminalSession.write("\033");
                                                break;
                                            case "TAB":
                                                terminalSession.write("\t");
                                                break;
                                            case "UP":
                                                terminalSession.write("\033[A");
                                                break;
                                            case "DOWN":
                                                terminalSession.write("\033[B");
                                                break;
                                            case "RIGHT":
                                                terminalSession.write("\033[C");
                                                break;
                                            case "LEFT":
                                                terminalSession.write("\033[D");
                                                break;
                                            // --- NEW ORIGINAL TERMUX KEYS ---
                                            case "HOME":
                                                terminalSession.write("\033[1~");
                                                break;
                                            case "END":
                                                terminalSession.write("\033[4~");
                                                break;
                                            case "PGUP":
                                                terminalSession.write("\033[5~");
                                                break;
                                            case "PGDN":
                                                terminalSession.write("\033[6~");
                                                break;
                                            default:
                                                terminalSession.write(key);
                                                break;
                                        }
                                    }
                                }

                                @Override
                                public boolean performExtraKeyButtonHapticFeedback(View view, com.termux.shared.termux.extrakeys.ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
                                    return false; // Let Termux handle the vibration natively
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize Native ExtraKeys", e);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to start Terminal Session", e);
        }
    }
}
