package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

public final class SketchEngine {
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    public static final class Result {
        public final Bitmap preview;
        public final ArrayList<float[]> strokes;
        public final int width;
        public final int height;

        Result(Bitmap preview, ArrayList<float[]> strokes, int width, int height) {
            this.preview = preview;
            this.strokes = strokes;
            this.width = width;
            this.height = height;
        }
    }

    private static int clip(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Result generate(Bitmap source, int detailLevel) {
        int detail = clip(detailLevel, 0, 3);
        int originalWidth = source.getWidth();
        int originalHeight = source.getHeight();
        float ratio = Math.min(1f, 480f / Math.max(originalWidth, originalHeight));
        int width = Math.max(8, Math.round(originalWidth * ratio));
        int height = Math.max(8, Math.round(originalHeight * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        int size = width * height;
        int[] pixels = new int[size];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] gray = new int[size];
        for (int i = 0; i < size; i++) {
            int c = pixels[i];
            gray[i] = (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) >> 8;
        }

        int[] temp = new int[size];
        int[] blurred = new int[size];
        for (int y = 0; y < height; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                temp[idx] = (gray[idx - 1] + 2 * gray[idx] + gray[idx + 1] + 2) / 4;
            }
            temp[y * width] = gray[y * width];
            temp[y * width + width - 1] = gray[y * width + width - 1];
        }
        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                blurred[idx] = (temp[idx - width] + 2 * temp[idx] + temp[idx + width] + 2) / 4;
            }
        }
        System.arraycopy(temp, 0, blurred, 0, width);
        System.arraycopy(temp, (height - 1) * width, blurred, (height - 1) * width, width);

