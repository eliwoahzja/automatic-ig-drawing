package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Pure geometry helpers for AI-made strokes (no Android dependencies). */
public final class StrokeTools {

    private StrokeTools() {}

    /** Uniform Catmull-Rom subdivision; turns the model's coarse polylines into smooth curves. */
    public static float[] smooth(float[] stroke, int subdivisions) {
        int n = stroke.length / 2;
        if (n < 3 || subdivisions < 2) return stroke;
        float[] out = new float[((n - 1) * subdivisions + 1) * 2];
        int o = 0;
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i3 = Math.min(n - 1, i + 2);
            for (int k = 0; k < subdivisions; k++) {
                float t = k / (float) subdivisions, t2 = t * t, t3 = t2 * t;
                for (int axis = 0; axis < 2; axis++) {
                    float p0 = stroke[i0 * 2 + axis], p1 = stroke[i * 2 + axis];
                    float p2 = stroke[(i + 1) * 2 + axis], p3 = stroke[i3 * 2 + axis];
                    float v = .5f * ((2f * p1) + (-p0 + p2) * t
                            + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                            + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
                    out[o++] = DrawingSettings.clamp(v, 0f, 1f);
                }
            }
        }
        out[o++] = stroke[(n - 1) * 2];
        out[o] = stroke[(n - 1) * 2 + 1];
        return out;
    }

    /**
     * Pulls AI points onto the nearest traced edge. The AI decides what to draw and in which
     * order; the edge trace decides exactly where the line sits.
     *
     * @param radius search distance as a fraction of the image height
     */
    public static ArrayList<float[]> snap(List<float[]> strokes, Sketch base, float radius) {
        ArrayList<float[]> result = new ArrayList<>();
        if (base == null || base.isEmpty()) {
            result.addAll(strokes);
            return result;
        }
        final float aspect = base.width / (float) base.height;
        int total = base.pointCount();
        float[] bx = new float[total], by = new float[total];
        int count = 0;
        for (float[] s : base.strokes) {
            for (int i = 0; i + 1 < s.length; i += 2) {
                bx[count] = s[i] * aspect;
                by[count] = s[i + 1];
                count++;
            }
        }
        HashMap<Integer, ArrayList<Integer>> grid = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int key = cell(bx[i], radius) * 4096 + cell(by[i], radius);
            ArrayList<Integer> bucket = grid.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grid.put(key, bucket);
            }
            bucket.add(i);
        }
        float limit = radius * radius;
        for (float[] stroke : strokes) {
            float[] moved = stroke.clone();
            for (int i = 0; i + 1 < moved.length; i += 2) {
                float px = moved[i] * aspect, py = moved[i + 1];
                int cx = cell(px, radius), cy = cell(py, radius);
                float best = limit;
                int found = -1;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        ArrayList<Integer> bucket = grid.get((cx + dx) * 4096 + (cy + dy));
                        if (bucket == null) continue;
                        for (int index : bucket) {
                            float ddx = bx[index] - px, ddy = by[index] - py;
                            float d = ddx * ddx + ddy * ddy;
                            if (d < best) {
                                best = d;
                                found = index;
                            }
                        }
                    }
                }
                if (found >= 0) {
                    moved[i] = (px + .85f * (bx[found] - px)) / aspect;
                    moved[i + 1] = py + .85f * (by[found] - py);
                }
            }
            result.add(relax(moved));
        }
        return result;
    }

    /** One light 3-tap smoothing pass that keeps both end points. */
    static float[] relax(float[] s) {
        int n = s.length / 2;
        if (n < 3) return s;
        float[] out = s.clone();
        for (int i = 1; i < n - 1; i++) {
            for (int axis = 0; axis < 2; axis++) {
                out[i * 2 + axis] = .25f * s[(i - 1) * 2 + axis] + .5f * s[i * 2 + axis]
                        + .25f * s[(i + 1) * 2 + axis];
            }
        }
        return out;
    }

    private static int cell(float value, float size) {
        return Math.max(0, Math.min(4000, (int) Math.floor(value / size)));
    }
}
