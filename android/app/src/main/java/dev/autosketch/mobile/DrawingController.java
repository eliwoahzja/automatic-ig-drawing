package dev.autosketch.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * Reliable drawing state machine. It owns the gesture plan, dispatches one
 * continuous stroke at a time, waits for each gesture to finish and only
 * reports completion after every stroke was accepted and completed.
 */
public final class DrawingController {

    public enum State {
        IDLE, PROCESSING, READY, DRAWING, PAUSED, COMPLETED, CANCELLED, ERROR
    }

    public interface Host {
        boolean dispatchGesture(GestureDescription gesture,
                                AccessibilityService.GestureResultCallback callback);

        void onDrawingStateChanged(State state, String message, float progress);
    }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Gesture chunks in screen coordinates, split so each stays under the duration cap. */
    private final ArrayList<float[]> plan = new ArrayList<>();
    private State state = State.IDLE;
    private String message = "No sketch yet";
    private int index = 0;
    private boolean inFlight = false;
    private int session = 0;
    private int speedPxPerSec = 520;
    private int maxStrokeDurationMs = 2400;
    private int gapMs = 24;

    private final Runnable scheduler = new Runnable() {
        @Override
        public void run() {
            dispatchNext();
        }
    };

    public DrawingController(Host host) {
        this.host = host;
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public float getProgress() {
        if (plan.isEmpty()) {
            return state == State.COMPLETED ? 1f : 0f;
        }
        return Math.min(1f, index / (float) plan.size());
    }

    public int getIndex() {
        return index;
    }

    public int getStrokeCount() {
        return plan.size();
    }

    public List<float[]> planStrokes() {
        return plan;
    }

    public boolean isBusy() {
        return state == State.DRAWING || state == State.PAUSED;
    }

    /** Replaces the gesture plan. Safe to call while paused; it resets progress. */
    public void setStrokes(List<float[]> screenStrokes, DrawingSettings settings) {
        stopInternal(false);
        plan.clear();
        if (settings != null) {
            speedPxPerSec = Math.max(80, settings.drawingSpeedPxPerSec);
            maxStrokeDurationMs = Math.max(240, Math.min(10000, settings.maxStrokeDurationMs));
            gapMs = Math.max(6, Math.min(240, settings.strokeGapMs));
        }
        float maxDistance = speedPxPerSec * (maxStrokeDurationMs / 1000f);
        if (screenStrokes != null) {
            for (float[] stroke : screenStrokes) {
                for (float[] chunk : splitForGestures(stroke, maxDistance)) {
                    plan.add(chunk);
                }
            }
        }
        if (plan.isEmpty()) {
            setState(State.IDLE, "No strokes yet");
        } else {
            setState(State.READY, "Ready · " + plan.size() + " strokes");
        }
    }

    public void markProcessing() {
        stopInternal(false);
        setState(State.PROCESSING, "Processing image…");
    }

    public void start() {
        if (plan.isEmpty()) {
            setState(State.ERROR, "Generate a sketch before drawing");
            return;
        }
        if (state == State.PAUSED) {
            resume();
            return;
        }
        if (state == State.DRAWING || state == State.PROCESSING || inFlight) {
            return;
        }
        session++;
        handler.removeCallbacks(scheduler);
        index = 0;
        setState(State.DRAWING, "Drawing…");
        dispatchNext();
    }

    public void pause() {
        if (state != State.DRAWING) {
            return;
        }
        handler.removeCallbacks(scheduler);
        setState(State.PAUSED, "Paused at " + Math.round(getProgress() * 100f) + "%");
    }

    public void resume() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.DRAWING, "Drawing…");
        if (!inFlight) {
            handler.post(scheduler);
        }
    }

    public void stop() {
        stopInternal(true);
    }

    private void stopInternal(boolean publish) {
        session++;
        handler.removeCallbacks(scheduler);
        inFlight = false;
        index = 0;
        if (publish) {
            setState(State.CANCELLED, "Stopped");
        } else if (state == State.DRAWING || state == State.PAUSED) {
            state = State.IDLE;
        }
    }

    public void shutdown() {
        session++;
        handler.removeCallbacks(scheduler);
        inFlight = false;
        plan.clear();
        state = State.IDLE;
    }

    private void setState(State next, String text) {
        state = next;
        message = text;
        host.onDrawingStateChanged(next, text, getProgress());
    }

    private void dispatchNext() {
        if (state != State.DRAWING || inFlight) {
            return;
        }
        if (index >= plan.size()) {
            setState(State.COMPLETED, "Completed · " + plan.size() + " strokes");
            return;
        }
        float[] stroke = plan.get(index);
        Path path = new Path();
        path.moveTo(stroke[0], stroke[1]);
        for (int i = 2; i + 1 < stroke.length; i += 2) {
            path.lineTo(stroke[i], stroke[i + 1]);
        }
        float distance = StrokeOptimizer.length(stroke);
        int duration = Math.round(distance * 1000f / Math.max(1, speedPxPerSec));
        duration = Math.max(40, Math.min(maxStrokeDurationMs, duration));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        final int expectedSession = session;
        inFlight = true;
        boolean accepted;
        try {
            accepted = host.dispatchGesture(builder.build(),
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleCompleted(expectedSession);
                                }
                            });
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleCancelled(expectedSession);
                                }
                            });
                        }
                    });
        } catch (RuntimeException exception) {
            accepted = false;
        }
        if (!accepted) {
            inFlight = false;
            setState(State.ERROR, "Android rejected the drawing gesture");
        }
    }

    private void handleCompleted(int expectedSession) {
        if (expectedSession != session) {
            return;
        }
        inFlight = false;
        // The stroke finished, so advance even when the user paused during it:
        // resuming must continue with the next stroke instead of redrawing this
        // one on top of itself.
        index++;
        if (index >= plan.size()) {
            setState(State.COMPLETED, "Completed · " + plan.size() + " strokes");
            return;
        }
        if (state == State.DRAWING) {
            setState(State.DRAWING, "Drawing " + index + " / " + plan.size());
            handler.removeCallbacks(scheduler);
            handler.postDelayed(scheduler, gapMs);
        } else if (state == State.PAUSED) {
            setState(State.PAUSED, "Paused at " + Math.round(getProgress() * 100f) + "%");
        }
    }

    private void handleCancelled(int expectedSession) {
        if (expectedSession != session) {
            return;
        }
        inFlight = false;
        if (state == State.DRAWING) {
            // Keep the current index so resume retries the interrupted stroke.
            setState(State.PAUSED, "Gesture interrupted · resume to continue");
        }
    }

    /**
     * Splits one polyline into chunks no longer than {@code maxDistance} screen
     * pixels. The last point of a chunk is the first point of the next one, so
     * the drawn line has no gap even though each chunk is a separate touch
     * sequence (Android caps a single gesture stroke's duration).
     */
    static ArrayList<float[]> splitForGestures(float[] stroke, float maxDistance) {
        ArrayList<float[]> chunks = new ArrayList<>();
        if (stroke == null || stroke.length < 4) {
            return chunks;
        }
        float limit = Math.max(8f, maxDistance);
        ArrayList<Float> current = new ArrayList<>();
        current.add(stroke[0]);
        current.add(stroke[1]);
        float travelled = 0f;
        for (int i = 2; i + 1 < stroke.length; i += 2) {
            float dx = stroke[i] - stroke[i - 2];
            float dy = stroke[i + 1] - stroke[i - 1];
            float segment = (float) Math.hypot(dx, dy);
            if (travelled + segment > limit && current.size() >= 4) {
                chunks.add(toArray(current));
                current.clear();
                current.add(stroke[i - 2]);
                current.add(stroke[i - 1]);
                travelled = 0f;
            }
            current.add(stroke[i]);
            current.add(stroke[i + 1]);
            travelled += segment;
        }
        if (current.size() >= 4) {
            chunks.add(toArray(current));
        }
        return chunks;
    }

    private static float[] toArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
