package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.List;

/**
 * Tonal hatching for the full drawing mode. Three crossing hatch layers are
 * generated from the blurred greyscale image: light shading for mid tones,
 * cross-hatching for shadows and a third pass for the darkest areas. Runs are
 * split into pen-sized dashes and simplified like traced contours, so the
 * result is drawn with the same continuous gesture pipeline as the outlines.
 *
 * <p>When the finished outlines are supplied, hatching is limited to the areas
 * they enclose. Flood-filling the outline walls from the image border gives the
 * inside of the subject, so a decorated background, a busy wallpaper or a page
 * of watermark text never receives diagonal stripes. Hatching is additionally
 * restricted to pixels that are genuinely dark and surrounded by dark pixels,
 * which keeps a single dark hair strand from shading the whole face.</p>
 */
public final class HatchGenerator {

    private static final float[] ANGLES = {38f, -47f, 82f};
    private static final float[] THRESHOLDS = {0.40f, 0.58f, 0.72f};

    private HatchGenerator() {
    }

    public static ArrayList<float[]> generate(int[] gray, int width, int height, byte[] edges,
                                              DrawingSettings settings, float densityScale) {
        return generate(gray, width, height, edges, settings, densityScale, null);
    }

    /**
     * @param outlines the finished, filtered outlines in pixel coordinates; when
     *                 present the hatch is confined to the regions they enclose.
     */
    public static ArrayList<float[]> generate(int[] gray, int width, int height, byte[] edges,
                                              DrawingSettings settings, float densityScale,
                                              List<float[]> outlines) {
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
        int cap = settings.hatchStrokeBudget();
        int layerCap = Math.max(50, cap / ANGLES.length);
        int diagonal = (int) Math.ceil(Math.hypot(width, height));
        int centerX = width / 2;
        int centerY = height / 2;
        int detail = settings.detailLevel;
        boolean[] allowed = hatchArea(edges, width, height, outlines, spacing);

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
                        if (allowed[index] && solidDark(gray, width, height, ix, iy,
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

    /**
     * Where hatching is allowed at all: the areas enclosed by the finished
     * outlines, or (when no outlines are supplied) the neighbourhood of the raw
     * edge mask.
     */
    static boolean[] hatchArea(byte[] edges, int width, int height,
                               List<float[]> outlines, int spacing) {
        if (outlines == null || outlines.isEmpty()) {
            return edgeProximity(edges, width, height, Math.max(8, spacing * 3));
        }
        // Only strokes that survived filtering count: raw edge pixels are spread
        // over the whole photo (glitter, grain, wallpaper), which is what used to
        // stripe the background. Hatching stays within a few hatch spacings of a
        // real contour, and solidDark() keeps it on actual shadow.
        byte[] walls = rasterize(outlines, width, height);
        walls = dilate(walls, width, height, 1);
        return proximity(walls, width, height, Math.max(12, spacing * 3));
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

    /** Rasterises polylines into a wall mask. */
    private static byte[] rasterize(List<float[]> outlines, int width, int height) {
        byte[] walls = new byte[width * height];
        for (float[] stroke : outlines) {
            if (stroke == null || stroke.length < 4) {
                continue;
            }
            for (int i = 2; i + 1 < stroke.length; i += 2) {
                drawLine(walls, width, height, stroke[i - 2], stroke[i - 1], stroke[i],
                        stroke[i + 1]);
            }
        }
        return walls;
    }

    private static void drawLine(byte[] mask, int width, int height, float x0, float y0,
                                 float x1, float y1) {
        int ax = Math.round(x0);
        int ay = Math.round(y0);
        int bx = Math.round(x1);
        int by = Math.round(y1);
        int dx = Math.abs(bx - ax);
        int dy = -Math.abs(by - ay);
        int sx = ax < bx ? 1 : -1;
        int sy = ay < by ? 1 : -1;
        int error = dx + dy;
        while (true) {
            if (ax >= 0 && ay >= 0 && ax < width && ay < height) {
                mask[ay * width + ax] = 1;
            }
            if (ax == bx && ay == by) {
                return;
            }
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                ax += sx;
            }
            if (doubled <= dx) {
                error += dx;
                ay += sy;
            }
        }
    }

    /** Separable square dilation. */
    private static byte[] dilate(byte[] mask, int width, int height, int radius) {
        if (radius <= 0) {
            return mask;
        }
        byte[] horizontal = new byte[mask.length];
        byte[] out = new byte[mask.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int from = Math.max(0, x - radius);
                int to = Math.min(width - 1, x + radius);
                byte value = 0;
                for (int k = from; k <= to; k++) {
                    if (mask[row + k] != 0) {
                        value = 1;
                        break;
                    }
                }
                horizontal[row + x] = value;
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int from = Math.max(0, y - radius);
                int to = Math.min(height - 1, y + radius);
                byte value = 0;
                for (int k = from; k <= to; k++) {
                    if (horizontal[k * width + x] != 0) {
                        value = 1;
                        break;
                    }
                }
                out[y * width + x] = value;
            }
        }
        return out;
    }

    /** Square dilation of a mask expressed as a boolean proximity test. */
    private static boolean[] proximity(byte[] mask, int width, int height, int radius) {
        boolean[] near = new boolean[width * height];
        byte[] grown = dilate(mask, width, height, radius);
        for (int i = 0; i < grown.length; i++) {
            near[i] = grown[i] != 0;
        }
        return near;
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
