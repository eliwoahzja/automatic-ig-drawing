package dev.autosketch.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.SeekBar;
import android.text.InputType;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;

/** Image selection, sketch generation, preview and entry points to the overlay. */
public class MainActivity extends Activity {

    private static final int REQUEST_IMAGE = 1;

    private static final int SURFACE = 0xFF101218;
    private static final int CARD = 0xFF1B1E27;
    private static final int CHIP = 0xFF222630;
    private static final int ACCENT = 0xFF81BFFF;
    private static final int ACCENT_SOFT = 0x334B9EFF;
    private static final int TEXT = 0xFFF3F5F9;
    private static final int MUTED = 0xFFA0A4B3;

    private SketchPreviewView preview;
    private TextView status;
    private TextView meta;
    private TextView accessibilityState;
    private TextView generateButton;
    private TextView centerToggle;
    private EditText captionInput;
    private LinearLayout modeChips;
    private LinearLayout detailChips;
    private LinearLayout fitChips;
    private DrawingSettings settings;
    private Bitmap source;
    private SketchSession.Token token;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0B0C10);
        getWindow().setNavigationBarColor(0xFF0B0C10);
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
            meta.setText(sketch.strokes.size() + " contours · " + sketch.colorLayers.size()
                    + " color layers · " + sketch.edgePixels + " edge pixels");
        }
    }

    // -------------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF090B10);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(34));
        scroll.addView(root);

        TextView title = label("Auto Sketch", 30f, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);
        TextView subtitle = label("Turn images into clean strokes and color layers.",
                12.5f, MUTED);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        root.addView(subtitle);

        preview = new SketchPreviewView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(336));
        root.addView(preview, previewParams);

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setPadding(0, dp(14), 0, 0);
        TextView choose = pill("Choose image", false, false);
        choose.setContentDescription("Choose an image");
        choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        sourceRow.addView(choose, weighted(dp(6)));
        generateButton = pill("Generate sketch", true, false);
        generateButton.setContentDescription("Generate sketch");
        generateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generate();
            }
        });
        sourceRow.addView(generateButton, weighted(dp(6)));
        root.addView(sourceRow);

        status = label("Choose an image to begin", 11f, MUTED);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);
        meta = label("", 10f, 0x99FFFFFF);
        meta.setPadding(0, dp(3), 0, dp(6));
        root.addView(meta);

        root.addView(section("Render mode"));
        modeChips = segmented(DrawingSettings.MODE_NAMES, settings.renderMode, 2);
        root.addView(modeChips);
        TextView modeHint = label("Color uses guided brush changes. Trace fills first, then outlines.", 10f, MUTED);
        modeHint.setPadding(0, dp(6), 0, 0);
        root.addView(modeHint);

        TextView advancedToggle = pill("Adjust drawing & lettering   +", false, false);
        LinearLayout.LayoutParams advancedToggleParams =
                new LinearLayout.LayoutParams(-1, dp(50));
        advancedToggleParams.topMargin = dp(18);
        root.addView(advancedToggle, advancedToggleParams);

        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setPadding(dp(14), dp(5), dp(14), dp(16));
        advanced.setBackground(surface(CARD, 20, 1f, 0x1FFFFFFF));
        advanced.setVisibility(View.GONE);
        LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(-1, -2);
        advancedParams.topMargin = dp(8);
        root.addView(advanced, advancedParams);
        advancedToggle.setOnClickListener(v -> {
            boolean opening = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(opening ? View.VISIBLE : View.GONE);
            advancedToggle.setText(opening ? "Close adjustments   −"
                    : "Adjust drawing & lettering   +");
        });

        advanced.addView(section("Detail level"));
        detailChips = segmented(DrawingSettings.DETAIL_NAMES, settings.detailLevel, 0);
        advanced.addView(detailChips);

        advanced.addView(section("Color coverage"));
        TextView colorHint = label("Select Color mode, generate again, then choose each brush color as prompted.",
                10.5f, MUTED);
        colorHint.setPadding(0, 0, 0, dp(5));
        advanced.addView(colorHint);
        advanced.addView(settingSlider("Fill density", 15, 95, settings.colorDensity, new SettingValue() {
            @Override public void changed(int value) {
                settings.colorDensity = value;
            }
        }));

        advanced.addView(section("Canvas fit"));
        fitChips = segmented(DrawingSettings.FIT_NAMES, settings.fitMode, 1);
        advanced.addView(fitChips);

        centerToggle = toggle("Center image", settings.centerImage);
        centerToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.centerImage = !settings.centerImage;
                styleToggle(centerToggle, settings.centerImage);
                persist(true);
            }
        });
        advanced.addView(centerToggle, new LinearLayout.LayoutParams(-1, dp(42)));

        advanced.addView(section("Readable lettering"));
        TextView letteringHint = label("Type the words exactly to replace distorted lettering. Set the position in the image, then regenerate.",
                10.5f, MUTED);
        letteringHint.setPadding(0, 0, 0, dp(8));
        letteringHint.setLineSpacing(dp(3), 1f);
        advanced.addView(letteringHint);
        captionInput = new EditText(this);
        captionInput.setSingleLine(false);
        captionInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        captionInput.setText(settings.captionText);
        captionInput.setTextColor(TEXT);
        captionInput.setHintTextColor(MUTED);
        captionInput.setHint("Optional text, including line breaks");
        captionInput.setTextSize(13f);
        captionInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        captionInput.setBackground(surface(CARD, 16, 1f, 0x22FFFFFF));
        advanced.addView(captionInput, new LinearLayout.LayoutParams(-1, dp(90)));
        advanced.addView(settingSlider("Text position X", 10, 90, settings.captionXPercent, new SettingValue() {
            @Override public void changed(int value) {
                settings.captionXPercent = value;
            }
        }));
        advanced.addView(settingSlider("Text position Y", 10, 90, settings.captionYPercent, new SettingValue() {
            @Override public void changed(int value) {
                settings.captionYPercent = value;
            }
        }));
        advanced.addView(settingSlider("Text size", 3, 18, settings.captionSizePercent, new SettingValue() {
            @Override public void changed(int value) {
                settings.captionSizePercent = value;
            }
        }));
        TextView applyText = pill("Apply lettering + regenerate", false, false);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(-1, dp(48));
        applyParams.topMargin = dp(8);
        advanced.addView(applyText, applyParams);
        applyText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settings.captionText = captionInput.getText().toString();
                persist(true);
                generate();
            }
        });

        root.addView(section("Automation"));
        LinearLayout automationRow = new LinearLayout(this);
        automationRow.setOrientation(LinearLayout.HORIZONTAL);
        automationRow.setPadding(0, dp(8), 0, 0);
        TextView accessibility = pill("Accessibility", false, true);
        accessibility.setContentDescription("Open accessibility settings");
        accessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        automationRow.addView(accessibility, weighted(dp(5)));
        TextView floating = pill("Floating controls", true, true);
        floating.setContentDescription("Open floating controls");
        floating.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFloatingControls();
            }
        });
        automationRow.addView(floating, weighted(dp(5)));
        root.addView(automationRow);

        accessibilityState = label("", 10f, MUTED);
        accessibilityState.setPadding(0, dp(10), 0, 0);
        root.addView(accessibilityState);

        TextView hint = label(
                "1 · Enable the accessibility service  2 · Choose an image and generate  3 · Open the "
                        + "floating controls, select the drawing area on Instagram  4 · Start drawing. "
                        + "Pick a thin brush in Instagram first; the app never taps anything outside "
                        + "your selected rectangle.",
                10f, 0x88FFFFFF);
        hint.setPadding(0, dp(12), 0, 0);
        hint.setLineSpacing(dp(3), 1f);
        root.addView(hint);

        setContentView(scroll);
    }

    private interface SettingValue {
        void changed(int value);
    }

    private View settingSlider(String title, int min, int max, int value,
                               final SettingValue callback) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(5), 0, dp(3));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(title, 12f, MUTED);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(22), 1f));
        final TextView number = label(String.valueOf(value), 12f, TEXT);
        row.addView(number);
        block.addView(row);
        SeekBar slider = new SeekBar(this);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
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
        block.addView(slider, new LinearLayout.LayoutParams(-1, dp(35)));
        return block;
    }

    private LinearLayout.LayoutParams weighted(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
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
        TextView view = label(text.toUpperCase(), 10f, MUTED);
        view.setLetterSpacing(0.12f);
        view.setPadding(0, dp(22), 0, dp(10));
        return view;
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
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                surface(background, radiusDp, 0f, 0),
                surface(0xFFFFFFFF, radiusDp, 0f, 0));
    }

    private TextView pill(String text, boolean primary, boolean compact) {
        TextView view = label(text, compact ? 12f : 12.5f, primary ? 0xFF06131F : TEXT);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (primary) {
            view.setBackground(ripple(ACCENT, 16));
        } else {
            view.setBackground(ripple(CHIP, 16));
            view.setTextColor(TEXT);
        }
        return view;
    }

    private LinearLayout segmented(final String[] labels, int selected, final int kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(2));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = label(labels[i], 11.5f, MUTED);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            params.leftMargin = i == 0 ? 0 : dp(3);
            row.addView(chip, params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (kind == 0) {
                        settings.detailLevel = index;
                        styleSegments(detailChips, settings.detailLevel);
                    } else if (kind == 1) {
                        settings.fitMode = index;
                        styleSegments(fitChips, settings.fitMode);
                    } else {
                        settings.renderMode = index;
                        styleSegments(modeChips, settings.renderMode);
                    }
                    persist(kind != 1);
                }
            });
        }
        styleSegments(row, selected);
        return row;
    }

    private void styleSegments(LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView chip = (TextView) row.getChildAt(i);
            boolean active = i == selected;
            chip.setTextColor(active ? ACCENT : MUTED);
            chip.setBackground(active ? surface(ACCENT_SOFT, 13, 1f, 0x5562B0FF)
                    : ripple(CHIP, 13));
        }
    }

    private TextView toggle(String text, boolean enabled) {
        TextView view = label(text, 11f, MUTED);
        view.setGravity(Gravity.CENTER);
        styleToggle(view, enabled);
        return view;
    }

    private void styleToggle(TextView view, boolean enabled) {
        view.setText(enabled ? "✓  Center image" : "Center image");
        view.setTextColor(enabled ? ACCENT : MUTED);
        view.setBackground(enabled ? surface(ACCENT_SOFT, 11, 1f, 0x557CC6FF)
                : ripple(CHIP, 11));
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
            Bitmap decoded = SketchSession.loadSource(stream,
                    SketchProcessor.MAX_SOURCE_DIMENSION);
            if (decoded == null) {
                throw new IllegalStateException("Unsupported image");
            }
            Bitmap prepared = SketchSession.setSource(decoded);
            if (prepared != decoded && !decoded.isRecycled()) {
                decoded.recycle();
            }
            source = prepared;
            SketchSession.setSketch(Sketch.empty());
            preview.setSource(source);
            preview.setSketch(Sketch.empty(), settings);
            status.setText("Image loaded · tap Generate sketch");
            meta.setText(decodedWidth(prepared) + " × " + decodedHeight(prepared) + " source");
        } catch (Exception exception) {
            status.setText("Unable to load image: " + exception.getMessage());
        }
    }

    private int decodedWidth(Bitmap bitmap) {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    private int decodedHeight(Bitmap bitmap) {
        return bitmap == null ? 0 : bitmap.getHeight();
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SketchProcessor.Outcome outcome = SketchProcessor.process(source, snapshot,
                        canvasWidth, active);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
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
                    }
                });
            }
        }, "sketch-process").start();
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
        settings = SettingsStore.load(this);
        SketchSession.updateSettings(settings);
        source = SketchSession.source();
        if (captionInput != null && !captionInput.hasFocus())
            captionInput.setText(settings.captionText);
        styleSegments(modeChips, settings.renderMode);
        styleSegments(detailChips, settings.detailLevel);
        styleSegments(fitChips, settings.fitMode);
        styleToggle(centerToggle, settings.centerImage);
        refreshFromSession();
        DrawService service = DrawService.instance;
        if (accessibilityState != null) {
            if (service == null) {
                accessibilityState.setText("Accessibility service is off");
                accessibilityState.setTextColor(0xFFFFA36C);
            } else if (service.isDrawingActive()) {
                accessibilityState.setText("Accessibility service is on · drawing in progress");
                accessibilityState.setTextColor(ACCENT);
            } else {
                accessibilityState.setText("Accessibility service is on");
                accessibilityState.setTextColor(0xFF6AD79A);
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
