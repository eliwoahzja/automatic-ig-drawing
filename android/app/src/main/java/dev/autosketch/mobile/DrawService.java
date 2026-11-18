package dev.autosketch.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;

public class DrawService extends AccessibilityService {
    static DrawService instance;

    private WindowManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View controls;
    private WindowManager.LayoutParams controlsParams;
    private LinearLayout controlBody;
    private TextView progress;
    private View frame;
    private View moveHandle;
    private View topLeftHandle;
    private View topRightHandle;
    private View bottomLeftHandle;
    private View bottomRightHandle;
    private View selectionActions;
    private Rect selection;
    private Rect bounds;
    private int controlX = -1;
    private int controlY = -1;
    private int controlWidth = -1;
    private int controlHeight = -1;
    private int index = 0;
    private int session = 0;
    private boolean paused = true;
    private boolean cancelled = false;
    private boolean inFlight = false;

    private int dp(float size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Point displaySize() {
        Point point = new Point();
        manager.getDefaultDisplay().getRealSize(point);
        return point;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stopDrawing();
    }

    @Override
    public void onDestroy() {
        stopDrawing();
        closeSelection();
        remove(controls);
        controls = null;
        instance = null;
        super.onDestroy();
    }

    private void remove(View view) {
        if (view == null || manager == null) return;
        try {
            manager.removeView(view);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        return params;
    }

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private void setProgress(String message) {
        if (progress != null) progress.setText(message);
    }

    public void showControls() {
        if (manager == null) return;
        remove(controls);
        Point screen = displaySize();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(7), dp(8), dp(8));
        panel.setBackground(rounded(0xee171c28, 14));
        panel.setElevation(dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView dragLabel = label("≡  Auto Sketch  •  Drag", 13);
        dragLabel.setPadding(dp(4), dp(6), dp(6), dp(6));
        header.addView(dragLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView minimize = label("−", 24);
        minimize.setGravity(Gravity.CENTER);
        minimize.setBackground(rounded(0xff31394e, 8));
        header.addView(minimize, new LinearLayout.LayoutParams(dp(38), dp(38)));
        panel.addView(header);

        controlBody = new LinearLayout(this);
        controlBody.setOrientation(LinearLayout.VERTICAL);
        progress = label(bounds == null ? "Select your drawing area" : "Area selected", 12);
        progress.setPadding(dp(6), dp(4), dp(4), dp(6));
        controlBody.addView(progress);
        Button select = button("Select / resize area");
        select.setOnClickListener(v -> selectArea());
        controlBody.addView(select, new LinearLayout.LayoutParams(-1, dp(47)));

        LinearLayout actionRow = new LinearLayout(this);
        Button start = button("Start / Resume");
        start.setOnClickListener(v -> startDrawing());
        actionRow.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button pause = button("Pause");
        pause.setOnClickListener(v -> {
            paused = true;
            setProgress("Paused");
        });
        actionRow.addView(pause, new LinearLayout.LayoutParams(0, dp(48), 1));
        controlBody.addView(actionRow);

        LinearLayout secondRow = new LinearLayout(this);
        Button stop = button("Stop");
        stop.setOnClickListener(v -> stopDrawing());
        secondRow.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button hide = button("Hide");
        hide.setOnClickListener(v -> {
            paused = true;
            saveControlPosition();
            remove(controls);
            controls = null;
            progress = null;
        });
        secondRow.addView(hide, new LinearLayout.LayoutParams(0, dp(48), 1));
        controlBody.addView(secondRow);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(controlBody);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = label("Drag corner to resize", 10);
        hint.setPadding(dp(6), 0, 0, 0);
        footer.addView(hint, new LinearLayout.LayoutParams(0, -1, 1));
        TextView resizeGrip = label("⤡", 26);
        resizeGrip.setGravity(Gravity.CENTER);
        resizeGrip.setBackground(rounded(0xff34425a, 8));
        footer.addView(resizeGrip, new LinearLayout.LayoutParams(dp(48), dp(36)));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, dp(39)));

        resizeGrip.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int initialWidth;
            private int initialHeight;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        initialWidth = controlsParams.width;
                        initialHeight = controlsParams.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point size = displaySize();
                        int maximumWidth = Math.max(dp(175), size.x - controlsParams.x - dp(8));
                        int maximumHeight = Math.max(dp(130), size.y - controlsParams.y - dp(12));
                        controlWidth = clamp(initialWidth + Math.round(event.getRawX() - downX),
                                dp(175), maximumWidth);
                        controlHeight = clamp(initialHeight + Math.round(event.getRawY() - downY),
                                dp(130), maximumHeight);
                        controlsParams.width = controlWidth;
                        controlsParams.height = controlHeight;
                        manager.updateViewLayout(controls, controlsParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });

        minimize.setOnClickListener(v -> {
            boolean opening = scroll.getVisibility() != View.VISIBLE;
            scroll.setVisibility(opening ? View.VISIBLE : View.GONE);
            footer.setVisibility(opening ? View.VISIBLE : View.GONE);
            controlsParams.height = opening ? controlHeight : dp(62);
            manager.updateViewLayout(controls, controlsParams);
            minimize.setText(opening ? "−" : "+");
        });

        controls = panel;
        if (controlWidth < 0) controlWidth = dp(204);
        if (controlHeight < 0) controlHeight = dp(257);
        controlWidth = Math.min(controlWidth, screen.x - dp(12));
        controlHeight = Math.min(controlHeight, screen.y - dp(80));
        controlsParams = overlayParams(controlWidth, controlHeight, true);
        controlX = controlX < 0 ? screen.x - controlWidth - dp(12) : controlX;
        controlY = controlY < 0 ? dp(94) : controlY;
        controlsParams.x = clamp(controlX, 0, Math.max(0, screen.x - controlWidth));
        controlsParams.y = clamp(controlY, 0, Math.max(0, screen.y - controlHeight));
        manager.addView(controls, controlsParams);
        dragLabel.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private float startY;
            private int originX;
            private int originY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        originX = controlsParams.x;
                        originY = controlsParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point screen = displaySize();
                        int width = controls.getWidth() > 0 ? controls.getWidth() : controlWidth;
                        int height = controls.getHeight() > 0 ? controls.getHeight() : controlHeight;
                        controlsParams.x = clamp(originX + Math.round(event.getRawX() - startX),
                                0, Math.max(0, screen.x - width));
                        controlsParams.y = clamp(originY + Math.round(event.getRawY() - startY),
                                0, Math.max(0, screen.y - height));
                        manager.updateViewLayout(controls, controlsParams);
                        saveControlPosition();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveControlPosition();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void saveControlPosition() {
        if (controlsParams != null) {
            controlX = controlsParams.x;
            controlY = controlsParams.y;
        }
    }

    private class SelectionFrame extends View {
        private final Paint fill = new Paint(3);
        private final Paint border = new Paint(3);

        SelectionFrame() {
            super(DrawService.this);
            fill.setColor(0x183ca5ff);
            fill.setStyle(Paint.Style.FILL);
            border.setColor(0xff75caff);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (selection == null) return;
            canvas.drawRoundRect(selection.left, selection.top, selection.right,
                    selection.bottom, dp(7), dp(7), fill);
            canvas.drawRoundRect(selection.left, selection.top, selection.right,
                    selection.bottom, dp(7), dp(7), border);
        }
    }

    private void selectArea() {
        paused = true;
        saveControlPosition();
        remove(controls);
        controls = null;
        progress = null;
        closeSelection();

        Point screen = displaySize();
        if (bounds != null) {
            selection = new Rect(bounds);
        } else {
            selection = new Rect(screen.x / 8, screen.y / 4,
                    screen.x * 7 / 8, screen.y * 3 / 4);
        }

        frame = new SelectionFrame();
        WindowManager.LayoutParams frameParams = overlayParams(-1, -1, false);
        manager.addView(frame, frameParams);

        moveHandle = createHandle("MOVE", 0, dp(110), dp(42));
        topLeftHandle = createHandle("↖", 1, dp(46), dp(46));
        topRightHandle = createHandle("↗", 2, dp(46), dp(46));
        bottomLeftHandle = createHandle("↙", 3, dp(46), dp(46));
        bottomRightHandle = createHandle("↘", 4, dp(46), dp(46));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBackground(rounded(0xee171c28, 12));
        Button apply = button("Use area");
        apply.setOnClickListener(v -> {
            bounds = new Rect(selection);
            index = 0;
            closeSelection();
            showControls();
            setProgress("Area: " + bounds.width() + " × " + bounds.height());
        });
        buttons.addView(apply, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button cancel = button("Cancel");
        cancel.setOnClickListener(v -> {
            closeSelection();
            showControls();
            setProgress(bounds == null ? "No area selected" : "Previous area kept");
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        selectionActions = buttons;
        manager.addView(buttons, overlayParams(dp(222), dp(54), true));
        updateSelectionViews();
    }

    private View createHandle(String text, int mode, int width, int height) {
        TextView handle = label(text, 14);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable surface = rounded(0xee243047, 10);
        surface.setStroke(dp(1), 0xff79c6ff);
        handle.setBackground(surface);
        handle.setElevation(dp(5));
        manager.addView(handle, overlayParams(width, height, true));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private float startY;
            private Rect initial;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        initial = new Rect(selection);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - startX);
                        int dy = Math.round(event.getRawY() - startY);
                        Point screen = displaySize();
                        int minWidth = dp(120);
                        int minHeight = dp(120);
                        Rect next = new Rect(initial);
                        if (mode == 0) {
                            dx = clamp(dx, -initial.left + dp(4),
                                    screen.x - dp(4) - initial.right);
                            dy = clamp(dy, dp(30) - initial.top,
                                    screen.y - dp(35) - initial.bottom);
                            next.offset(dx, dy);
                        } else {
                            if (mode == 1 || mode == 3)
                                next.left = clamp(initial.left + dx, dp(4),
                                        initial.right - minWidth);
                            if (mode == 2 || mode == 4)
                                next.right = clamp(initial.right + dx,
                                        initial.left + minWidth, screen.x - dp(4));
                            if (mode == 1 || mode == 2)
                                next.top = clamp(initial.top + dy, dp(30),
                                        initial.bottom - minHeight);
                            if (mode == 3 || mode == 4)
                                next.bottom = clamp(initial.bottom + dy,
                                        initial.top + minHeight, screen.y - dp(35));
                        }
                        selection.set(next);
                        updateSelectionViews();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        return handle;
    }

    private void position(View view, int x, int y, int width, int height) {
        if (view == null) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        params.width = width;
        params.height = height;
        Point screen = displaySize();
        params.x = clamp(x, 0, Math.max(0, screen.x - width));
        params.y = clamp(y, 0, Math.max(0, screen.y - height));
        manager.updateViewLayout(view, params);
    }

    private void updateSelectionViews() {
        if (selection == null) return;
        if (frame != null) frame.invalidate();
        int corner = dp(46);
        int moveWidth = dp(110);
        position(moveHandle, selection.centerX() - moveWidth / 2,
                selection.top - dp(21), moveWidth, dp(42));
        position(topLeftHandle, selection.left - corner / 2,
                selection.top - corner / 2, corner, corner);
        position(topRightHandle, selection.right - corner / 2,
                selection.top - corner / 2, corner, corner);
        position(bottomLeftHandle, selection.left - corner / 2,
                selection.bottom - corner / 2, corner, corner);
        position(bottomRightHandle, selection.right - corner / 2,
                selection.bottom - corner / 2, corner, corner);
        Point screen = displaySize();
        int actionY = selection.bottom + dp(28);
        if (actionY + dp(54) > screen.y - dp(32))
            actionY = selection.top - dp(79);
        position(selectionActions, selection.centerX() - dp(111),
                actionY, dp(222), dp(54));
    }

    private void closeSelection() {
        remove(selectionActions);
        remove(topLeftHandle);
        remove(topRightHandle);
        remove(bottomLeftHandle);
        remove(bottomRightHandle);
        remove(moveHandle);
        remove(frame);
        selectionActions = null;
        topLeftHandle = null;
        topRightHandle = null;
        bottomLeftHandle = null;
        bottomRightHandle = null;
        moveHandle = null;
        frame = null;
        selection = null;
    }

    private void stopDrawing() {
        session++;
        paused = true;
        cancelled = true;
        index = 0;
        setProgress("Stopped");
    }

    private void startDrawing() {
        if (bounds == null) {
            setProgress("Select an area first");
            return;
        }
        if (MainActivity.strokes.isEmpty()) {
            setProgress("Generate a sketch first");
            return;
        }
        if (inFlight) {
            paused = false;
            cancelled = false;
            setProgress("Finishing current stroke");
            return;
        }
        if (index >= MainActivity.strokes.size()) index = 0;
        cancelled = false;
        paused = false;
        drawNext();
    }

    private void drawNext() {
        if (inFlight || paused || cancelled || bounds == null) return;
        ArrayList<float[]> paths = MainActivity.strokes;
        if (index >= paths.size()) {
            paused = true;
            setProgress("Drawing complete");
            return;
        }
        float[] points = paths.get(index++);
        if (points.length < 4) {
            handler.post(this::drawNext);
            return;
        }
        Rect area = new Rect(bounds);
        float imageWidth = Math.max(1f, MainActivity.sketchWidth);
        float imageHeight = Math.max(1f, MainActivity.sketchHeight);
        float scale = Math.min(area.width() / imageWidth, area.height() / imageHeight);
        float fittedWidth = imageWidth * scale;
        float fittedHeight = imageHeight * scale;
        float originX = area.left + (area.width() - fittedWidth) / 2f;
        float originY = area.top + (area.height() - fittedHeight) / 2f;
        Path path = new Path();
        float x = originX + Math.max(0f, Math.min(1f, points[0])) * fittedWidth;
        float y = originY + Math.max(0f, Math.min(1f, points[1])) * fittedHeight;
        path.moveTo(x, y);
        for (int i = 2; i + 1 < points.length; i += 2) {
            x = originX + Math.max(0f, Math.min(1f, points[i])) * fittedWidth;
            y = originY + Math.max(0f, Math.min(1f, points[i + 1])) * fittedHeight;
            path.lineTo(x, y);
        }
        long duration = Math.max(80, Math.min(1600, points.length * 2L));
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        int requestedSession = session;
        inFlight = true;
        setProgress(index + " / " + paths.size() + " strokes");
        boolean accepted = dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gesture) {
                inFlight = false;
                if (requestedSession == session && !paused && !cancelled)
                    handler.postDelayed(() -> drawNext(), 40);
            }

            @Override
            public void onCancelled(GestureDescription gesture) {
                inFlight = false;
                if (requestedSession == session) {
                    paused = true;
                    handler.post(() -> setProgress("Gesture interrupted"));
                }
            }
        }, handler);
        if (!accepted) {
            inFlight = false;
            paused = true;
            setProgress("Drawing gesture unavailable");
        }
    }
}