        int[] magnitude = new int[size];
        byte[] direction = new byte[size];
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                int gx = -blurred[i - width - 1] + blurred[i - width + 1]
                        - 2 * blurred[i - 1] + 2 * blurred[i + 1]
                        - blurred[i + width - 1] + blurred[i + width + 1];
                int gy = -blurred[i - width - 1] - 2 * blurred[i - width]
                        - blurred[i - width + 1]
                        + blurred[i + width - 1] + 2 * blurred[i + width]
                        + blurred[i + width + 1];
                magnitude[i] = (int) Math.hypot(gx, gy);
                double angle = Math.toDegrees(Math.atan2(gy, gx));
                if (angle < 0) angle += 180;
                direction[i] = (byte) (angle < 22.5 || angle >= 157.5 ? 0
                        : angle < 67.5 ? 1 : angle < 112.5 ? 2 : 3);
            }
        }

        int[] nms = new int[size];
        int[] histogram = new int[2048];
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                int strength = magnitude[i];
                if (strength < 12) continue;
                int a, b;
                switch (direction[i]) {
                    case 0:
                        a = i - 1;
                        b = i + 1;
                        break;
                    case 1:
                        a = i - width - 1;
                        b = i + width + 1;
                        break;
                    case 2:
                        a = i - width;
                        b = i + width;
                        break;
                    default:
                        a = i - width + 1;
                        b = i + width - 1;
                }
                if (strength >= magnitude[a] && strength > magnitude[b]) {
                    nms[i] = strength;
                    histogram[Math.min(strength, histogram.length - 1)]++;
                    count++;
                }
            }
        }

        float percentile = detail == 0 ? .88f : detail == 1 ? .82f
                : detail == 2 ? .75f : .68f;
        int rank = Math.max(1, Math.round(count * percentile));
        int cumulative = 0;
        int high = 110;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= rank) {
                high = i;
                break;
            }
        }
        high = clip(high, 65, 350);
        int low = Math.round(high * (detail >= 2 ? .42f : .49f));

        byte[] edges = new byte[size];
        int[] queue = new int[size];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < size; i++) {
            if (nms[i] >= high) {
                edges[i] = 1;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % width;
            int y = i / width;
            for (int d = 0; d < 8; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                if (nx <= 0 || ny <= 0 || nx >= width - 1 || ny >= height - 1) continue;
                int j = ny * width + nx;
                if (edges[j] == 0 && nms[j] >= low) {
                    edges[j] = 1;
                    queue[tail++] = j;
                }
            }
        }

        int[] degrees = new int[size];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                if (edges[i] == 0) continue;
                int deg = 0;
                for (int d = 0; d < 8; d++)
                    if (neighbor(i, d, width, height, edges) >= 0) deg++;
                degrees[i] = deg;
            }
        }

        ArrayList<int[]> raw = new ArrayList<>();
        int[] visited = new int[size];
        int minLength = detail == 0 ? 18 : detail == 1 ? 13 : detail == 2 ? 10 : 8;
        for (int i = 0; i < size; i++) {
            if (edges[i] == 0 || degrees[i] == 2) continue;
            for (int d = 0; d < 8; d++) {
                if ((visited[i] & (1 << d)) != 0) continue;
                if (neighbor(i, d, width, height, edges) >= 0) {
                    int[] segment = trace(i, d, width, height, edges, degrees, visited);
                    if (length(segment, width) >= minLength) raw.add(segment);
                }
            }
        }
        for (int i = 0; i < size; i++) {
            if (edges[i] == 0 || degrees[i] != 2) continue;
            for (int d = 0; d < 8; d++) {
                if ((visited[i] & (1 << d)) != 0) continue;
                if (neighbor(i, d, width, height, edges) >= 0) {
                    int[] segment = trace(i, d, width, height, edges, degrees, visited);
                    if (length(segment, width) >= minLength) raw.add(segment);
                }
            }
        }

        Collections.sort(raw, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Integer.compare(b.length, a.length);
            }
        });
        int limit = detail == 0 ? 600 : detail == 1 ? 950 : detail == 2 ? 1500 : 2300;
        ArrayList<float[]> strokes = new ArrayList<>();
        Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        preview.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(preview);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(1.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);

        for (int k = 0; k < raw.size() && strokes.size() < limit; k++) {
            int[] path = simplify(raw.get(k), width, detail == 0 ? 1.5f : 1.0f);
            if (path.length < 2) continue;
            Path shape = new Path();
            shape.moveTo(path[0] % width, path[0] / width);
            for (int j = 1; j < path.length; j++)
                shape.lineTo(path[j] % width, path[j] / width);
            canvas.drawPath(shape, paint);

            int begin = 0;
            while (begin < path.length - 1 && strokes.size() < limit) {
                int end = Math.min(begin + 110, path.length - 1);
                if (end <= begin) break;
                float[] points = new float[(end - begin + 1) * 2];
                int p = 0;
                for (int j = begin; j <= end; j++) {
                    points[p++] = (path[j] % width + .5f) / width;
                    points[p++] = (path[j] / width + .5f) / height;
                }
                strokes.add(points);
                begin = end;
            }
        }
        return new Result(preview, strokes, width, height);
    }

    private static int neighbor(int i, int d, int w, int h, byte[] mask) {
        int x = i % w;
        int y = i / w;
        int nx = x + DX[d];
        int ny = y + DY[d];
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) return -1;
        int j = ny * w + nx;
        if (mask[j] == 0) return -1;
        if ((d & 1) != 0) {
            if (mask[y * w + nx] != 0 || mask[ny * w + x] != 0) return -1;
        }
        return j;
    }

    private static int[] trace(int start, int dir, int w, int h,
                               byte[] mask, int[] degrees, int[] visited) {
        int[] list = new int[96];
        int size = 1;
        list[0] = start;
        int current = start;
        int direction = dir;
        int max = Math.min(w * h, 10000);
        while (size < max) {
            int next = neighbor(current, direction, w, h, mask);
            if (next < 0 || (visited[current] & (1 << direction)) != 0) break;
            visited[current] |= 1 << direction;
            visited[next] |= 1 << ((direction + 4) & 7);
            if (size >= list.length) list = Arrays.copyOf(list, list.length * 2);
            list[size++] = next;
            if (degrees[next] != 2) break;
            int nextDirection = -1;
            for (int d = 0; d < 8; d++) {
                if ((visited[next] & (1 << d)) == 0
                        && neighbor(next, d, w, h, mask) >= 0) {
                    nextDirection = d;
                    break;
                }
            }
            if (nextDirection < 0) break;
            current = next;
            direction = nextDirection;
        }
        return Arrays.copyOf(list, size);
    }

    private static double length(int[] path, int width) {
        double total = 0;
        for (int i = 1; i < path.length; i++) {
            int dx = path[i] % width - path[i - 1] % width;
            int dy = path[i] / width - path[i - 1] / width;
            total += Math.hypot(dx, dy);
        }
        return total;
    }

    private static int[] simplify(int[] path, int width, float tolerance) {
        if (path.length <= 3) return path;
        boolean[] keep = new boolean[path.length];
        keep[0] = keep[path.length - 1] = true;
        int[] stack = new int[path.length * 2];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = path.length - 1;
        double threshold = tolerance * tolerance;
        while (sp > 0) {
            int end = stack[--sp];
            int start = stack[--sp];
            double ax = path[start] % width;
            double ay = path[start] / width;
            double bx = path[end] % width;
            double by = path[end] / width;
            double vx = bx - ax;
            double vy = by - ay;
            double denom = vx * vx + vy * vy;
            int farthest = -1;
            double maxDist = threshold;
            for (int i = start + 1; i < end; i++) {
                double px = path[i] % width - ax;
                double py = path[i] / width - ay;
                double t = denom < 1e-6 ? 0 : Math.max(0, Math.min(1,
                        (px * vx + py * vy) / denom));
                double dx = px - vx * t;
                double dy = py - vy * t;
                double d = dx * dx + dy * dy;
                if (d > maxDist) {
                    maxDist = d;
                    farthest = i;
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true;
                stack[sp++] = start;
                stack[sp++] = farthest;
                stack[sp++] = farthest;
                stack[sp++] = end;
            }
        }
        int total = 0;
        for (boolean mark : keep) if (mark) total++;
        int[] result = new int[total];
        int j = 0;
        for (int i = 0; i < path.length; i++)
            if (keep[i]) result[j++] = path[i];
        return result;
    }
}
