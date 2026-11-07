package io.github.almeida96.bomtrail

import org.junit.jupiter.api.Test
import kotlin.test.*
import io.github.almeida96.bomtrail.GraphTest.Companion.bom
import io.github.almeida96.bomtrail.GraphTest.Companion.component
import io.github.almeida96.bomtrail.GraphTest.Companion.edge

class ComparisonTest {
    private fun snapshot(name: String, bom: Bom, project: String = "payments") = Snapshot(name, name, project, bom)

    @Test fun versionUpgradePreservesIdentityAndPointsToBothSources() {
        val old = component("old", "1", "pkg:maven/example/lib", pointer = "/components/0", purl = "pkg:maven/example/lib@1")
        val new = component("new", "2", old.identity, old.name, "/components/3", "pkg:maven/example/lib@2")
        val report = Comparison.compare(snapshot("before", bom(listOf(old), emptyList())), snapshot("after", bom(listOf(new), emptyList())))
        assertEquals("CHANGED", report.path("status").asText())
        assertEquals(1, report.path("componentChangeCount").asInt())
        val change = report.path("componentChanges").get(0)
        assertEquals("/components/0", change.path("beforePointer").asText())
        assertEquals("/components/3", change.path("afterPointer").asText())
        assertEquals(listOf("version", "purl"), change.path("fields").map { it.asText() })
    }

    @Test fun refsAndInventoryOrderingAreNotPackageIdentity() {
        val a = component("a", identity = "app", name = "app")
        val b = component("b", identity = "library", name = "library")
        val before = bom(listOf(a, b), listOf(edge("a", "b")))
        val after = bom(listOf(b.copy(ref = "new-b", pointer = "/components/0"), a.copy(ref = "new-a", pointer = "/components/1")),
            listOf(edge("new-a", "new-b", 1))).copy(subjectRef = "new-a", sourceSha256 = "b".repeat(64))
        val report = Comparison.compare(snapshot("old", before), snapshot("new", after))
        assertEquals("UNCHANGED", report.path("status").asText())
        assertEquals(0, report.path("totalChanges").asInt())
        assertNotEquals(report.path("baseline").path("sourceSha256").asText(), report.path("candidate").path("sourceSha256").asText())
    }

    @Test fun edgeChangesFollowCanonicalEndpointsRatherThanRefNames() {
        val nodes = listOf(component("app"), component("lib"), component("extra"))
        val before = bom(nodes, listOf(edge("app", "lib")))
        val after = bom(nodes, listOf(edge("app", "extra", 2)))
        val result = Comparison.compare(snapshot("before", before), snapshot("after", after))
        assertEquals(2, result.path("edgeChangeCount").asInt())
        assertEquals(setOf("ADDED", "REMOVED"), result.path("edgeChanges").map { it.path("kind").asText() }.toSet())
        val addition = result.path("edgeChanges").first { it.path("kind").asText() == "ADDED" }
        assertTrue(addition.path("beforePointer").isNull)
        assertEquals("/dependencies/2/dependsOn/0", addition.path("afterPointer").asText())
    }

    @Test fun ambiguousIdentitiesNeverProduceAnArbitraryVersionPairing() {
        val old = bom(listOf(component("one", "1", "same"), component("two", "2", "same")), emptyList())
        val newer = bom(listOf(component("new", "3", "same")), emptyList())
        val result = Comparison.compare(snapshot("old", old), snapshot("new", newer))
        assertEquals("INCONCLUSIVE", result.path("status").asText())
        assertEquals(1, result.path("ambiguousIdentityCount").asInt())
        assertEquals(0, result.path("componentChanges").size())
        assertFalse(result.path("hasChanges").asBoolean())
    }

    @Test fun incompleteGraphsAndProjectMismatchRemainInconclusiveWithObservedChanges() {
        val old = bom(listOf(component("a")), emptyList())
        val newer = bom(listOf(component("a", "2")), listOf(edge("a", "unknown"))).copy(declaredRefs = emptySet())
        val result = Comparison.compare(snapshot("old", old), snapshot("new", newer, "other-project"))
        assertEquals("INCONCLUSIVE", result.path("status").asText())
        assertTrue(result.path("hasChanges").asBoolean())
        val reasons = result.path("inconclusiveReasons").map { it.asText() }
        assertTrue("PROJECT_LABEL_MISMATCH" in reasons)
        assertTrue("CANDIDATE_MISSING_DEPENDENCY_DECLARATIONS" in reasons)
        assertTrue("CANDIDATE_UNRESOLVED_REFERENCES" in reasons)
    }

