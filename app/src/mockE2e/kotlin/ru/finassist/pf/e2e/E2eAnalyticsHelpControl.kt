package ru.finassist.pf.e2e

import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.storage.TooltipOnboardingStore
import ru.finassist.pf.mock.MockAnalyticsHelp
import java.io.IOException
import javax.inject.Inject

/** Checks the persisted onboarding state that a mockE2e receipt promises. */
internal class E2eAnalyticsHelpControl @Inject constructor(
    private val tokenStore: TokenStore,
    private val tooltipOnboarding: TooltipOnboardingStore,
) {
    suspend fun verify(mode: MockAnalyticsHelp, allowSignedOut: Boolean = false): String? {
        val manual = mode == MockAnalyticsHelp.MANUAL
        return try {
            val current = tokenStore.current()
            if (current == null) {
                return if (allowSignedOut && !manual) null else "analytics_help needs an active login"
            }
            val epoch = current.authSessionEpoch ?: return "analytics_help needs an active login"
            if (manual) tooltipOnboarding.claim("analytics", current.userId, epoch)
            // A false claim can mean an existing record or a failed write. The receipt depends on stored state.
            when (tooltipOnboarding.isClaimed("analytics", current.userId, epoch)) {
                null -> "analytics_help: login changed"
                true -> if (manual) null else "analytics_help: onboarding already claimed; clear app data first"
                false -> if (manual) "analytics_help=manual: claim was not saved" else null
            }
        } catch (_: IOException) {
            "analytics_help: could not read onboarding state"
        }
    }
}
