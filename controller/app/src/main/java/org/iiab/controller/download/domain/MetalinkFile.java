/*
 * ============================================================================
 * Name        : MetalinkFile.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4676. Pure parser for a Metalink 4 (.meta4, RFC 5854)
 *               describing a single rootfs artifact. Extracts the authoritative
 *               { fileName, sizeBytes, sha256, mirrors } so a download can be
 *               verified end-to-end before extraction.
 * ============================================================================
 */
package org.iiab.controller.download.domain;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Pure (framework-free) model + parser for a Metalink 4 file.
 *
 * <p>The file-level SHA-256 is the {@code <hash type="sha-256">} that is a direct
 * child of {@code <file>}. It must NOT be confused with the per-piece hashes
 * inside {@code <pieces>}, which use the same element and type. A single
 * "inside &lt;pieces&gt;" flag guarantees only the file-level hash is captured.
 */
public final class MetalinkFile {

    private final String fileName;
    private final long sizeBytes;   // -1 if absent
    private final String sha256;    // lowercase hex, or null if absent
    private final List<String> mirrors;

    private MetalinkFile(String fileName, long sizeBytes, String sha256, List<String> mirrors) {
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.mirrors = Collections.unmodifiableList(mirrors);
    }

    public String fileName() { return fileName; }
    public long sizeBytes() { return sizeBytes; }
    public String sha256() { return sha256; }
    public List<String> mirrors() { return mirrors; }

    /** True when we have enough to verify a download end-to-end. */
    public boolean canVerify() {
        return fileName != null && sizeBytes > 0 && sha256 != null && !sha256.isEmpty();
    }

    /** Parse the first {@code <file>} of a Metalink stream. Does not close {@code in}. */
    public static MetalinkFile parse(InputStream in) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        SAXParser parser = f.newSAXParser();
        Handler h = new Handler();
        parser.parse(new InputSource(in), h);
        return new MetalinkFile(h.fileName, h.size, h.sha256, h.mirrors);
    }

    private static String local(String qName) {
        int i = qName.indexOf(':');
        return i >= 0 ? qName.substring(i + 1) : qName;
    }

    private static final class Handler extends DefaultHandler {
        String fileName;
        long size = -1;
        String sha256;
        final List<String> mirrors = new ArrayList<>();

        private boolean done;      // stop after the first <file>
        private boolean inFile;
        private boolean inPieces;
        private final StringBuilder buf = new StringBuilder();
        private String capture;    // "size" | "hash" | "url" | null

        @Override public void startElement(String uri, String localName, String qName, Attributes at) {
            if (done) return;
            switch (local(qName)) {
                case "file":
                    if (!inFile) { inFile = true; if (fileName == null) fileName = at.getValue("name"); }
                    break;
                case "pieces":
                    inPieces = true;
                    break;
                case "size":
                    if (inFile && !inPieces) startCapture("size");
                    break;
                case "hash":
                    if (inFile && !inPieces && sha256 == null) {
                        String type = at.getValue("type");
                        String t = type == null ? "" : type.toLowerCase(Locale.US);
                        if (t.isEmpty() || t.contains("sha-256") || t.contains("sha256")) startCapture("hash");
                    }
                    break;
                case "url":
                    if (inFile && !inPieces) startCapture("url");
                    break;
                default:
                    break;
            }
        }

        @Override public void characters(char[] ch, int start, int length) {
            if (capture != null) buf.append(ch, start, length);
        }

        @Override public void endElement(String uri, String localName, String qName) {
            if (done) return;
            switch (local(qName)) {
                case "size":
                    if ("size".equals(capture)) {
                        try { size = Long.parseLong(buf.toString().trim()); } catch (NumberFormatException ignored) {}
                        endCapture();
                    }
                    break;
                case "hash":
                    if ("hash".equals(capture)) {
                        String v = buf.toString().trim().toLowerCase(Locale.US);
                        if (!v.isEmpty()) sha256 = v;
                        endCapture();
                    }
                    break;
                case "url":
                    if ("url".equals(capture)) {
                        String v = buf.toString().trim();
                        String low = v.toLowerCase(Locale.US);
                        if (low.startsWith("http://") || low.startsWith("https://")) mirrors.add(v);
                        endCapture();
                    }
                    break;
                case "pieces":
                    inPieces = false;
                    break;
                case "file":
                    if (inFile) { inFile = false; done = true; }
                    break;
                default:
                    break;
            }
        }

        private void startCapture(String what) { capture = what; buf.setLength(0); }
        private void endCapture() { capture = null; buf.setLength(0); }
    }
}
