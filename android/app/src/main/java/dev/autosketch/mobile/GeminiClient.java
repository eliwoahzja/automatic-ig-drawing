package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal direct client for the Gemini generateContent REST API.
 *
 * <p>The request goes from the phone straight to Google with the user's own API key, so no
 * server is needed. The key is only ever sent in the x-goog-api-key header.</p>
 */
public final class GeminiClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    public static final class GeminiException extends Exception {
        public final int status;
        /** True when trying another model could help (busy, quota, unknown model). */
        public final boolean fallbackHelps;

        GeminiException(String message, int status, boolean fallbackHelps) {
            super(message);
            this.status = status;
            this.fallbackHelps = fallbackHelps;
        }
    }

    private GeminiClient() {}

    // ---------------------------------------------------------------- parts

    public static JSONObject textPart(String text) throws Exception {
        return new JSONObject().put("text", text);
    }

    /**
     * Encodes a bitmap as a JPEG part. Transparent pixels are flattened onto white first,
     * otherwise stickers and PNG cut-outs turn black and the model cannot read them.
     */
    public static JSONObject imagePart(Bitmap image, int maxDimension) throws Exception {
        if (image == null || image.isRecycled()) throw new Exception("No source image");
        float scale = Math.min(1f, maxDimension / (float) Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, Math.round(image.getWidth() * scale));
        int h = Math.max(1, Math.round(image.getHeight() * scale));
        Bitmap flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(flat);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(image, null, new android.graphics.Rect(0, 0, w, h), paint);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!flat.compress(Bitmap.CompressFormat.JPEG, 86, out))
                throw new Exception("Could not compress image");
            JSONObject data = new JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            return new JSONObject().put("inlineData", data);
        } finally {
            flat.recycle();
        }
    }

    // ------------------------------------------------------------- requests

    /**
     * Sends the parts and returns the parsed JSON answer. Tries each model in order and moves
     * to the next one when a model is busy, out of quota or unavailable.
     */
    public static JSONObject generateJson(String apiKey, String[] models, JSONArray parts,
                                          JSONObject schema, int maxOutputTokens,
                                          String thinkingLevel) throws GeminiException {
        if (apiKey == null || apiKey.trim().isEmpty())
            throw new GeminiException("Add your Gemini API key first", 0, false);
        GeminiException last = null;
        for (String model : models) {
            try {
                return callModel(apiKey.trim(), model, parts, schema, maxOutputTokens, thinkingLevel);
            } catch (GeminiException e) {
                last = e;
                if (!e.fallbackHelps) throw e;
            }
        }
        throw last == null ? new GeminiException("No model available", 0, false) : last;
    }

    private static JSONObject callModel(String key, String model, JSONArray parts, JSONObject schema,
                                        int maxTokens, String thinking) throws GeminiException {
        try {
            try {
                return post(key, model, parts, schema, maxTokens, thinking);
            } catch (GeminiException e) {
                // Some models reject thinkingConfig; retry once without it.
                if (e.status == 400 && thinking != null
                        && String.valueOf(e.getMessage()).toLowerCase().contains("thinking")) {
                    return post(key, model, parts, schema, maxTokens, null);
                }
                throw e;
            }
        } catch (GeminiException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new GeminiException(model + " timed out. Try a lower style or Flash-Lite", 0, true);
        } catch (java.io.IOException e) {
            throw new GeminiException("No connection to Gemini", 0, false);
        } catch (Exception e) {
            throw new GeminiException(e.getMessage() == null ? "Gemini request failed" : e.getMessage(),
                    0, false);
        }
    }

    private static JSONObject post(String key, String model, JSONArray parts, JSONObject schema,
                                   int maxTokens, String thinking) throws Exception {
        JSONObject config = new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("maxOutputTokens", maxTokens);
        if (thinking != null) {
            config.put("thinkingConfig", new JSONObject().put("thinkingLevel", thinking));
        }
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("parts", parts)))
                .put("generationConfig", config);

        HttpURLConnection connection = (HttpURLConnection)
                new URL(BASE + model + ":generateContent").openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(150000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", key);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String text = stream == null ? "" : readAll(stream, 4_000_000);
            if (status != 200) throw httpError(model, status, text);
            return extractJson(new JSONObject(text), model);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = stream) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > limit) throw new Exception("Reply too large");
            }
        }
        return out.toString("UTF-8");
    }

    static GeminiException httpError(String model, int status, String raw) {
        String detail = "";
        try {
            detail = new JSONObject(raw).getJSONObject("error").optString("message", "");
        } catch (Exception ignored) {
        }
        if (detail.length() > 140) detail = detail.substring(0, 140);
        if (status == 401 || status == 403)
            return new GeminiException("Gemini rejected the API key", status, false);
        if (status == 429)
            return new GeminiException(model + " is out of quota or rate limited", status, true);
        if (status == 404)
            return new GeminiException(model + " is not available for this key", status, true);
        if (status >= 500)
            return new GeminiException(model + " is busy right now", status, true);
        return new GeminiException("Gemini error " + status + (detail.isEmpty() ? "" : ": " + detail),
                status, false);
    }

    /** Pulls the answer text out of a generateContent reply and parses it as JSON. */
    static JSONObject extractJson(JSONObject reply, String model) throws GeminiException {
        JSONObject feedback = reply.optJSONObject("promptFeedback");
        if (feedback != null && feedback.has("blockReason"))
            throw new GeminiException("Gemini blocked this image (" +
                    feedback.optString("blockReason") + ")", 0, false);
        JSONArray candidates = reply.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0)
            throw new GeminiException(model + " returned no answer", 0, true);
        JSONObject candidate = candidates.optJSONObject(0);
        StringBuilder text = new StringBuilder();
        JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null || part.optBoolean("thought", false)) continue;
                text.append(part.optString("text", ""));
            }
        }
        String answer = text.toString().trim();
        if (answer.startsWith("```")) {
            int firstBreak = answer.indexOf('\n');
            int lastFence = answer.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) answer = answer.substring(firstBreak + 1, lastFence).trim();
        }
        if (answer.isEmpty()) {
            boolean cut = candidate != null && "MAX_TOKENS".equals(candidate.optString("finishReason"));
            throw new GeminiException(cut ? "The drawing was too long. Try Minimal style"
                    : model + " returned an empty answer", 0, !cut);
        }
        try {
            return new JSONObject(answer);
        } catch (Exception e) {
            boolean cut = candidate != null && "MAX_TOKENS".equals(candidate.optString("finishReason"));
            throw new GeminiException(cut ? "The drawing was too long. Try Minimal style"
                    : "Gemini returned malformed data", 0, !cut);
        }
    }
}
