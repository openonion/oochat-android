package ai.openonion.oochat.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** A schema Room exported to `app/schemas/`, used to build a real old database, identity hash included. */
class SavedSchema private constructor(val version: Int, private val database: JsonObject) {

    val tables: Set<String>
        get() = database["entities"]!!.jsonArray.map { it.jsonObject.string("tableName") }.toSet()

    /** Each table's columns as `name type notnull`, as [columnsOf] reads them. */
    fun columns(table: String): Set<String> =
        entity(table)["fields"]!!.jsonArray.map { it.jsonObject }.map { field ->
            // Room leaves notNull out of the file when it is false.
            val notNull = field["notNull"]?.jsonPrimitive?.content?.toBoolean() == true
            "${field.string("columnName")} ${field.string("affinity")} ${if (notNull) "NOT NULL" else "NULL"}"
        }.toSet()

    /** Creates [name] at this version, then lets [seed] insert rows. */
    fun create(context: Context, name: String, seed: (SupportSQLiteDatabase) -> Unit = {}) {
        context.getDatabasePath(name).delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements().forEach(db::execSQL)
                        seed(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("$name already existed at v$oldVersion; delete it before creating v$newVersion")
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun statements(): List<String> {
        val tables = database["entities"]!!.jsonArray.map { it.jsonObject }.flatMap { entity ->
            val table = entity.string("tableName")
            val indices = entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject.string("createSql") }
            (listOf(entity.string("createSql")) + indices).map { it.replace("\${TABLE_NAME}", table) }
        }
        // room_master_table with the identity hash Room checks on open.
        val setup = database["setupQueries"]!!.jsonArray.map { it.jsonPrimitive.content }
        return tables + setup
    }

    private fun entity(table: String): JsonObject =
        database["entities"]!!.jsonArray.map { it.jsonObject }.single { it.string("tableName") == table }

    companion object {
        private val directory: Path = sequenceOf(
            Path.of("schemas", AppDatabase::class.java.name),
            Path.of("app", "schemas", AppDatabase::class.java.name)
        ).firstOrNull(Files::isDirectory)
            ?: error("Could not find app/schemas from the test working directory")

        /** Every version with an exported schema, oldest first. */
        val versions: List<Int> by lazy {
            Files.list(directory).use { files ->
                files.map { it.fileName.toString() }
                    .filter { it.endsWith(".json") }
                    .map { it.removeSuffix(".json").toInt() }
                    .sorted()
                    .toList()
            }
        }

        fun of(version: Int): SavedSchema {
            val json = Json.parseToJsonElement(String(Files.readAllBytes(directory.resolve("$version.json")), Charsets.UTF_8)).jsonObject
            val database = json["database"]!!.jsonObject
            check(database["version"]!!.jsonPrimitive.int == version) { "$version.json describes another version" }
            return SavedSchema(version, database)
        }

        private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
    }
}

/** The app's own tables, leaving out SQLite's, Android's and Room's bookkeeping ones. */
fun SupportSQLiteDatabase.tableNames(): Set<String> =
    query(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')"
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

/** [table]'s columns from `PRAGMA table_info`, in the form [SavedSchema.columns] uses. */
fun SupportSQLiteDatabase.columnsOf(table: String): Set<String> =
    query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        val type = c.getColumnIndexOrThrow("type")
        val notNull = c.getColumnIndexOrThrow("notnull")
        generateSequence {
            if (c.moveToNext()) {
                "${c.getString(name)} ${c.getString(type)} ${if (c.getInt(notNull) == 1) "NOT NULL" else "NULL"}"
            } else {
                null
            }
        }.toSet()
    }
