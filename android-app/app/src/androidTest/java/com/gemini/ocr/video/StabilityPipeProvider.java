package com.gemini.ocr.video;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Pure Java: this test APK provider starts in its own process without the target Kotlin runtime. */
public final class StabilityPipeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private String fixtureName(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || !("EP01.mp4".equals(name) || "EP02.mp4".equals(name)
                || "EP03.mp4".equals(name) || "broken.mp4".equals(name))) {
            throw new IllegalArgumentException("Unknown synthetic stability fixture");
        }
        return name;
    }

    @Override public String getType(Uri uri) { fixtureName(uri); return "video/mp4"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String name = fixtureName(uri);
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        long size = 5L;
        if (!"broken.mp4".equals(name)) {
            try (InputStream source = getContext().getAssets().open("stability-source.mp4")) { size = source.available(); }
            catch (IOException error) { throw new IllegalStateException("Synthetic fixture asset unavailable"); }
        }
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = name;
            else if (OpenableColumns.SIZE.equals(columns[index])) values[index] = size;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final String name = fixtureName(uri);
        if (!"r".equals(mode)) throw new SecurityException("Synthetic fixture provider is read only");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException error) { throw new FileNotFoundException("Cannot open synthetic pipe"); }
        Log.i("StabilityNative", "synthetic-provider-open:" + name);
        new Thread(new Runnable() {
            @Override public void run() {
                // A caller can close early after proving ESPIPE; both owned streams still close.
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if ("broken.mp4".equals(name)) output.write(new byte[] { 0, 1, 2, 3, 4 });
                    else try (InputStream source = getContext().getAssets().open("stability-source.mp4")) {
                        byte[] buffer = new byte[32 * 1024];
                        int count;
                        while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    Log.i("StabilityNative", "synthetic-provider-write-complete:" + name);
                } catch (IOException error) {
                    Log.i("StabilityNative", "synthetic-provider-write-ended:" + error.getClass().getSimpleName());
                }
            }
        }, "stability-fixture-pipe").start();
        return pipe[0];
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
