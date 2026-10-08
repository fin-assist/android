package ru.finassist.pf.core.designsystem.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Test tags of shared components. UI tests (Maestro, `.maestro/`) find elements by these ids; feature screens
 * tag their own elements as `<feature>.<screen>[.<element>]` (docs/e2e.md). Renaming a tag breaks the flows
 * that use it — search `.maestro/` before changing one.
 */
object PfTestTags {
    const val BACK = "ds.back"
    const val SHEET_CLOSE = "ds.sheet.close"
    const val DIALOG = "ds.dialog"
    const val DIALOG_CONFIRM = "ds.dialog.confirm"
    const val DIALOG_CANCEL = "ds.dialog.cancel"
    const val SNACKBAR = "ds.snackbar"

    const val PASSCODE_DOTS = "ds.passcode.dots"
    const val PASSCODE_ERROR = "ds.passcode.error"
    const val NUMPAD_DELETE = "ds.numpad.delete"
    const val NUMPAD_BIOMETRIC = "ds.numpad.biometric"
    fun numpad(digit: Int) = "ds.numpad.$digit"

    const val PERIOD_PREV = "ds.period.prev"
    const val PERIOD_NEXT = "ds.period.next"
    const val PERIOD_PICK = "ds.period.pick"
    fun segment(index: Int) = "ds.segment.$index"
    fun tab(id: String) = "ds.tab.$id"

    const val CHAT_INPUT = "ds.chat.input"
    const val CHAT_SEND = "ds.chat.send"
    const val CHAT_VOICE = "ds.chat.voice"
    const val CHAT_LIMIT = "ds.chat.limit"
}

/**
 * Whether [pfTestRoot] exposes test tags. Set once by the app at start: on for debuggable builds (the e2e build
 * is one), off in release, where tags have no reader and stay out of the accessibility tree.
 */
@Volatile
var pfExposeTestTags: Boolean = false

/**
 * Exposes test tags as resource ids to UI Automator (and so to Maestro) for this subtree when [pfExposeTestTags]
 * is on. Every window root needs it: the activity content, and each dialog and bottom sheet, which compose in
 * windows of their own.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.pfTestRoot(): Modifier = if (pfExposeTestTags) semantics { testTagsAsResourceId = true } else this
