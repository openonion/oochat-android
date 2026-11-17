package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.di.sharedHttpClient
import ai.openonion.oochat.domain.model.UserProfile
import ai.openonion.oochat.network.HostClock
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Talks to `oo.openonion.ai`'s account API. Auth is a signed-challenge
 * scheme, not OAuth: sign `ConnectOnion-Auth-{address}-{timestamp}` with the
 * wallet's Ed25519 key, trade the signature for a JWT at `/api/v1/auth`,
 * then use that JWT as a bearer token against `/api/v1/auth/me` for the
 * balance/credits profile. Ported from the web client's
 * `hooks/use-identity.ts` (`authenticateWithOpenOnion` / `getUserProfile`)
 * so a wallet identity behaves identically on both clients.
 *
 * The web client proxies these two calls through its own `/api/auth` Next.js
 * route purely to dodge browser CORS; Android has no such restriction and
 * calls oo.openonion.ai directly.
 */
class AccountRepositoryImpl(
    private val keyManager: KeyManager,
    private val baseUrl: String = "https://oo.openonion.ai",
    deviceMillis: () -> Long = System::currentTimeMillis
) : AccountRepository {

    /**
     * `/api/v1/auth` refuses a timestamp more than five minutes from its own clock, and the app
     * signs in again on every launch, so a phone whose clock is off had no balance and no voice
     * transcription at all. Each sign-in response's `Date` header corrects the clock, bounded as
     * for the agent connection (W16AAPPLE-90).
     */
    private val serverClock = HostClock(deviceMillis)

    // Derived from the app's one client rather than built fresh, so these
    // calls reuse the connection already pooled for oo.openonion.ai instead
    // of paying their own TLS handshake — see [sharedHttpClient].
    private val httpClient = sharedHttpClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** In-memory only — re-authenticating is cheap (one signed request), no need to persist. */
    @Volatile private var cachedToken: String? = null

    /**
     * Identity epoch, bumped by [invalidate] on every identity switch
     * (reset/import). A load that began under an older epoch can neither
     * publish its token into [cachedToken] (see [authenticate]) nor return
     * its snapshot (checked before returning below), so an auth/fetch
     * already in flight across the switch cannot reinstate the old
     * identity's token or hand its stale profile to the UI.
     */
    private val identityEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    override fun invalidate() {
        // Epoch first, then the token: the reverse order would let an
        // in-flight authenticate() observe the old epoch as still current
        // and re-publish the old identity's token right after the clear.
        identityEpoch.incrementAndGet()
        cachedToken = null
        FileLogger.i(LogTags.ACCOUNT, "account cache invalidated (identity changed)")
    }

    override suspend fun loadAccount(forceReauth: Boolean): Result<AccountSnapshot> {
        return withContext(Dispatchers.IO) {
            val epochAtStart = identityEpoch.get()
            runCatchingCancellable {
                val bearer = cachedToken.takeUnless { forceReauth } ?: authenticate(epochAtStart)
                val snapshot = try {
                    AccountSnapshot(bearer, fetchProfile(bearer))
                } catch (e: AuthExpiredException) {
                    // Token rejected server-side (expired/revoked) — re-auth once and retry.
                    val fresh = authenticate(epochAtStart)
                    AccountSnapshot(fresh, fetchProfile(fresh))
                }
                if (identityEpoch.get() != epochAtStart) {
                    // Identity switched while this load was in flight — the
                    // snapshot belongs to the previous identity, never
                    // surface it. Callers just see a failed load and retry.
                    throw StaleIdentityException()
                }
                snapshot
            }.onFailure { e ->
                // Scoped to our own epoch so a stale load's cleanup can't
                // wipe a token the *new* identity's load already cached.
                if (identityEpoch.get() == epochAtStart) cachedToken = null
                FileLogger.e(LogTags.ACCOUNT, "loadAccount failed: ${e.message}")
            }
        }
    }

    private fun authenticate(epochAtStart: Int): String {
        val keys = keyManager.loadOrGenerate()
        val offsetBefore = serverClock.offsetMillis
        val token = try {
            signIn(keys, serverClock.nowSeconds())
        } catch (refused: AuthRefusedException) {
            // The refusal's Date has just corrected the clock. If that moved it, one more try
            // signed with the server's time can pass; with the same correction it would be
            // refused again. Compared by offset, not by time passed: a slow 401 changes nothing.
            // A Date past HostClock's bound resets the correction instead of applying it, which
            // is a change too, so the retry must also land inside the server's window.
            val retryAt = serverClock.nowSeconds()
            val corrected = abs(serverClock.offsetMillis - offsetBefore) >= MIN_CORRECTION_MS
            if (!corrected || !refused.wouldAccept(retryAt)) throw refused.clockProblemOrSelf()
            try {
                signIn(keys, retryAt)
            } catch (again: AuthRefusedException) {
                // Signed with the server's own time, so the clock is ruled out unless the
                // server's Date moved again since.
                throw again.clockProblemOrSelf()
            }
        }
        // Only publish under the epoch this load started with - if the
        // identity switched mid-auth, this token was signed by the OLD
        // keys and caching it would resurrect the stale identity.
        if (identityEpoch.get() == epochAtStart) cachedToken = token
        return token
    }

    /** One signed sign-in at [timestamp]; feeds the response's `Date`, if any, to [serverClock]. */
    private fun signIn(keys: KeyManager.AddressData, timestamp: Long): String {
        val message = "ConnectOnion-Auth-${keys.address}-$timestamp"
        val signature = keyManager.sign(keys, message)

        val payload = json.encodeToString(
            AuthRequest.serializer(),
            AuthRequest(publicKey = keys.address, signature = signature, message = message)
        )
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val serverMillis = serverClock.correctFrom(response)
            val body = response.body?.string()
            if (response.code == 401) {
                // Against what was signed, not this device's raw clock: a sign-in already signed
                // with the corrected time and refused for another reason is not the clock's fault.
                throw AuthRefusedException(serverMillis = serverMillis, signedAt = timestamp)
            }
            if (!response.isSuccessful || body == null) {
                throw AuthFailedException("auth returned ${response.code}")
            }
            return json.decodeFromString(AuthResponse.serializer(), body).token
        }
    }

    private fun fetchProfile(token: String): UserProfile {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 401) throw AuthExpiredException()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw ProfileFetchException("profile returned ${response.code}")
            }
            val dto = json.decodeFromString(ProfileResponse.serializer(), body)
            return UserProfile(
                publicKey = dto.publicKey,
                creditsUsd = dto.creditsUsd,
                totalCostUsd = dto.totalCostUsd,
                balanceUsd = dto.balanceUsd
            )
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L

        /** A correction smaller than this cannot be why a sign-in was refused. */
        private const val MIN_CORRECTION_MS = 60_000L
    }
}

