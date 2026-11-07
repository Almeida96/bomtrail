package io.github.almeida96.bomtrail

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class StoreTest {
    @TempDir lateinit var temp: Path
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/baseline.json")).use { it.readAllBytes() }

    @Test fun snapshotsAreImmutableAndIdenticalImportsAreIdempotent() {
        val file = temp.resolve("boms.db")
        Store(file, true).use { store ->
            val first = store.add("release-a", "shop", fixture())
            assertEquals(first.id, store.add("release-a", "shop", fixture()).id)
            assertEquals(first, store.get("release-a"))
            assertEquals(first, store.get(first.id))
            assertFails { store.add("release-a", "another-project", fixture()) }
            assertFails { store.add("release-a", "shop", fixture() + ' '.code.toByte()) }
            assertEquals(1, store.list().path("snapshots").size())
        }
    }

    @Test fun readOperationsLeaveTheDatabaseBytesUnchanged() {
        val file = temp.resolve("boms.db")
        Store(file, true).use { it.add("release-a", "shop", fixture()) }
        val before = Files.readAllBytes(file)
        Store(file, false).use {
            assertEquals("release-a", it.get("release-a").name)
            assertEquals(1, it.list().path("snapshots").size())
            assertFails { it.add("release-b", "shop", fixture()) }
        }
        assertContentEquals(before, Files.readAllBytes(file))
    }

    @Test fun missingReadDatabaseIsNotCreatedAndForeignDataIsPreserved() {
        val missing = temp.resolve("missing.db")
        assertFails { Store(missing, false).close() }; assertFalse(Files.exists(missing))
        val foreign = temp.resolve("foreign.db")
        DriverManager.getConnection("jdbc:sqlite:$foreign").use { c -> c.createStatement().use { it.execute("CREATE TABLE important_data(value TEXT)") } }
        val bytes = Files.readAllBytes(foreign)
        assertFails { Store(foreign, true).close() }
        assertContentEquals(bytes, Files.readAllBytes(foreign))
    }

    @Test fun tamperedSourceAndMetadataAreRejected() {
        val file = temp.resolve("boms.db")
        Store(file, true).use { it.add("release-a", "shop", fixture()) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { it.execute("UPDATE snapshots SET raw=CAST('{}' AS BLOB)") } }
        Store(file, false).use { assertFails { it.get("release-a") } }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { it.execute("UPDATE snapshots SET project='changed'") } }
        Store(file, false).use { assertFails { it.list() } }
    }

    @Test fun paginationIsStableAndLabelsCannotCollideWithIds() {
        Store(temp.resolve("boms.db"), true).use { store ->
            for (name in listOf("c", "a", "b")) store.add(name, "shop", fixture())
            val first = store.list(0, 2)
            assertEquals(listOf("a", "b"), first.path("snapshots").map { it.path("name").asText() })
            assertEquals(2, first.path("nextOffset").asInt())
            assertFalse(store.list(2, 2).has("nextOffset"))
            assertFails { store.list(-1, 2) }; assertFails { store.list(0, 51) }
            assertFails { store.add("a".repeat(64), "shop", fixture()) }
            assertFails { store.add("bad\nname", "shop", fixture()) }
        }
    }
}
