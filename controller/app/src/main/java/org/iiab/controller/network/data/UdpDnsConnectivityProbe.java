/*
 * ============================================================================
 * Name        : UdpDnsConnectivityProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : UDP DNS-query probe: sends a query to the server and waits for a reply.
 * ============================================================================
 */
package org.iiab.controller.network.data;

import android.util.Log;

import org.iiab.controller.network.domain.DnsConnectivityProbe;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * {@link DnsConnectivityProbe} that sends a tiny DNS A-query (for a fixed, stable
 * name) over UDP to the given server on port 53 and waits for a valid reply.
 * Pure {@code java.net}; works for IPv4 and IPv6 servers. Never throws — any
 * error (timeout, unreachable, malformed) means "not reachable".
 */
public final class UdpDnsConnectivityProbe implements DnsConnectivityProbe {

    private static final String TAG = "IIAB-DNS";
    private static final String PROBE_NAME = "example.com";
    private static final int DNS_PORT = 53;

    @Override
    public boolean isReachable(String dnsServer, long timeoutMs) {
        if (dnsServer == null || dnsServer.trim().isEmpty()) return false;
        DatagramSocket socket = null;
        try {
            byte[] query = buildQuery(PROBE_NAME);
            InetAddress addr = InetAddress.getByName(dnsServer.trim());
            socket = new DatagramSocket();
            socket.setSoTimeout((int) Math.max(1, Math.min(timeoutMs, Integer.MAX_VALUE)));
            socket.send(new DatagramPacket(query, query.length, addr, DNS_PORT));

            byte[] buf = new byte[512];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            if (response.getLength() < 12) return false;
            boolean idMatches = buf[0] == query[0] && buf[1] == query[1];
            boolean isResponse = (buf[2] & 0x80) != 0;
            int rcode = buf[3] & 0x0F;
            return idMatches && isResponse && rcode == 0;
        } catch (Exception e) {
            Log.i(TAG, "DNS probe to " + dnsServer + " failed: " + e.getMessage());
            return false;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static byte[] buildQuery(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = (int) (System.nanoTime() & 0xFFFF);
        out.write((id >> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        return out.toByteArray();
    }
}
