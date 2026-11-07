package io.github.almeida96.bomtrail

import org.junit.jupiter.api.Test
import kotlin.test.*

class GraphTest {
    @Test fun shortestPathIsDeterministicAndCycleSafe() {
        val nodes = listOf(component("app"), component("a"), component("b"), component("lib"))
        val bom = bom(nodes, listOf(edge("b", "lib", 0), edge("app", "b", 1), edge("a", "lib", 2), edge("app", "a", 3), edge("lib", "app", 4)))
        val path = Graph.path(bom, "app", "lib")
        assertEquals("FOUND", path.path("status").asText())
        assertEquals(2, path.path("distance").asInt())
        assertEquals(listOf("app", "a", "lib"), path.path("nodes").map { it.path("ref").asText() })
        assertEquals(listOf("/dependencies/3/dependsOn/0", "/dependencies/2/dependsOn/0"), path.path("edges").map { it.path("sourcePointer").asText() })
    }

    @Test fun depthCutoffIsDistinctFromAnExhaustedGraph() {
        val bom = bom(listOf(component("app"), component("a"), component("lib"), component("other")), listOf(edge("app", "a"), edge("a", "lib", 1)))
        assertEquals("DEPTH_LIMIT", Graph.path(bom, "app", "other", 1).path("status").asText())
        assertEquals("NOT_OBSERVED", Graph.path(bom, "app", "other", 2).path("status").asText())
        assertEquals("FOUND", Graph.path(bom, "app", "lib", 2).path("status").asText())
    }

    @Test fun selfPathIsZeroEdgesAndUnknownReferencesAreNotNodes() {
        val bom = bom(listOf(component("app")), listOf(edge("app", "external")))
        val path = Graph.path(bom, "app", "app")
        assertEquals(0, path.path("distance").asInt())
        assertEquals(1, path.path("nodes").size())
        assertEquals(0, path.path("edges").size())
        assertFalse(path.path("graphDeclarationsComplete").asBoolean())
        assertFailsWith<IllegalArgumentException> { Graph.path(bom, "app", "external") }
    }

    @Test fun missingDeclarationsCannotBePresentedAsKnownLeaves() {
        val full = bom(listOf(component("a"), component("b")), emptyList())
        assertTrue(Graph.summary(full).path("graphDeclarationsComplete").asBoolean())
        assertEquals("DECLARED", Graph.inspect(full, "a").path("dependencyDeclaration").asText())
        val partial = full.copy(declaredRefs = setOf("b"))
        val report = Graph.summary(partial)
        assertFalse(report.path("graphDeclarationsComplete").asBoolean())
        assertEquals(1, report.path("missingDeclarations").asInt())
        assertEquals("UNKNOWN", Graph.inspect(partial, "a").path("dependencyDeclaration").asText())
    }

    @Test fun reverseImpactIncludesEveryKnownDependentOnceAndExcludesTheTarget() {
        val bom = bom(listOf(component("app"), component("web"), component("worker"), component("lib")), listOf(
            edge("app", "web"), edge("app", "worker", 1), edge("web", "lib", 2), edge("worker", "lib", 3), edge("lib", "lib", 4)))
        val impact = Graph.impact(bom, "lib", 0, 2)
        assertEquals(3, impact.path("total").asInt())
        assertEquals(2, impact.path("directDependents").asInt())
        assertEquals(2, impact.path("nextOffset").asInt())
        assertEquals(listOf("web", "worker"), impact.path("dependents").map { it.path("component").path("ref").asText() })
        val rest = Graph.impact(bom, "lib", 2, 2)
        assertTrue(rest.path("nextOffset").isNull)
        assertEquals("app", rest.path("dependents").get(0).path("component").path("ref").asText())
        assertEquals(2, rest.path("dependents").get(0).path("distance").asInt())
        assertFalse(rest.path("dependents").get(0).path("witness").path("truncated").asBoolean())
    }

