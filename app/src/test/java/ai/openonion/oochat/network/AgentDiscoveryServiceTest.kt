package ai.openonion.oochat.network

import ai.openonion.oochat.util.FileLogger
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The address branch of [AgentDiscoveryService.resolveAgentAddress].
 *
 * A ConnectOnion address is an Ed25519 public key — `0x` plus 64 hex chars.
 * The regex said `{40}`, an Ethereum-style address, so no real address ever
 * matched and every connect fell through to the unrecognized-input fallback.
 *
 * That fallback returns the same [ResolvedAgent] the address branch does,
 * which is why the bug survived: asserting on the return value alone cannot
 * tell the two apart, and such a test would pass against the broken regex.
 * These assert on the line the branch logs, which is the only thing that
 * differs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentDiscoveryServiceTest {

    private val service = AgentDiscoveryService()

    /** A real relay address: `0x` + 64 hex = 66 characters. */
    private val realAddress =
        "0x537d9356b8348eccff6337754b4927a6e554a032e116fc26c1b3d7aa42ec5ea7"

    @Before
    fun setUp() {
        FileLogger.init(ApplicationProvider.getApplicationContext())
        FileLogger.clear()
    }

    private fun resolveAndReadLog(input: String): Pair<ResolvedAgent, String> = runBlocking {
        val resolved = service.resolveAgentAddress(input)
        FileLogger.awaitDrain()
        resolved to FileLogger.readLogs(500)
    }

    @Test
    fun `a 64-hex address is recognised as an address, not fallback material`() {
        assertEquals("test setup: an address is 0x + 64 hex", 66, realAddress.length)

        val (resolved, log) = resolveAndReadLog(realAddress)

        assertEquals(realAddress, resolved.address)
        assertTrue(
            "a real address must take the address branch, but the log says: $log",
            log.contains("Input is valid address")
        )
    }

    @Test
    fun `an Ethereum-shaped address is not a ConnectOnion address`() {
        val ethShaped = "0x" + "a".repeat(40)

        val (resolved, log) = resolveAndReadLog(ethShaped)

        // Still returned as-is — the fallback is deliberately permissive, since
        // refusing an address the relay might accept is worse than trying it.
        assertEquals(ethShaped, resolved.address)
        assertTrue(
            "42 chars is not an Ed25519 key and must not pass the check",
            log.contains("Unrecognized input")
        )
    }

    @Test
    fun `surrounding whitespace is trimmed before the address is matched`() {
        val (resolved, log) = resolveAndReadLog("  $realAddress\n")

        assertEquals(realAddress, resolved.address)
        assertTrue("whitespace must not push an address into the fallback", log.contains("Input is valid address"))
    }

    @Test
    fun `an uppercase-hex address is recognised`() {
        val upper = "0x" + realAddress.removePrefix("0x").uppercase()

        val (resolved, log) = resolveAndReadLog(upper)

        assertEquals(upper, resolved.address)
        assertTrue("hex is case-insensitive", log.contains("Input is valid address"))
    }
}
