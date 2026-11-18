package dev.autosketch.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility service: hosts the floating UI, dispatches gestures and owns the
 * drawing controller. Only the selected rectangle is ever touched.
 */
public class DrawService extends AccessibilityService
        implements DrawingController.Host, OverlayController.Listener {

    static volatile DrawService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager manager;
    private DrawingController controller;
    private OverlayController overlay;
    private CanvasMapper.Rect bounds;
    private ExecutorService worker;
    private SketchSession.Token processToken;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        SketchSession.updateSettings(SettingsStore.load(this));
        controller = new DrawingController(this);
        overlay = new OverlayController(this, this, manager);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Gesture-only service; no event inspection required.
    }

    @Override
    public void onInterrupt() {
        if (controller != null) {
            controller.pause();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (controller != null && controller.isBusy()) {
            controller.stop();
        }
        bounds = null;
        if (overlay != null) {
            overlay.onConfigurationChanged();
            overlay.setStatus("Screen rotated · select the drawing area again");
        }
    }

    @Override
    public void onDestroy() {
        if (processToken != null) {
            processToken.cancel();
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        if (controller != null) {
            controller.shutdown();
        }
        if (overlay != null) {
            overlay.dispose();
        }
        SketchSession.clearSource();
        controller = null;
        overlay = null;
        manager = null;
        instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    // -------------------------------------------------------------- host APIs

    public void showControls() {
        if (overlay == null || controller == null) {
            return;
        }
        if (!controller.isBusy()) {
            syncPlan();
        }
        overlay.showControls();
    }

    public boolean isDrawingActive() {
        return controller != null && controller.isBusy();
    }

    @Override
    public boolean dispatchGesture(GestureDescription gesture,
                                   AccessibilityService.GestureResultCallback callback) {
        return super.dispatchGesture(gesture, callback, mainHandler);
    }

    @Override
    public void onDrawingStateChanged(DrawingController.State state, String message, float progress) {
        if (overlay == null) {
            return;
        }
        overlay.refresh();
        if (state == DrawingController.State.COMPLETED) {
            overlay.onDrawingCompleted();
        }
    }

    // ---------------------------------------------------------- overlay events

    @Override
    public DrawingController controller() {
        return controller;
    }

    @Override
    public Sketch sketch() {
        return SketchSession.sketch();
    }

    @Override
    public DrawingSettings settings() {
        return SketchSession.settings();
    }

    @Override
    public CanvasMapper.Rect drawingArea() {
        return bounds;
    }

    @Override
    public void onSettingsChanged(DrawingSettings settings, boolean needsReprocessing) {
        SketchSession.updateSettings(settings);
        SettingsStore.save(this, settings);
        if (needsReprocessing) {
            reprocess();
            return;
        }
        if (controller != null && !controller.isBusy()) {
            syncPlan();
        }
        if (overlay != null) {
            overlay.refresh();
        }
    }

    @Override
    public void onAreaConfirmed(CanvasMapper.Rect area) {
        bounds = area;
        if (controller != null && controller.isBusy()) {
            controller.stop();
        }
        syncPlan();
        if (overlay != null) {
            overlay.refresh();
        }
    }

    @Override
    public void onStartRequested() {
        if (overlay == null || controller == null) {
            return;
        }
        if (bounds == null) {
            overlay.setStatus("Select the drawing area first");
            return;
        }
        if (SketchSession.sketch().isEmpty()) {
            overlay.setStatus("Generate a sketch in Auto Sketch");
            return;
        }
        if (!controller.isBusy()) {
            syncPlan();
        }
        controller.start();
        overlay.refresh();
    }

    @Override
    public void onPauseRequested() {
        if (controller != null) {
            controller.pause();
        }
    }

    @Override
    public void onResumeRequested() {
        if (controller != null) {
            controller.resume();
        }
    }

    @Override
    public void onStopRequested() {
        if (controller != null) {
            controller.stop();
        }
    }

    @Override
    public void onCloseRequested() {
        if (controller != null) {
            controller.stop();
        }
    }

    // ------------------------------------------------------------------- plan

    private void syncPlan() {
        if (controller == null) {
            return;
        }
        DrawingSettings settings = SketchSession.settings();
        Sketch sketch = SketchSession.sketch();
        if (bounds == null || sketch == null || sketch.isEmpty()) {
            controller.setStrokes(new ArrayList<float[]>(), settings);
            return;
        }
        ArrayList<float[]> mapped = CanvasMapper.mapStrokes(sketch.strokes, bounds,
                sketch.width, sketch.height, settings);
        controller.setStrokes(mapped, settings);
    }

    private void reprocess() {
        final Bitmap source = SketchSession.source();
        if (source == null || source.isRecycled()) {
            if (overlay != null) {
                overlay.setStatus("Choose an image in Auto Sketch first");
            }
            return;
        }
        if (processToken != null) {
            processToken.cancel();
        }
        final SketchSession.Token token = new SketchSession.Token();
        processToken = token;
        final DrawingSettings settings = SketchSession.settings();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int canvasWidth = bounds != null ? Math.round(bounds.width())
                : Math.max(1, screenWidth);
        if (controller != null) {
            controller.markProcessing();
        }
        if (overlay != null) {
            overlay.setProcessingProgress("Processing image…");
        }
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor();
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final SketchProcessor.Outcome outcome =
                        SketchProcessor.process(source, settings, canvasWidth, token);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token.isCancelled() || processToken != token) {
                            return;
                        }
                        processToken = null;
                        if (outcome.cancelled) {
                            return;
                        }
                        SketchSession.setSketch(outcome.sketch);
                        if (bounds != null) {
                            syncPlan();
                        }
                        if (overlay != null) {
                            overlay.setStatus("Ready · " + outcome.sketch.strokes.size()
                                    + " strokes · " + outcome.durationMs + " ms");
                            overlay.refresh();
                        }
                    }
                });
            }
        });
    }
}
