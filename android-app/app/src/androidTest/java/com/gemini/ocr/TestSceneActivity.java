package com.gemini.ocr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Separate-APK synthetic scene. Java keeps it independent of the target APK's Kotlin runtime. */
public class TestSceneActivity extends Activity {
    private boolean animated;
    private View scene;
    private String sourceText = "Hello traveler";
    private boolean subtitle;
    private float subtitleBaseline = .9f;
    private float subtitleSp = 30f;
    private float sourceCenter = .5f;
    private String eventId = "scene";
    private boolean announceDraw = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray sequence;
    private int sequenceIndex;
    private final Runnable nextScene = () -> {
        sequenceIndex++;
        applySequenceItem();
        if (scene != null) scene.invalidate();
    };
    private void configure(Intent intent) {
        handler.removeCallbacks(nextScene);
        animated = intent.getBooleanExtra("animate", false);
        subtitle = intent.getBooleanExtra("subtitle", false);
        subtitleBaseline = intent.getFloatExtra("subtitleBaseline", .9f);
        subtitleSp = intent.getFloatExtra("subtitleSp", 30f);
        sourceCenter = intent.getFloatExtra("sourceCenter", .5f);
        if (intent.getStringExtra("sourceText") != null) sourceText = intent.getStringExtra("sourceText");
        eventId = intent.getStringExtra("eventId") == null ? "scene" : intent.getStringExtra("eventId");
        sequence = null;
        String encoded = intent.getStringExtra("sequenceJson");
        if (encoded != null) {
            try { sequence = new JSONArray(encoded); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid test sequence", error); }
            sequenceIndex = 0;
            applySequenceItem();
        }
        announceDraw = true;
    }
    private void applySequenceItem() {
        if (sequence == null || sequenceIndex >= sequence.length()) return;
        JSONObject item = sequence.optJSONObject(sequenceIndex);
        if (item == null) throw new IllegalArgumentException("Sequence item must be an object");
        sourceText = item.optString("text", "");
        eventId = item.optString("id", "scene-" + sequenceIndex);
        announceDraw = true;
    }
    private String sourceHash() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
            Normalizer.normalize(sourceText, Normalizer.Form.NFKC).getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 8; i++) output.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
        return output.toString();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        configure(getIntent());
        scene = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(17,33,42));
                paint.setColor(Color.rgb(150,173,186));
                paint.setTextSize(32f * getResources().getDisplayMetrics().density);
                paint.setColor(Color.WHITE);
                if (subtitle) {
                    paint.setColor(Color.rgb(34, 65, 82));
                    canvas.drawRect(0, 0, getWidth(), getHeight() * .75f, paint);
                    paint.setColor(Color.rgb(110, 231, 199));
                    paint.setTextSize(20f * getResources().getDisplayMetrics().density);
                    canvas.drawText("VIDEO TEST / OUTSIDE OCR REGION", 50f, getHeight() * .3f, paint);
                    paint.setColor(Color.YELLOW);
                    paint.setTextSize(subtitleSp * getResources().getDisplayMetrics().density);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    canvas.drawText(sourceText, getWidth() * sourceCenter - paint.measureText(sourceText) / 2, getHeight() * subtitleBaseline, paint);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                } else canvas.drawText(sourceText, 50f, getHeight()*.55f, paint);
                // Keep movement outside the subtitle ROI; the previous timer was covered by the background.
                if (animated) {
                    paint.setColor(Color.rgb(150,173,186));
                    paint.setTextSize(22f * getResources().getDisplayMetrics().density);
                    canvas.drawText("Time: " + SystemClock.elapsedRealtime()/250, 32f, 110f, paint);
                }
                if (announceDraw) {
                    announceDraw = false;
                    try {
                        JSONObject event = new JSONObject().put("event", "source_drawn").put("id", eventId)
                            .put("elapsed_ms", SystemClock.elapsedRealtime()).put("visible", !sourceText.isEmpty())
                            .put("source_hash", sourceHash());
                        Log.i("GeminiScene", event.toString());
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    if (sequence != null && sequenceIndex + 1 < sequence.length()) {
                        handler.postDelayed(nextScene, Math.max(16L, sequence.optJSONObject(sequenceIndex).optLong("durationMs", 3000L)));
                    }
                }
                if (animated) postInvalidateDelayed(250);
            }
        };
        setContentView(scene);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        configure(intent);
        scene.invalidate();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(nextScene);
        super.onDestroy();
    }
}
