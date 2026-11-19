package dev.autosketch.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.InputStream;

/** Image selection, sketch generation, AI drawing and entry points to the overlay. */
public class MainActivity extends Activity {

    private static final int REQUEST_IMAGE = 1;

    // Creamy, minimal palette: warm paper, soft latte chips, dark espresso ink.
    private static final int BG = 0xFFF5EEE0;
    private static final int CARD = 0xFFFCF8F0;
    private static final int CHIP = 0xFFEDE4D2;
    private static final int BORDER = 0xFFE4D9C4;
    private static final int INK = 0xFF2B2621;
    private static final int MUTED = 0xFF8D8272;
    private static final int LATTE = 0xFFE6D3B0;
    private static final int LATTE_LINE = 0xFFD5BD90;
    private static final int TERRACOTTA = 0xFFC77B52;
    private static final int ON_DARK = 0xFFFCF8F0;
    private static final int GOOD = 0xFF4F8A5B;
    private static final int WARN = 0xFFC2693B;

    private SketchPreviewView preview;
    private TextView status;
    private TextView meta;
    private TextView accessibilityState;
    private TextView generateButton;
    private TextView refineButton;
    private TextView drawButton;
    private TextView centerToggle;
    private EditText captionInput;
    private EditText aiKeyInput;
    private EditText aiFocusInput;
    private LinearLayout geminiPanel;
    private TextView geminiToggle;
    private boolean aiBusy;
    private LinearLayout modeChips;
    private LinearLayout detailChips;
    private LinearLayout fitChips;
    private LinearLayout styleChips;
    private LinearLayout modelChips;
    private DrawingSettings settings;
    private Bitmap source;
    private SketchSession.Token token;

    private interface Pick {
        void picked(int index);
    }

    private interface SettingValue {
        void changed(int value);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        settings = SettingsStore.load(this);
        SketchSession.updateSettings(settings);
        source = SketchSession.source();
        buildUi();
        refreshFromSession();
    }

    private void refreshFromSession() {
        Sketch sketch = SketchSession.sketch();
        preview.setSource(source);
        preview.setSketch(sketch, settings);
        if (sketch.isEmpty()) {
            meta.setText(source == null ? "No image loaded" : "Image ready · not traced yet");
        } else {
            meta.setText(sketch.strokes.size() + " strokes · " + sketch.colorLayers.size()
                    + " color layers · " + sketch.edgePixels + " edge pixels");
        }
    }

    // -------------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(40));
        scroll.addView(root);

        TextView title = label("Auto Sketch", 34f, INK);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        root.addView(title);
        TextView subtitle = label("Turn any picture into clean, gentle strokes.", 13f, MUTED);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        root.addView(subtitle);

        preview = new SketchPreviewView(this);
        preview.setElevation(dp(1.5f));
        root.addView(preview, new LinearLayout.LayoutParams(-1, dp(340)));

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setPadding(0, dp(16), 0, 0);
        TextView choose = pill("Choose image", false);
        choose.setContentDescription("Choose an image");
        choose.setOnClickListener(v -> pickImage());
        sourceRow.addView(choose, weighted(dp(8), 48));
        generateButton = pill("Generate sketch", true);
        generateButton.setContentDescription("Generate sketch");
        generateButton.setOnClickListener(v -> generate());
        sourceRow.addView(generateButton, weighted(dp(8), 48));
        root.addView(sourceRow);

        status = label("Choose an image to begin", 12f, INK);
        status.setPadding(dp(2), dp(14), 0, 0);
        status.setLineSpacing(dp(2), 1f);
        root.addView(status);
        meta = label("", 10.5f, MUTED);
        meta.setPadding(dp(2), dp(4), 0, dp(4));
        root.addView(meta);

        buildGeminiCard(root);

        root.addView(section("Render mode"));
        modeChips = segmented(DrawingSettings.MODE_NAMES, settings.renderMode, index -> {
            settings.renderMode = index;
            styleSegments(modeChips, index);
            persist(true);
        });
        root.addView(modeChips);
        TextView modeHint = label("Color uses guided brush changes. Trace fills first, then outlines.",
                10.5f, MUTED);
        modeHint.setPadding(dp(2), dp(8), 0, 0);
        root.addView(modeHint);

        buildAdjustments(root);

