package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared state between the activity and the accessibility service. */
public final class SketchSession {

    private static final Object LOCK = new Object();

    private static Bitmap source;
    private static Sketch sketch = Sketch.empty();
    private static DrawingSettings settings = new DrawingSettings();

    private SketchSession() {
    }

    public static DrawingSettings settings() {
        synchronized (LOCK) {
            return settings;
        }
    }

    public static void updateSettings(DrawingSettings next) {
        synchronized (LOCK) {
            settings = next.copy();
        }
    }

    public static Bitmap source() {
        synchronized (LOCK) {
            return source;
        }
    }

    /**
     * Keeps a bounded copy of the selected image so image-processing settings can
     * be re-applied from the floating panel without going back to the activity.
     */
    public static Bitmap setSource(Bitmap bitmap) {
        Bitmap prepared = fromBitmap(bitmap, SketchProcessor.MAX_SOURCE_DIMENSION);
        synchronized (LOCK) {
            // Deliberately not recycling the previous bitmap: a background
            // processing run or a view may still be reading it.
            source = prepared;
            return prepared;
        }
    }

    /** Loads a content Uri, downscaling while decoding to keep memory flat. */
    public static Bitmap loadSource(java.io.InputStream stream, int maxDimension)
            throws java.io.IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        byte[] bytes = readAll(stream);
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static byte[] readAll(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static Bitmap fromBitmap(Bitmap bitmap, int maxDimension) {
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = Math.min(1f, maxDimension / (float) Math.max(width, height));
        if (scale >= 1f) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    public static Sketch sketch() {
        synchronized (LOCK) {
            return sketch;
        }
    }

    public static void setSketch(Sketch next) {
        synchronized (LOCK) {
            sketch = next == null ? Sketch.empty() : next;
        }
    }

    public static void clearSource() {
        synchronized (LOCK) {
            // The activity may still be showing this bitmap, so only drop the
            // reference here; the bitmap is reclaimed with the app process.
            source = null;
            sketch = Sketch.empty();
        }
    }

    /** Cancellation token shared by the activity and the floating panel. */
    public static final class Token {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
