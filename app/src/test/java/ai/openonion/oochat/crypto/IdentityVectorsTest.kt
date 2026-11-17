package ai.openonion.oochat.crypto

import android.app.Application
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Known answers for the identity: recovery phrase → seed → address, and key →
 * signature. Every expected value here came from outside this codebase —
 * RFC 8032's own test vectors, and keys, addresses and signatures produced by
 * the connectonion Python SDK itself (v1.9.2b28: `derive.py`'s SLIP-0010
 * `derive_path` at `slip13_path(ACCOUNT_URI)`, over python-mnemonic and PyNaCl,
 * i.e. libsodium) — so these are the addresses `co` gives the same phrases.
 *
 * So a failure here means the app would compute a different identity or a
 * different signature from the rest of the ecosystem: a phrase that restores
 * someone else's address, or a CONNECT the relay rejects. Nothing else in the
 * suite would notice either — the connection tests sign with a fake.
 *
 * Derivation pinned is the SDK's `address._account_key()` (W16AAPPLE-97): BIP39
 * seed (empty passphrase), SLIP-0010 down the SLIP-0013 account path, RFC 8032
 * expansion. The web client (connectonion-react) still takes the seed's first
 * 32 bytes, so until it moves the same phrase gives a different address there.
 */
class IdentityVectorsTest {

    private val keyManager = KeyManager(Application(), BouncyCastleEd25519())

    private data class PhraseVector(
        val phrase: String,
        val seedHex: String,
        /** The 32-byte Ed25519 seed at the account path — what `SigningKey()` is given. */
        val accountKeyHex: String,
        val address: String
    )

    private val phrases = listOf(
        PhraseVector(
            phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            seedHex = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            accountKeyHex = "451758ecc454c400bb9e394b128b1b2103400e445e589b84027f67d2f9873224",
            address = "0x54ff7e165926bca30e8b1535f22c053a0e0e2ca6d5a445a149bde23d90c6ee1e"
        ),
        PhraseVector(
            phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow",
            seedHex = "878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe" +
                "5f377ba02bf3f8544ab800b955e51fbff09828f682052a20faa6addbbddfb096",
            accountKeyHex = "c2969d023619f94365f2a51fa8e4fb4095be6b515421ca194190a747ccfff5d8",
            address = "0xf8405257284c15e67fd759703d0c04afdf2f216153b9781946f096cae1990a95"
        ),
        PhraseVector(
            phrase = (List(23) { "abandon" } + "art").joinToString(" "),
            seedHex = "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
                "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
            accountKeyHex = "d3cc88452cf2930f7c2378acae874b4e5486170d45e9bb998b0d9f08e54d2d2f",
            address = "0x8f0603db74882f8040f8c153f76cfe51f5296a9118a702b7f86a6f183a6866f8"
        )
    )

    /** More phrases through `co`'s derivation: the other BIP39 lengths, and the all-ones entropy. */
    private val sdkAddresses = mapOf(
        (List(14) { "abandon" } + "address").joinToString(" ") to
            "0xd7ca67119a20dcf17260a9e7b07c7db79a4452beabc849143bb50ce37784ca16",
        (List(17) { "abandon" } + "agent").joinToString(" ") to
            "0x0e9d0b749ea3788a66d3dc7d68171f96560bac25fe6c9c67456f6161dbaa1c64",
        (List(20) { "abandon" } + "admit").joinToString(" ") to
            "0x96ef0856a854247b15aec93351545b86f5b515e6bc1092fe12a2351c88169cec",
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong" to
            "0x94f1eec2a492a2b7971bca49f8c9516a7f54b53ac220620bf91af0a26001194e"
    )

    // ── BIP39: phrase → seed ──────────────────────────────────────────
    //
    // kotlin-bip39 is pure Kotlin, so this half runs exactly as it does on a
    // phone — no stand-in involved.

    @Test
    fun `a recovery phrase stretches to the standard BIP39 seed`() {
        for (v in phrases) {
            val seed = Mnemonics.MnemonicCode(v.phrase).toSeed()
            assertEquals("seed for \"${v.phrase.take(24)}…\"", v.seedHex, seed.toHex())
        }
    }

    // ── the stand-in is libsodium ─────────────────────────────────────
    //
    // Everything below the seed runs on BouncyCastleEd25519. These pin it to
    // RFC 8032 section 7.1, so the rest of this file tests the app's logic and
    // not a disagreement between two Ed25519 implementations.

    @Test
    fun `RFC 8032 test 1 - empty message`() {
        val ed = BouncyCastleEd25519()
        val (publicKey, secretKey) =
            ed.keypairFromSeed("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes())

        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", publicKey.toHex())
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
                "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ed.sign(ByteArray(0), secretKey).toHex()
        )
    }

    @Test
    fun `RFC 8032 test 2 - one-byte message`() {
        val ed = BouncyCastleEd25519()
        val (publicKey, secretKey) =
            ed.keypairFromSeed("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexToBytes())

        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", publicKey.toHex())
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ed.sign(byteArrayOf(0x72), secretKey).toHex()
        )
    }

    // ── phrase → address, through KeyManager ──────────────────────────

