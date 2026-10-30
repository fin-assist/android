package ru.finassist.pf.e2e

import java.io.InputStream
import java.io.OutputStream

/** Make only the three compact history examples admissible to the real OFX parser. */
object StageHistoryStatementFixture {
    private val paths = setOf(
        "e2e/statements/zz-history-period.ofx",
        "e2e/statements/zz-history-fallback.ofx",
        "e2e/statements/zz-history-empty.ofx",
    )
    private const val STATUS = "<STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>"
    private const val SIGNON = "<SIGNONMSGSRSV1><SONRS>" + STATUS +
        "<DTSERVER>20261001000000[+3:MSK]</DTSERVER><LANGUAGE>RUS</LANGUAGE></SONRS></SIGNONMSGSRSV1>"
    private const val HEADERS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<?OFX OFXHEADER=\"200\" VERSION=\"202\" SECURITY=\"NONE\" OLDFILEUID=\"NONE\" NEWFILEUID=\"NONE\"?>\n"

    fun copyTo(assetPath: String, input: InputStream, output: OutputStream) {
        if (assetPath in paths) output.write(prepare(assetPath, input.readBytes())) else input.copyTo(output)
    }

    internal fun prepare(assetPath: String, original: ByteArray): ByteArray {
        if (assetPath !in paths) return original
        val text = try { original.decodeToString(throwOnInvalidSequence = true) } catch (_: java.nio.charset.CharacterCodingException) { return original }
        // Already complete files and unexpected source shapes retain their original bytes, including malformed cases.
        if (!text.startsWith("<OFX><BANKMSGSRSV1><STMTTRNRS>")) return original
        // Keep source periods, account, FITIDs and every transaction field exactly as supplied by the bundled asset.
        return (HEADERS + text.replaceFirst("<OFX>", "<OFX>" + SIGNON)
            .replaceFirst("<STMTTRNRS>", "<STMTTRNRS><TRNUID>0</TRNUID>" + STATUS)).encodeToByteArray()
    }
}
