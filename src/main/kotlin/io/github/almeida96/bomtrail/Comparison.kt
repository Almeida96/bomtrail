package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.packageurl.PackageURL

/** Matches unique declared package identities; ambiguous groups are left unresolved. */
object Comparison {
    fun compare(before: Snapshot, after: Snapshot): ObjectNode {
        val left = before.bom.nodes.groupBy { it.identity }
        val right = after.bom.nodes.groupBy { it.identity }
        val identities = (left.keys + right.keys).sorted()
        val ambiguous = identities.filter { left[it].orEmpty().size > 1 || right[it].orEmpty().size > 1 }.toSet()
        val reasons = linkedSetOf<String>()
        if (before.project != after.project) reasons.add("PROJECT_LABEL_MISMATCH")
        reasons.addAll(Graph.incompleteness(before.bom).map { "BASELINE_$it" })
        reasons.addAll(Graph.incompleteness(after.bom).map { "CANDIDATE_$it" })
        if (ambiguous.isNotEmpty()) reasons.add("AMBIGUOUS_COMPONENT_IDENTITIES")

        val output = Json.mapper.createObjectNode().put("kind", "comparison")
        output.set<ObjectNode>("baseline", snapshot(before))
        output.set<ObjectNode>("candidate", snapshot(after))
        val components = output.putArray("componentChanges")
        val edges = output.putArray("edgeChanges")
        var componentChanges = 0
        var edgeChanges = 0
        var stored = 0

        for (identity in identities) {
            if (identity in ambiguous) continue
            val old = left[identity]?.single()
            val new = right[identity]?.single()
            val changedFields = if (old != null && new != null) fields(old, new, before.bom, after.bom) else emptyList()
            if (old == null || new == null || changedFields.isNotEmpty()) {
                componentChanges++
                if (stored++ < Limits.MAX_CHANGES) {
                    val change = components.addObject().put("kind", if (old == null) "ADDED" else if (new == null) "REMOVED" else "CHANGED")
                    change.put("identity", identity)
                    change.set<JsonNode>("fields", Json.mapper.valueToTree(changedFields))
                    if (old == null) change.putNull("before") else change.set<ObjectNode>("before", Graph.component(old))
                    if (new == null) change.putNull("after") else change.set<ObjectNode>("after", Graph.component(new))
                    if (old == null) change.putNull("beforePointer") else change.put("beforePointer", old.pointer)
                    if (new == null) change.putNull("afterPointer") else change.put("afterPointer", new.pointer)
                }
            }
        }

        val oldEdges = identityEdges(before.bom, ambiguous)
        val newEdges = identityEdges(after.bom, ambiguous)
        for (key in (oldEdges.keys + newEdges.keys).sortedWith(compareBy({ it.first }, { it.second }))) {
            val old = oldEdges[key]
            val new = newEdges[key]
            if (old == null || new == null) {
                edgeChanges++
                if (stored++ < Limits.MAX_CHANGES) {
                    val change = edges.addObject().put("kind", if (old == null) "ADDED" else "REMOVED")
                    change.put("fromIdentity", key.first).put("toIdentity", key.second)
                    if (old == null) change.putNull("beforePointer") else change.put("beforePointer", old.pointer)
                    if (new == null) change.putNull("afterPointer") else change.put("afterPointer", new.pointer)
                    if (old == null) change.putNull("before") else change.set<JsonNode>("before", Json.mapper.valueToTree(old))
                    if (new == null) change.putNull("after") else change.set<JsonNode>("after", Json.mapper.valueToTree(new))
                }
            }
        }

        val ambiguousRows = output.putArray("ambiguousIdentities")
        for (identity in ambiguous.sorted().take(50)) {
            val row = ambiguousRows.addObject().put("identity", identity)
            row.put("baselineCount", left[identity].orEmpty().size).put("candidateCount", right[identity].orEmpty().size)
            row.set<JsonNode>("baselinePointers", Json.mapper.valueToTree(left[identity].orEmpty().take(10).map { it.pointer }))
            row.set<JsonNode>("candidatePointers", Json.mapper.valueToTree(right[identity].orEmpty().take(10).map { it.pointer }))
            row.put("pointersTruncated", left[identity].orEmpty().size > 10 || right[identity].orEmpty().size > 10)
        }
        val changed = componentChanges + edgeChanges > 0
        output.put("status", if (reasons.isNotEmpty()) "INCONCLUSIVE" else if (changed) "CHANGED" else "UNCHANGED")
        output.put("hasChanges", changed)
        output.put("componentChangeCount", componentChanges).put("edgeChangeCount", edgeChanges)
        output.put("totalChanges", componentChanges + edgeChanges)
        output.put("storedChanges", components.size() + edges.size())
        output.put("truncated", stored > Limits.MAX_CHANGES)
        output.put("ambiguousIdentityCount", ambiguous.size).put("ambiguousIdentitiesTruncated", ambiguous.size > 50)
        output.set<JsonNode>("inconclusiveReasons", Json.mapper.valueToTree(reasons.toList()))
        output.set<JsonNode>("limitations", Json.mapper.valueToTree(listOf(
            "Comparison covers projected components, services and declared dependsOn relationships, not the full CycloneDX document.",
            "Package identities retain purl qualifiers and subpaths but omit package versions. Identities with multiple instances are not paired.",
            "UNCHANGED means this declared projection matches. It does not establish runtime compatibility, absence of vulnerabilities or equivalent build resolution.",
            "Unknown endpoints and edges touching ambiguous identities are excluded from edge matching and make the comparison inconclusive.",
            "Source pointers identify the original component objects or dependsOn entries. Values shown for components are a supported-field projection."
        )))
        return output
    }

    private fun snapshot(value: Snapshot): ObjectNode = Graph.summary(value.bom).apply {
        put("id", value.id).put("name", value.name).put("project", value.project)
    }

    private fun fields(before: Component, after: Component, left: Bom, right: Bom): List<String> = buildList {
        if (before.kind != after.kind) add("kind")
        if (before.type != after.type) add("type")
        if (before.group != after.group) add("group")
        if (before.name != after.name) add("name")
        if (before.version != after.version) add("version")
        if (canonicalPurl(before.purl) != canonicalPurl(after.purl)) add("purl")
        if ((before.ref == left.subjectRef) != (after.ref == right.subjectRef)) add("subject")
    }

    private fun canonicalPurl(value: String?): String? = value?.let { PackageURL(it).canonicalize() }

    private fun identityEdges(bom: Bom, ambiguous: Set<String>): Map<Pair<String, String>, Edge> {
        val nodes = bom.nodes.associateBy { it.ref }
        val result = linkedMapOf<Pair<String, String>, Edge>()
        for (edge in bom.edges.sortedBy { it.pointer }) {
            val from = nodes[edge.from] ?: continue
            val to = nodes[edge.to] ?: continue
            if (from.identity in ambiguous || to.identity in ambiguous) continue
            result.putIfAbsent(from.identity to to.identity, edge)
        }
        return result
    }
}
