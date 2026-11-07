package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.ArrayDeque
import java.util.Locale

/** Queries only the declared dependsOn graph. Containment is not a dependency. */
object Graph {
    private const val MAX_PAGE = 50
    private const val MAX_WITNESS_EDGES = 64

    fun summary(bom: Bom): ObjectNode {
        val index = Index(bom)
        val missing = bom.nodes.count { it.ref !in bom.declaredRefs }
        return base("summary", bom).apply {
            put("specVersion", bom.specVersion)
            put("nodes", bom.nodes.size)
            put("components", bom.nodes.count { it.kind == "component" })
            put("services", bom.nodes.count { it.kind == "service" })
            put("edges", bom.edges.size)
            put("knownEdges", index.knownEdges.size)
            put("unresolvedEdges", bom.edges.size - index.knownEdges.size)
            put("unresolvedReferences", (bom.edges.flatMap { listOf(it.from, it.to) } + bom.declaredRefs +
                bom.warnings.filter { it.code == "UNRESOLVED_REF" }.mapNotNull { it.ref }).filter { it !in index.nodes }.distinct().size)
            put("declaredNodes", bom.nodes.size - missing)
            put("missingDeclarations", missing)
            put("syntheticRefs", bom.nodes.count { it.syntheticRef })
            nullable("subjectRef", bom.subjectRef)
            put("warningCount", bom.warningCount)
            put("warningsTruncated", bom.warningCount > bom.warnings.size)
            set<JsonNode>("warnings", Json.mapper.valueToTree(bom.warnings))
        }
    }

    fun inventory(bom: Bom, query: String = "", offset: Int = 0, limit: Int = 25): ObjectNode {
        page(offset, limit)
        require(query.length <= 512) { "Search query exceeds 512 characters" }
        val needle = query.lowercase(Locale.ROOT)
        val matches = bom.nodes.sortedBy { it.ref }.filter { node ->
            listOfNotNull(node.ref, node.name, node.group, node.purl).any { it.lowercase(Locale.ROOT).contains(needle) }
        }
        return paged("inventory", bom, matches.size, offset, limit).apply {
            put("query", query)
            val output = putArray("components")
            matches.drop(offset).take(limit).forEach { output.add(component(it)) }
        }
    }

    fun inspect(bom: Bom, ref: String): ObjectNode {
        val index = Index(bom)
        val selected = index.requireNode(ref)
        val outgoing = bom.edges.filter { it.from == ref }.sortedWith(compareBy(Edge::to, Edge::pointer))
        val incoming = bom.edges.filter { it.to == ref }.sortedWith(compareBy(Edge::from, Edge::pointer))
        return base("component", bom).apply {
            set<ObjectNode>("component", component(selected))
            put("dependencyDeclaration", if (ref in bom.declaredRefs) "DECLARED" else "UNKNOWN")
            put("dependencyCount", outgoing.size)
            put("dependentCount", incoming.size)
            put("dependenciesTruncated", outgoing.size > MAX_PAGE)
            put("dependentsTruncated", incoming.size > MAX_PAGE)
            val dependencies = putArray("dependencies")
            outgoing.take(MAX_PAGE).forEach { dependencies.add(edgeJson(it, index)) }
            val dependents = putArray("dependents")
            incoming.take(MAX_PAGE).forEach { dependents.add(edgeJson(it, index)) }
            put("note", "Edges are declared relationships, not evidence that a dependency executes at runtime.")
        }
    }

    fun path(bom: Bom, from: String, to: String, maxDepth: Int = 64): ObjectNode {
        require(maxDepth in 1..128) { "maxDepth must be between 1 and 128" }
        val index = Index(bom)
        index.requireNode(from)
        index.requireNode(to)
        val distance = mutableMapOf(from to 0)
        val previous = mutableMapOf<String, Edge>()
        val queue = ArrayDeque<String>().apply { add(from) }
        var limited = false
        while (queue.isNotEmpty() && to !in distance) {
            val current = queue.removeFirst()
            val depth = distance.getValue(current)
            for (edge in index.outgoing[current].orEmpty()) {
                if (edge.to in distance) continue
                if (depth == maxDepth) { limited = true; continue }
                distance[edge.to] = depth + 1
                previous[edge.to] = edge
                queue.addLast(edge.to)
            }
        }
        val found = to in distance
        return base("path", bom).apply {
            put("status", if (found) "FOUND" else if (limited) "DEPTH_LIMIT" else "NOT_OBSERVED")
            put("from", from)
            put("to", to)
            put("maxDepth", maxDepth)
            put("visitedNodes", distance.size)
            val nodes = putArray("nodes")
            val edges = putArray("edges")
            if (found) {
                put("distance", distance.getValue(to))
                val chain = mutableListOf<Edge>()
                var cursor = to
                while (cursor != from) {
                    val edge = previous.getValue(cursor)
                    chain.add(edge)
                    cursor = edge.from
                }
                chain.reverse()
                nodes.add(pathNode(index.nodes.getValue(from)))
                chain.forEach { relation ->
                    edges.add(edgeJson(relation, index))
                    nodes.add(pathNode(index.nodes.getValue(relation.to)))
                }
            } else putNull("distance")
            put("note", "One shortest path through known declared edges. NOT_OBSERVED is not proof of runtime independence; DEPTH_LIMIT means unexplored nodes remain beyond the requested depth.")
        }
    }

