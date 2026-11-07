# MCP access

Import snapshots with the CLI, then start a stdio server over that database:

```sh
bomtrail mcp --db /absolute/path/to/boms.db
```

The database must already exist. The server opens it read-only. Tool arguments select existing snapshot names or capture IDs and component references; they cannot select another database, import files, install dependencies or run the analyzed project.

## Client configuration

For clients using an `mcpServers` configuration:

```json
{
  "mcpServers": {
    "bomtrail": {
      "command": "/absolute/path/to/bomtrail/bin/bomtrail",
      "args": ["mcp", "--db", "/absolute/path/to/boms.db"]
    }
  }
}
```

On Windows, a direct Java invocation avoids MCP clients that cannot launch batch files:

```json
{
  "mcpServers": {
    "bomtrail": {
      "command": "java",
      "args": ["-cp", "C:/tools/bomtrail/lib/*", "io.github.almeida96.bomtrail.MainKt", "mcp", "--db", "C:/snapshots/boms.db"]
    }
  }
}
```

Adapt the enclosing configuration to your client. Java 21 or later must be available; use its absolute executable path when the client does not inherit `PATH`.

## Tools

| Tool | Required arguments | Result |
|---|---|---|
| `list_snapshots` | None | Existing snapshots with source hashes; optional `offset`, `limit`. |
| `summarize_snapshot` | `snapshot` | Counts, declaration coverage, source hash and bounded warnings. |
| `list_components` | `snapshot` | Searchable inventory; optional `query`, `offset`, `limit`. |
| `inspect_component` | `snapshot`, `ref` | Component fields and bounded direct relationship evidence. |
| `trace_dependency` | `snapshot`, `from`, `to` | One shortest declared path; optional `maxDepth`. |
| `find_dependents` | `snapshot`, `ref` | Reverse dependencies and bounded witness paths; optional `offset`, `limit`. |
| `compare_snapshots` | `baseline`, `candidate` | Observed changes, source pointers, truncation and comparability limits. |

Pagination limits are 1–50, with a default of 25. Follow `nextOffset` when returned. `maxDepth` is 1–128, with a default of 64. Tool arguments reject unknown fields and non-integer pagination values. Use the exact `ref` returned by the inventory; display names are not unique identifiers.

Every tool returns a JSON object in `structuredContent` and the same object serialized as a text content block. The declared output schema is the generic object type; field meanings are documented in the [analysis guide](analysis.md). Tools advertise read-only, idempotent and closed-domain annotations. Those annotations describe these tool implementations; they are not a general MCP permission system.

## Investigation sequence

1. List snapshots and select the relevant project/release.
2. Read its summary. Missing declarations and unresolved references affect conclusions.
3. Search for the package, then inspect its exact reference and source pointer.
4. Trace from the application subject to that reference. Inspect reverse dependents when reviewing an upgrade.
5. Compare captures from the same project. Review `INCONCLUSIVE`, ambiguity and truncation explicitly.

For the bundled synthetic demo, `trace_dependency` with `snapshot: "baseline"`, `from: "app"`, and `to: "codec"` returns a two-edge path. `find_dependents` for `codec` reports three declared dependents. These are fixture observations, not production measurements.

## Protocol boundary

The endpoint supports MCP `2025-11-25` and `2025-06-18` with newline-delimited UTF-8 JSON-RPC. It requires `initialize`, followed by `notifications/initialized`, before tool requests. It supports `ping`, `tools/list` and `tools/call`; it does not advertise resources, prompts, tasks, sampling, elicitation, HTTP transport or list-change notifications.

Malformed JSON/UTF-8 receives a parse error. Unknown tools, invalid wrappers and invalid tool arguments receive JSON-RPC `-32602`. A valid request whose snapshot/component cannot be read returns a tool result with `isError: true` and `LOCAL_DATA_ERROR`. These errors do not contain filesystem paths or raw database error text.

Input and output frames are limited to 1 MiB. The reader discards an oversized input frame through its newline and continues with the next frame. An oversized tool response becomes `isError: true` with `OUTPUT_LIMIT`; use a narrower query or the CLI. An output limit never becomes an empty successful result.

The server writes only protocol messages to stdout. It does not make network requests. Component names, metadata and descriptions in imported files remain untrusted data. A declared dependency path does not prove runtime execution or exploitability, and a missing path does not prove independence.

The integration check in `checks/mcp_smoke.py` uses the official Python MCP SDK as a client against the installed distribution and verifies that the database bytes remain unchanged.
