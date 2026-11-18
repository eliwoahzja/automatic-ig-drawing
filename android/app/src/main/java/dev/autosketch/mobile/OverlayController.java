package dev.autosketch.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * All floating UI: a compact translucent control panel (draggable by its header,
 * resizable from its corner, minimisable to a small icon), the settings page and
 * a pass-through selection frame with a live stroke preview.
 */
public final class OverlayController {

    public interface Listener {
        DrawingController controller();

        Sketch sketch();

        DrawingSettings settings();

        CanvasMapper.Rect drawingArea();

        void onSettingsChanged(DrawingSettings settings, boolean needsReprocessing);

        void onAreaConfirmed(CanvasMapper.Rect area);

        void onStartRequested();

        void onPauseRequested();

        void onResumeRequested();

        void onStopRequested();

        void onCloseRequested();

        int currentColorLayer();

        void onNextColorRequested();
    }

    private static final int SURFACE = 0xF3171921;
    private static final int BORDER = 0x1FFFFFFF;
    private static final int CHIP = 0xFF292D37;
    private static final int ACCENT = 0xFF83BCFF;
    private static final int ACCENT_SOFT = 0x334D96FF;
    private static final int TEXT = 0xFFF3F5F9;
    private static final int MUTED = 0xFFA0A5B4;
    private static final int DANGER = 0xFFFF8A8A;

    private static final int MODE_NONE = 0;
    private static final int MODE_SELECT = 1;
    private static final int MODE_PREVIEW = 2;

    private final Context context;
    private final Listener listener;
    private final WindowManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DrawingSettings working = new DrawingSettings();

    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private LinearLayout controlsPage;
    private LinearLayout settingsPage;
    private ScrollView bodyScroll;
    private TextView statusText;
    private TextView dimsText;
    private ProgressBarView progressBar;
    private TextView settingsButtonLabel;
    private TextView previewButtonLabel;
    private LinearLayout colorModeRow;
    private TextView colorStepLabel;
    private TextView nextColorButton;

    private View miniIcon;
    private WindowManager.LayoutParams miniParams;
    private MiniIconView miniView;

    private View frameView;
    private SelectionView selectionView;
    private WindowManager.LayoutParams frameParams;
    private View moveHandle;
    private View topLeft;
    private View topRight;
    private View bottomLeft;
    private View bottomRight;
    private LinearLayout actionBar;
    private TextView previewToggle;
    private WindowManager.LayoutParams actionParams;

    private CanvasMapper.Rect selection;
    private int overlayMode = MODE_NONE;
    private boolean previewVisible;
    private boolean panelWasVisible;
    private boolean settingsOpen;
    private boolean minimized;

    private int panelX = -1;
    private int panelY = -1;
    private int panelWidth;
    private int panelHeight;
    private int miniX = -1;
    private int miniY = -1;
    private float overrideProgress = -1f;
    private String overrideStatus;

    private boolean pendingReprocess;
    private boolean settingsCallbackScheduled;

    private final Runnable settingsDispatch = new Runnable() {
        @Override
        public void run() {
            settingsCallbackScheduled = false;
            boolean reprocess = pendingReprocess;
            pendingReprocess = false;
            listener.onSettingsChanged(working.copy(), reprocess);
        }
    };

    public OverlayController(Context context, Listener listener, WindowManager manager) {
        this.context = context;
        this.listener = listener;
        this.manager = manager;
        panelX = SettingsStore.overlayX(context, -1);
        panelY = SettingsStore.overlayY(context, -1);
        panelWidth = SettingsStore.overlayWidth(context, 0);
        panelHeight = SettingsStore.overlayHeight(context, 0);
        miniX = SettingsStore.miniX(context, -1);
        miniY = SettingsStore.miniY(context, -1);
    }

    // ---------------------------------------------------------------- geometry

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int screenWidth() {
        return displayPoint().x;
    }

    private int screenHeight() {
        return displayPoint().y;
    }

    private Point displayPoint() {
        Point point = new Point();
        manager.getDefaultDisplay().getRealSize(point);
        return point;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        return params;
    }

    private void addWindow(View view, WindowManager.LayoutParams params) {
        try {
            manager.addView(view, params);
        } catch (RuntimeException ignored) {
            // Window already attached or service tearing down.
        }
    }

    private void removeWindow(View view) {
        if (view == null) {
            return;
        }
        try {
            manager.removeView(view);
        } catch (RuntimeException ignored) {
            // Already detached.
        }
    }