/** `/api/v1/auth`'s timestamp window, from oo-api's published spec. */
private const val AUTH_WINDOW_MS = 5 * 60 * 1000L

private class AuthFailedException(message: String) : Exception(message)

/**
 * A 401 from `/api/v1/auth`. [serverMillis] is the server's clock when it answered, if it sent one;
 * its skew is measured against the timestamp actually signed, not this device's raw clock.
 */
private class AuthRefusedException(
    private val serverMillis: Long?,
    signedAt: Long
) : Exception("auth returned 401 (skew ${serverMillis?.let { it - signedAt * 1000 }}ms)") {
    private val skewMillis = serverMillis?.let { it - signedAt * 1000 }

    /** Whether a signature at [timestamp] would fall inside the server's window, by its own clock. */
    fun wouldAccept(timestamp: Long): Boolean =
        serverMillis != null && abs(serverMillis - timestamp * 1000) <= AUTH_WINDOW_MS

    fun clockProblemOrSelf(): Exception =
        if (skewMillis != null && abs(skewMillis) > AUTH_WINDOW_MS) AccountClockException(this) else this
}
private class AuthExpiredException : Exception("token expired")
private class ProfileFetchException(message: String) : Exception(message)
private class StaleIdentityException : Exception("identity changed while account load was in flight")

@Serializable
private data class AuthRequest(
    @SerialName("public_key") val publicKey: String,
    val signature: String,
    val message: String
)

@Serializable
private data class AuthResponse(val token: String)

@Serializable
private data class ProfileResponse(
    @SerialName("public_key") val publicKey: String,
    @SerialName("credits_usd") val creditsUsd: Double,
    @SerialName("total_cost_usd") val totalCostUsd: Double,
    @SerialName("balance_usd") val balanceUsd: Double
)
