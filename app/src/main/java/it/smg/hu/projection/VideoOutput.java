package it.smg.hu.projection;

import android.os.Build;
import android.view.SurfaceView;

import it.smg.hu.config.Settings;
import it.smg.libs.common.Log;

public abstract class VideoOutput extends it.smg.libs.aasdk.projection.VideoOutput {

    protected final Settings settings_;

    protected SurfaceView surfaceView_;

    public VideoOutput(SurfaceView surfaceView){
        surfaceView_ = surfaceView;

        settings_ = Settings.instance();

        fps_ = fpsFromSettings(settings_.video.fps());
        resolution_ = settings_.video.resolution();

        // The Honda Connect Android 4.0.4 head unit uses the legacy OMX decoder.
        // Keep the negotiated stream within its practical 480p/30fps budget,
        // even when a higher video profile was selected in preferences.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                && settings_.advanced.hondaIntegrationEnabled()) {
            fps_ = Math.min(fps_, 30);
            resolution_ = 1; // AA 480p profile (same as the existing default).
        }
        dpi_ = settings_.video.dpi();

        String margin = settings_.video.margin();
        try {
            if (margin != null && !margin.trim().isEmpty()) {
                String[] values = margin.split(",");
                if (values.length == 2) {
                    margin_ = new Rect(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
                }
            }
        } catch (Exception e){
            Log.e(tag(), "error on load margin (" + margin + ")", e);
            margin_ = new Rect(0,0);
        }
    }

    protected int fpsFromSettings(int fps){
        switch (fps){
            case 2:
                return 60;
            case 1:
            default:
                return 30;
        }
    }

//    @Override
//    public int getVideoFPS() {
//        return fps_;
//    }
//
//    @Override
//    public int getVideoResolution() {
//        return resolution_;
//    }
//
//    @Override
//    public int getScreenDPI() {
//        return dpi_;
//    }
//
//    @Override
//    public Rect getVideoMargins() {
//        return margin_;
//    }

    protected abstract String tag();
}
