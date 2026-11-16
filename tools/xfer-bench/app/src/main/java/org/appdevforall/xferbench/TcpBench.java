package org.appdevforall.xferbench;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Raw TCP throughput from Java, for K2GO-576 (steps 2 and 3).
 * Methods: "stream" (byte[] via java.io), "direct" (one direct ByteBuffer),
 * "kernel" (FileChannel.transferTo / transferFrom: sendfile on the sender).
 */
final class TcpBench {
    private static final long CHUNK = 4L << 20;

    private TcpBench() {}

    /** Accepts one connection on {@code port} and reads until EOF. sinkFile null = discard. */
    static String receive(int port, String method, File sinkFile, int bufKb) throws IOException {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.socket().setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            try (SocketChannel ch = server.accept()) {
                long t0 = System.nanoTime();
                long n;
                if (sinkFile == null) {
                    n = readDiscard(ch, method, bufKb);
                } else {
                    try (RandomAccessFile raf = new RandomAccessFile(sinkFile, "rw");
                         FileChannel fc = raf.getChannel()) {
                        n = readToFile(ch, fc, method, bufKb);
                        fc.force(false);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        sinkFile.delete();
                    }
                }
                return result("tcp-recv", method, sinkFile == null ? "null" : sinkFile.getParent(), n, System.nanoTime() - t0);
            }
        }
    }

    /** Connects and sends {@code mb} MiB of {@code pattern}, or the whole {@code srcFile} when it is not null. */
    static String send(String host, int port, String method, String pattern, long mb, File srcFile, int bufKb)
            throws IOException {
        try (SocketChannel ch = SocketChannel.open(new InetSocketAddress(host, port))) {
            long t0 = System.nanoTime();
            long n = srcFile == null ? sendPattern(ch, method, pattern, mb << 20, bufKb)
                                     : sendFile(ch, method, srcFile, bufKb);
            ch.shutdownOutput();
            String src = srcFile == null ? pattern : srcFile.getParent();
            return result("tcp-send", method, src, n, System.nanoTime() - t0);
        }
    }

    /** Writes {@code mb} MiB of {@code pattern} to {@code f}, so "send" can read a real file. */
    static String prepare(File f, String pattern, long mb) throws IOException {
        byte[] b = Payload.fill(pattern, 1 << 20);
        long t0 = System.nanoTime();
        try (FileOutputStream out = new FileOutputStream(f)) {
            for (long i = 0; i < mb; i++) out.write(b);
            out.getFD().sync();
        }
        return result("prepare", pattern, f.getParent(), mb << 20, System.nanoTime() - t0);
    }

    private static long readDiscard(SocketChannel ch, String method, int bufKb) throws IOException {
        long n = 0;
        if ("stream".equals(method)) {
            InputStream in = ch.socket().getInputStream();
            byte[] b = new byte[bufKb << 10];
            for (int r; (r = in.read(b)) > 0; ) n += r;
        } else {
            ByteBuffer bb = ByteBuffer.allocateDirect(bufKb << 10);
            for (int r; (r = ch.read(bb)) >= 0; bb.clear()) n += r;
        }
        return n;
    }

    private static long readToFile(SocketChannel ch, FileChannel fc, String method, int bufKb) throws IOException {
        long n = 0;
        switch (method) {
            case "kernel": {
                // transferFrom can return 0 before the end of a socket stream on Android: only a
                // read of -1 means the sender is done.
                ByteBuffer one = ByteBuffer.allocate(1);
                while (true) {
                    long t = fc.transferFrom(ch, n, CHUNK);
                    if (t > 0) { n += t; continue; }
                    one.clear();
                    if (ch.read(one) < 0) break;
                    one.flip();
                    n += fc.write(one, n);
                }
                break;
            }
            case "stream": {
                InputStream in = ch.socket().getInputStream();
                OutputStream out = java.nio.channels.Channels.newOutputStream(fc);
                byte[] b = new byte[bufKb << 10];
                for (int r; (r = in.read(b)) > 0; ) { out.write(b, 0, r); n += r; }
                break;
            }
            default: {
                ByteBuffer bb = ByteBuffer.allocateDirect(bufKb << 10);
                for (int r; (r = ch.read(bb)) >= 0; ) {
                    bb.flip();
                    while (bb.hasRemaining()) fc.write(bb);
                    bb.clear();
                    n += r;
                }
            }
        }
        return n;
    }

    private static long sendPattern(SocketChannel ch, String method, String pattern, long total, int bufKb)
            throws IOException {
        byte[] b = Payload.fill(pattern, bufKb << 10);
        long n = 0;
        if ("stream".equals(method)) {
            OutputStream out = ch.socket().getOutputStream();
            while (n < total) { int len = (int) Math.min(b.length, total - n); out.write(b, 0, len); n += len; }
            out.flush();
        } else {
            ByteBuffer bb = ByteBuffer.allocateDirect(b.length);
            bb.put(b);
            while (n < total) {
                bb.clear();
                bb.limit((int) Math.min(bb.capacity(), total - n));
                while (bb.hasRemaining()) n += ch.write(bb);
            }
        }
        return n;
    }

    private static long sendFile(SocketChannel ch, String method, File f, int bufKb) throws IOException {
        long n = 0;
        try (FileInputStream fis = new FileInputStream(f); FileChannel fc = fis.getChannel()) {
            long size = fc.size();
            switch (method) {
                case "kernel":
                    while (n < size) n += fc.transferTo(n, Math.min(CHUNK, size - n), ch);
                    break;
                case "stream": {
                    OutputStream out = ch.socket().getOutputStream();
                    byte[] b = new byte[bufKb << 10];
                    for (int r; (r = fis.read(b)) > 0; ) { out.write(b, 0, r); n += r; }
                    out.flush();
                    break;
                }
                default: {
                    ByteBuffer bb = ByteBuffer.allocateDirect(bufKb << 10);
                    for (int r; (r = fc.read(bb)) > 0; ) {
                        bb.flip();
                        while (bb.hasRemaining()) ch.write(bb);
                        bb.clear();
                        n += r;
                    }
                }
            }
        }
        return n;
    }

    static String result(String what, String method, String where, long bytes, long nanos) {
        double s = nanos / 1e9;
        return String.format(java.util.Locale.ROOT, "%s method=%s at=%s bytes=%d secs=%.2f MBps=%.1f",
                what, method, where, bytes, s, bytes / 1e6 / s);
    }
}
