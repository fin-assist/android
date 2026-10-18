package ru.finassist.pf.core.common.availability

/**
 * Version of this client as the server sees it (api.md «Заголовки», FIN-20): `X-Client-Version: android/0.2.7+41`.
 * [name] is `MAJOR.MINOR.PATCH`, [build] the build number; only [name] is compared with a minimum.
 */
data class ClientVersion(val name: String, val build: Int, val platform: String = PLATFORM) {
    /** Value of the `X-Client-Version` header. */
    val header: String get() = "$platform/$name+$build"

    /**
     * `true` when this version is below [minimum] (`MAJOR.MINOR.PATCH`, numbers compared in order; equal passes).
     * A blank or malformed minimum never blocks: the server's answer stays the source of truth.
     */
    fun isBelow(minimum: String?): Boolean {
        val min = minimum?.let(::parse) ?: return false
        val own = parse(name) ?: return false
        return compareParts(own, min) < 0
    }

    companion object {
        const val PLATFORM = "android"

        /** `MAJOR.MINOR.PATCH` without leading zeros → its three numbers; null for anything else. */
        fun parse(version: String): List<Long>? {
            val parts = version.trim().split('.')
            if (parts.size != 3) return null
            return parts.map { p ->
                if (p.isEmpty() || !p.all(Char::isDigit) || (p.length > 1 && p[0] == '0')) return null
                p.toLongOrNull() ?: return null
            }
        }

        private fun compareParts(a: List<Long>, b: List<Long>): Int =
            a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
    }
}
