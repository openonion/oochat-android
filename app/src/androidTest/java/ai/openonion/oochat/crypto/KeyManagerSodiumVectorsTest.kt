package ai.openonion.oochat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The known answers from the JVM's IdentityVectorsTest, against the real
 * libsodium binary.
 *
 * The JVM suite runs identity code on an RFC 8032 stand-in because the `.so`
 * only loads on a device; this is the one place the production [KeyManager]
 * — `KeyManager(context)`, libsodium and all — is held to the same values. If
 * the stand-in and the phone ever disagree, this is where it shows.
 */
@RunWith(AndroidJUnit4::class)
class KeyManagerSodiumVectorsTest {

    private val keyManager = KeyManager(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun a_recovery_phrase_restores_the_address_co_recovers_from_it() {
        assertEquals(
            "0x54ff7e165926bca30e8b1535f22c053a0e0e2ca6d5a445a149bde23d90c6ee1e",
            keyManager.keysFromMnemonic(ABANDON_ABOUT).address
        )
        assertEquals(
            "0xf8405257284c15e67fd759703d0c04afdf2f216153b9781946f096cae1990a95",
            keyManager.keysFromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow").address
        )
    }

    @Test
    fun a_relay_connect_payload_carries_the_signature_the_relay_verifies() {
        val keys = keyManager.keysFromMnemonic(ABANDON_ABOUT)
        val canonical = keyManager.canonicalJson(
            linkedMapOf("to" to "0x" + "ab".repeat(32), "timestamp" to 1_760_000_000L)
        )

        assertEquals(
            "2402db638f0cf481d7107ea49fec3bb319a6e186cd24287dd37251d42db55970" +
                "7047184d9625db0b53e9907c2934325742d13b64ea28ba826369a70621ac580a",
            keyManager.sign(keys, canonical)
        )
    }

    private companion object {
        const val ABANDON_ABOUT =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
