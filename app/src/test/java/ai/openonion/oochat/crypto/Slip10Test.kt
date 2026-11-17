package ai.openonion.oochat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [Slip10] against outside answers: SLIP-0010's own ed25519 test vector 1, and
 * paths computed by the connectonion SDK's `derive.py` (v1.9.2b28). The
 * phrase → address vectors that go through it are in [IdentityVectorsTest].
 */
class Slip10Test {

    // SLIP-0010, "Test vector 1 for ed25519".
    private val specSeed = "000102030405060708090a0b0c0d0e0f".hexToBytes()

    @Test
    fun `the master key is SLIP-0010 test vector 1's`() {
        val (key, chainCode) = Slip10.masterKey(specSeed)

        assertEquals("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7", key.toHex())
        assertEquals("90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb", chainCode.toHex())
    }

    @Test
    fun `a five-level hardened path reaches SLIP-0010 test vector 1's last key`() {
        assertEquals(
            "8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793",
            Slip10.derivePath(specSeed, "m/0'/1'/2'/2'/1000000000'").toHex()
        )
        // derive.py accepts h and H for the hardened marker too.
        assertEquals(
            Slip10.derivePath(specSeed, "m/0'/1'/2'/2'/1000000000'").toHex(),
            Slip10.derivePath(specSeed, "m/0h/1H/2'/2h/1000000000'").toHex()
        )
    }

    @Test
    fun `the account path is the SDK's`() {
        assertEquals(
            "m/13'/2107038128'/851645955'/1509754396'/381167091'",
            Slip10.slip13Path(Slip10.ACCOUNT_URI)
        )
    }

    @Test
    fun `the rotation index and the URI both change the path`() {
        assertEquals(
            "m/13'/1200411256'/45279630'/1682495684'/461299469'",
            Slip10.slip13Path(Slip10.ACCOUNT_URI, index = 1)
        )
        // derive.identity_uri("Alice")
        assertEquals(
            "m/13'/2026397833'/1728896664'/683410748'/56884984'",
            Slip10.slip13Path("agent://alice")
        )
    }

    @Test
    fun `an unhardened, malformed or out-of-range path is refused rather than derived`() {
        for (path in listOf("m/13", "m/13'/7", "13'", "m/x'", "m/'", "m/2147483648'")) {
            assertThrows(path, IllegalArgumentException::class.java) { Slip10.derivePath(specSeed, path) }
        }
    }

    @Test
    fun `a seed outside 16 to 64 bytes is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Slip10.masterKey(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { Slip10.masterKey(ByteArray(65)) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
