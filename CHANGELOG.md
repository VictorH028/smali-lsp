# Changelog

## Unreleased

A hand-written parser replaces the ANTLR grammar, the index and AST hold a third of the memory
they did, the server gives idle memory back to the OS, and editing gets register-aware hover,
assembler-level diagnostics and resource-id navigation. Every change is verified
against three decompiled apps (98,686 files) with a per-file AST fingerprint, real-server
drives of every request at thousands of positions, and the MCP tools.

### Added

- **Register hover and highlight** — hover on `p1` says which parameter it carries (`this`, the second register of a wide one), on `v3` which local slot, with the `.param`/`.local` debug names and the method's frame; document highlight on a register marks every use in the method, writes apart from reads
- **Assembler checks for hand edits** — a register the method does not declare or that the instruction form cannot address (`move` v0..v15, five sub-v16 registers for `invoke-*` unless `/range`), a literal that does not fit (`const/4` holds -8..7), a `move-result` not right after a call; widths and ranges come from the opcode database's format table
- **Resource ids** — hover on a `0x7f...` literal names the resource (`id/login_button`) from the project's `res/values/public.xml` and shows the string, color or dimension value from `res/values/*.xml`; go-to-definition opens the value's line, the layout or drawable file, or the `public.xml` entry; inlay hints put name and value after every such literal, in a `const`, a `.field` value, an `.array-data` element (the styleable arrays), a switch key or an annotation; the table reloads when apktool rewrites the file
- **Literal values** — inlay hints put the decimal value after every other hex literal (`const/16 v0, 0x3e8  = 1000`, `mul-int/lit8 v0, v0, 0x1f  = 31`), in instructions, `.field` values, `.array-data` elements, switch keys and annotation elements; values -9..9, bytes, shorts and literals baksmali already commented (`# 1.0f`) are left alone
- **Strings** — find references on a `const-string` lists every load of the same value in the workspace; hover shows the text and the count
- **Frame hover** — hover on `.registers`/`.locals` maps the frame: which `vN` the locals, `this` and each parameter occupy, with the `.param` names; a method hover names the register each parameter arrives in
- **Class code lens** — the `.class` line shows the class's reference count and how many classes extend or implement it
- **Implementations, document highlight, folding ranges** — implementors and overrides of a method, occurrences of a symbol or register in its file, foldable methods and directive blocks
- **Label diagnostics** — a jump to an undefined label and a duplicate label are errors
- **Hierarchies from any reference**; **completion** offers inherited members and ranks classes; **member hovers** name the declaring class and count the references
- **Workspace folders** added or removed during the session are indexed and dropped
- **MCP** — tools are bounded and validated, two new ones (`smali_read_method`, `smali_list_package`), an unknown tool is the JSON-RPC error the protocol specifies
- **Logging** — warnings on stderr by default, `--log-file` instead of files in the working directory, `--verbose`

### Changed

- **The parser is hand-written** — a lexer and statement parser replace the ANTLR grammar, its listener and the generated parser: the same model byte for byte on every fixture file, 3.6–4.6× faster single-threaded (0.04 ms per file), four to six times less allocation, cold start about twice as fast; a statement cut short at a line end is reported where it stopped, one bad line costs one diagnostic, and no input can overflow the stack
- **Undefined-class warnings** are reported only for classes in packages the workspace defines; the classes an app references but does not ship (`org.xmlpull`, an optional Conscrypt, a Firebase connector behind a runtime check) are not mistakes — 652 false warnings on the three fixture apps went to zero
- **One writer thread** applies every mutation; requests wait for the writes sent before them, so an answer never sees a half-applied edit; cancelled requests stop
- **Definitions and selection ranges** point at the name token; references list every declaration of the member and start from its root declaration; only members a call can bind to are its references
- **Diagnostics underline the offending token** (syntax errors, undefined classes, code lenses span the declaration line)
- **Memory** — ProtonMail (19,637 files) holds 174 MB of live heap after indexing and the 76 K-file app 657 MB (from 288 MB and 1.05 GB): instructions, declarations, labels, references and class tokens keep positions as ints, modifiers are shared sets, usage lists are packed ints trimmed after the scan, member keys share the pooled strings
- **Heap returned to the OS** — the free ratios are set in-process and a periodic concurrent cycle, armed by activity, uncommits the scan's garbage: committed heap 1.2 GB → 400 MB on ProtonMail and 3 GB → 1.6 GB on the large app a minute after indexing, with no launch flag
- **Toolchain** — Gradle 9.8.1, Kotlin 2.4.21, lsp4j 1.0.0, MCP Kotlin SDK 0.15.0, JUnit 6.1.3, coroutines, Gson, SLF4J and Logback at their latest; any JDK 17+ builds the project; the shadow jar carries no grammar runtime (9.0 MB)

### Fixed

- The opcode and registers of an instruction no longer answer go-to-definition, references, implementation or document highlight with the instruction's member (editors underlined the whole instruction as a link); only the owner, member and type tokens do
- Label operations scoped to the containing method (rename, definition and hover for `:cond_0` resolved against methods in file order)
- Rename rejected for SDK and unindexed members; arrays of workspace classes no longer flagged; a primitive-array owner is not a class reference
- Ghost class evicted when a watched file's class is renamed in place; the index follows the editor's buffer, then the disk again on close; percent-encoded file URIs are the same file; a watcher is registered for `.smali` files
- Members used through a subclass resolve to the member the declaring class owns; fields inherited from interfaces resolve; incoming calls include calls through subclasses and supertypes; implementations of `Object` are not every class
- Bundled copies of SDK-prefixed classes (`androidx.*`, `kotlin.*`) are workspace classes and navigable
- Parsing accepts `synchronized`, debug-info forms of `.param` and `.local`, digit-leading and all-digit member names, keyword-, literal- and opcode-spelled member names, byte/short literal suffixes, primitive-array method owners (`[I->clone()`), `invoke-custom`, `invoke-polymorphic` and method-handle constants; string and char literals lex against their own delimiter; columns are UTF-16 code units; unparseable top-level constructs are reported instead of truncating the file
- Indexing survives a silent client, a bad file and a symlink loop; the `rootUri` workspace is indexed; progress cannot abort indexing; capability registration that throws does not stop initialization
- Exit status 1 when no shutdown request preceded exit; MCP stdout no longer corrupted by a logging banner

### Internal

- The real-APK invariants, the grammar corpus, stdio drives of the LSP and MCP binaries, the AST fingerprint and the mutation and fuzz corpora are the gate for every change
- Enabled Gradle's configuration cache; removed the unused jacoco plugin; CI test jobs no longer build the shadow jar first

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
