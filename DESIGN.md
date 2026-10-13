# smali-lsp Architecture

How smali-lsp works, from startup to each LSP feature, with the measured costs and the
reasons behind the main design choices. Numbers in this document come from three apktool
projects: Mastodon v2.13.4 (2,517 files), ProtonMail 7.11.5 (19,637 files) and a 76,532-file
production app, measured on a 12-core Apple Silicon laptop with JDK 21.

**Codebase**: 28 Kotlin source files (~6,400 lines), 2 ANTLR grammars (1,546 lines),
96 test files (~21,000 lines).

---

## 1. Startup

### 1.1 Entry point (`Main.kt`)

```
main() → parseArgs → configureLogging → startLsp() | McpMode().run()
```

`java -jar smali-lsp.jar <lsp|mcp> [--verbose] [--log-file <path>]`

- **lsp**: `SmaliLanguageServer` wired to stdin/stdout through LSP4J's `Launcher`; the JVM
  blocks in `startListening()`.
- **mcp**: `McpMode` serves the MCP protocol over stdio with the MCP Kotlin SDK.
- **Logging**: stdout carries the protocol, so everything the server says goes to stderr.
  WARN and above by default, INFO with `--verbose`, and `--log-file` additionally writes to
  a file. LSP4J logs through `java.util.logging`, which `logging.properties` points at stderr.

### 1.2 LSP handshake

| Step | What happens |
|------|--------------|
| `initialize` | Capabilities are answered at once. Workspace folders are taken from `workspaceFolders`, else `rootUri`, else `rootPath`. |
| `initialized` | A `**/*.smali` file watcher is registered when the client supports dynamic registration. Indexing starts in the background, with `window/workDoneProgress` only for clients that advertise it; a client that rejects the progress token still gets indexed. |
| `shutdown` / `exit` | `exit` returns 0 after a `shutdown` request and 1 otherwise, as the protocol asks. |

Requests arriving during indexing are answered from whatever is indexed so far. Semantic
diagnostics (unresolved classes) are suppressed until indexing completes, then re-published
for every open file, so a half-built index never floods a file with false warnings.

---

## 2. Parsing

### 2.1 Grammar (`SmaliLexer.g4`, `SmaliParser.g4`)

ANTLR 4, SLL prediction only. The grammar accepts every opcode of the Dalvik instruction set
(224 opcode tokens), all directives, `invoke-custom`, `invoke-polymorphic`,
`const-method-handle`, `const-method-type`, typed literal suffixes and the debug forms of
`.param`/`.local`. The start rule ends in `EOF`, so a file is either parsed to the end or
reports a syntax error; a silently truncated AST, which once hid 912 methods of ProtonMail's
uniffi bindings, cannot recur. `GrammarCorpusTest` parses every fixture file and fails on any
syntax error or on a file whose `.method`/`.field` count does not match the AST: 98,686 files,
zero errors.

**SLL only**: the smali grammar is unambiguous; no file in the corpus needs LL prediction.

### 2.2 `SmaliParser.kt` and `StringCharStream`

```
content → BOM strip → ".class" fast reject → StringCharStream → Lexer → Parser(+ASTBuilder) → SmaliFile
```

`StringCharStream` feeds the lexer UTF-16 code units, so every token offset is a string index
and every LSP column is exact, including lines with astral characters. Lexer and parser errors
are collected as `SyntaxError(line, column, message)`.

### 2.3 `ASTBuilder.kt`: a parse listener

The builder is attached with `addParseListener`, so the model is built while the parser runs
instead of by a second walk over the finished tree. Every rule callback is an `exit` hook
(children and stop token exist only then); the few `enter` hooks maintain a depth counter.
Text is sliced straight out of the source by token offset (`src()`), which replaced
`ParserRuleContext.getText()`'s per-level string building, the single biggest parse cost.

What is captured:

- **ClassDefinition**: name and its token span, modifiers, superclass and interfaces with the
  spans of their type tokens.
- **MethodDefinition**: name, descriptor, parameters, return type, modifiers, the full range,
  the name and descriptor spans, label definitions, and `labelRefs`, every label reference in
  the body (jumps, `.catch` blocks, switch tables, `fill-array-data`).
- **FieldDefinition**: name, type, modifiers, range, name and type spans.
- **Instructions** that name a symbol: invoke (incl. custom/polymorphic/method-handle), field
  access, type operands (`new-instance`, `check-cast`, `instance-of`, `const-class`,
  `new-array`, `filled-new-array`), jumps and `const-string`. Each member reference carries one
  extra int, the column of its `Lowner;->name` target, from which owner, name and descriptor
  spans are derived; nothing else is allocated per site.
