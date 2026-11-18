# Android controls

This Android prototype uses an AccessibilityService to draw generated paths within a selected screen rectangle. Android 8.0 or later is required.

1. Install the debug APK from the **Build Android APK** GitHub Actions artifact.
2. Open Auto Sketch Mobile, choose an image, and generate the sketch.
3. Open Android Accessibility Settings and enable the Auto Sketch Mobile drawing service.
4. Return to Auto Sketch Mobile and choose **Open floating controls**.
5. Open Instagram's drawing canvas or another drawing application.
6. Drag **≡ Auto Sketch • Drag** to move the floating control panel.
7. Tap **Select / resize area**. A blue outlined rectangle appears.
8. Drag the **MOVE** grip to reposition the rectangle. Drag any of the four corner handles to resize it.
9. Tap or interact with the underlying application outside the grips. The selection overlay does not capture those touches.
10. Tap **Use area** to save the rectangle. The drawing gestures will be mapped inside its bounds.
11. Move the control panel outside the canvas. Tap **Start / Resume**, **Pause**, or **Stop**.
12. Tap **−** to collapse the controls, or **Hide** to remove them.

The four corner grips, MOVE grip, and Use area / Cancel buttons are touchable. The rest of the screen remains interactive during area selection. The service uses Android accessibility overlays rather than a full-screen touch-blocking window. Accessibility access is required, but overlay permission and root access are not.

The selected rectangle uses absolute screen coordinates, so reselect it after rotating your phone or changing the drawing canvas layout. Gesture speed and canvas behavior depend on the target application. This APK has not yet been tested with every Instagram version.
