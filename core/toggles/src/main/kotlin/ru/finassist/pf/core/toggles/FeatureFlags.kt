package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.Flow
import java.util.zip.CRC32

/**
 * Feature flags as the app reads them. The provider (RuStore Remote Config in `prod`, a JSON asset plus
 * debug overrides in `mock`) is one implementation behind this interface, so switching to GrowthBook later
 * touches only `:providers:*`. Values are read at the screen boundary (view model init) and not re-read
 * mid-screen; [changes] lets the root re-evaluate the screen when a new config arrives.
 */
interface FeatureFlags {
    /** Current value; the code default from [Flag.defaultValue] when the provider has nothing. */
    fun isEnabled(flag: Flag): Boolean

    /** Emits after every config update (fetch, user change, debug override). */
    val changes: Flow<Unit>

    /**
     * Version of the config in effect — [flagsVersion] of the provider's values, [FLAGS_VERSION_DEFAULTS] while it
     * has none. Goes into crash reports (`rc_ver`), so a crash can be matched with the switches behind it.
     */
    val version: String

    /**
     * Identity for targeting: our own `user_id` after sign-in, `null` before sign-in and after sign-out
     * (then the provider keys by device id). Triggers a re-fetch in providers that support it.
     */
    suspend fun setUser(userId: String?)

    /** Fetches the newest config; errors are swallowed — defaults or the cached config stay in effect. */
    suspend fun refresh()

    /** Variant of [experiment] in effect (FIN-9); its default variant when the provider has none. */
    fun variant(experiment: Experiment): String = experiment.defaultVariant
}

/** Debug-only overrides (mock flavor, hidden settings screen). */
interface FlagOverrides {
    val overrides: Flow<Map<Flag, Boolean>>
    suspend fun set(flag: Flag, enabled: Boolean?)
    suspend fun clear()
}

/** [FeatureFlags.version] when the provider has no values: code defaults are in effect. */
const val FLAGS_VERSION_DEFAULTS = "defaults"

/**
 * Short stable fingerprint of a set of flag values, by flag key: the same values give the same version on every
 * device and launch, whatever the map order. Eight hex digits (CRC32) — it identifies a config, it is not a secret.
 */
fun flagsVersion(values: Map<String, Boolean>, variants: Map<String, String> = emptyMap()): String {
    if (values.isEmpty() && variants.isEmpty()) return FLAGS_VERSION_DEFAULTS
    val crc = CRC32()
    values.toSortedMap().forEach { (key, value) -> crc.update("$key=$value;".toByteArray(Charsets.UTF_8)) }
    // Experiment variants (FIN-9) count too; none — the version of the flags alone stays as it was.
    variants.toSortedMap().forEach { (key, value) -> crc.update("exp:$key=$value;".toByteArray(Charsets.UTF_8)) }
    return "%08x".format(crc.value)
}
