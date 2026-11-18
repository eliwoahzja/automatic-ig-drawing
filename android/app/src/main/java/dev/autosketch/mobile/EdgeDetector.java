package dev.autosketch.mobile;

/**
 * Canny-style edge detection with a Zhang-Suen thinning pass.
 *
 * <p>All methods are pure Java and operate on flat arrays so the whole chain can
 * be unit tested without an Android device.</p>
 */
public final class EdgeDetector {

    private EdgeDetector() {
    }

    /** Luma conversion plus contrast / brightness / invert / local contrast / background flattening. */
    public static int[] grayscale(int[] argb, int width, int height, DrawingSettings settings) {
        int[] gray = new int[width * height];
        float c = DrawingSettings.clamp(settings.contrast, -100, 100) * 2.55f;
        float factor = (259f * (c + 255f)) / (255f * (259f - c));
        int brightness = Math.round(DrawingSettings.clamp(settings.brightness, -100, 100) * 1.27f);
        boolean invert = settings.invert;
        for (int i = 0; i < gray.length; i++) {
            int pixel = argb[i];
            int red = (pixel >> 16) & 0xFF;
            int green = (pixel >> 8) & 0xFF;
            int blue = pixel & 0xFF;
            int alpha = (pixel >>> 24) & 0xFF;
            if (alpha < 255) {
                // Transparent pixels arrive as black RGB; composite them over white so
                // PNG cut-outs do not read as solid ink (and do not hatch).
                int inverse = 255 - alpha;
                red = (red * alpha + 255 * inverse) / 255;
                green = (green * alpha + 255 * inverse) / 255;
                blue = (blue * alpha + 255 * inverse) / 255;
            }
            int value = (red * 77 + green * 150 + blue * 29) >> 8;
            if (invert) {
                value = 255 - value;
            }
            value = Math.round(factor * (value - 128f) + 128f) + brightness;
            if (value < 0) {
                value = 0;
            } else if (value > 255) {
                value = 255;
            }
            gray[i] = value;
        }

        float gain = settings.localContrastGain();
        if (gain > 1.01f) {
            localContrast(gray, width, height, gain);
        }

        int backgroundLevel = settings.backgroundLevel();
        for (int i = 0; i < gray.length; i++) {
            if (gray[i] >= backgroundLevel) {
                gray[i] = 255;
            }
        }
        return gray;
    }

