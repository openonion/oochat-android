package ai.openonion.oochat.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AppDatabase.MIGRATION_9_10] run alone. Opening through Room would not show
 * it: [AppDatabase.MIGRATION_10_11] drops the column again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration9To10Test {

    private val dbName = "migration-9-10-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun seedV9() = SavedSchema.of(9).create(context, dbName) { db ->
        db.execSQL(
            "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode) " +
                "VALUES ('agent-1', '0xabc', 'Agent', 'https://oo.openonion.ai', 1000, 1, 'RELAY')"
        )
        db.execSQL(
            "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count, last_message_preview) " +
                "VALUES ('conv-1', 'agent-1', 'Logs', 1000, 2000, 2, 'Nothing unusual')"
        )
        db.execSQL(
            "INSERT INTO chat_messages (id, session_id, role, content, timestamp, files) " +
                "VALUES ('m1', 'conv-1', 'USER', 'What is in the logs?', 1000, '[{\"name\":\"app.log\"}]')"
        )
        db.execSQL(
            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                "VALUES ('m2', 'conv-1', 'ASSISTANT', 'Nothing unusual', 2000)"
        )
        db.execSQL(
            "INSERT INTO pending_messages (agent_address, prompt, files_json, created_at) " +
                "VALUES ('0xabc', 'queued before the upgrade', NULL, 1500)"
        )
    }

    private fun <T> migratedTo10(read: (SupportSQLiteDatabase) -> T): T {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("the v9 database should already exist")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(9, oldVersion)
                        AppDatabase.MIGRATION_9_10.migrate(db)
                    }
                })
                .build()
        )
        return try {
            read(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    @Test
    fun `the migrated tables are the ones Room exported for v10`() {
        seedV9()
        val v10 = SavedSchema.of(10)

        val actual = migratedTo10 { db -> v10.tables.associateWith { db.columnsOf(it) } }

        v10.tables.forEach { table ->
            assertEquals("columns of $table after 9 -> 10", v10.columns(table), actual.getValue(table))
        }
    }

    @Test
    fun `an existing conversation starts at offset zero with everything else kept`() {
        seedV9()

        val row = migratedTo10 { db ->
            db.query(
                "SELECT title, created_at, updated_at, message_count, last_message_preview, server_message_offset " +
                    "FROM chat_sessions WHERE id = 'conv-1'"
            ).use { c ->
                c.moveToFirst()
                listOf(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4), c.getInt(5))
            }
        }

        assertEquals(listOf<Any>("Logs", 1000L, 2000L, 2, "Nothing unusual", 0), row)
    }

    @Test
    fun `the transcript and the outbox are not touched`() {
        seedV9()

        val (messages, queued) = migratedTo10 { db ->
            val messages = db.query("SELECT id, content, files FROM chat_messages ORDER BY timestamp").use { c ->
                generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null }.toList()
            }
            val queued = db.query("SELECT prompt FROM pending_messages").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
            messages to queued
        }

        assertEquals(
            listOf(
                Triple("m1", "What is in the logs?", "[{\"name\":\"app.log\"}]"),
                Triple("m2", "Nothing unusual", null)
            ),
            messages
        )
        assertEquals(listOf("queued before the upgrade"), queued)
    }
}