    fun impact(bom: Bom, ref: String, offset: Int = 0, limit: Int = 25): ObjectNode {
        page(offset, limit)
        val index = Index(bom)
        val selected = index.requireNode(ref)
        val distance = mutableMapOf(ref to 0)
        val next = mutableMapOf<String, Edge>()
        val queue = ArrayDeque<String>().apply { add(ref) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (edge in index.incoming[current].orEmpty()) if (edge.from !in distance) {
                distance[edge.from] = distance.getValue(current) + 1
                next[edge.from] = edge
                queue.addLast(edge.from)
            }
        }
        val affected = distance.keys.filter { it != ref }.sortedWith(compareBy<String>({ distance.getValue(it) }, { it }))
        return paged("impact", bom, affected.size, offset, limit).apply {
            set<ObjectNode>("component", component(selected))
            put("directDependents", index.incoming[ref].orEmpty().map { it.from }.filter { it != ref }.distinct().size)
            put("witnessEdgeBudget", MAX_WITNESS_EDGES)
            val rows = putArray("dependents")
            var remaining = MAX_WITNESS_EDGES
            for (dependent in affected.drop(offset).take(limit)) {
                val row = rows.addObject()
                row.set<ObjectNode>("component", component(index.nodes.getValue(dependent)))
                val totalEdges = distance.getValue(dependent)
                row.put("distance", totalEdges)
                val witness = row.putObject("witness")
                witness.put("from", dependent).put("to", ref).put("totalEdges", totalEdges)
                val edges = witness.putArray("edges")
                var cursor = dependent
                while (cursor != ref && remaining > 0) {
                    val edge = next.getValue(cursor)
                    edges.add(edgeJson(edge, index))
                    remaining--
                    cursor = edge.to
                }
                witness.put("displayedEdges", edges.size())
                witness.put("truncated", cursor != ref)
                if (cursor != ref) witness.put("nextRef", cursor)
            }
            put("note", "Reverse reachability identifies declared dependents, not runtime exploitability. Witnesses share a 64-edge page budget; use the path command or trace_dependency tool for an individual path.")
        }
    }

    internal fun component(node: Component): ObjectNode = Json.mapper.createObjectNode().apply {
        put("ref", node.ref)
        put("kind", node.kind)
        put("type", node.type)
        nullable("group", node.group)
        put("name", node.name)
        nullable("version", node.version)
        nullable("purl", node.purl)
        put("identity", node.identity)
        put("sourcePointer", node.pointer)
        put("syntheticRef", node.syntheticRef)
    }

    internal fun incompleteness(bom: Bom): List<String> {
        val known = bom.nodes.mapTo(hashSetOf()) { it.ref }
        return buildList {
            if (bom.nodes.isEmpty()) add("NO_INVENTORY")
            if (bom.nodes.any { it.ref !in bom.declaredRefs }) add("MISSING_DEPENDENCY_DECLARATIONS")
            if (bom.edges.any { it.from !in known || it.to !in known } || bom.declaredRefs.any { it !in known }
                || bom.warnings.any { it.code == "UNRESOLVED_REF" }) add("UNRESOLVED_REFERENCES")
            if (bom.nodes.any { it.syntheticRef }) add("SYNTHETIC_REFERENCES")
            if (bom.warnings.any { it.code.contains("COMPOSITION") }) add("COMPOSITIONS_NOT_ANALYZED")
            if (bom.warnings.any { it.code == "PURL_VERSION_MISMATCH" }) add("PURL_VERSION_MISMATCH")
            // The warning list is bounded. Omitted warnings could carry an additional coverage caveat.
            if (bom.warningCount > bom.warnings.size) add("WARNINGS_TRUNCATED")
        }
    }

    private fun base(kind: String, bom: Bom): ObjectNode = Json.mapper.createObjectNode().apply {
        put("kind", kind)
        put("sourceSha256", bom.sourceSha256)
        val reasons = incompleteness(bom)
        put("graphDeclarationsComplete", reasons.isEmpty())
        set<JsonNode>("coverageNotes", Json.mapper.valueToTree(reasons))
    }

    private fun paged(kind: String, bom: Bom, total: Int, offset: Int, limit: Int) = base(kind, bom).apply {
        put("total", total).put("offset", offset).put("limit", limit)
        if (offset.toLong() + limit < total) put("nextOffset", offset + limit) else putNull("nextOffset")
    }

    private fun pathNode(node: Component): ObjectNode = Json.mapper.createObjectNode()
        .put("ref", node.ref).put("name", node.name).put("sourcePointer", node.pointer)

    private fun edgeJson(edge: Edge, index: Index): ObjectNode = Json.mapper.createObjectNode()
        .put("from", edge.from).put("to", edge.to).put("sourcePointer", edge.pointer)
        .put("resolved", edge.from in index.nodes && edge.to in index.nodes)

    private fun page(offset: Int, limit: Int) {
        require(offset in 0..Limits.MAX_NODES && limit in 1..MAX_PAGE) { "offset must be 0..10000 and limit must be 1..50" }
    }

    private fun ObjectNode.nullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private class Index(bom: Bom) {
        val nodes = bom.nodes.associateBy { it.ref }
        val knownEdges = bom.edges.filter { it.from in nodes && it.to in nodes }
        val outgoing = knownEdges.groupBy { it.from }.mapValues { (_, rows) -> rows.sortedWith(compareBy(Edge::to, Edge::pointer)) }
        val incoming = knownEdges.groupBy { it.to }.mapValues { (_, rows) -> rows.sortedWith(compareBy(Edge::from, Edge::pointer)) }
        fun requireNode(ref: String): Component = requireNotNull(nodes[ref]) { "Component reference was not found in this snapshot" }
    }
}
