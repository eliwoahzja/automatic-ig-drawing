package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Final output of the sketch pipeline: ordered polylines in normalized image
 * space (0..1 on both axes). The very same list feeds the preview and the
 * gesture dispatcher, so what the user sees is what gets drawn.
 */
public final class Sketch {

    public final int width;
    public final int height;
    /** Flat x,y pairs per stroke, values in 0..1. Ordered for drawing. */
    public final List<float[]> strokes;
    public final int edgePixels;

    Sketch(int width, int height, ArrayList<float[]> strokes, int edgePixels) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
        this.edgePixels = edgePixels;
    }

    public static Sketch empty() {
        return new Sketch(1, 1, new ArrayList<float[]>(), 0);
    }

    public boolean isEmpty() {
        return strokes.isEmpty();
    }

    public int pointCount() {
        int total = 0;
        for (float[] stroke : strokes) {
            total += stroke.length / 2;
        }
        return total;
    }

    /** Total polyline length in image pixels, used for progress estimates. */
    public float pixelLength() {
        float total = 0f;
        for (float[] stroke : strokes) {
            for (int i = 2; i + 1 < stroke.length; i += 2) {
                float dx = (stroke[i] - stroke[i - 2]) * width;
                float dy = (stroke[i + 1] - stroke[i - 1]) * height;
                total += (float) Math.hypot(dx, dy);
            }
        }
        return total;
    }
}
