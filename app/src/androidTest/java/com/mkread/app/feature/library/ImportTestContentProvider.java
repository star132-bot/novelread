package com.mkread.app.feature.library;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImportTestContentProvider extends ContentProvider {
    private static final String AUTHORITY = "com.mkread.app.import-test";
    private static final ConcurrentHashMap<String, AtomicInteger> REMAINING_FAILURES =
            new ConcurrentHashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Test provider is read-only");
        }
        AtomicInteger failures = failureCounter(uri);
        if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new FileNotFoundException("Injected provider failure");
        }
        byte[] bytes = decodeBytes(uri);
        File directory = getContext().getCacheDir();
        File file = new File(directory, "import-test-" + uri.toString().hashCode() + ".bin");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
        } catch (IOException failure) {
            throw new FileNotFoundException(failure.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        MatrixCursor cursor = new MatrixCursor(
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
        );
        cursor.addRow(new Object[] {uri.getLastPathSegment() + ".txt", decodeBytes(uri).length});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public static Uri register(String id, byte[] bytes, int openFailures) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(id)
                .appendQueryParameter(
                        "data",
                        Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP)
                )
                .appendQueryParameter("failures", Integer.toString(openFailures))
                .appendQueryParameter("nonce", Long.toString(System.nanoTime()))
                .build();
    }

    public static Uri register(String id, byte[] bytes) {
        return register(id, bytes, 0);
    }

    public static void clear() {
        REMAINING_FAILURES.clear();
    }

    private static byte[] decodeBytes(Uri uri) {
        String encoded = uri.getQueryParameter("data");
        if (encoded == null) {
            return new byte[0];
        }
        return Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static AtomicInteger failureCounter(Uri uri) {
        String key = uri.toString();
        AtomicInteger existing = REMAINING_FAILURES.get(key);
        if (existing != null) {
            return existing;
        }
        String value = uri.getQueryParameter("failures");
        int initial = value == null ? 0 : Integer.parseInt(value);
        AtomicInteger created = new AtomicInteger(initial);
        AtomicInteger raced = REMAINING_FAILURES.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }
}
