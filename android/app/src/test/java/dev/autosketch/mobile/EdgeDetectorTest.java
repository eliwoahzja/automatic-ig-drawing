package dev.autosketch.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EdgeDetectorTest {

    @Test
    public void grayscaleUsesLumaWeights() {
        int[] argb = {0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF};
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_LOW;
        settings.backgroundSuppression = 0;
        int[] gray = EdgeDetector.grayscale(argb, 5, 1, settings);
        assertEquals(255, gray[0]);
        assertEquals(0, gray[1]);
        assertTrue("red luma", Math.abs(gray[2] - 76) <= 2);
        assertTrue("green luma", Math.abs(gray[3] - 149) <= 2);
        assertTrue("blue luma", Math.abs(gray[4] - 28) <= 2);
    }

    @Test
    public void transparentPixelsCompositeOverWhite() {
        int[] argb = {0x00000000, 0x40FF0000, 0x80000000, 0xFF000000};
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_LOW;
        settings.backgroundSuppression = 0;
        int[] gray = EdgeDetector.grayscale(argb, 4, 1, settings);
        assertEquals("fully transparent is blank paper", 255, gray[0]);
        assertTrue("partly transparent ink is lighter than solid ink", gray[1] > 100);
        assertTrue("semi-transparent black stays grey", gray[2] > 100 && gray[2] < 160);
        assertEquals("opaque ink is black", 0, gray[3]);
    }

    @Test
    public void invertAndBackgroundSuppressionApply() {
        int[] argb = {0xFFFF0000, 0xFFF0F0F0};
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = DrawingSettings.DETAIL_LOW;
        settings.invert = true;
        settings.backgroundSuppression = 100;
        int[] gray = EdgeDetector.grayscale(argb, 2, 1, settings);
        assertEquals(179, gray[0]);
        assertEquals("inverted light area becomes dark ink", 15, gray[1]);
    }

    @Test
    public void gaussianBlurKeepsFlatAreasFlat() {
        int width = 16;
        int height = 16;
        int[] gray = new int[width * height];
        java.util.Arrays.fill(gray, 120);
        int[] blurred = EdgeDetector.blur(gray, width, height, 1.4f);
        assertEquals(120, blurred[0]);
        assertEquals(120, blurred[blurred.length - 1]);
        assertEquals(120, blurred[width * 8 + 8]);
    }

    @Test
    public void cannyFindsRectangleBorderAndIgnoresFlatAreas() {
        int size = 72;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inside = x >= 20 && x < 52 && y >= 20 && y < 52;
                argb[y * size + x] = inside ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        DrawingSettings settings = new DrawingSettings();
        settings.backgroundSuppression = 0;
        settings.noiseReduction = 20;
        int[] gray = EdgeDetector.grayscale(argb, size, size, settings);
        int[] blurred = EdgeDetector.blur(gray, size, size, settings.blurSigma());
        byte[] mask = EdgeDetector.detect(blurred, size, size, settings);

        int count = 0;
        for (byte value : mask) {
            if (value != 0) {
                count++;
            }
        }
        assertTrue("border should produce edges, got " + count, count > 40);
        assertEquals("flat interior must stay empty", 0, mask[36 * size + 36]);
        assertEquals("flat exterior must stay empty", 0, mask[4 * size + 4]);
        assertEquals("edge at the top border", 1, mask[20 * size + 30]);
    }

    @Test
    public void thinningNeverAddsPixelsAndKeepsLineConnected() {
        int width = 24;
        int height = 12;
        byte[] mask = new byte[width * height];
        for (int y = 4; y <= 6; y++) {
            for (int x = 3; x < 20; x++) {
                mask[y * width + x] = 1;
            }
        }
        int before = 0;
        for (byte value : mask) {
            if (value != 0) {
                before++;
            }
        }
        byte[] thinned = EdgeDetector.thin(mask, width, height);
        int after = 0;
        for (byte value : thinned) {
            if (value != 0) {
                after++;
            }
        }
        assertTrue("skeleton should be thinner: " + after + " vs " + before, after < before);
        assertTrue("skeleton must keep the line", after >= 14);
        for (int x = 3; x < 20; x++) {
            int column = 0;
            for (int y = 0; y < height; y++) {
                if (thinned[y * width + x] != 0) {
                    column++;
                }
            }
            assertTrue("line should be one pixel wide at x=" + x, column <= 2);
        }
    }
}
