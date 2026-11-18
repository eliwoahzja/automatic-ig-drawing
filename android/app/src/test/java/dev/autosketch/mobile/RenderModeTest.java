package dev.autosketch.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class RenderModeTest {

    private static int[] darkDisc(int size) {
        int[] pixels = new int[size * size];
        int center = size / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.hypot(x - center, y - center);
                pixels[y * size + x] = distance <= 60 ? 0xFF202020 : 0xFFFFFFFF;
            }
        }
        return pixels;
    }

    @Test
    public void fullModeAddsHatchingToDarkShapes() {
        int size = 220;
        int[] pixels = darkDisc(size);
        DrawingSettings sketchSettings = new DrawingSettings();
        sketchSettings.detailLevel = DrawingSettings.DETAIL_HIGH;
        sketchSettings.backgroundSuppression = 0;
        DrawingSettings fullSettings = sketchSettings.copy();
        fullSettings.renderMode = DrawingSettings.MODE_FULL;

        Sketch sketch = SketchEngine.generate(pixels, size, size, sketchSettings, size);
        Sketch full = SketchEngine.generate(pixels, size, size, fullSettings, size);
        assertFalse(sketch.isEmpty());
        assertTrue("full drawing should add shading strokes, sketch=" + sketch.strokes.size()
                        + " full=" + full.strokes.size(),
                full.strokes.size() > sketch.strokes.size() + 5);
    }

    @Test
    public void cleanModeDrawsFewerStrokesThanSketch() {
        int size = 220;
        int[] pixels = darkDisc(size);
        DrawingSettings sketchSettings = new DrawingSettings();
        sketchSettings.detailLevel = DrawingSettings.DETAIL_HIGH;
        sketchSettings.backgroundSuppression = 0;
        DrawingSettings cleanSettings = sketchSettings.copy();
        cleanSettings.renderMode = DrawingSettings.MODE_CLEAN;

        Sketch sketch = SketchEngine.generate(pixels, size, size, sketchSettings, size);
        Sketch clean = SketchEngine.generate(pixels, size, size, cleanSettings, size);
        assertTrue("clean mode should trim detail",
                clean.strokes.size() <= sketch.strokes.size());
    }

    @Test
    public void coveredParallelLineIsDropped() {
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0, 10, 20, 10, 40, 10, 60, 10});
        strokes.add(new float[]{0, 12, 20, 12, 40, 12, 60, 12});
        ArrayList<float[]> kept = StrokeOptimizer.suppressCovered(strokes, 3f, 0.7f);
        assertEquals("offset duplicate should be removed", 1, kept.size());
    }

    @Test
    public void separatedParallelLinesSurvive() {
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0, 10, 20, 10, 40, 10, 60, 10});
        strokes.add(new float[]{0, 22, 20, 22, 40, 22, 60, 22});
        ArrayList<float[]> kept = StrokeOptimizer.suppressCovered(strokes, 3f, 0.7f);
        assertEquals("real parallel detail must stay", 2, kept.size());
    }

    @Test
    public void crossingLinesSurvive() {
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0, 20, 20, 20, 40, 20, 60, 20});
        strokes.add(new float[]{30, 0, 30, 20, 30, 40});
        ArrayList<float[]> kept = StrokeOptimizer.suppressCovered(strokes, 3f, 0.7f);
        assertEquals("crossing contours are not duplicates", 2, kept.size());
    }

    @Test
    public void smallCanvasGetsSmallerStrokeBudgetAndLongerMinimum() {
        CanvasMapper.Rect small = new CanvasMapper.Rect(0, 0, 300, 300);
        CanvasMapper.Rect large = new CanvasMapper.Rect(0, 0, 1200, 1200);
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_ULTRA;
        ArrayList<float[]> strokes = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            float y = 0.2f + (i % 100) * 0.006f;
            strokes.add(new float[]{0.05f, y, 0.95f, y});
        }
        ArrayList<float[]> smallMapped = CanvasMapper.mapStrokes(strokes, small, 1000, 1000,
                settings);
        ArrayList<float[]> largeMapped = CanvasMapper.mapStrokes(strokes, large, 1000, 1000,
                settings);
        assertFalse(smallMapped.isEmpty());
        assertTrue("small canvas must receive fewer strokes",
                smallMapped.size() < largeMapped.size());
        assertTrue("small canvas budget", smallMapped.size() <= 120);
    }
}
