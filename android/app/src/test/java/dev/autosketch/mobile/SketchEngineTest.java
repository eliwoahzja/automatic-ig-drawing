package dev.autosketch.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class SketchEngineTest {

    private static int[] circleImage(int size, int radius, boolean filled) {
        int[] pixels = new int[size * size];
        int center = size / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.hypot(x - center, y - center);
                boolean ink = filled ? distance <= radius : Math.abs(distance - radius) <= 1.1;
                pixels[y * size + x] = ink ? 0xFF101010 : 0xFFFFFFFF;
            }
        }
        return pixels;
    }

    @Test
    public void processingSizePreservesAspectRatio() {
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_HIGH;
        int[] wide = SketchEngine.processingSize(2000, 1000, settings);
        assertArrayEquals(new int[]{720, 360}, wide);
        int[] tall = SketchEngine.processingSize(1000, 2000, settings);
        assertArrayEquals(new int[]{360, 720}, tall);
        int[] small = SketchEngine.processingSize(100, 80, settings);
        assertArrayEquals(new int[]{100, 80}, small);
    }

    @Test
    public void blankImageProducesNoStrokes() {
        int size = 160;
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        Sketch sketch = SketchEngine.generate(pixels, size, size, new DrawingSettings(), size);
        assertTrue("blank page must not be drawn", sketch.isEmpty());
    }

    @Test
    public void circleProducesLongOrderedContour() {
        int size = 220;
        int[] pixels = circleImage(size, 70, false);
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_HIGH;
        settings.backgroundSuppression = 0;
        Sketch sketch = SketchEngine.generate(pixels, size, size, settings, size);
        assertFalse("circle should be traced", sketch.isEmpty());

        float longest = 0f;
        for (float[] stroke : sketch.strokes) {
            longest = Math.max(longest, StrokeOptimizer.length(scaleToPixels(stroke, size, size)));
            for (float value : stroke) {
                assertTrue("normalized coordinate", value >= 0f && value <= 1f);
            }
        }
        assertTrue("expected a connected contour, longest=" + longest, longest > 120f);
        assertTrue("stroke count should stay reasonable, got " + sketch.strokes.size(),
                sketch.strokes.size() < 400);
    }

    @Test
    public void filledShapeProducesRecognisableOutline() {
        int size = 200;
        int[] pixels = circleImage(size, 60, true);
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_MEDIUM;
        settings.backgroundSuppression = 0;
        Sketch sketch = SketchEngine.generate(pixels, size, size, settings, size);
        assertFalse(sketch.isEmpty());
        float minX = 1f;
        float maxX = 0f;
        float minY = 1f;
        float maxY = 0f;
        for (float[] stroke : sketch.strokes) {
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                minX = Math.min(minX, stroke[i]);
                maxX = Math.max(maxX, stroke[i]);
                minY = Math.min(minY, stroke[i + 1]);
                maxY = Math.max(maxY, stroke[i + 1]);
            }
        }
        assertTrue("outline spans the subject", maxX - minX > 0.4f && maxY - minY > 0.4f);
    }

    @Test
    public void cancellationReturnsEmptySketch() {
        int size = 120;
        int[] pixels = circleImage(size, 40, false);
        Sketch sketch = SketchEngine.generate(pixels, size, size, new DrawingSettings(), size,
                new SketchEngine.Cancellation() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                });
        assertTrue(sketch.isEmpty());
    }

    @Test
    public void detailLevelsChangeStrokeBudgets() {
        DrawingSettings low = new DrawingSettings();
        low.detailLevel = DrawingSettings.DETAIL_LOW;
        DrawingSettings ultra = new DrawingSettings();
        ultra.detailLevel = DrawingSettings.DETAIL_ULTRA;
        assertTrue(low.maxStrokes() < ultra.maxStrokes());
        assertTrue(low.maxProcessingDimension() < ultra.maxProcessingDimension());
        assertTrue(low.strongEdgeFraction() < ultra.strongEdgeFraction());
    }

    @Test
    public void generatedStrokesAreOrderedForNearestNeighbourDrawing() {
        int size = 200;
        int[] pixels = circleImage(size, 60, false);
        DrawingSettings settings = new DrawingSettings();
        settings.backgroundSuppression = 0;
        Sketch sketch = SketchEngine.generate(pixels, size, size, settings, size);
        ArrayList<float[]> strokes = new ArrayList<>(sketch.strokes);
        assertFalse(strokes.isEmpty());
        for (int i = 1; i < strokes.size(); i++) {
            float[] previous = strokes.get(i - 1);
            float[] current = strokes.get(i);
            float endX = previous[previous.length - 2] * size;
            float endY = previous[previous.length - 1] * size;
            float startX = current[0] * size;
            float startY = current[1] * size;
            assertTrue("jump between strokes should stay modest",
                    Math.hypot(startX - endX, startY - endY) < size * 1.5f);
        }
    }

    private static float[] scaleToPixels(float[] normalized, int width, int height) {
        float[] out = new float[normalized.length];
        for (int i = 0; i + 1 < normalized.length; i += 2) {
            out[i] = normalized[i] * width;
            out[i + 1] = normalized[i + 1] * height;
        }
        return out;
    }
}