    // ------------------------------------------------------------------- style

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
        GradientDrawable content = surface(background, radiusDp, 0f, 0);
        GradientDrawable mask = surface(0xFFFFFFFF, radiusDp, 0f, 0);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    private TextView label(String text, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView chipButton(String text, float sizeSp, int color) {
        TextView view = label(text, sizeSp, color);
        view.setGravity(Gravity.CENTER);
        view.setBackground(ripple(CHIP, 11));
        view.setPadding(dp(6), 0, dp(6), 0);
        view.setContentDescription(text);
        return view;
    }

    // ------------------------------------------------------------------- panel

    public void showControls() {
        reloadSettings();
        if (panel != null) {
            refresh();
            return;
        }
        if (minimized && miniIcon != null) {
            restoreFromMini();
            return;
        }
        showPanel();
    }

    private void reloadSettings() {
        DrawingSettings current = listener.settings();
        if (current != null) {
            copyInto(current, working);
        }
    }

    private static void copyInto(DrawingSettings from, DrawingSettings to) {
        DrawingSettings copy = from.copy();
        to.detailLevel = copy.detailLevel;
        to.colorDensity = copy.colorDensity;
        to.captionText = copy.captionText;
        to.captionXPercent = copy.captionXPercent;
        to.captionYPercent = copy.captionYPercent;
        to.captionSizePercent = copy.captionSizePercent;
        to.captionReplaceOriginal = copy.captionReplaceOriginal;
        to.renderMode = copy.renderMode;
        to.noiseReduction = copy.noiseReduction;
        to.edgeSensitivity = copy.edgeSensitivity;
        to.contrast = copy.contrast;
        to.brightness = copy.brightness;
        to.invert = copy.invert;
        to.backgroundSuppression = copy.backgroundSuppression;
        to.smoothing = copy.smoothing;
        to.simplification = copy.simplification;
        to.strokeOverlapReduction = copy.strokeOverlapReduction;
        to.pointSpacingPx = copy.pointSpacingPx;
        to.minStrokeLengthPx = copy.minStrokeLengthPx;
        to.maxStrokeDurationMs = copy.maxStrokeDurationMs;
        to.drawingSpeedPxPerSec = copy.drawingSpeedPxPerSec;
        to.strokeGapMs = copy.strokeGapMs;
        to.fitMode = copy.fitMode;
        to.centerImage = copy.centerImage;
        to.showProgressPreview = copy.showProgressPreview;
    }

    private void showPanel() {
        if (panel != null) {
            return;
        }
        minimized = false;
        Point screen = displayPoint();
        int width = panelWidth > 0 ? panelWidth : dp(246);
        int height = panelHeight > 0 ? panelHeight : dp(266);
        width = clamp(width, dp(196), Math.max(dp(196), screen.x - dp(8)));
        height = clamp(height, dp(150), Math.max(dp(150), screen.y - dp(64)));
        panelWidth = width;
        panelHeight = height;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(11), dp(9), dp(11), dp(8));
        GradientDrawable background = surface(SURFACE, 20, 1f, BORDER);
        root.setBackground(background);
        root.setElevation(dp(10f));

        root.addView(buildHeader(screen, width), new LinearLayout.LayoutParams(-1, dp(28)));

        bodyScroll = new ScrollView(context);
        bodyScroll.setFillViewport(true);
        bodyScroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        controlsPage = buildControlsPage();
        settingsPage = buildSettingsPage();
        settingsPage.setVisibility(View.GONE);
        body.addView(controlsPage, new LinearLayout.LayoutParams(-1, -2));
        body.addView(settingsPage, new LinearLayout.LayoutParams(-1, -2));
        bodyScroll.addView(body);
        root.addView(bodyScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildFooter(), new LinearLayout.LayoutParams(-1, dp(20)));

        panel = root;
        panelParams = overlayParams(width, height, true);
        int clampedX = panelX < 0 ? screen.x - width - dp(10) : panelX;
        int clampedY = panelY < 0 ? dp(88) : panelY;
        panelParams.x = clamp(clampedX, dp(2), Math.max(dp(2), screen.x - width - dp(2)));
        panelParams.y = clamp(clampedY, dp(2), Math.max(dp(2), screen.y - height - dp(2)));
        panelX = panelParams.x;
        panelY = panelParams.y;
        addWindow(panel, panelParams);
        refresh();
    }

