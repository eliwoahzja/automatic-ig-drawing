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
    static ArrayList<float[]> strokes = new ArrayList<>();
    Bitmap image;
    ImageView preview;
    TextView status;
    SeekBar detail;
    int quality = 2;
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
            status.setText("Image loaded. Generate sketch.");
        } catch(Exception e) { status.setText("Unable to load image: "+e.getMessage()); }
    }
    void processImage() {
        if(image==null) { status.setText("Select an image first"); return; }
        int w=image.getWidth(),h=image.getHeight(),step=detail.getProgress()+2;
        int[] pixels=new int[w*h];
        image.getPixels(pixels,0,w,0,0,w,h);
        int[] gray=new int[w*h];
        for(int i=0;i<gray.length;i++) {
            int c=pixels[i];gray[i]=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;
        }
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        result.eraseColor(Color.WHITE);
        ArrayList<float[]> paths=new ArrayList<>();
        for(int y=1;y<h-1;y+=step) {
            ArrayList<Float> points=new ArrayList<>();
            for(int x=1;x<w-1;x+=step) {
                int dx=Math.abs(gray[y*w+x+1]-gray[y*w+x-1]);
                int dy=Math.abs(gray[(y+1)*w+x]-gray[(y-1)*w+x]);
                boolean edge=dx+dy>Math.max(30,110-detail.getProgress()*20);
                if(edge) {
                    result.setPixel(x,y,Color.BLACK);
                    points.add((float)x/w);points.add((float)y/h);
                } else if(points.size()>=4) {
                    addPath(paths,points);points.clear();
                } else points.clear();
            }
            if(points.size()>=4)addPath(paths,points);
        }
        strokes=paths;
        preview.setImageBitmap(result);
        status.setText("Sketch ready: "+paths.size()+" strokes. Enable Accessibility, open floating controls, and adjust the drawing frame.");
    }
    static void addPath(ArrayList<float[]> out,ArrayList<Float> src) {
        float[] p=new float[src.size()];
        for(int i=0;i<p.length;i++)p[i]=src.get(i);
        out.add(p);
    }
}
