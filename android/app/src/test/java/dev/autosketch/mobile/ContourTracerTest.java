package dev.autosketch.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

public class ContourTracerTest {

    @Test
    public void tracesStraightLineInOrder() {
        int width = 12;
        int height = 6;
        byte[] mask = new byte[width * height];
        for (int x = 2; x <= 9; x++) {
            mask[3 * width + x] = 1;
        }
        ArrayList<int[]> chains = ContourTracer.trace(mask, width, height, 2);
        assertEquals(1, chains.size());
        int[] chain = chains.get(0);
        assertEquals(8, chain.length);
        assertTrue(chain[0] == 3 * width + 2 || chain[0] == 3 * width + 9);
        for (int i = 1; i < chain.length; i++) {
            int step = Math.abs(chain[i] - chain[i - 1]);
            assertEquals("contour must stay connected", 1, step);
        }
    }

    @Test
    public void tracesClosedLoop() {
        int size = 24;
        byte[] mask = new byte[size * size];
        for (int i = 5; i <= 18; i++) {
            mask[5 * size + i] = 1;
            mask[18 * size + i] = 1;
            mask[i * size + 5] = 1;
            mask[i * size + 18] = 1;
        }
        ArrayList<int[]> chains = ContourTracer.trace(mask, size, size, 2);
        assertTrue("loop should be traced", chains.size() >= 1);
        int total = 0;
        for (int[] chain : chains) {
            total += chain.length;
        }
        assertTrue("loop should cover the outline, got " + total, total >= 50);
        for (int[] chain : chains) {
            for (int i = 1; i < chain.length; i++) {
                int dx = Math.abs(chain[i] % size - chain[i - 1] % size);
                int dy = Math.abs(chain[i] / size - chain[i - 1] / size);
                assertTrue("8-connected contour", dx <= 1 && dy <= 1);
            }
        }
    }

    @Test
    public void splitsAtJunctionInsteadOfJumpingBetweenBranches() {
        int width = 21;
        int height = 21;
        byte[] mask = new byte[width * height];
        for (int x = 2; x <= 18; x++) {
            mask[10 * width + x] = 1;
        }
        for (int y = 2; y <= 18; y++) {
            mask[y * width + 10] = 1;
        }
        ArrayList<int[]> chains = ContourTracer.trace(mask, width, height, 2);
        assertTrue("junction should produce several chains", chains.size() >= 4);
        for (int[] chain : chains) {
            for (int i = 1; i < chain.length; i++) {
                int dx = Math.abs(chain[i] % width - chain[i - 1] % width);
                int dy = Math.abs(chain[i] / width - chain[i - 1] / width);
                assertTrue(dx <= 1 && dy <= 1);
            }
        }
    }
}