- **typeRefs**: class names inside annotations, `.catch`, `.local` and method prototypes,
  recorded from the token stream while those directives are open.

Arithmetic, move and return instructions are not captured: they reference registers only.
Opcode documentation for them comes from the line text (see 5.3).

Strings are interned through the index's `StringPool`, so a class name that occurs in
thousands of files is one `String`; the pool is bounded (500 K entries) and per index.

| | ProtonMail (19,637 files) | 76 K-file app |
|---|---|---|
| Single-thread parse, all files | 2.7 s (0.14 ms/file) | ~11 s |
| AST heap after GC | 293 MB (15 KB/file) | 975 MB |

Interning the instruction strings took the ProtonMail AST from 470 MB to 293 MB for 2 to 5 %
parse time. The remaining per-instruction cost is mostly LSP4J `Range`/`Position` objects.

### 2.4 Symbol resolution: `SmaliFile.symbolAt`

Providers never read line text to find out what the caret is on. `symbolAt(position)` walks
the spans and answers with a `Symbol`: `ClassDecl`, `MethodDecl`, `FieldDecl`, `ClassRef` (owner,
`.super`, `.implements`, a type in a descriptor or field type, a type operand, a directive
reference; arrays resolve to their element class), `MethodRef`, `FieldRef`, `Primitive`,
`LabelDecl`, `LabelRef`, or `OnInstruction` when the caret is on an opcode or register. A caret
right after a token still counts as that token, as editors expect.

---

## 3. The index (`WorkspaceIndex.kt`)

### 3.1 Storage

| Structure | Key | Value |
|-----------|-----|-------|
| `filesByUri` | canonical `file:///…` URI | `SmaliFile` |
| `uriByClass` | `Lcom/Foo;` | canonical URI of its definition (last indexed wins) |
| `subclassIndex`, `implementorsIndex` | parent / interface | direct subclasses / implementors |
| `simpleNameIndex` | `Outer$Inner` | full class names |
| `methodUsages` | `Lcom/Foo;->bar(I)V` | `UsageList` of call sites |
| `fieldUsages` | `Lcom/Foo;->baz:I` | `UsageList` of access sites |
| `classRefs` | `Lcom/Foo;` | `UsageList` of owner, operand, field-type and descriptor-type tokens |
| `stringIndex` (lazy) | literal value | `UsageList` of `const-string` sites |

Files are keyed by canonical URI: the scanner's and the client's forms of one file are one
entry, and a file whose class name changes on edit replaces its old entry instead of leaving a
ghost class. Field keys include the type, since DEX allows two fields of one name. Declarations
have no table: `findMethod`/`findField` look in the class's `SmaliFile` and walk up the
hierarchy, and return the name-token location.

### 3.2 `UsageList` and generations

A usage list is a packed `IntArray`, five ints per site: file id, the file's generation when
the site was added, line, start column, end column. Every file has an id and a generation.
Re-indexing a file bumps its generation, which retires all of its earlier entries in O(1)
instead of walking every symbol the file touched. Readers skip retired entries; a compaction
drops them once they make up a quarter of all entries (and at least 100 K), amortized O(1).

Why: the previous `ConcurrentHashMap<String, Set<Location>>` tables cost ~130 bytes per site
and removal scanned every usage set the file touched, so a keystroke in a file that calls
`Object.<init>` walked the 76 K-entry set for that symbol.

| | ProtonMail | 76 K-file app |
|---|---|---|
| Index heap after GC | 371 MB → **90 MB** | 1,331 MB → **328 MB** |
| AST + index | 841 MB → **383 MB** | 2,850 MB → **1,303 MB** |
| Index build, single thread | 0.95 s → 0.52 s | 3.4 s → 1.4 s |
| didChange (re-parse + re-index one file), p95 | 58 ms → **0.6 ms** | 340 ms → **0.8 ms** |
| didChange, worst of 200 files | 0.9 s → 18 ms | 7.5 s → 9 ms |

### 3.3 Scanning (`WorkspaceScanner.kt`)

`maxOf(2, cores)` coroutine workers drain a bounded channel of files, each reading, parsing
and indexing its file. The index is safe for concurrent `indexFile` calls: maps are concurrent,
usage lists synchronize per symbol, generations are bumped under one lock. Through the real
server on 12 cores, including file reads:

| | Mastodon | ProtonMail | 76 K-file app |
|---|---|---|---|
| Cold | 1.6 s | 2.7 s | 8.8 s |
| Warm re-index | 0.4 s | 1.0 s | 7.5 s |

### 3.4 String literals

