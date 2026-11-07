package io.github.almeida96.bomtrail

object Limits {
    const val VERSION = "0.1.0"
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    const val MAX_NODES = 10_000
    const val MAX_EDGES = 50_000
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val MAX_WARNINGS = 100
    const val MAX_CHANGES = 250
}

data class Component(
    val ref: String,
    val kind: String,
    val type: String,
    val group: String?,
    val name: String,
    val version: String?,
    val purl: String?,
    val identity: String,
    val pointer: String,
    val syntheticRef: Boolean = false
)
data class Edge(val from: String, val to: String, val pointer: String)
data class Finding(val code: String, val message: String, val pointer: String, val ref: String? = null)
data class Bom(
    val specVersion: String,
    val subjectRef: String?,
    val nodes: List<Component>,
    val edges: List<Edge>,
    val declaredRefs: Set<String>,
    val warnings: List<Finding>,
    val warningCount: Int,
    val sourceSha256: String
)
data class Snapshot(val id: String, val name: String, val project: String, val bom: Bom)
