package dev.autosketch.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and converts Gemini's JSON answer. Every coordinate from the model is
 * [y, x] on a 0..1000 grid, which is the convention Gemini is trained on for grounding.
 */
public final class GeminiPlan {

    public final String summary;
    /** Indices of traced strokes the model wants removed (Refine only). */
    public final Set<Integer> remove;
    /** Legible lettering with its box in 0..1 image space. */
    public final List<AiScene.Region> lettering;
    /** New strokes in 0..1 image space as flat x,y pairs. */
    public final ArrayList<float[]> strokes;

    private GeminiPlan(String summary, Set<Integer> remove, List<AiScene.Region> lettering,
                       ArrayList<float[]> strokes) {
        this.summary = summary;
        this.remove = remove;
        this.lettering = lettering;
        this.strokes = strokes;
    }

    public static JSONObject schema(boolean withRemove) throws Exception {
        JSONObject point = new JSONObject().put("type", "ARRAY")
                .put("items", new JSONObject().put("type", "INTEGER"));
        JSONObject stroke = new JSONObject().put("type", "ARRAY").put("items", point);
        JSONObject box = new JSONObject().put("type", "ARRAY")
                .put("items", new JSONObject().put("type", "INTEGER"));
        JSONObject text = new JSONObject().put("type", "OBJECT")
                .put("properties", new JSONObject()
                        .put("text", new JSONObject().put("type", "STRING"))
                        .put("box", box))
                .put("required", new JSONArray().put("text").put("box"));
        JSONObject props = new JSONObject()
                .put("summary", new JSONObject().put("type", "STRING"))
                .put("texts", new JSONObject().put("type", "ARRAY").put("items", text))
                .put("strokes", new JSONObject().put("type", "ARRAY").put("items", stroke));
        JSONArray required = new JSONArray().put("summary").put("texts").put("strokes");
        if (withRemove) {
            props.put("remove", new JSONObject().put("type", "ARRAY")
                    .put("items", new JSONObject().put("type", "INTEGER")));
            required.put("remove");
        }
        return new JSONObject().put("type", "OBJECT").put("properties", props).put("required", required);
    }

    /**
     * @param existingStrokes number of traced strokes the model could refer to by id
     * @param maxStrokes      cap for new strokes
     * @param maxPoints       cap for points per new stroke
     */
    public static GeminiPlan parse(JSONObject root, int existingStrokes, int maxStrokes, int maxPoints) {
        String summary = root.optString("summary", "").trim();
        if (summary.length() > 120) summary = summary.substring(0, 120);

        Set<Integer> remove = new HashSet<>();
        JSONArray ids = root.optJSONArray("remove");
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                int id = ids.optInt(i, -1);
                if (id >= 0 && id < existingStrokes) remove.add(id);
            }
            // A model that asks to delete most of the drawing is almost certainly wrong.
            if (remove.size() > existingStrokes * 0.6f) remove.clear();
        }

        ArrayList<AiScene.Region> lettering = new ArrayList<>();
        JSONArray texts = root.optJSONArray("texts");
        if (texts != null) {
            for (int i = 0; i < Math.min(8, texts.length()); i++) {
                JSONObject item = texts.optJSONObject(i);
                if (item == null) continue;
                String value = item.optString("text", "").trim();
                JSONArray box = item.optJSONArray("box");
                if (value.length() < 2 || value.length() > 80 || box == null || box.length() != 4)
                    continue;
                float top = unit(box.optDouble(0, Double.NaN)), left = unit(box.optDouble(1, Double.NaN));
                float bottom = unit(box.optDouble(2, Double.NaN)), right = unit(box.optDouble(3, Double.NaN));
                if (Float.isNaN(top + left + bottom + right)) continue;
                float w = right - left, h = bottom - top;
                if (w < .03f || h < .012f) continue;
                lettering.add(new AiScene.Region(left, top, w, h, value));
            }
        }

        ArrayList<float[]> strokes = new ArrayList<>();
        JSONArray paths = root.optJSONArray("strokes");
        if (paths != null) {
            for (int i = 0; i < paths.length() && strokes.size() < maxStrokes; i++) {
                JSONArray path = paths.optJSONArray(i);
                if (path == null) continue;
                ArrayList<Float> run = new ArrayList<>();
                float px = 0f, py = 0f;
                for (int j = 0; j < Math.min(path.length(), maxPoints); j++) {
                    JSONArray point = path.optJSONArray(j);
                    if (point == null || point.length() != 2) continue;
                    float y = unit(point.optDouble(0, Double.NaN));
                    float x = unit(point.optDouble(1, Double.NaN));
                    if (Float.isNaN(x + y)) continue;
                    // A long jump means the model lifted the pen; start a new stroke.
                    if (run.size() >= 2 && Math.hypot(x - px, y - py) > .33) {
                        flush(run, strokes, maxStrokes);
                    }
                    run.add(x);
                    run.add(y);
                    px = x;
                    py = y;
                }
                flush(run, strokes, maxStrokes);
            }
        }
        return new GeminiPlan(summary, remove, lettering, strokes);
    }

    private static void flush(ArrayList<Float> run, ArrayList<float[]> out, int max) {
        if (run.size() >= 4 && out.size() < max) {
            float[] line = new float[run.size()];
            for (int i = 0; i < line.length; i++) line[i] = run.get(i);
            out.add(line);
        }
        run.clear();
    }

    private static float unit(double thousand) {
        if (!Double.isFinite(thousand) || thousand < 0.0 || thousand > 1000.0)
            return Float.NaN;
        return (float) (thousand / 1000.0);
    }
}
