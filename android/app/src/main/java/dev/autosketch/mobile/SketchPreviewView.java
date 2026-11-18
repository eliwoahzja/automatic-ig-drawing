package dev.autosketch.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;

/**
 * Renders the source image and, once generated, the exact polylines that will be
 * sent as gestures. No separate drawing algorithm is used for previews.
 */
public final class SketchPreviewView extends View {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private Bitmap source;
    private Sketch sketch = Sketch.empty();
    private DrawingSettings settings = new DrawingSettings();

    public SketchPreviewView(Context context) {
        this(context, null);
    }

    public SketchPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cardPaint.setColor(0xFF12131A);
        borderPaint.setColor(0x1AFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        imagePaint.setAlpha(120);
        strokePaint.setColor(0xFFF2F4F8);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(dp(1.1f));
        placeholderPaint.setColor(0xFF667085);
        placeholderPaint.setTextSize(dp(12));
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setSource(Bitmap bitmap) {
        source = bitmap;
        invalidate();
    }

    public void setSketch(Sketch nextSketch, DrawingSettings nextSettings) {
        sketch = nextSketch == null ? Sketch.empty() : nextSketch;
        settings = nextSettings == null ? new DrawingSettings() : nextSettings;
        strokePaint.setStrokeWidth(dp(1.1f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(6);
        float radius = dp(16);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(rect, cardPaint);

        CanvasMapper.Rect area = new CanvasMapper.Rect(rect.left, rect.top, rect.right, rect.bottom);
        if (source != null && !source.isRecycled()) {
            CanvasMapper.Rect destination = CanvasMapper.destination(area,
                    source.getWidth(), source.getHeight(), settings.fitMode, settings.centerImage);
            canvas.drawBitmap(source, null,
                    new RectF(destination.left, destination.top, destination.right, destination.bottom),
                    imagePaint);
        }
        if (sketch != null && !sketch.isEmpty()) {
            if (settings.renderMode == DrawingSettings.MODE_COLOR &&
                    !sketch.colorLayers.isEmpty()) {
                for (ColorLayer layer : sketch.colorLayers) {
                    strokePaint.setColor(layer.color);
                    renderStrokes(canvas, CanvasMapper.mapStrokes(layer.strokes, area,
                            sketch.width, sketch.height, settings));
                }
            } else {
                strokePaint.setColor(0xFFF2F4F8);
                renderStrokes(canvas, CanvasMapper.mapStrokes(sketch.strokes, area,
                        sketch.width, sketch.height, settings));
            }
        } else {
            String text = source == null ? "Choose an image to begin" : "Tap Generate sketch";
            canvas.drawText(text, rect.centerX(),
                    rect.centerY() - (placeholderPaint.descent() + placeholderPaint.ascent()) / 2f,
                    placeholderPaint);
        }
        canvas.restore();
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
    }
    private void renderStrokes(Canvas canvas, ArrayList<float[]> mapped) {
        Path path = new Path();
        for (float[] stroke : mapped) {
            if (stroke.length < 4) continue;
            path.reset();
            path.moveTo(stroke[0], stroke[1]);
            for (int i = 2; i + 1 < stroke.length; i += 2) {
                path.lineTo(stroke[i], stroke[i + 1]);
            }
            canvas.drawPath(path, strokePaint);
        }
    }

}
