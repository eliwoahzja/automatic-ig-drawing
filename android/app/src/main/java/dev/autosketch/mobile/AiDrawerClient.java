package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class AiDrawerClient {
    public interface Callback {
        void complete(Sketch sketch, String description, String error);
    }

    private AiDrawerClient() {}

    public static void generate(Bitmap image, Sketch previous, String endpoint,
                                String privateToken, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                if (image == null || image.isRecycled()) throw new Exception("No source image");
                URL target = new URL(endpoint);
                if (!"https".equalsIgnoreCase(target.getProtocol()))
                    throw new Exception("An HTTPS server is required");
                Bitmap resized = image;
                int w = image.getWidth(), h = image.getHeight();
                if (Math.max(w, h) > 768) {
                    float scale = 768f / Math.max(w, h);
                    resized = Bitmap.createScaledBitmap(image,
                            Math.max(1, Math.round(w * scale)),
                            Math.max(1, Math.round(h * scale)), true);
                }
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                try {
                    if (!resized.compress(Bitmap.CompressFormat.JPEG, 75, encoded))
                        throw new Exception("Could not compress image");
                } finally {
                    if (resized != image) resized.recycle();
                }
                byte[] bytes = encoded.toByteArray();
                if (bytes.length > 1200000) throw new Exception("Image exceeds upload limit");
                String data = "data:image/jpeg;base64," +
                        Base64.encodeToString(bytes, Base64.NO_WRAP);
                JSONObject request = new JSONObject();
                request.put("image", data);
                request.put("width", w);
                request.put("height", h);
                connection = (HttpURLConnection) target.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(110000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (privateToken != null && !privateToken.trim().isEmpty())
                    connection.setRequestProperty("Authorization", "Bearer " + privateToken.trim());
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) throw new Exception("Empty server reply");
                ByteArrayOutputStream incoming = new ByteArrayOutputStream();
                try (InputStream input = stream) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) > 0) {
                        incoming.write(buffer, 0, n);
                        if (incoming.size() > 600000) throw new Exception("Reply too large");
                    }
                }
                JSONObject reply = new JSONObject(incoming.toString("UTF-8"));
                if (status != 200) throw new Exception(
                        reply.optString("error", "Server returned HTTP " + status));
                JSONArray paths = reply.optJSONArray("strokes");
                if (paths == null) throw new Exception("Server returned no drawing paths");
                ArrayList<float[]> lines = new ArrayList<>();
                int pointBudget = 4500;
                for (int i = 0; i < Math.min(120, paths.length()) && pointBudget > 0; i++) {
                    JSONArray path = paths.optJSONArray(i);
                    if (path == null || path.length() < 2 || path.length() > 110) continue;
                    ArrayList<Float> coords = new ArrayList<>();
                    float px = 0f, py = 0f;
                    for (int j = 0; j < path.length() && pointBudget > 0; j++) {
                        JSONArray point = path.optJSONArray(j);
                        if (point == null || point.length() != 2) continue;
                        double rawX = point.optDouble(0, Double.NaN);
                        double rawY = point.optDouble(1, Double.NaN);
                        if (!Double.isFinite(rawX) || !Double.isFinite(rawY)) continue;
                        float x = DrawingSettings.clamp((float) rawX, 0f, 1f);
                        float y = DrawingSettings.clamp((float) rawY, 0f, 1f);
                        if (coords.size() >= 2 &&
                                Math.hypot(x - px, y - py) > .42) break;
                        coords.add(x);
                        coords.add(y);
                        px = x; py = y;
                        pointBudget--;
                    }
                    if (coords.size() < 4) continue;
                    float[] flattened = new float[coords.size()];
                    for (int j = 0; j < flattened.length; j++) flattened[j] = coords.get(j);
                    lines.add(flattened);
                }
                if (lines.size() < 2) throw new Exception("The AI returned too few valid paths");
                Sketch result = new Sketch(
                        Math.max(1, w), Math.max(1, h), lines,
                        previous == null ? 0 : previous.edgePixels);
                callback.complete(result, reply.optString("summary", "AI drawing ready"), null);
            } catch (Exception e) {
                callback.complete(null, "", e.getMessage() == null ? "Unknown error" : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "ai-cloud-drawer").start();
    }
}
