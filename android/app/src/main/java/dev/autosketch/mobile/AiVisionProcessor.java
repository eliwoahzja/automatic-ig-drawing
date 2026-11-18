package dev.autosketch.mobile;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class AiVisionProcessor {
    public interface Callback { void onComplete(AiScene scene); }

    private AiVisionProcessor() {}

    public static void analyze(Bitmap source, Callback callback) {
        if (source == null || source.isRecycled()) {
            callback.onComplete(AiScene.empty());
            return;
        }
        final InputImage image = InputImage.fromBitmap(source, 0);
        final int imageWidth = source.getWidth(), imageHeight = source.getHeight();
        final ArrayList<AiScene.Region> letters = new ArrayList<>();
        final ArrayList<AiScene.Region> objects = new ArrayList<>();
        final AtomicInteger remaining = new AtomicInteger(2);
        final StringBuilder issues = new StringBuilder();
        Runnable finish = () -> {
            if (remaining.decrementAndGet() == 0) {
                callback.onComplete(new AiScene(objects, letters,
                        issues.length() == 0 ? "On-device AI analysis complete"
                                : issues.toString()));
            }
        };

        final TextRecognizer reader = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            reader.process(image).addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful() && task.getResult() != null) {
                        for (Text.TextBlock block : task.getResult().getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                Rect area = line.getBoundingBox();
                                String value = line.getText();
                                if (area != null && value != null && value.trim().length() >= 2
                                        && letters.size() < 32) {
                                    letters.add(region(area, value, imageWidth, imageHeight));
                                }
                            }
                        }
                    } else {
                        issues.append("Text model unavailable. ");
                    }
                } finally {
                    reader.close();
                    finish.run();
                }
            });
        } catch (Exception e) {
            reader.close();
            issues.append("Text model failed. ");
            finish.run();
        }

        ObjectDetectorOptions config = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build();
        final ObjectDetector detector = ObjectDetection.getClient(config);
        try {
            detector.process(image).addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful() && task.getResult() != null) {
                        for (DetectedObject object : task.getResult()) {
                            Rect area = object.getBoundingBox();
                            if (area == null) continue;
                            AiScene.Region region = region(area, "", imageWidth, imageHeight);
                            if (region.width * region.height > .008f
                                    && objects.size() < 6) objects.add(region);
                        }
                    } else {
                        issues.append("Object model unavailable. ");
                    }
                } finally {
                    detector.close();
                    finish.run();
                }
            });
        } catch (Exception e) {
            detector.close();
            issues.append("Object model failed. ");
            finish.run();
        }
    }

    private static AiScene.Region region(Rect area, String value, int width, int height) {
        float w = Math.max(1f, width), h = Math.max(1f, height);
        return new AiScene.Region(
                Math.max(0f, area.left / w), Math.max(0f, area.top / h),
                Math.max(0f, area.width() / w), Math.max(0f, area.height() / h),
                value);
    }
}
