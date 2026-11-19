package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini powered drawing helpers.
 *
 * <p><b>Refine</b> keeps the accurate edge trace and lets Gemini curate it: it removes noise,
 * reads the lettering and adds missing features. <b>Draw</b> lets Gemini plan the whole line
 * art, then snaps it onto the traced edges so the lines sit exactly on the image.</p>
 */
public final class GeminiArtist {

    public interface Callback {
        /** Called on a background thread. Exactly one of result or error is non-null. */
        void done(Result result, String error);
    }

    public static final class Result {
        public final Sketch sketch;
        public final String summary;
        public final int removed;
        public final int added;
        public final int textLines;

        Result(Sketch sketch, String summary, int removed, int added, int textLines) {
            this.sketch = sketch;
            this.summary = summary;
            this.removed = removed;
            this.added = added;
            this.textLines = textLines;
        }
    }

    private static final String COORDS =
            "All coordinates are integers on a 0-1000 grid over the whole image, written as " +
            "[y, x] with y downward from the top and x rightward from the left. ";

    private GeminiArtist() {}

    private static String[] modelOrder(DrawingSettings settings) {
        String[] ids = DrawingSettings.GEMINI_MODEL_IDS;
        int first = Math.max(0, Math.min(ids.length - 1, settings.geminiModel));
        String[] ordered = new String[ids.length];
        ordered[0] = ids[first];
        int n = 1;
        for (int i = 0; i < ids.length; i++) if (i != first) ordered[n++] = ids[i];
        return ordered;
    }

    // ---------------------------------------------------------------- refine