    @Test fun impactSharesABoundedWitnessBudgetWithoutHidingTotalReachability() {
        val nodes = (0..100).map { component("n${it.toString().padStart(3, '0')}") }
        val edges = (0 until 100).map { edge(nodes[it].ref, nodes[it + 1].ref, it) }
        val report = Graph.impact(bom(nodes, edges), "n100", 0, 50)
        assertEquals(100, report.path("total").asInt())
        assertEquals(50, report.path("dependents").size())
        assertEquals(64, report.path("dependents").sumOf { it.path("witness").path("displayedEdges").asInt() })
        assertTrue(report.path("dependents").last().path("witness").path("truncated").asBoolean())
        assertEquals(50, report.path("dependents").last().path("distance").asInt())
    }

    @Test fun unresolvedEdgesRemainInspectableAndBounded() {
        val selected = component("a")
        val edges = (0..60).map { edge("a", "outside-$it", it) }
        val bom = bom(listOf(selected), edges)
        val inspect = Graph.inspect(bom, "a")
        assertEquals(61, inspect.path("dependencyCount").asInt())
        assertEquals(50, inspect.path("dependencies").size())
        assertTrue(inspect.path("dependenciesTruncated").asBoolean())
        assertTrue(inspect.path("dependencies").all { !it.path("resolved").asBoolean() })
        assertEquals(61, Graph.summary(bom).path("unresolvedEdges").asInt())
    }

    @Test fun inventoryPaginationSearchAndBoundsAreExplicit() {
        val bom = bom(listOf(component("b", name = "Second"), component("a", name = "FIRST")), emptyList())
        assertEquals("a", Graph.inventory(bom, "first").path("components").get(0).path("ref").asText())
        assertEquals(1, Graph.inventory(bom, "", 0, 1).path("nextOffset").asInt())
        assertEquals(0, Graph.inventory(bom, "", 10, 1).path("components").size())
        assertFailsWith<IllegalArgumentException> { Graph.inventory(bom, "", -1) }
        assertFailsWith<IllegalArgumentException> { Graph.impact(bom, "a", 0, 51) }
        assertFailsWith<IllegalArgumentException> { Graph.path(bom, "a", "b", 129) }
        assertFailsWith<IllegalArgumentException> { Graph.inspect(bom, "absent") }
    }

    @Test fun structuralCoverageDoesNotIgnoreCompositionWarningsOrTruncation() {
        val bom = bom(listOf(component("a")), emptyList())
        assertTrue(Graph.summary(bom.copy(warnings = listOf(Finding("PROVIDES_IGNORED", "ignored", "/dependencies")), warningCount = 1)).path("graphDeclarationsComplete").asBoolean())
        assertFalse(Graph.summary(bom.copy(warnings = listOf(Finding("COMPOSITIONS_NOT_EVALUATED", "unknown", "/compositions")), warningCount = 1)).path("graphDeclarationsComplete").asBoolean())
        assertFalse(Graph.summary(bom.copy(warningCount = 101)).path("graphDeclarationsComplete").asBoolean())
    }

    @Test fun longChainsUseIterativeTraversal() {
        val nodes = (0 until 10_000).map { component("n$it") }
        val edges = (0 until 9_999).map { edge("n$it", "n${it + 1}", it) }
        val result = Graph.impact(bom(nodes, edges), "n9999", 9_998, 1)
        assertEquals(9_999, result.path("total").asInt())
        assertEquals(9_999, result.path("dependents").get(0).path("distance").asInt())
        assertEquals(64, result.path("dependents").get(0).path("witness").path("displayedEdges").asInt())
    }

    @Test fun unknownDependencyRecordWithNoEdgesStillMarksCoverageIncomplete() {
        val source = bom(listOf(component("a")), emptyList()).copy(declaredRefs = setOf("a", "external"))
        val report = Graph.summary(source)
        assertEquals(1, report.path("unresolvedReferences").asInt())
        assertFalse(report.path("graphDeclarationsComplete").asBoolean())
    }

    companion object {
        fun component(ref: String, version: String? = "1", identity: String = "id:$ref", name: String = ref,
                      pointer: String = "/components/$ref", purl: String? = null) =
            Component(ref, "component", "library", "example", name, version, purl, identity, pointer)

        fun edge(from: String, to: String, index: Int = 0) = Edge(from, to, "/dependencies/$index/dependsOn/0")
        fun bom(nodes: List<Component>, edges: List<Edge>) = Bom("1.6", nodes.firstOrNull()?.ref, nodes, edges,
            nodes.mapTo(linkedSetOf()) { it.ref }, emptyList(), 0, "a".repeat(64))
    }
}
