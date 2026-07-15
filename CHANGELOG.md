# Changelog

## Unreleased

### Fixed

- **Label operations scoped to the containing method** — rename, go-to-definition, and hover for labels resolved the label name against methods in file order; since baksmali reuses `:cond_0`/`:goto_0` in nearly every method, renaming a label in any method after the first silently rewrote the *first* method's labels, go-to-definition jumped into the wrong method, and hover inflated jump counts
- **Rename rejected for SDK/unindexed members** — renaming e.g. `String.length()` from a call site rewrote every call site in the workspace while the SDK declaration kept its name, breaking each rewritten call
- **Arrays of workspace classes no longer flagged** — `check-cast v0, [Lcom/app/Foo;` produced a false "class not found" warning even when `Lcom/app/Foo;` was indexed (primitive/SDK arrays were already handled)
- **Ghost class evicted on watched-file class rename** — when apktool regenerates a file whose class name changed, the previous class at that URI is now removed from the index
- **MCP stdout protocol corruption** — kotlin-logging 8.x (transitive via MCP SDK 0.14) printed a startup banner to stdout ahead of the first JSON-RPC response; suppressed via `kotlin-logging.logStartupMessage`

### Changed

- **Toolchain modernized** — Gradle 9.6.1, `com.gradleup.shadow` 9.5.1, Kotlin 2.4.10; any JDK 17+ builds the project (Gradle 9's own floor — no pinned toolchain, no forced downloads), while `release=17` + `-Xjdk-release=17` keep the artifact's bytecode *and* API surface Java-17 compatible regardless of build JDK; CI builds/releases on Temurin 25 and runs tests on a JDK 17 + 25 matrix to keep the floor honest
- **Dependencies updated** — lsp4j 0.24.0, MCP Kotlin SDK 0.14.0, JUnit 6.1.2, coroutines 1.11.0, Gson 2.14.0, SLF4J 2.0.18, Logback 1.5.38, ANTLR 4.13.2 (lsp4j 1.0.0 deliberately deferred: its LSP-3.18 `Either` type changes on `Diagnostic.message`/`TextDocumentEdit.edits` are breaking with no feature benefit here)
- **Shadow jar 25% smaller (13 MB → 9.7 MB)** — jar contents are now governed at the dependency level: the ANTLR *tool* (build-time codegen, StringTemplate, ICU) and the MCP SDK's Ktor transports (with their kotlin-reflect and jansi baggage) are excluded from the runtime classpath itself instead of via fragile path lists — this also drops the excluded artifacts' metadata debris the old path excludes missed
- `StringPool.clear()` re-seeds the pre-interned common strings, so MCP re-indexing keeps the hot-path fast lookups

### Internal

- Regression tests for label scoping, SDK rename guard, workspace-class arrays, and watched-file class renames
- Corrected stale docs (opcode DB coverage claim, ReferenceProvider complexity note); removed a dead `unused_` filter
- Removed the unused jacoco plugin — its agent taxed every test run while no workflow or doc ever consumed a report
- Enabled Gradle's configuration cache (warm no-op builds ~300 ms); repaired jmh benchmark compilation and jar packaging
- CI test jobs no longer build the shadow jar first (tests never used it)

## [1.5.0] - 2026-04-21

### Changed

- `getStats()` now returns O(1) class, method, field, and string counts without forcing lazy string-index materialization
- `workspace/symbol` now keeps only the best top-N matches while scanning, avoiding full-result sorts on large workspaces
- `workspace/symbol` no longer mixes string literals into symbol results; string searches stay on the dedicated string-search path
- `searchStrings()` now exits as soon as `maxResults` is reached and avoids building intermediate collections

### Fixed

- Build metadata now tracks git HEAD correctly when running `./gradlew shadowJar` without `clean`
- MCP `serverInfo.version` now uses the same generated version metadata as LSP and `--version`
- Corrected live build-output docs/scripts to reference the actual versioned jar naming

### Internal

- Added regression coverage for MCP version metadata, string-count reindex/remove flows, workspace symbol string exclusion, and relevance ordering on large workspaces

## [1.4.1] - 2026-04-17

### Fixed

- **Stale diagnostics after indexing** — "class not found" warnings shown during indexing now get cleared once indexing completes instead of persisting until the user edits the file
- **Semantic diagnostics suppressed during indexing** — only syntax errors are shown while the workspace index is being built, preventing false-positive "undefined class" warnings
- **`didChange` during indexing no longer unleashes false-positive warnings** — typing a character while the workspace index is still being built previously flooded the file with "undefined-class" warnings even though `didOpen` had correctly suppressed them; both now behave consistently
- **Rename name validation** — rejects invalid Smali identifiers (spaces, slashes, parens, names starting with digits); labels validated separately (no hyphens or dollar signs)
- **Indexing progress notification never ends if scan throws** — `WorkDoneProgressEnd` is now emitted from a `finally` block so the client status bar no longer stays stuck at "Indexing…" when one folder scan fails mid-workspace; the end message also reports final class/method/field counts
- **Watched files now react to create/delete/change** — external filesystem changes (e.g. apktool regenerating a project, deletion outside the editor) re-parse or evict the affected URI; go-to-definition no longer navigates to ghost URIs, and references to freshly created classes resolve without requiring the user to open each file manually

### Internal

- `WorkspaceIndex.removeFile(uri)` — evicts a class entry and all reverse references (usages, refs, method/field locations) for a URI; paired with `SmaliWorkspaceService.didChangeWatchedFiles` handling
- Open file tracking in `SmaliTextDocumentService` for post-indexing diagnostic refresh
- `DiagnosticProvider.computeSyntaxDiagnosticsFromParseResult()` for syntax-only mode
- `WorkspaceIndex.getDocumentContent()` for full buffer retrieval
- 18 new tests: 6 service-level diagnostic refresh tests, 7 rename validation tests, 2 syntax-only diagnostic tests, 1 name validation unit test, 2 `WorkspaceIndex.removeFile` tests

## [1.4.0] - 2026-04-13

### Added

- **Rename** provider — rename methods, fields, and labels across workspace
  - Polymorphic-aware: renaming a method cascades to subclass overrides
  - Supports rename from both declaration and usage sites
  - `prepareRename` validation rejects `<init>`, `<clinit>`, and class names
- **Instruction hover** — opcode documentation with format, description, and operands (218 Dalvik opcodes)
- **Directive navigation** — hover and go-to-definition for directives inside method body (`.catch`, `.annotation`, `.implements`)
- MCP: validate `direction` parameter in type hierarchy tools

### Changed

- **Find References**: O(1) reverse index lookups (was O(n) full scan) — <1ms instead of ~5s for large codebases
- **Parsing**: micro-optimizations — SLL-only mode, fast-reject for non-smali files, bounded string pool
- **Completion**: class names now show full path as label (e.g., `com/example/MyActivity`) with simple name as detail
- Deduplicated document content storage (removed redundant buffers)

### Fixed

- Class completion double-L insertion bug (`LLcom/...` instead of `Lcom/...`)
- Class names with digit-starting path components (e.g., `Lcom/1foo/Bar;`) now parse correctly
- Field type class references extracted properly (e.g., hover on `.field` type works)
- `extractClassRefFromFieldType` no longer returns `null` for primitive arrays
- Cycle protection in `WorkspaceIndex` hierarchy traversal (prevents infinite loops)
- `Range.contains` off-by-one error for end-of-range positions
- BOM handling in parser (UTF-8 BOM stripped before parsing)
- HoverProvider position bugs and pattern matching (correct cursor resolution)
- Negative line positions in diagnostics (clamp to 0)
- Diagnostics now published on `didOpen`, not just `didChange`
- Dynamic project version in `ServerInfo` and shadow JAR (was hardcoded)
- Defensive null handling in MCP hover content extraction
- Parse failure reporting (errors propagated instead of silently swallowed)
- Eliminated double-parse on `didOpen` (parse once, reuse)

### Internal

- Dead code removal, unified helpers, fixed stale comments
- Comprehensive regression test suite for audit bug findings
- Architecture and design documentation (`DESIGN.md`)

## [1.3.0] - 2026-04-06

### Added

- **Type Hierarchy** provider — supertypes/subtypes navigation (LSP + MCP)
- **Code Lens** provider — inline reference counts on methods and fields
- **Completion** provider — class names, method/field members, opcodes
- **Indexing progress** — reports progress to client via `WorkDoneProgress` notifications
- `--verbose` flag for file logging (off by default to avoid log files in CWD)

### Changed

- CLI uses subcommands (`lsp`, `mcp`) instead of flags (`--lsp`, `--mcp`)
- Skip redundant parsing and diagnostics on `textDocument/didOpen`

## [1.2.0] - 2026-04-04

### Added

- **Call Hierarchy** provider — incoming/outgoing call graphs
- **String Literal Search** — indexed `const-string` instructions for fast text search
- MCP tools: `smali_search_strings`, `smali_call_graph`, `smali_xref_summary`
- CLI: `--help` usage text and explicit `--lsp` mode flag
- DescriptorParser utility for type descriptor handling

### Changed

- Drop `-all` suffix from artifact name
- Clean up deprecated Gradle API usage

### Fixed

- MCP: better class search, remove count ambiguity
- CI: run all test categories, update MCP tool count assertions

## [1.1.0] - 2026-04-02

### Added

- `--version` flag to print build info
- **MCP Server** — 9 tools for AI agent integration (index, definition, search, stats, references, hover, diagnostics, symbols)

### Fixed

- Find references average performance threshold relaxed for real APKs
- Search symbols diagnostic test count for package-path matches
- Capped parallel test forks at 4, set 1g heap per worker
- Scanner: explicit UTF-8 charset when reading smali files
- MCP: fix OOM on re-index, missing line ranges in document symbols, package path search
- Limit coroutines in WorkspaceScanner

### Removed

- Dead InstructionParser and no-op `parseInstructionsInMethods`

## [1.0.0] - 2026-04-01

### Added

- **LSP Server** — go to definition, find references, hover, document/workspace symbols, diagnostics, label navigation
- **ANTLR4 Grammar** — full smali parser with SLL prediction mode
- **WorkspaceIndex** — thread-safe concurrent index with bidirectional class-URI maps, string interning
- **WorkspaceScanner** — parallel file processing with APKTool project detection
- Position-aware symbol extraction for all 41 Dalvik instruction types
- Inheritance-aware polymorphic reference matching
- SDK class detection and filtering
- CI/CD with GitHub Actions (build, test, release)
- Shadow JAR build (13MB, excluding build-time ANTLR4 tool)
- GPLv3 license
