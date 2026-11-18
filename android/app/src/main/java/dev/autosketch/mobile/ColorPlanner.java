package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ColorPlanner {
    private static final int MAX_CLUSTERS = 5;

    private ColorPlanner() {}

    public static ArrayList<ColorLayer> generate(int[] pixels, int width, int height,
                                                   List<float[]> outlines, int density,
                                                   SketchEngine.Cancellation cancellation) {
        ArrayList<ColorLayer> layers = new ArrayList<>();
        if (pixels == null || width < 8 || height < 8) return layers;
        int total = width * height;
        if (pixels.length < total) return layers;
        ArrayList<Integer> candidates = new ArrayList<>();
        int stride = Math.max(1, total / 12000);
        for (int i = 0; i < total; i += stride) {
            if (eligible(pixels[i])) candidates.add(pixels[i]);
        }
        if (candidates.size() < 50) {
            addOutline(layers, outlines);
            return layers;
        }

        int count = Math.min(MAX_CLUSTERS, Math.max(2, candidates.size() / 160));
        float[][] center = new float[count][3];
        int first = candidates.get(candidates.size() / 2);
        rgb(first, center[0]);
        for (int k = 1; k < count; k++) {
            double best = -1;
            int chosen = candidates.get(0);
            for (int color : candidates) {
                double shortest = Double.MAX_VALUE;
                for (int j = 0; j < k; j++)
                    shortest = Math.min(shortest, distance(color, center[j]));
                if (shortest > best) {
                    best = shortest;
                    chosen = color;
                }
            }
            rgb(chosen, center[k]);
        }

        int[] counts = new int[count];
        float[][] sums = new float[count][3];
        for (int iteration = 0; iteration < 7; iteration++) {
            Arrays.fill(counts, 0);
            for (int k = 0; k < count; k++) Arrays.fill(sums[k], 0f);
            for (int color : candidates) {
                int index = nearest(color, center);
                counts[index]++;
                sums[index][0] += (color >> 16) & 255;
                sums[index][1] += (color >> 8) & 255;
                sums[index][2] += color & 255;
            }
            for (int k = 0; k < count; k++) {
                if (counts[k] == 0) continue;
                for (int j = 0; j < 3; j++) center[k][j] = sums[k][j] / counts[k];
            }
        }

        int minimum = Math.max(16, (int) (candidates.size() * .08f));
        final int[] frequency = new int[count];
        for (int color : candidates) frequency[nearest(color, center)]++;
        ArrayList<Integer> used = new ArrayList<>();
        for (int k = 0; k < count; k++) if (frequency[k] >= minimum) used.add(k);
        Collections.sort(used, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Integer.compare(frequency[b], frequency[a]);
            }
        });
        if (used.isEmpty()) {
            addOutline(layers, outlines);
            return layers;
        }

        int[] assignment = new int[total];
        Arrays.fill(assignment, -1);
        for (int i = 0; i < total; i++) {
            if (!eligible(pixels[i])) continue;
            int closest = nearest(pixels[i], center);
            if (frequency[closest] >= minimum && distance(pixels[i], center[closest]) <= 16000)
                assignment[i] = closest;
        }
        float[][] snapshot = center;
        int gap = Math.max(4, Math.round(Math.min(width, height) *
                (density >= 70 ? .011f : density >= 40 ? .017f : .025f)));
        int minRun = Math.max(7, Math.round(width * .022f));
        int totalBudget = density >= 70 ? 650 : density >= 40 ? 480 : 300;
        int perLayer = Math.max(40, totalBudget / Math.max(1, used.size()));
        for (int cluster : used) {
            if (cancellation != null && cancellation.isCancelled()) return new ArrayList<>();
            ArrayList<float[]> hatches = new ArrayList<>();
            int startRow = gap / 2;
            for (int y = startRow; y < height - 1 && hatches.size() < perLayer; y += gap) {
                int runStart = -1;
                for (int x = 1; x <= width; x++) {
                    boolean valid = false;
                    if (x < width) {
                        int hits = 0;
                        for (int oy = -1; oy <= 1; oy++) {
                            int sy = Math.max(0, Math.min(height - 1, y + oy));
                            if (assignment[sy * width + x] == cluster) hits++;
                        }
                        valid = hits >= 2;
                    }
                    if (valid && runStart < 0) runStart = x;
                    if (!valid && runStart >= 0) {
                        int runEnd = x - 1;
                        if (runEnd - runStart >= minRun) {
                            float x1 = (runStart + 0.5f) / width;
                            float x2 = (runEnd + 0.5f) / width;
                            float yn = (y + 0.5f) / height;
                            hatches.add(new float[]{x1, yn, x2, yn});
                        }
                        runStart = -1;
                    }
                    if (hatches.size() >= perLayer) break;
                }
            }
            if (hatches.isEmpty()) continue;
            int color = 0xff000000 | (clamp(Math.round(snapshot[cluster][0])) << 16)
                    | (clamp(Math.round(snapshot[cluster][1])) << 8)
                    | clamp(Math.round(snapshot[cluster][2]));
            layers.add(new ColorLayer(color, describe(color), hatches));
        }
        addOutline(layers, outlines);
        return layers;
    }

    private static void addOutline(ArrayList<ColorLayer> layers, List<float[]> outlines) {
        if (outlines != null && !outlines.isEmpty())
            layers.add(new ColorLayer(0xffffffff, "White outline", outlines));
    }

    private static int clamp(int v) { return Math.min(255, Math.max(0, v)); }

    private static boolean eligible(int color) {
        if ((color >>> 24) < 160) return false;
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int brightness = (r * 30 + g * 59 + b * 11) / 100;
        return brightness > 35 && brightness < 240 && max - min > 20;
    }

    private static void rgb(int c, float[] out) {
        out[0] = (c >> 16) & 255;
        out[1] = (c >> 8) & 255;
        out[2] = c & 255;
    }

    private static double distance(int c, float[] center) {
        double r = ((c >> 16) & 255) - center[0];
        double g = ((c >> 8) & 255) - center[1];
        double b = (c & 255) - center[2];
        return r * r * .30 + g * g * .59 + b * b * .11;
    }

    private static int nearest(int c, float[][] centers) {
        int best = 0;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < centers.length; i++) {
            double d = distance(c, centers[i]);
            if (d < min) { min = d; best = i; }
        }
        return best;
    }

    public static String describe(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 26)
            return "Neutral grey";
        if (b > r * 1.22f && b > g * 1.1f) return "Blue";
        if (g > r * 1.14f && g > b * 1.06f) return "Green";
        if (r > g * 1.3f && r > b * 1.27f) return "Red";
        if (r > b * 1.25f && g > b * 1.3f)
            return g > r * .73f ? "Gold / yellow" : "Orange / brown";
        if (b > g * 1.2f && r > g * 1.12f) return "Purple";
        if (b > r && g > r) return "Cyan";
        return "Mixed color";
    }
}
