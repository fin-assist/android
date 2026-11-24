package ru.finassist.pf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Emulates Chrome's ACTION_VIEW in the separate test APK's process. */
public final class ExternalStatementTestSender extends Activity {
    public static final String OMIT_READ_GRANT = "omit_read_grant";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean omitGrant = getIntent().getBooleanExtra(OMIT_READ_GRANT, false);
        Uri uri = Uri.parse("content://" + getPackageName() + ExternalStatementTestProvider.AUTHORITY_SUFFIX
                + (omitGrant ? "/2" : "/1"));
        Intent request = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ExternalStatementTestProvider.MIME_TYPE)
                .setPackage("ru.finassist.pf.mock.e2e");
        if (!omitGrant) request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(request);
        finish();
    }
}
