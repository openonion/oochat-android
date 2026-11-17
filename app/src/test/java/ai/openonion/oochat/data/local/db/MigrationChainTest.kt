package ai.openonion.oochat.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every upgrade from the oldest exported schema (v5) to the current one, built from `app/schemas` ([SavedSchema]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationChainTest {

    private val dbName = "migration-chain-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun openThroughRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `the saved schemas run from v5 to the current version with none missing`() {
        val db = openThroughRoom()
        val current = db.openHelper.readableDatabase.version
        db.close()
        assertEquals((5..current).toList(), SavedSchema.versions)
    }

    @Test
    fun `there is one migration for every step from v5 on`() {
        val steps = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        val expected = SavedSchema.versions.zipWithNext()
        assertEquals(expected, steps)
    }

    @Test
    fun `each migration produces exactly the next exported schema`() {
        for ((from, to) in SavedSchema.versions.zipWithNext()) {
            SavedSchema.of(from).create(context, dbName)
            val migration = AppDatabase.ALL_MIGRATIONS.single { it.startVersion == from }
            val next = SavedSchema.of(to)

            val (tables, actual) = openAt(to) { db, oldVersion ->
                assertEquals(from, oldVersion)
                migration.migrate(db)
            }.let { db ->
                try {
                    db.tableNames() to next.tables.associateWith { db.columnsOf(it) }
                } finally {
                    db.close()
                }
            }

            assertEquals("tables after $from -> $to", next.tables, tables)
            next.tables.forEach { table ->
                assertEquals("columns of $table after $from -> $to", next.columns(table), actual.getValue(table))
            }
            context.getDatabasePath(dbName).delete()
        }
    }

    @Test
    fun `every saved version opens through Room at the current version`() {
        // Room throws on a schema mismatch, so opening is the assertion.
        for (version in SavedSchema.versions.dropLast(1)) {
            SavedSchema.of(version).create(context, dbName)
            val db = openThroughRoom()
            try {
                db.openHelper.writableDatabase
            } catch (e: Exception) {
                throw AssertionError("an install at v$version cannot be upgraded: ${e.message}", e)
            } finally {
                db.close()
            }
            context.getDatabasePath(dbName).delete()
        }
    }

    @Test
    fun `a v5 install keeps its agent, conversations and transcript all the way to the current version`() = runTest {
        SavedSchema.of(5).create(context, dbName) { db ->
            db.execSQL(
                "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode, position) " +
                    "VALUES ('agent-1', '0xabc', 'Log reader', 'https://oo.openonion.ai', 1000, 1, 'relay', 0)"
            )
            db.execSQL(
                "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count, last_message_preview) " +
                    "VALUES ('conv-1', 'agent-1', 'Logs', 1000, 2000, 2, 'Three warnings')"
            )
            db.execSQL(
                "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count) " +
                    "VALUES ('conv-2', 'agent-1', 'Deploy', 3000, 3000, 1)"
            )
            db.execSQL(
                "INSERT INTO chat_messages (id, session_id, role, content, timestamp, images) " +
                    "VALUES ('m1', 'conv-1', 'USER', 'What is in the logs?', 1000, '[\"file:///photo.jpg\"]')"
            )
            db.execSQL(
                "INSERT INTO chat_messages (id, session_id, role, content, timestamp, model, duration_ms) " +
                    "VALUES ('m2', 'conv-1', 'ASSISTANT', 'Three warnings', 2000, 'gemini-2.5-flash', 2400)"
            )
            db.execSQL(
                "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                    "VALUES ('m3', 'conv-2', 'USER', 'Ship it', 3000)"
            )
            // Agent-keyed; MIGRATION_10_11 drops it.
            db.execSQL(
                "INSERT INTO session_states (agent_address, session_id, turn, last_updated) " +
                    "VALUES ('0xabc', 'server-session', 3, 2000)"
            )
        }

        val db = openThroughRoom()

        val agent = db.agentDao().getAgentByAddress("0xabc")
        assertNotNull("the saved agent was lost", agent)
        assertEquals("Log reader", agent!!.name)
        assertEquals("https://oo.openonion.ai", agent.serverUrl)

        val logs = db.sessionDao().getSessionById("conv-1")
        assertEquals("Logs", logs?.title)
        assertEquals(2, logs?.messageCount)
        assertEquals("Three warnings", logs?.lastMessagePreview)
        assertEquals("Deploy", db.sessionDao().getSessionById("conv-2")?.title)

        val transcript = db.messageDao().getMessagesListBySession("conv-1").sortedBy { it.timestamp }
        assertEquals(listOf("What is in the logs?", "Three warnings"), transcript.map { it.content })
        assertEquals("[\"file:///photo.jpg\"]", transcript[0].images)
        assertEquals("gemini-2.5-flash", transcript[1].model)
        assertEquals(2400L, transcript[1].durationMs)
        transcript.forEach {
            assertNull(it.files)
            assertNull(it.voicePath)
            assertNull(it.itemType)
            assertNull(it.payload)
            assertNull("a v5 message must not reload as failed", it.deliveryState)
        }
        assertEquals(listOf("Ship it"), db.messageDao().getMessagesListBySession("conv-2").map { it.content })

        assertNull(db.sessionStateDao().getSession("conv-1"))
        assertTrue(db.pendingMessageDao().allIds().isEmpty())
        db.close()
    }

    @Test
    fun `the upgraded database takes new writes in every table`() = runTest {
        SavedSchema.of(5).create(context, dbName) { db ->
            db.execSQL(
                "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode, position) " +
                    "VALUES ('agent-1', '0xabc', 'Agent', 'https://oo.openonion.ai', 1000, 1, 'relay', 0)"
            )
            db.execSQL(
                "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count) " +
                    "VALUES ('conv-1', 'agent-1', 'Chat', 1000, 1000, 0)"
            )
        }
        val db = openThroughRoom()

        db.messageDao().insertMessage(
            ai.openonion.oochat.data.local.db.entity.ChatMessageEntity(
                id = "new", sessionId = "conv-1", role = "USER", content = "after the upgrade", timestamp = 5000
            )
        )
        db.sessionStateDao().saveSession(
            ai.openonion.oochat.data.local.db.entity.SessionStateEntity(conversationId = "conv-1", sessionId = "sid", turn = 1)
        )
        db.pendingMessageDao().insert(
            ai.openonion.oochat.data.local.db.entity.PendingMessageEntity(
                agentAddress = "0xabc", prompt = "queued", imagesJson = null, sessionId = "conv-1", createdAt = 5000
            )
        )

        assertEquals("after the upgrade", db.messageDao().getMessagesListBySession("conv-1").single().content)
        assertEquals("sid", db.sessionStateDao().getSession("conv-1")?.sessionId)
        assertEquals(1, db.pendingMessageDao().countForAgent("0xabc"))
        db.close()
    }

    private fun openAt(version: Int, upgrade: (SupportSQLiteDatabase, Int) -> Unit): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("the old database should already exist")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        upgrade(db, oldVersion)
                })
                .build()
        )
        return helper.writableDatabase
    }
}
