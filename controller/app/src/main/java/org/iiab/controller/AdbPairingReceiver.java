package org.iiab.controller;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import org.iiab.controller.util.AppExecutors;

public class AdbPairingReceiver extends BroadcastReceiver {

    public static final String KEY_PIN_REPLY = "key_pin_reply";
    public static final int NOTIFICATION_ID = 9401;
    public static final int RETURN_NOTIFICATION_ID = 9402;
    private static final String TAG = "AdbPairingNative";


    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput != null) {
            CharSequence pinSequence = remoteInput.getCharSequence(KEY_PIN_REPLY);
            int connectPort = intent.getIntExtra("connectPort", -1);
            int pairingPort = intent.getIntExtra("pairingPort", -1);

            String hostIp = intent.getStringExtra("hostIp");
            if (hostIp == null) hostIp = "127.0.0.1";

            if (pinSequence != null && connectPort != -1 && pairingPort != -1) {
                String pin = pinSequence.toString().trim();

                if (pin.length() == 6) {
                    // Update notification to remove the loading spinner
                    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "adb_pairing_channel")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle("Pairing initiated")
                                .setContentText("Returning to app...");
                        nm.notify(NOTIFICATION_ID, builder.build());
                        new Handler(Looper.getMainLooper()).postDelayed(() -> nm.cancel(NOTIFICATION_ID), 1500);
                    }

                    performNativePairing(context.getApplicationContext(), hostIp, pairingPort, pin);

                } else {
                    Toast.makeText(context, context.getString(R.string.adb_invalid_pin_length), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void performNativePairing(Context context, String hostIp, int pairingPort, String pin) {
        AppExecutors.get().io().execute(() -> {
            try {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(context);
                Log.d(TAG, "Attempting pairing on " + hostIp + ":" + pairingPort);
                boolean isPaired = adbManager.pair(hostIp, pairingPort, pin);

                if (isPaired) {
                    Log.i(TAG, "Native Pairing SUCCESSFUL! Forcing app to foreground...");

                    // Save success state to disk
                    context.getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("pairing_just_succeeded", true)
                            .apply();

                    // ADFA-4725 (overlay decision, Option B): no SAW / no background
                    // startActivity. Post an OS-sanctioned "tap to return" notification that
                    // opens the new UI (LibraryActivity). Reliable on restrictive phones.
                    Intent open = new Intent(context, org.iiab.controller.redesign.LibraryActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                               ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
                    android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, 0, open, piFlags);
                    NotificationManager nmReturn = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nmReturn != null) {
                        NotificationCompat.Builder rb = new NotificationCompat.Builder(context, "adb_pairing_channel")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle("Paired")
                                .setContentText("Tap to return to K2Go")
                                .setAutoCancel(true)
                                .setContentIntent(pi);
                        nmReturn.notify(RETURN_NOTIFICATION_ID, rb.build());
                    }

                } else {
                    Log.e(TAG, "Native pairing failed.");
                    showToastOnMainThread(context, "Pairing failed. Please check the PIN and try again.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in native ADB pairing", e);
                showToastOnMainThread(context, "Error: " + e.getMessage());
            }
        });
    }

    private void showToastOnMainThread(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }
}