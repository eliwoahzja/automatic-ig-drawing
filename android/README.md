# Auto Sketch (Android)

Turns any image into clean strokes and draws them on Instagram's canvas (or any drawing app)
through accessibility gestures. No root, no APK modification.

## AI artist (Gemini, runs straight from the phone)

There is no server to deploy. Paste your own Gemini API key in the app and the phone talks
directly to Google.

1. Create a free key at https://aistudio.google.com
2. Open **AI artist → Gemini key & model** and paste it. It is stored privately on the device and is
   sent only to Google, only when you press **Refine** or **Draw**.
3. Pick a model: **3.5 Flash** (`gemini-3.5-flash`, most accurate) or **Flash-Lite**
   (`gemini-3.1-flash-lite`, faster and cheaper). If the chosen model is busy, out of quota or
   unavailable, the other one is tried automatically.

### Refine
Press **Generate sketch** first. Gemini then sees the original picture plus a numbered overlay of
the traced lines and returns:
- which lines are noise (texture, glitter, shadows, doubled outlines, scribbles) and should go,
- every line of lettering, transcribed exactly with its box. The app re-typesets it as clean
  vector lettering, so text is readable instead of scribbled,
- a few extra strokes for important features the trace missed.

The accurate edge trace is kept, so lines stay exactly on the picture.

### Draw
Gemini plans the whole line art itself in one of three styles (**Outline**, **Detailed**,
**Minimal**). The strokes are smoothed with Catmull-Rom curves and then snapped onto the nearest
traced edge, so the AI decides what to draw and the trace decides where each line sits. Lettering is
transcribed and typeset by the app, never drawn freehand. Use the optional note to steer it
("focus on the face"). Draw can take up to a minute; Minimal is the quickest.

### On-device enhance
The older offline option (ML Kit text and object detection) is still available as a small link
under the AI card. Nothing leaves the phone.

## Notes
- Charges, if any, come from your own Gemini key and quota.
- AI strokes are approximate illustrations. Always check the preview before drawing.
- AI output is monochrome line art. Color mode keeps using the guided palette workflow.
- Passing compilation does not prove that Instagram accepts all gestures on your device.

## Build and tests
GitHub Actions runs the Android unit tests (`gradle testDebugUnitTest`) and builds a debug APK.

### API key storage

Gemini keys are encrypted with Android Keystore AES-GCM and migrated from older plain preferences when present. The key is sent only to the Google Gemini HTTPS endpoint when Refine or Draw is used. If secure storage is unavailable, the key is not saved to device preferences.
