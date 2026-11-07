package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.*

class BomParserTest {
    private fun parse(value: String): Bom = BomParser.parse(value.toByteArray(StandardCharsets.UTF_8))
    private fun document(): ObjectNode = Json.mapper.createObjectNode().put("bomFormat", "CycloneDX").put("specVersion", "1.6")
    private fun component(ref: String, name: String = ref): ObjectNode = Json.mapper.createObjectNode()
        .put("type", "library").put("bom-ref", ref).put("name", name)
    private fun raw(value: ObjectNode) = Json.text(value).toByteArray(StandardCharsets.UTF_8)
    private fun leafDocument(): ObjectNode = document().also {
        it.putArray("components").add(component("lib"))
        it.putArray("dependencies").addObject().put("ref", "lib").putArray("dependsOn")
    }

    @Test fun acceptsSupportedVersionsAndExplicitEmptyLeaf() {
        for (version in listOf("1.5", "1.6", "1.7")) {
            val bom = BomParser.parse(raw(leafDocument().put("specVersion", version)))
            assertEquals(version, bom.specVersion)
            assertEquals(setOf("lib"), bom.declaredRefs)
            assertTrue(bom.edges.isEmpty())
            assertEquals(0, bom.warningCount)
        }
    }

    @Test fun projectsRootNestedComponentsAndServicesWithoutInventingContainmentEdges() {
        val bom = parse("""{
          "bomFormat":"CycloneDX","specVersion":"1.6",
          "metadata":{"component":{"type":"application","bom-ref":"app","name":"app",
            "components":[{"type":"library","bom-ref":"embedded","name":"embedded"}]}},
          "components":[{"type":"library","bom-ref":"library","name":"library"}],
          "services":[{"bom-ref":"service","name":"service","services":[{"bom-ref":"nested-service","name":"nested-service"}]}],
          "dependencies":[{"ref":"app","dependsOn":["library"]},{"ref":"library","dependsOn":[]}]
        }""")
        assertEquals("app", bom.subjectRef)
        assertEquals(5, bom.nodes.size)
        assertEquals("/metadata/component/components/0", bom.nodes.single { it.ref == "embedded" }.pointer)
        assertEquals("service", bom.nodes.single { it.ref == "nested-service" }.kind)
        assertEquals("/services/0/services/0", bom.nodes.single { it.ref == "nested-service" }.pointer)
        assertEquals(listOf(Edge("app", "library", "/dependencies/0/dependsOn/0")), bom.edges)
        assertEquals(3, bom.warnings.count { it.code == "MISSING_DECLARATION" })
    }

    @Test fun explicitEmptyRecordDeclaresALeafButAbsentRecordRemainsUnknown() {
        val document = leafDocument()
        (document.path("dependencies").get(0) as ObjectNode).remove("dependsOn")
        val bom = BomParser.parse(raw(document))
        assertEquals(setOf("lib"), bom.declaredRefs)
        assertEquals(0, bom.warningCount)
        document.remove("dependencies")
        val unknown = BomParser.parse(raw(document))
        assertTrue(unknown.declaredRefs.isEmpty())
        assertEquals(listOf("MISSING_DECLARATION"), unknown.warnings.map { it.code })
    }

    @Test fun retainsUnknownReferencesWithEvidenceAndAllowsCycles() {
        val bom = parse("""{"bomFormat":"CycloneDX","specVersion":"1.7",
          "components":[{"type":"library","bom-ref":"a","name":"a"},{"type":"library","bom-ref":"b","name":"b"}],
          "dependencies":[{"ref":"a","dependsOn":["b","external"]},{"ref":"b","dependsOn":["a"]},{"ref":"unknown","dependsOn":[]}]}""")
        assertEquals(3, bom.edges.size)
        assertTrue(bom.edges.any { it.from == "b" && it.to == "a" })
        assertEquals(setOf("/dependencies/0/dependsOn/1", "/dependencies/2/ref"),
            bom.warnings.filter { it.code == "UNRESOLVED_REF" }.map { it.pointer }.toSet())
    }

