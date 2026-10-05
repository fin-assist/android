package ru.finassist.pf.feature.statements.impl.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import ru.finassist.pf.core.common.support.Support

/**
 * Opens T-Bank pages in a Custom Tab (the bank's cookies live in the browser, so «Скачать за год» works right after
 * «Войти»). Falls back to a plain VIEW intent when no browser supports Custom Tabs.
 */
fun openUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** «Написать в поддержку» / «Сообщить нам»: a prefilled mail (the contract has no endpoint for this). */
fun openSupportMail(context: Context, subject: String, body: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Support.EMAIL}"))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
    runCatching { context.startActivity(intent) }
}
