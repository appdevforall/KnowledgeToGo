package org.appdevforall.xferbench;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * K2GO-576 transfer benchmark. Driven by adb, for example:
 *   am start -n org.appdevforall.xferbench/.MainActivity --es cmd tcp-recv --es method kernel --es sink int
 *   am start -n org.appdevforall.xferbench/.MainActivity --es cmd tcp-send --es host 192.168.1.66 --el mb 1024
 *   am start -n org.appdevforall.xferbench/.MainActivity --es cmd aoa-host --es dir S --el mb 256 --ei buf 16384
 * Each result goes to logcat (tag XferBench), to the screen and to files/results.txt.
 */
public class MainActivity extends Activity {
    private static final String TAG = "XferBench";
    private static final String ACTION_USB_PERMISSION = "org.appdevforall.xferbench.USB_PERMISSION";

    private TextView logView;
    private UsbManager usb;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.addView(button("AOA host: send 256 MiB", () -> aoaHost('S', "zeros", 256, 16384)));
        root.addView(button("AOA host: receive 256 MiB", () -> aoaHost('R', "zeros", 256, 16384)));
        root.addView(button("AOA accessory: wait for host", () -> aoaAccessory(null, 16384)));
        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv);
        setContentView(root);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent i) {
        if (i == null) return;
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(i.getAction())) {
            UsbAccessory a = i.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            run(() -> aoaAccessory(a, 16384));
            return;
        }
        String cmd = i.getStringExtra("cmd");
        if (cmd == null) return;
        String method = str(i, "method", "kernel");
        String pattern = str(i, "pattern", "zeros");
        long mb = i.getLongExtra("mb", 1024);
        int bufKb = i.getIntExtra("bufkb", 256);
        int port = i.getIntExtra("port", 5301);
        switch (cmd) {
            case "tcp-recv":
                run(() -> log(TcpBench.receive(port, method, sink(str(i, "sink", "null")), bufKb)));
                break;
            case "tcp-send":
                String src = str(i, "src", "pattern");
                run(() -> log(TcpBench.send(str(i, "host", ""), port, method, pattern, mb,
                        "pattern".equals(src) ? null : new File(dir(src), "src.bin"), bufKb)));
                break;
            case "prep":
                run(() -> log(TcpBench.prepare(new File(dir(str(i, "at", "int")), "src.bin"), pattern, mb)));
                break;
            case "aoa-host":
                char dirCmd = str(i, "dir", "S").charAt(0);
                int buf = i.getIntExtra("buf", 16384);
                run(() -> aoaHost(dirCmd, pattern, mb, buf));
                break;
            case "aoa-acc":
                run(() -> aoaAccessory(null, i.getIntExtra("buf", 16384)));
                break;
            default:
                log("unknown cmd " + cmd);
        }
    }

    private static String str(Intent i, String k, String def) {
        String v = i.getStringExtra(k);
        return v == null ? def : v;
    }

    /** "int" = app-private storage, "ext" = app folder on shared storage (FUSE). */
    private File dir(String at) {
        return "ext".equals(at) ? getExternalFilesDir(null) : getFilesDir();
    }

    private File sink(String at) {
        return "null".equals(at) ? null : new File(dir(at), "sink.bin");
    }

    // ---------------------------------------------------------------- AOA

    private void aoaHost(char cmd, String pattern, long mb, int buf) throws Exception {
        UsbDevice d = findDevice();
        if (d == null) { log("aoa-host: no USB device. Is this phone the USB host?"); return; }
        if (!AoaBench.isAccessoryMode(d)) {
            if (!permission(d)) { log("aoa-host: permission denied"); return; }
            int proto = AoaBench.switchToAccessory(usb, d);
            log("aoa-host: AOA version " + proto + ", waiting for accessory mode");
            d = waitForAccessoryMode();
            if (d == null) { log("aoa-host: the other phone did not come back in accessory mode"); return; }
            Thread.sleep(1500); // let the accessory app open its end
        }
        if (!permission(d)) { log("aoa-host: permission denied (accessory mode)"); return; }
        log(AoaBench.host(usb, d, cmd, pattern, mb, buf));
    }

    private void aoaAccessory(UsbAccessory a, int buf) throws Exception {
        if (a == null) {
            UsbAccessory[] list = usb.getAccessoryList();
            if (list == null || list.length == 0) { log("aoa-acc: no accessory. Start AOA on the host phone first"); return; }
            a = list[0];
        }
        if (!usb.hasPermission(a) && !permission(a)) { log("aoa-acc: permission denied"); return; }
        AoaBench.accessory(usb, a, buf, this::log);
    }

    private UsbDevice findDevice() {
        UsbDevice any = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (AoaBench.isAccessoryMode(d)) return d;
            any = d;
        }
        return any;
    }

    private UsbDevice waitForAccessoryMode() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(250);
            for (UsbDevice d : usb.getDeviceList().values()) if (AoaBench.isAccessoryMode(d)) return d;
        }
        return null;
    }

    private boolean permission(Object target) throws InterruptedException {
        if (target instanceof UsbDevice && usb.hasPermission((UsbDevice) target)) return true;
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                ok[0] = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                latch.countDown();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
        try {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            log("waiting for the USB permission dialog");
            if (target instanceof UsbDevice) usb.requestPermission((UsbDevice) target, pi);
            else usb.requestPermission((UsbAccessory) target, pi);
            latch.await(60, TimeUnit.SECONDS);
        } finally {
            unregisterReceiver(r);
        }
        return ok[0];
    }

    // ---------------------------------------------------------------- plumbing

    interface Job { void run() throws Exception; }

    private Button button(String label, Job job) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> run(job));
        return b;
    }

    @SuppressWarnings("deprecation") // WIFI_MODE_FULL_HIGH_PERF: the same lock K2Go Clone holds
    private void run(Job job) {
        new Thread(() -> {
            PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XferBench:run");
            WifiManager.WifiLock fl = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "XferBench:run");
            wl.acquire(30 * 60 * 1000L);
            fl.acquire();
            try {
                job.run();
            } catch (Exception e) {
                log("error: " + e);
            } finally {
                fl.release();
                wl.release();
            }
        }, "xfer-bench").start();
    }

    private void log(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> logView.append(s + "\n"));
        try (FileWriter w = new FileWriter(new File(getFilesDir(), "results.txt"), true)) {
            w.write(System.currentTimeMillis() + " " + s + "\n");
        } catch (IOException ignored) {
            // the logcat line is the record
        }
    }
}
