package org.appdevforall.xferbench;

import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Android Open Accessory (AOA) byte stream between two phones, for K2GO-576 step 2.
 * The USB host phone switches the other phone into accessory mode, then both sides
 * move raw bytes over the bulk endpoints. Protocol: the host sends a 10-byte header
 * (command, pattern, length); 'S' = host sends, 'R' = host receives.
 * Spec: https://source.android.com/docs/core/interaction/accessories/aoa
 */
final class AoaBench {
    private static final int GOOGLE_VID = 0x18D1;
    private static final int AOA_GET_PROTOCOL = 51;
    private static final int AOA_SEND_STRING = 52;
    private static final int AOA_START = 53;
    // Must match res/xml/accessory_filter.xml (manufacturer, model, version).
    private static final String[] IDS = {"AppDevForAll", "XferBench", "K2Go transfer benchmark", "1",
            "https://appdevforall.org", "0"};
    private static final int HEADER = 10;
    private static final int TIMEOUT_MS = 5000;

    interface Log { void line(String s); }

    private AoaBench() {}

    static boolean isAccessoryMode(UsbDevice d) {
        return d.getVendorId() == GOOGLE_VID && d.getProductId() >= 0x2D00 && d.getProductId() <= 0x2D05;
    }

    /** Asks the attached phone to restart its USB function in accessory mode. Returns the AOA version. */
    static int switchToAccessory(UsbManager um, UsbDevice d) throws IOException {
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) throw new IOException("openDevice failed");
        try {
            byte[] v = new byte[2];
            if (c.controlTransfer(0xC0, AOA_GET_PROTOCOL, 0, 0, v, 2, 1000) < 2) {
                throw new IOException("the other phone does not answer AOA GET_PROTOCOL");
            }
            for (int i = 0; i < IDS.length; i++) {
                byte[] s = (IDS[i] + "\0").getBytes(StandardCharsets.UTF_8);
                if (c.controlTransfer(0x40, AOA_SEND_STRING, 0, i, s, s.length, 1000) < 0) {
                    throw new IOException("AOA SEND_STRING " + i + " failed");
                }
            }
            if (c.controlTransfer(0x40, AOA_START, 0, 0, null, 0, 1000) < 0) {
                throw new IOException("AOA START failed");
            }
            return (v[1] & 0xFF) << 8 | (v[0] & 0xFF);
        } finally {
            c.close();
        }
    }

    /** Host side, with the other phone already in accessory mode. */
    static String host(UsbManager um, UsbDevice d, char cmd, String pattern, long mb, int buf) throws IOException {
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) throw new IOException("openDevice failed (accessory mode)");
        UsbInterface intf = d.getInterface(0);
        try {
            if (!c.claimInterface(intf, true)) throw new IOException("claimInterface failed");
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < intf.getEndpointCount(); i++) {
                UsbEndpoint e = intf.getEndpoint(i);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e; else out = e;
            }
            if (in == null || out == null) throw new IOException("no bulk endpoints");
            long total = mb << 20;
            ByteBuffer h = ByteBuffer.allocate(HEADER);
            h.put((byte) cmd).put((byte) pattern.charAt(0)).putLong(total);
            if (c.bulkTransfer(out, h.array(), HEADER, TIMEOUT_MS) != HEADER) throw new IOException("header failed");

            byte[] b = Payload.fill(pattern, buf);
            long n = 0, t0 = System.nanoTime();
            if (cmd == 'S') {
                while (n < total) {
                    int r = c.bulkTransfer(out, b, (int) Math.min(b.length, total - n), TIMEOUT_MS);
                    if (r < 0) throw new IOException("bulk OUT failed after " + n + " bytes");
                    n += r;
                }
                long dt = System.nanoTime() - t0;
                byte[] ack = new byte[16384];
                int r = c.bulkTransfer(in, ack, ack.length, 20000);
                String acc = r >= 8 ? String.format(java.util.Locale.ROOT, " accessorySecs=%.2f",
                        ByteBuffer.wrap(ack, 0, 8).getLong() / 1e9) : " (no ack)";
                return TcpBench.result("aoa-host-send", "bulk" + buf, pattern, n, dt) + acc;
            }
            while (n < total) {
                int r = c.bulkTransfer(in, b, b.length, TIMEOUT_MS);
                if (r < 0) throw new IOException("bulk IN failed after " + n + " bytes");
                n += r;
            }
            return TcpBench.result("aoa-host-recv", "bulk" + buf, "accessory", n, System.nanoTime() - t0);
        } finally {
            c.releaseInterface(intf);
            c.close();
        }
    }

    /** Accessory side: serves host commands until the cable is unplugged or the host closes. */
    static void accessory(UsbManager um, UsbAccessory a, int buf, Log log) throws IOException {
        ParcelFileDescriptor pfd = um.openAccessory(a);
        if (pfd == null) throw new IOException("openAccessory failed");
        try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            // f_accessory reads need a buffer of at least 16 KiB.
            byte[] b = new byte[Math.max(buf, 16384)];
            log.line("accessory: ready, waiting for the host");
            while (true) {
                int r = in.read(b);
                if (r < 0) break;
                if (r < HEADER) { log.line("accessory: short header " + r); continue; }
                ByteBuffer h = ByteBuffer.wrap(b, 0, HEADER);
                char cmd = (char) h.get();
                String pattern = patternName((char) h.get());
                long total = h.getLong();
                long t0 = System.nanoTime();
                if (cmd == 'S') {
                    long n = r - HEADER;
                    while (n < total) {
                        int k = in.read(b);
                        if (k < 0) throw new IOException("accessory read ended after " + n + " bytes");
                        n += k;
                    }
                    long dt = System.nanoTime() - t0;
                    out.write(ByteBuffer.allocate(8).putLong(dt).array());
                    log.line(TcpBench.result("aoa-acc-recv", "read" + b.length, pattern, n, dt));
                } else if (cmd == 'R') {
                    byte[] data = Payload.fill(pattern, b.length);
                    long n = 0;
                    while (n < total) {
                        int len = (int) Math.min(data.length, total - n);
                        out.write(data, 0, len);
                        n += len;
                    }
                    log.line(TcpBench.result("aoa-acc-send", "write" + data.length, pattern, n, System.nanoTime() - t0));
                } else {
                    break;
                }
            }
        } finally {
            pfd.close();
            log.line("accessory: closed");
        }
    }

    private static String patternName(char c) {
        return c == 'o' ? "ones" : c == 'a' ? "alt" : "zeros";
    }
}
