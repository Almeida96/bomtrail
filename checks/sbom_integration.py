"""Generate and query a real installed-environment SBOM with CycloneDX's Python tool.

Install checks/integration-requirements.txt in a disposable virtual environment.
The generator inspects that environment; no generated SBOM is committed or uploaded.
"""

from __future__ import annotations

import argparse
from collections import defaultdict, deque
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def invoke(command: list[str], expected: tuple[int, ...] = (0,), timeout: int = 60) -> subprocess.CompletedProcess:
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    require(result.returncode in expected,
            f"Process exited {result.returncode}, expected {expected}: {result.stderr}\n{result.stdout}")
    return result


def pointer(document, path: str):
    current = document
    for part in path.removeprefix("/").split("/"):
        token = part.replace("~1", "/").replace("~0", "~")
        current = current[int(token)] if isinstance(current, list) else current[token]
    return current


def nodes_in(document: dict) -> dict[str, dict]:
    found = {}

    def visit(node: dict, child_field: str) -> None:
        ref = node["bom-ref"]
        require(ref not in found, "Generator emitted a duplicate reference")
        found[ref] = node
        for child in node.get(child_field, []):
            visit(child, child_field)

    subject = document.get("metadata", {}).get("component")
    if subject:
        visit(subject, "components")
    for node in document.get("components", []):
        visit(node, "components")
    for node in document.get("services", []):
        visit(node, "services")
    return found


