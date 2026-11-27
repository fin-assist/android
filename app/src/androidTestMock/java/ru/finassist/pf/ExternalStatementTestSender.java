package ru.finassist.pf;

import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Emulates Chrome's ACTION_VIEW in the separate test APK's process. */
public final class ExternalStatementTestSender extends Activity {
    public static final String OMIT_READ_GRANT = "omit_read_grant";
    static final String MEDIA_STORE_DOWNLOAD = "media_store_download";
    static final String NOT_OFX = "not_ofx";
    private static final String BINARY_MIME_TYPE = "application/octet-stream";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean omitGrant = getIntent().getBooleanExtra(OMIT_READ_GRANT, false);
        boolean mediaStore = getIntent().getBooleanExtra(MEDIA_STORE_DOWNLOAD, false);
        Uri uri = mediaStore ? createDownload(getIntent().getBooleanExtra(NOT_OFX, false))
                : Uri.parse("content://" + getPackageName() + ExternalStatementTestProvider.AUTHORITY_SUFFIX
                        + (omitGrant ? "/2" : "/1"));
        Intent request = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mediaStore ? BINARY_MIME_TYPE : ExternalStatementTestProvider.MIME_TYPE)
                .setPackage("ru.finassist.pf.mock.e2e");
        if (!omitGrant) request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(request);
        finish();
    }

    private Uri createDownload(boolean notOfx) {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("MediaStore downloads require API 29");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "fin549-" + UUID.randomUUID() + ".ofx");
        values.put(MediaStore.Downloads.MIME_TYPE, BINARY_MIME_TYPE);
        Uri file = getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (file == null) throw new IllegalStateException("Cannot add a synthetic download");
        try (OutputStream output = getContentResolver().openOutputStream(file)) {
            if (output == null) throw new IOException("Cannot write the synthetic download");
            if (notOfx) {
                output.write("Not a bank statement".getBytes(StandardCharsets.UTF_8));
            } else {
                try (InputStream input = getAssets().open("external-statement.ofx")) {
                    byte[] buffer = new byte[1024];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write the synthetic download", error);
        }
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentUris.parseId(file));
    }
}
