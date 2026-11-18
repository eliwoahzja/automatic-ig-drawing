package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The sketch pipeline. Takes ARGB pixels, runs grayscale + Gaussian smoothing,
 * Canny edge detection (Sobel, non-maximum suppression, dual-threshold
 * hysteresis), despeckling, Zhang-Suen thinning, contour tracing,
 * Douglas-Peucker simplification, colour-saliency filtering, stroke merging,
 * duplicate and decoration removal, ordering and normalisation.
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
        int[] blurred = EdgeDetector.blur(gray, width, height,
                config.blurSigma(Math.min(width, height)));
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
        float minLength = Math.max(1.8f, config.minStrokeLengthPx * referenceScale)
                * config.minStrokeMultiplier();
        float tolerance = config.simplifyTolerance() * config.simplifyMultiplier();
        int smoothPasses = config.smoothing < 20 ? 0 : (config.smoothing < 60 ? 1 : 2);
        // Grain and sensor noise put a floor under what counts as a real
        // boundary; without it a noisy photo traces its own noise floor.
        float minStepContrast = Math.max(config.minStepContrast(),
                EdgeDetector.noiseLevel(gray, width, height) * 3f);

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
            points = StrokeOptimizer.simplify(points, tolerance);
            if (points.length < 4 || StrokeOptimizer.length(points) < minLength * 0.6f) {
                continue;
            }
            // Texture, glitter and watermarks all produce gradients but no real
            // boundary; only keep strokes that separate two different colours.
            if (StrokeSaliency.stepContrast(points, argb, width, height) < minStepContrast) {
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
            float[] simplified = StrokeOptimizer.simplify(stroke, tolerance * 0.8f);
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
        // Distance-based coverage removes the second line of every thick ink
        // stroke (the "double outline" halo) and duplicate traces.
        float coverageRadius = Math.max(2.2f, Math.min(width, height) * 0.008f)
                * (config.renderMode == DrawingSettings.MODE_CLEAN ? 1.3f : 1f);
        float rejectRatio = DrawingSettings.clamp(
                0.94f - config.overlapRejectRatio() * 0.45f, 0.55f, 0.9f);
        if (config.renderMode == DrawingSettings.MODE_CLEAN) {
            rejectRatio = Math.max(0.5f, rejectRatio - 0.1f);
        }
        cleaned = StrokeOptimizer.suppressCovered(cleaned, coverageRadius, rejectRatio);
        cleaned = StrokeOptimizer.dropTinyLoops(cleaned, width, height);
        // Rank by structure (length) and by how clearly the stroke separates two
        // areas, so a small budget keeps the face before background texture.
        cleaned = rankBySalience(cleaned, argb, width, height);

        int outlineBudget = config.renderMode == DrawingSettings.MODE_FULL
                ? Math.max(80, Math.round(config.maxStrokes() * 0.75f))
                : config.maxStrokesForRenderMode();
        if (cleaned.size() > outlineBudget) {
            cleaned = new ArrayList<>(cleaned.subList(0, outlineBudget));
        }
        if (config.renderMode == DrawingSettings.MODE_FULL && !cancel.isCancelled()) {
            ArrayList<float[]> hatch = HatchGenerator.generate(blurred, width, height, edges,
                    config, referenceScale, cleaned);
            int totalBudget = config.maxStrokesForRenderMode();
            for (float[] stroke : hatch) {
                if (cleaned.size() >= totalBudget) {
                    break;
                }
                cleaned.add(stroke);
            }
        }
        cleaned = StrokeOptimizer.orderNearestNeighbour(cleaned);
        if (cancel.isCancelled()) {
            return Sketch.empty();
        }

        ArrayList<float[]> normalized = normalize(cleaned, width, height);
        if (config.renderMode == DrawingSettings.MODE_COLOR) {
            ArrayList<ColorLayer> colors = ColorPlanner.generate(argb, width, height,
                    normalized, config.colorDensity, cancel);
            if (cancel.isCancelled()) return Sketch.empty();
            return new Sketch(width, height, normalized, edgePixels, colors);
        }
        return new Sketch(width, height, normalized, edgePixels);
    }

    /**
     * Longest-first order that also prefers contours with a real colour step;
     * texture scribbles are long-ish but flat across, so they rank low.
     */
    private static ArrayList<float[]> rankBySalience(ArrayList<float[]> strokes, int[] argb,
                                                     int width, int height) {
        int count = strokes.size();
        if (count <= 1) {
            return new ArrayList<>(strokes);
        }
        final float[] score = new float[count];
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            float[] stroke = strokes.get(i);
            float contrast = StrokeSaliency.stepContrast(stroke, argb, width, height);
            score[i] = StrokeOptimizer.length(stroke) * (0.35f + contrast / 18f);
            order[i] = i;
        }
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(score[b], score[a]);
            }
        });
        ArrayList<float[]> ranked = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ranked.add(strokes.get(order[i]));
        }
        return ranked;
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
