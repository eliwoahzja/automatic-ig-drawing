package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps normalised sketch polylines into a screen rectangle. The same mapper is
 * used for the on-screen preview and for gesture dispatch, so preview and
 * drawing cannot drift apart.
 */
public final class CanvasMapper {

    public static final class Rect {
        public float left;
        public float top;
        public float right;
        public float bottom;

        public Rect() {
        }

        public Rect(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public Rect(Rect other) {
            this(other.left, other.top, other.right, other.bottom);
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }

        public float centerX() {
            return (left + right) * 0.5f;
        }

        public float centerY() {
            return (top + bottom) * 0.5f;
        }

        public boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        @Override
        public String toString() {
            return "Rect(" + left + ", " + top + ", " + right + ", " + bottom + ")";
        }
    }

    private CanvasMapper() {
    }

    /** Image box inside {@code area} for the requested fit mode. */
    public static Rect destination(Rect area, int imageWidth, int imageHeight,
                                   int fitMode, boolean center) {
        float areaWidth = Math.max(1f, area.width());
        float areaHeight = Math.max(1f, area.height());
        float iw = Math.max(1f, imageWidth);
        float ih = Math.max(1f, imageHeight);
        if (fitMode == DrawingSettings.FIT_STRETCH) {
            return new Rect(area.left, area.top, area.right, area.bottom);
        }
        float scale = fitMode == DrawingSettings.FIT_FILL
                ? Math.max(areaWidth / iw, areaHeight / ih)
                : Math.min(areaWidth / iw, areaHeight / ih);
        float width = iw * scale;
        float height = ih * scale;
        float left = center ? area.left + (areaWidth - width) * 0.5f : area.left;
        float top = center ? area.top + (areaHeight - height) * 0.5f : area.top;
        return new Rect(left, top, left + width, top + height);
    }

    /**
     * Projects every normalised stroke into absolute screen coordinates,
     * resamples it, simplifies it for gestures and clips it to the drawing area.
     */
    public static ArrayList<float[]> mapStrokes(List<float[]> normalized, Rect area,
                                                int imageWidth, int imageHeight,
                                                DrawingSettings settings) {
        ArrayList<float[]> out = new ArrayList<>();
        if (normalized == null || area == null || area.width() < 2f || area.height() < 2f) {
            return out;
        }
        Rect destination = destination(area, imageWidth, imageHeight,
                settings.fitMode, settings.centerImage);
        float spacing = Math.max(1.2f, settings.pointSpacingPx);
        float tolerance = 0.3f + DrawingSettings.clamp(settings.simplification, 0, 100) / 100f * 0.55f;
        // Detail has to scale with the canvas: on a small drawing area long,
        // structural strokes read well while tiny details collapse into a blob.
        float shortSide = Math.min(area.width(), area.height());
        float adaptiveMinLength = Math.max(Math.max(1f, settings.minStrokeLengthPx),
                shortSide * 0.018f);
        if (settings.renderMode == DrawingSettings.MODE_CLEAN) {
            adaptiveMinLength *= 1.6f;
        } else if (settings.renderMode == DrawingSettings.MODE_FULL) {
            adaptiveMinLength *= 0.8f;
        }
        float minLength = adaptiveMinLength;
        for (float[] stroke : normalized) {
            if (stroke == null || stroke.length < 4) {
                continue;
            }
            float[] screen = new float[stroke.length];
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                float nx = DrawingSettings.clamp(stroke[i], 0f, 1f);
                float ny = DrawingSettings.clamp(stroke[i + 1], 0f, 1f);
                screen[i] = destination.left + nx * destination.width();
                screen[i + 1] = destination.top + ny * destination.height();
            }
            screen = StrokeOptimizer.resample(screen, spacing);
            screen = StrokeOptimizer.simplify(screen, tolerance);
            if (settings.fitMode == DrawingSettings.FIT_FILL) {
                for (float[] piece : clipToRect(screen, area)) {
                    if (StrokeOptimizer.length(piece) >= minLength) {
                        out.add(clampToRect(piece, area));
                    }
                }
            } else {
                float[] clamped = clampToRect(screen, area);
                if (StrokeOptimizer.length(clamped) >= minLength) {
                    out.add(clamped);
                }
            }
        }
        int perCanvas = Math.max(24, Math.round(shortSide / 2.6f));
        if (settings.renderMode == DrawingSettings.MODE_FULL) {
            perCanvas *= 3;
        }
        int budget = Math.min(perCanvas, settings.maxStrokesForRenderMode());
        if (out.size() > budget) {
            out = StrokeOptimizer.sortByLengthDescending(out);
            out = new ArrayList<>(out.subList(0, budget));
        }
        return StrokeOptimizer.orderNearestNeighbour(out);
    }

    public static float[] clampToRect(float[] points, Rect area) {
        float[] out = points.clone();
        for (int i = 0; i + 1 < out.length; i += 2) {
            out[i] = DrawingSettings.clamp(out[i], area.left, area.right);
            out[i + 1] = DrawingSettings.clamp(out[i + 1], area.top, area.bottom);
        }
        return out;
    }

    /** Liang-Barsky clipping that returns every visible piece of a polyline. */
    public static ArrayList<float[]> clipToRect(float[] points, Rect area) {
        ArrayList<float[]> pieces = new ArrayList<>();
        ArrayList<Float> current = new ArrayList<>();
        float[] clipped = new float[4];
        for (int i = 0; i + 3 < points.length; i += 2) {
            boolean visible = clipSegment(points[i], points[i + 1],
                    points[i + 2], points[i + 3], area, clipped);
            if (!visible) {
                flush(current, pieces);
                continue;
            }
            if (clipped[0] != points[i] || clipped[1] != points[i + 1]) {
                flush(current, pieces);
            }
            if (current.isEmpty()) {
                current.add(clipped[0]);
                current.add(clipped[1]);
            }
            current.add(clipped[2]);
            current.add(clipped[3]);
            if (clipped[2] != points[i + 2] || clipped[3] != points[i + 3]) {
                flush(current, pieces);
            }
        }
        flush(current, pieces);
        return pieces;
    }

    private static void flush(List<Float> current, ArrayList<float[]> pieces) {
        if (current.size() >= 4) {
            float[] piece = new float[current.size()];
            for (int i = 0; i < piece.length; i++) {
                piece[i] = current.get(i);
            }
            pieces.add(piece);
        }
        current.clear();
    }

    private static boolean clipSegment(float x0, float y0, float x1, float y1,
                                       Rect area, float[] out) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float t0 = 0f;
        float t1 = 1f;
        float[] p = {-dx, dx, -dy, dy};
        float[] q = {x0 - area.left, area.right - x0, y0 - area.top, area.bottom - y0};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-7f) {
                if (q[i] < 0f) {
                    return false;
                }
            } else {
                float t = q[i] / p[i];
                if (p[i] < 0f) {
                    if (t > t1) {
                        return false;
                    }
                    if (t > t0) {
                        t0 = t;
                    }
                } else {
                    if (t < t0) {
                        return false;
                    }
                    if (t < t1) {
                        t1 = t;
                    }
                }
            }
        }
        out[0] = x0 + t0 * dx;
        out[1] = y0 + t0 * dy;
        out[2] = x0 + t1 * dx;
        out[3] = y0 + t1 * dy;
        return true;
    }
}
