package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.JsonNode
import com.github.packageurl.PackageURL
import java.util.Collections

/** Projects inventory and declared dependsOn relationships; it does not validate the entire SBOM schema. */
object BomParser {
    fun parse(raw: ByteArray): Bom {
        require(raw.size <= Limits.MAX_INPUT_BYTES) { "SBOM exceeds 16 MiB" }
        val document = Json.read(Json.utf8(raw).removePrefix("\uFEFF"))
        require(document.isObject) { "SBOM must be a JSON object" }
        require(text(document, "bomFormat", "", 32) == "CycloneDX") { "bomFormat must be CycloneDX" }
        val specVersion = text(document, "specVersion", "", 16)
        require(specVersion in setOf("1.5", "1.6", "1.7")) { "Supported CycloneDX versions are 1.5, 1.6 and 1.7" }
        val collector = Collector()
        val metadata = document.get("metadata")
        if (metadata != null) require(metadata.isObject) { "/metadata must be an object" }
        val subject = metadata?.get("component")
        if (subject != null) collector.component(subject, "/metadata/component", "component", 1)
        array(document, "components", "")?.forEachIndexed { index, node ->
            collector.component(node, "/components/$index", "component", 1)
        }
        array(document, "services", "")?.forEachIndexed { index, node ->
            collector.component(node, "/services/$index", "service", 1)
        }

        val records = mutableListOf<DependencyRecord>()
        val seenRecords = mutableSetOf<String>()
        val reserved = collector.explicitRefs.toMutableSet()
        var edgeCount = 0
        var providedCount = 0
        array(document, "dependencies", "")?.forEachIndexed { index, record ->
            val pointer = "/dependencies/$index"
            require(index < Limits.MAX_NODES) { "SBOM exceeds ${Limits.MAX_NODES} dependency records" }
            require(record.isObject) { "$pointer must be an object" }
            val ref = text(record, "ref", pointer, 1024)
            require(seenRecords.add(ref)) { "Duplicate dependency record ref at $pointer/ref" }
            reserved.add(ref)
            val targets = array(record, "dependsOn", pointer)?.mapIndexed { targetIndex, target ->
                val targetPointer = "$pointer/dependsOn/$targetIndex"
                val value = scalar(target, targetPointer, 1024)
                reserved.add(value)
                require(++edgeCount <= Limits.MAX_EDGES) { "SBOM exceeds ${Limits.MAX_EDGES} dependency edges" }
                value
            }
            if (targets != null) require(targets.size == targets.toSet().size) {
                "Duplicate dependsOn reference at $pointer/dependsOn"
            }
            val provided = array(record, "provides", pointer)
            provided?.forEachIndexed { providedIndex, item ->
                require(++providedCount <= Limits.MAX_EDGES) { "SBOM exceeds ${Limits.MAX_EDGES} provides references" }
                reserved.add(scalar(item, "$pointer/provides/$providedIndex", 1024))
            }
            if (provided != null && !provided.isEmpty) collector.warn(
                "PROVIDES_IGNORED", "provides relationships are not dependency edges in this projection.", "$pointer/provides", ref
            )
            records.add(DependencyRecord(ref, targets, pointer))
        }
        val compositions = array(document, "compositions", "")
        if (compositions != null && !compositions.isEmpty) {
            compositions.forEachIndexed { index, item -> require(item.isObject) { "/compositions/$index must be an object" } }
            collector.warn("COMPOSITIONS_NOT_EVALUATED", "Composition completeness assertions are not evaluated.", "/compositions")
        }

        // Reserve external and unresolved references too: a generated ID must never make an input edge resolve.
        val nodes = collector.drafts.map { draft ->
            val ref = draft.ref ?: run {
                val base = "urn:bomtrail:synthetic:" + Json.hash(draft.pointer)
                var candidate = base
                var suffix = 0
                while (!reserved.add(candidate)) candidate = "$base:${++suffix}"
                collector.warn("MISSING_REF", "No bom-ref was supplied; this ID is local to the imported document.", draft.pointer, candidate)
                candidate
            }
            val identity = draft.purl?.let { original ->
                val parsed = packageUrl(original, draft.pointer)
                if (draft.version != null && parsed.version != null && draft.version != parsed.version) collector.warn(
                    "PURL_VERSION_MISMATCH", "Component version differs from the PURL version.", "${draft.pointer}/purl", ref
                )
                "purl:" + PackageURL(parsed.type, parsed.namespace, parsed.name, null,
                    parsed.qualifiers?.let { java.util.TreeMap(it) }, parsed.subpath).canonicalize()
            } ?: "name:" + Json.text(listOf(draft.kind, draft.type, draft.group, draft.name))
            Component(ref, draft.kind, draft.type, draft.group, draft.name, draft.version, draft.purl,
                identity, draft.pointer, draft.ref == null)
        }
        val known = nodes.mapTo(mutableSetOf()) { it.ref }
        val declared = linkedSetOf<String>()
        val edges = mutableListOf<Edge>()
        records.forEach { record ->
            if (record.ref !in known) collector.warn(
                "UNRESOLVED_REF", "Dependency source does not identify an imported component or service.", "${record.pointer}/ref", record.ref
            )
            // CycloneDX's official generator represents a leaf as {"ref":"..."}.
            // An existing empty record declares a leaf; absence of the record remains unknown.
            declared.add(record.ref)
            record.targets.orEmpty().forEachIndexed { index, target ->
                    val pointer = "${record.pointer}/dependsOn/$index"
                    edges.add(Edge(record.ref, target, pointer))
                    if (target !in known) collector.warn(
                        "UNRESOLVED_REF", "Dependency target does not identify an imported component or service.", pointer, target
                    )
            }
        }
        nodes.forEach { node ->
            if (node.ref !in declared) collector.warn(
                "MISSING_DECLARATION", "No dependency record declares this node's dependencies.", node.pointer, node.ref
            )
        }
        return Bom(specVersion, nodes.firstOrNull { it.pointer == "/metadata/component" }?.ref,
            Collections.unmodifiableList(nodes), Collections.unmodifiableList(edges), Collections.unmodifiableSet(declared),
            Collections.unmodifiableList(collector.warnings), collector.warningCount, Json.hash(raw))
    }

