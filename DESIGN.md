# smali-lsp Architecture

How smali-lsp works, from startup to each LSP feature, with the measured costs and the
reasons behind the main design choices. Numbers in this document come from three apktool
projects: Mastodon v2.13.4 (2,517 files), ProtonMail 7.11.5 (19,637 files) and a 76,532-file
production app, measured on a 12-core Apple Silicon laptop with JDK 21.

**Codebase**: 28 Kotlin source files (~6,400 lines), 2 ANTLR grammars (1,546 lines),
96 test files (~21,000 lines).

---

## 1. Startup

### 1.1 Entry point (`Main.kt`, `Cli.kt`, `Logging.kt`, `SmaliLanguageServer.kt`)

```
main() → parseArgs → configureLogging → startLsp() | McpMode().run()
```

`java -jar smali-lsp.jar <lsp|mcp> [--verbose] [--log-file <path>]`

`Main.kt` holds `main()`; `Cli.kt` the options, the usage text and the version resource;
`Logging.kt` the logback setup; `SmaliLanguageServer.kt` the server.

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
| AST heap after GC | 190 MB (10 KB/file) | 673 MB |

Interning the instruction strings took the ProtonMail AST from 470 MB to 293 MB for 2 to 5 %
parse time; storing instruction and label ranges as four ints instead of LSP4J `Range`
objects took it to 190 MB. Declarations keep their `Range`; there are few of them.

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
| AST + index | 841 MB → **281 MB** | 2,850 MB → **1,027 MB** |
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

`const-string` values go into a usage table like members (value → sites), retired by the file's
generation like everything else, so a search after an edit never rebuilds anything. The search
is a case-insensitive `contains` over the table, allocation-free. `getStats().strings` is a
count map kept incrementally. The table costs about 7 MB on ProtonMail and 20 MB on the
76 K-file app.

---

## 4. Services and threading

### 4.1 One writer, many readers

`WriteQueue` is a single thread that applies every mutation in order: `didOpen`,
`didChange`, `didClose`, watched-file batches (coalesced to the last event per file) and the
post-indexing diagnostics refresh. Notifications enqueue their work and return, so the LSP4J
reader thread never parses and a regenerated project's burst of watched-file events cannot
stall message dispatch.

Every request goes through `WriteQueue.request`, the one helper both services use: it takes
the queue's barrier (a future for the writes queued before it) and waits for it on the common
pool before computing, so a request always sees the edits the editor sent ahead of it; a
failing provider is logged and answered with the request's empty value; a `ResponseError`
reaches the client as such; and the cancellation token of `$/cancelRequest` is checked by the
symbol scan and by big reference lists, so the query the editor cancels on the next keystroke
stops instead of finishing its scan. There is no debounce: re-indexing a file is
sub-millisecond, and a delay would only make hover and completion stale.

### 4.2 `SmaliTextDocumentService`

One `request` helper answers every request: barrier, `CompletableFutures.computeAsync` (so a
provider can honour cancellation later), a logged fallback on failure, and `ResponseErrorException`
passed through. `TextDocumentSyncKind.Full`: the client sends the whole document on every
change, which is cheaper than maintaining an edit buffer for files this size. Diagnostics are
published on `didOpen` and `didChange`, syntax-only until indexing completes.

The editor's buffer is the truth while a file is open: `didOpen` always indexes the text it
receives (a restored unsaved buffer differs from the disk), `didChange` re-indexes, and
watched-file events for an open file are ignored. `didClose` re-reads the file from disk, so
an edit discarded without saving leaves the index, and a buffer that was never saved is
removed.

### 4.3 Workspace

`didChangeWatchedFiles` re-parses created and changed files from disk on the writer and removes
deleted ones, leaving files that are open in the editor alone. `didChangeWorkspaceFolders`
drops the files under a closed folder and indexes an opened one like the initial folders.
`workspace/symbol` runs on the common pool after the barrier.

---

## 5. Features

### 5.1 By symbol kind

