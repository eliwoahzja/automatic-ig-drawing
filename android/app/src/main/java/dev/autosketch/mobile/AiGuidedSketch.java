package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.List;

public final class AiGuidedSketch {
    private AiGuidedSketch() {}

    public static Sketch enhance(Bitmap image, Sketch original, AiScene scene,
                                 DrawingSettings settings, SketchSession.Token token) {
        if (original == null || image == null || scene == null || original.isEmpty())
            return original == null ? Sketch.empty() : original;
        ArrayList<float[]> combined = new ArrayList<>(original.strokes);
        int imageWidth = image.getWidth(), imageHeight = image.getHeight();
        int totalAdded = 0;

        DrawingSettings detail = settings.copy();
        detail.renderMode = DrawingSettings.MODE_SKETCH;
        detail.detailLevel = DrawingSettings.DETAIL_ULTRA;
        detail.noiseReduction = Math.min(45, detail.noiseReduction);
        detail.edgeSensitivity = Math.max(58, detail.edgeSensitivity);
        detail.minStrokeLengthPx = 3;
        detail.strokeOverlapReduction = 20;
        detail.captionText = "";

        for (AiScene.Region region : scene.objects) {
            if (token != null && token.isCancelled()) return original;
            if (totalAdded >= 110) break;
            float pad = .055f;
            int left = Math.max(0, Math.round((region.x - pad) * imageWidth));
            int top = Math.max(0, Math.round((region.y - pad) * imageHeight));
            int right = Math.min(imageWidth, Math.round(
                    (region.x + region.width + pad) * imageWidth));
            int bottom = Math.min(imageHeight, Math.round(
                    (region.y + region.height + pad) * imageHeight));
            if (right - left < 24 || bottom - top < 24) continue;
            Bitmap crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top);
            try {
                SketchProcessor.Outcome outcome = SketchProcessor.process(crop, detail,
                        imageWidth, token);
                for (float[] line : outcome.sketch.strokes) {
                    if (token != null && token.isCancelled()) return original;
                    if (totalAdded >= 110) break;
                    float[] mapped = new float[line.length];
                    for (int i = 0; i < line.length - 1; i += 2) {
                        mapped[i] = (left + line[i] * crop.getWidth()) / imageWidth;
                        mapped[i + 1] = (top + line[i + 1] * crop.getHeight()) / imageHeight;
                    }
                    if (newCoverage(mapped, combined, original.width, original.height)) {
                        combined.add(mapped);
                        totalAdded++;
                    }
                }
            } finally {
                if (!crop.isRecycled()) crop.recycle();
            }
        }

        for (AiScene.Region line : scene.lettering) {
            if (token != null && token.isCancelled()) return original;
            if (line.text.length() < 2 || line.height < .012f) continue;
            ArrayList<float[]> paths = drawText(line, original.width, original.height);
            if (paths.isEmpty()) continue;
            ArrayList<float[]> keep = new ArrayList<>();
            RectF box = new RectF(line.x - .007f, line.y - .007f,
                    line.x + line.width + .007f, line.y + line.height + .007f);
            for (float[] stroke : combined) {
                ArrayList<Float> piece = new ArrayList<>();
                for (int i = 0; i < stroke.length - 1; i += 2) {
                    if (box.contains(stroke[i], stroke[i + 1])) {
                        flush(piece, keep);
                    } else {
                        piece.add(stroke[i]);
                        piece.add(stroke[i + 1]);
                    }
                }
                flush(piece, keep);
            }
            combined = keep;
            combined.addAll(paths);
        }

        ArrayList<ColorLayer> layers = new ArrayList<>();
        for (ColorLayer layer : original.colorLayers) {
            if ("White outline".equals(layer.name)) {
                layers.add(new ColorLayer(layer.color, layer.name, combined));
            } else {
                layers.add(layer);
            }
        }
        return new Sketch(original.width, original.height, combined, original.edgePixels, layers);
    }

    private static void flush(ArrayList<Float> points, ArrayList<float[]> lines) {
        if (points.size() >= 4) {
            float[] stroke = new float[points.size()];
            for (int i = 0; i < points.size(); i++) stroke[i] = points.get(i);
            lines.add(stroke);
        }
        points.clear();
    }

    private static boolean newCoverage(float[] proposed, List<float[]> existing,
                                       int width, int height) {
        if (proposed.length < 6 || existing.size() > 950) return false;
        float distance = 0f;
        for (int i = 2; i < proposed.length - 1; i += 2) {
            distance += Math.hypot((proposed[i] - proposed[i-2]) * width,
                    (proposed[i+1] - proposed[i-1]) * height);
        }
        if (distance < Math.min(width, height) * .018f) return false;
        int steps = 0, novel = 0;
        for (int i = 0; i < proposed.length - 1; i += Math.max(2, proposed.length / 14 / 2 * 2)) {
            float px = proposed[i], py = proposed[i+1];
            boolean near = false;
            for (float[] stroke : existing) {
                for (int k = 0; k < stroke.length - 1; k += Math.max(2, stroke.length / 28 / 2 * 2)) {
                    float dx = (px - stroke[k]) * width, dy = (py - stroke[k + 1]) * height;
                    if (dx * dx + dy * dy < 16f) { near = true; break; }
                }
                if (near) break;
            }
            steps++;
            if (!near) novel++;
        }
        return steps > 0 && novel >= Math.ceil(steps * .52);
    }

    private static ArrayList<float[]> drawText(AiScene.Region line, int width, int height) {
        ArrayList<float[]> strokes = new ArrayList<>();
        if (line.text == null || line.text.trim().isEmpty()) return strokes;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setStyle(Paint.Style.FILL);
        float desiredHeight = line.height * height * .94f;
        float size = Math.max(6f, desiredHeight);
        paint.setTextSize(size);
        float textWidth = paint.measureText(line.text);
        if (textWidth <= 0) return strokes;
        size *= Math.min(1f, line.width * width / textWidth);
        paint.setTextSize(Math.max(5f, size));
        float left = line.x * width;
        float baseline = (line.y + line.height) * height - line.height * height * .10f;
        Path path = new Path();
        paint.getTextPath(line.text, 0, line.text.length(), left, baseline, path);
        PathMeasure measure = new PathMeasure(path, false);
        do {
            float length = measure.getLength();
            if (length < 3f) continue;
            int n = Math.max(2, Math.min(125, (int) Math.ceil(length / 1.7f)));
            float[] output = new float[(n + 1) * 2];
            float[] xy = new float[2];
            for (int i = 0; i <= n; i++) {
                measure.getPosTan(i * length / n, xy, null);
                output[2*i] = DrawingSettings.clamp(xy[0] / width, 0f, 1f);
                output[2*i+1] = DrawingSettings.clamp(xy[1] / height, 0f, 1f);
            }
            strokes.add(output);
        } while (measure.nextContour());
        return strokes;
    }
}
