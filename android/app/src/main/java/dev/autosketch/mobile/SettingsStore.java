package dev.autosketch.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists drawing settings and overlay geometry. */
public final class SettingsStore {

    private static final String FILE = "autosketch_settings";
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final String KEY_WIDTH = "overlay_width";
    private static final String KEY_HEIGHT = "overlay_height";
    private static final String KEY_MINI_X = "mini_x";
    private static final String KEY_MINI_Y = "mini_y";
    private static final String KEY_MINIMIZED = "overlay_minimized";

    private SettingsStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static DrawingSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = prefs.getInt("detail", settings.detailLevel);
        settings.renderMode = prefs.getInt("render_mode", settings.renderMode);
        settings.colorDensity = prefs.getInt("color_density", settings.colorDensity);
        settings.aiEndpointUrl = prefs.getString("ai_endpoint_url", "");
        settings.captionText = prefs.getString("caption", settings.captionText);
        settings.captionXPercent = prefs.getInt("caption_x", settings.captionXPercent);
        settings.captionYPercent = prefs.getInt("caption_y", settings.captionYPercent);
        settings.captionSizePercent = prefs.getInt("caption_size", settings.captionSizePercent);
        settings.captionReplaceOriginal = prefs.getBoolean("caption_replace", true);
        settings.noiseReduction = prefs.getInt("noise", settings.noiseReduction);
        settings.edgeSensitivity = prefs.getInt("sensitivity", settings.edgeSensitivity);
        settings.contrast = prefs.getInt("contrast", settings.contrast);
        settings.brightness = prefs.getInt("brightness", settings.brightness);
        settings.invert = prefs.getBoolean("invert", settings.invert);
        settings.backgroundSuppression = prefs.getInt("background", settings.backgroundSuppression);
        settings.smoothing = prefs.getInt("smoothing", settings.smoothing);
        settings.simplification = prefs.getInt("simplification", settings.simplification);
        settings.strokeOverlapReduction = prefs.getInt("overlap", settings.strokeOverlapReduction);
        settings.pointSpacingPx = prefs.getInt("spacing", settings.pointSpacingPx);
        settings.minStrokeLengthPx = prefs.getInt("min_length", settings.minStrokeLengthPx);
        settings.maxStrokeDurationMs = prefs.getInt("max_duration", settings.maxStrokeDurationMs);
        settings.drawingSpeedPxPerSec = prefs.getInt("speed", settings.drawingSpeedPxPerSec);
        settings.fitMode = prefs.getInt("fit", settings.fitMode);
        settings.centerImage = prefs.getBoolean("center", settings.centerImage);
        settings.showProgressPreview = prefs.getBoolean("progress_preview", settings.showProgressPreview);
        return settings;
    }

    public static void save(Context context, DrawingSettings settings) {
        prefs(context).edit()
                .putInt("detail", settings.detailLevel)
                .putInt("render_mode", settings.renderMode)
                .putInt("color_density", settings.colorDensity)
                .putString("ai_endpoint_url", settings.aiEndpointUrl)
                .putString("caption", settings.captionText)
                .putInt("caption_x", settings.captionXPercent)
                .putInt("caption_y", settings.captionYPercent)
                .putInt("caption_size", settings.captionSizePercent)
                .putBoolean("caption_replace", settings.captionReplaceOriginal)
                .putInt("noise", settings.noiseReduction)
                .putInt("sensitivity", settings.edgeSensitivity)
                .putInt("contrast", settings.contrast)
                .putInt("brightness", settings.brightness)
                .putBoolean("invert", settings.invert)
                .putInt("background", settings.backgroundSuppression)
                .putInt("smoothing", settings.smoothing)
                .putInt("simplification", settings.simplification)
                .putInt("overlap", settings.strokeOverlapReduction)
                .putInt("spacing", settings.pointSpacingPx)
                .putInt("min_length", settings.minStrokeLengthPx)
                .putInt("max_duration", settings.maxStrokeDurationMs)
                .putInt("speed", settings.drawingSpeedPxPerSec)
                .putInt("fit", settings.fitMode)
                .putBoolean("center", settings.centerImage)
                .putBoolean("progress_preview", settings.showProgressPreview)
                .apply();
    }

    public static int overlayX(Context context, int fallback) {
        return prefs(context).getInt(KEY_X, fallback);
    }

    public static int overlayY(Context context, int fallback) {
        return prefs(context).getInt(KEY_Y, fallback);
    }

    public static int overlayWidth(Context context, int fallback) {
        return prefs(context).getInt(KEY_WIDTH, fallback);
    }

    public static int overlayHeight(Context context, int fallback) {
        return prefs(context).getInt(KEY_HEIGHT, fallback);
    }

    public static int miniX(Context context, int fallback) {
        return prefs(context).getInt(KEY_MINI_X, fallback);
    }

    public static int miniY(Context context, int fallback) {
        return prefs(context).getInt(KEY_MINI_Y, fallback);
    }

    public static boolean isMinimized(Context context) {
        return prefs(context).getBoolean(KEY_MINIMIZED, false);
    }

    public static void saveOverlayGeometry(Context context, int x, int y, int width, int height,
                                           boolean minimized, int miniX, int miniY) {
        prefs(context).edit()
                .putInt(KEY_X, x)
                .putInt(KEY_Y, y)
                .putInt(KEY_WIDTH, width)
                .putInt(KEY_HEIGHT, height)
                .putBoolean(KEY_MINIMIZED, minimized)
                .putInt(KEY_MINI_X, miniX)
                .putInt(KEY_MINI_Y, miniY)
                .apply();
    }
}
