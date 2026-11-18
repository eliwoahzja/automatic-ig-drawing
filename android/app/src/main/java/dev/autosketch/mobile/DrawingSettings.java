package dev.autosketch.mobile;

/**
 * Every knobs the sketch pipeline and the gesture mapper use. Pure Java so the
 * pipeline can be verified in JVM unit tests.
 */
public final class DrawingSettings {

    public static final int DETAIL_LOW = 0;
    public static final int DETAIL_MEDIUM = 1;
    public static final int DETAIL_HIGH = 2;
    public static final int DETAIL_ULTRA = 3;

    public static final int FIT_CONTAIN = 0;
    public static final int FIT_FILL = 1;
    public static final int FIT_STRETCH = 2;

    /** Outline sketch: traced contours only, the classic look. */
    public static final int MODE_SKETCH = 0;
    /** Cleaned line art: fewer, longer outlines and strong double-line removal. */
    public static final int MODE_CLEAN = 1;
    /** Full drawing: cleaned outlines plus tonal cross-hatching for shading. */
    public static final int MODE_FULL = 2;
    public static final int MODE_COLOR = 3;

    public static final String[] DETAIL_NAMES = {"Low", "Medium", "High", "Ultra"};
    public static final String[] FIT_NAMES = {"Contain", "Fill", "Stretch"};
    public static final String[] MODE_NAMES = {"Sketch", "Clean", "Full", "Color"};

    /** 0..3, see DETAIL_*. */
    public int detailLevel = DETAIL_HIGH;
    /** 0..2, see MODE_*. */
    public int renderMode = MODE_SKETCH;

    /** 0..100, drives the Gaussian sigma before edge detection. */
    public int noiseReduction = 40;
    /** 0..100, higher keeps weaker edges (more detail, more noise). */
    public int edgeSensitivity = 55;
    /** -100..100. */
    public int contrast = 0;
    /** -100..100. */
    public int brightness = 0;
    public boolean invert = false;
    /** 0..100, flattens light backgrounds so paper/texture noise disappears. */
    public int backgroundSuppression = 22;

    /** 0..100, corner rounding of traced polylines. */
    public int smoothing = 30;
    /** 0..100, Douglas-Peucker tolerance. */
    public int simplification = 30;
    /** 0..100, duplicate/overlapping stroke rejection. */
    public int strokeOverlapReduction = 45;

    /** Screen pixels between resampled gesture points. */
    public int pointSpacingPx = 3;
    /** Screen pixels; shorter strokes are dropped so no dots are drawn. */
    public int minStrokeLengthPx = 8;
    /** Hard cap for one dispatched gesture stroke. */
    public int maxStrokeDurationMs = 2400;
    /** Screen pixels per second while a stroke is drawn. */
    public int drawingSpeedPxPerSec = 520;
    /** Minimum pause between two strokes, keeps apps responsive. */
    public int strokeGapMs = 24;

    public String aiEndpointUrl = "";
    public int colorDensity = 55;
    public String captionText = "";
    public int captionXPercent = 65;
    public int captionYPercent = 40;
    public int captionSizePercent = 9;
    public boolean captionReplaceOriginal = true;

    public int fitMode = FIT_CONTAIN;
    public boolean centerImage = true;
    public boolean showProgressPreview = false;

    public DrawingSettings() {
    }