    @Test fun addedRemovedAndSubjectChangesAreVisible() {
        val a = component("a")
        val b = component("b")
        val before = bom(listOf(a, b), emptyList())
        val after = bom(listOf(b, component("c")), emptyList())
        val report = Comparison.compare(snapshot("old", before), snapshot("new", after))
        assertEquals(3, report.path("componentChangeCount").asInt())
        assertEquals(setOf("ADDED", "REMOVED", "CHANGED"), report.path("componentChanges").map { it.path("kind").asText() }.toSet())
        val changed = report.path("componentChanges").first { it.path("kind").asText() == "CHANGED" }
        assertEquals(listOf("subject"), changed.path("fields").map { it.asText() })
    }

    @Test fun reportCapsEvidenceButCountsEveryObservedChange() {
        val nodes = (0 until 300).map { component("n$it") }
        val before = bom(nodes, emptyList())
        val after = bom(nodes.map { it.copy(version = "2") }, emptyList())
        val report = Comparison.compare(snapshot("old", before), snapshot("new", after))
        assertEquals("CHANGED", report.path("status").asText())
        assertEquals(300, report.path("totalChanges").asInt())
        assertEquals(250, report.path("storedChanges").asInt())
        assertTrue(report.path("truncated").asBoolean())
    }

    @Test fun distinctPurlVariantsAreAddedAndRemovedRatherThanMerged() {
        val old = component("old", identity = "pkg:generic/lib?arch=x86", name = "lib")
        val new = component("new", identity = "pkg:generic/lib?arch=arm", name = "lib")
        val report = Comparison.compare(snapshot("old", bom(listOf(old), emptyList())), snapshot("new", bom(listOf(new), emptyList())))
        assertEquals(2, report.path("componentChangeCount").asInt())
        assertEquals(setOf("ADDED", "REMOVED"), report.path("componentChanges").map { it.path("kind").asText() }.toSet())
    }

    @Test fun syntheticRefsAndUnassessedCompositionCannotProduceUnchangedVerdict() {
        val bom = bom(listOf(component("synthetic").copy(syntheticRef = true)), emptyList()).copy(
            warnings = listOf(Finding("COMPOSITIONS_NOT_EVALUATED", "unsupported", "/compositions")), warningCount = 1)
        val report = Comparison.compare(snapshot("a", bom), snapshot("b", bom))
        assertEquals("INCONCLUSIVE", report.path("status").asText())
        assertTrue(report.path("inconclusiveReasons").any { it.asText() == "BASELINE_COMPOSITIONS_NOT_ANALYZED" })
    }

    @Test fun canonicalPurlFormattingDoesNotCreateDrift() {
        val old = component("a", identity = "pkg:generic/lib?a=1&b=2", purl = "pkg:generic/lib@1?b=2&a=1")
        val new = old.copy(purl = "pkg:generic/lib@1?a=1&b=2")
        val report = Comparison.compare(snapshot("old", bom(listOf(old), emptyList())), snapshot("new", bom(listOf(new), emptyList())))
        assertEquals("UNCHANGED", report.path("status").asText())
    }

    @Test fun purlVersionAloneIsStillAnObservedUpgrade() {
        val old = component("a", version = null, identity = "pkg:generic/lib", purl = "pkg:generic/lib@1")
        val new = old.copy(purl = "pkg:generic/lib@2")
        val report = Comparison.compare(snapshot("old", bom(listOf(old), emptyList())), snapshot("new", bom(listOf(new), emptyList())))
        assertEquals("CHANGED", report.path("status").asText())
        assertEquals(listOf("purl"), report.path("componentChanges").get(0).path("fields").map { it.asText() })
    }

    @Test fun emptyInventoriesAreInconclusiveRatherThanUnchangedEvidence() {
        val empty = bom(emptyList(), emptyList())
        val report = Comparison.compare(snapshot("before", empty), snapshot("after", empty))
        assertEquals("INCONCLUSIVE", report.path("status").asText())
        assertEquals(0, report.path("totalChanges").asInt())
        assertTrue(report.path("inconclusiveReasons").any { it.asText() == "BASELINE_NO_INVENTORY" })
    }
}
