package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.os.SystemClock;

/** Bridges Android bitmaps to the pure {@link SketchEngine}. */
public final class SketchProcessor {

    /** Bounded source resolution, keeps re-processing cheap on mid-range phones. */
    public static final int MAX_SOURCE_DIMENSION = 1280;

    public static final class Outcome {
        public final Sketch sketch;
        public final long durationMs;
        public final boolean cancelled;

        Outcome(Sketch sketch, long durationMs, boolean cancelled) {
            this.sketch = sketch;
            this.durationMs = durationMs;
            this.cancelled = cancelled;
        }
    }

    private SketchProcessor() {
    }

    public static Outcome process(Bitmap source, DrawingSettings settings, int canvasWidthPx,
                                  SketchSession.Token token) {
        long started = SystemClock.uptimeMillis();
        if (source == null || source.isRecycled()) {
            return new Outcome(Sketch.empty(), 0, true);
        }
        int[] size = SketchEngine.processingSize(source.getWidth(), source.getHeight(), settings);
        Bitmap working = source;
        boolean temporary = false;
        if (size[0] != source.getWidth() || size[1] != source.getHeight()) {
            working = Bitmap.createScaledBitmap(source, size[0], size[1], true);
            temporary = true;
        }
        try {
            int width = working.getWidth();
            int height = working.getHeight();
            int[] pixels = new int[width * height];
            working.getPixels(pixels, 0, width, 0, 0, width, height);
            Sketch sketch = SketchEngine.generate(pixels, width, height, settings,
                    Math.max(1, canvasWidthPx), token == null ? null : new SketchEngine.Cancellation() {
                        @Override
                        public boolean isCancelled() {
                            return token.isCancelled();
                        }
                    });
            boolean cancelled = token != null && token.isCancelled();
            return new Outcome(sketch, SystemClock.uptimeMillis() - started, cancelled);
        } finally {
            if (temporary && working != null && !working.isRecycled()) {
                working.recycle();
            }
        }
    }
}
