package io.github.almeida96.bomtrail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

object Reports {
    fun render(report: ObjectNode, format: String): String = when (format) {
        "json" -> Json.pretty(report)
        "md" -> markdown(report)
        "html" -> html(report)
        else -> throw IllegalArgumentException("Format must be json, md or html")
    }

    private fun markdown(report: JsonNode): String = buildString {
        append("# BOMTrail\n\n")
        append("Report: ").append(md(report.path("kind").asText())).append("\n\n")
        if (report.has("status")) append("Status: **").append(md(report.path("status").asText())).append("**\n\n")
        if (report.path("kind").asText() == "comparison") {
            append("| Change | Component | Before | After |\n|---|---|---|---|\n")
            for (change in report.path("componentChanges")) {
                val node = if (change.path("after").isObject) change.path("after") else change.path("before")
                append("| ").append(md(change.path("kind").asText())).append(" | ").append(md(label(node)))
                    .append(" | ").append(md(version(change.path("before")))).append(" | ").append(md(version(change.path("after")))).append(" |\n")
            }
            append("\n").append(report.path("edgeChangeCount").asInt()).append(" declared dependency relationships changed.\n\n")
        }
        append("## Evidence\n\n````json\n")
        append(Json.pretty(report).replace("`", "\\u0060").replace("<", "\\u003c"))
        append("````\n\nResults describe the supplied SBOM projection. They do not establish runtime use, compatibility or exploitability.\n")
    }

