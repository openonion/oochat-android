package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.BouncyCastleEd25519
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AccountRepositoryImpl] against a real [MockWebServer] —
 * `baseUrl` is already a constructor param, so no production seam was
 * needed to point it at a local server instead of `oo.openonion.ai`.
 *
 * [AccountRepositoryImpl.authenticate] calls [KeyManager.sign], which needs
 * the native libsodium binary (blocked on a host JVM regardless of
 * Robolectric, see [ai.openonion.oochat.crypto.KeyManagerTest]), so
 * most tests here seed the private `cachedToken` field via reflection
 * instead of going through the real auth call. The sign-in clock tests
 * (W16AAPPLE-91) sign for real with [BouncyCastleEd25519]. [AccountRepositoryImpl.loadAccount]
 * skips `authenticate()` entirely whenever a cached token exists and
 * `forceReauth` is false, so this exercises exactly the same `fetchProfile()`
 * code path a real cached-token call would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        repository = AccountRepositoryImpl(keyManager, baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun seedCachedToken(token: String) {
        val field = AccountRepositoryImpl::class.java.getDeclaredField("cachedToken")
        field.isAccessible = true
        field.set(repository, token)
    }

    private fun readCachedToken(): String? {
        val field = AccountRepositoryImpl::class.java.getDeclaredField("cachedToken")
        field.isAccessible = true
        return field.get(repository) as String?
    }

    @Test
    fun `loadAccount with a cached token fetches the profile without re-authenticating`() = runTest {
        seedCachedToken("cached-token-123")
        server.enqueue(
            MockResponse().setBody(
                """{"public_key":"0xabc","credits_usd":10.5,"total_cost_usd":2.5,"balance_usd":8.0}"""
            ).setResponseCode(200)
        )

        val result = repository.loadAccount(forceReauth = false)

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals("cached-token-123", snapshot.apiKey)
        assertEquals("0xabc", snapshot.profile.publicKey)
        assertEquals(10.5, snapshot.profile.creditsUsd, 0.001)
        assertEquals(8.0, snapshot.profile.balanceUsd, 0.001)

        val recorded = server.takeRequest()
        assertEquals("Bearer cached-token-123", recorded.getHeader("Authorization"))
        assertEquals("/api/v1/auth/me", recorded.path)
    }

    @Test
    fun `loadAccount fails and clears the cached token when the profile request errors`() = runTest {
        seedCachedToken("cached-token-123")
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.loadAccount(forceReauth = false)

        assertTrue(result.isFailure)
        assertNull("a failed load must clear the cached token", readCachedToken())
    }

    @Test
    fun `invalidate clears the cached token`() {
        seedCachedToken("old-identity-token")

        repository.invalidate()

        assertNull("invalidate must forget the identity-scoped JWT", readCachedToken())
    }

    @Test
    fun `after invalidate the old identity's bearer is never sent to the server`() = runTest {
        seedCachedToken("old-identity-token")
        repository.invalidate()

        // With the cache invalidated, loadAccount must re-authenticate as
        // the *current* identity instead of reusing the old JWT. On the host
        // JVM the native Ed25519 signer is unavailable (see class doc), so
        // the re-auth attempt dies before any HTTP — which is exactly the
        // point: no request carrying the stale bearer may ever go out.
        runCatching { repository.loadAccount() }

        assertEquals(
            "no request (in particular none with the old bearer) may reach the server",
            0,
            server.requestCount
        )
    }

    @Test
    fun `loadAccount fails when the profile response body is malformed`() = runTest {
        seedCachedToken("cached-token-123")
        server.enqueue(MockResponse().setBody("not json").setResponseCode(200))

        val result = repository.loadAccount(forceReauth = false)

        assertTrue(result.isFailure)
    }

    // -- W16AAPPLE-91: signing in with the server's time --

    /** A whole second, so a `Date` header (whole seconds) can say it exactly. */
    private val deviceNow = 1_800_000_000_000L

    private fun signingRepository(deviceMillis: () -> Long = { deviceNow }): AccountRepositoryImpl {
        val keyManager = KeyManager(ApplicationProvider.getApplicationContext(), BouncyCastleEd25519())
        keyManager.save(TestIdentity.keys())
        return AccountRepositoryImpl(keyManager, baseUrl = server.url("/").toString().trimEnd('/'), deviceMillis = deviceMillis)
    }

    /** Signs in once against a server [serverNow] ahead, so the repository learns that correction. */
    private suspend fun learnCorrection(repository: AccountRepositoryImpl, serverNow: Long) {
        server.enqueue(auth(401, serverNow, refused))
        server.enqueue(auth(200, serverNow))
        server.enqueue(profileOk())
        repository.loadAccount().exceptionOrNull()?.let { throw AssertionError("sign-in failed", it) }
        repeat(3) { server.takeRequest() }
    }

    /** `/api/v1/auth`'s answer, with the server's clock in `Date` as the live service sends it. */
    private fun auth(code: Int, serverMillis: Long, body: String = """{"token":"jwt-1"}"""): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)
            .addHeader("Date", Headers.Builder().add("Date", java.util.Date(serverMillis)).build()["Date"]!!)

    private val refused = """{"detail":"Message expired. Timestamp must be within 5 minutes."}"""

    private fun profileOk() = MockResponse().setResponseCode(200)
        .setBody("""{"public_key":"0xabc","credits_usd":1.0,"total_cost_usd":0.0,"balance_usd":1.0}""")

    /** The timestamp a recorded sign-in signed. */
    private fun signedTimestamp(): Long {
        val request = server.takeRequest()
        assertEquals("/api/v1/auth", request.path)
        val message = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["message"]!!.jsonPrimitive.content
        return message.substringAfterLast('-').toLong()
    }

    @Test
    fun `a clock that agrees with the server signs in at the first try`() = runTest {
        val repository = signingRepository()
        server.enqueue(auth(200, deviceNow))
        server.enqueue(profileOk())

        repository.loadAccount().exceptionOrNull()?.let { throw AssertionError("sign-in failed", it) }
        assertEquals(deviceNow / 1000, signedTimestamp())
    }

    @Test
    fun `a refusal from a server whose clock is ahead is retried once with its time, which then sticks`() = runTest {
        val repository = signingRepository()
        val serverNow = deviceNow + 10 * 60_000L
        server.enqueue(auth(401, serverNow, refused))
        server.enqueue(auth(200, serverNow))
        server.enqueue(profileOk())

        repository.loadAccount().exceptionOrNull()?.let { throw AssertionError("sign-in failed", it) }
        assertEquals(deviceNow / 1000, signedTimestamp())
        assertEquals("the retry signs with the server's time", serverNow / 1000, signedTimestamp())
        server.takeRequest()

        // The next launch's sign-in, in the same process, starts from the corrected clock.
        server.enqueue(auth(200, serverNow))
        server.enqueue(profileOk())
        repository.loadAccount(forceReauth = true).exceptionOrNull()?.let { throw AssertionError("sign-in failed", it) }
        assertEquals(serverNow / 1000, signedTimestamp())
    }

    @Test
    fun `a server clock past the bound is not trusted, and the failure says the clock is wrong`() = runTest {
        val repository = signingRepository()
        server.enqueue(auth(401, deviceNow + 2 * 60 * 60_000L, refused))

        val result = repository.loadAccount()

        assertTrue(result.exceptionOrNull() is AccountClockException)
        assertEquals("no retry: the correction would be past the 30-minute bound", 1, server.requestCount)
    }

    @Test
    fun `a refusal when the clocks agree is not retried and not blamed on the clock`() = runTest {
        val repository = signingRepository()
        server.enqueue(auth(401, deviceNow, """{"detail":"Invalid signature"}"""))

        val result = repository.loadAccount()

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is AccountClockException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a retry signed with the server's time and refused again is not blamed on the clock`() = runTest {
        val repository = signingRepository()
        val serverNow = deviceNow + 10 * 60_000L
        server.enqueue(auth(401, serverNow, refused))
        server.enqueue(auth(401, serverNow, """{"detail":"Invalid signature"}"""))

        val result = repository.loadAccount()

        assertTrue(result.isFailure)
        assertFalse("the retry carried the server's own time", result.exceptionOrNull() is AccountClockException)
        assertEquals("one retry, not a loop", 2, server.requestCount)
    }

    @Test
    fun `a refusal after the clock was already corrected is not blamed on the clock`() = runTest {
        val repository = signingRepository()
        val serverNow = deviceNow + 10 * 60_000L
        learnCorrection(repository, serverNow)
        server.enqueue(auth(401, serverNow, """{"detail":"Invalid signature"}"""))

        val result = repository.loadAccount(forceReauth = true)

        assertTrue(result.isFailure)
        assertFalse(
            "the device clock is still 10 minutes off, but this sign-in was signed with the server's time",
            result.exceptionOrNull() is AccountClockException
        )
        assertEquals("nothing for a retry to correct", 4, server.requestCount)
    }

    @Test
    fun `a response with no Date keeps the correction already learned`() = runTest {
        val repository = signingRepository()
        val serverNow = deviceNow + 10 * 60_000L
        learnCorrection(repository, serverNow)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(repository.loadAccount(forceReauth = true).isFailure)
        server.takeRequest()

        server.enqueue(auth(200, serverNow))
        server.enqueue(profileOk())
        repository.loadAccount(forceReauth = true).exceptionOrNull()?.let { throw AssertionError("sign-in failed", it) }

        assertEquals("still signed with the server's time", serverNow / 1000, signedTimestamp())
    }

    @Test
    fun `a slow refusal from a server whose clock agrees is not retried`() = runTest {
        // Each reading of the device clock is 90 s later, as if the 401 took that long; the
        // server's Date matches the device's clock when it answered.
        var reads = 0
        val repository = signingRepository(deviceMillis = { deviceNow + 90_000L * reads++ })
        server.enqueue(auth(401, deviceNow + 90_000L, """{"detail":"Invalid signature"}"""))

        val result = repository.loadAccount()

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is AccountClockException)
        assertEquals("time passing is not a correction", 1, server.requestCount)
    }

    @Test
    fun `a Date past the bound after a learned correction does not cost a retry`() = runTest {
        // The reading past HostClock's bound resets the correction to the device clock, which
        // the server's window would refuse anyway.
        val repository = signingRepository()
        learnCorrection(repository, deviceNow + 10 * 60_000L)
        server.enqueue(auth(401, deviceNow + 40 * 60_000L, refused))

        val result = repository.loadAccount(forceReauth = true)

        assertTrue(result.exceptionOrNull() is AccountClockException)
        assertEquals("no retry with a time the server would refuse", 4, server.requestCount)
    }

    @Test
    fun `a clock failure keeps the refusal it came from`() = runTest {
        val repository = signingRepository()
        server.enqueue(auth(401, deviceNow + 2 * 60 * 60_000L, refused))

        val failure = repository.loadAccount().exceptionOrNull()

        assertTrue(failure is AccountClockException)
        assertTrue("cause: ${failure?.cause}", failure?.cause?.message?.contains("401") == true)
    }
}
