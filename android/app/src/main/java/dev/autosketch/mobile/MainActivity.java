package dev.autosketch.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    static volatile ArrayList<float[]> strokes = new ArrayList<>();
    static volatile int sketchWidth = 1;
    static volatile int sketchHeight = 1;
    Bitmap image;
    ImageView preview;
    TextView status;
    SeekBar detail;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(24,24,24,24);
        root.setBackgroundColor(0xff12131a);
        TextView title = new TextView(this);
        title.setText("Auto Sketch Mobile");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        status = new TextView(this);
        status.setText("Choose an image to begin");
        status.setTextColor(Color.WHITE);
        root.addView(status);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        Button pick = button("Choose image");
        pick.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i,1); });
        root.addView(pick);
        root.addView(label("Detail level"));
        detail = new SeekBar(this);
        detail.setMax(3);
        detail.setProgress(2);
        root.addView(detail);
        Button process = button("Generate sketch");
        process.setOnClickListener(v -> processImage());
        root.addView(process);
        Button accessibility = button("Enable drawing accessibility");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);
        Button start = button("Open floating controls");
        start.setOnClickListener(v -> {
            if(strokes.isEmpty()) { status.setText("Generate a sketch first"); return; }
            if(DrawService.instance==null) { status.setText("Enable accessibility service first"); return; }
            DrawService.instance.showControls();
            status.setText("Open the drawing canvas. Drag the floating controls and use Select area to move or resize the drawing frame.");
        });
        root.addView(start);
        setContentView(root);
    }
    TextView label(String text) {
        TextView t = new TextView(this); t.setText(text); t.setTextColor(Color.WHITE); return t;
    }
    Button button(String text) { Button b=new Button(this);b.setText(text);return b; }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=1 || result!=RESULT_OK || data==null) return;
        try(InputStream stream=getContentResolver().openInputStream(data.getData())) {
            image=BitmapFactory.decodeStream(stream);
            if(image==null) throw new Exception("Invalid image");
            int w=image.getWidth(),h=image.getHeight();
            double scale=Math.min(1.0,512.0/Math.max(w,h));
            image=Bitmap.createScaledBitmap(image,Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),true);
            preview.setImageBitmap(image);
            strokes = new ArrayList<>();
            status.setText("Image loaded. Generate sketch.");
        } catch(Exception e) { status.setText("Unable to load image: "+e.getMessage()); }
    }
    void processImage() {
        if (image == null) {
            status.setText("Select an image first");
            return;
        }
        final Bitmap selected = image;
        final int level = detail.getProgress();
        status.setText("Tracing connected outlines...");
        new Thread(() -> {
            try {
                SketchEngine.Result result = SketchEngine.generate(selected, level);
                runOnUiThread(() -> {
                    if (image != selected || isFinishing()) return;
                    sketchWidth = result.width;
                    sketchHeight = result.height;
                    strokes = result.strokes;
                    preview.setImageBitmap(result.preview);
                    status.setText("Ready: " + result.strokes.size()
                            + " contour strokes. Choose a thin brush in Instagram for best results.");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (!isFinishing())
                        status.setText("Sketch processing failed: " + exception.getMessage());
                });
            }
        }).start();
    }

}
