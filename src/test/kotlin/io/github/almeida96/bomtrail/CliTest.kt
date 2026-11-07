package io.github.almeida96.bomtrail

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CliTest {
    @TempDir lateinit var temp: Path
    data class Result(val code: Int, val output: String, val error: String)
    private fun run(vararg args: String): Result {
        val output = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), ByteArrayInputStream(byteArrayOf()), PrintStream(output, true, Charsets.UTF_8), PrintStream(error, true, Charsets.UTF_8))
        return Result(code, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }
    @Test fun demoProvidesQueryableEvidenceAndChangedExitCode() {
        val dir = temp.resolve("demo")
        val demo = run("demo", "--dir", dir.toString())
        assertEquals(0, demo.code, demo.error)
        assertEquals("CHANGED", Json.read(demo.output).path("comparison").asText())
        val db = dir.resolve("boms.db").toString()
        assertEquals(0, run("summary", "--db", db, "--snapshot", "baseline").code)
        assertEquals(0, run("path", "--db", db, "--snapshot", "baseline", "--to", "codec").code)
        assertEquals(0, run("impact", "--db", db, "--snapshot", "baseline", "--ref", "codec").code)
        assertEquals(2, run("compare", "--db", db, "--baseline", "baseline", "--candidate", "candidate").code)
        assertEquals(0, run("compare", "--db", db, "--baseline", "baseline", "--candidate", "baseline").code)
        val before = Files.readAllBytes(dir.resolve("boms.db"))
        assertEquals(1, run("demo", "--dir", dir.toString()).code)
        assertContentEquals(before, Files.readAllBytes(dir.resolve("boms.db")))
    }
    @Test fun rejectsBadOptionsAndNeverOverwritesEvidence() {
        assertEquals(0, run("--help").code); assertEquals(0, run("--version").code)
        assertEquals(1, run("list", "--db").code)
        assertEquals(1, run("list", "--db", "a", "--db", "b").code)
        assertEquals(1, run("list", "--unknown", "x").code)
        val existing = temp.resolve("report.json"); Files.writeString(existing, "keep")
        val response = run("summary", "--db", "absent", "--snapshot", "x", "--out", existing.toString())
        assertEquals(1, response.code); assertTrue(response.error.contains("already exists"))
        assertEquals("keep", Files.readString(existing))
    }
    @Test fun invalidImportDoesNotCreateDatabase() {
        val input = temp.resolve("invalid.json"); Files.writeString(input, "{}")
        val database = temp.resolve("boms.db")
        assertEquals(1, run("import", "--db", database.toString(), "--file", input.toString(), "--name", "bad", "--project", "shop").code)
        assertFalse(Files.exists(database))
        val valid = requireNotNull(javaClass.getResourceAsStream("/baseline.json")).use { it.readAllBytes() }
        Files.write(input, valid)
        assertEquals(1, run("import", "--db", database.toString(), "--file", input.toString(), "--name", "a".repeat(64), "--project", "shop").code)
        assertFalse(Files.exists(database))
    }
}