    private fun html(report: JsonNode): String = buildString {
        append("""
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>BOMTrail — dependency review</title><style>
            *{box-sizing:border-box}body{margin:0;background:#f5f6f8;color:#1b2739;font:15px/1.6 system-ui,sans-serif}main{max-width:1180px;margin:auto;padding:38px 28px}header{background:#15263d;color:#f4f6fb;border-radius:12px;padding:28px 32px;position:relative}.brand{font:600 13px ui-monospace,monospace;letter-spacing:.13em;color:#d3e5bb}h1{font-size:37px;line-height:1.2;letter-spacing:-.03em;margin:16px 0 8px}header p{color:#c5cfde;margin:10px 0}.status{display:inline-block;background:#e8bf72;color:#392b15;padding:4px 10px;border-radius:4px;font:600 12px ui-monospace,monospace}.UNCHANGED{background:#c9e9d7;color:#16412e}.INCONCLUSIVE{background:#f3d9d1;color:#612d1e}h2{font-size:22px;margin:28px 0 12px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:22px 0}.metric{padding:14px 20px;background:white;border:1px solid #dae0e8;border-radius:8px}.metric strong{display:block;font-size:29px}.label,.muted{color:#5d6b7d;font-size:13px}.table{overflow:auto}table{width:100%;border-collapse:collapse;background:white}th,td{padding:13px 16px;text-align:left;border-bottom:1px solid #e0e5eb;vertical-align:top}th{font-size:12px;color:#526174;background:#edf0f5}td{overflow-wrap:anywhere}.tag{font:11px ui-monospace,monospace;display:inline-block;border:1px solid #c7d2e0;border-radius:4px;padding:2px 5px}.evidence{background:white;border:1px solid #dae0e8;border-radius:8px;margin:10px 0;padding:16px 20px;overflow-wrap:anywhere}.columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.columns>div{min-width:0}code{font:12px ui-monospace,monospace;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#eef2f6;padding:16px;max-height:460px;overflow:auto;font:12px/1.6 ui-monospace,monospace}summary{cursor:pointer;font-weight:550}details{margin:18px 0}footer{margin-top:28px;border-top:1px solid #d2dbe6;padding-top:16px;color:#5d6b7d;font-size:12px}.path{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.node{background:white;border:1px solid #c7d2e0;padding:10px;border-radius:5px}li{margin:7px 0}@media(max-width:650px){main{padding:18px 12px}header{padding:22px}.metrics,.columns{grid-template-columns:1fr}h1{font-size:29px}}
            </style></head><body><main><header><div class="brand">BOMTRAIL / DECLARED DEPENDENCIES</div>
        """.trimIndent())
        val comparison = report.path("kind").asText() == "comparison"
        append("<h1>").append(if (comparison) "Dependency review" else "Dependency evidence").append("</h1>")
        val status = report.path("status").asText("RECORDED")
        append("<span class=\"status ").append(esc(status)).append("\">").append(esc(status)).append("</span>")
        append("<p>Inspect the packages, relationships and source evidence declared in your SBOM.</p></header>")
        if (comparison) {
            append("<div class=metrics>")
            for ((key, title) in listOf("componentChangeCount" to "Component changes", "edgeChangeCount" to "Relationship changes", "ambiguousIdentityCount" to "Ambiguous identities")) {
                append("<div class=metric><span class=label>").append(title).append("</span><strong>").append(report.path(key).asInt()).append("</strong></div>")
            }
            append("</div><div class=columns>")
            for (side in listOf("baseline", "candidate")) {
                val snapshot = report.path(side)
                append("<div class=evidence><span class=label>").append(side.replaceFirstChar(Char::uppercase)).append("</span><h2>")
                    .append(esc(snapshot.path("name").asText())).append("</h2><p>").append(esc(snapshot.path("project").asText()))
                    .append(" · ").append(snapshot.path("nodes").asInt()).append(" components/services · ").append(snapshot.path("edges").asInt()).append(" relationships</p></div>")
            }
            append("</div><h2>Component changes</h2><div class=table><table><thead><tr><th>CHANGE</th><th>COMPONENT</th><th>BASELINE</th><th>CANDIDATE</th></tr></thead><tbody>")
            for (change in report.path("componentChanges")) {
                val node = if (change.path("after").isObject) change.path("after") else change.path("before")
                append("<tr><td><span class=tag>").append(esc(change.path("kind").asText())).append("</span></td><td>")
                    .append(esc(label(node)))
                if (!change.path("fields").isEmpty) append("<div class=muted>Changed: ").append(esc(change.path("fields").joinToString(", ") { it.asText() })).append("</div>")
                append("</td><td>").append(esc(version(change.path("before"))))
                    .append("</td><td>").append(esc(version(change.path("after")))).append("</td></tr>")
            }
            append("</tbody></table></div>")
            if (report.path("componentChanges").isEmpty) append("<p>No component changes were recorded in the displayed evidence.</p>")
            append("<h2>Relationship changes</h2>")
            for (change in report.path("edgeChanges")) {
                append("<div class=evidence><span class=tag>").append(esc(change.path("kind").asText())).append("</span><p><code>")
                    .append(esc(change.path("fromIdentity").asText())).append("</code> → <code>")
                    .append(esc(change.path("toIdentity").asText())).append("</code></p><span class=label>Source: </span><code>")
                    .append(esc(change.path(if (change.path("kind").asText() == "ADDED") "afterPointer" else "beforePointer").asText())).append("</code></div>")
            }
            if (report.path("truncated").asBoolean()) append("<p>Evidence is capped at 250 changes; the totals include undisplayed changes.</p>")
            if (!report.path("inconclusiveReasons").isEmpty) {
                append("<h2>Review required</h2><ul>")
                report.path("inconclusiveReasons").forEach { append("<li><code>").append(esc(it.asText())).append("</code></li>") }
                append("</ul>")
            }
        } else {
            append("<h2>").append(esc(report.path("kind").asText())).append("</h2>")
            if (report.path("kind").asText() == "path" && report.path("status").asText() == "FOUND") {
                append("<div class=path>")
                report.path("nodes").forEachIndexed { index, node ->
                    if (index > 0) append("<span>→</span>")
                    append("<div class=node>").append(esc(node.path("name").asText(node.path("ref").asText()))).append("</div>")
                }
                append("</div>")
            }
            append("<pre>").append(esc(Json.pretty(report))).append("</pre>")
        }
        append("<details><summary>Full structured evidence and source hashes</summary><pre>").append(esc(Json.pretty(report))).append("</pre></details>")
        append("<footer>BOMTrail 0.1.0 · Local report · Declared dependencies are not proof of runtime use, compatibility or exploitability.</footer></main></body></html>\n")
    }

    private fun label(node: JsonNode) = listOf(node.path("group").asText(""), node.path("name").asText("")).filter(String::isNotEmpty).joinToString("/")
    private fun version(node: JsonNode): String {
        if (!node.isObject) return "absent"
        val explicit = node.path("version").asText("")
        if (explicit.isNotBlank()) return explicit
        return runCatching { com.github.packageurl.PackageURL(node.path("purl").asText()).version }.getOrNull() ?: "unspecified"
    }
    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun md(value: String) = esc(value).replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[").replace("]", "\\]").replace("\n", " ").replace("\r", " ")
}