    private View buildHeader(final Point screen, final int width) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(context);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(ACCENT);
        dot.setBackground(dotShape);
        header.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));

        TextView title = label("Auto Sketch", 11.5f, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1f);
        titleParams.leftMargin = dp(6);
        header.addView(title, titleParams);

        TextView minimize = chipButton("–", 15f, MUTED);
        header.addView(minimize, new LinearLayout.LayoutParams(dp(26), dp(24)));
        TextView close = chipButton("✕", 10f, MUTED);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(26), dp(24));
        closeParams.leftMargin = dp(4);
        header.addView(close, closeParams);

        minimize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                minimizePanel();
            }
        });
        minimize.setContentDescription("Minimize panel");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onStopRequested();
                listener.onCloseRequested();
                hideAll();
            }
        });
        close.setContentDescription("Close controls");

        header.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int originX;
            private int originY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (panelParams == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        originX = panelParams.x;
                        originY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point current = displayPoint();
                        int w = panel != null ? panel.getWidth() : panelWidth;
                        int h = panel != null ? panel.getHeight() : panelHeight;
                        panelParams.x = clamp(originX + Math.round(event.getRawX() - downX),
                                dp(2), Math.max(dp(2), current.x - Math.max(1, w) - dp(2)));
                        panelParams.y = clamp(originY + Math.round(event.getRawY() - downY),
                                dp(2), Math.max(dp(2), current.y - Math.max(1, h) - dp(2)));
                        if (panel != null) {
                            try {
                                manager.updateViewLayout(panel, panelParams);
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveGeometry();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return header;
    }

    private LinearLayout buildControlsPage() {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusText = label("No sketch yet", 9.5f, MUTED);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, dp(16), 1f));
        dimsText = label("", 8.5f, ACCENT);
        dimsText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        statusRow.addView(dimsText, new LinearLayout.LayoutParams(-2, dp(16)));
        page.addView(statusRow, new LinearLayout.LayoutParams(-1, dp(16)));

        progressBar = new ProgressBarView();
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(8));
        barParams.topMargin = dp(1);
        page.addView(progressBar, barParams);

        LinearLayout firstRow = row();
        firstRow.addView(actionButton("▶", "Start", new Runnable() {
            @Override
            public void run() {
                listener.onStartRequested();
                if (listener.controller().getState() == DrawingController.State.DRAWING) {
                    minimizePanel();
                }
            }
        }), weight());
        firstRow.addView(actionButton("❚❚", "Pause", new Runnable() {
            @Override
            public void run() {
                listener.onPauseRequested();
                refresh();
            }
        }), weight());
        firstRow.addView(actionButton("▶❙", "Resume", new Runnable() {
            @Override
            public void run() {
                listener.onResumeRequested();
                refresh();
            }
        }), weight());
        firstRow.addView(actionButton("■", "Stop", new Runnable() {
            @Override
            public void run() {
                listener.onStopRequested();
                refresh();
            }
        }), weight());
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(-1, dp(44));
        firstParams.topMargin = dp(6);
        page.addView(firstRow, firstParams);

        LinearLayout secondRow = row();
        secondRow.addView(actionButton("⬚", "Area", new Runnable() {
            @Override
            public void run() {
                openSelection();
            }
        }), weight());
        View previewButton = actionButton("◌", "Preview", new Runnable() {
            @Override
            public void run() {
                togglePreview();
            }
        });
        previewButtonLabel = findLabel(previewButton);
        secondRow.addView(previewButton, weight());
        View settingsButton = actionButton("⚙", "Settings", new Runnable() {
            @Override
            public void run() {
                setSettingsOpen(!settingsOpen);
            }
        });
        settingsButtonLabel = findLabel(settingsButton);
        secondRow.addView(settingsButton, weight());
        secondRow.addView(actionButton("—", "Minimize", new Runnable() {
            @Override
            public void run() {
                minimizePanel();
            }
        }), weight());
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-1, dp(44));
        secondParams.topMargin = dp(4);
        page.addView(secondRow, secondParams);

        LinearLayout colorRow = row();
        colorModeRow = colorRow;
        colorRow.setPadding(dp(4), dp(6), dp(2), 0);
        colorStepLabel = label("", 9.5f, MUTED);
        colorStepLabel.setSingleLine(true);
        colorStepLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        colorRow.addView(colorStepLabel, new LinearLayout.LayoutParams(0, dp(34), 1f));
        nextColorButton = chipButton("Next color", 9f, ACCENT);
        nextColorButton.setOnClickListener(v -> listener.onNextColorRequested());
        colorRow.addView(nextColorButton, new LinearLayout.LayoutParams(dp(75), dp(30)));
        page.addView(colorRow, new LinearLayout.LayoutParams(-1, dp(40)));
        return page;
    }

    private TextView findLabel(View group) {
        if (!(group instanceof LinearLayout)) {
            return null;
        }
        LinearLayout layout = (LinearLayout) group;
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof TextView && i == layout.getChildCount() - 1) {
                return (TextView) child;
            }
        }
        return null;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private View actionButton(String glyph, String text, final Runnable action) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(ripple(text.equals("Start") ? 0xFF285FA4 : CHIP, 12));
        box.setPadding(0, dp(4), 0, dp(3));
        box.setContentDescription(text);
        box.setFocusable(true);
        TextView symbol = label(glyph, 15f, TEXT);
        symbol.setGravity(Gravity.CENTER);
        TextView caption = label(text, 8.5f, text.equals("Start") ? 0xFFE8F4FF : MUTED);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        box.addView(symbol, new LinearLayout.LayoutParams(-1, dp(16)));
        box.addView(caption, new LinearLayout.LayoutParams(-1, dp(12)));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return box;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = label("drag header · resize", 8f, 0x66FFFFFF);
        footer.addView(hint, new LinearLayout.LayoutParams(0, -1, 1f));
        GripView grip = new GripView();
        grip.setContentDescription("Resize panel");
        footer.addView(grip, new LinearLayout.LayoutParams(dp(22), dp(18)));
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startWidth;
            private int startHeight;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (panelParams == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startWidth = panelParams.width;
                        startHeight = panelParams.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point screen = displayPoint();
                        int maxWidth = Math.max(dp(196), screen.x - panelParams.x - dp(4));
                        int maxHeight = Math.max(dp(150), screen.y - panelParams.y - dp(4));
                        panelParams.width = clamp(startWidth + Math.round(event.getRawX() - downX),
                                dp(196), maxWidth);
                        panelParams.height = clamp(startHeight + Math.round(event.getRawY() - downY),
                                dp(150), maxHeight);
                        if (panel != null) {
                            try {
                                manager.updateViewLayout(panel, panelParams);
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (panelParams != null) {
                            panelWidth = panelParams.width;
                            panelHeight = panelParams.height;
                        }
                        saveGeometry();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return footer;
    }

    // ---------------------------------------------------------------- settings

    private interface ValueSink {
        void onValue(int value, boolean reprocess);
    }

    private LinearLayout buildSettingsPage() {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(4), 0, dp(2));

        LinearLayout titleRow = row();
        TextView title = label("Settings", 10f, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(20), 1f));
        TextView back = chipButton("Done", 9f, ACCENT);
        back.setContentDescription("Close settings");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSettingsOpen(false);
            }
        });
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(48), dp(20)));
        page.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(22)));

        page.addView(sectionLabel("Render mode"));
        page.addView(segmented(DrawingSettings.MODE_NAMES, working.renderMode, true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.renderMode = value;
                    }
                }));
        page.addView(sectionLabel("Color fills"));
        page.addView(slider("Fill coverage", 15, 95, working.colorDensity, "%", true,
                new ValueSink() {
                    @Override public void onValue(int value, boolean reprocess) {
                        working.colorDensity = value;
                    }
                }));
        page.addView(sectionLabel("Detail"));
        page.addView(segmented(DrawingSettings.DETAIL_NAMES, working.detailLevel, true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.detailLevel = value;
                    }
                }));
        page.addView(slider("Edge sensitivity", 0, 100, working.edgeSensitivity, "%", true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.edgeSensitivity = value;
                    }
                }));
        page.addView(slider("Noise reduction", 0, 100, working.noiseReduction, "%", true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.noiseReduction = value;
                    }
                }));
        page.addView(toggleRow("Invert colors", working.invert, true, new ValueSink() {
            @Override
            public void onValue(int value, boolean reprocess) {
                working.invert = value == 1;
            }
        }));
        page.addView(slider("Background suppression", 0, 100, working.backgroundSuppression, "%",
                true, new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.backgroundSuppression = value;
                    }
                }));
        page.addView(slider("Contrast", -100, 100, working.contrast, "", true, new ValueSink() {
            @Override
            public void onValue(int value, boolean reprocess) {
                working.contrast = value;
            }
        }));
        page.addView(slider("Brightness", -100, 100, working.brightness, "", true, new ValueSink() {
            @Override
            public void onValue(int value, boolean reprocess) {
                working.brightness = value;
            }
        }));

        page.addView(sectionLabel("Stroke"));
        page.addView(slider("Drawing speed", 150, 1600, working.drawingSpeedPxPerSec, "px/s",
                false, new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.drawingSpeedPxPerSec = value;
                    }
                }));
        page.addView(slider("Stroke smoothing", 0, 100, working.smoothing, "%", true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.smoothing = value;
                    }
                }));
        page.addView(slider("Minimum stroke length", 2, 40, working.minStrokeLengthPx, "px", false,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.minStrokeLengthPx = value;
                    }
                }));
        page.addView(slider("Maximum stroke duration", 400, 6000, working.maxStrokeDurationMs, "ms",
                false, new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.maxStrokeDurationMs = value;
                    }
                }));
        page.addView(slider("Point spacing", 1, 12, working.pointSpacingPx, "px", false,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.pointSpacingPx = value;
                    }
                }));
        page.addView(slider("Simplification", 0, 100, working.simplification, "%", true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.simplification = value;
                    }
                }));
        page.addView(slider("Overlap reduction", 0, 100, working.strokeOverlapReduction, "%", true,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.strokeOverlapReduction = value;
                    }
                }));

        page.addView(sectionLabel("Canvas"));
        page.addView(segmented(DrawingSettings.FIT_NAMES, working.fitMode, false, new ValueSink() {
            @Override
            public void onValue(int value, boolean reprocess) {
                working.fitMode = value;
            }
        }));
        page.addView(toggleRow("Center image", working.centerImage, false, new ValueSink() {
            @Override
            public void onValue(int value, boolean reprocess) {
                working.centerImage = value == 1;
            }
        }));
        page.addView(toggleRow("Highlight drawn strokes", working.showProgressPreview, false,
                new ValueSink() {
                    @Override
                    public void onValue(int value, boolean reprocess) {
                        working.showProgressPreview = value == 1;
                    }
                }));
        return page;
    }

    private TextView sectionLabel(String text) {
        TextView view = label(text.toUpperCase(), 8f, 0x88FFFFFF);
        view.setLetterSpacing(0.12f);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private View slider(final String name, final int min, final int max, int value,
                        final String suffix, final boolean reprocess, final ValueSink sink) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(3), 0, 0);

        LinearLayout header = row();
        header.addView(label(name, 9.5f, MUTED), new LinearLayout.LayoutParams(0, dp(14), 1f));
        final TextView valueLabel = label(formatValue(value, suffix), 9.5f, TEXT);
        valueLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(valueLabel, new LinearLayout.LayoutParams(-2, dp(14)));
        block.addView(header, new LinearLayout.LayoutParams(-1, dp(14)));

        SeekBar bar = new SeekBar(context);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(clamp(value, min, max));
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbOffset(dp(4));
        bar.setPadding(0, 0, 0, 0);
        bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                valueLabel.setText(formatValue(progress, suffix));
                sink.onValue(progress, reprocess);
                scheduleSettings(reprocess);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        block.addView(bar, new LinearLayout.LayoutParams(-1, dp(24)));
        return block;
    }

    private String formatValue(int value, String suffix) {
        return suffix.isEmpty() ? String.valueOf(value) : value + suffix;
    }

    private View segmented(final String[] labels, int selected, final boolean reprocess,
                           final ValueSink sink) {
        final LinearLayout row = row();
        row.setPadding(0, dp(2), 0, dp(2));
        final TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            final TextView chip = chipButton(labels[i], 9f, MUTED);
            chip.setBackground(ripple(CHIP, 8));
            chips[i] = chip;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(26), 1f);
            params.leftMargin = i == 0 ? 0 : dp(3);
            row.addView(chip, params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int k = 0; k < chips.length; k++) {
                        boolean active = k == index;
                        chips[k].setTextColor(active ? ACCENT : MUTED);
                        chips[k].setBackground(active ? surface(ACCENT_SOFT, 8, 1f, 0x557CC6FF)
                                : ripple(CHIP, 8));
                    }
                    sink.onValue(index, reprocess);
                    scheduleSettings(reprocess);
                }
            });
        }
        for (int i = 0; i < chips.length; i++) {
            boolean active = i == selected;
            chips[i].setTextColor(active ? ACCENT : MUTED);
            chips[i].setBackground(active ? surface(ACCENT_SOFT, 8, 1f, 0x557CC6FF)
                    : ripple(CHIP, 8));
        }
        return row;
    }

    private View toggleRow(final String name, boolean enabled, final boolean reprocess,
                           final ValueSink sink) {
        final LinearLayout row = row();
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(label(name, 9.5f, MUTED), new LinearLayout.LayoutParams(0, dp(20), 1f));
        final TextView pill = chipButton(enabled ? "On" : "Off", 9f, enabled ? ACCENT : MUTED);
        pill.setBackground(surface(enabled ? ACCENT_SOFT : CHIP, 8, 1f,
                enabled ? 0x557CC6FF : 0x00000000));
        pill.setContentDescription(name);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(20));
        row.addView(pill, params);
        final boolean[] state = {enabled};
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                pill.setText(state[0] ? "On" : "Off");
                pill.setTextColor(state[0] ? ACCENT : MUTED);
                pill.setBackground(surface(state[0] ? ACCENT_SOFT : CHIP, 8, 1f,
                        state[0] ? 0x557CC6FF : 0x00000000));
                sink.onValue(state[0] ? 1 : 0, reprocess);
                scheduleSettings(reprocess);
            }
        });
        return row;
    }

    private void scheduleSettings(boolean reprocess) {
        pendingReprocess |= reprocess;
        if (reprocess && settingsPage != null && settingsPage.getVisibility() == View.VISIBLE) {
            showTransientStatus("Updating preview…");
        }
        if (!settingsCallbackScheduled) {
            settingsCallbackScheduled = true;
            handler.postDelayed(settingsDispatch, reprocess ? 380 : 140);
        }
    }

    private void setSettingsOpen(boolean open) {
        settingsOpen = open;
        if (controlsPage == null || settingsPage == null) {
            return;
        }
        controlsPage.setVisibility(open ? View.GONE : View.VISIBLE);
        settingsPage.setVisibility(open ? View.VISIBLE : View.GONE);
        if (settingsButtonLabel != null) {
            settingsButtonLabel.setText(open ? "Done" : "Settings");
        }
        if (open && panelParams != null) {
            int wanted = Math.max(panelParams.height, dp(350));
            int maxHeight = Math.max(dp(150), screenHeight() - panelParams.y - dp(6));
            panelParams.height = Math.min(wanted, maxHeight);
            try {
                manager.updateViewLayout(panel, panelParams);
            } catch (RuntimeException ignored) {
            }
        }
        if (bodyScroll != null) {
            bodyScroll.scrollTo(0, 0);
        }
    }

    // ------------------------------------------------------------------- status

    public void refresh() {
        DrawingController controller = listener.controller();
        if (controller == null) {
            return;
        }
        DrawingController.State state = controller.getState();
        String text;
        switch (state) {
            case DRAWING:
                text = "Drawing " + controller.getIndex() + " / " + controller.getStrokeCount();
                break;
            case PAUSED:
                text = "Paused · " + Math.round(controller.getProgress() * 100f) + "%";
                break;
            case COMPLETED:
                text = "Completed · " + controller.getStrokeCount() + " strokes";
                break;
            case CANCELLED:
                text = "Stopped";
                break;
            case ERROR:
                text = controller.getMessage();
                break;
            case PROCESSING:
                text = "Processing image…";
                break;
            case READY:
                text = "Ready · " + listener.sketch().strokes.size() + " strokes";
                break;
            case IDLE:
            default:
                text = listener.sketch().isEmpty() ? "No sketch yet" : "Ready";
                break;
        }
        if (state == DrawingController.State.COMPLETED &&
                listener.sketch() != null && !listener.sketch().colorLayers.isEmpty()) {
            int step = clamp(listener.currentColorLayer(), 0,
                    listener.sketch().colorLayers.size() - 1);
            text = step < listener.sketch().colorLayers.size() - 1
                    ? "Layer done · change brush, tap Next color"
                    : "All color layers completed";
        }
        if (overrideStatus != null && (state == DrawingController.State.IDLE
                || state == DrawingController.State.READY
                || state == DrawingController.State.PROCESSING)) {
            text = overrideStatus;
        }
        if (statusText != null) {
            statusText.setText(text);
            statusText.setTextColor(state == DrawingController.State.ERROR ? DANGER : MUTED);
        }
        float progress = overrideProgress >= 0 ? overrideProgress : controller.getProgress();
        if (progressBar != null) {
            progressBar.setProgress(progress);
        }
        if (dimsText != null) {
            CanvasMapper.Rect area = listener.drawingArea();
            DrawingSettings settings = listener.settings();
            if (area == null) {
                dimsText.setText("no area");
            } else {
                dimsText.setText(Math.round(area.width()) + "×" + Math.round(area.height())
                        + " · " + DrawingSettings.FIT_NAMES[clamp(settings.fitMode, 0, 2)]);
            }
        }
        Sketch sketch = listener.sketch();
        boolean hasColors = sketch != null && !sketch.colorLayers.isEmpty();
        int currentColor = hasColors ? clamp(listener.currentColorLayer(), 0,
                sketch.colorLayers.size() - 1) : 0;
        if (colorModeRow != null) {
            colorModeRow.setVisibility(hasColors ? View.VISIBLE : View.GONE);
        }
        if (colorStepLabel != null) {
            colorStepLabel.setVisibility(hasColors ? View.VISIBLE : View.GONE);
            if (hasColors) {
                ColorLayer layer = sketch.colorLayers.get(currentColor);
                colorStepLabel.setText((currentColor + 1) + "/" + sketch.colorLayers.size()
                        + " · " + layer.name);
            }
        }
        if (nextColorButton != null) {
            nextColorButton.setVisibility(hasColors ? View.VISIBLE : View.GONE);
            nextColorButton.setEnabled(hasColors &&
                    controller.getState() == DrawingController.State.COMPLETED &&
                    currentColor < sketch.colorLayers.size() - 1);
            nextColorButton.setAlpha(nextColorButton.isEnabled() ? 1f : .35f);
        }
        if (miniView != null) {
            miniView.setProgress(progress);
            miniView.setActive(state == DrawingController.State.DRAWING);
        }
        if (previewButtonLabel != null) {
            previewButtonLabel.setText(previewVisible ? "Hide" : "Preview");
            previewButtonLabel.setTextColor(previewVisible ? ACCENT : MUTED);
        }
        if (frameView != null) {
            frameView.invalidate();
        }
        if (panel != null) {
            panel.invalidate();
        }
    }

    public void setStatus(String text) {
        overrideStatus = text;
        overrideProgress = -1;
        refresh();
    }

    public void setProcessingProgress(String text) {
        overrideStatus = text;
        overrideProgress = 0f;
        refresh();
    }

    public void clearOverride() {
        overrideStatus = null;
        overrideProgress = -1;
        refresh();
    }

    private void showTransientStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
    }

    // ----------------------------------------------------------------- dragging

    private void saveGeometry() {
        if (panelParams != null) {
            panelX = panelParams.x;
            panelY = panelParams.y;
            panelWidth = panelParams.width;
            panelHeight = panelParams.height;
        }
        if (miniParams != null) {
            miniX = miniParams.x;
            miniY = miniParams.y;
        }
        SettingsStore.saveOverlayGeometry(context, panelX, panelY, panelWidth, panelHeight,
                minimized, miniX, miniY);
    }

    private void minimizePanel() {
        if (panel == null) {
            return;
        }
        saveGeometry();
        removeWindow(panel);
        panel = null;
        panelParams = null;
        controlsPage = null;
        settingsPage = null;
        bodyScroll = null;
        statusText = null;
        dimsText = null;
        progressBar = null;
        settingsButtonLabel = null;
        previewButtonLabel = null;
        minimized = true;
        showMiniIcon();
        saveGeometry();
    }

    private void showMiniIcon() {
        if (miniIcon != null) {
            return;
        }
        Point screen = displayPoint();
        int size = dp(50);
        miniView = new MiniIconView();
        miniView.setContentDescription("Open Auto Sketch controls");
        miniView.setProgress(listener.controller().getProgress());
        final int[] position = new int[]{miniX, miniY};
        if (position[0] < 0 || position[1] < 0) {
            position[0] = panelX >= 0 ? panelX : screen.x - size - dp(10);
            position[1] = panelY >= 0 ? panelY : dp(88);
        }
        position[0] = clamp(position[0], dp(2), Math.max(dp(2), screen.x - size - dp(2)));
        position[1] = clamp(position[1], dp(2), Math.max(dp(2), screen.y - size - dp(2)));
        miniParams = overlayParams(size, size, true);
        miniParams.x = position[0];
        miniParams.y = position[1];
        miniIcon = miniView;
        addWindow(miniIcon, miniParams);
        miniView.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int originX;
            private int originY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        originX = miniParams.x;
                        originY = miniParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                            moved = true;
                        }
                        if (moved) {
                            Point screen = displayPoint();
                            int size = miniParams.width;
                            miniParams.x = clamp(originX + Math.round(dx), dp(2),
                                    Math.max(dp(2), screen.x - size - dp(2)));
                            miniParams.y = clamp(originY + Math.round(dy), dp(2),
                                    Math.max(dp(2), screen.y - size - dp(2)));
                            try {
                                manager.updateViewLayout(miniIcon, miniParams);
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved) {
                            restoreFromMini();
                        } else {
                            miniX = miniParams.x;
                            miniY = miniParams.y;
                            saveGeometry();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void restoreFromMini() {
        removeWindow(miniIcon);
        miniIcon = null;
        miniView = null;
        miniParams = null;
        minimized = false;
        showPanel();
        saveGeometry();
    }

    public void hideAll() {
        closeSelection();
        removePreviewWindow();
        if (panel != null) {
            saveGeometry();
            removeWindow(panel);
        }
        panel = null;
        panelParams = null;
        controlsPage = null;
        settingsPage = null;
        bodyScroll = null;
        statusText = null;
        dimsText = null;
        progressBar = null;
        settingsButtonLabel = null;
        previewButtonLabel = null;
        removeWindow(miniIcon);
        miniIcon = null;
        miniView = null;
        miniParams = null;
        minimized = false;
    }

    public boolean isVisible() {
        return panel != null || miniIcon != null || frameView != null;
    }

    /** Restores the panel after a finished drawing, but only if it was minimised. */
    public void onDrawingCompleted() {
        if (minimized && miniIcon != null) {
            restoreFromMini();
        }
        refresh();
    }

    // ---------------------------------------------------------------- selection

    public void openSelection() {
        if (frameView != null && overlayMode == MODE_SELECT) {
            closeSelection();
            return;
        }
        listener.onPauseRequested();
        panelWasVisible = panel != null;
        saveGeometry();
        removeWindow(panel);
        panel = null;
        panelParams = null;
        controlsPage = null;
        settingsPage = null;
        bodyScroll = null;
        statusText = null;
        dimsText = null;
        progressBar = null;
        settingsButtonLabel = null;
        previewButtonLabel = null;
        removeWindow(miniIcon);
        miniIcon = null;
        miniView = null;
        miniParams = null;
        removePreviewWindow();
        closeSelection();

        Point screen = displayPoint();
        CanvasMapper.Rect existing = listener.drawingArea();
        if (existing != null && existing.width() > dp(60) && existing.height() > dp(60)) {
            selection = new CanvasMapper.Rect(existing);
        } else {
            float width = screen.x * 0.74f;
            float height = screen.y * 0.5f;
            selection = new CanvasMapper.Rect((screen.x - width) / 2f, screen.y * 0.26f,
                    (screen.x + width) / 2f, screen.y * 0.26f + height);
        }
        clampSelection();
        previewVisible = true;
        overlayMode = MODE_SELECT;

        selectionView = new SelectionView();
        frameView = selectionView;
        frameParams = overlayParams(-1, -1, false);
        selectionView.setMode(MODE_SELECT);
        addWindow(frameView, frameParams);

        moveHandle = handle("⋮⋮", 0, dp(74), dp(26));
        topLeft = handle("↖", 1, dp(40), dp(40));
        topRight = handle("↗", 2, dp(40), dp(40));
        bottomLeft = handle("↙", 3, dp(40), dp(40));
        bottomRight = handle("↘", 4, dp(40), dp(40));

        actionBar = buildSelectionActions();
        actionParams = overlayParams(dp(236), dp(36), true);
        addWindow(actionBar, actionParams);
        layoutSelectionWindows();
    }

    private LinearLayout buildSelectionActions() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(3), dp(4), dp(3));
        bar.setBackground(surface(SURFACE, 14, 1f, BORDER));
        TextView apply = chipButton("Use area", 9.5f, ACCENT);
        apply.setContentDescription("Use selected area");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CanvasMapper.Rect area = new CanvasMapper.Rect(selection);
                closeSelection();
                listener.onAreaConfirmed(area);
                if (panelWasVisible) {
                    showPanel();
                }
            }
        });
        bar.addView(apply, new LinearLayout.LayoutParams(0, dp(28), 1f));
        previewToggle = chipButton("Hide", 9f, MUTED);
        previewToggle.setContentDescription("Toggle sketch preview");
        previewToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                previewVisible = !previewVisible;
                previewToggle.setText(previewVisible ? "Hide" : "Preview");
                previewToggle.setTextColor(previewVisible ? ACCENT : MUTED);
                if (frameView != null) {
                    frameView.invalidate();
                }
            }
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(0, dp(28), 1f);
        toggleParams.leftMargin = dp(3);
        bar.addView(previewToggle, toggleParams);
        TextView cancel = chipButton("Cancel", 9f, MUTED);
        cancel.setContentDescription("Cancel area selection");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSelection();
                if (panelWasVisible) {
                    showPanel();
                }
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(28), 1f);
        cancelParams.leftMargin = dp(3);
        bar.addView(cancel, cancelParams);
        return bar;
    }

    private View handle(String glyph, int mode, int width, int height) {
        TextView view = label(glyph, 12f, TEXT);
        view.setGravity(Gravity.CENTER);
        GradientDrawable shape = surface(0xE6242730, 10, 1f, 0x807CC6FF);
        view.setBackground(shape);
        view.setContentDescription(mode == 0 ? "Move drawing area" : "Resize drawing area");
        WindowManager.LayoutParams params = overlayParams(width, height, true);
        addWindow(view, params);
        view.setOnTouchListener(new SelectionTouch(mode));
        return view;
    }

    private final class SelectionTouch implements View.OnTouchListener {
        private final int mode;
        private float downX;
        private float downY;
        private CanvasMapper.Rect initial;

        SelectionTouch(int mode) {
            this.mode = mode;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (selection == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    initial = new CanvasMapper.Rect(selection);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    Point screen = displayPoint();
                    int minWidth = dp(96);
                    int minHeight = dp(96);
                    CanvasMapper.Rect next = new CanvasMapper.Rect(initial);
                    if (mode == 0) {
                        dx = clamp(dx, dp(2) - Math.round(initial.left),
                                screen.x - dp(2) - Math.round(initial.right));
                        dy = clamp(dy, dp(30) - Math.round(initial.top),
                                screen.y - dp(30) - Math.round(initial.bottom));
                        next.left += dx;
                        next.right += dx;
                        next.top += dy;
                        next.bottom += dy;
                    } else {
                        if (mode == 1 || mode == 3) {
                            next.left = DrawingSettings.clamp(initial.left + dx, dp(2),
                                    initial.right - minWidth);
                        }
                        if (mode == 2 || mode == 4) {
                            next.right = DrawingSettings.clamp(initial.right + dx,
                                    initial.left + minWidth, screen.x - dp(2));
                        }
                        if (mode == 1 || mode == 2) {
                            next.top = DrawingSettings.clamp(initial.top + dy, dp(28),
                                    initial.bottom - minHeight);
                        }
                        if (mode == 3 || mode == 4) {
                            next.bottom = DrawingSettings.clamp(initial.bottom + dy,
                                    initial.top + minHeight, screen.y - dp(28));
                        }
                    }
                    selection = next;
                    layoutSelectionWindows();
                    if (frameView != null) {
                        frameView.invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    private void clampSelection() {
        if (selection == null) {
            return;
        }
        Point screen = displayPoint();
        float width = Math.min(selection.width(), screen.x - dp(4));
        float height = Math.min(selection.height(), screen.y - dp(60));
        float left = DrawingSettings.clamp(selection.left, dp(2), screen.x - width - dp(2));
        float top = DrawingSettings.clamp(selection.top, dp(28), screen.y - height - dp(28));
        selection.left = left;
        selection.top = top;
        selection.right = left + width;
        selection.bottom = top + height;
    }

    private void layoutSelectionWindows() {
        if (selection == null) {
            return;
        }
        Point screen = displayPoint();
        int corner = dp(40);
        int moveWidth = dp(74);
        int moveHeight = dp(26);
        place(moveHandle, Math.round(selection.centerX() - moveWidth / 2f),
                Math.round(selection.top - moveHeight / 2f), moveWidth, moveHeight);
        place(topLeft, Math.round(selection.left - corner / 2f),
                Math.round(selection.top - corner / 2f), corner, corner);
        place(topRight, Math.round(selection.right - corner / 2f),
                Math.round(selection.top - corner / 2f), corner, corner);
        place(bottomLeft, Math.round(selection.left - corner / 2f),
                Math.round(selection.bottom - corner / 2f), corner, corner);
        place(bottomRight, Math.round(selection.right - corner / 2f),
                Math.round(selection.bottom - corner / 2f), corner, corner);
        if (actionBar != null) {
            int barWidth = actionBar.getLayoutParams() != null
                    ? actionBar.getLayoutParams().width : dp(236);
            int barHeight = dp(36);
            int x = clamp(Math.round(selection.centerX() - barWidth / 2f), dp(2),
                    Math.max(dp(2), screen.x - barWidth - dp(2)));
            int y = Math.round(selection.bottom + dp(10));
            if (y + barHeight > screen.y - dp(20)) {
                y = Math.round(selection.top - barHeight - dp(10));
            }
            y = clamp(y, dp(4), Math.max(dp(4), screen.y - barHeight - dp(4)));
            place(actionBar, x, y, barWidth, barHeight);
        }
    }

    private void place(View view, int x, int y, int width, int height) {
        if (view == null) {
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        if (params == null) {
            return;
        }
        int maxX = Math.max(0, screenWidth() - width);
        int maxY = Math.max(0, screenHeight() - height);
        params.width = width;
        params.height = height;
        params.x = clamp(x, 0, maxX);
        params.y = clamp(y, 0, maxY);
        try {
            manager.updateViewLayout(view, params);
        } catch (RuntimeException ignored) {
        }
    }

    public void closeSelection() {
        removeWindow(actionBar);
        removeWindow(topLeft);
        removeWindow(topRight);
        removeWindow(bottomLeft);
        removeWindow(bottomRight);
        removeWindow(moveHandle);
        removeWindow(frameView);
        actionBar = null;
        topLeft = null;
        topRight = null;
        bottomLeft = null;
        bottomRight = null;
        moveHandle = null;
        frameView = null;
        selectionView = null;
        previewToggle = null;
        overlayMode = MODE_NONE;
        previewVisible = false;
    }

    // ------------------------------------------------------------------ preview

    public void togglePreview() {
        if (frameView != null && overlayMode == MODE_SELECT) {
            previewVisible = !previewVisible;
            if (previewToggle != null) {
                previewToggle.setText(previewVisible ? "Hide" : "Preview");
            }
            frameView.invalidate();
            refresh();
            return;
        }
        if (frameView != null && overlayMode == MODE_PREVIEW) {
            removePreviewWindow();
            refresh();
            return;
        }
        CanvasMapper.Rect area = listener.drawingArea();
        if (area == null) {
            setStatus("Select an area first");
            return;
        }
        previewVisible = true;
        selection = new CanvasMapper.Rect(area);
        overlayMode = MODE_PREVIEW;
        selectionView = new SelectionView();
        selectionView.setMode(MODE_PREVIEW);
        frameView = selectionView;
        frameParams = overlayParams(-1, -1, false);
        addWindow(frameView, frameParams);
        refresh();
    }

    private void removePreviewWindow() {
        if (overlayMode == MODE_PREVIEW) {
            removeWindow(frameView);
            frameView = null;
            selectionView = null;
            overlayMode = MODE_NONE;
            previewVisible = false;
        }
    }

    public boolean isPreviewVisible() {
        return frameView != null && previewVisible;
    }

    public void onConfigurationChanged() {
        closeSelection();
        removePreviewWindow();
        Point screen = displayPoint();
        if (panel != null && panelParams != null) {
            panelParams.x = clamp(panelParams.x, dp(2),
                    Math.max(dp(2), screen.x - panelParams.width - dp(2)));
            panelParams.y = clamp(panelParams.y, dp(2),
                    Math.max(dp(2), screen.y - panelParams.height - dp(2)));
            try {
                manager.updateViewLayout(panel, panelParams);
            } catch (RuntimeException ignored) {
            }
        }
        if (miniIcon != null && miniParams != null) {
            miniParams.x = clamp(miniParams.x, dp(2), Math.max(dp(2), screen.x - miniParams.width - dp(2)));
            miniParams.y = clamp(miniParams.y, dp(2), Math.max(dp(2), screen.y - miniParams.height - dp(2)));
            try {
                manager.updateViewLayout(miniIcon, miniParams);
            } catch (RuntimeException ignored) {
            }
        }
        refresh();
    }

    public void dispose() {
        handler.removeCallbacks(settingsDispatch);
        hideAll();
    }

    // -------------------------------------------------------------------- views

    private final class ProgressBarView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;

        ProgressBarView() {
            super(context);
            track.setColor(0x22FFFFFF);
            fill.setColor(ACCENT);
        }

        void setProgress(float value) {
            float next = DrawingSettings.clamp(value, 0f, 1f);
            if (Math.abs(next - progress) > 0.001f) {
                progress = next;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float height = dp(3);
            float top = (getHeight() - height) / 2f;
            float radius = height / 2f;
            canvas.drawRoundRect(new RectF(0, top, getWidth(), top + height), radius, radius, track);
            if (progress > 0f) {
                canvas.drawRoundRect(new RectF(0, top, Math.max(height, getWidth() * progress),
                        top + height), radius, radius, fill);
            }
        }
    }

    private final class GripView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GripView() {
            super(context);
            paint.setColor(0x66FFFFFF);
            paint.setStrokeWidth(dp(1.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            for (int i = 0; i < 3; i++) {
                float offset = dp(3 + i * 4);
                canvas.drawLine(w - offset, h - dp(2), w - dp(2), h - offset, paint);
            }
        }
    }

    private final class MiniIconView extends View {
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private boolean active;

        MiniIconView() {
            super(context);
            background.setColor(SURFACE);
            border.setColor(0x33FFFFFF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1f));
            arc.setColor(ACCENT);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(2.4f));
            arc.setStrokeCap(Paint.Cap.ROUND);
            glyph.setColor(TEXT);
            glyph.setTextAlign(Paint.Align.CENTER);
            glyph.setTextSize(dp(17));
            glyph.setAntiAlias(true);
            setElevation(dp(8));
            setBackground(ripple(0x00000000, 25));
        }

        void setProgress(float value) {
            float next = DrawingSettings.clamp(value, 0f, 1f);
            if (Math.abs(next - progress) > 0.004f) {
                progress = next;
                invalidate();
            }
        }

        void setActive(boolean value) {
            if (active != value) {
                active = value;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - dp(1.5f);
            canvas.drawCircle(cx, cy, radius, background);
            canvas.drawCircle(cx, cy, radius, border);
            if (progress > 0.001f) {
                RectF oval = new RectF(cx - radius + dp(2), cy - radius + dp(2),
                        cx + radius - dp(2), cy + radius - dp(2));
                canvas.drawArc(oval, -90f, 360f * progress, false, arc);
            }
            String text = active || progress > 0.001f
                    ? Math.round(progress * 100) + "%"
                    : "✎";
            float baseline = cy - (glyph.descent() + glyph.ascent()) / 2f;
            canvas.drawText(text, cx, baseline, glyph);
        }
    }

    /** Full-screen, non-touchable frame plus the live stroke preview. */
    private final class SelectionView extends View {
        private final Paint dim = new Paint();
        private final Paint areaFill = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bracket = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint previewDone = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chipBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mode = MODE_SELECT;

        SelectionView() {
            super(context);
            dim.setColor(0x33000000);
            areaFill.setColor(0x0A7CC6FF);
            border.setColor(0xCC7CC6FF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1.4f));
            bracket.setColor(ACCENT);
            bracket.setStyle(Paint.Style.STROKE);
            bracket.setStrokeWidth(dp(3f));
            bracket.setStrokeCap(Paint.Cap.ROUND);
            previewPaint.setColor(0xB37CC6FF);
            previewPaint.setStyle(Paint.Style.STROKE);
            previewPaint.setStrokeWidth(dp(1.1f));
            previewPaint.setStrokeCap(Paint.Cap.ROUND);
            previewPaint.setStrokeJoin(Paint.Join.ROUND);
            previewDone.setColor(0xD934D399);
            previewDone.setStyle(Paint.Style.STROKE);
            previewDone.setStrokeWidth(dp(1.3f));
            previewDone.setStrokeCap(Paint.Cap.ROUND);
            previewDone.setStrokeJoin(Paint.Join.ROUND);
            chipBackground.setColor(0xE616171C);
            chipText.setColor(TEXT);
            chipText.setTextSize(dp(9));
            chipText.setAntiAlias(true);
        }

        void setMode(int value) {
            mode = value;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            CanvasMapper.Rect area = selection;
            if (area == null) {
                return;
            }
            int width = getWidth();
            int height = getHeight();
            canvas.drawRect(0, 0, width, area.top, dim);
            canvas.drawRect(0, area.bottom, width, height, dim);
            canvas.drawRect(0, area.top, area.left, area.bottom, dim);
            canvas.drawRect(area.right, area.top, width, area.bottom, dim);
            canvas.drawRect(area.left, area.top, area.right, area.bottom, areaFill);

            if (previewVisible) {
                drawPreview(canvas, area);
            }

            if (mode == MODE_SELECT) {
                canvas.drawRect(area.left, area.top, area.right, area.bottom, border);
                float length = dp(16);
                canvas.drawLine(area.left, area.top, area.left + length, area.top, bracket);
                canvas.drawLine(area.left, area.top, area.left, area.top + length, bracket);
                canvas.drawLine(area.right, area.top, area.right - length, area.top, bracket);
                canvas.drawLine(area.right, area.top, area.right, area.top + length, bracket);
                canvas.drawLine(area.left, area.bottom, area.left + length, area.bottom, bracket);
                canvas.drawLine(area.left, area.bottom, area.left, area.bottom - length, bracket);
                canvas.drawLine(area.right, area.bottom, area.right - length, area.bottom, bracket);
                canvas.drawLine(area.right, area.bottom, area.right, area.bottom - length, bracket);
            } else {
                canvas.drawRect(area.left, area.top, area.right, area.bottom, border);
            }

            String caption = Math.round(area.width()) + "×" + Math.round(area.height());
            if (previewVisible) {
                Sketch sketch = listener.sketch();
                caption += " · " + sketch.strokes.size() + " strokes";
            }
            float textWidth = chipText.measureText(caption);
            float padding = dp(6);
            float chipLeft = area.left + dp(4);
            float chipTop = area.top - dp(20);
            if (chipTop < dp(4)) {
                chipTop = area.top + dp(4);
            }
            float chipHeight = dp(16);
            canvas.drawRoundRect(new RectF(chipLeft, chipTop, chipLeft + textWidth + padding * 2,
                    chipTop + chipHeight), dp(5), dp(5), chipBackground);
            canvas.drawText(caption, chipLeft + padding, chipTop + chipHeight - dp(5), chipText);
        }

        private void drawPreview(Canvas canvas, CanvasMapper.Rect area) {
            Sketch sketch = listener.sketch();
            if (sketch == null || sketch.isEmpty()) {
                return;
            }
            DrawingSettings settings = listener.settings();
            ArrayList<float[]> mapped = CanvasMapper.mapStrokes(sketch.strokes, area,
                    sketch.width, sketch.height, settings);
            float progress = listener.controller().getProgress();
            boolean highlight = settings.showProgressPreview && progress > 0.001f
                    && listener.controller().getState() != DrawingController.State.READY;
            float total = 0f;
            for (float[] stroke : mapped) {
                total += StrokeOptimizer.length(stroke);
            }
            float target = total * progress;
            float travelled = 0f;
            for (float[] stroke : mapped) {
                if (stroke.length < 4) {
                    continue;
                }
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(stroke[0], stroke[1]);
                for (int i = 2; i + 1 < stroke.length; i += 2) {
                    path.lineTo(stroke[i], stroke[i + 1]);
                }
                boolean done = highlight && travelled <= target;
                canvas.drawPath(path, done ? previewDone : previewPaint);
                travelled += StrokeOptimizer.length(stroke);
            }
        }
    }
}
