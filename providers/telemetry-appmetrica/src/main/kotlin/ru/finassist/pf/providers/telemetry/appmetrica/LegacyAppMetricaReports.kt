package ru.finassist.pf.providers.telemetry.appmetrica

import java.io.File
import java.nio.file.Files

private const val ACCOUNT_CONTEXT_MIGRATION = "pf_appmetrica_account_context_removed"

/**
 * AppMetrica 8.6.0 stores the profile and buffered reports in SQLite and can recover crashes from files.
 * Clear the reporting stores before the SDK opens them. Installation identifiers and other app files stay in place.
 */
internal fun discardLegacyAppMetricaReports(noBackupFilesDir: File, packageName: String) {
    val root = noBackupFilesDir.canonicalFile
    val marker = File(root, ACCOUNT_CONTEXT_MIGRATION)
    check(!Files.isSymbolicLink(marker.toPath()))
    if (marker.isFile) return

    val appMetricaRoot = File(root, "appmetrica")
    val storage = File(appMetricaRoot, "analytics")
    check(!Files.isSymbolicLink(appMetricaRoot.toPath()) && !Files.isSymbolicLink(storage.toPath()))
    discard(File(storage, "db"))
    discard(File(storage, "crashes"))
    discard(File(storage, "native_crashes"))

    // The SDK can import these old databases and their SQLite sidecars when a current database is absent.
    val legacyNames = listOf("db_metrica_$packageName", "metrica_data.db", "metrica_client_data.db")
    for (name in legacyNames) {
        for (suffix in listOf("", "-journal", "-shm", "-wal")) {
            discard(File(root, name + suffix))
        }
    }
    check(marker.createNewFile())
}

private fun discard(file: File) {
    // Avoid descending through links even inside the private SDK directories.
    if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
        checkNotNull(file.listFiles()).forEach(::discard)
    }
    Files.deleteIfExists(file.toPath())
}
