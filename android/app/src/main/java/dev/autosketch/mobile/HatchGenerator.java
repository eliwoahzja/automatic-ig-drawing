package dev.autosketch.mobile;

import java.util.ArrayList;

/**
 * Tonal hatching for the full drawing mode. Three crossing hatch layers are
 * generated from the blurred greyscale image: light shading for mid tones,
 * cross-hatching for shadows and a third pass for the darkest areas. Runs are
 * split into pen-sized dashes and simplified like traced contours, so the
 * result is drawn with the same continuous gesture pipeline as the outlines.
 *
 * <p>Hatching only appears near detected edges. That keeps flat photo
 * backgrounds and empty canvas out of the drawing and puts the shading on the
 * subject, where the contour detail already is.</p>
 */
public final class HatchGenerator {

    private static final float[] ANGLES = {38f, -47f, 82f};
    private static final float[] THRESHOLDS = {0.40f, 0.58f, 0.72f};

    private HatchGenerator() {
    }

    public static ArrayList<float[]> generate(int[] gray, int width, int height, byte[] edges,
                                              DrawingSettings settings, float densityScale) {
        ArrayList<float[]> strokes = new ArrayList<>();
        if (gray == null || width < 8 || height < 8) {
            return strokes;
        }
        int shortSide = Math.min(width, height);
        float scale = DrawingSettings.clamp(densityScale, 0.8f, 2.4f);
        int spacing = Math.max(6,
                Math.round(shortSide / 30f * scale) - settings.detailLevel);
        int step = Math.max(1, Math.round(spacing / 3f));
        int minRun = Math.max(6, spacing);
        int maxRun = Math.max(18, spacing * 2);
        int cap = 260 + settings.detailLevel * 130;
        int layerCap = Math.max(50, cap / ANGLES.length);
        int diagonal = (int) Math.ceil(Math.hypot(width, height));
        int centerX = width / 2;
        int centerY = height / 2;
        int detail = settings.detailLevel;
        boolean[] nearEdge = edgeProximity(edges, width, height, Math.max(8, spacing * 3));

        for (int layer = 0; layer < ANGLES.length; layer++) {
            int stopAt = Math.min(cap, strokes.size() + layerCap);
            double angle = Math.toRadians(ANGLES[layer]);
            float dirX = (float) Math.cos(angle);
            float dirY = (float) Math.sin(angle);
            float perpX = -dirY;
            float perpY = dirX;
            float threshold = THRESHOLDS[layer] - detail * 0.03f;
            for (int offset = -diagonal; offset <= diagonal && strokes.size() < stopAt;
                 offset += spacing) {
                ArrayList<Float> run = new ArrayList<>();
                int gap = 0;
                for (int t = -diagonal; t <= diagonal && strokes.size() < stopAt; t += step) {
                    float x = centerX + perpX * offset + dirX * t;
                    float y = centerY + perpY * offset + dirY * t;
                    int ix = (int) x;
                    int iy = (int) y;
                    boolean inside = ix >= 1 && iy >= 1 && ix < width - 1 && iy < height - 1;
                    float darkness = 0f;
                    if (inside) {
                        int index = iy * width + ix;
                        int maxGray = Math.round((1f - threshold) * 255f);
                        if (nearEdge[index] && solidDark(gray, width, height, ix, iy,
                                Math.max(2, step), maxGray)) {
                            darkness = 1f - gray[index] / 255f;
                        }
                    }
                    if (darkness >= threshold) {
                        run.add(x);
                        run.add(y);
                        gap = 0;
                    } else if (!run.isEmpty()) {
                        if (++gap > 2) {
                            emit(run, strokes, stopAt, minRun, maxRun, step);
                            run.clear();
                            gap = 0;
                        }
                    }
                }
                if (!run.isEmpty()) {
                    emit(run, strokes, stopAt, minRun, maxRun, step);
                }
            }
        }
        return strokes;
    }

    /** Requires the sample and its four neighbours to be dark, keeping hatch inside shapes. */
    private static boolean solidDark(int[] gray, int width, int height, int x, int y, int radius,
                                     int maxGray) {
        if (gray[y * width + x] > maxGray) {
            return false;
        }
        int left = Math.max(0, x - radius);
        int right = Math.min(width - 1, x + radius);
        int top = Math.max(0, y - radius);
        int bottom = Math.min(height - 1, y + radius);
        return gray[y * width + left] <= maxGray
                && gray[y * width + right] <= maxGray
                && gray[top * width + x] <= maxGray
                && gray[bottom * width + x] <= maxGray;
    }

    /** Chamfer-style dilation of the edge mask, limited to {@code radius} pixels. */
    private static boolean[] edgeProximity(byte[] edges, int width, int height, int radius) {
        int count = width * height;
        boolean[] near = new boolean[count];
        int[] queue = new int[count];
        int head = 0;
        int tail = 0;
        if (edges != null && edges.length == count) {
            for (int i = 0; i < count; i++) {
                if (edges[i] != 0) {
                    near[i] = true;
                    queue[tail++] = i;
                }
            }
        }
        int depth = 0;
        while (head < tail && depth < radius) {
            int levelSize = tail - head;
            for (int s = 0; s < levelSize; s++) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                if (x > 0) {
                    tail = visit(near, queue, tail, index - 1);
                }
                if (x < width - 1) {
                    tail = visit(near, queue, tail, index + 1);
                }
                if (y > 0) {
                    tail = visit(near, queue, tail, index - width);
                }
                if (y < height - 1) {
                    tail = visit(near, queue, tail, index + width);
                }
            }
            depth++;
        }
        return near;
    }

    private static int visit(boolean[] near, int[] queue, int tail, int index) {
        if (!near[index]) {
            near[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static void emit(ArrayList<Float> run, ArrayList<float[]> out, int stopAt,
                             int minRun, int maxRun, int step) {
        if (run.size() < 4 || out.size() >= stopAt) {
            return;
        }
        float[] points = new float[run.size()];
        for (int i = 0; i < points.length; i++) {
            points[i] = run.get(i);
        }
        if (StrokeOptimizer.length(points) < minRun) {
            return;
        }
        ArrayList<float[]> dashes = StrokeOptimizer.chunk(points,
                Math.max(2, Math.round(maxRun / (float) Math.max(1, step))));
        for (float[] dash : dashes) {
            float[] simplified = StrokeOptimizer.simplify(dash, 0.4f);
            if (simplified.length >= 4 && StrokeOptimizer.length(simplified) >= minRun * 0.6f) {
                out.add(simplified);
                if (out.size() >= stopAt) {
                    return;
                }
            }
        }
    }
}
