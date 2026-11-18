# Android AI drawing

## What changed

The Android version has two AI-assisted workflows in addition to the existing edge and color tracing engine.

### AI Enhance: private, on-device

Press **Generate sketch**, then **AI Enhance**.

Two local trained machine-learning models run on the source image:
- Google ML Kit Text Recognition v2 detects text lines and their bounding boxes.
- Google ML Kit Object Detection detects candidate objects and their bounding boxes.

The app traces object crops at Ultra detail, filters redundant paths, and reconstructs recognized text as vector lettering inside the recognized text boxes. The resulting strokes replace the original preview. Your image does not leave the phone when you use AI Enhance. Recognized words are copied into the editable lettering field, so review the results before drawing.

These generic models do not guarantee that every cartoon, anime character, or stylized title will be recognized. Keep the normal **Generate sketch** option available as a fallback.

### AI Draw: optional private cloud

Press **AI Draw** to use an optional two-stage cloud line-art service:
1. An AI image analyst describes important shapes, details, and legible text.
2. An AI stroke planner uses that analysis plus the original image to return normalized polylines.

The app validates and previews the returned strokes before you select the drawing area.

AI Draw is **not usable until you deploy and configure the private server**. It is not active by default and does not upload images automatically. No AI provider key is stored in the APK.

#### Deploy the backend

The repository contains a Vercel-compatible serverless function at `api/ai-draw.js`.

Deploy the repository to a private Vercel project. Configure the following server environment variables:

- `OPENAI_API_KEY` : the provider key. Set this only in Vercel environment settings.
- `AI_DRAW_ACCESS_TOKEN` : a long, random secret that restricts access to your endpoint.
- `AI_MODEL` : optional vision-capable OpenAI model (defaults to `gpt-4.1`).

Deploy the backend and confirm the function is available at:

`https://YOUR-DEPLOYMENT.vercel.app/api/ai-draw`

In Android, open **Adjust drawing & lettering**, then enter the HTTPS URL. Enter the private backend access token in the separate field. Never enter your OpenAI provider key into the Android app. The private token is kept only in current UI memory, while the endpoint URL is saved locally.

The AI backend requires an internet connection and sends the selected compressed image to the AI service only when you press AI Draw. Provider charges apply to the configured server. Protect access, monitor usage, and restrict public deployment.

### Drawing restrictions

Both outputs use the existing draggable, resizable drawing-area overlay and Android accessibility gestures. Automated strokes remain limited to the selected drawing rectangle. Cloud AI drawing paths are approximate illustrations, not pixel-accurate vectorization. Review the preview before drawing.

Color rendering continues to use the existing guided manual palette workflow. AI Draw currently creates monochrome line strokes rather than automatic multi-color fills.

### Builds and tests

GitHub Actions runs Android unit tests and compiles a debug APK. Cloud AI server tests run separately in the same workflow.

Passing compilation does not prove that Instagram accepts all gestures on your device.