| Caret on | Definition | Hover | References | Rename |
|----------|------------|-------|------------|--------|
| class name in `.class` | itself | class info | declaration, `.super`/`.implements` lines, every reference | not supported |
| `.super`, `.implements`, owner, type operand, descriptor type, field type, directive reference | the class, by element type for arrays | class info, or SDK note | references to that class (a type inside an invoke's descriptor answers with the call's references) | — |
| method name in `.method` | — | signature | the member family: every declaration (the topmost ones it overrides and their overrides) and every call that binds to one of them | the whole family |
| method name in an invoke | declaration, via hierarchy | the declaration's signature, or SDK note | the family of the method the call resolves to; only the exact owner's call sites for a class outside the workspace | same; nothing for classes outside the workspace |
| field name in `.field` / access | declaration, via hierarchy (interfaces first) | type | the declaration and every access that binds to it: through subclasses that inherit it without redeclaring it | same |
| primitive type | the member, when inside a reference | primitive info | — | — |
| `:label` definition or reference | definition | reference count and label kind | every reference in the method | definition and every reference |
| opcode or register | the instruction's primary symbol | opcode documentation | — | — |

A call binds to a member by resolution, as the runtime would: constructors and private
methods to their own class only; static methods and fields to the nearest declaration up the
superclass chain (superinterfaces first for fields); virtual methods to the nearest
declaration, with a redeclaration below counting as an override of the same member. The
expansion through subclasses applies to every indexed class, including bundled classes whose
names look like the SDK (`androidx`, `kotlin`, `com.google.android`); a class that is not
indexed (`Ljava/lang/Object;`) has only its exact call sites.

Definitions and the declaration entries of references point at the name token, so an editor
lands on the name instead of selecting the whole declaration. Usage locations are name tokens
too, so rename edits are the usage locations themselves. Labels are method-local: a `:cond_0` in another method is never touched.
`<init>`/`<clinit>` are not renamed; neither are members of classes outside the workspace,
whose declarations are unreachable.

### 5.2 Hierarchies, lenses, symbols

