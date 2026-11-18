package dev.autosketch.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.util.ArrayList;

public class DrawService extends AccessibilityService {
    static DrawService instance;
    WindowManager wm;
    View controls;
    View calibration;
    Handler handler=new Handler(Looper.getMainLooper());
    Rect bounds;
    int index;
    boolean paused=true;
    boolean cancelled=false;
    TextView progress;
    @Override protected void onServiceConnected() { instance=this;wm=(WindowManager)getSystemService(WINDOW_SERVICE); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {cancelled=true;}
    @Override public void onDestroy() {cancelled=true;remove(controls);remove(calibration);instance=null;super.onDestroy();}
    void remove(View v) { if(v!=null)try{wm.removeView(v);}catch(Exception ignored){} }
    WindowManager.LayoutParams params(int width,int height,int flags) {
        return new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT);
    }
    Button button(String s) {Button b=new Button(this);b.setText(s);return b;}
    public void showControls() {
        remove(controls);
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(1);bar.setPadding(8,8,8,8);bar.setBackgroundColor(0xee20212b);
        progress=new TextView(this);progress.setTextColor(-1);progress.setText("Ready");bar.addView(progress);
        Button select=button("Select area");select.setOnClickListener(v->selectArea());bar.addView(select);
        Button play=button("Start / Resume");play.setOnClickListener(v->{
            if(bounds==null){progress.setText("Select area first");return;}
            if(index>=MainActivity.strokes.size())index=0;
            cancelled=false;paused=false;drawNext();
        });bar.addView(play);
        Button pause=button("Pause");pause.setOnClickListener(v->{paused=true;progress.setText("Paused");});bar.addView(pause);
        Button stop=button("Stop");stop.setOnClickListener(v->{paused=true;cancelled=true;index=0;progress.setText("Stopped");});bar.addView(stop);
        Button hide=button("Hide controls");hide.setOnClickListener(v->{paused=true;remove(controls);controls=null;});bar.addView(hide);
        controls=bar;
        WindowManager.LayoutParams p=params(-2,-2,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        p.gravity=Gravity.TOP|Gravity.END;p.x=10;p.y=100;
        wm.addView(controls,p);
    }
    void selectArea() {
        paused=true;
        remove(controls);controls=null;
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(0x55000000);
        TextView hint=new TextView(this);
        hint.setText("Drag diagonally across drawing area");
        hint.setTextColor(-1);
        frame.addView(hint);
        frame.setOnTouchListener(new View.OnTouchListener() {
            float x0,y0;
            @Override public boolean onTouch(View v,android.view.MotionEvent e) {
                if(e.getAction()==MotionEvent.ACTION_DOWN){x0=e.getRawX();y0=e.getRawY();return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){
                    int left=(int)Math.min(x0,e.getRawX()),right=(int)Math.max(x0,e.getRawX());
                    int top=(int)Math.min(y0,e.getRawY()),bottom=(int)Math.max(y0,e.getRawY());
                    if(right-left>20&&bottom-top>20)bounds=new Rect(left,top,right,bottom);
                    remove(calibration);calibration=null;showControls();return true;
                }
                return true;
            }
        });
        calibration=frame;
        wm.addView(frame,params(-1,-1,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN));
    }
    void drawNext() {
        if(paused||cancelled||bounds==null)return;
        ArrayList<float[]> paths=MainActivity.strokes;
        if(index>=paths.size()){paused=true;progress.setText("Complete");return;}
        float[] points=paths.get(index++);
        if(points.length<4){handler.post(this::drawNext);return;}
        Path p=new Path();
        float x=bounds.left+points[0]*bounds.width(),y=bounds.top+points[1]*bounds.height();
        p.moveTo(x,y);
        for(int i=2;i<points.length;i+=2)p.lineTo(bounds.left+points[i]*bounds.width(),bounds.top+points[i+1]*bounds.height());
        long duration=Math.max(50,Math.min(1000,points.length*4L));
        GestureDescription.Builder builder=new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(p,0,duration));
        progress.setText(index+" / "+paths.size());
        boolean accepted=dispatchGesture(builder.build(),new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){handler.postDelayed(()->drawNext(),50);}
            @Override public void onCancelled(GestureDescription g){paused=true;handler.post(()->progress.setText("Gesture interrupted"));}
        },handler);
        if(!accepted){paused=true;progress.setText("Gesture unavailable");}
    }
}
