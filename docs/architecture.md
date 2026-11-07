# Architecture

The engine is Kotlin on Java 21. It has no model dependency. MCP and the CLI use the same parser, graph queries and comparison implementation.

1. `BomParser` reads bounded UTF-8 JSON and projects supported inventory/dependency fields. Every node and edge retains its source pointer. PURL parsing uses the package-url Java implementation.
2. `Store` persists source bytes, labels and SHA-256 in SQLite. Named snapshots are immutable. An existing name is accepted only for identical bytes and context. Read commands open a read-only SQLite connection; they do not migrate or initialize a database.
3. `Graph` indexes known edges by source and target. Path and impact queries use breadth-first traversal with visited sets, so cycles do not cause recursive expansion. Sorting determines which shortest witness is returned when multiple paths exist.
4. `Comparison` groups components by identity, leaves ambiguous groups unresolved, and matches relationships by their endpoint identities. Result counts include changes beyond the bounded displayed evidence.
5. `Reports` serializes the same evidence to JSON, Markdown or HTML. HTML embeds its styles, escapes input content and uses a restrictive content security policy. It has no scripts or external assets.
6. `McpServer` provides newline-framed stdio requests against one selected read-only store. It validates tool arguments and rejects oversized responses with an explicit error rather than returning partial JSON.

Inputs are limited to 16 MiB, 10,000 inventory nodes and 50,000 dependency edges. Traversal indexes are linear in nodes and edges; deterministic adjacency sorting adds sorting cost. Queries rebuild indexes from the selected immutable snapshot. No persistent graph cache or background indexing process is required.

The parser does not fetch schemas or resolve external references. The application does not invoke package managers, build tools or remote services. The optional CI interoperability check uses a separate CycloneDX generator to create test input; that generator is not part of the distribution.

Snapshot hashes cover the exact input bytes. Snapshot IDs also include the chosen name and project label. Readers verify stored metadata and content hashes before analysis. This detects accidental or inconsistent changes, not an attacker who can rewrite the entire database and its hashes.