    public DrawingSettings copy() {
        DrawingSettings other = new DrawingSettings();
        other.detailLevel = detailLevel;
        other.renderMode = renderMode;
        other.noiseReduction = noiseReduction;
        other.edgeSensitivity = edgeSensitivity;
        other.contrast = contrast;
        other.brightness = brightness;
        other.invert = invert;
        other.backgroundSuppression = backgroundSuppression;
        other.smoothing = smoothing;
        other.simplification = simplification;
        other.strokeOverlapReduction = strokeOverlapReduction;
        other.pointSpacingPx = pointSpacingPx;
        other.minStrokeLengthPx = minStrokeLengthPx;
        other.maxStrokeDurationMs = maxStrokeDurationMs;
        other.drawingSpeedPxPerSec = drawingSpeedPxPerSec;
        other.strokeGapMs = strokeGapMs;
        other.aiEndpointUrl = aiEndpointUrl;
        other.colorDensity = colorDensity;
        other.captionText = captionText;
        other.captionXPercent = captionXPercent;
        other.captionYPercent = captionYPercent;
        other.captionSizePercent = captionSizePercent;
        other.captionReplaceOriginal = captionReplaceOriginal;
        other.fitMode = fitMode;
        other.centerImage = centerImage;
        other.showProgressPreview = showProgressPreview;
        return other;
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** Long edge of the working image used for edge detection. */
    public int maxProcessingDimension() {
        switch (detailLevel) {
            case DETAIL_LOW:
                return 420;
            case DETAIL_MEDIUM:
                return 560;
            case DETAIL_ULTRA:
                return 900;
            case DETAIL_HIGH:
            default:
                return 720;
        }
    }

    public int maxStrokes() {
        switch (detailLevel) {
            case DETAIL_LOW:
                return 240;
            case DETAIL_MEDIUM:
                return 420;
            case DETAIL_ULTRA:
                return 820;
            case DETAIL_HIGH:
            default:
                return 620;
        }
    }

    /** Stroke budget including hatching, per render mode. */
    public int maxStrokesForRenderMode() {
        int base = maxStrokes();
        if (renderMode == MODE_FULL) {
            return Math.round(base * 1.35f);
        }
        if (renderMode == MODE_COLOR) {
            return Math.max(90, Math.round(base * .75f));
        }
        if (renderMode == MODE_CLEAN) {
            return Math.max(60, Math.round(base * 0.65f));
        }
        return base;
    }

    /** Budget for the hatch layers alone, proportional to the detail level. */
    public int hatchStrokeBudget() {
        return 180 + detailLevel * 110;
    }

    /**
     * Minimum two-sided colour contrast a traced polyline needs to be kept.
     *
     * <p>Edge detection can only see that neighbouring pixels differ, which is
     * exactly what glitter, watermarks, grain and soft photo texture do; on a
     * decorated image that produced hundreds of scribbles over the whole
     * canvas. A real outline separates two areas of different colour, so this
     * threshold removes texture while keeping genuine contours.</p>
     */
    public float minStepContrast() {
        float base;
        switch (detailLevel) {
            case DETAIL_LOW:
                base = 11.5f;
                break;
            case DETAIL_MEDIUM:
                base = 10f;
                break;
            case DETAIL_ULTRA:
                base = 7.5f;
                break;
            case DETAIL_HIGH:
            default:
                base = 8.5f;
                break;
        }
        if (renderMode == MODE_CLEAN) {
            base *= 1.3f;
        } else if (renderMode == MODE_COLOR) {
            base *= 1.12f;
        } else if (renderMode == MODE_FULL) {
            base *= 1.1f;
        }
        // More noise reduction means the user asked for a calmer drawing.
        base *= 1f + (clamp(noiseReduction, 0, 100) - 40) / 200f;
        return clamp(base, 4f, 18f);
    }

    /** Clean mode drops small details; full mode keeps short hatch dashes. */
    public float minStrokeMultiplier() {
        if (renderMode == MODE_CLEAN) {
            return 2.2f;
        }
        if (renderMode == MODE_FULL) {
            return 0.85f;
        }
        return 1f;
    }

    public float simplifyMultiplier() {
        return renderMode == MODE_CLEAN ? 1.6f : 1f;
    }

    /**
     * Smoothing sigma for a working image of the given short side.
     *
     * <p>The filter has to scale with the processing resolution. A fixed 1.4 px
     * blur is enough at 400 px but leaves glitter, grain and watermark texture
     * intact at 900 px, which then gets traced as hundreds of scribbles. Scaling
     * the kernel keeps the same amount of texture removal at every detail level.</p>
     */
    public float blurSigma(int workingShortSide) {
        // Small working images already smooth enough; below ~1.4 px thin ink
        // rings start to break up into fragments at the skeleton stage.
        float base = Math.max(1.39f, 0.3f + Math.max(0, workingShortSide) * 0.0026f);
        float knob = clamp(noiseReduction, 0, 100) / 100f;
        return Math.max(0.85f, base * (0.5f + knob * 1.25f));
    }

    /** Compatibility helper: sigma for a 720 px working image. */
    public float blurSigma() {
        return blurSigma(720);
    }

    /**
     * Gain of the local-contrast normalisation applied before edge detection.
     * Faint line art (eyes, brows, pale outlines) is only a few grey levels from
     * its surroundings; normalising against a local mean makes those details
     * survive the Canny thresholds while strong outlines stay unaffected.
     */
    public float localContrastGain() {
        switch (detailLevel) {
            case DETAIL_LOW:
                return 1.0f;
            case DETAIL_MEDIUM:
                return 1.25f;
            case DETAIL_ULTRA:
                return 1.8f;
            case DETAIL_HIGH:
            default:
                return 1.5f;
        }
    }

    public int backgroundLevel() {
        return 255 - Math.round(clamp(backgroundSuppression, 0, 100) * 0.7f);
    }

    /** Fraction of non-maximum-suppressed pixels kept as strong edges. */
    public float strongEdgeFraction() {
        float base;
        switch (detailLevel) {
            case DETAIL_LOW:
                base = 0.085f;
                break;
            case DETAIL_MEDIUM:
                base = 0.15f;
                break;
            case DETAIL_ULTRA:
                base = 0.30f;
                break;
            case DETAIL_HIGH:
            default:
                base = 0.22f;
                break;
        }
        float sensitivity = 1f + (clamp(edgeSensitivity, 0, 100) - 55) / 55f * 0.75f;
        return clamp(base * sensitivity, 0.035f, 0.55f);
    }

    /**
     * Low/high hysteresis ratio. Higher detail levels keep weaker connected
     * edges (soft jaw lines, eyelids, fabric folds) instead of only the strong
     * outline seeds.
     */
    public float weakEdgeRatio() {
        switch (detailLevel) {
            case DETAIL_LOW:
                return 0.46f;
            case DETAIL_MEDIUM:
                return 0.38f;
            case DETAIL_ULTRA:
                return 0.25f;
            case DETAIL_HIGH:
            default:
                return 0.30f;
        }
    }

    public int minComponentPixels() {
        int base;
        switch (detailLevel) {
            case DETAIL_LOW:
                base = 18;
                break;
            case DETAIL_MEDIUM:
                base = 12;
                break;
            case DETAIL_ULTRA:
                base = 5;
                break;
            case DETAIL_HIGH:
            default:
                base = 8;
                break;
        }
        return Math.max(3, Math.round(base * (1f + clamp(noiseReduction, 0, 100) / 100f)));
    }

    public float simplifyTolerance() {
        return 0.45f + clamp(simplification, 0, 100) / 100f * 2.1f;
    }

    public float smoothStrength() {
        return clamp(smoothing, 0, 100) / 100f;
    }

    /** Occupancy ratio above which a stroke is considered already drawn. */
    public float overlapRejectRatio() {
        return clamp(0.82f - clamp(strokeOverlapReduction, 0, 100) / 100f * 0.52f, 0.22f, 0.85f);
    }
}
