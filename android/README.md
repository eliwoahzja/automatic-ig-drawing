# Auto Sketch Mobile (Android)

An Android accessibility app that traces an uploaded image onto the Instagram
drawing canvas — or any other drawing app — with continuous, recognisable
strokes. The desktop Python version in the repository root is unchanged and
continues to work independently.

## What changed

The old Android build grouped edge pixels into short horizontal segments, which
produced dots, blobs and distorted shapes. That code is gone. The drawing engine
is now a real image-processing pipeline:

1. image decoded and bounded (max 1280 px, aspect ratio preserved)
2. grayscale conversion with alpha composited over white (transparent PNG cut-outs stay blank),
   brightness, contrast, invert and local-contrast normalisation
   (so faint eyes, brows and pale outlines survive thresholding) plus background flattening
3. configurable Gaussian smoothing
4. Sobel gradients
5. non-maximum suppression (4-directional sector test)
6. dual-threshold hysteresis with a percentile-based high threshold
7. 8-connected despeckling of tiny components
8. Zhang-Suen thinning to a single-pixel skeleton
9. contour tracing into ordered polylines (endpoints, junctions, loops)
10. Douglas-Peucker simplification
11. junction-fragment merging with tangent continuity
12. distance-based coverage suppression that removes duplicate traces and the second
    line of every thick ink stroke (the "double outline" halo)
13. optional tonal cross-hatching for the full drawing mode
14. nearest-neighbour stroke ordering with direction reversal
15. canvas-adaptive resampling, minimum stroke length and stroke budget, then gesture chunking

Every step is pure Java (`EdgeDetector`, `ContourTracer`, `StrokeOptimizer`,
`HatchGenerator`, `CanvasMapper`, `SketchEngine`) and covered by JVM unit tests.

## Render modes

- **Sketch** (default) draws the cleaned contours only — the classic line-art look.
- **Clean** prunes harder: longer minimum stroke length, stronger simplification and
  aggressive double-line removal. Best for busy photos and small canvases.
- **Full** adds tonal hatching on top of the outlines. Three crossing hatch layers
  cover mid tones, shadows and the darkest areas, generated only near detected edges
  and only where the pixels are actually dark, so backgrounds stay blank. Hatch
  spacing scales with the selected canvas size instead of the source image.

Hatching is drawn with the same continuous gesture pipeline as the outlines; there
are no fills. It needs many short strokes, so use a thin brush and the fastest
comfortable speed. On a small canvas the mapper automatically reduces the stroke
budget and raises the minimum stroke length, which prevents the dense white blob
that a thick brush produces from tiny details.

`ContourTracer` corner handling uses 8-connectivity with a corner-cut rule; without
it circles fragment into hundreds of chains.

Instagram's own doodle canvas applies a native spring smoother to each incoming
stroke (its `DoodleSpringSmoother` in the decoded app is a JNI wrapper), so sending
evenly spaced, already-smoothed points is exactly what the receiving canvas expects.

## Continuous strokes

Each contour becomes one continuous `GestureDescription.StrokeDescription` that
is dispatched only after the previous gesture reports completion. Very long
contours are split into chunks whose end point is the next chunk's start point,
so the line stays continuous while respecting Android's per-gesture duration
limit. Strokes never connect two unrelated contours, and resampling removes the
"dot at every touch" artifact.

Pause stops scheduling new strokes; the active gesture is allowed to finish.
Resume continues at the same stroke, and Stop clears the queue and progress.
Completion is only reported after every stroke finished successfully.

## Canvas fitting

`Contain` (default) fits the whole image inside the selected rectangle and
centres it. `Fill` covers the rectangle and crops the excess. `Stretch` is
opt-in and scales each axis independently. Every mapped point is clamped and
clipped to the selected area.

## Floating controls

Compact dark translucent panel:

- draggable by its header, clamped to the screen
- resize handle in the bottom-right corner with remembered dimensions
- minimise to a small draggable icon that shows drawing progress
- Start, Pause, Resume, Stop, Area, Preview, Settings, Close

Settings include render mode, detail level, edge sensitivity, noise reduction, contrast,
brightness, invert, background suppression, speed, smoothing, minimum/maximum
stroke length, point spacing, simplification, overlap reduction and the canvas
fit mode. Image-processing changes re-run the pipeline in the background and
refresh the on-screen preview before drawing starts.

## Drawing area

Tap **Area** to show a transparent frame with corner handles and a move grip.
The rest of the overlay is `FLAG_NOT_TOUCHABLE`, so the app underneath stays
fully usable while you position the frame. **Use area** saves it, **Cancel**
keeps the previous one. The live preview uses the exact polylines that will be
drawn; it can be hidden before starting.

## Install and use

1. Install the APK from the **Build Android APK** GitHub Actions artifact.
2. Open Auto Sketch, choose an image, tap **Generate sketch**.
3. Enable *Auto Sketch Mobile* in Android accessibility settings.
4. Tap **Floating controls**.
5. In Instagram, open a story/post drawing canvas and pick a thin brush and colour
   (thinnest brush for **Full** mode).
6. Tap **Area**, position the frame over the canvas, tap **Use area**.
7. Move the panel off the canvas, tap **Start**. The panel minimises itself so
   it does not cover the drawing.
8. Use **Pause / Resume / Stop** from the panel (tap the small icon to reopen it).

## Composable source layout

```
android/app/src/main/java/dev/autosketch/mobile/
  MainActivity.java        image selection, generation, preview, entry points
  SketchPreviewView.java   preview renderer (same polylines as gestures)
  SketchEngine.java        pipeline orchestration (pure Java)
  EdgeDetector.java        grayscale, blur, Sobel, NMS, hysteresis, thinning
  ContourTracer.java       skeleton -> ordered polylines
  HatchGenerator.java      tonal cross-hatching for full drawing mode
  StrokeOptimizer.java     resample, simplify, merge, dedupe, order, chunk
  CanvasMapper.java        contain/fill/stretch, clipping, gesture resampling
  DrawingSettings.java     settings model and derived pipeline parameters
  Sketch.java              normalised stroke result
  SketchProcessor.java     bitmap bridge and background-safe processing
  SketchSession.java       shared image/sketch state
  SettingsStore.java       persistence
  DrawingController.java   state machine and gesture scheduler
  DrawService.java         accessibility service, gestures, reprocessing
  OverlayController.java   floating panel, settings page, selection, preview
```

## Build and test

```bash
cd android
gradle testDebugUnitTest   # JVM tests: edge detection, tracing, hatching, mapping, modes
gradle assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/build-android.yml`) runs both on every push that touches
`android/**` and uploads the APK plus the unit-test report.

## Known limitations

- The app draws with accessibility gestures, not by modifying Instagram. Once a
  stroke is dispatched it cannot be cancelled; Stop only prevents later strokes.
- Android caps a single gesture stroke's duration (60 s platform limit); long
  contours are therefore chunked at the configured maximum (default 2.4 s).
- Injected gestures land on the topmost touchable window. Keep the floating
  panel and its minimised icon outside the selected canvas, otherwise that part
  of the drawing is lost. Auto-minimising helps but does not move the icon.
- Brush size, colour and tool must be chosen by the user; the app does not tap
  Instagram controls.
- Instagram changes its UI regularly. The app has been verified by build and
  unit tests, not on a physical device against a live Instagram build.
- Very small canvases automatically receive fewer, longer strokes. If detail is
  still lost, select a larger area or use **Clean** mode.
- **Full** mode is stroke-heavy: a 1080 px canvas can receive more than a thousand
  short hatch strokes, so a complete drawing takes noticeably longer than Sketch.
