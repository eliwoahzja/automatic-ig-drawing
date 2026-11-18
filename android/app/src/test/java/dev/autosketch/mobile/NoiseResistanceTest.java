package dev.autosketch.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

/**
 * Regression cover for the decorated-photo failure: glitter, grain, watermark
 * text and soft wallpaper texture used to be traced as hundreds of scribbles.
 */
public class NoiseResistanceTest {

    /** Soft noisy background with glitter, plus one clear dark subject shape. */
    private static int[] decoratedPhoto(int size, int radius) {
        int[] pixels = new int[size * size];
        Random random = new Random(7);
        int center = size / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int texture = 226 + random.nextInt(17);
                pixels[y * size + x] = grey(texture);
            }
        }
        // Glitter: bright blobs scattered everywhere, sized like the sprinkles on
        // a typical decorated anime screenshot (2-4% of the canvas width).
        for (int i = 0; i < 110; i++) {
            int cx = 8 + random.nextInt(size - 16);
            int cy = 8 + random.nextInt(size - 16);
            int r = 3 + random.nextInt(4);
            for (int y = cy - r; y <= cy + r; y++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    if (x < 0 || y < 0 || x >= size || y >= size) {
                        continue;
                    }
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                        pixels[y * size + x] = 0xFFFF8FC0;
                    }
                }
            }
        }
        // Subject: a solid dark disc with a clear boundary.
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.hypot(x - center, y - center);
                if (distance <= radius) {
                    pixels[y * size + x] = grey(45 + (int) (distance % 9));
                }
            }
        }
        return pixels;
    }

    private static int grey(int level) {
        int value = Math.max(0, Math.min(255, level));
        return 0xFF000000 | (value << 16) | (value << 8) | value;
    }

    @Test
    public void decoratedPhotoTracesTheSubjectNotTheGlitter() {
        int size = 300;
        int radius = 62;
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_HIGH;
        settings.backgroundSuppression = 0;

        Sketch sketch = SketchEngine.generate(decoratedPhoto(size, radius), size, size,
                settings, size);

        assertFalse("the subject outline must survive", sketch.isEmpty());
        assertTrue("expected a calm drawing, got " + sketch.strokes.size() + " strokes",
                sketch.strokes.size() < 40);

        float longest = 0f;
        int sprinkleLoops = 0;
        for (float[] stroke : sketch.strokes) {
            float[] pixels = new float[stroke.length];
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                pixels[i] = stroke[i] * size;
                pixels[i + 1] = stroke[i + 1] * size;
                minX = Math.min(minX, pixels[i]);
                maxX = Math.max(maxX, pixels[i]);
                minY = Math.min(minY, pixels[i + 1]);
                maxY = Math.max(maxY, pixels[i + 1]);
            }
            longest = Math.max(longest, StrokeOptimizer.length(pixels));
            if (Math.max(maxX - minX, maxY - minY) < size * 0.07f) {
                sprinkleLoops++;
            }
        }
        assertTrue("the subject circle must be one connected contour, longest=" + longest,
                longest > radius * 4);
        assertTrue("sprinkles must not dominate the drawing, kept " + sprinkleLoops,
                sprinkleLoops < 8);
    }

    @Test
    public void flatTextureProducesAlmostNoStrokes() {
        int size = 320;
        int[] pixels = new int[size * size];
        Random random = new Random(3);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = grey(200 + random.nextInt(28));
        }
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_ULTRA;

        Sketch sketch = SketchEngine.generate(pixels, size, size, settings, size);

        assertTrue("grain must not be drawn, got " + sketch.strokes.size() + " strokes",
                sketch.strokes.size() < 12);
    }

    @Test
    public void smoothingScalesWithProcessingResolution() {
        DrawingSettings settings = new DrawingSettings();
        float small = settings.blurSigma(360);
        float large = settings.blurSigma(900);
        assertTrue("a 900 px working image needs much more smoothing than a 360 px one: "
                + small + " vs " + large, large > small * 1.4f);
        assertTrue("smoothing must stay sane", settings.blurSigma(240) >= 0.85f);
    }

    @Test
    public void fullModeHatchBudgetScalesWithDetail() {
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_LOW;
        int low = settings.hatchStrokeBudget();
        settings.detailLevel = DrawingSettings.DETAIL_ULTRA;
        assertTrue("ultra should shade more than low", settings.hatchStrokeBudget() > low);
        assertTrue("full mode must stay drawable by hand",
                settings.maxStrokesForRenderMode() < 1400);
    }
}
