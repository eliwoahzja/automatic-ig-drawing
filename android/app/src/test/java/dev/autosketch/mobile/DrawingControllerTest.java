package dev.autosketch.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class DrawingControllerTest {

    @Test
    public void longStrokeIsSplitIntoContinuousChunks() {
        float[] stroke = {0, 0, 20, 0, 40, 0, 60, 0, 80, 0, 100, 0};
        ArrayList<float[]> chunks = DrawingController.splitForGestures(stroke, 25f);
        assertTrue("should split", chunks.size() >= 2);
        for (int i = 1; i < chunks.size(); i++) {
            float[] previous = chunks.get(i - 1);
            float[] current = chunks.get(i);
            assertEquals("chunk start must equal previous chunk end (x)",
                    previous[previous.length - 2], current[0], 0.001f);
            assertEquals("chunk start must equal previous chunk end (y)",
                    previous[previous.length - 1], current[1], 0.001f);
        }
        float[] last = chunks.get(chunks.size() - 1);
        assertEquals(100f, last[last.length - 2], 0.001f);
        assertEquals(0f, last[last.length - 1], 0.001f);
    }

    @Test
    public void shortStrokeStaysOneChunk() {
        float[] stroke = {5, 5, 10, 10, 15, 15};
        ArrayList<float[]> chunks = DrawingController.splitForGestures(stroke, 500f);
        assertEquals(1, chunks.size());
        assertEquals(3, chunks.get(0).length / 2);
    }

    @Test
    public void degenerateStrokesAreDropped() {
        assertTrue(DrawingController.splitForGestures(new float[]{1, 1}, 100f).isEmpty());
        assertTrue(DrawingController.splitForGestures(null, 100f).isEmpty());
    }

    @Test
    public void reverseDirectionIsNotIntroducedWithinAStroke() {
        float[] stroke = {0, 0, 10, 0, 20, 0, 30, 0};
        ArrayList<float[]> chunks = DrawingController.splitForGestures(stroke, 15f);
        float previousX = -1f;
        for (float[] chunk : chunks) {
            for (int i = 0; i + 1 < chunk.length; i += 2) {
                assertTrue("monotonic direction", chunk[i] >= previousX);
                previousX = chunk[i];
            }
        }
    }
}
