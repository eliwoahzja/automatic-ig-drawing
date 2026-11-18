package dev.autosketch.mobile;

import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.ArrayList;

public final class LetteringComposer {
    private LetteringComposer() {}

    public static Sketch apply(Sketch original, DrawingSettings settings) {
        if (original == null || settings == null || settings.captionText == null ||
                settings.captionText.trim().isEmpty()) return original;
        String[] lines = settings.captionText.trim().split("\\n", 4);
        if (lines.length == 0) return original;
        int width = original.width, height = original.height;
        float fontSize = Math.max(9f, Math.min(width, height) * settings.captionSizePercent / 100f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-rounded", Typeface.BOLD));
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(fontSize);
        float maxLineWidth = 0;
        for (String line : lines)
            maxLineWidth = Math.max(maxLineWidth, paint.measureText(line));
        float maxAllowed = width * .88f;
        if (maxLineWidth > maxAllowed) {
            fontSize *= maxAllowed / maxLineWidth;
            paint.setTextSize(fontSize);
        }
        float lineSpacing = fontSize * 1.28f;
        float anchorX = width * settings.captionXPercent / 100f;
        float anchorY = height * settings.captionYPercent / 100f;
        float firstBaseline = anchorY - (lines.length - 1) * lineSpacing / 2f +
                fontSize * .35f;

        ArrayList<float[]> textPaths = new ArrayList<>();
        RectF bounds = new RectF();
        bounds.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.trim().isEmpty()) continue;
            float x = anchorX - paint.measureText(line) / 2f;
            Path linePath = new Path();
            paint.getTextPath(line, 0, line.length(), x,
                    firstBaseline + n * lineSpacing, linePath);
            RectF rect = new RectF();
            linePath.computeBounds(rect, true);
            bounds.left = Math.min(bounds.left, rect.left);
            bounds.right = Math.max(bounds.right, rect.right);
            bounds.top = Math.min(bounds.top, rect.top);
            bounds.bottom = Math.max(bounds.bottom, rect.bottom);
            PathMeasure measure = new PathMeasure(linePath, false);
            do {
                float length = measure.getLength();
                if (length < 3f) continue;
                int segments = Math.max(2, Math.min(150, (int) Math.ceil(length / 1.8f)));
                float[] values = new float[(segments + 1) * 2];
                float[] pos = new float[2];
                for (int i = 0; i <= segments; i++) {
                    measure.getPosTan(length * i / segments, pos, null);
                    values[2 * i] = DrawingSettings.clamp(pos[0] / width, 0f, 1f);
                    values[2 * i + 1] = DrawingSettings.clamp(pos[1] / height, 0f, 1f);
                }
                textPaths.add(values);
            } while (measure.nextContour());
        }
        if (textPaths.isEmpty()) return original;

        float pad = fontSize * .17f;
        bounds.inset(-pad, -pad);
        ArrayList<float[]> combined = new ArrayList<>();
        for (float[] stroke : original.strokes) {
            if (!settings.captionReplaceOriginal) {
                combined.add(stroke);
                continue;
            }
            splitOutside(stroke, bounds, width, height, combined);
        }
        combined.addAll(textPaths);

        ArrayList<ColorLayer> layers = new ArrayList<>();
        for (ColorLayer layer : original.colorLayers) {
            if ("White outline".equals(layer.name)) {
                layers.add(new ColorLayer(layer.color, layer.name, combined));
            } else {
                layers.add(layer);
            }
        }
        return new Sketch(width, height, combined, original.edgePixels, layers);
    }

    private static void splitOutside(float[] stroke, RectF excluded, int width, int height,
                                     ArrayList<float[]> out) {
        ArrayList<Float> piece = new ArrayList<>();
        for (int i = 0; i + 1 < stroke.length; i += 2) {
            float x = stroke[i] * width, y = stroke[i + 1] * height;
            if (excluded.contains(x, y)) {
                flush(piece, out);
            } else {
                piece.add(stroke[i]);
                piece.add(stroke[i + 1]);
            }
        }
        flush(piece, out);
    }

    private static void flush(ArrayList<Float> values, ArrayList<float[]> out) {
        if (values.size() >= 4) {
            float[] line = new float[values.size()];
            for (int i = 0; i < values.size(); i++) line[i] = values.get(i);
            out.add(line);
        }
        values.clear();
    }
}
