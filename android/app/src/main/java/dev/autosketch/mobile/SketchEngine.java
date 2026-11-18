package dev.autosketch.mobile;

import java.util.ArrayList;

/**
 * The sketch pipeline. Takes ARGB pixels, runs grayscale + Gaussian smoothing,
 * Canny edge detection (Sobel, non-maximum suppression, dual-threshold
 * hysteresis), despeckling, Zhang-Suen thinning, contour tracing,
 * Douglas-Peucker simplification, stroke merging, duplicate removal, ordering
 * and normalisation.
 *
 * <p>Pure Java: no Android types, so it is covered by JVM unit tests.</p>
 */
public final class SketchEngine {

    public interface Cancellation {
        boolean isCancelled();
    }

    private static final Cancellation NEVER = new Cancellation() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private SketchEngine() {
    }

    /** Processing resolution for the given source size and detail level. */
    public static int[] processingSize(int width, int height, DrawingSettings settings) {
        int maxDimension = settings.maxProcessingDimension();
        float scale = Math.min(1f, maxDimension / (float) Math.max(1, Math.max(width, height)));
        return new int[]{
                Math.max(8, Math.round(width * scale)),
                Math.max(8, Math.round(height * scale))
        };
    }

    public static Sketch generate(int[] argb, int width, int height, DrawingSettings settings,
                                  int canvasWidthPx) {
        return generate(argb, width, height, settings, canvasWidthPx, NEVER);
    }

    public static Sketch generate(int[] argb, int width, int height, DrawingSettings settings,
                                  int canvasWidthPx, Cancellation cancellation) {
        if (argb == null || width < 8 || height < 8) {
            return Sketch.empty();
        }
        DrawingSettings config = settings == null ? new DrawingSettings() : settings;
        Cancellation cancel = cancellation == null ? NEVER : cancellation;

        int[] gray = EdgeDetector.grayscale(argb, width, height, config);
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }
        int[] blurred = EdgeDetector.blur(gray, width, height, config.blurSigma());
        byte[] edges = EdgeDetector.detect(blurred, width, height, config);
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }
        int edgePixels = countPixels(edges);
        byte[] skeleton = EdgeDetector.thin(edges, width, height);
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }

        ArrayList<int[]> chains = ContourTracer.trace(skeleton, width, height, 2);
        float referenceScale = DrawingSettings.clamp(width / (float) Math.max(120, canvasWidthPx),
                0.45f, 2.6f);
        float minLength = Math.max(1.8f, config.minStrokeLengthPx * referenceScale);
        int smoothPasses = config.smoothing < 20 ? 0 : (config.smoothing < 60 ? 1 : 2);

        ArrayList<float[]> polylines = new ArrayList<>();
        for (int[] chain : chains) {
            if (cancel.isCancelled()) {
                return Sketch.empty();
            }
            float[] points = toPixelPoints(chain, width);
            if (points.length < 4) {
                continue;
            }
            points = StrokeOptimizer.resample(points, 1.15f);
            points = StrokeOptimizer.smooth(points, smoothPasses, config.smoothStrength());
            points = StrokeOptimizer.simplify(points, config.simplifyTolerance());
            if (points.length < 4 || StrokeOptimizer.length(points) < minLength * 0.6f) {
                continue;
            }
            polylines.add(points);
        }
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }

        // Junction fragments from thinning are stitched back into longer strokes.
        polylines = StrokeOptimizer.mergeTouching(polylines, 2.0f, 78f);
        ArrayList<float[]> cleaned = new ArrayList<>();
        for (float[] stroke : polylines) {
            float[] simplified = StrokeOptimizer.simplify(stroke, config.simplifyTolerance() * 0.8f);
            if (simplified.length >= 4 && StrokeOptimizer.length(simplified) >= minLength) {
                cleaned.add(simplified);
            }
        }
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }

        // Longest outlines first: structural strokes survive every later filter.
        cleaned = StrokeOptimizer.sortByLengthDescending(cleaned);
        cleaned = StrokeOptimizer.removeNearDuplicates(cleaned, Math.max(1.8f, minLength * 0.4f));
        cleaned = filterOverlaps(cleaned, width, height, config);
        if (cleaned.size() > config.maxStrokes()) {
            cleaned = new ArrayList<>(cleaned.subList(0, config.maxStrokes()));
        }
        cleaned = StrokeOptimizer.orderNearestNeighbour(cleaned);
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }

        return new Sketch(width, height, normalize(cleaned, width, height), edgePixels);
    }

    private static int countPixels(byte[] mask) {
        int total = 0;
        for (byte value : mask) {
            if (value != 0) {
                total++;
            }
        }
        return total;
    }

    private static float[] toPixelPoints(int[] chain, int width) {
        float[] points = new float[chain.length * 2];
        for (int i = 0; i < chain.length; i++) {
            points[i * 2] = (chain[i] % width) + 0.5f;
            points[i * 2 + 1] = (chain[i] / width) + 0.5f;
        }
        return points;
    }

    /**
     * Rejects strokes that mostly run over pixels an already accepted stroke
     * covers. Because strokes arrive longest-first this removes duplicates and
     * double-traced contours without dropping the structure itself.
     */
    private static ArrayList<float[]> filterOverlaps(ArrayList<float[]> strokes, int width,
                                                     int height, DrawingSettings settings) {
        int cell = Math.max(2, Math.round(Math.min(width, height) / 260f));
        int columns = width / cell + 2;
        int rows = height / cell + 2;
        boolean[] occupied = new boolean[columns * rows];
        float rejectRatio = settings.overlapRejectRatio();
        ArrayList<float[]> kept = new ArrayList<>();
        for (float[] stroke : strokes) {
            int samples = 0;
            int covered = 0;
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                samples++;
                if (occupied[cellIndex(stroke[i], stroke[i + 1], cell, columns, rows)]) {
                    covered++;
                }
            }
            if (samples > 0 && covered / (float) samples > rejectRatio) {
                continue;
            }
            kept.add(stroke);
            for (int i = 2; i + 1 < stroke.length; i += 2) {
                markSegment(occupied, stroke[i - 2], stroke[i - 1], stroke[i], stroke[i + 1],
                        cell, columns, rows);
            }
        }
        return kept;
    }

    private static int cellIndex(float x, float y, int cell, int columns, int rows) {
        int column = DrawingSettings.clamp((int) (x / cell), 0, columns - 1);
        int row = DrawingSettings.clamp((int) (y / cell), 0, rows - 1);
        return row * columns + column;
    }

    private static void markSegment(boolean[] occupied, float x0, float y0, float x1, float y1,
                                     int cell, int columns, int rows) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        int steps = Math.max(1, (int) (Math.hypot(dx, dy) / (cell * 0.5f)) + 1);
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            occupied[cellIndex(x0 + dx * t, y0 + dy * t, cell, columns, rows)] = true;
        }
    }

    private static ArrayList<float[]> normalize(ArrayList<float[]> strokes, int width, int height) {
        ArrayList<float[]> out = new ArrayList<>(strokes.size());
        float maxX = Math.max(1f, width - 1f);
        float maxY = Math.max(1f, height - 1f);
        for (float[] stroke : strokes) {
            if (stroke.length < 4) {
                continue;
            }
            float[] normalized = new float[stroke.length];
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                normalized[i] = DrawingSettings.clamp(stroke[i] / maxX, 0f, 1f);
                normalized[i + 1] = DrawingSettings.clamp(stroke[i + 1] / maxY, 0f, 1f);
            }
            out.add(normalized);
        }
        return out;
    }
}
