package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.mock.api.UploadBody
import ru.finassist.pf.mock.domain.Sha256
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The common-Kotlin SHA-256 of the fake backend against FIPS 180-4 vectors and the JDK digest. */
class Sha256Test {
    private fun hex(s: String) = Sha256.hex(Sha256.digest(s.encodeToByteArray()))
    private fun jdk(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    @Test
    fun `known vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex("a".repeat(1_000_000)))
    }

    @Test
    fun `equals the JDK digest at every padding boundary and over several parts`() {
        val random = Random(340)
        // 55 / 56 and 63 / 64 bytes: where the length still fits the last block and where it needs another one.
        for (size in listOf(1, 55, 56, 57, 63, 64, 65, 119, 120, 128, 1_000, 65_537)) {
            val bytes = random.nextBytes(size)
            assertEquals(Sha256.hex(jdk(bytes)), Sha256.hex(Sha256.digest(bytes)), "size $size")
            val cut = size / 3
            val a = bytes.copyOfRange(0, cut)
            val b = bytes.copyOfRange(cut, size)
            assertEquals(Sha256.hex(jdk(bytes)), Sha256.hex(Sha256.digest(a, b)), "size $size in two parts")
        }
    }

    @Test
    fun `the upload fingerprint is the request hash of grpc-api md 6`() {
        val content = "OFXHEADER:100".encodeToByteArray()
        val name = "Выписка (1).ofx"
        val expected = Sha256.hex(jdk(jdk(content), name.toByteArray(Charsets.UTF_8)))
        assertEquals(UploadBody(expected), UploadBody.of(content, name))
        assertEquals(64, expected.length)
        // The name is part of the body, as sent: case and an empty name change it.
        assertNotEquals(UploadBody.of(content, name), UploadBody.of(content, "выписка (1).ofx"))
        assertEquals(UploadBody(Sha256.hex(jdk(jdk(content)))), UploadBody.of(content, ""))
    }
}
