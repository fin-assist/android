package ru.finassist.pf.e2e

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret ownership receipt installed by the host after it creates this synthetic account. */
@Serializable
data class StageRun(
    val run_id: String,
    val phone: String,
    val new_phone: String? = null,
    val new_account: Boolean = false,
) {
    fun validated(): StageRun = apply {
        require(UUID.fromString(run_id).toString() == run_id) { "Invalid run receipt" }
        require(Regex("\\+7000000[0-9]{4}").matches(phone)) { "Not a synthetic phone" }
        require(new_phone == null || (new_phone != phone && Regex("\\+7000000[0-9]{4}").matches(new_phone))) { "Invalid second synthetic phone" }
    }
}

@Singleton
class StageRunStore @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(): StageRun {
        val file = File(context.filesDir, "stage-run.json")
        require(file.isFile && file.length() in 1..1024) { "Missing run receipt" }
        return Json.decodeFromString<StageRun>(file.readText()).validated()
    }
}
