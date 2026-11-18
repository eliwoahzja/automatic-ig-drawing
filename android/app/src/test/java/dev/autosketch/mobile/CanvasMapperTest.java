package dev.autosketch.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class CanvasMapperTest {

    private final CanvasMapper.Rect area = new CanvasMapper.Rect(100, 200, 400, 500);

    @Test
    public void containPreservesAspectAndCenters() {
        CanvasMapper.Rect destination = CanvasMapper.destination(area, 600, 300,
                DrawingSettings.FIT_CONTAIN, true);
        assertEquals(300f, destination.width(), 0.01f);
        assertEquals(150f, destination.height(), 0.01f);
        assertEquals(100f, destination.left, 0.01f);
        assertEquals(275f, destination.top, 0.01f);
    }

    @Test
    public void containWithoutCenteringPinsTopLeft() {
        CanvasMapper.Rect destination = CanvasMapper.destination(area, 600, 300,
                DrawingSettings.FIT_CONTAIN, false);
        assertEquals(100f, destination.left, 0.01f);
        assertEquals(200f, destination.top, 0.01f);
    }

    @Test
    public void fillCropsInsteadOfStretching() {
        CanvasMapper.Rect destination = CanvasMapper.destination(area, 600, 300,
                DrawingSettings.FIT_FILL, true);
        assertEquals(600f, destination.width(), 0.01f);
        assertEquals(300f, destination.height(), 0.01f);
        assertEquals(-50f, destination.left, 0.01f);
        assertEquals(200f, destination.top, 0.01f);
    }

    @Test
    public void stretchUsesFullArea() {
        CanvasMapper.Rect destination = CanvasMapper.destination(area, 600, 300,
                DrawingSettings.FIT_STRETCH, true);
        assertEquals(area.left, destination.left, 0.01f);
        assertEquals(area.top, destination.top, 0.01f);
        assertEquals(area.right, destination.right, 0.01f);
        assertEquals(area.bottom, destination.bottom, 0.01f);
    }

    @Test
    public void mappedPointsStayInsideTheArea() {
        DrawingSettings settings = new DrawingSettings();
        settings.fitMode = DrawingSettings.FIT_CONTAIN;
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0f, 0f, 1f, 1f});
        strokes.add(new float[]{1f, 0f, 0f, 1f});
        ArrayList<float[]> mapped = CanvasMapper.mapStrokes(strokes, area, 200, 200, settings);
        assertFalse(mapped.isEmpty());
        for (float[] stroke : mapped) {
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                assertTrue("x inside", stroke[i] >= area.left - 0.01f && stroke[i] <= area.right + 0.01f);
                assertTrue("y inside", stroke[i + 1] >= area.top - 0.01f
                        && stroke[i + 1] <= area.bottom + 0.01f);
            }
        }
    }

    @Test
    public void fillClipsStrokesToTheVisibleArea() {
        DrawingSettings settings = new DrawingSettings();
        settings.fitMode = DrawingSettings.FIT_FILL;
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0.1f, 0.5f, 0.9f, 0.5f});
        ArrayList<float[]> mapped = CanvasMapper.mapStrokes(strokes, area, 200, 100, settings);
        assertFalse(mapped.isEmpty());
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float[] stroke : mapped) {
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                min = Math.min(min, stroke[i]);
                max = Math.max(max, stroke[i]);
                assertTrue(stroke[i] >= area.left - 0.01f && stroke[i] <= area.right + 0.01f);
            }
        }
        assertTrue("cropped stroke keeps its visible part", min <= area.left + 1f);
        assertTrue(max >= area.right - 1f);
    }

    @Test
    public void clipReturnsOnlyVisibleRuns() {
        float[] outside = {0, 250, 50, 250, 450, 250, 500, 250};
        ArrayList<float[]> pieces = CanvasMapper.clipToRect(outside, area);
        assertEquals(1, pieces.size());
        assertEquals(100f, pieces.get(0)[0], 0.01f);
        assertEquals(400f, pieces.get(0)[pieces.get(0).length - 2], 0.01f);
    }

    @Test
    public void shortStrokesAreFilteredOut() {
        DrawingSettings settings = new DrawingSettings();
        settings.minStrokeLengthPx = 20;
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0.5f, 0.5f, 0.502f, 0.5f});
        ArrayList<float[]> mapped = CanvasMapper.mapStrokes(strokes, area, 1000, 1000, settings);
        assertTrue("tiny marks must not be drawn", mapped.isEmpty());
    }

    @Test
    public void normalizedInputIsClamped() {
        DrawingSettings settings = new DrawingSettings();
        ArrayList<float[]> mapped = CanvasMapper.mapStrokes(
                Arrays.asList(new float[]{-0.2f, -0.2f, 1.2f, 1.2f}), area, 100, 100, settings);
        assertEquals(1, mapped.size());
        for (int i = 0; i + 1 < mapped.get(0).length; i += 2) {
            assertTrue(area.contains(mapped.get(0)[i], mapped.get(0)[i + 1]));
        }
    }
}
