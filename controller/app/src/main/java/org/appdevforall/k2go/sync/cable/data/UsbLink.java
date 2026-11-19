/*
 * ============================================================================
 * Name        : UsbLink.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The raw byte stream of the Clone cable (K2GO-576), on either
 *               side of an Android Open Accessory link. The host side moves
 *               bytes with bulk transfers; the accessory side reads and writes
 *               the accessory file descriptor. Which phone is the USB host is
 *               decided at plug-in; the protocol above does not depend on it.
 *               Spec: https://source.android.com/docs/core/interaction/accessories/aoa
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;

import org.appdevforall.k2go.sync.cable.domain.UsbCandidate;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public abstract class UsbLink implements Closeable {

    // Accessory mode re-enumerates the phone with Google's vendor id and these product ids.
    private static final int GOOGLE_VID = 0x18D1;
    private static final int AOA_PID_FIRST = 0x2D00;
    private static final int AOA_PID_LAST = 0x2D05;
    // Strings the host sends; the accessory side checks manufacturer and model.
    static final String MANUFACTURER = "AppDevForAll";
    static final String MODEL = "K2GoClone";
    // No URI: with one, Android shows a "not supported, see <URI>" notice on a phone without a
    // matching app filter (Clone watches the accessory list itself).
    private static final String[] IDS = {MANUFACTURER, MODEL, "Knowledge to Go Clone cable", "1",
            "", "0"};
    /** f_accessory needs a read buffer of at least 16 KiB; a smaller one loses data. */
    static final int READ_BUFFER = 16 * 1024;
    /** Lets the host use 128 KiB IN transfers where its controller allows them. */
    static final int MAX_READ = 128 * 1024;

    /** Reads at least one byte into {@code b} (at least READ_BUFFER long); -1 when the cable is gone. */
    public abstract int read(byte[] b) throws IOException;

    public abstract void write(byte[] b, int off, int len) throws IOException;

    public static boolean isAccessoryMode(UsbDevice d) {
        return d.getVendorId() == GOOGLE_VID
                && d.getProductId() >= AOA_PID_FIRST && d.getProductId() <= AOA_PID_LAST;
    }

    /** The device can be the other phone (UsbCandidate): not a hub, drive or keyboard. No permission needed. */
    public static boolean mayBePhone(UsbDevice d) {
        int[] classes = new int[d.getInterfaceCount()];
        for (int i = 0; i < classes.length; i++) classes[i] = d.getInterface(i).getInterfaceClass();
        return UsbCandidate.canBePhone(isAccessoryMode(d), classes);
    }

    public static boolean isOurAccessory(UsbAccessory a) {
        return MANUFACTURER.equals(a.getManufacturer()) && MODEL.equals(a.getModel());
    }

    /** Asks the phone at the other end to restart its USB function in accessory mode. */
    public static void switchToAccessory(UsbManager um, UsbDevice d) throws IOException {
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) throw new IOException("openDevice failed");
        try {
            byte[] v = new byte[2];
            if (c.controlTransfer(0xC0, 51, 0, 0, v, 2, 1000) < 2 || v[0] == 0) {
                throw new IOException("the other device does not support AOA");
            }
            for (int i = 0; i < IDS.length; i++) {
                byte[] s = (IDS[i] + "\0").getBytes(StandardCharsets.UTF_8);
                if (c.controlTransfer(0x40, 52, 0, i, s, s.length, 1000) < 0) {
                    throw new IOException("AOA string " + i + " failed");
                }
            }
            if (c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) < 0) throw new IOException("AOA start failed");
        } finally {
            c.close();
        }
    }

    /** USB host side: bulk endpoints of a phone already in accessory mode. */
    public static UsbLink host(UsbManager um, UsbDevice d) throws IOException {
        return new Host(um, d);
    }

    /** Accessory side: this phone was switched by the host. */
    public static UsbLink accessory(UsbManager um, UsbAccessory a) throws IOException {
        ParcelFileDescriptor pfd = um.openAccessory(a);
        if (pfd == null) throw new IOException("openAccessory failed");
        return new Accessory(pfd);
    }

    private static final class Host extends UsbLink {
        // MediaTek host controllers reject bulk transfers above 16 KiB (K2GO-576 tests).
        private static final int MIN_BULK = 16 * 1024;
        private static final int TIMEOUT_MS = 10_000;
        private static final int READ_POLL_MS = 1000;
        private volatile boolean closed;
        private final UsbDeviceConnection c;
        private final UsbInterface intf;
        private final UsbEndpoint in;
        private final UsbEndpoint out;
        private int bulk = 128 * 1024;
        private final byte[] piece = new byte[128 * 1024];

        Host(UsbManager um, UsbDevice d) throws IOException {
            c = um.openDevice(d);
            if (c == null) throw new IOException("openDevice failed (accessory mode)");
            intf = d.getInterface(0);
            UsbEndpoint i = null, o = null;
            for (int k = 0; k < intf.getEndpointCount(); k++) {
                UsbEndpoint e = intf.getEndpoint(k);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) i = e; else o = e;
            }
            if (i == null || o == null || !c.claimInterface(intf, true)) {
                c.close();
                throw new IOException("no AOA bulk endpoints");
            }
            in = i;
            out = o;
        }

        @Override
        public int read(byte[] b) throws IOException {
            // bulkTransfer returns -1 for a timeout and for an error alike. A timeout is normal
            // while the peer is idle; a fast -1 is an error: first drop to 16 KiB, then give up.
            while (!closed) {
                long t0 = System.nanoTime();
                int r = c.bulkTransfer(in, b, Math.min(b.length, bulk), READ_POLL_MS);
                if (r > 0) return r;
                boolean fast = System.nanoTime() - t0 < READ_POLL_MS * 500_000L;
                if (r < 0 && fast) {
                    if (bulk > MIN_BULK) bulk = MIN_BULK;
                    else return -1;
                }
            }
            return -1;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            int end = off + len;
            while (off < end) {
                // bulkTransfer with an offset needs API 28; copy each piece instead (minSdk 24).
                int n = Math.min(bulk, end - off);
                System.arraycopy(b, off, piece, 0, n);
                int r = c.bulkTransfer(out, piece, n, TIMEOUT_MS);
                if (r < 0 && bulk > MIN_BULK) { bulk = MIN_BULK; continue; }
                if (r < 0) throw new IOException("bulk OUT failed");
                off += r;
            }
        }

        @Override
        public void close() {
            closed = true;
            c.releaseInterface(intf);
            c.close();
        }
    }

    private static final class Accessory extends UsbLink {
        private final ParcelFileDescriptor pfd;
        private final FileInputStream in;
        private final FileOutputStream out;

        Accessory(ParcelFileDescriptor pfd) {
            this.pfd = pfd;
            in = new FileInputStream(pfd.getFileDescriptor());
            out = new FileOutputStream(pfd.getFileDescriptor());
        }

        @Override
        public int read(byte[] b) throws IOException {
            return in.read(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            pfd.close();
        }
    }
}
