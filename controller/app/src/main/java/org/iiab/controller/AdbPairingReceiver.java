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
                    Toast.makeText(context, "Invalid PIN length. Must be 6 digits.", Toast.LENGTH_LONG).show();
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

                    // CRITICAL: Bring our app back to the screen automatically!
                    Intent bringToFront = new Intent(context, MainActivity.class);
                    bringToFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(bringToFront);

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