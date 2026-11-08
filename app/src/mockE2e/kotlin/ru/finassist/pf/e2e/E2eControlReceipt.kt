package ru.finassist.pf.e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ru.finassist.pf.core.designsystem.components.pfTestRoot

/** A persistent mockE2e receipt: observing success and acknowledging it are separate actions. */
@Composable
internal fun E2eControlReceipt(unmet: String?, canContinue: Boolean, onAcknowledge: () -> Unit) {
    Column(Modifier.fillMaxSize().pfTestRoot()) {
        if (unmet == null) {
            Text("pfe2e: applied", Modifier.testTag(E2eControlActivity.TAG_APPLIED))
            Button(onClick = onAcknowledge, enabled = canContinue, modifier = Modifier.testTag("e2e.config.continue")) {
                Text("Продолжить")
            }
        } else {
            Text("pfe2e: $unmet", Modifier.testTag(E2eControlActivity.TAG_UNMET))
        }
    }
}
