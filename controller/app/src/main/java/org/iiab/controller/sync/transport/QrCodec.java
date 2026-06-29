/*
 * ============================================================================
 * Name        : QrCodec.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Single QR-bitmap encoder for the Share feature — the one copy of
 *               the ZXing QRCodeWriter -> Bitmap conversion previously duplicated
 *               in SyncHandshakeHelper and QrActivity (tech-debt EX3). Output is
 *               unchanged (RGB_565, black/white). Share-export, S14 step 3.
 * ============================================================================
 */
package org.iiab.controller.sync.transport;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public final class QrCodec {

    private QrCodec() {
    }

    /** Encodes {@code data} as a square QR bitmap of {@code size} px; null on failure. */
    public static Bitmap encode(String data, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
