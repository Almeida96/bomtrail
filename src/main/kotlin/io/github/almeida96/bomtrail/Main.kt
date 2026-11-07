package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

fun main(args: Array<String>) {
    val code = Cli.run(args, System.`in`, System.out, System.err)
    if (code != 0) kotlin.system.exitProcess(code)
}

object Cli {
    fun run(args: Array<String>, input: InputStream, out: PrintStream, err: PrintStream): Int = try {
        execute(args, input, out)
    } catch (failure: Exception) {
        if (failure !is java.io.IOException && failure !is java.sql.SQLException &&
            failure !is IllegalArgumentException && failure !is IllegalStateException) throw failure
        err.println("bomtrail: ${failure.message}")
        1
    }

    private fun execute(args: Array<String>, input: InputStream, out: PrintStream): Int {
        if (args.isEmpty() || (args.size == 1 && args[0] in setOf("--help", "help"))) { out.print(HELP); return 0 }
        if (args.contentEquals(arrayOf("--version"))) { out.println("bomtrail ${Limits.VERSION}"); return 0 }
        val reportOptions = setOf("format", "out")
        val snapshotOptions = setOf("db", "snapshot") + reportOptions
        val allowed = when (args[0]) {
            "import" -> setOf("db", "file", "name", "project")
            "list" -> setOf("db", "offset", "limit") + reportOptions
            "summary" -> snapshotOptions
            "components" -> snapshotOptions + setOf("query", "offset", "limit")
            "inspect" -> snapshotOptions + "ref"
            "path" -> snapshotOptions + setOf("from", "to", "max-depth")
            "impact" -> snapshotOptions + setOf("ref", "offset", "limit")
            "compare" -> setOf("db", "baseline", "candidate") + reportOptions
            "mcp" -> setOf("db")
            "demo" -> setOf("dir")
            else -> error("Unknown command: ${args[0]}")
        }
        if (args.size == 2 && args[1] == "--help") { out.print(HELP); return 0 }
        val flags = parse(args, allowed)
        val format = flags["format"] ?: "json"
        require(format in setOf("json", "md", "html")) { "Format must be json, md or html" }
        val output = flags["out"]?.let(Path::of)
        output?.let(::checkNew)
        if (args[0] == "demo") { demo(Path.of(required(flags, "dir")), out); return 0 }
        val db = Path.of(required(flags, "db"))
        if (args[0] == "import") {
            val name = required(flags, "name"); val project = required(flags, "project")
            Store.label(name); Store.label(project)
            require(!name.matches(Regex("[a-f0-9]{64}"))) { "Snapshot names cannot look like SHA-256 IDs" }
            val bytes = Files.newInputStream(Path.of(required(flags, "file"))).use { it.readNBytes(Limits.MAX_INPUT_BYTES + 1) }
            BomParser.parse(bytes) // A bad input must not create an empty database.
            Store(db, true).use { store ->
                val snapshot = store.add(name, project, bytes)
                out.print(Json.pretty(context(Graph.summary(snapshot.bom), snapshot)))
            }
            return 0
        }
        Store(db, false).use { store ->
            if (args[0] == "mcp") { McpServer(store).run(input, out); return 0 }
            val result = when (args[0]) {
                "list" -> store.list(integer(flags, "offset", 0, 0..1_000_000), integer(flags, "limit", 20, 1..50))
                "compare" -> Comparison.compare(store.get(required(flags, "baseline")), store.get(required(flags, "candidate")))
                else -> {
                    val snapshot = store.get(required(flags, "snapshot"))
                    val bom = snapshot.bom
                    val report = when (args[0]) {
                        "summary" -> Graph.summary(bom)
                        "components" -> Graph.inventory(bom, flags["query"] ?: "", integer(flags, "offset", 0, 0..1_000_000), integer(flags, "limit", 25, 1..50))
                        "inspect" -> Graph.inspect(bom, required(flags, "ref"))
                        "path" -> Graph.path(bom, flags["from"] ?: requireNotNull(bom.subjectRef) { "No metadata subject; provide --from" }, required(flags, "to"), integer(flags, "max-depth", 64, 1..128))
                        "impact" -> Graph.impact(bom, required(flags, "ref"), integer(flags, "offset", 0, 0..1_000_000), integer(flags, "limit", 25, 1..50))
                        else -> error("Unknown command")
                    }
                    context(report, snapshot)
                }
            }
            emit(output, Reports.render(result, format), out)
            return when {
                args[0] != "compare" -> 0
                result.path("status").asText() == "CHANGED" -> 2
                result.path("status").asText() == "INCONCLUSIVE" -> 3
                else -> 0
            }
        }
    }