def distances(adjacency: dict[str, set[str]], start: str) -> dict[str, int]:
    result = {start: 0}
    queue = deque([start])
    while queue:
        current = queue.popleft()
        for target in sorted(adjacency[current]):
            if target not in result:
                result[target] = result[current] + 1
                queue.append(target)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-dir", type=Path, required=True)
    installation = parser.parse_args().install_dir.resolve()
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    require(java is not None, "Java 21 must be available")
    jars = sorted((installation / "lib").glob("*.jar"))
    require(bool(jars), "Build the installed distribution before running this check")
    cli = [str(java), "-cp", os.pathsep.join(map(str, jars)), "io.github.almeida96.bomtrail.MainKt"]
    generator_version = importlib.metadata.version("cyclonedx-bom")
    require(generator_version == "8.0.1", "Install the pinned generator from integration-requirements.txt")

    with tempfile.TemporaryDirectory(prefix="bomtrail-cyclonedx-") as temporary:
        root = Path(temporary)
        project = root / "pyproject.toml"
        project.write_text('[project]\nname = "bomtrail-integration-fixture"\nversion = "1.0.0"\n'
                           'dependencies = ["cyclonedx-bom==8.0.1"]\n', encoding="utf-8")
        sbom = root / "environment.json"
        invoke([sys.executable, "-m", "cyclonedx_py", "environment", sys.executable,
                "--spec-version", "1.6", "--output-format", "JSON", "--output-reproducible",
                "--validate", "--pyproject", str(project), "--output-file", str(sbom)], timeout=120)
        raw = sbom.read_bytes()
        source = json.loads(raw)
        require(source["bomFormat"] == "CycloneDX" and source["specVersion"] == "1.6", "Wrong generator format")
        nodes = nodes_in(source)
        edges = {(record["ref"], target) for record in source.get("dependencies", []) for target in record.get("dependsOn", [])}
        known_edges = {(a, b) for a, b in edges if a in nodes and b in nodes}
        require(len(nodes) >= 10 and len(known_edges) >= 10, "Expected a nontrivial real dependency inventory")
        database = root / "snapshots.db"

        def query(*args: str, expected: tuple[int, ...] = (0,)) -> tuple[dict, int]:
            result = invoke([*cli, *args], expected)
            return json.loads(result.stdout), result.returncode

        imported, _ = query("import", "--db", str(database), "--file", str(sbom), "--name", "generated", "--project", "python-environment")
        require(imported["nodes"] == len(nodes), "Imported inventory count differs from the generator")
        require(imported["edges"] == len(edges), "Imported edge count differs from the generator")
        require(imported["knownEdges"] == len(known_edges), "Resolved edge count differs from original references")
        require(imported["graphDeclarationsComplete"] is True, "Official generated graph declarations should be complete")
        require(imported["sourceSha256"] == hashlib.sha256(raw).hexdigest(), "Source byte checksum changed")
        repeated, _ = query("import", "--db", str(database), "--file", str(sbom), "--name", "generated", "--project", "python-environment")
        require(repeated["snapshot"]["id"] == imported["snapshot"]["id"], "Exact import was not idempotent")

        inventory = {}
        offset = 0
        while True:
            page, _ = query("components", "--db", str(database), "--snapshot", "generated", "--offset", str(offset), "--limit", "10")
            for item in page["components"]:
                require(item["ref"] not in inventory, "Pagination repeated a node")
                require(pointer(source, item["sourcePointer"])["bom-ref"] == item["ref"], "Inventory pointer does not resolve")
                inventory[item["ref"]] = item
            if page["nextOffset"] is None:
                break
            offset = page["nextOffset"]
        require(set(inventory) == set(nodes), "Pagination missed generated inventory nodes")
        generator = next(item for item in inventory.values() if item["name"] == "cyclonedx-bom")
        require(generator["identity"] == "purl:pkg:pypi/cyclonedx-bom", "PURL identity differs from the generator package")
        require(generator["version"] == generator_version, "Generated package version was lost")
        search, _ = query("components", "--db", str(database), "--snapshot", "generated", "--query", "cyclonedx-bom")
        require(any(item["ref"] == generator["ref"] for item in search["components"]), "Package search missed a known dependency")

        outgoing, incoming = defaultdict(set), defaultdict(set)
        for before, after in known_edges:
            outgoing[before].add(after)
            incoming[after].add(before)
        start = source["metadata"]["component"]["bom-ref"]
        expected_paths = distances(outgoing, start)
        candidates = [ref for ref, distance in expected_paths.items() if 2 <= distance <= 8]
        require(bool(candidates), "Expected at least one transitive dependency path")
        target = sorted(candidates, key=lambda ref: (-expected_paths[ref], ref))[0]
        path, _ = query("path", "--db", str(database), "--snapshot", "generated", "--from", start, "--to", target)
        require(path["status"] == "FOUND" and path["distance"] == expected_paths[target], "Shortest path differs from generated graph")
        cursor = start
        for edge in path["edges"]:
            require(edge["from"] == cursor and (edge["from"], edge["to"]) in known_edges, "Path includes a fabricated or disconnected edge")
            require(pointer(source, edge["sourcePointer"]) == edge["to"], "Edge source pointer does not resolve")
            cursor = edge["to"]
        require(cursor == target, "Path did not reach the target")
        impacted = distances(incoming, target)
        impact, _ = query("impact", "--db", str(database), "--snapshot", "generated", "--ref", target)
        require(impact["total"] == len(impacted) - 1, "Reverse dependency count differs from the generated graph")
        for dependent in impact["dependents"]:
            require(dependent["distance"] == impacted[dependent["component"]["ref"]], "Reverse dependency distance differs")

        query("import", "--db", str(database), "--file", str(sbom), "--name", "same-data", "--project", "python-environment")
        comparison, code = query("compare", "--db", str(database), "--baseline", "generated", "--candidate", "same-data")
        require(comparison["hasChanges"] is False and comparison["totalChanges"] == 0, "Identical generated inventories produced changes")
        require(code == 0 and comparison["status"] == "UNCHANGED", "Comparison status and exit code disagree")
        print(json.dumps({"generator": f"cyclonedx-bom {generator_version}", "specVersion": "1.6",
                          "nodes": len(nodes), "edges": len(edges), "pathLength": path["distance"],
                          "reverseDependents": impact["total"], "identicalComparison": comparison["status"],
                          "result": "passed"}))


if __name__ == "__main__":
    main()
