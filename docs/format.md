# Supported SBOM projection

BOMTrail reads UTF-8 CycloneDX JSON documents with `bomFormat: "CycloneDX"` and `specVersion` equal to `1.5`, `1.6` or `1.7`. It projects inventory and declared dependency relationships into a bounded graph. It is not a complete CycloneDX schema validator, vulnerability scanner or build-system resolver.

The projection follows the [CycloneDX dependency model](https://cyclonedx.org/docs/1.7/json/#dependencies). An absent dependency record is unknown. An existing record with no `dependsOn` field or with `dependsOn: []` declares a leaf in the supplied document. The [official CycloneDX Python generator](https://cyclonedx-bom-tool.readthedocs.io/en/stable/usage.html) uses the first form for leaf packages; the integration check exercises its output. Neither form establishes runtime reachability or proves that the SBOM itself is complete.

## Inventory

The importer reads:

- `metadata.component`, including its nested `components`.
- Top-level `components`, including their nested `components`.
- Top-level `services`, including their nested `services`.

Containment does not create dependency edges. Inventory nested under unrelated sections, such as build-tool metadata, is outside this projection.

Each component requires a nonblank `type` and `name`. Services require a nonblank `name`; their projected type and kind are both `service`. Optional fields are `bom-ref`, `group`, `version`, and component `purl`. Present fields must have their expected JSON types; explicit `null` is not the same as an absent field. Group and version may be empty strings, which remain distinct from absence.

Duplicate explicit `bom-ref` values are rejected, including duplicates between the metadata component, other components and services. When a node has no `bom-ref`, the importer assigns a deterministic document-local identifier and marks it `syntheticRef: true`. Generated identifiers avoid all explicit inventory references and input dependency references, so they cannot accidentally resolve an otherwise unknown edge. A `MISSING_REF` warning identifies the node. These local IDs are not original CycloneDX references and may change if the node's input location changes.

Every node retains a JSON pointer to its source object, for example `/components/4` or `/services/0/services/1`. The source SHA-256 identifies the exact imported bytes, including whitespace and any UTF-8 byte-order mark.

## Dependencies

Only `dependencies[].dependsOn` creates graph edges. Each record requires a nonblank `ref`. An edge stores its source ref, target ref and the pointer to the target value, such as `/dependencies/2/dependsOn/1`.

| Input | Interpretation |
|---|---|
| `{"ref":"a","dependsOn":["b"]}` | `a` declares a dependency on `b`. |
| `{"ref":"a","dependsOn":[]}` | `a` explicitly declares no dependencies. |
| `{"ref":"a"}` | `a` explicitly declares no dependencies, using the compact leaf form. |
| No record for `a` | Dependencies of `a` remain unknown. |

Duplicate dependency source records and duplicate targets within one `dependsOn` array are rejected. Cycles and self-edges are retained; graph traversal must handle them without inferring that the document is invalid.

Unknown sources and targets are retained with `UNRESOLVED_REF` warnings. BOMTrail does not fetch external BOMs or interpret an unresolved reference as proof that the dependency is absent. `declaredRefs` records sources with an explicit dependency record. A present `dependsOn` must be an array; `null` and other types are rejected.

`provides` describes a different relationship and is not converted into a dependency edge. Nonempty `provides` arrays produce `PROVIDES_IGNORED`. Nonempty `compositions` arrays produce `COMPOSITIONS_NOT_EVALUATED`; their completeness assertions are not used to fill missing edges or declarations.

## Identity across snapshots

For components with a PURL, the comparison identity is the canonical Package URL with its version removed. The type, namespace, name, qualifiers and subpath remain part of the identity. Two Maven artifacts with different classifiers therefore remain different identities. Parsing and canonicalization use [packageurl-java](https://github.com/package-url/packageurl-java).

The original PURL text is retained as evidence. The explicit component version is also retained separately. If both the PURL and the component declare versions and those versions differ, the importer emits `PURL_VERSION_MISMATCH` rather than silently choosing one.

Without a PURL, identity is the exact tuple of kind, type, group and name. Version and `bom-ref` are excluded from this tuple. Names are not case-folded or trimmed, and a missing group is distinct from an empty group. This fallback is a matching rule, not a claim that two similarly named artifacts are equivalent. Multiple nodes sharing an identity need explicit review; their versions must not be paired by list order.

## Limits

| Boundary | Limit |
|---|---:|
| Input file | 16 MiB |
| Imported components and services combined | 10,000 |
| `dependsOn` edges | 50,000 |
| Dependency records | 10,000 |
| Ignored `provides` references | 50,000 |
| Component/service nesting | 32 levels |
| Overall JSON nesting | 96 levels |
| Reference strings | 1,024 characters |
| Names and groups | 512 characters |
| Explicit versions | 256 characters |
| Component type | 128 characters |
| PURL text | 4,096 characters |
| Stored warning details | First 100; total count is retained |

Handled labels reject control characters. Invalid UTF-8, duplicate JSON object keys, unpaired Unicode surrogates and multiple JSON documents are rejected. A leading UTF-8 byte-order mark is accepted and remains covered by the source hash. The shared JSON reader also bounds string length, numeric precision and scale.

Fields outside the projection, including vulnerabilities, licenses, hashes, external references and runtime topology, are not interpreted. No URL, PURL, license reference or schema reference is fetched. The original input is retained by the snapshot store, so ignored fields may still contain sensitive data. Review the source SBOM before committing or sharing a database.

Warning details are bounded, not silently counted as complete evidence. Consult `warningCount` as well as the returned warning list. A graph describes what the supplied SBOM declares; it does not establish which code paths execute or which vulnerabilities are exploitable.
