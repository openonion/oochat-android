package ai.openonion.oochat.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SLIP-0010 Ed25519 key derivation down a SLIP-0013 path — a line-for-line
 * port of the connectonion SDK's `connectonion/derive.py` (1.5.5+), which the
 * client made canonical (W16AAPPLE-97). The identity a recovery phrase gives
 * is [derivePath] of its BIP39 seed at [slip13Path] of [ACCOUNT_URI], the
 * SDK's `address._account_key()`, so the same phrase is the same address here
 * and in `co`.
 *
 * Hardened-only, as the SDK is: SLIP-0010 has no public derivation for
 * Ed25519, and a path level without the apostrophe is refused rather than
 * deriving some other key.
 *
 * JDK-only (HMAC-SHA512, SHA-256), so it runs unchanged in a JVM unit test.
 */
internal object Slip10 {

    /** SLIP-0010's domain separator for the ed25519 curve. */
    private val ED25519_CURVE = "ed25519 seed".toByteArray(Charsets.US_ASCII)

    private const val HARDENED = 0x80000000L

    /**
     * The account identity's SLIP-0013 URI. A domain separator, not an
     * address the app talks to: it must not follow the relay or backend URL,
     * or the same phrase would derive a different key per environment.
     */
    const val ACCOUNT_URI = "https://oo.openonion.ai"

    /** The Ed25519 seed of the account key for a 64-byte BIP39 seed. */
    fun accountKey(seed: ByteArray): ByteArray = derivePath(seed, slip13Path(ACCOUNT_URI))

    /** `I = HMAC-SHA512("ed25519 seed", seed)`: left half key, right half chain code. */
    fun masterKey(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size in 16..64) { "seed must be 16-64 bytes, got ${seed.size}" }
        val digest = hmacSha512(ED25519_CURVE, seed)
        return digest.copyOfRange(0, 32) to digest.copyOfRange(32, 64)
    }

    /** The 32-byte private key (an Ed25519 seed) at [path], e.g. `m/13'/…`. */
    fun derivePath(seed: ByteArray, path: String): ByteArray {
        var (key, chainCode) = masterKey(seed)
        for (index in parsePath(path)) {
            // 0x00 || key || index_be: the private (and only) derivation.
            val data = ByteBuffer.allocate(37).put(0).put(key).putInt(index.toInt()).array()
            val digest = hmacSha512(chainCode, data)
            key = digest.copyOfRange(0, 32)
            chainCode = digest.copyOfRange(32, 64)
        }
        return key
    }

    /**
     * `m/13'/A'/B'/C'/D'`, where A..D are the first 16 bytes of
     * `SHA256(index_le || uri)` read as four little-endian uint32s, each with
     * its top bit cleared and then hardened.
     */
    fun slip13Path(uri: String, index: Int = 0): String {
        val hashed = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index).array() +
            uri.toByteArray(Charsets.UTF_8)
        val digest = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(hashed))
            .order(ByteOrder.LITTLE_ENDIAN)
        val levels = List(4) { digest.int.toLong() and 0x7FFFFFFFL }
        return "m/13'/" + levels.joinToString("/") { "$it'" }
    }

    /** `"m/13'/7'"` → `[13 + 2^31, 7 + 2^31]`. */
    private fun parsePath(path: String): List<Long> {
        val parts = path.trim().split("/")
        require(parts.first() == "m") { "path must start with 'm', got '$path'" }
        return parts.drop(1).map { part ->
            require(part.isNotEmpty() && part.last() in "'hH") {
                "path level '$part' is not hardened — SLIP-0010 ed25519 has no unhardened derivation"
            }
            val number = part.dropLast(1)
            require(number.isNotEmpty() && number.all { it in '0'..'9' }) { "path level '$part' is not a number" }
            val value = number.toBigInteger()
            require(value < HARDENED.toBigInteger()) { "path level '$part' is out of range" }
            value.toLong() + HARDENED
        }
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(key, "HmacSHA512"))
            doFinal(data)
        }
}