    /**
     * Unsharp-style local normalisation using an integral image (O(n)).
     * Grey levels are compared to the local mean, so a faint eyelash on light
     * skin gets the same edge strength as a dark outline on a bright background.
     */
    static void localContrast(int[] gray, int width, int height, float gain) {
        int radius = Math.max(4, Math.min(width, height) / 20);
        int stride = width + 1;
        int[] integral = new int[stride * (height + 1)];
        for (int y = 0; y < height; y++) {
            int rowSum = 0;
            int row = y * width;
            int previous = y * stride;
            int current = (y + 1) * stride;
            for (int x = 0; x < width; x++) {
                rowSum += gray[row + x];
                integral[current + x + 1] = integral[previous + x + 1] + rowSum;
            }
        }
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height - 1, y + radius);
            int top = y0 * stride;
            int bottom = (y1 + 1) * stride;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                int sum = integral[bottom + x1 + 1] - integral[top + x1 + 1]
                        - integral[bottom + x0] + integral[top + x0];
                int count = (x1 - x0 + 1) * (y1 - y0 + 1);
                int mean = sum / Math.max(1, count);
                int value = gray[row + x];
                int enhanced = Math.round(value + gain * (value - mean));
                gray[row + x] = enhanced < 0 ? 0 : (enhanced > 255 ? 255 : enhanced);
            }
        }
    }

    /** Separable Gaussian blur with clamped borders. */
    public static int[] blur(int[] gray, int width, int height, float sigma) {
        if (sigma < 0.35f) {
            return gray.clone();
        }
        int radius = Math.max(1, (int) Math.ceil(sigma * 2.6f));
        float[] kernel = new float[radius * 2 + 1];
        float sum = 0f;
        for (int i = -radius; i <= radius; i++) {
            float value = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            kernel[i + radius] = value;
            sum += value;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }
        int[] horizontal = new int[gray.length];
        int[] output = new int[gray.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float acc = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sx = x + k;
                    if (sx < 0) {
                        sx = 0;
                    } else if (sx >= width) {
                        sx = width - 1;
                    }
                    acc += gray[row + sx] * kernel[k + radius];
                }
                horizontal[row + x] = Math.round(acc);
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float acc = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sy = y + k;
                    if (sy < 0) {
                        sy = 0;
                    } else if (sy >= height) {
                        sy = height - 1;
                    }
                    acc += horizontal[sy * width + x] * kernel[k + radius];
                }
                output[y * width + x] = Math.round(acc);
            }
        }
        return output;
    }

    /**
     * Full edge mask: Sobel gradients, sector non-maximum suppression,
     * percentile based dual thresholds, hysteresis, then connected-component
     * despeckling.
     */
    public static byte[] detect(int[] gray, int width, int height, DrawingSettings settings) {
        int size = width * height;
        int[] gx = new int[size];
        int[] gy = new int[size];
        int[] magnitude = new int[size];

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                int topLeft = gray[i - width - 1];
                int top = gray[i - width];
                int topRight = gray[i - width + 1];
                int left = gray[i - 1];
                int right = gray[i + 1];
                int bottomLeft = gray[i + width - 1];
                int bottom = gray[i + width];
                int bottomRight = gray[i + width + 1];
                int dx = (topRight + 2 * right + bottomRight) - (topLeft + 2 * left + bottomLeft);
                int dy = (bottomLeft + 2 * bottom + bottomRight) - (topLeft + 2 * top + topRight);
                gx[i] = dx;
                gy[i] = dy;
                magnitude[i] = (int) Math.sqrt((double) dx * dx + (double) dy * dy);
            }
        }

        int[] suppressed = new int[size];
        int[] histogram = new int[2048];
        int candidates = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                int value = magnitude[i];
                if (value < 8) {
                    continue;
                }
                int first;
                int second;
                switch (sector(gx[i], gy[i])) {
                    case 0:
                        first = i - 1;
                        second = i + 1;
                        break;
                    case 1:
                        first = i - width - 1;
                        second = i + width + 1;
                        break;
                    case 2:
                        first = i - width;
                        second = i + width;
                        break;
                    default:
                        first = i - width + 1;
                        second = i + width - 1;
                        break;
                }
                if (value >= magnitude[first] && value >= magnitude[second]) {
                    suppressed[i] = value;
                    histogram[Math.min(value, histogram.length - 1)]++;
                    candidates++;
                }
            }
        }

        int strong;
        if (candidates == 0) {
            return new byte[size];
        }
        int wanted = Math.max(1, Math.round(candidates * settings.strongEdgeFraction()));
        int cumulative = 0;
        int high = 220;
        for (int value = histogram.length - 1; value > 0; value--) {
            cumulative += histogram[value];
            if (cumulative >= wanted) {
                high = value;
                break;
            }
        }
        high = DrawingSettings.clamp(high, 16, 900);
        int low = Math.max(6, Math.round(high * settings.weakEdgeRatio()));

        byte[] mask = new byte[size];
        int[] stack = new int[size];
        int top = 0;
        for (int i = 0; i < size; i++) {
            if (suppressed[i] >= high) {
                mask[i] = 1;
                stack[top++] = i;
            }
        }
        while (top > 0) {
            int i = stack[--top];
            int x = i % width;
            int y = i / width;
            int x0 = Math.max(1, x - 1);
            int x1 = Math.min(width - 2, x + 1);
            int y0 = Math.max(1, y - 1);
            int y1 = Math.min(height - 2, y + 1);
            for (int ny = y0; ny <= y1; ny++) {
                for (int nx = x0; nx <= x1; nx++) {
                    int j = ny * width + nx;
                    if (mask[j] == 0 && suppressed[j] >= low) {
                        mask[j] = 1;
                        stack[top++] = j;
                    }
                }
            }
        }

        despeckle(mask, width, height, settings.minComponentPixels());
        return mask;
    }

    private static int sector(int gx, int gy) {
        int ax = Math.abs(gx);
        int ay = Math.abs(gy);
        if (ay * 2 < ax) {
            return 0;
        }
        if (ax * 2 < ay) {
            return 2;
        }
        return ((gx > 0) == (gy > 0)) ? 1 : 3;
    }

    /** Removes 8-connected components smaller than {@code minArea} pixels. */
    public static void despeckle(byte[] mask, int width, int height, int minArea) {
        if (minArea <= 1) {
            return;
        }
        int[] stack = new int[width * height];
        int[] component = new int[Math.max(16, minArea * 4)];
        int size = width * height;
        for (int start = 0; start < size; start++) {
            if (mask[start] != 1) {
                continue;
            }
            int top = 0;
            int count = 0;
            stack[top++] = start;
            mask[start] = 2;
            while (top > 0) {
                int i = stack[--top];
                if (count == component.length) {
                    int[] bigger = new int[component.length * 2];
                    System.arraycopy(component, 0, bigger, 0, component.length);
                    component = bigger;
                }
                component[count++] = i;
                int x = i % width;
                int y = i / width;
                int x0 = Math.max(0, x - 1);
                int x1 = Math.min(width - 1, x + 1);
                int y0 = Math.max(0, y - 1);
                int y1 = Math.min(height - 1, y + 1);
                for (int ny = y0; ny <= y1; ny++) {
                    for (int nx = x0; nx <= x1; nx++) {
                        int j = ny * width + nx;
                        if (mask[j] == 1) {
                            mask[j] = 2;
                            stack[top++] = j;
                        }
                    }
                }
            }
            byte value = count < minArea ? (byte) 0 : (byte) 1;
            for (int k = 0; k < count; k++) {
                mask[component[k]] = value;
            }
        }
    }

    /**
     * Zhang-Suen skeletonisation. Canny bands are 1-2 pixels wide; the skeleton
     * turns them into clean single-pixel chains that can be traced into ordered
     * contours.
     */
    public static byte[] thin(byte[] mask, int width, int height) {
        byte[] image = mask.clone();
        byte[] scratch = new byte[image.length];
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 24) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                System.arraycopy(image, 0, scratch, 0, image.length);
                for (int y = 1; y < height - 1; y++) {
                    int row = y * width;
                    for (int x = 1; x < width - 1; x++) {
                        int i = row + x;
                        if (image[i] == 0) {
                            continue;
                        }
                        int p2 = image[i - width];
                        int p3 = image[i - width + 1];
                        int p4 = image[i + 1];
                        int p5 = image[i + width + 1];
                        int p6 = image[i + width];
                        int p7 = image[i + width - 1];
                        int p8 = image[i - 1];
                        int p9 = image[i - width - 1];
                        int neighbours = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9;
                        if (neighbours < 2 || neighbours > 6) {
                            continue;
                        }
                        int transitions = 0;
                        if (p2 == 0 && p3 == 1) transitions++;
                        if (p3 == 0 && p4 == 1) transitions++;
                        if (p4 == 0 && p5 == 1) transitions++;
                        if (p5 == 0 && p6 == 1) transitions++;
                        if (p6 == 0 && p7 == 1) transitions++;
                        if (p7 == 0 && p8 == 1) transitions++;
                        if (p8 == 0 && p9 == 1) transitions++;
                        if (p9 == 0 && p2 == 1) transitions++;
                        if (transitions != 1) {
                            continue;
                        }
                        boolean keep;
                        if (step == 0) {
                            keep = (p2 * p4 * p6) == 0 && (p4 * p6 * p8) == 0;
                        } else {
                            keep = (p2 * p4 * p8) == 0 && (p2 * p6 * p8) == 0;
                        }
                        if (keep) {
                            scratch[i] = 0;
                            changed = true;
                        }
                    }
                }
                System.arraycopy(scratch, 0, image, 0, image.length);
            }
        }
        return image;
    }
}
