package ru.finassist.pf.feature.applock.impl.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ru.finassist.pf.core.designsystem.components.BiometricKind
import ru.finassist.pf.feature.applock.api.BiometricAvailability

/** Maps the feature's availability to the keypad's icon. */
internal fun BiometricAvailability.toKind(): BiometricKind = when (this) {
    BiometricAvailability.NONE -> BiometricKind.NONE
    BiometricAvailability.FINGERPRINT -> BiometricKind.FINGERPRINT
    BiometricAvailability.FACE -> BiometricKind.FACE
}

internal fun BiometricAvailability.noun(): String = when (this) {
    BiometricAvailability.FACE -> "лицу"
    else -> "отпечатку"
}

private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * System biometric sheet. The host activity must be a [FragmentActivity] (MainActivity is). Cancel and
 * errors both call [onDismissed] — the passcode pad is always available as the fallback.
 */
@Composable
internal fun rememberBiometricPrompt(
    title: String,
    onSuccess: () -> Unit,
    onDismissed: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val success by rememberUpdatedState(onSuccess)
    val dismissed by rememberUpdatedState(onDismissed)
    return remember(context, title) {
        {
            val activity = context.findActivity()
            if (activity == null) {
                dismissed()
            } else {
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = dismissed()
                    },
                )
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setNegativeButtonText("Ввести код")
                        .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
                        .setConfirmationRequired(false)
                        .build(),
                )
            }
        }
    }
}
