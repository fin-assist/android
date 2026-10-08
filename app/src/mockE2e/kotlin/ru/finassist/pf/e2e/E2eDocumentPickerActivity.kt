package ru.finassist.pf.e2e

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ru.finassist.pf.core.designsystem.components.pfTestRoot
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.mock.MockConfig
import java.io.File

/**
 * Stands in for the system document picker in the mock e2e build (declared in this source set's manifest,
 * picked up by `PickStatementDocument`). Lists the bundled test files; a tap returns a content Uri from a
 * [FileProvider], like the system picker would, and the upload screen takes it from there.
 */
class E2eDocumentPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = listOf(MockConfig.FIXTURE_STATEMENT) +
            assets.list(DIR).orEmpty().sorted().map { "$DIR/$it" }
        setContent {
            PfTheme {
                Column(Modifier.fillMaxSize().background(PfTheme.colors.bg).safeDrawingPadding().pfTestRoot().testTag("e2e.picker")) {
                    Text("Тестовые файлы", style = PfTheme.type.title2, color = PfTheme.colors.text, modifier = Modifier.padding(16.dp))
                    files.forEach { path ->
                        val name = path.substringAfterLast('/')
                        Text(
                            name,
                            style = PfTheme.type.body,
                            color = PfTheme.colors.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pick(path) }
                                .padding(16.dp)
                                .testTag("e2e.picker.$name"),
                        )
                    }
                }
            }
        }
    }

    private fun pick(assetPath: String) {
        // Copied out of the APK: FileProvider serves files, and reports their name and size like a real document.
        val file = File(cacheDir, "e2e/${assetPath.substringAfterLast('/')}")
        file.parentFile?.mkdirs()
        assets.open(assetPath).use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(this, "$packageName.e2e.files", file)
        setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    private companion object {
        const val DIR = "e2e/statements"
    }
}