    public static void refine(final Bitmap image, final Sketch base, final DrawingSettings settings,
                              final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (base == null || base.isEmpty()) throw new Exception("Generate the base sketch first");
                    Bitmap guide = renderGuide(image, base);
                    JSONArray parts = new JSONArray();
                    try {
                        parts.put(GeminiClient.textPart(refinePrompt(settings, base.strokes.size())));
                        parts.put(GeminiClient.imagePart(image, 1024));
                        parts.put(GeminiClient.imagePart(guide, 1024));
                    } finally {
                        guide.recycle();
                    }
                    GeminiPlan plan = GeminiPlan.parse(
                            GeminiClient.generateJson(settings.geminiApiKey, modelOrder(settings), parts,
                                    GeminiPlan.schema(true), 7000, "low"),
                            base.strokes.size(), 30, 40);

                    ArrayList<float[]> kept = new ArrayList<>();
                    for (int i = 0; i < base.strokes.size(); i++)
                        if (!plan.remove.contains(i)) kept.add(base.strokes.get(i));
                    for (float[] extra : plan.strokes)
                        kept.add(StrokeTools.smooth(extra, 3));

                    Sketch combined = rebuild(base, kept);
                    Sketch finished = withLettering(image, combined, plan.lettering, settings);
                    callback.done(new Result(finished, plan.summary, plan.remove.size(),
                            plan.strokes.size(), plan.lettering.size()), null);
                } catch (Exception e) {
                    callback.done(null, message(e));
                }
            }
        }, "gemini-refine").start();
    }

    private static String refinePrompt(DrawingSettings settings, int strokeCount) {
        return "You are the art director for a pen plotter that redraws images as clean line art. " +
                "Image 1 is the original. Image 2 is an automatic edge trace of it: every traced " +
                "line is numbered with an id from 0 to " + (strokeCount - 1) + " (very short lines " +
                "have no number). " + COORDS +
                "Return JSON with: " +
                "remove: ids of traced lines that are NOT real outlines of the subject, meaning " +
                "texture, grain, glitter, shadow edges, duplicate or doubled outlines, scribbles, " +
                "and every line that sits on lettering (the app redraws lettering itself). Keep " +
                "silhouettes, faces, clothing seams and meaningful internal shapes. Be careful: " +
                "when unsure, keep the line. " +
                "texts: every line of readable lettering, transcribed exactly as written, each with " +
                "a tight box [ymin, xmin, ymax, xmax]. Never invent words. Empty list if none. " +
                "strokes: up to 25 short extra strokes, each at most 24 points, only for important " +
                "features the trace missed (for example an eye highlight or the mouth line). " +
                "Empty list if the trace is complete. " +
                "summary: a very short description of the subject. " + focus(settings);
    }

    // ------------------------------------------------------------------ draw

    public static void draw(final Bitmap image, final Sketch base, final DrawingSettings settings,
                            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int style = Math.max(0, Math.min(2, settings.aiStyle));
                    int maxStrokes = style == 1 ? 110 : (style == 2 ? 35 : 70);
                    JSONArray parts = new JSONArray();
                    parts.put(GeminiClient.textPart(drawPrompt(settings, style, maxStrokes)));
                    parts.put(GeminiClient.imagePart(image, 1024));
                    GeminiPlan plan = GeminiPlan.parse(
                            GeminiClient.generateJson(settings.geminiApiKey, modelOrder(settings), parts,
                                    GeminiPlan.schema(false), style == 1 ? 26000 : 18000, "low"),
                            0, maxStrokes, 28);
                    if (plan.strokes.size() < 2) throw new Exception("Gemini returned too few strokes");

                    ArrayList<float[]> shaped = new ArrayList<>();
                    for (float[] stroke : plan.strokes) shaped.add(StrokeTools.smooth(stroke, 3));
                    ArrayList<float[]> snapped = StrokeTools.snap(shaped, base, .022f);

                    int w = base != null && !base.isEmpty() ? base.width : image.getWidth();
                    int h = base != null && !base.isEmpty() ? base.height : image.getHeight();
                    Sketch drawn = new Sketch(w, h, snapped, base == null ? 0 : base.edgePixels);
                    Sketch finished = withLettering(image, drawn, plan.lettering, settings);
                    callback.done(new Result(finished, plan.summary, 0, plan.strokes.size(),
                            plan.lettering.size()), null);
                } catch (Exception e) {
                    callback.done(null, message(e));
                }
            }
        }, "gemini-draw").start();
    }

    private static String drawPrompt(DrawingSettings settings, int style, int maxStrokes) {
        String look;
        if (style == 1) {
            look = "Detailed: outline plus the secondary shapes that give the subject character, such " +
                    "as fabric folds, patterns, highlights in the eyes and inner contours. ";
        } else if (style == 2) {
            look = "Minimal: the fewest bold, confident strokes that still make the subject " +
                    "instantly recognisable, like a clean one-line doodle. ";
        } else {
            look = "Outline: clean continuous contour line art with the silhouette and the main " +
                    "internal shapes. ";
        }
        return "You are an expert line artist planning how a pen plotter will redraw this image as " +
                "clean black line art on a white page. Style: " + look + COORDS +
                "Return JSON with: " +
                "strokes: at most " + maxStrokes + " strokes. Each stroke is an ordered list of " +
                "[y, x] points, at most 24 points, placed on the real edges of the image so the " +
                "lines follow the picture exactly. Prefer long smooth strokes over many tiny ones. " +
                "Close shapes by repeating the first point. Draw big shapes first, details last. " +
                "No hatching, no fills, no dots, no frame, no background. " +
                "texts: every line of readable lettering, transcribed exactly, each with a tight " +
                "box [ymin, xmin, ymax, xmax]. Do NOT draw lettering as strokes, the app writes " +
                "it from your transcription. Empty list if none. " +
                "summary: a very short description of the subject. " + focus(settings);
    }

    private static String focus(DrawingSettings settings) {
        String hint = settings.aiFocus == null ? "" : settings.aiFocus.trim();
        if (hint.isEmpty()) return "";
        if (hint.length() > 200) hint = hint.substring(0, 200);
        return "The person adds this note about the picture (treat it as a hint, not as a rule): " + hint;
    }

    // ---------------------------------------------------------------- shared

    private static Sketch withLettering(Bitmap image, Sketch sketch, List<AiScene.Region> lettering,
                                        DrawingSettings settings) {
        if (lettering.isEmpty()) return sketch;
        return AiGuidedSketch.enhance(image, sketch,
                new AiScene(new ArrayList<AiScene.Region>(), lettering, ""), settings, null);
    }

    private static Sketch rebuild(Sketch original, ArrayList<float[]> strokes) {
        ArrayList<ColorLayer> layers = new ArrayList<>();
        for (ColorLayer layer : original.colorLayers) {
            layers.add("White outline".equals(layer.name)
                    ? new ColorLayer(layer.color, layer.name, strokes) : layer);
        }
        return new Sketch(original.width, original.height, strokes, original.edgePixels, layers);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? "Something went wrong" : e.getMessage();
    }

    /** Numbered overlay of the edge trace so the model can refer to lines by id. */
    private static Bitmap renderGuide(Bitmap image, Sketch base) {
        float scale = Math.min(1f, 1024f / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, Math.round(image.getWidth() * scale));
        int h = Math.max(1, Math.round(image.getHeight() * scale));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFFFFFFFF);
        Paint faint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        faint.setAlpha(70);
        canvas.drawBitmap(image, null, new RectF(0, 0, w, h), faint);

        int[] palette = {0xFFD0021B, 0xFF0A6CFF, 0xFF008A3E, 0xFFB45F06, 0xFF7B1FA2, 0xFF00838F};
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1.6f, w / 600f));
        line.setStrokeCap(Paint.Cap.ROUND);
        Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
        tag.setTextSize(Math.max(11f, w / 60f));
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextAlign(Paint.Align.CENTER);
        Paint tagBack = new Paint(Paint.ANTI_ALIAS_FLAG);
        tagBack.setColor(0xE6FFFFFF);
        Path path = new Path();

        List<float[]> strokes = base.strokes;
        // Label the longest lines first so busy areas stay readable.
        int labelLimit = 150;
        boolean[] labelled = new boolean[strokes.size()];
        if (strokes.size() <= labelLimit) {
            java.util.Arrays.fill(labelled, true);
        } else {
            Integer[] order = new Integer[strokes.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final List<float[]> ref = strokes;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    return Integer.compare(ref.get(b).length, ref.get(a).length);
                }
            });
            for (int i = 0; i < labelLimit; i++) labelled[order[i]] = true;
        }
        for (int i = 0; i < strokes.size(); i++) {
            float[] s = strokes.get(i);
            if (s.length < 4) continue;
            line.setColor(palette[i % palette.length]);
            path.reset();
            path.moveTo(s[0] * w, s[1] * h);
            for (int k = 2; k + 1 < s.length; k += 2) path.lineTo(s[k] * w, s[k + 1] * h);
            canvas.drawPath(path, line);
        }
        for (int i = 0; i < strokes.size(); i++) {
            float[] s = strokes.get(i);
            if (!labelled[i] || s.length < 4) continue;
            int mid = (s.length / 4) * 2;
            float x = s[mid] * w, y = s[mid + 1] * h;
            String label = String.valueOf(i);
            float half = tag.measureText(label) / 2f + 3f;
            canvas.drawRoundRect(new RectF(x - half, y - tag.getTextSize() * .8f,
                    x + half, y + tag.getTextSize() * .3f), 4f, 4f, tagBack);
            tag.setColor(palette[i % palette.length]);
            canvas.drawText(label, x, y, tag);
        }
        return out;
    }
}
