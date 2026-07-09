package org.iiab.controller.feedback.data;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Captures the current window as a JPEG under {@code filesDir/feedback_screenshots}
 * (recycled from Code On the Go). Async: the result path is delivered on the main
 * thread; {@code null} means capture/save failed. ADFA-4538.
 */
public final class FeedbackScreenshot {

    public interface Callback {
        void onResult(String path);
    }

    private FeedbackScreenshot() {
    }

    public static void capture(Activity activity, Callback cb) {
        try {
            View root = activity.getWindow().getDecorView();
            int w = root.getWidth();
            int h = root.getHeight();
            if (w <= 0 || h <= 0) {
                cb.onResult(null);
                return;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), bmp, result -> {
                if (result == PixelCopy.SUCCESS) {
                    cb.onResult(save(activity, bmp));
                } else {
                    cb.onResult(null);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            cb.onResult(null);
        }
    }

    private static String save(Context ctx, Bitmap bmp) {
        try {
            File dir = new File(ctx.getFilesDir(), "feedback_screenshots");
            dir.mkdirs();
            File f = new File(dir, "screenshot_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
}