    private data class DependencyRecord(val ref: String, val targets: List<String>?, val pointer: String)
    private data class Draft(val ref: String?, val kind: String, val type: String, val group: String?,
                             val name: String, val version: String?, val purl: String?, val pointer: String)
    private class Collector {
        val drafts = mutableListOf<Draft>()
        val explicitRefs = mutableSetOf<String>()
        val warnings = mutableListOf<Finding>()
        var warningCount = 0

        fun warn(code: String, message: String, pointer: String, ref: String? = null) {
            warningCount++
            if (warnings.size < Limits.MAX_WARNINGS) warnings.add(Finding(code, message, pointer, ref))
        }

        fun component(node: JsonNode, pointer: String, kind: String, depth: Int) {
            require(depth <= 32) { "Component or service nesting exceeds 32 levels" }
            require(node.isObject) { "$pointer must be an object" }
            require(drafts.size < Limits.MAX_NODES) { "SBOM exceeds ${Limits.MAX_NODES} inventory nodes" }
            val ref = optional(node, "bom-ref", pointer, 1024)
            if (ref != null) require(explicitRefs.add(ref)) { "Duplicate bom-ref at $pointer/bom-ref" }
            val type = if (kind == "service") "service" else text(node, "type", pointer, 128)
            val group = optional(node, "group", pointer, 512, allowEmpty = true)
            val name = text(node, "name", pointer, 512)
            val version = optional(node, "version", pointer, 256, allowEmpty = true)
            val purl = if (kind == "component") optional(node, "purl", pointer, 4096)?.also { packageUrl(it, pointer) } else null
            drafts.add(Draft(ref, kind, type, group, name, version, purl, pointer))
            val childField = if (kind == "service") "services" else "components"
            array(node, childField, pointer)?.forEachIndexed { index, child ->
                component(child, "$pointer/$childField/$index", kind, depth + 1)
            }
        }
    }

    private fun packageUrl(value: String, pointer: String): PackageURL = try {
        PackageURL(value)
    } catch (_: Exception) { throw IllegalArgumentException("Invalid package URL at $pointer/purl") }

    private fun array(parent: JsonNode, field: String, pointer: String): JsonNode? = parent.get(field)?.also {
        require(it.isArray) { "$pointer/$field must be an array" }
    }
    private fun text(parent: JsonNode, field: String, pointer: String, max: Int): String =
        scalar(requireNotNull(parent.get(field)) { "Missing $pointer/$field" }, "$pointer/$field", max)
    private fun optional(parent: JsonNode, field: String, pointer: String, max: Int, allowEmpty: Boolean = false): String? =
        parent.get(field)?.let { scalar(it, "$pointer/$field", max, allowEmpty) }
    private fun scalar(value: JsonNode, pointer: String, max: Int, allowEmpty: Boolean = false): String {
        require(value.isTextual && value.textValue().length <= max && (allowEmpty || value.textValue().isNotBlank())) {
            "$pointer must be ${if (allowEmpty) "" else "nonblank "}text of at most $max characters"
        }
        require(value.textValue().none(Char::isISOControl)) { "$pointer cannot contain control characters" }
        return value.textValue()
    }
}
