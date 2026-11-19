package dev.autosketch.mobile;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;

public class StrokeToolsTest {

    @Test public void smoothingKeepsEndpointsAndStaysInsideTheImage() {
        float[] line = {.1f, .1f, .5f, .9f, .9f, .1f};
        float[] smooth = StrokeTools.smooth(line, 4);
        assertEquals((2 * 4 + 1) * 2, smooth.length);
        assertEquals(.1f, smooth[0], 1e-5f);
        assertEquals(.1f, smooth[1], 1e-5f);
        assertEquals(.9f, smooth[smooth.length - 2], 1e-5f);
        for (float v : smooth) assertTrue(v >= 0f && v <= 1f);
    }

    @Test public void twoPointStrokesAreLeftAlone() {
        float[] line = {0f, 0f, 1f, 1f};
        assertSame(line, StrokeTools.smooth(line, 4));
    }

    @Test public void snapPullsNearbyPointsOntoTheTrace() {
        ArrayList<float[]> traced = new ArrayList<>();
        traced.add(new float[]{.2f, .5f, .4f, .5f, .6f, .5f, .8f, .5f});
        Sketch base = new Sketch(100, 100, traced, 0);
        ArrayList<float[]> ai = new ArrayList<>();
        ai.add(new float[]{.2f, .515f, .4f, .515f, .6f, .515f, .8f, .515f});
        ArrayList<float[]> out = StrokeTools.snap(ai, base, .03f);
        assertEquals(1, out.size());
        float[] line = out.get(0);
        for (int i = 1; i < line.length; i += 2) assertEquals(.5f, line[i], .004f);
    }

    @Test public void pointsFarFromTheTraceStayWhereTheyAre() {
        ArrayList<float[]> traced = new ArrayList<>();
        traced.add(new float[]{.1f, .1f, .2f, .1f});
        Sketch base = new Sketch(100, 100, traced, 0);
        ArrayList<float[]> ai = new ArrayList<>();
        ai.add(new float[]{.8f, .8f, .9f, .8f, .9f, .9f});
        float[] out = StrokeTools.snap(ai, base, .03f).get(0);
        assertEquals(.8f, out[0], 1e-5f);
        assertEquals(.8f, out[1], 1e-5f);
        assertEquals(.9f, out[4], 1e-5f);
    }

    @Test public void snapWithoutATraceReturnsInput() {
        ArrayList<float[]> ai = new ArrayList<>();
        ai.add(new float[]{.1f, .1f, .5f, .5f});
        assertSame(ai.get(0), StrokeTools.snap(ai, Sketch.empty(), .03f).get(0));
    }
}
