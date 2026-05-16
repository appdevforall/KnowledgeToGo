/*
 * ============================================================================
 * Name        : ApkServer.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Server to handle local APK distribution
 * ============================================================================
 */
package org.iiab.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import fi.iki.elonen.NanoHTTPD;

public class ApkServer extends NanoHTTPD {
    private final String apkPath;

    public ApkServer(int port, String apkPath) {
        super(port);
        this.apkPath = apkPath;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            File apkFile = new File(apkPath);
            if (!apkFile.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "APK not found");
            }

            FileInputStream fis = new FileInputStream(apkFile);

            // We use ChunkedResponse for large files (Avoid OutOfMemoryErrors)
            Response response = newChunkedResponse(Response.Status.OK, "application/vnd.android.package-archive", fis);

            // We force the download name and indicate the size to the browser so that it shows the progress bar
            response.addHeader("Content-Disposition", "attachment; filename=\"IIAB-Controller-Latest.apk\"");
            response.addHeader("Content-Length", String.valueOf(apkFile.length()));

            return response;

        } catch (FileNotFoundException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading APK: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        android.util.Log.d("ApkServer", "Shutting down the internal server and freeing port 8080...");
        super.stop();
    }
}
