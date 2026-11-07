# Graph queries and comparison rules

BOMTrail analyzes the declared `dependsOn` relationships in an imported CycloneDX document. It does not resolve a build, inspect source code, scan vulnerabilities or observe runtime calls. Component nesting and service nesting describe inventory structure; neither creates a dependency edge.

## Explore one snapshot

The inventory searches references, names, groups and PURLs using a case-insensitive substring. Results are sorted by reference and paginated. The default page size is 25, the maximum is 50, and `nextOffset` is null on the final page.

Component inspection reports incoming and outgoing declarations, including unresolved references. Each direction shows at most 50 edges and reports its full count and truncation flag. A dependency declaration is `DECLARED` when the document contains a dependency record for that component. An empty or omitted `dependsOn` array declares a leaf. A missing record is `UNKNOWN`.

Every component projection has a `sourcePointer` to the original component or service object. Each edge points to the corresponding `dependsOn` array entry. These pointers use JSON Pointer syntax and apply to the original source bytes identified by `sourceSha256`.

## Trace a dependency

A trace uses breadth-first search and returns one shortest path through known endpoints. Outgoing edges are ordered by target reference, then source pointer, making the selected witness deterministic. Cycles do not cause repeated visits.

| Status | Meaning |
|---|---|
| `FOUND` | A path exists in the declared projection. `distance` is its edge count. |
| `NOT_OBSERVED` | The known reachable graph was exhausted without finding the target. |
| `DEPTH_LIMIT` | Unexplored nodes remain beyond the requested depth. |

The default maximum depth is 64; accepted values are 1–128. A trace from a component to itself has distance zero. Both endpoints must identify known components or services. A missing endpoint is an input error, not a negative finding.

`NOT_OBSERVED` does not establish runtime independence. An incomplete SBOM may omit relationships, and a declared relationship does not prove execution. The report includes `graphDeclarationsComplete` and `coverageNotes` alongside the result.

## Inspect impact

Impact traverses the graph in reverse. It returns every known component or service that can reach the selected reference, counted once. The target itself is excluded, including in cycles and self-loops. Results are ordered by shortest distance, then reference.

The traversal is complete within the bounded imported graph; the displayed results are paginated. Each row contains a distance and a witness toward the selected dependency. Witnesses share a budget of 64 edges per page to avoid repeating large chains. `witness.truncated`, `displayedEdges`, `totalEdges` and `nextRef` make partial evidence explicit. Run an individual trace for a selected dependent when its witness is truncated.

This is declared dependency reachability. It is not a vulnerability reachability or exploitability assessment.

## Compare snapshots

Comparison first groups components by identity:

- With a PURL, identity is its canonical package URL without the version. Type, namespace, name, qualifiers and subpath remain significant.
- Without a PURL, identity is the tuple of kind, component type, group and name.
- An identity occurring more than once in either snapshot is ambiguous. BOMTrail reports the group and does not choose a pairing or compare edges touching it.

For unique identities, comparison checks kind, type, group, name, explicit version, canonical full PURL, and whether the component is the SBOM subject. A version that appears only in the PURL still counts. PURL qualifier ordering alone does not produce a change. Snapshot-local `bom-ref` values, input ordering and source pointers do not define package identity.

Edges match by the identities of both endpoints. An endpoint's changed local reference therefore does not create a false addition or removal. Unknown endpoints and ambiguous identities cannot participate in reliable edge matching.

| Status | Meaning |
|---|---|
| `UNCHANGED` | The supported projection matches, and no recorded coverage or identity blocker was found. |
| `CHANGED` | At least one supported component field or dependency edge changed, with no coverage or identity blocker. |
| `INCONCLUSIVE` | Project labels differ, package identity is ambiguous, or the available projection has a coverage caveat. |

An inconclusive comparison can still contain observed changes. Check `hasChanges`, the counts and `inconclusiveReasons`; a lack of stored differences does not override an inconclusive status.

Coverage caveats include an empty inventory, missing dependency declarations, unresolved references, synthetic references, unassessed composition assertions, conflicts between explicit and PURL versions, and a truncated parser-warning list. `provides` relationships alone do not block comparison because they belong to a separate relationship type; they are not included in this projection.

The report includes component and edge change counts and up to 250 stored changes across both lists. Counts include all observed changes. `truncated` means the stored evidence is partial. Ambiguous identity groups are capped at 50, with up to 10 source pointers per side and explicit truncation flags.

Component values in the report are supported-field projections. Their before/after pointers identify the original component objects, which may contain additional fields. Edge pointers identify the relevant original dependency array entries. Baseline and candidate summaries retain their own source hashes and context labels.

`UNCHANGED` is not proof of compatible behavior, equivalent dependency resolution, a complete software inventory or the absence of vulnerabilities. Compositions are not evaluated; even an assertion of completeness requires review under this release's supported scope.
