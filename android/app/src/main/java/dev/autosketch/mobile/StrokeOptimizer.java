package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Polyline post-processing: resampling, smoothing, Douglas-Peucker
 * simplification, chain merging, duplicate removal, stroke ordering and
 * gesture-sized chunking.
 *
 * <p>Polylines are flat {@code [x0,y0,x1,y1,...]} arrays. All methods return
 * new arrays and never mutate their input.</p>
 */
public final class StrokeOptimizer {

    private StrokeOptimizer() {
    }

    public static float length(float[] points) {
        float total = 0f;
        for (int i = 2; i + 1 < points.length; i += 2) {
            total += (float) Math.hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1]);
        }
        return total;
    }

    public static float[] reverse(float[] points) {
        int n = points.length / 2;
        float[] out = new float[points.length];
        for (int i = 0; i < n; i++) {
            out[i * 2] = points[(n - 1 - i) * 2];
            out[i * 2 + 1] = points[(n - 1 - i) * 2 + 1];
        }
        return out;
    }

    public static float startX(float[] points) {
        return points[0];
    }

    public static float startY(float[] points) {
        return points[1];
    }

    public static float endX(float[] points) {
        return points[points.length - 2];
    }

    public static float endY(float[] points) {
        return points[points.length - 1];
    }

    /** Arc-length resampling with the exact end point preserved. */
    public static float[] resample(float[] points, float spacing) {
        if (points.length < 4 || spacing <= 0.01f) {
            return points.clone();
        }
        List<Float> out = new ArrayList<>(points.length * 2);
        out.add(points[0]);
        out.add(points[1]);
        float nextSample = spacing;
        float walked = 0f;
        for (int i = 2; i + 1 < points.length; i += 2) {
            float x0 = points[i - 2];
            float y0 = points[i - 1];
            float x1 = points[i];
            float y1 = points[i + 1];
            float dx = x1 - x0;
            float dy = y1 - y0;
            float segment = (float) Math.hypot(dx, dy);
            if (segment < 1e-5f) {
                continue;
            }
            while (nextSample <= walked + segment) {
                float t = (nextSample - walked) / segment;
                out.add(x0 + dx * t);
                out.add(y0 + dy * t);
                nextSample += spacing;
            }
            walked += segment;
        }
        float lastX = points[points.length - 2];
        float lastY = points[points.length - 1];
        int n = out.size();
        if (out.get(n - 2) != lastX || out.get(n - 1) != lastY) {
            out.add(lastX);
            out.add(lastY);
        }
        float[] result = new float[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** Iterative Douglas-Peucker simplification. */
    public static float[] simplify(float[] points, float tolerance) {
        int count = points.length / 2;
        if (count <= 2 || tolerance <= 0f) {
            return points.clone();
        }
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[count - 1] = true;
        int[] stack = new int[count * 2];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = count - 1;
        float threshold = tolerance * tolerance;
        while (top > 0) {
            int end = stack[--top];
            int start = stack[--top];
            float ax = points[start * 2];
            float ay = points[start * 2 + 1];
            float bx = points[end * 2];
            float by = points[end * 2 + 1];
            float vx = bx - ax;
            float vy = by - ay;
            float denominator = vx * vx + vy * vy;
            int farthest = -1;
            float best = threshold;
            for (int i = start + 1; i < end; i++) {
                float px = points[i * 2] - ax;
                float py = points[i * 2 + 1] - ay;
                float t = denominator < 1e-6f ? 0f
                        : Math.max(0f, Math.min(1f, (px * vx + py * vy) / denominator));
                float dx = px - vx * t;
                float dy = py - vy * t;
                float distance = dx * dx + dy * dy;
                if (distance > best) {
                    best = distance;
                    farthest = i;
                }
            }
            if (farthest > 0) {
                keep[farthest] = true;
                stack[top++] = start;
                stack[top++] = farthest;
                stack[top++] = farthest;
                stack[top++] = end;
            }
        }
        int kept = 0;
        for (boolean value : keep) {
            if (value) {
                kept++;
            }
        }
        float[] result = new float[kept * 2];
        int j = 0;
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                result[j++] = points[i * 2];
                result[j++] = points[i * 2 + 1];
            }
        }
        return result;
    }

    /**
     * Corner smoothing that keeps both endpoints fixed. {@code strength} is
     * 0..1, {@code passes} 0..3.
     */
    public static float[] smooth(float[] points, int passes, float strength) {
        if (points.length < 6 || passes <= 0 || strength <= 0.01f) {
            return points.clone();
        }
        float[] current = points.clone();
        float weight = Math.min(0.5f, strength * 0.5f);
        int count = current.length / 2;
        for (int pass = 0; pass < passes; pass++) {
            float[] next = new float[current.length];
            next[0] = current[0];
            next[1] = current[1];
            next[(count - 1) * 2] = current[(count - 1) * 2];
            next[(count - 1) * 2 + 1] = current[(count - 1) * 2 + 1];
            for (int i = 1; i < count - 1; i++) {
                next[i * 2] = current[i * 2] * (1f - weight)
                        + (current[(i - 1) * 2] + current[(i + 1) * 2]) * 0.5f * weight;
                next[i * 2 + 1] = current[i * 2 + 1] * (1f - weight)
                        + (current[(i - 1) * 2 + 1] + current[(i + 1) * 2 + 1]) * 0.5f * weight;
            }
            current = next;
        }
        return current;
    }

    public static float[] concat(float[] first, float[] second) {
        float[] out = new float[first.length + second.length - 2];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 2, out, first.length, second.length - 2);
        return out;
    }

    /**
     * Joins chains whose endpoints touch (1-2 pixels apart after skeletonising)
     * and whose tangents line up. This removes the fragmentation that otherwise
     * produces short disconnected gestures at every junction.
     */
    public static ArrayList<float[]> mergeTouching(ArrayList<float[]> strokes,
                                                   float joinDistance, float maxTurnDegrees) {
        ArrayList<float[]> result = new ArrayList<>();
        int n = strokes.size();
        if (n == 0) {
            return result;
        }
        boolean[] used = new boolean[n];
        float joinSquared = joinDistance * joinDistance;
        float minDot = (float) Math.cos(Math.toRadians(maxTurnDegrees));
        for (int i = 0; i < n; i++) {
            if (used[i]) {
                continue;
            }
            float[] current = strokes.get(i);
            used[i] = true;
            boolean grew = true;
            while (grew) {
                grew = false;
                int bestIndex = -1;
                int bestMode = 0;
                float bestDistance = Float.MAX_VALUE;
                for (int j = 0; j < n; j++) {
                    if (used[j]) {
                        continue;
                    }
                    float[] candidate = strokes.get(j);
                    if (candidate.length < 2) {
                        continue;
                    }
                    // Append at the current end.
                    float dx = endX(current) - startX(candidate);
                    float dy = endY(current) - startY(candidate);
                    float distance = dx * dx + dy * dy;
                    if (distance <= joinSquared && distance < bestDistance
                            && continuesAtEnd(current, candidate)) {
                        bestDistance = distance;
                        bestIndex = j;
                        bestMode = 0;
                    }
                    dx = endX(current) - endX(candidate);
                    dy = endY(current) - endY(candidate);
                    distance = dx * dx + dy * dy;
                    if (distance <= joinSquared && distance < bestDistance
                            && continuesAtEnd(current, reverse(candidate))) {
                        bestDistance = distance;
                        bestIndex = j;
                        bestMode = 1;
                    }
                }
                if (bestIndex >= 0) {
                    float[] candidate = strokes.get(bestIndex);
                    current = concat(current, bestMode == 1 ? reverse(candidate) : candidate);
                    used[bestIndex] = true;
                    grew = true;
                }
            }
            if (current.length >= 4) {
                result.add(current);
            }
        }
        return result;
    }

    private static boolean continuesAtEnd(float[] current, float[] candidate) {
        if (current.length < 4 || candidate.length < 4) {
            return true;
        }
        float ax = endX(current) - current[current.length - 4];
        float ay = endY(current) - current[current.length - 3];
        float bx = candidate[2] - candidate[0];
        float by = candidate[3] - candidate[1];
        float na = (float) Math.hypot(ax, ay);
        float nb = (float) Math.hypot(bx, by);
        if (na < 1e-4f || nb < 1e-4f) {
            return true;
        }
        float dot = (ax / na) * (bx / nb) + (ay / na) * (by / nb);
        return dot >= 0.17f; // up to ~80 degrees of joint bend
    }

    /**
     * Removes strokes that cover the same path in either direction. End points
     * alone are not enough: two unrelated contours can start and end close to
     * each other (a jaw line and a hair lock, for example), so the midpoint and
     * the arc length also have to match.
     */
    public static ArrayList<float[]> removeNearDuplicates(ArrayList<float[]> strokes, float tolerance) {
        ArrayList<float[]> result = new ArrayList<>();
        float toleranceSquared = tolerance * tolerance;
        for (float[] candidate : strokes) {
            if (candidate.length < 4) {
                continue;
            }
            float candidateLength = length(candidate);
            float[] candidateMid = pointAtFraction(candidate, 0.5f);
            boolean duplicate = false;
            for (float[] kept : result) {
                if (kept.length < 4) {
                    continue;
                }
                float keptLength = length(kept);
                if (Math.abs(keptLength - candidateLength) > Math.max(tolerance * 2f,
                        Math.max(keptLength, candidateLength) * 0.3f)) {
                    continue;
                }
                float[] keptMid = pointAtFraction(kept, 0.5f);
                if (distanceSquared(keptMid[0], keptMid[1], candidateMid[0], candidateMid[1])
                        > toleranceSquared * 4f) {
                    continue;
                }
                float forward = distanceSquared(startX(kept), startY(kept), startX(candidate), startY(candidate))
                        + distanceSquared(endX(kept), endY(kept), endX(candidate), endY(candidate));
                if (forward <= toleranceSquared * 2f) {
                    duplicate = true;
                    break;
                }
                float backward = distanceSquared(startX(kept), startY(kept), endX(candidate), endY(candidate))
                        + distanceSquared(endX(kept), endY(kept), startX(candidate), startY(candidate));
                if (backward <= toleranceSquared * 2f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Point at a fraction of the polyline's arc length (0..1). */
    public static float[] pointAtFraction(float[] points, float fraction) {
        if (points.length < 4) {
            return new float[]{points.length >= 2 ? points[0] : 0f,
                    points.length >= 2 ? points[1] : 0f};
        }
        float total = length(points);
        float target = total * DrawingSettings.clamp(fraction, 0f, 1f);
        if (total <= 1e-4f) {
            return new float[]{points[0], points[1]};
        }
        float walked = 0f;
        for (int i = 2; i + 1 < points.length; i += 2) {
            float dx = points[i] - points[i - 2];
            float dy = points[i + 1] - points[i - 1];
            float segment = (float) Math.hypot(dx, dy);
            if (walked + segment >= target) {
                float t = segment <= 1e-5f ? 0f : (target - walked) / segment;
                return new float[]{points[i - 2] + dx * t, points[i - 1] + dy * t};
            }
            walked += segment;
        }
        return new float[]{endX(points), endY(points)};
    }

    private static float distanceSquared(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }

    /** Greedy nearest-neighbour ordering with direction reversal allowed. */
    public static ArrayList<float[]> orderNearestNeighbour(ArrayList<float[]> strokes) {
        int n = strokes.size();
        ArrayList<float[]> ordered = new ArrayList<>(n);
        if (n == 0) {
            return ordered;
        }
        boolean[] used = new boolean[n];
        int first = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float[] s = strokes.get(i);
            float score = startX(s) + startY(s);
            if (score < best) {
                best = score;
                first = i;
            }
        }
        used[first] = true;
        ordered.add(strokes.get(first));
        float penX = endX(strokes.get(first));
        float penY = endY(strokes.get(first));
        for (int k = 1; k < n; k++) {
            int pick = -1;
            boolean flip = false;
            float pickDistance = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                float[] s = strokes.get(i);
                float dStart = distanceSquared(penX, penY, startX(s), startY(s));
                if (dStart < pickDistance) {
                    pickDistance = dStart;
                    pick = i;
                    flip = false;
                }
                float dEnd = distanceSquared(penX, penY, endX(s), endY(s));
                if (dEnd < pickDistance) {
                    pickDistance = dEnd;
                    pick = i;
                    flip = true;
                }
            }
            if (pick < 0) {
                break;
            }
            used[pick] = true;
            float[] chosen = flip ? reverse(strokes.get(pick)) : strokes.get(pick);
            ordered.add(chosen);
            penX = endX(chosen);
            penY = endY(chosen);
        }
        return ordered;
    }

    /** Sorts longest first so structural outlines win when a cap is applied. */
    public static ArrayList<float[]> sortByLengthDescending(ArrayList<float[]> strokes) {
        ArrayList<float[]> sorted = new ArrayList<>(strokes);
        Collections.sort(sorted, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(length(b), length(a));
            }
        });
        return sorted;
    }

    /**
     * Splits a polyline into chunks of at most {@code maxPoints} points. The last
     * point of a chunk is the first point of the next chunk, so consecutive
     * gestures are geometrically continuous.
     */
    public static ArrayList<float[]> chunk(float[] points, int maxPoints) {
        ArrayList<float[]> chunks = new ArrayList<>();
        int total = points.length / 2;
        if (total < 2) {
            return chunks;
        }
        int limit = Math.max(2, maxPoints);
        int start = 0;
        while (start < total - 1) {
            int end = Math.min(total - 1, start + limit - 1);
            float[] piece = new float[(end - start + 1) * 2];
            System.arraycopy(points, start * 2, piece, 0, piece.length);
            chunks.add(piece);
            if (end == start) {
                break;
            }
            start = end;
        }
        return chunks;
    }

    /** Small helper used by the engine to build normalised output arrays. */
    public static ArrayList<float[]> toList(List<float[]> strokes) {
        return new ArrayList<>(strokes);
    }
}