    @Test fun duplicateRefsAcrossRootAndInventoryAreRejected() {
        val document = leafDocument()
        document.putObject("metadata").set<ObjectNode>("component", component("lib"))
        val failure = assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(document)) }
        assertTrue(failure.message!!.contains("Duplicate bom-ref"))
    }

    @Test fun rejectsDuplicateDependencyRecordsAndTargets() {
        val duplicateRecords = leafDocument()
        (duplicateRecords.path("dependencies") as com.fasterxml.jackson.databind.node.ArrayNode)
            .addObject().put("ref", "lib").putArray("dependsOn")
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(duplicateRecords)) }
        val duplicateTargets = leafDocument()
        (duplicateTargets.path("dependencies").get(0).path("dependsOn") as com.fasterxml.jackson.databind.node.ArrayNode).add("lib").add("lib")
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(duplicateTargets)) }
    }

    @Test fun syntheticIdsCannotResolveInputEdgesOrCollideWithExplicitRefs() {
        val base = "urn:bomtrail:synthetic:" + Json.hash("/components/0")
        val document = document()
        document.putArray("components").add(component("temporary").also { it.remove("bom-ref") }).add(component("known"))
        document.putArray("dependencies").addObject().put("ref", "known").putArray("dependsOn").add(base)
        val bom = BomParser.parse(raw(document))
        assertEquals("$base:1", bom.nodes[0].ref)
        assertTrue(bom.nodes[0].syntheticRef)
        assertTrue(bom.warnings.any { it.code == "UNRESOLVED_REF" && it.ref == base })
        assertEquals(base, bom.edges.single().to)
        val repeated = BomParser.parse(raw(document))
        assertEquals(bom.nodes[0].ref, repeated.nodes[0].ref)

        (document.path("components").get(1) as ObjectNode).put("bom-ref", base)
        val explicitCollision = BomParser.parse(raw(document))
        assertEquals("$base:1", explicitCollision.nodes[0].ref)
        assertEquals(base, explicitCollision.nodes[1].ref)
    }

    @Test fun purlIdentityRemovesVersionButRetainsQualifiersAndSubpath() {
        fun entry(version: String, classifier: String, subpath: String) = component(version)
            .put("version", version).put("purl", "pkg:maven/org.example/client@$version?type=jar&classifier=$classifier#$subpath")
        val document = document()
        document.putArray("components").add(entry("1.0", "sources", "src"))
            .add(entry("2.0", "sources", "src")).add(entry("3.0", "tests", "src"))
            .add(entry("4.0", "sources", "other"))
        val nodes = BomParser.parse(raw(document)).nodes
        assertEquals(nodes[0].identity, nodes[1].identity)
        assertNotEquals(nodes[0].identity, nodes[2].identity)
        assertNotEquals(nodes[0].identity, nodes[3].identity)
        assertEquals("pkg:maven/org.example/client@1.0?type=jar&classifier=sources#src", nodes[0].purl)
        assertTrue(nodes[0].identity.contains("classifier=sources&type=jar"))
        assertFalse(nodes[0].identity.contains("@1.0"))
    }

    @Test fun warnsOnConflictingPurlVersionAndRejectsInvalidPurl() {
        val document = leafDocument()
        val lib = document.path("components").get(0) as ObjectNode
        lib.put("version", "2.0").put("purl", "pkg:maven/org.example/lib@1.0")
        val bom = BomParser.parse(raw(document))
        assertEquals("PURL_VERSION_MISMATCH", bom.warnings.single().code)
        assertEquals("/components/0/purl", bom.warnings.single().pointer)
        lib.put("purl", "https://not-a-package-url.example/")
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(document)) }
    }

    @Test fun nameIdentitySeparatesKindsTypesAndGroups() {
        val document = document()
        document.putArray("components").add(component("a", "shared").put("group", "org.a"))
            .add(component("b", "shared").put("group", "org.b"))
            .add(component("c", "shared").put("type", "application").put("group", "org.a"))
        document.putArray("services").addObject().put("bom-ref", "s").put("name", "shared").put("group", "org.a")
        assertEquals(4, BomParser.parse(raw(document)).nodes.map { it.identity }.toSet().size)
    }

    @Test fun providesAndCompositionAssertionsAreReportedWithoutBecomingDependencyEdges() {
        val document = leafDocument()
        (document.path("dependencies").get(0) as ObjectNode).putArray("provides").add("other")
        document.putArray("compositions").addObject().put("aggregate", "complete")
        val bom = BomParser.parse(raw(document))
        assertTrue(bom.edges.isEmpty())
        assertEquals(setOf("PROVIDES_IGNORED", "COMPOSITIONS_NOT_EVALUATED"), bom.warnings.map { it.code }.toSet())
    }

    @Test fun warningListIsBoundedButTotalRemainsAccurate() {
        val document = document()
        val nodes = document.putArray("components")
        repeat(125) { nodes.add(component("node-$it")) }
        val bom = BomParser.parse(raw(document))
        assertEquals(125, bom.warningCount)
        assertEquals(Limits.MAX_WARNINGS, bom.warnings.size)
    }

    @Test fun sourceChecksumUsesExactBytesIncludingBomAndWhitespace() {
        val original = raw(leafDocument())
        val bytesWithBom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + original
        val first = BomParser.parse(original)
        val second = BomParser.parse(bytesWithBom)
        assertEquals(first.nodes, second.nodes)
        assertEquals(Json.hash(original), first.sourceSha256)
        assertEquals(Json.hash(bytesWithBom), second.sourceSha256)
        assertNotEquals(first.sourceSha256, second.sourceSha256)
    }

    @Test fun rejectsInvalidUtf8DuplicateKeysUnpairedUnicodeAndTrailingDocuments() {
        assertFails { BomParser.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { parse("""{"bomFormat":"CycloneDX","bomFormat":"CycloneDX","specVersion":"1.6"}""") }
        assertFails { parse("""{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"type":"library","name":"\uD800"}]}""") }
        assertFails { parse(Json.text(leafDocument()) + " {}") }
    }

    @Test fun rejectsUnsupportedVersionsAndMalformedHandledFields() {
        for (mutation in listOf<(ObjectNode) -> Unit>(
            { it.put("specVersion", "2.0") }, { it.put("bomFormat", "SPDX") },
            { it.putNull("components") }, { it.putNull("metadata") },
            { (it.path("components").get(0) as ObjectNode).putNull("name") },
            { (it.path("components").get(0) as ObjectNode).put("bom-ref", "bad\nref") },
            { (it.path("dependencies").get(0) as ObjectNode).putNull("dependsOn") }
        )) {
            val document = leafDocument().also(mutation)
            assertFails { BomParser.parse(raw(document)) }
        }
    }

    @Test fun rejectsOversizedBytesNodeCountsEdgesAndLabels() {
        assertFailsWith<IllegalArgumentException> { BomParser.parse(ByteArray(Limits.MAX_INPUT_BYTES + 1)) }
        val tooMany = document()
        val nodes = tooMany.putArray("components")
        repeat(Limits.MAX_NODES + 1) { nodes.add(component("ref-$it")) }
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(tooMany)) }
        val tooManyEdges = leafDocument()
        val targets = tooManyEdges.path("dependencies").get(0).path("dependsOn") as com.fasterxml.jackson.databind.node.ArrayNode
        repeat(Limits.MAX_EDGES + 1) { targets.add("unknown-$it") }
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(tooManyEdges)) }
        val largeLabel = leafDocument()
        (largeLabel.path("components").get(0) as ObjectNode).put("name", "x".repeat(513))
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(largeLabel)) }
    }

    @Test fun permitsThirtyTwoNestedInventoryLevelsAndRejectsTheNext() {
        fun nested(levels: Int): ObjectNode {
            val document = document()
            var current = component("root")
            document.putArray("components").add(current)
            repeat(levels - 1) { index ->
                val child = component("child-$index")
                current.putArray("components").add(child)
                current = child
            }
            return document
        }
        assertEquals(32, BomParser.parse(raw(nested(32))).nodes.size)
        assertFailsWith<IllegalArgumentException> { BomParser.parse(raw(nested(33))) }
    }

    @Test fun unrelatedFieldsAreNotInterpretedOrFetched() {
        val document = leafDocument()
        document.putArray("externalReferences").addObject().put("type", "website").put("url", "https://example.invalid/unreachable")
        document.putArray("vulnerabilities").addObject().put("id", "example-only")
        val bom = BomParser.parse(raw(document))
        assertEquals(1, bom.nodes.size)
        assertEquals(0, bom.warningCount)
    }
}
