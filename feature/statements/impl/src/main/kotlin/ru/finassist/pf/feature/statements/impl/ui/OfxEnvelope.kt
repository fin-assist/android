package ru.finassist.pf.feature.statements.impl.ui

private const val MAX_PREAMBLE_BYTES = 8 * 1024

/** Local format check for downloads, before the server's full import validation. */
internal fun hasOfxEnvelope(bytes: ByteArray): Boolean {
    var markup = String(bytes, 0, minOf(bytes.size, MAX_PREAMBLE_BYTES), Charsets.UTF_8)
        .removePrefix("\uFEFF").trimStart()
    if (markup.startsWith("OFXHEADER:", ignoreCase = true)) {
        val rootStart = markup.indexOf('<')
        if (rootStart < 0) return false
        markup = markup.substring(rootStart)
    }
    while (true) {
        val end = when {
            markup.startsWith("<?xml ", ignoreCase = true) || markup.startsWith("<?OFX ", ignoreCase = true) -> "?>"
            markup.startsWith("<!--") -> "-->"
            else -> break
        }
        val endIndex = markup.indexOf(end)
        if (endIndex < 0) return false
        markup = markup.substring(endIndex + end.length).trimStart()
    }
    return markup.startsWith("<OFX", ignoreCase = true) &&
        markup.getOrNull(4)?.let { it == '>' || it.isWhitespace() } == true
}
