package com.gemini.ocr;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.VideoView;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;

/** Plays only the bundled synthetic Chinese acceptance clip. No external URI is accepted. */
public class LiveVideoSceneActivity extends Activity {
    private VideoView video;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        File source=new File(getCacheDir(),"live-requested-phrase.mp4");
        try(InputStream input=getAssets().open("live-requested-phrase.mp4"); FileOutputStream output=new FileOutputStream(source)) {
            byte[] buffer=new byte[32768];int count;
            while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
        }catch(Exception error){throw new IllegalStateException("Missing synthetic Live fixture",error);}
        video=new VideoView(this);video.setContentDescription("gemini-live-video-scene");setContentView(video);
        video.setVideoPath(source.getAbsolutePath());
        video.setOnPreparedListener(player->{player.setLooping(true);video.start();});
    }
    @Override protected void onStop(){if(video!=null)video.stopPlayback();super.onStop();}
}
