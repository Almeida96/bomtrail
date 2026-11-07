package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class McpServerTest {
    @TempDir lateinit var temp: Path

    private fun database(): Path {
        val path = temp.resolve("boms.db")
        Store(path, true).use { it.add("baseline", "shop-app", FIXTURE.toByteArray(StandardCharsets.UTF_8)) }
        return path
    }

    private fun exchange(path: Path, input: ByteArray): List<JsonNode> {
        val output = ByteArrayOutputStream()
        Store(path, false).use { McpServer(it).run(ByteArrayInputStream(input), output) }
        return output.toString(StandardCharsets.UTF_8).lineSequence().filter(String::isNotBlank).map(Json::read).toList()
    }
    private fun exchange(path: Path, vararg input: String): List<JsonNode> = exchange(path, (input.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8))
    private fun request(id: Any, method: String, params: Any = emptyMap<String, Any>()) =
        Json.text(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
    private fun initialize(version: String = "2025-11-25") = request(1, "initialize", mapOf(
        "protocolVersion" to version, "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "test", "version" to "1")))
    private fun call(id: Any, tool: String, arguments: Any = emptyMap<String, Any>()) =
        request(id, "tools/call", mapOf("name" to tool, "arguments" to arguments))
    private fun ready(path: Path, vararg requests: String) = exchange(path, initialize(), INITIALIZED, *requests).drop(1)

    @Test fun initializationIsRequiredAndNegotiatesSupportedVersions() {
        val path = database()
        val replies = exchange(path, request(0, "tools/list"), initialize("2025-06-18"), request(2, "tools/list"), INITIALIZED,
            request(3, "tools/list"), initialize())
        assertEquals(-32002, replies[0].path("error").path("code").asInt())
        assertEquals("2025-06-18", replies[1].path("result").path("protocolVersion").asText())
        assertEquals(-32002, replies[2].path("error").path("code").asInt())
        assertEquals(7, replies[3].path("result").path("tools").size())
        assertEquals(-32602, replies[4].path("error").path("code").asInt())
        assertEquals("2025-11-25", exchange(path, initialize("unknown-version"))[0].path("result").path("protocolVersion").asText())
    }

    @Test fun advertisedToolsAreReadOnlyAndHaveObjectSchemas() {
        val tools = ready(database(), request("inventory", "tools/list")).single().path("result").path("tools")
        assertEquals(setOf("list_snapshots", "summarize_snapshot", "list_components", "inspect_component", "trace_dependency", "find_dependents", "compare_snapshots"), tools.map { it.path("name").asText() }.toSet())
        tools.forEach {
            assertTrue(it.path("annotations").path("readOnlyHint").asBoolean())
            assertTrue(it.path("annotations").path("idempotentHint").asBoolean())
            assertFalse(it.path("annotations").path("destructiveHint").asBoolean())
            assertFalse(it.path("annotations").path("openWorldHint").asBoolean())
            assertEquals("object", it.path("inputSchema").path("type").asText())
            assertFalse(it.path("inputSchema").path("additionalProperties").asBoolean(true))
            assertEquals("object", it.path("outputSchema").path("type").asText())
        }
    }

    @Test fun successfulResultsContainIdenticalStructuredAndTextEvidence() {
        val path = database()
        val before = Files.readAllBytes(path)
        val replies = ready(path,
            call(2, "list_snapshots", mapOf("limit" to 1)),
            call(3, "summarize_snapshot", mapOf("snapshot" to "baseline")),
            call(4, "list_components", mapOf("snapshot" to "baseline", "query" to "codec", "limit" to 1)),
            call(5, "inspect_component", mapOf("snapshot" to "baseline", "ref" to "codec")),
            call(6, "trace_dependency", mapOf("snapshot" to "baseline", "from" to "app", "to" to "codec")),
            call(7, "find_dependents", mapOf("snapshot" to "baseline", "ref" to "codec")),
            call(8, "compare_snapshots", mapOf("baseline" to "baseline", "candidate" to "baseline")))
        replies.forEach {
            assertFalse(it.has("error"), it.toString())
            val result = it.path("result")
            assertFalse(result.path("isError").asBoolean(), result.toString())
            assertTrue(result.path("structuredContent").isObject)
            assertEquals(result.path("structuredContent"), Json.read(result.path("content")[0].path("text").asText()))
        }
        assertEquals("FOUND", replies[4].path("result").path("structuredContent").path("status").asText())
        assertContentEquals(before, Files.readAllBytes(path))
        assertFalse(Files.exists(temp.resolve("boms.db-wal")))
    }

    @Test fun rejectsUnknownToolsUnknownArgumentsAndMissingRequiredValues() {
        val replies = ready(database(), call(2, "delete_snapshot"), call(3, "summarize_snapshot"),
            call(4, "list_components", mapOf("snapshot" to "baseline", "file" to "untrusted.json")),
            call(5, "trace_dependency", mapOf("snapshot" to "baseline", "from" to "app")),
            call(6, "inspect_component", mapOf("snapshot" to "baseline", "ref" to "")),
            call(7, "list_snapshots", Json.mapper.nullNode()))
        replies.forEach { assertEquals(-32602, it.path("error").path("code").asInt(), it.toString()) }
    }

    @Test fun paginationAndDepthRequireBoundedIntegers() {
        val replies = ready(database(),
            call(2, "list_snapshots", mapOf("limit" to 1.5)),
            call(3, "list_snapshots", mapOf("limit" to 0)),
            call(4, "list_snapshots", mapOf("limit" to 51)),
            call(5, "list_snapshots", mapOf("offset" to -1)),
            call(6, "list_snapshots", mapOf("offset" to "0")),
            call(7, "trace_dependency", mapOf("snapshot" to "baseline", "from" to "app", "to" to "codec", "maxDepth" to 129)))
        replies.forEach { assertEquals(-32602, it.path("error").path("code").asInt()) }
    }

    @Test fun missingEvidenceIsAToolErrorAndDoesNotExposeFilesystemPaths() {
        val replies = ready(database(), call(2, "summarize_snapshot", mapOf("snapshot" to "absent")),
            call(3, "inspect_component", mapOf("snapshot" to "baseline", "ref" to "missing")))
        replies.forEach {
            assertFalse(it.has("error"))
            assertTrue(it.path("result").path("isError").asBoolean())
            assertEquals("LOCAL_DATA_ERROR", it.path("result").path("structuredContent").path("code").asText())
            assertFalse(it.toString().contains(temp.toString()))
        }
    }

    @Test fun invalidJsonDuplicateKeysAndBatchesDoNotPreventLaterRequests() {
        val replies = exchange(database(), "{", "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}",
            "{} {}", "[]", request("alive", "ping"))
        assertEquals(listOf(-32700, -32700, -32700, -32600), replies.take(4).map { it.path("error").path("code").asInt() })
        assertEquals("alive", replies.last().path("id").asText())
        assertTrue(replies.last().path("result").isObject)
    }

    @Test fun invalidUtf8AndOversizedFramesRecoverAtNextNewline() {
        val oversized = ByteArray(Limits.MAX_FRAME_BYTES + 10) { 'x'.code.toByte() }
        val input = byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte()) + oversized + '\n'.code.toByte() +
            (request(9, "ping") + "\n").toByteArray(StandardCharsets.UTF_8)
        val replies = exchange(database(), input)
        assertEquals(3, replies.size)
        assertEquals(-32700, replies[0].path("error").path("code").asInt())
        assertEquals(-32700, replies[1].path("error").path("code").asInt())
        assertEquals(9, replies[2].path("id").asInt())
    }

    @Test fun rejectsInvalidEnvelopeIdsMetadataAndUnsupportedMethods() {
        val invalidId = "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}"
        val invalidWrapper = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"result\":{}}"
        val replies = ready(database(), invalidId, invalidWrapper,
            request(4, "tools/list", mapOf("cursor" to "1")),
            request(5, "tools/call", mapOf("name" to "list_snapshots", "_meta" to mapOf("progressToken" to true))),
            request(6, "resources/list"), request("still-alive", "ping"))
        assertEquals(listOf(-32600, -32600, -32602, -32602, -32601), replies.take(5).map { it.path("error").path("code").asInt() })
        assertEquals("still-alive", replies.last().path("id").asText())
    }

    @Test fun malformedInitializedNotificationDoesNotAdvanceLifecycle() {
        val path = database()
        val replies = exchange(path, initialize(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{\"unexpected\":true}}",
            request(2, "tools/list"), INITIALIZED, request(3, "tools/list"))
        assertEquals(3, replies.size)
        assertEquals(-32002, replies[1].path("error").path("code").asInt())
        assertEquals(7, replies[2].path("result").path("tools").size())
    }

    @Test fun oversizedEvidenceIsAnExplicitToolErrorAndSessionRemainsUsable() {
        val bom = Json.mapper.createObjectNode().put("bomFormat", "CycloneDX").put("specVersion", "1.6").put("version", 1)
        val components = bom.putArray("components")
        val dependencies = bom.putArray("dependencies")
        repeat(50) { index ->
            val ref = "r$index-" + "x".repeat(1000)
            components.addObject().put("type", "library").put("bom-ref", ref)
                .put("name", "漢".repeat(500)).put("group", "字".repeat(500)).put("version", "1.0")
                .put("purl", "pkg:generic/test$index@1.0?source=" + "x".repeat(4000))
            dependencies.addObject().put("ref", ref).putArray("dependsOn")
        }
        val path = temp.resolve("large.db")
        Store(path, true).use { it.add("large", "shop-app", Json.text(bom).toByteArray(StandardCharsets.UTF_8)) }
        val replies = ready(path, call(2, "list_components", mapOf("snapshot" to "large", "limit" to 50)), request(3, "ping"))
        assertTrue(replies[0].path("result").path("isError").asBoolean())
        assertEquals("OUTPUT_LIMIT", replies[0].path("result").path("structuredContent").path("code").asText())
        assertTrue(Json.text(replies[0]).toByteArray(StandardCharsets.UTF_8).size < Limits.MAX_FRAME_BYTES)
        assertTrue(replies[1].path("result").isObject)
    }

    companion object {
        private const val INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        private const val FIXTURE = """{
          "bomFormat":"CycloneDX","specVersion":"1.6","version":1,
          "metadata":{"component":{"type":"application","bom-ref":"app","name":"shop","version":"1.0"}},
          "components":[
            {"type":"library","bom-ref":"api","name":"api","version":"1.0"},
            {"type":"library","bom-ref":"codec","name":"codec","version":"1.0"}
          ],
          "dependencies":[{"ref":"app","dependsOn":["api"]},{"ref":"api","dependsOn":["codec"]},{"ref":"codec","dependsOn":[]}]
        }"""
    }
}
