package dev.autosketch.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class StrokeOptimizerTest {

    @Test
    public void simplifyReducesCollinearLineToEndpoints() {
        float[] line = {0, 10, 10, 10, 20, 10, 30, 10, 40, 10};
        float[] simplified = StrokeOptimizer.simplify(line, 1f);
        assertEquals(4, simplified.length);
        assertEquals(0f, simplified[0], 0.001f);
        assertEquals(40f, simplified[2], 0.001f);
    }

    @Test
    public void simplifyKeepsCorners() {
        float[] corner = {0, 0, 10, 0, 20, 0, 20, 10, 20, 20};
        float[] simplified = StrokeOptimizer.simplify(corner, 0.5f);
        assertTrue(simplified.length >= 6);
    }

    @Test
    public void resampleProducesEvenSpacingAndKeepsEndpoints() {
        float[] line = {0, 0, 100, 0};
        float[] resampled = StrokeOptimizer.resample(line, 10f);
        assertEquals(0f, resampled[0], 0.001f);
        assertEquals(100f, resampled[resampled.length - 2], 0.001f);
        for (int i = 2; i < resampled.length; i += 2) {
            assertEquals(10f, resampled[i] - resampled[i - 2], 0.01f);
        }
    }

    @Test
    public void chunkSharesBoundaryPoints() {
        float[] line = {0, 0, 10, 0, 20, 0, 30, 0, 40, 0, 50, 0};
        ArrayList<float[]> chunks = StrokeOptimizer.chunk(line, 3);
        assertTrue(chunks.size() >= 2);
        for (int i = 1; i < chunks.size(); i++) {
            float[] previous = chunks.get(i - 1);
            float[] current = chunks.get(i);
            assertEquals(previous[previous.length - 2], current[0], 0.001f);
            assertEquals(previous[previous.length - 1], current[1], 0.001f);
        }
        assertEquals(50f, chunks.get(chunks.size() - 1)[chunks.size() > 0
                ? chunks.get(chunks.size() - 1).length - 2 : 0], 0.001f);
    }

    @Test
    public void mergeTouchingJoinsCollinearFragments() {
        ArrayList<float[]> fragments = new ArrayList<>();
        fragments.add(new float[]{0, 0, 10, 0});
        fragments.add(new float[]{10, 0, 20, 0});
        ArrayList<float[]> merged = StrokeOptimizer.mergeTouching(fragments, 1.5f, 70f);
        assertEquals(1, merged.size());
        assertEquals(3, merged.get(0).length / 2);
    }

    @Test
    public void mergeTouchingRejectsSharpTurns() {
        ArrayList<float[]> fragments = new ArrayList<>();
        fragments.add(new float[]{0, 0, 10, 0});
        fragments.add(new float[]{10, 0, 10, 10});
        ArrayList<float[]> merged = StrokeOptimizer.mergeTouching(fragments, 1.5f, 20f);
        assertEquals(2, merged.size());
    }

    @Test
    public void removeNearDuplicatesDropsReversedCopy() {
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0, 0, 10, 0, 10, 10});
        strokes.add(new float[]{10, 10, 10, 0, 0, 0});
        strokes.add(new float[]{30, 30, 40, 30});
        ArrayList<float[]> filtered = StrokeOptimizer.removeNearDuplicates(strokes, 2f);
        assertEquals(2, filtered.size());
    }

    @Test
    public void nearestNeighbourOrderingMinimisesTravel() {
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{100, 100, 110, 100});
        strokes.add(new float[]{0, 0, 10, 0});
        strokes.add(new float[]{20, 0, 30, 0});
        ArrayList<float[]> ordered = StrokeOptimizer.orderNearestNeighbour(strokes);
        assertEquals(3, ordered.size());
        assertEquals(0f, ordered.get(0)[0], 0.001f);
        assertEquals(20f, ordered.get(1)[0], 0.001f);
        assertEquals(100f, ordered.get(2)[0], 0.001f);
    }

    @Test
    public void nearestNeighbourCanReverseAStroke() {
        ArrayList<float[]> strokes = new ArrayList<>();
        strokes.add(new float[]{0, 0, 10, 0});
        strokes.add(new float[]{100, 0, 15, 0});
        ArrayList<float[]> ordered = StrokeOptimizer.orderNearestNeighbour(strokes);
        assertEquals(2, ordered.size());
        assertEquals(10f, ordered.get(0)[2], 0.001f);
        assertEquals(15f, ordered.get(1)[0], 0.001f);
    }

    @Test
    public void smoothingKeepsEndpoints() {
        float[] line = {0, 0, 5, 8, 10, 0, 15, 8, 20, 0};
        float[] smoothed = StrokeOptimizer.smooth(line, 2, 0.8f);
        assertEquals(0f, smoothed[0], 0.001f);
        assertEquals(0f, smoothed[1], 0.001f);
        assertEquals(20f, smoothed[smoothed.length - 2], 0.001f);
        assertEquals(0f, smoothed[smoothed.length - 1], 0.001f);
    }

    @Test
    public void reverseReturnsSameGeometry() {
        float[] line = {0, 0, 10, 2, 20, 4};
        float[] reversed = StrokeOptimizer.reverse(line);
        assertArrayEquals(new float[]{20, 4, 10, 2, 0, 0}, reversed, 0.001f);
        assertEquals(StrokeOptimizer.length(line), StrokeOptimizer.length(reversed), 0.001f);
    }
}