`const-string` values are indexed lazily on the first `searchStrings` call and rebuilt after
any change, because the MCP string search is rare and building the table during the parallel
scan was a contention point. `getStats().strings` is kept incrementally.

---

## 4. Services and threading

### 4.1 One writer, many readers

`WriteQueue` is a single thread that applies every mutation in order: `didOpen`,
`didChange`, `didClose`, watched-file batches (coalesced to the last event per file) and the
post-indexing diagnostics refresh. Notifications enqueue their work and return, so the LSP4J
reader thread never parses and a regenerated project's burst of watched-file events cannot
stall message dispatch.

Every request first takes the queue's barrier (a future for the writes queued before it) and
waits for it on the common pool before computing, so a request always sees the edits the
editor sent ahead of it. There is no debounce: re-indexing a file is sub-millisecond, and a
delay would only make hover and completion stale.

### 4.2 `SmaliTextDocumentService`

One `request` helper answers every request: barrier, `CompletableFutures.computeAsync` (so a
provider can honour cancellation later), a logged fallback on failure, and `ResponseErrorException`
passed through. `TextDocumentSyncKind.Full`: the client sends the whole document on every
change, which is cheaper than maintaining an edit buffer for files this size. Diagnostics are
published on `didOpen` and `didChange`, syntax-only until indexing completes.

### 4.3 Workspace

`didChangeWatchedFiles` re-parses created and changed files from disk on the writer and removes
deleted ones. `workspace/symbol` runs on the common pool after the barrier.

---

## 5. Features

### 5.1 By symbol kind

| Caret on | Definition | Hover | References | Rename |
|----------|------------|-------|------------|--------|
| class name in `.class` | itself | class info | declaration, `.super`/`.implements` lines, every reference | not supported |
| `.super`, `.implements`, owner, type operand, descriptor type, field type, directive reference | the class, by element type for arrays | class info, or SDK note | references to that class (a type inside an invoke's descriptor answers with the call's references) | — |
| method name in `.method` | — | signature | call sites incl. through subclasses | declaration, overrides, every call site |
| method name in an invoke | declaration, via hierarchy | signature or SDK note | call sites | same, for workspace classes only |
| field name in `.field` / access | declaration | type | accesses | declaration and accesses |
| primitive type | the member, when inside a reference | primitive info | — | — |
| `:label` definition or reference | definition | reference count and label kind | every reference in the method | definition and every reference |
| opcode or register | the instruction's primary symbol | opcode documentation | — | — |

Definitions point at the name token, so an editor lands on the name instead of selecting the
whole declaration. Usage locations are name tokens too, so rename edits are the usage
locations themselves. Labels are method-local: a `:cond_0` in another method is never touched.
`<init>`/`<clinit>` are not renamed; neither are members of classes outside the workspace,
whose declarations are unreachable.

### 5.2 Hierarchies, lenses, symbols

- **Call hierarchy**: incoming calls from `methodUsages` grouped by containing method;
  outgoing calls from the method's invoke instructions, resolved through the class hierarchy.
- **Type hierarchy**: supertypes from the class definition; subtypes from the subclass and
  implementor sets.
- **Code lens**: one lens per method and field, resolved lazily to a usage count.
- **Document symbols**: readable class name, constructors as `Constructor`, descriptor and
  type as detail, name token as `selectionRange`.
- **Workspace symbols**: fuzzy match over classes, methods and fields, best 500 kept.
- **Diagnostics**: syntax errors, then unresolved classes in `.super`, `.implements`, field
  types and instruction targets, skipping platform SDK classes.

### 5.3 What still reads line text

Completion works on the text being typed by nature. The opcode-documentation hover takes the
first word of the line, because instructions without symbols have no AST node. Both read the
open document's buffer.

---

## 6. MCP mode (`McpMode.kt`)

`smali_index` scans a directory into a fresh `WorkspaceIndex` and swaps in one immutable
`Session(index, providers)`; the old session is dropped before the scan so two indexes of a
large app never coexist. A registration helper declares each tool's arguments once, answers a
missing index or argument uniformly, and turns any failure into a tool error.

| Tool | Answers |
|------|---------|
| `smali_index` | scan a directory |
| `smali_find_definition` | a class by full or simple name |
| `smali_search_symbols` | fuzzy symbol search |
| `smali_get_stats` | counts |
| `smali_find_references` | by position (`uri`, `line`, `character`) or by name (`class_name`, optional `member_name` with `descriptor` or `field_type`) |
| `smali_hover` | same two forms |
| `smali_diagnostics` | diagnostics for given content |
| `smali_document_symbols` | a file's outline |
| `smali_search_strings` | `const-string` literals |
| `smali_call_graph` | callers and callees of a method |
| `smali_xref_summary` | subclasses, implementors, callers, accessors, referencing classes |
| `smali_type_hierarchy` | supertypes and subtypes |

