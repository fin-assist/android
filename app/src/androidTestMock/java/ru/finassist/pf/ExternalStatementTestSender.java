package ru.finassist.pf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Emulates Chrome's ACTION_VIEW in the separate test APK's process. */
public final class ExternalStatementTestSender extends Activity {
    public static final String REVOKE_READ_GRANT = "revoke_read_grant";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = Uri.parse("content://" + getPackageName() + ExternalStatementTestProvider.AUTHORITY_SUFFIX + "/1");
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ExternalStatementTestProvider.MIME_TYPE)
                .setPackage("ru.finassist.pf.mock.e2e")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        if (getIntent().getBooleanExtra(REVOKE_READ_GRANT, false)) {
            revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        finish();
    }
}
