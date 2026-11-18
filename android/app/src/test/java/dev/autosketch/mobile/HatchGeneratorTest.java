package dev.autosketch.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class HatchGeneratorTest {

    private static DrawingSettings fullSettings() {
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_HIGH;
        settings.renderMode = DrawingSettings.MODE_FULL;
        settings.backgroundSuppression = 0;
        return settings;
    }

    @Test
    public void hatchStaysOnDarkSideNearEdges() {
        int size = 240;
        int[] gray = new int[size * size];
        byte[] edges = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                gray[y * size + x] = x < size / 2 ? 40 : 250;
            }
        }
        for (int y = 20; y < size - 20; y++) {
            edges[y * size + size / 2] = 1;
        }

        ArrayList<float[]> hatch = HatchGenerator.generate(gray, size, size, edges,
                fullSettings(), 1f);

        assertFalse("dark subject edge should be hatched", hatch.isEmpty());
        for (float[] stroke : hatch) {
            assertTrue("hatch needs at least two points", stroke.length >= 4);
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                assertTrue("hatch must stay on the dark side, x=" + stroke[i],
                        stroke[i] < size / 2f + 2f);
                assertTrue("hatch must stay inside the image", stroke[i + 1] >= 0f
                        && stroke[i + 1] < size);
            }
        }
    }

    @Test
    public void noEdgesMeansNoHatching() {
        int size = 160;
        int[] gray = new int[size * size];
        java.util.Arrays.fill(gray, 20);
        byte[] edges = new byte[size * size];
        ArrayList<float[]> hatch = HatchGenerator.generate(gray, size, size, edges,
                fullSettings(), 1f);
        assertTrue("a flat photo background should not be shaded", hatch.isEmpty());
    }

    @Test
    public void lightImageProducesNoHatching() {
        int size = 160;
        int[] gray = new int[size * size];
        java.util.Arrays.fill(gray, 245);
        byte[] edges = new byte[size * size];
        java.util.Arrays.fill(edges, (byte) 1);
        ArrayList<float[]> hatch = HatchGenerator.generate(gray, size, size, edges,
                fullSettings(), 1f);
        assertTrue("there is nothing dark enough to shade", hatch.isEmpty());
    }

    @Test
    public void hatchCountRespectsBudget() {
        int size = 200;
        int[] gray = new int[size * size];
        java.util.Arrays.fill(gray, 25);
        byte[] edges = new byte[size * size];
        java.util.Arrays.fill(edges, (byte) 1);
        DrawingSettings settings = fullSettings();
        ArrayList<float[]> hatch = HatchGenerator.generate(gray, size, size, edges,
                settings, 1f);
        int cap = settings.hatchStrokeBudget();
        assertFalse(hatch.isEmpty());
        assertTrue("hatch must respect its stroke budget, got " + hatch.size(),
                hatch.size() <= cap);
    }

    @Test
    public void hatchStaysNearTheFinishedOutlines() {
        int size = 300;
        int[] gray = new int[size * size];
        java.util.Arrays.fill(gray, 60);
        byte[] edges = new byte[size * size];
        java.util.Arrays.fill(edges, (byte) 1);
        // One small square outline: everything far away from it is background.
        java.util.ArrayList<float[]> outlines = new java.util.ArrayList<>();
        outlines.add(new float[]{40, 40, 120, 40, 120, 120, 40, 120, 40, 40});

        ArrayList<float[]> hatch = HatchGenerator.generate(gray, size, size, edges,
                fullSettings(), 1f, outlines);

        assertFalse("the outlined shape should be shaded", hatch.isEmpty());
        for (float[] stroke : hatch) {
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                assertTrue("hatch must not drift into the empty background, point="
                                + stroke[i] + "," + stroke[i + 1],
                        stroke[i] < 220 && stroke[i + 1] < 220);
            }
        }
    }

    @Test
    public void largerCanvasScaleSpreadsHatchLines() {
        int size = 300;
        int[] gray = new int[size * size];
        java.util.Arrays.fill(gray, 250);
        byte[] edges = new byte[size * size];
        for (int y = 120; y < 180; y++) {
            for (int x = 120; x < 180; x++) {
                gray[y * size + x] = 25;
                edges[y * size + x] = 1;
            }
        }
        ArrayList<float[]> dense = HatchGenerator.generate(gray, size, size, edges,
                fullSettings(), 0.8f);
        ArrayList<float[]> sparse = HatchGenerator.generate(gray, size, size, edges,
                fullSettings(), 2.4f);
        assertFalse(dense.isEmpty());
        assertFalse(sparse.isEmpty());
        assertTrue("wider spacing should need fewer hatch strokes, dense=" + dense.size()
                        + " sparse=" + sparse.size(),
                sparse.size() < dense.size());
    }
}
