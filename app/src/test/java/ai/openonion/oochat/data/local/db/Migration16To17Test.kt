package ai.openonion.oochat.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real v16-to-v17 migration (W16AAPPLE-56): every saved agent keeps the route it
 * has always been dialled on. Until v17 any agent with its own server was dialled
 * direct whatever its mode said, so those rows become "direct".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration16To17Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `agent_profiles` (`id` TEXT NOT NULL, `address` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `server_url` TEXT NOT NULL, `api_key` TEXT, `avatar_url` TEXT, `created_at` INTEGER NOT NULL, `last_connected_at` INTEGER, `is_active` INTEGER NOT NULL, `connection_mode` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun agent(id: String, serverUrl: String, mode: String, address: String = "0x$id") {
        db.execSQL(
            "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode) " +
                "VALUES ('$id', '$address', 'Agent', '$serverUrl', 1000, 1, '$mode')"
        )
    }

    private fun modeOf(id: String): String =
        db.query("SELECT connection_mode FROM agent_profiles WHERE id = '$id'").use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    @Test
    fun `an agent on its own server saved under the relay default becomes direct`() {
        agent("own", "https://my-agent.example.com", "relay")

        AppDatabase.MIGRATION_16_17.migrate(db)

        assertEquals("direct", modeOf("own"))
    }

    @Test
    fun `agents on the default relay keep relay mode, in any of its spellings`() {
        agent("a", "https://oo.openonion.ai", "relay")
        agent("b", "https://oo.openonion.ai/", "relay")
        agent("c", "wss://oo.openonion.ai", "relay")
        agent("d", "http://oo.openonion.ai", "relay")
        agent("e", "ws://oo.openonion.ai", "relay")

        AppDatabase.MIGRATION_16_17.migrate(db)

        listOf("a", "b", "c", "d", "e").forEach { assertEquals(it, "relay", modeOf(it)) }
    }

    @Test
    fun `an agent already saved as direct is left alone`() {
        agent("d", "https://my-agent.example.com", "direct")

        AppDatabase.MIGRATION_16_17.migrate(db)

        assertEquals("direct", modeOf("d"))
    }

    @Test
    fun `an agent discovery found on a self-hosted relay stays on that relay`() {
        // Discovery saves the agent's address as the row id (in any letter case); everything else uses a UUID.
        agent("0xABC", "https://relay.example.com", "relay", address = "0xabc")

        AppDatabase.MIGRATION_16_17.migrate(db)

        assertEquals("relay", modeOf("0xABC"))
    }
}
