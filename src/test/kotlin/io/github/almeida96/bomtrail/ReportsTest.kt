package io.github.almeida96.bomtrail

import org.junit.jupiter.api.Test
import kotlin.test.*

class ReportsTest {
    @Test fun reportTreatsSbomContentAsDataInHtmlAndMarkdown() {
        val payload = "</pre><script>alert('x')</script>\n````\n<img src=x>"
        val report = Json.mapper.createObjectNode().put("kind", "summary").put("value", payload)
        val html = Reports.render(report, "html")
        assertFalse(html.contains("<script>")); assertFalse(html.contains("<img src=x>"))
        assertTrue(html.contains("&lt;script&gt;")); assertTrue(html.contains("default-src 'none'"))
        val markdown = Reports.render(report, "md")
        assertFalse(markdown.contains("<script>")); assertTrue(markdown.contains("\\u0060\\u0060\\u0060\\u0060"))
        assertEquals(payload, Json.read(Reports.render(report, "json")).path("value").asText())
    }
}