        root.addView(section("Automation"));
        LinearLayout automationRow = new LinearLayout(this);
        automationRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView accessibility = pill("Accessibility", false);
        accessibility.setContentDescription("Open accessibility settings");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        automationRow.addView(accessibility, weighted(dp(8), 46));
        TextView floating = pill("Floating controls", true);
        floating.setContentDescription("Open floating controls");
        floating.setOnClickListener(v -> openFloatingControls());
        automationRow.addView(floating, weighted(dp(8), 46));
        root.addView(automationRow);

        accessibilityState = label("", 10.5f, MUTED);
        accessibilityState.setPadding(dp(2), dp(12), 0, 0);
        root.addView(accessibilityState);

        TextView hint = label("1 · Turn on the accessibility service\n2 · Choose an image and generate\n"
                + "3 · Open the floating controls and select the drawing area on Instagram\n"
                + "4 · Start drawing. Pick a thin brush first; nothing is tapped outside your rectangle.",
                10.5f, MUTED);
        hint.setPadding(dp(2), dp(14), 0, 0);
        hint.setLineSpacing(dp(4), 1f);
        root.addView(hint);

        setContentView(scroll);
    }

    private void buildGeminiCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(surface(CARD, 26, 1f, BORDER));
        card.setElevation(dp(1.5f));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(20);
        root.addView(card, cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label("AI artist", 17f, INK);
        name.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = label("Gemini", 10.5f, TERRACOTTA);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        badge.setBackground(surface(0x22C77B52, 12, 0f, 0));
        header.addView(badge);
        card.addView(header);

        TextView blurb = label("Refine keeps your accurate trace and lets Gemini clean it up and read the "
                + "lettering. Draw lets Gemini plan the line art itself.", 11f, MUTED);
        blurb.setPadding(0, dp(8), 0, dp(14));
        blurb.setLineSpacing(dp(3), 1f);
        card.addView(blurb);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        refineButton = pill("Refine", false);
        refineButton.setContentDescription("Refine the sketch with Gemini");
        refineButton.setOnClickListener(v -> runGemini(true));
        actions.addView(refineButton, weighted(dp(8), 48));
        drawButton = pill("Draw", false);
        drawButton.setTextColor(ON_DARK);
        drawButton.setBackground(ripple(TERRACOTTA, 18));
        drawButton.setContentDescription("Let Gemini draw the picture");
        drawButton.setOnClickListener(v -> runGemini(false));
        actions.addView(drawButton, weighted(dp(8), 48));
        card.addView(actions);

        card.addView(section("Drawing style", 18));
        styleChips = segmented(DrawingSettings.AI_STYLE_NAMES, settings.aiStyle, index -> {
            settings.aiStyle = index;
            styleSegments(styleChips, index);
            persistAi();
        });
        card.addView(styleChips);

        aiFocusInput = field("Optional note, e.g. focus on the face", false);
        aiFocusInput.setText(settings.aiFocus);
        aiFocusInput.addTextChangedListener(new SimpleWatcher() {
            @Override public void changed(String value) {
                settings.aiFocus = value;
                persistAi();
            }
        });
        LinearLayout.LayoutParams focusParams = new LinearLayout.LayoutParams(-1, dp(50));
        focusParams.topMargin = dp(12);
        card.addView(aiFocusInput, focusParams);

        geminiToggle = pill(settings.geminiApiKey.isEmpty() ? "Gemini key & model   +" : "Gemini key & model   ·   saved",
                false);
        geminiToggle.setTextSize(12f);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-1, dp(44));
        toggleParams.topMargin = dp(12);
        card.addView(geminiToggle, toggleParams);

        geminiPanel = new LinearLayout(this);
        geminiPanel.setOrientation(LinearLayout.VERTICAL);
        geminiPanel.setPadding(0, dp(4), 0, 0);
        geminiPanel.setVisibility(settings.geminiApiKey.isEmpty() ? View.VISIBLE : View.GONE);
        card.addView(geminiPanel);
        if (settings.geminiApiKey.isEmpty()) geminiToggle.setText("Gemini key & model   −");
        geminiToggle.setOnClickListener(v -> setGeminiPanelOpen(geminiPanel.getVisibility() != View.VISIBLE));

        geminiPanel.addView(section("Model", 14));
        modelChips = segmented(DrawingSettings.GEMINI_MODEL_NAMES, settings.geminiModel, index -> {
            settings.geminiModel = index;
            styleSegments(modelChips, index);
            persistAi();
        });
        geminiPanel.addView(modelChips);
        TextView modelHint = label("3.5 Flash is the most accurate. Flash-Lite is faster and cheaper. "
                + "If one is busy, the other is used automatically.", 10.5f, MUTED);
        modelHint.setPadding(dp(2), dp(8), 0, 0);
        modelHint.setLineSpacing(dp(3), 1f);
        geminiPanel.addView(modelHint);

        geminiPanel.addView(section("API key", 14));
        aiKeyInput = field("Paste your Gemini API key", true);
        aiKeyInput.setText(settings.geminiApiKey);
        aiKeyInput.addTextChangedListener(new SimpleWatcher() {
            @Override public void changed(String value) {
                settings.geminiApiKey = value.trim();
            }
        });
        aiKeyInput.setOnFocusChangeListener((view, focused) -> {
            if (!focused) persistAi();
        });
        geminiPanel.addView(aiKeyInput, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView keyHint = label("Create a free key at aistudio.google.com. It stays on this phone and is "
                + "sent only to Google when you press Refine or Draw.", 10.5f, MUTED);
        keyHint.setPadding(dp(2), dp(8), 0, 0);
        keyHint.setLineSpacing(dp(3), 1f);
        geminiPanel.addView(keyHint);

        TextView onDevice = label("Or use on-device enhance (no internet)", 11f, MUTED);
        onDevice.setGravity(Gravity.CENTER);
        onDevice.setPadding(0, dp(14), 0, dp(4));
        onDevice.setOnClickListener(v -> enhanceOnDevice());
        card.addView(onDevice, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private void setGeminiPanelOpen(boolean open) {
        geminiPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        geminiToggle.setText(open ? "Gemini key & model   −"
                : (settings.geminiApiKey.isEmpty() ? "Gemini key & model   +"
                : "Gemini key & model   ·   saved"));
    }

    private void buildAdjustments(LinearLayout root) {
        final TextView toggle = pill("Adjust drawing & lettering   +", false);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-1, dp(50));
        toggleParams.topMargin = dp(22);
        root.addView(toggle, toggleParams);

        final LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setPadding(dp(16), dp(4), dp(16), dp(18));
        advanced.setBackground(surface(CARD, 24, 1f, BORDER));
        advanced.setVisibility(View.GONE);
        LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(-1, -2);
        advancedParams.topMargin = dp(10);
        root.addView(advanced, advancedParams);
        toggle.setOnClickListener(v -> {
            boolean opening = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(opening ? View.VISIBLE : View.GONE);
            toggle.setText(opening ? "Close adjustments   −" : "Adjust drawing & lettering   +");
        });

        advanced.addView(section("Detail level"));
        detailChips = segmented(DrawingSettings.DETAIL_NAMES, settings.detailLevel, index -> {
            settings.detailLevel = index;
            styleSegments(detailChips, index);
            persist(true);
        });
        advanced.addView(detailChips);

        advanced.addView(section("Color coverage"));
        TextView colorHint = label("Select Color mode, generate again, then choose each brush color as prompted.",
                10.5f, MUTED);
        colorHint.setPadding(0, 0, 0, dp(5));
        advanced.addView(colorHint);
        advanced.addView(settingSlider("Fill density", 15, 95, settings.colorDensity,
                value -> settings.colorDensity = value));

        advanced.addView(section("Canvas fit"));
        fitChips = segmented(DrawingSettings.FIT_NAMES, settings.fitMode, index -> {
            settings.fitMode = index;
            styleSegments(fitChips, index);
            persist(false);
        });
        advanced.addView(fitChips);

        centerToggle = label("", 11.5f, MUTED);
        centerToggle.setGravity(Gravity.CENTER);
        styleToggle(centerToggle, settings.centerImage);
        centerToggle.setOnClickListener(v -> {
            settings.centerImage = !settings.centerImage;
            styleToggle(centerToggle, settings.centerImage);
            persist(true);
        });
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(-1, dp(44));
        centerParams.topMargin = dp(8);
        advanced.addView(centerToggle, centerParams);

        advanced.addView(section("Readable lettering"));
        TextView letteringHint = label("Type the words exactly to replace distorted lettering. Set the "
                + "position in the image, then regenerate. The AI artist fills this in on its own.",
                10.5f, MUTED);
        letteringHint.setPadding(0, 0, 0, dp(8));
        letteringHint.setLineSpacing(dp(3), 1f);
        advanced.addView(letteringHint);
        captionInput = field("Optional text, including line breaks", false);
        captionInput.setSingleLine(false);
        captionInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        captionInput.setGravity(Gravity.TOP | Gravity.START);
        captionInput.setText(settings.captionText);
        advanced.addView(captionInput, new LinearLayout.LayoutParams(-1, dp(92)));
        advanced.addView(settingSlider("Text position X", 10, 90, settings.captionXPercent,
                value -> settings.captionXPercent = value));
        advanced.addView(settingSlider("Text position Y", 10, 90, settings.captionYPercent,
                value -> settings.captionYPercent = value));
        advanced.addView(settingSlider("Text size", 3, 18, settings.captionSizePercent,
                value -> settings.captionSizePercent = value));
        TextView applyText = pill("Apply lettering + regenerate", false);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(-1, dp(48));
        applyParams.topMargin = dp(10);
        advanced.addView(applyText, applyParams);
        applyText.setOnClickListener(v -> {
            settings.captionText = captionInput.getText().toString();
            persist(true);
            generate();
        });
    }

    private View settingSlider(String title, int min, int max, int value, final SettingValue callback) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(6), 0, dp(3));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, 12f, MUTED), new LinearLayout.LayoutParams(0, dp(22), 1f));
        final TextView number = label(String.valueOf(value), 12f, INK);
        row.addView(number);
        block.addView(row);
        SeekBar slider = new SeekBar(this);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setProgressTintList(ColorStateList.valueOf(TERRACOTTA));
        slider.setThumbTintList(ColorStateList.valueOf(TERRACOTTA));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(LATTE));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                number.setText(String.valueOf(progress));
                callback.changed(progress);
                persist(true);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        block.addView(slider, new LinearLayout.LayoutParams(-1, dp(36)));
        return block;
    }

    // ----------------------------------------------------------- UI helpers

    private abstract static class SimpleWatcher implements TextWatcher {
        abstract void changed(String value);
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            changed(s.toString());
        }
    }

    private LinearLayout.LayoutParams weighted(int margin, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        params.leftMargin = margin / 2;
        params.rightMargin = margin / 2;
        return params;
    }

    private TextView label(String text, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView section(String text) {
        return section(text, 24);
    }

    private TextView section(String text, int topDp) {
        TextView view = label(text.toUpperCase(), 10f, MUTED);
        view.setLetterSpacing(0.14f);
        view.setPadding(dp(2), dp(topDp), 0, dp(10));
        return view;
    }

    private EditText field(String hint, boolean secret) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(INK);
        input.setHintTextColor(0xFFB3A892);
        input.setTextSize(13f);
        input.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setBackground(surface(CHIP, 16, 0f, 0));
        return input;
    }

    private GradientDrawable surface(int color, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0f) {
            drawable.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        }
        return drawable;
    }

    private RippleDrawable ripple(int background, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000),
                surface(background, radiusDp, 0f, 0),
                surface(0xFFFFFFFF, radiusDp, 0f, 0));
    }

    private TextView pill(String text, boolean primary) {
        TextView view = label(text, 13f, primary ? ON_DARK : INK);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(ripple(primary ? INK : CHIP, 18));
        return view;
    }

    private LinearLayout segmented(final String[] labels, int selected, final Pick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = label(labels[i], 12f, MUTED);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            params.leftMargin = i == 0 ? 0 : dp(6);
            row.addView(chip, params);
            chip.setOnClickListener(v -> pick.picked(index));
        }
        styleSegments(row, selected);
        return row;
    }

    private void styleSegments(LinearLayout row, int selected) {
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView chip = (TextView) row.getChildAt(i);
            boolean active = i == selected;
            chip.setTextColor(active ? INK : MUTED);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setBackground(active ? surface(LATTE, 15, 1f, LATTE_LINE) : ripple(CHIP, 15));
        }
    }

    private void styleToggle(TextView view, boolean enabled) {
        view.setText(enabled ? "✓  Center image" : "Center image");
        view.setTextColor(enabled ? INK : MUTED);
        view.setBackground(enabled ? surface(LATTE, 15, 1f, LATTE_LINE) : ripple(CHIP, 15));
    }

    private void setAiButtons(boolean enabled, String refineText, String drawText) {
        refineButton.setEnabled(enabled);
        drawButton.setEnabled(enabled);
        refineButton.setAlpha(enabled ? 1f : .6f);
        drawButton.setAlpha(enabled ? 1f : .6f);
        refineButton.setText(refineText);
        drawButton.setText(drawText);
    }

    // --------------------------------------------------------------- actions

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_IMAGE);
        } catch (RuntimeException exception) {
            status.setText("No image picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                throw new IllegalStateException("Unable to read the selected file");
            }
            Bitmap decoded = SketchSession.loadSource(stream, SketchProcessor.MAX_SOURCE_DIMENSION);
            if (decoded == null) {
                throw new IllegalStateException("Unsupported image");
            }
            Bitmap prepared = SketchSession.setSource(decoded);
            if (prepared != decoded && !decoded.isRecycled()) {
                decoded.recycle();
            }
            source = prepared;
            SketchSession.setSketch(Sketch.empty());
            aiBusy = false;
            setAiButtons(true, "Refine", "Draw");
            preview.setSource(source);
            preview.setSketch(Sketch.empty(), settings);
            status.setText("Image loaded · tap Generate sketch");
            meta.setText(prepared.getWidth() + " × " + prepared.getHeight() + " source");
        } catch (Exception exception) {
            status.setText("Unable to load image: " + exception.getMessage());
        }
    }

    private void generate() {
        if (source == null || source.isRecycled()) {
            status.setText("Choose an image first");
            return;
        }
        if (token != null) {
            token.cancel();
        }
        final SketchSession.Token active = new SketchSession.Token();
        token = active;
        generateButton.setEnabled(false);
        generateButton.setText("Processing…");
        status.setText("Detecting edges and tracing contours…");
        meta.setText("");
        final DrawingSettings snapshot = settings.copy();
        final int canvasWidth = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            final SketchProcessor.Outcome outcome = SketchProcessor.process(source, snapshot,
                    canvasWidth, active);
            runOnUiThread(() -> {
                if (token != active) {
                    return;
                }
                token = null;
                generateButton.setEnabled(true);
                generateButton.setText("Generate sketch");
                if (outcome.cancelled) {
                    return;
                }
                SketchSession.setSketch(outcome.sketch);
                preview.setSketch(outcome.sketch, settings);
                status.setText(outcome.sketch.isEmpty()
                        ? "No strokes detected · try a higher detail level"
                        : "Sketch ready · " + outcome.durationMs + " ms");
                meta.setText(outcome.sketch.strokes.size() + " strokes · "
                        + outcome.sketch.pointCount() + " points · "
                        + outcome.sketch.edgePixels + " edge pixels");
            });
        }, "sketch-process").start();
    }

    /** Gemini: refine = curate the trace, draw = plan the whole line art. */
    private void runGemini(final boolean refine) {
        if (source == null || source.isRecycled()) {
            status.setText("Choose an image first");
            return;
        }
        if (aiBusy) return;
        settings.geminiApiKey = aiKeyInput.getText().toString().trim();
        if (settings.geminiApiKey.isEmpty()) {
            setGeminiPanelOpen(true);
            status.setText("Paste your Gemini API key to use the AI artist");
            return;
        }
        final Sketch base = SketchSession.sketch();
        if (refine && (base == null || base.isEmpty())) {
            status.setText("Generate the base sketch first, then refine it");
            return;
        }
        persistAi();
        aiBusy = true;
        setAiButtons(false, refine ? "Refining…" : "Drawing…", refine ? "Refine" : "Drawing…");
        status.setText(refine
                ? "Gemini is reviewing the trace and reading the lettering…"
                : "Gemini is planning the drawing · this can take up to a minute");
        meta.setText("");
        final Bitmap requested = source;
        final DrawingSettings snapshot = settings.copy();
        GeminiArtist.Callback callback = (result, error) -> runOnUiThread(() -> {
            aiBusy = false;
            setAiButtons(true, "Refine", "Draw");
            if (source != requested || isFinishing() || isDestroyed()) return;
            if (result == null) {
                status.setText("AI artist: " + error);
                return;
            }
            SketchSession.setSketch(result.sketch);
            preview.setSketch(result.sketch, settings);
            meta.setText(result.sketch.strokes.size() + " strokes · "
                    + (refine ? result.removed + " noisy lines removed · " : "")
                    + result.textLines + " lettering lines");
            String summary = result.summary.isEmpty() ? "" : result.summary + " · ";
            status.setText((refine ? "Refined · " : "Drawn by Gemini · ") + summary
                    + "check the preview before drawing");
        });
        if (refine) GeminiArtist.refine(requested, base, snapshot, callback);
        else GeminiArtist.draw(requested, base, snapshot, callback);
    }

    /** Offline option: ML Kit text and object detection. */
    private void enhanceOnDevice() {
        if (source == null || source.isRecycled()) {
            status.setText("Choose an image first");
            return;
        }
        final Sketch baseline = SketchSession.sketch();
        if (baseline == null || baseline.isEmpty()) {
            status.setText("Generate the base sketch first");
            return;
        }
        if (aiBusy) return;
        aiBusy = true;
        final Bitmap requested = source;
        status.setText("On-device AI: recognizing text and important objects…");
        AiVisionProcessor.analyze(requested, scene -> {
            if (source != requested || isFinishing() || isDestroyed()) {
                aiBusy = false;
                return;
            }
            status.setText("On-device AI: recovering fine contours…");
            final DrawingSettings snapshot = settings.copy();
            final SketchSession.Token workToken = new SketchSession.Token();
            new Thread(() -> {
                try {
                    Sketch result = AiGuidedSketch.enhance(requested, baseline, scene, snapshot, workToken);
                    runOnUiThread(() -> {
                        if (source != requested || isFinishing() || isDestroyed()) return;
                        aiBusy = false;
                        SketchSession.setSketch(result);
                        preview.setSketch(result, settings);
                        meta.setText(result.strokes.size() + " strokes · "
                                + scene.objects.size() + " object regions · "
                                + scene.lettering.size() + " text regions");
                        if (scene.lettering.size() > 0 && captionInput != null
                                && captionInput.length() == 0) {
                            captionInput.setText(scene.recognizedWords());
                        }
                        status.setText("Enhanced on-device · inspect text in the preview before drawing");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        aiBusy = false;
                        status.setText("On-device AI failed: " + e.getMessage());
                    });
                }
            }, "ai-contour-refinement").start();
        });
    }

    private void openFloatingControls() {
        if (SketchSession.sketch().isEmpty()) {
            status.setText("Generate a sketch before opening the controls");
            return;
        }
        DrawService service = DrawService.instance;
        if (service == null) {
            status.setText("Enable the Auto Sketch accessibility service first");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        service.showControls();
        status.setText("Controls opened · switch to Instagram and select the drawing area");
    }

    private void persistAi() {
        SketchSession.updateSettings(settings);
        SettingsStore.save(this, settings);
    }

    private void persist(boolean structural) {
        SketchSession.updateSettings(settings);
        SettingsStore.save(this, settings);
        preview.setSketch(SketchSession.sketch(), settings);
        if (structural) {
            status.setText("Render settings changed · generate again to apply them");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Settings may have been edited from the floating panel while this
        // activity was in the background; mirror them back into the UI.
        DrawingSettings stored = SettingsStore.load(this);
        if (aiKeyInput != null && aiKeyInput.hasFocus()) stored.geminiApiKey = settings.geminiApiKey;
        settings = stored;
        SketchSession.updateSettings(settings);
        source = SketchSession.source();
        if (captionInput != null && !captionInput.hasFocus())
            captionInput.setText(settings.captionText);
        styleSegments(modeChips, settings.renderMode);
        styleSegments(detailChips, settings.detailLevel);
        styleSegments(fitChips, settings.fitMode);
        styleSegments(styleChips, settings.aiStyle);
        styleSegments(modelChips, settings.geminiModel);
        styleToggle(centerToggle, settings.centerImage);
        refreshFromSession();
        DrawService service = DrawService.instance;
        if (accessibilityState != null) {
            if (service == null) {
                accessibilityState.setText("Accessibility service is off");
                accessibilityState.setTextColor(WARN);
            } else if (service.isDrawingActive()) {
                accessibilityState.setText("Accessibility service is on · drawing in progress");
                accessibilityState.setTextColor(TERRACOTTA);
            } else {
                accessibilityState.setText("Accessibility service is on");
                accessibilityState.setTextColor(GOOD);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (token != null) {
            token.cancel();
            token = null;
        }
        super.onDestroy();
    }
}
