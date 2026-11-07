# BOMTrail 0.1.0

BOMTrail investigates declared software dependencies from CycloneDX SBOMs. It is a Kotlin command-line application and read-only MCP server for Java 21.

Download `bomtrail-0.1.0.zip`, verify it against `SHA256SUMS`, and extract it. Run `bin/bomtrail demo --dir demo-output` (Windows: `bin\bomtrail.bat demo --dir demo-output`), then open `demo-output/comparison.html`.

The release includes immutable SQLite snapshots, component search, shortest dependency paths, reverse dependents, release comparisons, and seven MCP tools. Reports include source pointers and coverage limits. The bundled examples use synthetic packages.

Supported input: a projected subset of CycloneDX JSON 1.5, 1.6 and 1.7. The application runs offline and does not execute builds, fetch packages or look up vulnerabilities. Declared dependency paths are not proof of runtime use or exploitability. Incomplete graphs and ambiguous identities produce inconclusive comparisons.

[Getting started](https://github.com/Almeida96/bomtrail/blob/v0.1.0/README.md) · [Analysis rules](https://github.com/Almeida96/bomtrail/blob/v0.1.0/docs/analysis.md) · [MCP setup](https://github.com/Almeida96/bomtrail/blob/v0.1.0/docs/mcp.md)
