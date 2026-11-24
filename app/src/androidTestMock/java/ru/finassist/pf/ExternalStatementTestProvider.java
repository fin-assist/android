package ru.finassist.pf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Read-only synthetic statement, owned by the test APK so Android must grant cross-app access. */
public final class ExternalStatementTestProvider extends ContentProvider {
    static final String AUTHORITY_SUFFIX = ".external-statement-test";
    static final String MIME_TYPE = "application/x-ofx";
    private static final String FIXTURE = "external-statement.ofx";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return MIME_TYPE; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String order) {
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME };
        MatrixCursor cursor = new MatrixCursor(columns);
        if ("/1".equals(uri.getPath())) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? FIXTURE : null);
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/1".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), FIXTURE);
        try (InputStream input = getContext().getAssets().open(FIXTURE);
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                output.write(buffer, 0, count);
            }
        } catch (IOException error) {
            throw new FileNotFoundException("Cannot prepare test statement");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
