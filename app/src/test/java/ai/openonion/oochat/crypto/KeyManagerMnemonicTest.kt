package ai.openonion.oochat.crypto

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cash.z.ecc.android.bip39.Mnemonics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recovery-phrase half of [KeyManager]: minting an identity with a phrase,
 * restoring one from it, and what "Backup Seed Phrase" then shows.
 *
 * [KeyManagerPersistenceTest] covers the hex paths; these were out of reach
 * until [Ed25519] made libsodium substitutable. [BouncyCastleEd25519] stands in
 * for it, held to libsodium's own output by [IdentityVectorsTest], so the
 * addresses asserted here are the ones a phone derives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyManagerMnemonicTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val keyManager = KeyManager(context, BouncyCastleEd25519())

    private val phrase12 =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val address12 = "0xf8405257284c15e67fd759703d0c04afdf2f216153b9781946f096cae1990a95"
    private val phrase24 = (List(23) { "abandon" } + "art").joinToString(" ")
    private val address24 = "0x8f0603db74882f8040f8c153f76cfe51f5296a9118a702b7f86a6f183a6866f8"

    // ── first launch ──────────────────────────────────────────────────

    @Test
    fun `a first launch mints a phrase-backed identity and keeps it`() {
        val minted = keyManager.loadOrGenerate()

        assertTrue("address ${minted.address}", Regex("^0x[0-9a-f]{64}$").matches(minted.address))
        assertEquals("a second launch must load, not mint again", minted.address, keyManager.loadOrGenerate().address)

        val backup = keyManager.exportBackup()
        assertTrue("backup should be the phrase, got $backup", backup is KeyManager.BackupExport.Phrase)
        val words = (backup as KeyManager.BackupExport.Phrase).mnemonic.split(" ")
        assertEquals(12, words.size)
        assertEquals(
            "the backed-up phrase must restore the identity it came with",
            minted.address,
            keyManager.keysFromMnemonic(backup.mnemonic).address
        )
    }

    @Test
    fun `the identity survives the process - a new KeyManager on the same store loads it`() {
        val minted = keyManager.loadOrGenerate()

        val afterRestart = KeyManager(context, BouncyCastleEd25519())

        assertEquals(minted.address, afterRestart.load()?.address)
    }

    @Test
    fun `two fresh identities are never the same one`() {
        val first = keyManager.generateWithMnemonic()
        val second = keyManager.generateWithMnemonic()

        assertNotEquals(first.mnemonic, second.mnemonic)
        assertNotEquals(first.addressData.address, second.addressData.address)
    }

    // ── restore ───────────────────────────────────────────────────────

    @Test
    fun `importing a 12-word phrase restores its address and becomes the backup`() {
        keyManager.loadOrGenerate()

        val restored = keyManager.importFromPhraseOrHex(phrase12)

        assertEquals(address12, restored.address)
        assertEquals(address12, keyManager.load()?.address)
        assertEquals(KeyManager.BackupExport.Phrase(phrase12), keyManager.exportBackup())
    }

    @Test
    fun `importing a 24-word phrase restores its address`() {
        assertEquals(address24, keyManager.importFromPhraseOrHex(phrase24).address)
        assertEquals(address24, keyManager.load()?.address)
    }

    @Test
    fun `a phrase typed in capitals with stray outer spaces still restores`() {
        val restored = keyManager.importFromPhraseOrHex("  ${phrase12.uppercase()}\n")

        assertEquals(address12, restored.address)
    }

    @Test
    fun `a phrase with a bad checksum is rejected and the current identity survives`() {
        val current = keyManager.loadOrGenerate()
        // Every word valid, last word wrong for the checksum.
        val badChecksum = phrase12.replace("yellow", "wave")

        assertThrows(Mnemonics.ChecksumException::class.java) {
            keyManager.importFromPhraseOrHex(badChecksum)
        }

        assertEquals(current.address, keyManager.load()?.address)
    }

    @Test
    fun `a phrase with a word outside the list is rejected and the current identity survives`() {
        val current = keyManager.loadOrGenerate()

        assertThrows(Mnemonics.InvalidWordException::class.java) {
            keyManager.importFromPhraseOrHex(phrase12.replace("legal", "legally"))
        }

        assertEquals(current.address, keyManager.load()?.address)
    }

    @Test
    fun `a hex import after a phrase identity drops the phrase from the backup`() {
        keyManager.importFromPhraseOrHex(phrase12)

        keyManager.importFromPhraseOrHex(TestIdentity.hexKey())

        // A stale phrase here would back up an identity that is no longer the
        // one in use.
        assertEquals(
            KeyManager.BackupExport.LegacyHexKey(TestIdentity.hexKey()),
            keyManager.exportBackup()
        )
    }

    @Test
    fun `a valid 15, 18 or 21-word phrase restores like any other`() {
        // BIP39 allows 12, 15, 18, 21 and 24 words, and so do the connectonion
        // SDK's recover() and the React client's seedFromRecovery(). Import
        // used to route only 12 and 24 to the phrase path, so these fell
        // through to hex and were refused as "Invalid key length".
        //
        // Addresses from the connectonion SDK (v1.9.2b28), the same SLIP-0010
        // derivation as the other phrases in this file.
        val phrases = mapOf(
            (List(14) { "abandon" } + "address").joinToString(" ") to
                "0xd7ca67119a20dcf17260a9e7b07c7db79a4452beabc849143bb50ce37784ca16",
            (List(17) { "abandon" } + "agent").joinToString(" ") to
                "0x0e9d0b749ea3788a66d3dc7d68171f96560bac25fe6c9c67456f6161dbaa1c64",
            (List(20) { "abandon" } + "admit").joinToString(" ") to
                "0x96ef0856a854247b15aec93351545b86f5b515e6bc1092fe12a2351c88169cec"
        )

        for ((phrase, address) in phrases) {
            val words = phrase.split(" ").size

            assertEquals("$words words", address, keyManager.importFromPhraseOrHex(phrase).address)
            assertEquals("$words words", KeyManager.BackupExport.Phrase(phrase), keyManager.exportBackup())
        }
    }

    @Test
    fun `a phrase with a double space between two words is restored`() {
        val doubleSpaced = phrase12.replaceFirst(" ", "  ")

        assertEquals(address12, keyManager.importFromPhraseOrHex(doubleSpaced).address)
    }

    @Test
    fun `a phrase typed across lines and tabs is backed up single-spaced`() {
        // Backup shows the stored phrase, so it is stored the way BIP39 reads
        // it rather than the way it happened to be pasted.
        val messy = phrase12.split(" ").chunked(4).joinToString("\n") { it.joinToString(" \t") }

        assertEquals(address12, keyManager.importFromPhraseOrHex(messy).address)
        assertEquals(KeyManager.BackupExport.Phrase(phrase12), keyManager.exportBackup())
    }

    // ── identities minted before SLIP-0010 ────────────────────────────
    //
    // Before W16AAPPLE-97 a phrase derived the key from the first 32 bytes of
    // its BIP39 seed. Those installs keep their key; the phrase beside it now
    // derives a different one, which the app has to say.

    /** What the app wrote for [phrase12] before the switch: the seed[:32] key, and the phrase. */
    private fun seedPreSlip10Install() {
        val seedHalf = "878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe"
        val publicKey = "c6f2ac5598970c79633714d3eb5c34d7bfc3e92da58c7354b37996d9a4af3ab2"
        // The plain-file fallback SafePreferencesWrapper lands on under
        // Robolectric — see KeyManagerPersistenceTest.
        context.getSharedPreferences("${KeyManager.PREFS_NAME}_standard", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("address", "0x$publicKey")
            .putString("public_key", publicKey)
            .putString("private_key", seedHalf + publicKey)
            .putString("mnemonic", phrase12)
            .commit()
    }

    private val preSlip10Address12 = "0xc6f2ac5598970c79633714d3eb5c34d7bfc3e92da58c7354b37996d9a4af3ab2"

    @Test
    fun `an update keeps a pre-SLIP-0010 identity's stored key and address`() {
        seedPreSlip10Install()

        assertEquals(preSlip10Address12, keyManager.loadOrGenerate().address)
        assertEquals(preSlip10Address12, keyManager.load()?.address)
    }

    @Test
    fun `a stored phrase that no longer derives the stored key is reported`() {
        seedPreSlip10Install()

        assertTrue(keyManager.hasLegacyDerivation())
        // The phrase is still kept, but the key is what restores this identity.
        val backup = keyManager.exportBackup() as KeyManager.BackupExport.Phrase
        assertEquals(phrase12, backup.mnemonic)
        assertEquals(keyManager.load()!!.privateKey.toHex(), backup.legacyPrivateKeyHex)
        assertEquals(preSlip10Address12, keyManager.keysFromHex(backup.legacyPrivateKeyHex!!).address)
    }

    @Test
    fun `restoring from the old phrase moves to the SDK's address and clears the warning`() {
        seedPreSlip10Install()
        assertTrue(keyManager.hasLegacyDerivation())

        keyManager.importFromPhraseOrHex(phrase12)

        assertEquals(address12, keyManager.load()?.address)
        assertFalse(keyManager.hasLegacyDerivation())
        assertEquals(KeyManager.BackupExport.Phrase(phrase12), keyManager.exportBackup())
    }

    @Test
    fun `saving a new key under the same phrase rechecks rather than serving the cached answer`() {
        // Only the key changes here; the stored phrase is not touched, so the
        // reset in saveMnemonic() never runs. The one in save() is what has to
        // drop the cached "legacy".
        seedPreSlip10Install()
        assertTrue(keyManager.hasLegacyDerivation())

        keyManager.save(keyManager.keysFromMnemonic(phrase12))

        assertFalse(keyManager.hasLegacyDerivation())
    }

    @Test
    fun `a current identity, a hex import and no identity at all are not legacy`() {
        assertFalse("no identity", keyManager.hasLegacyDerivation())

        keyManager.loadOrGenerate()
        assertFalse("freshly minted", keyManager.hasLegacyDerivation())

        keyManager.importFromPhraseOrHex(phrase24)
        assertFalse("restored phrase", keyManager.hasLegacyDerivation())

        keyManager.importFromPhraseOrHex(TestIdentity.hexKey())
        assertFalse("hex import has no phrase to disagree with", keyManager.hasLegacyDerivation())
    }

    @Test
    fun `a hex import is unchanged - the key it is given is the key it keeps`() {
        // Same 128 hex characters, same address, before and after W16AAPPLE-97:
        // no phrase is involved, so no derivation is either.
        val oldKey = "878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe" +
            "c6f2ac5598970c79633714d3eb5c34d7bfc3e92da58c7354b37996d9a4af3ab2"

        assertEquals(preSlip10Address12, keyManager.importFromPhraseOrHex(oldKey).address)
        assertEquals(KeyManager.BackupExport.LegacyHexKey(oldKey), keyManager.exportBackup())
    }

    // ── reset ─────────────────────────────────────────────────────────

    @Test
    fun `reset replaces the identity and hands back the phrase that restores it`() {
        val before = keyManager.importFromPhraseOrHex(phrase12)

        val fresh = keyManager.resetIdentity()

        assertNotEquals(before.address, fresh.addressData.address)
        assertEquals(fresh.addressData.address, keyManager.load()?.address)
        assertEquals(KeyManager.BackupExport.Phrase(fresh.mnemonic), keyManager.exportBackup())
        assertEquals(fresh.addressData.address, keyManager.keysFromMnemonic(fresh.mnemonic).address)
    }

    // ── signing with the stored identity ──────────────────────────────

    @Test
    fun `a signature is 64 bytes of hex and repeatable`() {
        val keys = keyManager.importFromPhraseOrHex(phrase12)
        val loaded = keyManager.load()
        assertNotNull(loaded)

        val signature = keyManager.sign(keys, """{"timestamp":1760000000}""")

        // The relay parses it with bytes.fromhex, so either case would verify.
        assertTrue(signature, Regex("^[0-9a-fA-F]{128}$").matches(signature))
        assertEquals(
            "the identity read back from storage must sign identically",
            signature,
            keyManager.sign(loaded!!, """{"timestamp":1760000000}""")
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
