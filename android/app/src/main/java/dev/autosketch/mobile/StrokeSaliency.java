package dev.autosketch.mobile;

/**
 * Decides whether a traced polyline is a real contour or just texture.
 *
 * <p>A Canny edge only proves that two neighbouring pixels differ. Glitter,
 * watermarks, film grain and soft photo texture all produce those differences,
 * which is why a naive pipeline covers the canvas in crackle. Real contours are
 * either a <em>step</em> between two differently coloured regions, or a thin
 * <em>ridge</em> (an ink line) that is markedly darker or brighter than its
 * surroundings. This class measures both and keeps the larger value.</p>
 *
 * <p>Sampling is done on the original pixels, never on the locally contrasted
 * greyscale copy, so boosting faint lines cannot re-introduce noise here.</p>
 */
public final class StrokeSaliency {

    private StrokeSaliency() {
    }

    /**
     * Mean colour contrast of the stroke, 0..255 per channel.
     *
     * <p>Below ~6 the stroke runs through one flat area (texture, grain, a soft
     * gradient); real outlines and ink lines score far higher.</p>
     */
    public static float stepContrast(float[] points, int[] argb, int width, int height) {
        if (points == null || points.length < 4 || argb == null
                || width < 4 || height < 4 || argb.length < width * height) {
            return 0f;
        }
        float[] dense = StrokeOptimizer.resample(points, 2.5f);
        double stepSum = 0;
        double ridgeSum = 0;
        int samples = 0;
        for (int i = 2; i + 1 < dense.length; i += 2) {
            float dx = dense[i] - dense[i - 2];
            float dy = dense[i + 1] - dense[i - 1];
            float length = (float) Math.hypot(dx, dy);
            if (length < 1e-3f) {
                continue;
            }
            float normalX = -dy / length;
            float normalY = dx / length;
            float x = dense[i - 2];
            float y = dense[i - 1];
            int centreX = Math.round(x);
            int centreY = Math.round(y);
            if (centreX < 1 || centreY < 1 || centreX >= width - 1 || centreY >= height - 1) {
                continue;
            }
            int centre = averageColour(argb, width, height, centreX, centreY);
            for (int distance = 3; distance <= 7; distance += 2) {
                int leftX = Math.round(x + normalX * distance);
                int leftY = Math.round(y + normalY * distance);
                int rightX = Math.round(x - normalX * distance);
                int rightY = Math.round(y - normalY * distance);
                if (leftX < 1 || leftY < 1 || rightX < 1 || rightY < 1
                        || leftX >= width - 1 || rightX >= width - 1
                        || leftY >= height - 1 || rightY >= height - 1) {
                    continue;
                }
                int left = averageColour(argb, width, height, leftX, leftY);
                int right = averageColour(argb, width, height, rightX, rightY);
                stepSum += colourDistance(left, right);
                ridgeSum += Math.min(colourDistance(centre, left), colourDistance(centre, right));
                samples++;
            }
        }
        if (samples == 0) {
            return 0f;
        }
        return (float) (Math.max(stepSum, ridgeSum) / samples);
    }

    /** 3x3 average: single pixels are dominated by grain and JPEG artefacts. */
    private static int averageColour(int[] argb, int width, int height, int x, int y) {
        int red = 0;
        int green = 0;
        int blue = 0;
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int py = y + dy;
            if (py < 0 || py >= height) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                int px = x + dx;
                if (px < 0 || px >= width) {
                    continue;
                }
                int pixel = argb[py * width + px];
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
                count++;
            }
        }
        count = Math.max(1, count);
        return ((red / count) << 16) | ((green / count) << 8) | (blue / count);
    }

    /** Mean per-channel RGB difference, 0..255. */
    private static float colourDistance(int first, int second) {
        return (Math.abs(((first >> 16) & 0xFF) - ((second >> 16) & 0xFF))
                + Math.abs(((first >> 8) & 0xFF) - ((second >> 8) & 0xFF))
                + Math.abs((first & 0xFF) - (second & 0xFF))) / 3f;
    }
}