    @Test
    fun `a seed reaches the SDK's account key down the SLIP-0013 path`() {
        for (v in phrases) {
            assertEquals("key for \"${v.phrase.take(24)}…\"", v.accountKeyHex, Slip10.accountKey(v.seedHex.hexToBytes()).toHex())
        }
    }

    @Test
    fun `a recovery phrase restores the address co recovers from it`() {
        for (v in phrases) {
            assertEquals("address for \"${v.phrase.take(24)}…\"", v.address, keyManager.keysFromMnemonic(v.phrase).address)
        }
        for ((phrase, address) in sdkAddresses) {
            assertEquals("address for \"${phrase.take(24)}…\"", address, keyManager.keysFromMnemonic(phrase).address)
        }
    }

    @Test
    fun `a newly generated phrase restores the identity it was minted with`() {
        val minted = keyManager.generateWithMnemonic()

        assertEquals(minted.addressData.address, keyManager.keysFromMnemonic(minted.mnemonic).address)
    }

    @Test
    fun `the secret key is packed the way libsodium packs it - seed then public key`() {
        // The hex import path and every stored identity rely on this layout:
        // keysFromHex reads the public key back out of the last 32 bytes.
        val v = phrases.first()
        val keys = keyManager.keysFromMnemonic(v.phrase)

        assertEquals(v.accountKeyHex + v.address.removePrefix("0x"), keys.privateKey.toHex())
        assertEquals(v.address, keyManager.keysFromHex(keys.privateKey.toHex()).address)
    }

    @Test
    fun `an address is 0x plus 64 lower-case hex characters, shortened to 6 and 4`() {
        // The same form the connectonion SDK gives an address ("0x" + hex) and
        // its short form ("0x3d40...660c" in address.py's own docstring).
        val keys = keyManager.keysFromMnemonic(phrases.first().phrase)

        assertEquals(66, keys.address.length)
        assertEquals(keys.address.lowercase(), keys.address)
        assertEquals("0x54ff...ee1e", keys.shortAddress)
    }

    // ── canonical payloads and their signatures ───────────────────────
    //
    // What the relay verifies is the signature over *its own* canonical
    // re-encoding of the payload it received: Python's
    // json.dumps(payload, sort_keys=True, separators=(",", ":")). The app has
    // to produce those exact bytes, then sign them with the right key.

    private val agent = "0x" + "ab".repeat(32)
    private val timestamp = 1_760_000_000L

    private data class SignedPayload(val fields: Map<String, Any>, val canonical: String, val signature: String)

    private val signedPayloads = mapOf(
        "relay CONNECT" to SignedPayload(
            linkedMapOf("to" to agent, "timestamp" to timestamp),
            """{"timestamp":1760000000,"to":"$agent"}""",
            "2402db638f0cf481d7107ea49fec3bb319a6e186cd24287dd37251d42db55970" +
                "7047184d9625db0b53e9907c2934325742d13b64ea28ba826369a70621ac580a"
        ),
        "direct CONNECT" to SignedPayload(
            linkedMapOf("timestamp" to timestamp),
            """{"timestamp":1760000000}""",
            "7b341e2adc4cd67d616a3acf1a733feb5cfae9ea3e335fefe19a07484b306b35" +
                "a00077de6f7b2d58b493439c9bcdf4c55c2f04e400b5c8cf73226053721d5e0e"
        ),
        "invite-code ONBOARD_SUBMIT" to SignedPayload(
            linkedMapOf("timestamp" to timestamp, "invite_code" to "BETA2024"),
            """{"invite_code":"BETA2024","timestamp":1760000000}""",
            "3079a914a345e31266e215a86d0891d7c2ae2b183a9a0943a11d6aec383ef9e0" +
                "ca4346b90a4cb4f32f1cfb1ab52dc39c17634543cb7ca1269f57d13f9fbb430f"
        ),
        // The payment travels as a Double, so the relay re-encodes 10.0, not 10.
        "payment ONBOARD_SUBMIT" to SignedPayload(
            linkedMapOf("timestamp" to timestamp, "payment" to 10.0),
            """{"payment":10.0,"timestamp":1760000000}""",
            "383314ab38172a6233a07d8c11a91e61abbedee97e14021dc933e0b38582fc54" +
                "bc7c0562426697737d069d181d1f9922f88526902cc69732f6c06ecf8e90de08"
        ),
        // Unescaped UTF-8 — the server accepts it alongside the \u-escaped form.
        "non-ASCII invite code" to SignedPayload(
            linkedMapOf("timestamp" to timestamp, "invite_code" to "邀请码"),
            """{"invite_code":"邀请码","timestamp":1760000000}""",
            "aa09bb55af48edcef0500a28d0e9906db2c0f2f29cbfac9a350a2b20ca2ee6b1" +
                "494968bde3df813b36056b16914221421d863678a28709642bf31fd492e71603"
        )
    )

    @Test
    fun `signed payloads canonicalise to the bytes the relay re-encodes`() {
        for ((name, p) in signedPayloads) {
            assertEquals(name, p.canonical, keyManager.canonicalJson(p.fields))
        }
    }

    @Test
    fun `signed payloads carry the signature the relay verifies`() {
        val keys = keyManager.keysFromMnemonic(phrases.first().phrase)
        for ((name, p) in signedPayloads) {
            assertEquals(name, p.signature, keyManager.sign(keys, keyManager.canonicalJson(p.fields)))
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
