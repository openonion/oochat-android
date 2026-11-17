package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.PlanEntryWire
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.ConnectionEvent
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.network.WebSocketFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reproduces the "first conversation loses the whole reply on reconnect" bug.
 *
 * Every SessionStore write in ConnectionRepositoryImpl hangs off a non-null
 * `event.session` (CONNECTED at :108, OUTPUT at :155) or off the explicit
 * disconnect() path at :270. A brand-new conversation hits none of them: the
 * server's CONNECTED for a fresh session carries `session_id` but no `session`
 * body, mid-turn state arrives as `session_sync` (which the repository doesn't
 * persist at all), and the agent-switch teardown inside connect() calls
 * AgentConnection.disconnect() directly, bypassing :270. The sessionId is
 * therefore never written, the next attempt connects with sessionId=null, and
 * the server orphans the in-flight reply.
 *
 * Every frame here is delivered exactly once. ConnectionRepositoryImpl.connect()
 * does not return until its collector is subscribed to `conn.events`, so a frame
 * fed afterwards cannot be dropped by the replay=0 bus; re-sending until
 * something sticks would only hide a regression in that guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionRepositorySessionPersistenceTest {

    private class FakeWebSocket : WebSocket {
        val sentMessages = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url("wss://fake.example.com/ws").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sentMessages += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private class FakeWebSocketFactory : WebSocketFactory {
        val createCount = MutableStateFlow(0)
        @Volatile var lastListener: WebSocketListener? = null
        @Volatile var lastWebSocket: FakeWebSocket? = null
        @Volatile var frameOnNextCreate: String? = null
        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            val socket = FakeWebSocket()
            lastWebSocket = socket
            lastListener = listener
            // Published last, so a waiter that sees the new count is also
            // guaranteed to see the listener that belongs to it.
            createCount.update { it + 1 }
            frameOnNextCreate?.let { frame ->
                frameOnNextCreate = null
                // Deliberately before create() returns: a real WebSocket
                // normally calls back later, but this closes the exact
                // ordering window a slow CI runner can expose.
                listener.onMessage(socket, frame)
            }
            return socket
        }
    }

    /** Records every write so a test can assert what (if anything) was persisted. */
    private class RecordingSessionStore : SessionStore {
        // A flow, not a plain map: waits on it are event-driven rather than
        // polled, so nothing in this file sleeps waiting for an IO hop.
        private val _saved = MutableStateFlow<Map<String, SessionState>>(emptyMap())
        val savedFlow: StateFlow<Map<String, SessionState>> = _saved
        val saved: Map<String, SessionState> get() = _saved.value

        /** Writes attempted, not distinct values stored — see the debounce test. */
        val writeCount = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile var pauseNextGet = false
        @Volatile var failNextGet = false
        lateinit var getEntered: CompletableDeferred<Unit>
        lateinit var releaseGet: CompletableDeferred<Unit>
        @Volatile var pauseNextSave = false
        lateinit var saveEntered: CompletableDeferred<Unit>
        lateinit var releaseSave: CompletableDeferred<Unit>

        fun pauseNextSessionLookup() {
            getEntered = CompletableDeferred()
            releaseGet = CompletableDeferred()
            pauseNextGet = true
        }

        fun pauseNextSessionSave() {
            saveEntered = CompletableDeferred()
            releaseSave = CompletableDeferred()
            pauseNextSave = true
        }

        /** Seed a session as if a previous run had stored it. */
        fun preload(conversationId: String, session: SessionState) {
            _saved.update { it + (conversationId to session) }
        }

        override suspend fun saveSession(conversationId: String, session: SessionState) {
            writeCount.incrementAndGet()
            if (pauseNextSave) {
                pauseNextSave = false
                saveEntered.complete(Unit)
                releaseSave.await()
            }
            _saved.update { it + (conversationId to session) }
        }
        override suspend fun getSession(conversationId: String): SessionState? {
            if (failNextGet) {
                failNextGet = false
                error("test session lookup failure")
            }
            if (pauseNextGet) {
                pauseNextGet = false
                getEntered.complete(Unit)
                releaseGet.await()
            }
            return _saved.value[conversationId]
        }
        override suspend fun deleteSessionByConversation(conversationId: String) {
            _saved.update { it - conversationId }
        }
        override suspend fun deleteOrphanedSessions(): Int = 0
    }

    private class NoopSink : PendingMessageSink {
        override fun enqueue(
            agentAddress: String,
            sessionId: String?,
            prompt: String,
            images: List<String>?,
            files: List<FileAttachment>?
        ) = Unit
        override fun drain(agentAddress: String, sessionId: String?, conversationId: String?, send: (QueuedMessage) -> Boolean) = Unit
        override fun clear(agentAddress: String) = Unit
    }

    private class BlockingDrainSink : PendingMessageSink {
        @Volatile private var queued = false
        val drainEntered = CountDownLatch(1)
        val releaseDrain = CountDownLatch(1)
        val hasQueued: Boolean get() = queued

        override fun enqueue(
            agentAddress: String,
            sessionId: String?,
            prompt: String,
            images: List<String>?,
            files: List<FileAttachment>?
        ) {
            queued = true
        }

        override fun drain(
            agentAddress: String,
            sessionId: String?,
            conversationId: String?,
            send: (QueuedMessage) -> Boolean
        ) {
            if (!queued) return
            queued = false
            drainEntered.countDown()
            releaseDrain.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!send(QueuedMessage("queued", null))) queued = true
        }

        override fun clear(agentAddress: String) {
            queued = false
        }
    }

    private lateinit var factory: FakeWebSocketFactory
    private lateinit var store: RecordingSessionStore
    private lateinit var repository: ConnectionRepositoryImpl
    private lateinit var agentConnection: AgentConnection

    private val agentA = "0xAAA"
    private val agentB = "0xBBB"
    // Sessions are keyed by the local conversation, not the agent — see
    // SessionStateEntity. Each connect names the conversation it is for.
    private val convA = "conv-A"
    private val convB = "conv-B"

    @Before
    fun setUp() {
        val keyManager = KeyManager(ApplicationProvider.getApplicationContext<android.content.Context>())
        keyManager.save(TestIdentity.keys())
        factory = FakeWebSocketFactory()
        store = RecordingSessionStore()
        val sink = NoopSink()

        repository = ConnectionRepositoryImpl(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            sessionStore = store,
            pendingMessageSink = sink,
            agentConnectionFactory = { km, url ->
                AgentConnection(km, url, factory, signer = { _, _ -> "fake-signature" }, pendingMessageSink = sink)
                    .also { agentConnection = it }
            }
        )
    }

    private fun send(frame: String) {
        factory.lastListener?.onMessage(factory.lastWebSocket!!, frame)
    }

    /**
     * Suspend until [flow] carries a value matching [condition].
     *
     * Every wait in this file is on a flow the code under test writes, so the
     * value cannot be missed once we are collecting. The timeout is a
     * deadlock guard, not a budget the assertion depends on — it only fires
     * when the behaviour under test is genuinely broken.
     */
    private suspend fun <T> awaitUntil(what: String, flow: Flow<T>, condition: (T) -> Boolean) {
        val settled = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            flow.first(condition)
            true
        }
        if (settled == null) {
            fail("timed out after ${AWAIT_TIMEOUT_MS}ms waiting for: $what")
        }
    }

    private suspend fun awaitConnected() =
        awaitUntil("connection state to reach Connected", repository.connectionState) {
            it is ConnectionState.Connected
        }

    private suspend fun awaitSockets(n: Int) =
        awaitUntil("socket #$n to open", factory.createCount) { it >= n }

    /**
     * Feed a frame with no side effects beyond [ConnectionRepository.agentProfile]
     * and wait for it to land. The bus preserves order, so anything sent before
     * the marker has been processed by the time this returns — which is how the
     * negative assertions below prove a frame was refused rather than merely
     * still in flight.
     */
    private suspend fun awaitFramesDrained() {
        send("""{"type":"AGENT_PROFILE","name":"marker","model":"test"}""")
        awaitUntil("the marker frame to be processed", repository.agentProfile) { it != null }
    }

    /** A fresh server session: session_id present, no `session` body (len=92 on the wire). */
    private fun connectedIdOnly(sessionId: String) = """{"type":"CONNECTED","session_id":"$sessionId"}"""

    private fun connectedWithBody(sessionId: String, turn: Int) =
        """{"type":"CONNECTED","session_id":"$sessionId","session":{"session_id":"$sessionId","turn":$turn,"messages":[]}}"""

    private fun outputWithBody(sessionId: String, turn: Int) =
        """{"type":"OUTPUT","result":"done","session_id":"$sessionId","session":{"session_id":"$sessionId","turn":$turn,"messages":[]}}"""

    private fun sessionSync(sessionId: String, turn: Int, reply: String) =
        """{"type":"session_sync","session":{"session_id":"$sessionId","turn":$turn,""" +
            """"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"$reply"}]}}"""

    @Test
    fun `connect resumes the named conversation's own session, not the agent's`() = runBlocking {
        // Two conversations with the same agent, each with its own server
        // session. Connecting for one must hand the connection that one's
        // session id — keying this by agent address is what made a new
        // conversation inherit an older one's server-side history.
        store.preload(convA, SessionState(sessionId = "sess-for-A", turn = 4))
        store.preload(convB, SessionState(sessionId = "sess-for-B", turn = 9))

        repository.connect(agentA, convA)

        assertEquals("sess-for-A", repository.getCurrentSession()?.sessionId)

        repository.connect(agentA, convB)

        assertEquals("sess-for-B", repository.getCurrentSession()?.sessionId)
    }

    @Test
    fun `a conversation with nothing stored connects on a session named by its own id`() = runBlocking {
        // The whole point of minting client-side: the conversation id is the
        // session id, so a conversation the server has never seen still
        // CONNECTs on a session of its own instead of waiting to be told one.
        // It also means the id survives losing session_states entirely.
        repository.connect(agentA, convA)

        assertEquals(convA, repository.getCurrentSession()?.sessionId)
    }

    @Test
    fun `a stored session wins over the bare conversation id, keeping its transcript`() = runBlocking {
        store.preload(convA, SessionState(sessionId = convA, turn = 7))

        repository.connect(agentA, convA)

        assertEquals(convA, repository.getCurrentSession()?.sessionId)
        assertEquals(
            "the stored session carries the turn count a bare id cannot",
            7,
            repository.getCurrentSession()?.turn
        )
    }

    @Test
    fun `connect with no conversation at all carries no session`() = runBlocking {
        // The brand-new-conversation path: no session_id on the CONNECT is
        // what makes the server allocate one for this conversation alone.
        store.preload(convA, SessionState(sessionId = "sess-for-A", turn = 4))

        repository.connect(agentA, conversationId = null)

        assertNull(repository.getCurrentSession()?.sessionId)
    }

    @Test
    fun `a server-assigned session we then switch away from cannot be filed under the new conversation`() = runBlocking {
        // The device repro exactly. The first connect had no conversation, so
        // nothing staged an id and the server allocated its own; the switch
        // then asked for conversation B and was answered with that allocated
        // id. Unless a server-assigned id is recorded as one we have been on,
        // it looks like a fresh allocation and gets adopted — which is how
        // B's row ended up naming a session it never requested.
        repository.connect(agentA, conversationId = null)
        send(connectedWithBody("server-allocated", turn = 1))
        awaitConnected()

        factory.frameOnNextCreate = connectedWithBody("server-allocated", turn = 9)
        repository.switchConversation(convB)
        awaitSockets(2)
        awaitFramesDrained()

        assertFalse(
            "conversation B was filed under a session the server never gave it",
            store.saved.containsKey(convB)
        )
    }

    @Test
    fun `a reconnect is held off while the target session is loading`() = runBlocking {
        // A reconnect rung can already be scheduled when the user switches
        // conversations. The Room lookup for B is suspendable, so make the
        // ordering explicit: the rung must not open a socket until
        // switchSession(B) has registered the target.
        repository.connect(agentA, conversationId = null)
        send(connectedWithBody("server-allocated", turn = 1))
        awaitConnected()

        store.pauseNextSessionLookup()
        val oldListener = factory.lastListener!!
        val oldSocket = factory.lastWebSocket!!
        oldListener.onClosed(oldSocket, 1006, "network drop")

        val switchJob = launch { repository.switchConversation(convB) }
        store.getEntered.await()

        // The switch barrier disarms the pending rung, so a foreground retry
        // cannot create a replacement while B's session is still loading.
        repository.retryNow()
        val replacementOpenedDuringLookup = withTimeoutOrNull(1_000L) {
            factory.createCount.first { it >= 2 }
            true
        } ?: false
        assertFalse("no replacement socket may open during the lookup", replacementOpenedDuringLookup)

        factory.frameOnNextCreate = connectedWithBody("server-allocated", turn = 9)
        store.releaseGet.complete(Unit)
        switchJob.join()
        awaitSockets(2)
        awaitFramesDrained()

        assertFalse(
            "conversation B was filed under the session from the old socket",
            store.saved.containsKey(convB)
        )
    }

    @Test
    fun `a frame already in flight on A is dropped after switching to B`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedWithBody(convA, turn = 1))
        awaitConnected()
        awaitUntil("conversation A's initial session to be stored", store.savedFlow) { it[convA]?.turn == 1 }

        val framePassedGenerationCheck = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        agentConnection.beforeHandleMessageForTest = {
            if (pauseOnce.compareAndSet(true, false)) {
                framePassedGenerationCheck.countDown()
                releaseFrame.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }

        val oldListener = factory.lastListener!!
        val oldSocket = factory.lastWebSocket!!
        val frameJob = launch(Dispatchers.Default) {
            oldListener.onMessage(oldSocket, sessionSync("sess-A", turn = 9, reply = "late A"))
        }

        assertTrue(
            "the old frame did not reach the window between the callback check and the lock",
            framePassedGenerationCheck.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )

        val switchJob = launch { repository.switchConversation(convB) }
        switchJob.join()
        awaitSockets(2)
        releaseFrame.countDown()
        frameJob.join()
        awaitFramesDrained()

        val misattributed = withTimeoutOrNull(2_500L) {
            store.savedFlow.first { it[convB]?.turn == 9 }
            true
        } ?: false
        assertFalse("the in-flight frame from A must not persist under B", misattributed)
        assertEquals(1, store.saved[convA]?.turn)
    }

    @Test
    fun `a session update keeps A attribution when the user switches to B`() = runBlocking {
        // Hold the repository on an earlier save. The frame is handled on A,
        // then the switch makes B current before its save is scheduled.
        repository.connect(agentA, conversationId = null)
        agentConnection.setSession(SessionState(sessionId = convA, turn = 0), convA)
        store.pauseNextSessionSave()
        send(connectedWithBody(convA, turn = 1))
        store.saveEntered.await()

        val updateJob = async(start = CoroutineStart.UNDISPATCHED) {
            agentConnection.events.filterIsInstance<ConnectionEvent.SessionUpdated>().first()
        }
        send(sessionSync(convA, turn = 2, reply = "reply from A"))
        assertEquals(convA, updateJob.await().conversationId)

        repository.switchConversation(convB)
        store.releaseSave.complete(Unit)

        val saved = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            store.savedFlow.first { it[convA]?.turn == 2 || it[convB]?.turn == 2 }
        }
        assertEquals("the update must eventually be persisted", 2, saved?.get(convA)?.turn)
        assertFalse("the update from A must not be persisted under B", saved?.containsKey(convB) == true)
    }

    @Test
    fun `an OUTPUT keeps A attribution when B is already current`() = runBlocking {
        repository.connect(agentA, conversationId = null)
        agentConnection.setSession(SessionState(sessionId = convA, turn = 0), convA)
        store.pauseNextSessionSave()
        send(connectedWithBody(convA, turn = 1))
        store.saveEntered.await()

        send(outputWithBody(convA, turn = 2))
        repository.switchConversation(convB)
        store.releaseSave.complete(Unit)

        val saved = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            store.savedFlow.first { it[convA]?.turn == 2 || it[convB]?.turn == 2 }
        }
        assertEquals("OUTPUT must be saved for A", 2, saved?.get(convA)?.turn)
        assertFalse("OUTPUT from A must not be saved under B", saved?.containsKey(convB) == true)
    }

    @Test
    fun `a CONNECTED keeps A attribution when B is already current`() = runBlocking {
        repository.connect(agentA, conversationId = null)
        agentConnection.setSession(SessionState(sessionId = convA, turn = 0), convA)
        store.pauseNextSessionSave()
        send(connectedWithBody(convA, turn = 1))
        store.saveEntered.await()

        send(connectedWithBody(convA, turn = 2))
        repository.switchConversation(convB)
        store.releaseSave.complete(Unit)

        val saved = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            store.savedFlow.first { it[convA]?.turn == 2 || it[convB]?.turn == 2 }
        }
        assertEquals("CONNECTED must be saved for A", 2, saved?.get(convA)?.turn)
        assertFalse("CONNECTED from A must not be saved under B", saved?.containsKey(convB) == true)
    }

    @Test
    fun `a failed switch does not make B current before the socket switches`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedWithBody(convA, turn = 1))
        awaitConnected()
        awaitUntil("conversation A's session to be stored", store.savedFlow) { it[convA]?.turn == 1 }

        store.failNextGet = true
        assertNotNull(runCatching { repository.switchConversation(convB) }.exceptionOrNull())
        awaitSockets(2)

        send(sessionSync(convA, turn = 2, reply = "still A"))
        val saved = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            store.savedFlow.first { it[convA]?.turn == 2 || it[convB]?.turn == 2 }
        }
        assertEquals("A must remain current after a failed switch", 2, saved?.get(convA)?.turn)
        assertFalse("a failed switch must not file A under B", saved?.containsKey(convB) == true)
    }

    @Test
    fun `teardown does not wait for an outbox drain`() = runBlocking {
        val keyManager = KeyManager(ApplicationProvider.getApplicationContext<android.content.Context>())
        keyManager.save(TestIdentity.keys())
        val blockingSink = BlockingDrainSink()
        val connection = AgentConnection(
            keyManager = keyManager,
            relayUrl = "ws://test.invalid",
            webSocketFactory = factory,
            signer = { _, _ -> "fake-signature" },
            pendingMessageSink = blockingSink
        )

        connection.connect(agentA)
        connection.sendMessage("queued", agentA)

        val listener = factory.lastListener!!
        val socket = factory.lastWebSocket!!
        val frameJob = launch(Dispatchers.Default) {
            listener.onMessage(socket, connectedIdOnly("sess-A"))
        }
        assertTrue(
            "the CONNECTED handler did not reach the blocking outbox drain",
            blockingSink.drainEntered.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )

        val teardownJob = launch(Dispatchers.Default) { connection.teardown() }
        val teardownReturned = withTimeoutOrNull(1_000L) {
            teardownJob.join()
            true
        } ?: false

        blockingSink.releaseDrain.countDown()
        frameJob.join()
        teardownJob.join()

        assertTrue("teardown must not wait for Room/outbox work", teardownReturned)
        assertTrue("a message rejected by the retired socket must remain queued", blockingSink.hasQueued)
    }

    @Test
    fun `a CONNECTED naming the previous conversation's session is never saved under the new one`() = runBlocking {
        // Verbatim from the device against wss://oo.openonion.ai: we asked for
        // conversation B's id, the relay answered with A's (it pins one
        // session per socket), and we saved A's id under B — so the row named
        // a session it never requested. Refusing the frame is what keeps the
        // "conversation id is the session id" property true.
        repository.connect(agentA, convA)
        send(connectedWithBody(convA, turn = 1))
        awaitConnected()
        // Positive control: writes really are reaching the store on this path.
        awaitUntil("conversation A's session to be stored", store.savedFlow) { it[convA]?.turn == 1 }

        repository.switchConversation(convB)
        awaitSockets(2)

        // The host ignores the switch and answers with A's session anyway.
        send(connectedWithBody(convA, turn = 9))
        awaitFramesDrained()

        assertFalse(
            "conversation B was given the session it never asked for",
            store.saved.containsKey(convB)
        )
        assertEquals(
            "conversation A's stored session must not be rewritten from a refused frame",
            1,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `switching conversation clears the mode chip and any unconfirmed mode change`() = runBlocking {
        // Mode belongs to the server session, so it becomes unknown again on a
        // switch for the same reason it does on connect. Two changes landed
        // side by side here — per-conversation sessions and the pending-mode
        // rollback — and this is where they meet.
        repository.connect(agentA, convA)
        send(connectedWithBody(convA, turn = 1))
        awaitConnected()
        repository.setMode(ApprovalMode.ACCEPT_EDITS)
        assertEquals(ApprovalMode.ACCEPT_EDITS, repository.approvalMode.value)
        assertEquals(true, repository.modePending.value)

        repository.switchConversation(convB)

        assertEquals(
            "the mode of the conversation we left must not carry over",
            ApprovalMode.DEFAULT,
            repository.approvalMode.value
        )
        assertEquals(
            "a mode_change awaiting confirmation there is never answered here",
            false,
            repository.modePending.value
        )
    }

    // ── plan survives a switch and a restart (W16AAPPLE-17) ─────────
    //
    // Nothing on the wire brings an idle conversation's plan back: the Host
    // puts a session on CONNECTED only when its copy is newer, and replays
    // frames only into a running turn. So the plan has to be kept with the
    // stored session — and, since the Host adopts the session a CONNECT
    // echoes, it has to be on the session the next CONNECT sends.

    private val storedPlan = listOf(PlanEntryWire(content = "Run tests", priority = "high", status = "in_progress"))

    private fun planFrame(content: String) =
        """{"type":"plan","id":"tr-plan","entries":[{"content":"$content","priority":"high","status":"pending"}]}"""

    @Test
    fun `a plan frame is saved with the session and carried on the next CONNECT`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedIdOnly(convA))
        awaitConnected()

        send(planFrame("Run tests"))

        awaitUntil("the plan to be saved with convA's session", store.savedFlow) {
            it[convA]?.plan?.map(PlanEntryWire::content) == listOf("Run tests")
        }
        assertEquals(listOf("Run tests"), repository.getCurrentSession()?.plan?.map { it.content })
    }

    @Test
    fun `a session snapshot without a plan keeps the session's plan`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedIdOnly(convA))
        awaitConnected()
        send(planFrame("Run tests"))

        send(sessionSync(convA, turn = 1, reply = "the reply"))
        awaitUntil("session_sync to persist turn 1", store.savedFlow) { it[convA]?.turn == 1 }

        assertEquals(listOf("Run tests"), store.saved[convA]?.plan?.map { it.content })
        assertEquals(listOf("Run tests"), repository.plan.value.map { it.content })
    }

    @Test
    fun `the stored plan shows on connect, before the Host says anything`() = runBlocking {
        store.preload(convA, SessionState(sessionId = convA, turn = 3, plan = storedPlan))

        repository.connect(agentA, convA)

        assertEquals(listOf("Run tests"), repository.plan.value.map { it.content })
        assertEquals("echoed on CONNECT", storedPlan, repository.getCurrentSession()?.plan)
    }

    @Test
    fun `an idle CONNECTED does not clear the stored plan`() = runBlocking {
        store.preload(convA, SessionState(sessionId = convA, turn = 3, plan = storedPlan))
        repository.connect(agentA, convA)

        send(connectedIdOnly(convA))
        awaitConnected()
        awaitFramesDrained()

        assertEquals(listOf("Run tests"), repository.plan.value.map { it.content })
    }

    @Test
    fun `switching away and back shows each conversation's own plan`() = runBlocking {
        store.preload(convA, SessionState(sessionId = convA, plan = storedPlan))
        repository.connect(agentA, convA)
        send(connectedIdOnly(convA))
        awaitConnected()

        repository.switchConversation(convB)
        assertTrue("convB has no plan of its own", repository.plan.value.isEmpty())

        repository.switchConversation(convA)
        assertEquals(listOf("Run tests"), repository.plan.value.map { it.content })
        assertEquals(storedPlan, repository.getCurrentSession()?.plan)
    }

    @Test
    fun `a CONNECTED frame that carries only session_id still persists the session id`() = runBlocking {
        repository.connect(agentA, convA)
        // Exactly one delivery: the id it carries only ever changes the
        // session once, so a lost first frame can never be made good by a
        // second copy — that is what made this the flakiest test in the file.
        send(connectedIdOnly("sess-new"))
        awaitConnected()
        awaitUntil("the session write to land for agentA", store.savedFlow) { it.containsKey(convA) }

        assertNotNull(
            "CONNECTED carried session_id=sess-new but nothing was persisted, so the next " +
                "connect sends sessionId=null and the server orphans the in-flight reply",
            store.saved[convA]
        )
        assertEquals("sess-new", store.saved[convA]?.sessionId)
    }

    @Test
    fun `a session_sync frame persists the session it carries`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        send(sessionSync("sess-1", turn = 1, reply = "the reply"))
        awaitUntil("session_sync to persist turn 1", store.savedFlow) { it[convA]?.turn == 1 }

        assertEquals(
            "session_sync is the only frame carrying mid-turn state, but the repository " +
                "ignores it — the turn the user is watching is never persisted",
            1,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `a burst of session_sync frames is conflated into far fewer writes`() = runBlocking {
        // Each write re-encodes the whole transcript and rewrites the row, and
        // session_sync streams after every trace entry — so a turn with 20 tool
        // calls paid for 20 of them on the same coroutine that has to read the
        // next frame. The newest session is the only one resume ever reads.
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        val writesBefore = store.writeCount.get()

        repeat(BURST) { turn -> send(sessionSync("sess-1", turn = turn + 1, reply = "reply-$turn")) }

        awaitUntil("the newest session_sync to be persisted", store.savedFlow) { it[convA]?.turn == BURST }
        val writes = store.writeCount.get() - writesBefore
        assertTrue(
            "$BURST session_sync frames caused $writes writes — they are not being conflated",
            writes < BURST
        )
        assertEquals(
            "conflating must still land the newest session, not an intermediate one",
            BURST,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `a conversation switch flushes what the debounce window is still holding`() = runBlocking {
        // The window is only safe because every lifecycle boundary writes
        // inline and supersedes it. Leaving a queued write behind here would
        // lose the transcript of the conversation being left.
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        send(sessionSync("sess-1", turn = 7, reply = "mid-turn"))
        awaitFramesDrained()

        repository.switchConversation(convB)

        assertEquals(
            "the switch returned before the pending session_sync was written, so the " +
                "conversation being left keeps a stale turn count",
            7,
            store.saved[convA]?.turn
        )
    }

    @Test
    fun `switching agents persists the outgoing agent's live session before tearing it down`() = runBlocking {
        repository.connect(agentA, convA)
        send(connectedWithBody("sess-1", turn = 0))
        awaitConnected()
        // The agent starts replying: turn advances, but only via session_sync.
        send(sessionSync("sess-1", turn = 1, reply = "the reply"))
        awaitUntil("session_sync to persist turn 1", store.savedFlow) { it[convA]?.turn == 1 }

        // User navigates to another agent — connect() tears the socket down
        // through AgentConnection.disconnect(), not repository.disconnect().
        // It suspends until the switch is done, so the assert needs no wait.
        repository.connect(agentB, convB)

        assertEquals(
            "agent A's last known session must survive the switch; it is stale at turn=0 " +
                "(or absent), so reconnecting to A resumes the wrong session",
            1,
            store.saved[convA]?.turn
        )
    }

    private companion object {
        /** Deadlock guard for [awaitUntil]; never reached while the code is correct. */
        const val AWAIT_TIMEOUT_MS = 15_000L

        /** session_sync frames in one turn, matching ChatEventReducer's "after each trace entry". */
        const val BURST = 20
    }
}
