"""Exercise the installed read-only MCP endpoint with the official Python SDK.

The CLI creates synthetic fixtures in a temporary directory. This check requires
Python 3.12, mcp==1.26.0 and Java 21; it needs no model account or network service.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import timedelta
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.shared.exceptions import McpError


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def cli(command: list[str], *args: str, expected: int = 0) -> dict:
    result = subprocess.run([*command, *args], capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    require(result.returncode == expected,
            f"CLI exited {result.returncode}, expected {expected}: {result.stderr}")
    return json.loads(result.stdout)


async def invalid(session: ClientSession, name: str, arguments: dict) -> None:
    try:
        await session.call_tool(name, arguments)
    except McpError as failure:
        require(failure.error.code == -32602, f"Unexpected protocol error: {failure.error}")
    else:
        raise AssertionError("Expected a protocol error for invalid tool arguments")


async def check(command: list[str], database: Path) -> None:
    parameters = StdioServerParameters(command=command[0], args=[*command[1:], "mcp", "--db", str(database)])
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=20)) as session:
            initialized = await session.initialize()
            require(initialized.protocolVersion == "2025-11-25", "Unexpected negotiated protocol")
            require(initialized.serverInfo.name == "bomtrail", "Unexpected server identity")
            tools = (await session.list_tools()).tools
            names = {tool.name for tool in tools}
            require(names == {"list_snapshots", "summarize_snapshot", "list_components", "inspect_component",
                              "trace_dependency", "find_dependents", "compare_snapshots"}, "Unexpected tool inventory")
            definitions = {tool.name: tool for tool in tools}
            for tool in tools:
                require(tool.annotations is not None, "Missing tool annotations")
                require(tool.annotations.readOnlyHint is True and tool.annotations.idempotentHint is True,
                        "Read-only tool annotations differ")
                require(tool.annotations.destructiveHint is False and tool.annotations.openWorldHint is False,
                        "Unexpected effect annotation")
                require(tool.inputSchema.get("additionalProperties") is False, "Tool accepts undeclared fields")
                require(tool.outputSchema is not None, "Missing output schema")
                jsonschema.Draft202012Validator.check_schema(tool.inputSchema)
                jsonschema.Draft202012Validator.check_schema(tool.outputSchema)

            async def call(name: str, args: dict) -> dict:
                result = await session.call_tool(name, args)
                require(not result.isError, f"Tool returned an error: {result}")
                require(isinstance(result.structuredContent, dict), "Missing structured evidence")
                require(len(result.content) == 1 and result.content[0].type == "text", "Expected JSON text fallback")
                require(json.loads(result.content[0].text) == result.structuredContent, "Text and structured evidence differ")
                jsonschema.validate(result.structuredContent, definitions[name].outputSchema)
                return result.structuredContent

            first = await call("list_snapshots", {"limit": 1})
            require(len(first["snapshots"]) == 1 and first["nextOffset"] == 1, "Snapshot pagination failed")
            second = await call("list_snapshots", {"limit": 1, "offset": first["nextOffset"]})
            require(len(second["snapshots"]) == 1, "Second snapshot page missing")
            require({first["snapshots"][0]["name"], second["snapshots"][0]["name"]} == {"baseline", "candidate"},
                    "Unexpected snapshot names")
            summary = await call("summarize_snapshot", {"snapshot": "baseline"})
            require(summary["nodes"] == 5 and summary["missingDeclarations"] == 0, "Unexpected graph coverage")
            require(summary["snapshot"]["project"] == "shop-app", "Missing source context")
            inventory = await call("list_components", {"snapshot": "baseline", "query": "codec", "limit": 1})
            require(len(inventory["components"]) == 1 and inventory["components"][0]["ref"] == "codec", "Search failed")
            component = await call("inspect_component", {"snapshot": "baseline", "ref": "codec"})
            require(component["component"]["version"] == "2.1.0", "Unexpected component version")
            require(component["component"]["sourcePointer"].startswith("/components/"), "Missing source pointer")
            path = await call("trace_dependency", {"snapshot": "baseline", "from": "app", "to": "codec"})
            require(path["status"] == "FOUND" and path["distance"] == 2, "Expected a two-edge dependency path")
            require(len(path["edges"]) == 2, "Missing path evidence")
            impact = await call("find_dependents", {"snapshot": "baseline", "ref": "codec", "limit": 2})
            require(impact["total"] == 3 and len(impact["dependents"]) == 2 and impact["nextOffset"] == 2,
                    "Reverse dependency pagination failed")
            rest = await call("find_dependents", {"snapshot": "baseline", "ref": "codec", "offset": 2, "limit": 2})
            require(len(rest["dependents"]) == 1, "Missing final dependent")
            same = await call("compare_snapshots", {"baseline": "baseline", "candidate": "baseline"})
            require(same["status"] == "UNCHANGED", "Self-comparison should be unchanged")
            changed = await call("compare_snapshots", {"baseline": "baseline", "candidate": "candidate"})
            require(changed["status"] == "CHANGED", "Fixture upgrade should change the graph")

            for name, arguments in [
                ("summarize_snapshot", {}),
                ("list_snapshots", {"limit": 1.5}),
                ("list_components", {"snapshot": "baseline", "file": "another.json"}),
                ("trace_dependency", {"snapshot": "baseline", "from": "app", "to": "codec", "maxDepth": 129}),
                ("delete_snapshot", {"snapshot": "baseline"}),
            ]:
                await invalid(session, name, arguments)
            missing = await session.call_tool("summarize_snapshot", {"snapshot": "missing"})
            require(missing.isError and missing.structuredContent["code"] == "LOCAL_DATA_ERROR", "Missing input should be a tool error")
            await session.send_ping()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-dir", required=True, type=Path)
    installation = parser.parse_args().install_dir.resolve()
    java = shutil.which("java")
    require(java is not None, "Java must be available on PATH")
    jars = sorted((installation / "lib").glob("*.jar"))
    require(bool(jars), "Installed distribution has no libraries")
    command = [java, "-cp", os.pathsep.join(map(str, jars)), "io.github.almeida96.bomtrail.MainKt"]
    with tempfile.TemporaryDirectory(prefix="bomtrail-mcp-") as temporary:
        directory = Path(temporary) / "demo"
        cli(command, "demo", "--dir", str(directory))
        database = directory / "boms.db"
        before = hashlib.sha256(database.read_bytes()).hexdigest()
        asyncio.run(check(command, database))
        require(hashlib.sha256(database.read_bytes()).hexdigest() == before, "Read-only MCP changed the database")
        require(not database.with_name(database.name + "-wal").exists(), "Read-only MCP left a WAL file")
    print(f"Official MCP SDK {importlib.metadata.version('mcp')}: seven read-only tools, evidence, pagination, comparison and invalid-call checks passed.")


if __name__ == "__main__":
    main()
