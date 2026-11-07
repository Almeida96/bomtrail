# Contributing

Use Java 21. Run `./gradlew test installDist distZip` or `gradlew.bat` on Windows. Dependencies and wrapper versions are pinned in source control.

Run the installed CLI and MCP checks with Python 3.12:

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -r checks/requirements.txt
python checks/cli_smoke.py --install-dir build/install/bomtrail
python checks/mcp_smoke.py --install-dir build/install/bomtrail
```

For the independent SBOM generator check:

```sh
python -m pip install -r checks/integration-requirements.txt
python checks/sbom_integration.py --install-dir build/install/bomtrail
```

On Windows, activate `.venv\Scripts\Activate.ps1`. Check scripts use temporary directories and synthetic or generated test inputs. They do not need production credentials.

Changes to matching, graph coverage or exit codes need a regression test showing the affected behavior. Preserve source pointers and explicit truncation. Do not infer a dependency from component nesting or silently match ambiguous package identities. Update the format guide when a previously ignored CycloneDX field starts influencing a result.

For a bug report, include a minimal sanitized SBOM, the command, expected output, and Java/BOMTrail versions. Avoid posting real supplier details or private package names unless they are intended to be public.
