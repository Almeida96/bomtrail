package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.node.ObjectNode
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Immutable input snapshots. Read commands never initialize or migrate a database. */
class Store(path: Path, private val writable: Boolean) : AutoCloseable {
    private val connection: Connection

    init {
        require(writable || Files.isRegularFile(path)) { "Snapshot database does not exist" }
        require(path.toAbsolutePath().parent?.let(Files::isDirectory) == true) { "Database parent directory does not exist" }
        val config = SQLiteConfig().apply { setReadOnly(!writable); setBusyTimeout(5000) }
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties())
        try {
            execute("PRAGMA trusted_schema=OFF")
            val app = pragma("application_id")
            val version = pragma("user_version")
            if (app == 0 && version == 0 && writable) {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM sqlite_master").use {
                        require(it.next() && it.getInt(1) == 0) { "Refusing to initialize a nonempty foreign database" }
                    }
                }
                connection.autoCommit = false
                execute("CREATE TABLE snapshots (id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL, project TEXT NOT NULL, sha TEXT NOT NULL, raw BLOB NOT NULL CHECK(length(raw)<=16777216))")
                execute("PRAGMA application_id=$APPLICATION_ID")
                execute("PRAGMA user_version=1")
                connection.commit()
                connection.autoCommit = true
            } else require(app == APPLICATION_ID && version == 1) { "Unsupported BOMTrail database format" }
        } catch (failure: Exception) {
            connection.close()
            throw failure
        }
    }

    fun add(name: String, project: String, raw: ByteArray): Snapshot {
        require(writable) { "Snapshot database is read-only" }
        label(name); label(project)
        require(!name.matches(Regex("[a-f0-9]{64}"))) { "Snapshot names cannot look like SHA-256 IDs" }
        val bom = BomParser.parse(raw)
        val id = id(name, project, bom.sourceSha256)
        connection.prepareStatement("SELECT id FROM snapshots WHERE name=?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows ->
                if (rows.next()) {
                    require(rows.getString(1) == id) { "Snapshot name already exists with different content or project; choose a new name" }
                    return get(id)
                }
            }
        }
        connection.prepareStatement("INSERT INTO snapshots(id,name,project,sha,raw) VALUES(?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, project)
            it.setString(4, bom.sourceSha256); it.setBytes(5, raw); it.executeUpdate()
        }
        return Snapshot(id, name, project, bom)
    }

    fun get(idOrName: String): Snapshot {
        label(idOrName)
        val key = if (idOrName.matches(Regex("[a-f0-9]{64}"))) "id" else "name"
        connection.prepareStatement("SELECT id,name,project,sha,length(raw) AS bytes,raw FROM snapshots WHERE $key=?").use { query ->
            query.setString(1, idOrName)
            query.executeQuery().use { row ->
                require(row.next()) { "Snapshot not found" }
                validate(row)
                val raw = row.getBytes("raw")
                require(raw != null && Json.hash(raw) == row.getString("sha")) { "Stored snapshot checksum mismatch" }
                return Snapshot(row.getString("id"), row.getString("name"), row.getString("project"), BomParser.parse(raw))
            }
        }
    }

    fun list(offset: Int = 0, limit: Int = 20): ObjectNode {
        require(offset in 0..1_000_000 && limit in 1..50) { "Invalid page bounds" }
        val result = Json.mapper.createObjectNode().put("kind", "snapshots").put("offset", offset).put("limit", limit)
        val entries = result.putArray("snapshots")
        connection.prepareStatement("SELECT id,name,project,sha,length(raw) AS bytes FROM snapshots ORDER BY name LIMIT ? OFFSET ?").use { query ->
            query.setInt(1, limit + 1); query.setInt(2, offset)
            query.executeQuery().use { rows ->
                var count = 0
                while (rows.next()) {
                    if (count == limit) { result.put("nextOffset", offset + limit); break }
                    validate(rows)
                    entries.addObject().put("id", rows.getString("id")).put("name", rows.getString("name"))
                        .put("project", rows.getString("project")).put("sourceSha256", rows.getString("sha"))
                        .put("bytes", rows.getLong("bytes"))
                    count++
                }
            }
        }
        return result
    }

    private fun validate(row: ResultSet) {
        val name = row.getString("name") ?: error("Stored snapshot name is missing")
        val project = row.getString("project") ?: error("Stored project is missing")
        val sha = row.getString("sha") ?: error("Stored checksum is missing")
        label(name); label(project)
        require(sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid stored checksum" }
        require(!name.matches(Regex("[a-f0-9]{64}")) && row.getString("id") == id(name, project, sha)) { "Stored snapshot metadata mismatch" }
        require(row.getLong("bytes") in 1..Limits.MAX_INPUT_BYTES.toLong()) { "Invalid stored snapshot size" }
    }

    private fun execute(sql: String) = connection.createStatement().use { it.execute(sql); Unit }
    private fun pragma(name: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA $name").use { it.next(); it.getInt(1) }
    }
    override fun close() = connection.close()

    companion object {
        private const val APPLICATION_ID = 0x424F4D54
        fun label(value: String) {
            require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)) { "Labels must contain 1..128 characters without control characters" }
            Json.read(Json.text(value))
        }
        private fun id(name: String, project: String, sha: String) = Json.hash(Json.text(listOf(name, project, sha)))
    }
}