    private fun context(result: ObjectNode, snapshot: Snapshot): ObjectNode {
        result.putObject("snapshot").put("id", snapshot.id).put("name", snapshot.name).put("project", snapshot.project)
        return result
    }
    private fun parse(args: Array<String>, allowed: Set<String>): Map<String, String> {
        val flags = linkedMapOf<String, String>()
        require(args.size % 2 == 1) { "Each option requires a value" }
        for (i in 1 until args.size step 2) {
            val key = args[i].removePrefix("--")
            require(args[i].startsWith("--") && key in allowed) { "Unknown option: ${args[i]}" }
            require(!args[i + 1].startsWith("--")) { "Missing value for ${args[i]}" }
            require(flags.putIfAbsent(key, args[i + 1]) == null) { "Repeated option: ${args[i]}" }
        }
        return flags
    }
    private fun required(flags: Map<String, String>, key: String): String = flags[key]?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing --$key")
    private fun integer(flags: Map<String, String>, key: String, default: Int, range: IntRange): Int {
        val raw = flags[key] ?: return default
        val value = raw.toIntOrNull()
        require(value != null && value in range) { "--$key must be an integer from ${range.first} to ${range.last}" }
        return value
    }
    private fun checkNew(path: Path) {
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Output already exists; choose a new path" }
        require(path.toAbsolutePath().parent?.let(Files::isDirectory) == true) { "Output parent directory does not exist" }
    }
    private fun emit(path: Path?, value: String, out: PrintStream) {
        if (path == null) out.print(value)
        else Files.writeString(path, value, StandardOpenOption.CREATE_NEW)
    }
    private fun demo(directory: Path, out: PrintStream) {
        Files.createDirectory(directory)
        val snapshots = mutableListOf<Snapshot>()
        Store(directory.resolve("boms.db"), true).use { store ->
            for (name in listOf("baseline", "candidate")) {
                val bytes = requireNotNull(Cli::class.java.getResourceAsStream("/$name.json")) { "Missing bundled fixture" }.use(InputStream::readAllBytes)
                Files.write(directory.resolve("$name.json"), bytes, StandardOpenOption.CREATE_NEW)
                snapshots.add(store.add(name, "shop-app", bytes))
            }
            val comparison = Comparison.compare(snapshots[0], snapshots[1])
            check(comparison.path("status").asText() == "CHANGED") { "Demo expected changed dependency evidence" }
            for (format in listOf("json", "md", "html")) emit(directory.resolve("comparison.$format"), Reports.render(comparison, format), out)
            emit(directory.resolve("path.json"), Json.pretty(Graph.path(snapshots[0].bom, "app", "codec")), out)
            emit(directory.resolve("impact.json"), Json.pretty(Graph.impact(snapshots[0].bom, "codec")), out)
            out.print(Json.pretty(Json.mapper.createObjectNode().put("status", "completed").put("data", "synthetic-shop-sbom")
                .put("snapshots", 2).put("comparison", "CHANGED").put("report", directory.resolve("comparison.html").toString())))
        }
    }
    private val HELP = """
        BOMTrail 0.1.0 — investigate declared software dependencies

        bomtrail import --db FILE --file SBOM.json --name NAME --project PROJECT
        bomtrail list --db FILE [--offset 0] [--limit 20]
        bomtrail summary --db FILE --snapshot NAME
        bomtrail components --db FILE --snapshot NAME [--query TEXT] [--offset 0] [--limit 25]
        bomtrail inspect --db FILE --snapshot NAME --ref BOM-REF
        bomtrail path --db FILE --snapshot NAME --to BOM-REF [--from BOM-REF] [--max-depth 64]
        bomtrail impact --db FILE --snapshot NAME --ref BOM-REF [--offset 0] [--limit 25]
        bomtrail compare --db FILE --baseline NAME --candidate NAME
        bomtrail mcp --db FILE
        bomtrail demo --dir NEW-DIRECTORY

        Read commands support --format json|md|html and --out NEW-FILE (except mcp).
        Java 21+. CycloneDX JSON 1.5, 1.6, 1.7 projected inventory/dependency support.
        Offline analysis of declared dependencies; no project execution, package downloads or CVE lookup.
        MCP opens an existing database read-only. Input bytes remain in the local database.
        Compare exits: 0 UNCHANGED, 1 invalid input/failure, 2 CHANGED, 3 INCONCLUSIVE.
        All other commands exit 0 on success, 1 on failure. Demo succeeds with a CHANGED example.
        An observed path is not proof of runtime use or exploitability. Missing graph data is unknown.
    """.trimIndent() + "\n"
}
