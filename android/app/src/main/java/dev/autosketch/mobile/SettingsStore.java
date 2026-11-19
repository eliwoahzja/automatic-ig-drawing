package dev.autosketch.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persists drawing settings and overlay geometry. */
public final class SettingsStore {

    private static final String FILE = "autosketch_settings";
    private static final String ENCRYPTED_KEY = "gemini_key_encrypted";
    private static final String KEY_ALIAS = "autosketch_gemini_key_v1";
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final String KEY_WIDTH = "overlay_width";
    private static final String KEY_HEIGHT = "overlay_height";
    private static final String KEY_MINI_X = "mini_x";
    private static final String KEY_MINI_Y = "mini_y";
    private static final String KEY_MINIMIZED = "overlay_minimized";

    private SettingsStore() {
    }

    private static int clampIndex(int value, int size) {
        return value < 0 ? 0 : (value >= size ? size - 1 : value);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encryptSecret(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] data = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, data, 0, iv.length);
        System.arraycopy(ciphertext, 0, data, iv.length, ciphertext.length);
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String decryptSecret(String encoded) throws Exception {
        byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
        if (data.length < 29) return "";
        byte[] iv = java.util.Arrays.copyOfRange(data, 0, 12);
        byte[] ciphertext = java.util.Arrays.copyOfRange(data, 12, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readGeminiKey(SharedPreferences prefs) {
        String encrypted = prefs.getString(ENCRYPTED_KEY, "");
        if (encrypted != null && !encrypted.isEmpty()) {
            try { return decryptSecret(encrypted); }
            catch (Exception ignored) { return ""; }
        }
        String legacy = prefs.getString("gemini_key", "");
        if (legacy == null || legacy.isEmpty()) return "";
        try {
            prefs.edit().putString(ENCRYPTED_KEY, encryptSecret(legacy))
                    .remove("gemini_key").commit();
        } catch (Exception ignored) {
            prefs.edit().remove("gemini_key").apply();
        }
        return legacy;
    }

    public static DrawingSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        DrawingSettings settings = new DrawingSettings();
        settings.detailLevel = prefs.getInt("detail", settings.detailLevel);
        settings.renderMode = prefs.getInt("render_mode", settings.renderMode);
        settings.colorDensity = prefs.getInt("color_density", settings.colorDensity);
        settings.geminiApiKey = readGeminiKey(prefs);
        settings.geminiModel = clampIndex(prefs.getInt("gemini_model", 0),
                DrawingSettings.GEMINI_MODEL_IDS.length);
        settings.aiStyle = clampIndex(prefs.getInt("ai_style", 0),
                DrawingSettings.AI_STYLE_NAMES.length);
        settings.aiFocus = prefs.getString("ai_focus", "");
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
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (settings.geminiApiKey != null && !settings.geminiApiKey.isEmpty()) {
            try { editor.putString(ENCRYPTED_KEY, encryptSecret(settings.geminiApiKey)); }
            catch (Exception ignored) { editor.remove(ENCRYPTED_KEY); }
        } else {
            editor.remove(ENCRYPTED_KEY);
        }
        editor.remove("gemini_key");
        editor.putInt("detail", settings.detailLevel)
                .putInt("render_mode", settings.renderMode)
                .putInt("color_density", settings.colorDensity)
                .putInt("gemini_model", settings.geminiModel)
                .putInt("ai_style", settings.aiStyle)
                .putString("ai_focus", settings.aiFocus)
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
