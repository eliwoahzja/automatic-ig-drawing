package dev.autosketch.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;

public class ColorPlannerTest {
    @Test public void blankImageHasNoPaintLayers() {
        int[] pixels = new int[120 * 120];
        java.util.Arrays.fill(pixels, 0xffffffff);
        ArrayList<ColorLayer> result = ColorPlanner.generate(pixels, 120, 120,
                new ArrayList<float[]>(), 50, null);
        assertTrue(result.isEmpty());
    }

    @Test public void coloredShapesYieldBoundedStrokes() {
        int w = 180, h = 160;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, 0xffffffff);
        for (int y = 30; y < 135; y++) {
            for (int x = 15; x < 165; x++) {
                pixels[y * w + x] = x < 90 ? 0xffddb341 : 0xff468ed0;
            }
        }
        ArrayList<ColorLayer> result = ColorPlanner.generate(pixels, w, h,
                new ArrayList<float[]>(), 55, null);
        assertTrue("color layers generated", result.size() >= 2);
        int total = 0;
        for (ColorLayer layer : result) {
            assertNotNull(layer.name);
            assertEquals(0xff, layer.color >>> 24);
            for (float[] stroke : layer.strokes) {
                assertEquals(4, stroke.length);
                for (float c : stroke) assertTrue(c >= 0f && c <= 1f);
                total++;
            }
        }
        assertTrue("bounded strokes", total < 600);
    }

    @Test public void drawingLayerAppearsLast() {
        int w = 100, h = 100;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, 0xfff4b832);
        ArrayList<float[]> outlines = new ArrayList<>();
        outlines.add(new float[]{.1f, .1f, .9f, .9f});
        ArrayList<ColorLayer> layers = ColorPlanner.generate(pixels, w, h,
                outlines, 60, null);
        assertFalse(layers.isEmpty());
        assertEquals("White outline", layers.get(layers.size() - 1).name);
    }
}