- **Call hierarchy**: prepared from the called method when the caret is on a call, else the
  enclosing method; incoming calls are the member family's call sites grouped by containing
  method (found by binary search over the file's methods); outgoing calls from the method's
  invoke instructions, resolved through the class hierarchy.
- **Type hierarchy**: prepared from any class reference under the caret; supertypes from the
  class definition; subtypes from the subclass and implementor sets.
- **Implementation**: the concrete declarations of a method's family other than the one under
  the caret; for a class, every subclass and implementor.
- **Document highlight**: the references of the symbol restricted to its file; `iput`/`sput`
  sites are writes, other field sites reads.
- **Folding ranges**: multi-line methods and the annotation, switch-table and array-data
  blocks the builder records.
- **Code lens**: one lens per method and field on its declaration line, resolved lazily to
  the count of sites that bind to that declaration (an override counts its own callers).
- **Document symbols**: readable class name, constructors as `Constructor`, descriptor and
  type as detail, name token as `selectionRange`.
- **Workspace symbols**: fuzzy match over classes, methods and fields, best 500 kept. The
  scan runs in parallel chunks with per-chunk top sets merged at the end: 15 to 26 ms per
  query over the 76 K-file app's 660 K names.
- **Completion**: after `->`, the members of the owner and of its workspace supertypes (own
  members first, overrides hiding what they override); class names ranked by match kind,
  length and name so the list is stable across keystrokes; opcodes at the start of a line.
- **Diagnostics**: syntax errors on the offending token and the file-local label checks (a
  jump to an undefined label, a label defined twice) are published at once; unresolved classes
  in `.super`, `.implements`, field types and instruction targets wait for indexing to finish
  and skip platform SDK classes.

### 5.3 What still reads line text

Completion works on the text being typed by nature. The opcode-documentation hover takes the
first word of the line, because instructions without symbols have no AST node. Both read the
open document's buffer, or the file on disk when the document is not open (MCP by position).

---

## 6. MCP mode (`McpMode.kt`)

`smali_index` scans a directory into a fresh `WorkspaceIndex` and swaps in one immutable
`Session(index, providers)`; the old session is dropped before the scan so two indexes of a
large app never coexist. A registration helper declares each tool's arguments once, answers a
missing index or argument uniformly, and turns any failure into a tool error.

| Tool | Answers |
|------|---------|
| `smali_index` | scan a directory |
| `smali_find_definition` | a class by full or simple name; the declaration at a position; a member by `class_name` + `member_name` |
| `smali_search_symbols` | fuzzy symbol search |
| `smali_get_stats` | counts |
| `smali_find_references` | by position (`uri`, `line`, `character`) or by name (`class_name`, optional `member_name` with `descriptor` or `field_type`); at most `max_results` (default 500, cap 5,000) with the full `total` |
| `smali_hover` | same two forms |
| `smali_diagnostics` | diagnostics for given content |
| `smali_document_symbols` | a file's outline |
| `smali_search_strings` | `const-string` literals, at most `max_results` (default 100, cap 5,000) |
| `smali_call_graph` | callers and callees of a method |
| `smali_xref_summary` | subclasses, implementors, callers, accessors, referencing classes |
| `smali_type_hierarchy` | supertypes and subtypes |
| `smali_read_method` | one method's numbered source lines |
| `smali_list_package` | the classes under a package prefix and its sub-packages |

Arguments are validated by type (a wrongly typed one is a tool error naming it), tool
handlers run concurrently with the SDK's 0.15 dispatch, and `smali_index` holds a lock so
only one index is built at a time.

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
  instruction lists. At about 10 KB per file after interning and range packing, the AST is
  two thirds of the heap; re-parsing on demand would save it at the cost of a disk read per
  request.
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
- A method that overrides one declared outside the workspace (`onCreate` of a framework
  `Activity`) is renamed with its own subclasses only; the server cannot see the framework
  declaration, so the override silently stops overriding. The family starts at the topmost
  declaration *in the workspace*.
- Two files defining the same class (duplicate dex splits) keep both files indexed, but the
  class name maps to the last one indexed.
- The 76 K-file app needs more than the 1 GB test fork, so the fixture-invariant test skips it
  by default (`-Dsmali.invariants.maxFiles`).
- Find-references on `Ljava/lang/Object;` answers with every owner, operand and descriptor
  use: 264 K locations on the 76 K-file app, serialized in well under a second but more than
  an editor can show. The LSP answer has no cap (a cancelled request stops early); the MCP
  tool caps its answer and reports the total.

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
- Before a release, a scripted stdio session against a real app exercises every request at
  about a thousand positions (declarations, references, operands, labels, directives,
  opcodes), compares the answers with the previous build, and checks each definition,
  reference line and rename edit against the source text; the same for the twelve MCP tools.
- Parser and builder changes are checked with a per-file fingerprint of every AST in the corpus
  against the previous build; a builder change must leave every pre-existing field identical.

---

## 10. File inventory

| File | Lines | Role |
|------|-------|------|
| SmaliParser.g4 | 1,062 | parser grammar |
| DalvikOpcodeDatabase.kt | 1,042 | opcode documentation for hover and completion |
| ASTBuilder.kt | 748 | parse listener building the model |
| McpMode.kt | 537 | MCP server |
| WorkspaceIndex.kt | 515 | the index |
| SmaliLexer.g4 | 484 | lexer grammar |
| SmaliLanguageServer.kt | 317 | the LSP server: handshake, indexing, capabilities |
| WorkspaceSymbolProvider.kt | 254 | workspace symbol search |
| SmaliTextDocumentService.kt | 252 | document lifecycle and requests |
| DataStructures.kt | 252 | the AST model |
| Types.kt | 222 | descriptor and class-name helpers |
| HoverProvider.kt | 209 | hover |
| ReferenceProvider.kt | 195 | references and member families |
| DiagnosticProvider.kt | 188 | diagnostics |
| CompletionProvider.kt | 185 | completion |
| WorkspaceScanner.kt | 142 | parallel scan |
| TypeHierarchyProvider.kt | 140 | type hierarchy |
| Symbol.kt | 140 | `symbolAt` and the symbol kinds |
| RenameProvider.kt | 130 | rename |
| CallHierarchyProvider.kt | 127 | call hierarchy |
| SmaliParser.kt | 126 | parser entry |
| SmaliWorkspaceService.kt | 113 | watched files, workspace symbols |
| CodeLensProvider.kt | 88 | reference counts |
| DocumentSymbolProvider.kt | 82 | outline |
| WriteQueue.kt | 71 | the writer thread and the request helper |
| DefinitionProvider.kt | 68 | definition |
| Cli.kt | 64 | command line, usage, version |
| StringCharStream.kt | 55 | UTF-16 char stream |
| UsageList.kt | 53 | packed usage sites |
| StringPool.kt | 53 | per-index interning |
| Logging.kt | 39 | logback setup |
| Main.kt | 31 | entry point |