---

## 7. Design decisions

- **ANTLR, not a hand-written parser.** The grammar is 1.5 K lines against an estimated 3 K
  for a hand-written parser, and ANTLR's error recovery keeps a file with one bad line
  navigable. Parsing is 0.14 ms per file; the one-time scan of a 76 K-file app is 9 s.
- **A parse listener, not a tree walk.** Building the model during the parse and slicing text
  by offset halved the parse pipeline (5.1 s → 2.7 s on ProtonMail) without changing a byte of
  output, verified by a per-file fingerprint of every AST.
- **Spans in the AST.** One int per member reference gives exact token ranges; the alternative,
  re-reading lines and matching names with `indexOf`, mis-resolved duplicated names and needed
  file reads for closed documents.
- **Full ASTs in memory.** Hover, call hierarchy, diagnostics and the MCP tools all need
  instruction lists. At 13 to 15 KB per file the AST is the larger half of the heap; packing
  the `Range` objects would be the next saving.
- **Generations, not per-symbol removal.** O(1) retirement is what makes editing a file that
  references popular symbols cheap.
- **One writer thread.** Ordering is trivial to reason about, the reader thread stays free,
  and requests wait a few microseconds for the barrier.
- **Full document sync.** An edit buffer with incremental ranges is where off-by-one bugs
  live; smali files are small.

---

## 8. Known limitations

- Class rename is not offered: it would mean renaming the file and rewriting the package path
  across the workspace.
- Two files defining the same class (duplicate dex splits) keep both files indexed, but the
  class name maps to the last one indexed.
- The 76 K-file app needs more than the 1 GB test fork, so the fixture-invariant test skips it
  by default (`-Dsmali.invariants.maxFiles`).
- `Range`/`Position` objects inside the AST are the remaining memory target.

---

## 9. Verification

- `*.unit.*` (467 tests, ~4 s), `*.regression.*` (74, ~1 s), `*.integration.*` (136, ~65 s with
  the fixtures present; one skip). Run the categories separately: each fork holds a parsed app.
- `GrammarCorpusTest`: every fixture file parses without syntax errors and without losing a
  `.method` or `.field`.
- `RealApkInvariantsTest`: properties that hold for any apktool project (every invoke in the
  workspace resolves, hierarchy sets agree with `.super`/`.implements`, usage locations name
  the member, re-indexing is a no-op), so fixtures can change version without rewriting tests.
- `LspStdioTest` and `McpModeTest` drive the real binaries over stdio with JSON-RPC.
- Parser and builder changes are checked with a per-file fingerprint of every AST in the corpus
  against the previous build; a builder change must leave every pre-existing field identical.

---

## 10. File inventory

| File | Lines | Role |
|------|-------|------|
| SmaliParser.g4 | 1,062 | parser grammar |
| SmaliLexer.g4 | 484 | lexer grammar |
| DalvikOpcodeDatabase.kt | 1,042 | opcode documentation for hover and completion |
| ASTBuilder.kt | 738 | parse listener building the model |
| McpMode.kt | 521 | MCP server |
| WorkspaceIndex.kt | 502 | the index |
| Main.kt | 452 | entry point, CLI, LSP server |
| WorkspaceSymbolProvider.kt | 282 | workspace symbol search |
| SmaliTextDocumentService.kt | 258 | document lifecycle and requests |
| DiagnosticProvider.kt | 235 | diagnostics |
| Types.kt | 222 | descriptor and class-name helpers |
| HoverProvider.kt | 211 | hover |
| CompletionProvider.kt | 188 | completion |
| DataStructures.kt | 183 | the AST model |
| CallHierarchyProvider.kt | 159 | call hierarchy |
| ReferenceProvider.kt | 156 | references |
| SmaliParser.kt | 153 | parser entry |
| WorkspaceScanner.kt | 152 | parallel scan |
| RenameProvider.kt | 141 | rename |
| TypeHierarchyProvider.kt | 140 | type hierarchy |
| Symbol.kt | 139 | `symbolAt` and the symbol kinds |
| SmaliWorkspaceService.kt | 122 | watched files, workspace symbols |
| DocumentSymbolProvider.kt | 87 | outline |
| CodeLensProvider.kt | 86 | reference counts |
| DefinitionProvider.kt | 68 | definition |
| StringCharStream.kt | 55 | UTF-16 char stream |
| StringPool.kt | 53 | per-index interning |
| UsageList.kt | 53 | packed usage sites |
| WriteQueue.kt | 42 | the writer thread |
