package ru.finassist.pf.core.designsystem.icons

import androidx.annotation.DrawableRes
import ru.finassist.pf.core.designsystem.R

/**
 * Icon set of the design system: 24×24 line icons, stroke 1.75, Lucide outlines (ISC).
 * Keys are the wire icon names from the API (`category_icon`) and the names used in mockups.
 * Generated from the design-system bundle; do not edit by hand — see scripts in the DS pipeline.
 */
object PfIcons {
    val byName: Map<String, Int> = mapOf(
        "cart" to R.drawable.ic_cart,
        "car" to R.drawable.ic_car,
        "wine" to R.drawable.ic_wine,
        "package" to R.drawable.ic_package,
        "trending-up" to R.drawable.ic_trending_up,
        "users" to R.drawable.ic_users,
        "list" to R.drawable.ic_list,
        "pie-chart" to R.drawable.ic_pie_chart,
        "user" to R.drawable.ic_user,
        "arrow-left" to R.drawable.ic_arrow_left,
        "info" to R.drawable.ic_info,
        "alert-triangle" to R.drawable.ic_alert_triangle,
        "check-circle" to R.drawable.ic_check_circle,
        "search" to R.drawable.ic_search,
        "plus" to R.drawable.ic_plus,
        "x" to R.drawable.ic_x,
        "check" to R.drawable.ic_check,
        "chevron-right" to R.drawable.ic_chevron_right,
        "chevron-down" to R.drawable.ic_chevron_down,
        "eye" to R.drawable.ic_eye,
        "mail" to R.drawable.ic_mail,
        "lock" to R.drawable.ic_lock,
        "bell" to R.drawable.ic_bell,
        "moon" to R.drawable.ic_moon,
        "tag" to R.drawable.ic_tag,
        "download" to R.drawable.ic_download,
        "upload" to R.drawable.ic_upload,
        "log-out" to R.drawable.ic_log_out,
        "message" to R.drawable.ic_message,
        "mic" to R.drawable.ic_mic,
        "clock" to R.drawable.ic_clock,
        "repeat" to R.drawable.ic_repeat,
        "percent" to R.drawable.ic_percent,
        "coffee" to R.drawable.ic_coffee,
        "bike" to R.drawable.ic_bike,
        "basket" to R.drawable.ic_basket,
        "landmark" to R.drawable.ic_landmark,
        "receipt" to R.drawable.ic_receipt,
        "bar-chart" to R.drawable.ic_bar_chart,
        "transfer" to R.drawable.ic_transfer,
        "phone" to R.drawable.ic_phone,
        "circle" to R.drawable.ic_circle,
        "file-text" to R.drawable.ic_file_text,
        "pencil" to R.drawable.ic_pencil,
        "send" to R.drawable.ic_send,
        "piggy-bank" to R.drawable.ic_piggy_bank,
        "credit-card" to R.drawable.ic_credit_card,
        "chevron-left" to R.drawable.ic_chevron_left,
        "sun" to R.drawable.ic_sun,
        "smartphone" to R.drawable.ic_smartphone,
        "fingerprint" to R.drawable.ic_fingerprint,
        "sliders" to R.drawable.ic_sliders,
        "trash" to R.drawable.ic_trash,
        "delete" to R.drawable.ic_delete,
        "scan-face" to R.drawable.ic_scan_face,
        "life-buoy" to R.drawable.ic_life_buoy,
    )

    /** Unknown wire key → default icon (api.md 5.1: «неизвестный ключ — иконка по умолчанию»). */
    @DrawableRes
    fun resolve(name: String?): Int = byName[name] ?: R.drawable.ic_circle
}
