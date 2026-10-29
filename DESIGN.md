# smali-lsp Architecture

How smali-lsp works, from startup to each LSP feature, with the measured costs and the
reasons behind the main design choices. Numbers in this document come from three apktool
projects: Mastodon v2.13.4 (2,517 files), ProtonMail 7.11.5 (19,637 files) and a 76,532-file
production app, measured on a 12-core Apple Silicon laptop with JDK 21.

**Codebase**: 48 Kotlin source files, no grammar: the front end is a hand-written parser;
84 test files.

---

## 1. Startup

### 1.1 Entry point (`Main.kt`, `Cli.kt`, `Logging.kt`, `SmaliLanguageServer.kt`)

```
main() → parseArgs → configureLogging → startLsp() | McpMode().run()
```

`java -jar smali-lsp.jar <lsp|mcp> [--verbose] [--log-file <path>]`

`Main.kt` holds `main()`; `Cli.kt` the options, the usage text and the version resource;
`Logging.kt` the SLF4J binding; `SmaliLanguageServer.kt` the server.

- **lsp**: `SmaliLanguageServer` wired to stdin/stdout through LSP4J's `Launcher`; the JVM
  blocks in `startListening()`.
- **mcp**: `McpMode` serves the MCP protocol over stdio with the MCP Kotlin SDK.
- **Logging**: stdout carries the protocol, so everything the server says goes to stderr.
  WARN and above by default, INFO with `--verbose`, and `--log-file` additionally writes to
  a file, started afresh each run. The SLF4J binding is the server's own (`Logging.kt`, a
  logger is a name and a level check): logback was 150 ms of a 200 ms start and 1.7 MB of the
  jar. LSP4J logs through `java.util.logging`, which `logging.properties` points at stderr.
- **Launch flags**: a cold start is bound by the JIT and by class loading, not by I/O.
  Measured over stdio with the jar on JDK 21, process start to the end of indexing, the
  initialize answer in parentheses:

  | | default JVM | `-XX:TieredStopAtLevel=1` | plus a CDS archive |
  |---|---|---|---|
  | Mastodon | 0.97 s (238 ms) | 0.40 s (216 ms) | 0.29 s (134 ms) |
  | ProtonMail | 1.54 s (239 ms) | 0.99 s (216 ms) | 0.88 s (134 ms) |
  | 76 K-file app | 4.97 s (242 ms) | 4.63 s (224 ms) | 4.33 s (139 ms) |

  The flag is the client's to pass (the README has the command): a running JVM cannot change
  its JIT level, and a relaunch through a child JVM would keep a second process for the
  session. C1-only code is 5 to 10 % slower on the heaviest requests. The archive
  (`-XX:+AutoCreateSharedArchive`, JDK 19) is not recommended: its further 80 to 110 ms cost
  three caveats (the JVM prints archive warnings on stdout unless `-Xlog:disable` is passed, a
  replaced jar leaves an archive the JVM ignores and never rebuilds, and 15 MB per jar version
  in a cache directory nothing cleans up).

### 1.2 LSP handshake

| Step | What happens |
|------|--------------|
| `initialize` | Capabilities are answered at once. Workspace folders are taken from `workspaceFolders`, else `rootUri`, else `rootPath`. |
| `initialized` | Watchers for `**/*.smali` and `**/res/values/public.xml` are registered when the client supports dynamic registration. Indexing starts in the background, with `window/workDoneProgress` only for clients that advertise it; a client that rejects the progress token still gets indexed. |
| `shutdown` / `exit` | `exit` returns 0 after a `shutdown` request and 1 otherwise, as the protocol asks; a client that closes the connection without `exit` gets the same statuses, so a dead editor leaves no server behind. MCP mode exits when its input ends (the SDK's transport stops reading at EOF but never closes the session). |

Requests arriving during indexing are answered from whatever is indexed so far. Semantic
diagnostics (unresolved classes) are suppressed until indexing completes, then re-published
for every open file, so a half-built index never floods a file with false warnings.

---

## 2. Parsing

### 2.1 The front end (`SmaliTextParser.kt`, `InstructionShapes.kt`)

A hand-written lexer and statement parser, 0.04 ms per file single-threaded. Smali is a list
of statements, one per line in everything baksmali writes, so the parser reads a statement at
a time: a directive, a `:label`, or an instruction whose operands are fixed by its opcode.
`InstructionShapes` maps each of the 224 opcodes to one of 26 operand shapes (`R_LITERAL`,
`LIST_METHOD`, `RR_FIELD`, …), a table derived mechanically from the grammar this parser
replaced, so it accepts exactly what the grammar accepted: every directive, `invoke-custom`,
`invoke-polymorphic`, `const-method-handle`, `const-method-type`, typed literal suffixes, the
debug forms of `.param`/`.local`, member names spelled like opcodes, keywords or digits.

Whitespace, including newlines, separates tokens the way the assembler's lexer treats it, so a
hand-written statement may still wrap. Tokens are spans into the source, never objects: the
lexer advances a cursor and leaves the current token's kind and bounds in fields, classifies
ASCII through lookup tables (the JDK's Unicode tables only for anything beyond), and the parser
interns names and descriptors straight from their ranges: `StringPool.intern(source, start,
end)` hashes the range and answers from a per-thread cache before creating any substring, so a
scan allocates about half of what it did (2 GB instead of 4 GB per two scans of ProtonMail).
Positions are UTF-16 string indices, so every LSP column is exact, including lines with astral
characters.

The parser has no unbounded recursion: array dimensions are a loop, and annotations nested
deeper than 64 levels are a syntax error, so no input can overflow the stack; 57,000 randomly
damaged fixture files (inserted tokens, deletions, truncations, 5,000-bracket runs, 300-deep
nests) parse without an exception on a 256 KB stack (a fuzzing probe kept outside the
repository).

**Errors and recovery.** A syntax error is reported on the offending token, with the token's
length, and parsing resumes at the next line (at the offending token itself when it begins a
line, since the statement before it ended early). One bad line costs one diagnostic, not the
rest of the file; a method cut off by the next declaration or by the end of the file is
closed there and kept. On 10,500 fixture files broken in nine ways (unknown opcode, missing
`;`, deleted `.end method`, garbage line, field without a type, missing brace, unterminated
string, missing comma, unknown directive) the parser agreed with the grammar on every case:
error or not, the first error's line, and the number of methods recovered.

**Why not the grammar.** The ANTLR lexer allocated a token object for each of 16 million
tokens and the parser a tree it then threw away: 29 to 31 times the source size in garbage,
45 MB/s. The hand-written front end runs at 165–170 MB/s with 6 to 7 times the source
allocated, 3.6× faster on ProtonMail and 4.6× on the large app, and the scan is no longer
GC-bound. The swap was verified by the per-file AST fingerprint over all 98,686 fixture files
(byte-identical to the grammar's output) and by the parser tests that pin every accepted
spelling.

`ParserCorpusTest` still parses every fixture file and fails on any syntax error or on a
file whose `.method`/`.field` count does not match the AST: 98,686 files, zero errors.

### 2.2 `SmaliParser.kt`

```
content → BOM strip → ".class" fast reject → SmaliTextParser → SmaliFile + syntax errors
```

Errors are collected as `SyntaxError(line, column, message, length)`, never thrown: a broken
method still yields the class around it.

### 2.3 What the model holds

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
| Single-thread parse, all files | 0.77 s (0.04 ms/file, 165 MB/s) | 3.8 s (170 MB/s) |
| AST heap after GC | 116 MB (5.9 KB/file) | 388 MB |

Interning the instruction strings took the ProtonMail AST from 470 MB to 293 MB for 2 to 5 %
parse time; storing instruction and label ranges as four ints instead of LSP4J `Range`
objects took it to 190 MB. The rest came from what a class histogram showed around the
instructions, none of it visible through the API: declarations now keep their range and
token spans as ints too and build `Range`/`Span` on demand (a `Range` is three objects); a
member's modifiers are one immutable set per distinct combination (`StringPool.internModifiers`,
a few dozen per workspace) instead of a `LinkedHashSet` with its table and entries; a
method's labels sit in `LabelMap`, a sorted name array and an int array behind the `Map`
interface, with the `LabelDefinition` built when one is asked for; label references, type
references, parameters, directive blocks and the `.implements` tokens are `LabelRefList`,
`TypeRefList`, `ParameterList`, `BlockList` and `RangeList`, arrays behind the `List`
interface that build their element on access; and string literals up to 64 characters are
pooled. Every value read through the getters is unchanged, which a per-file hash of the whole
AST over the three fixtures (a probe kept outside the repository) checks after each step. The instruction objects
are now half of what is left; their line ints stay exact because the lexer skips newlines,
so a hand-edited instruction may span lines.

### 2.4 Symbol resolution: `SmaliFile.symbolAt`

Providers never read line text to find out what the caret is on. `symbolAt(position)` walks
the spans and answers with a `Symbol`: `ClassDecl`, `MethodDecl`, `FieldDecl`, `ClassRef` (owner,
`.super`, `.implements`, a type in a descriptor or field type, a type operand, a directive
reference; arrays resolve to their element class), `MethodRef`, `FieldRef`, `Primitive`,
`LabelDecl`, `LabelRef`, or `OnInstruction` when the caret is on an opcode, a register or a
string. `OnInstruction` navigates nowhere (an editor underlines whatever answers go-to-definition,
and an opcode is not a link); hover reads the opcode or register from the line, and references on
the string literal are every load of the same value. A caret right after a token still counts as
that token, as editors expect.

---

## 3. The index (`WorkspaceIndex.kt`)

### 3.1 Storage

| Structure | Key | Value |
|-----------|-----|-------|
| `filesByUri` | canonical `file:///…` URI | `SmaliFile`: with its body while the file is open, declarations only otherwise |
| `uriByClass` | `Lcom/Foo;` | canonical URI of its definition (last indexed wins) |
| `subclassIndex`, `implementorsIndex` | parent / interface | direct subclasses / implementors |
| `simpleNameIndex` | `Outer$Inner` | full class names |
| `methodUsages` | `Lcom/Foo;->bar(I)V` | `UsageList` of call sites |
| `fieldUsages` | `Lcom/Foo;->baz:I` | `UsageList` of access sites |
| `classRefs` | `Lcom/Foo;` | `UsageList` of owner, operand, field-type and descriptor-type tokens |
| `stringIndex` (lazy) | literal value | `UsageList` of `const-string` sites |

Files are keyed by canonical URI: the scanner's and the client's forms of one file are one
entry, and a file whose class name changes on edit replaces its old entry instead of leaving a
ghost class. The instruction lists are most of the AST's memory (60 of ProtonMail's 113 MB) and
matter only for the open files and the occasional call-hierarchy lookup, so the scanner, the
watcher and `didClose` index a file without its body (`indexFile(file, keepBody = false)`):
the usage tables are built from the full parse, then `withoutBody()` is stored. `findFileByUri`
parses such a file again from the disk (or the open text) when a request needs its body and
keeps the last 32 bodies; `findDeclarations` returns what is stored. The distinct string
literals of each file live in a side map so a declarations-only entry can still be retracted.
Live heap after indexing: ProtonMail 198 → 118 MB, the 76 K-file app 711 → 447 MB, with scan
and request times unchanged. Field keys include the type, since DEX allows two fields of one name. Declarations
have no table: `findMethod`/`findField` look in the class's `SmaliFile` and walk up the
hierarchy, and return the name-token location.

### 3.2 `UsageList` and file ids

A usage list is a packed `IntArray`, four ints per site: file id, line, start column, end
column. Every indexing of a file gets a fresh id and retires the file's previous id, which
retires all of its earlier entries in O(1) instead of walking every symbol the file touched;
a site is stale exactly when its id is no longer alive, so it carries no generation. Ids are
never reused (an `alive` flag and a contribution count per id, grown under one lock). Readers
skip retired entries; a compaction drops them once they make up a quarter of all entries (and
at least 100 K), amortized O(1). Dropping the generation column took the packed sites from
43.8 to 35.4 MB on ProtonMail (live heap 119 → 109 MB) and the large app's live heap from
448 to 415 MB, with scan and request times unchanged.

Why: the previous `ConcurrentHashMap<String, Set<Location>>` tables cost ~130 bytes per site
and removal scanned every usage set the file touched, so a keystroke in a file that calls
`Object.<init>` walked the 76 K-entry set for that symbol.

| | ProtonMail | 76 K-file app |
|---|---|---|
| Index heap after GC | 371 MB → 90 MB → **62 MB** | 1,331 MB → 328 MB → **271 MB** |
| AST + index | 841 MB → 281 MB → **178 MB** | 2,850 MB → 1,027 MB → **659 MB** |
| Index build, single thread | 0.95 s → 0.52 s | 3.4 s → 1.4 s |
| didChange (re-parse + re-index one file), p95 | 58 ms → 0.6 ms → **0.2 ms** | 340 ms → 0.8 ms → **0.6 ms** |
| didChange, worst of 200 files | 0.9 s → 18 ms | 7.5 s → 9 ms |

The second step: the method and field tables are keyed by `MemberKey` (owner, name,
descriptor, each the AST's pooled string) instead of a concatenated `Lowner;->name(desc)`
string per symbol, which was 67 MB of keys on the large app, and the usage lists are trimmed
to their exact size once the initial scan ends (`trimUsages`), dropping the 30 % the doubling
growth leaves; they grow by half afterwards.

### 4.1 Giving memory back

The live heap is not what the OS shows. G1 keeps up to 70 % of the heap free after a
collection and never uncommits, so a server whose live data was 300 MB sat at 1.2 GB of
committed heap after the scan, and 3 GB on the large app. `JvmTuning` sets the two free-ratio
flags to 10..30 % through the HotSpot diagnostic bean at startup (they are manageable, so no
launch flag is needed) and arms G1's periodic collection: 30 s without a collection triggers a
concurrent cycle, after which G1 uncommits down to the ratio. A cycle marks the whole live
heap (about 0.4 s of CPU per gigabyte), so it is not left running: every request or
notification re-arms it, and a cycle that followed no activity disarms it. Measured on the
real server over stdio: committed heap 1,256 → 400 MB on ProtonMail and 2,992 → 1,556 MB on
the large app, a minute after indexing, with the scan time unchanged and a remark pause of 2
to 4 ms; a JVM without these flags is left as it is. The initial scan's garbage does not wait
for that minute: one explicit full collection right after the scan (`compactAfterScan`, 46 ms
on ProtonMail) compacts and uncommits it at once, to 200 MB on ProtonMail and 664 MB on the
large app.

### 3.3 Scanning (`WorkspaceScanner.kt`)

`maxOf(2, cores)` coroutine workers drain a bounded channel of files, each reading, parsing
and indexing its file. Files are walked with `Files.walkFileTree`, whose attributes answer
"regular file?" without a second stat (117 ms instead of 154 ms over ProtonMail's tree), and
read as bytes: baksmali writes ASCII, so a file without a high bit decodes as Latin-1, one copy
instead of a UTF-8 decode (288 ms instead of 427 ms for ProtonMail's 126 MB on 12 threads).
The index is safe for concurrent `indexFile` calls: maps are concurrent, usage lists
synchronize per symbol, file ids are allocated and retired under one lock. Through the real
server on 12 cores, including file reads:

| | Mastodon | ProtonMail | 76 K-file app |
|---|---|---|---|
| Cold JVM | 1.0 s | 2.2 s | 6.1 s |
| Warm JVM | 0.3 s | 1.1 s | 3.3 s |

### 3.4 String literals

`const-string` values go into a usage table like members (value → sites), retired with the
file's id like everything else, so a search after an edit never rebuilds anything. The search
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
whose declarations are unreachable. A new name is checked by parsing it as the assembler would
(letters, digits, `$`, `_`, `-` and anything beyond ASCII, a leading digit included); any
other is answered with an error naming it.

### 5.2 Hierarchies, lenses, symbols

- **Call hierarchy**: prepared from the called method when the caret is on a call, else the
  enclosing method; incoming calls are the member family's call sites grouped by containing
  method (found by binary search over the file's methods); outgoing calls from the method's
  invoke instructions, resolved through the class hierarchy. A callee outside the workspace
  has no declaration, so its item points at the first call.
- **Type hierarchy**: prepared from any class reference under the caret; supertypes from the
  class definition; subtypes from the subclass and implementor sets. A supertype outside the
  workspace is placed at the `.super` or `.implements` token that names it.
- **Implementation**: the concrete declarations of a method's family other than the one under
  the caret; for a class, every subclass and implementor.
- **Document highlight**: the references of the symbol restricted to its file; `iput`/`sput`
  sites are writes, other field sites reads.
- **Folding ranges**: multi-line methods and the annotation, switch-table and array-data
  blocks the builder records.
- **Code lens**: one lens per method and field on its declaration line, resolved lazily to
  the count of sites that bind to that declaration (an override counts its own callers); the
  `.class` line has one for the class's references and, when any, one for how many classes
  extend or implement it (a map lookup, so it comes resolved).
- **Document symbols**: readable class name, constructors as `Constructor`, descriptor and
  type as detail, name token as `selectionRange`; the class symbol's range runs to its last
  member, so an editor can tell which symbol the cursor is in.
- **Workspace symbols**: fuzzy match over classes, methods and fields, best 500 kept, each
  at its name token. The
  scan runs in parallel chunks with per-chunk top sets merged at the end: 15 to 26 ms per
  query over the 76 K-file app's 660 K names.
- **Completion**: what the caret's context can take, so the list is short and right. After
  `->`, the owner's members and the inherited ones (an override hiding what it overrides),
  narrowed by the opcode: static methods for `invoke-static`, the owner's own private methods
  and constructors for `invoke-direct`, instance methods otherwise, and when the braces are
  complete only the methods whose register count they hold (`this` and wide parameters counted
  as the frame counts them); fields by the static-ness and type the field opcode moves. Before
  it, class names of the kind the operand needs, only where a class name can start (not the
  `L` inside `onLayout` or a string): concrete for `new-instance`, an interface for
  `.implements` and an `invoke-interface` owner, non-interface for `.super`, a workspace
  throwable for `.catch`. After `:`, the method's labels; on `v` or `p` (or right after `{`),
  the frame's registers with `this`, the parameter type or the local slot; after `.`, the
  directives that belong at the caret with the innermost open block's `.end` first; on an
  empty line inside a method every opcode, the `move-result` a preceding call needs first, each
  inserting its operand shape as tab stops when the client takes snippets. Class names are
  ranked by match kind, length and name; the list is scanned once keeping the best fifty, with
  the kind check only for a name that would make the fifty, so the worst case (an `L` alone on
  the 76 K-file app) stays at a few milliseconds. An item's edit replaces the whole token the
  caret is in, so completing inside an existing name does not leave the old descriptor behind.
- **Diagnostics**: syntax errors on the offending token and the file-local checks are
  published at once: a jump to an undefined label, a label defined twice, and the assembler
  checks (`AssemblerChecks`, driven by the opcode database's format table: register widths per
  operand, five 4-bit registers for `35c` calls, the second register of a wide pair, literal
  ranges for `11n`/`21s`/`21h`/`22b`/`22s`, the register count of a call against its
  descriptor's slots, `return` and `move-result` kinds against the method's and the call's
  return type, `move-result` placement, one `.registers` or `.locals`); unresolved classes in
  `.super`, `.implements`, field types and instruction targets wait for indexing to finish and
  are reported only when the class's package is one the workspace defines classes in (the
  file's own package included), and a call or field access whose kind contradicts the
  declaration the workspace holds (`invoke-static` on an instance method, `sget` on an instance
  field, and the reverse) is an error naming the right opcode. A method or field that neither
  the owner nor any supertype in the workspace declares is a warning naming the closest declared
  member, which its quick fix inserts; told only when every supertype up to `Object` is in the
  workspace, since one outside may declare it, and a constructor is the owner's alone; a
  platform-named class in the workspace is a stub, so its hierarchy counts as outside. The two
  open-source fixtures produce none; the 76 K-file app has six call sites whose target exists
  nowhere in the APK (library version skew), which the warning reports as it should. A
  decompiled app also references classes it does not ship, such as `org.xmlpull` from the
  platform or an optional library like Conscrypt behind a runtime check, and those are not
  errors; a hand-edited name inside the app's own packages is. The three fixtures went from
  652 such warnings to none.
- **Resource ids**: `ResourceTable` reads each workspace root's `res/values/public.xml` once
  after the scan (and again when the watcher reports it changed) into a map of id → entry,
  plus the values the default `res/values/*.xml` files give strings, colors, dimensions,
  integers and booleans. Hover on a `0x7f...` literal names the resource (`id/login_button`)
  and shows its value; definition opens the value's line, the file a layout or drawable is, or
  the `public.xml` entry. `android.R` ids (`0x01TTEEEE`) are named by `FrameworkResources`, a
  bundled table of the framework's 14,516 public ids (`src/main/resources/android-public.txt`,
  written by `./gradlew generateAndroidPublic` from the `public.xml` of the framework apktool
  ships; public ids never change, so one table serves every app). The literal is read
  from the line on demand, like registers, so the AST is untouched.
- **Quick fixes**: a diagnostic with one right answer carries it in its `data` (`Fixes`: the
  opcode to use, that the line goes, the `/range` form, the frame count, the label meant), set
  where the check already knows it. `CodeActionProvider` recomputes the file's diagnostics on
  request, keeps those on the requested lines and builds each edit from the current text, so a
  stale diagnostic on the client's side cannot misplace an edit. A fix that would have to guess
  (a register for `return`, the registers a call is missing) is not offered.
- **Formatting**: `SmaliFormatter` re-indents by a stack of blocks (`.method`, `.annotation`,
  `.subannotation`, a switch or array payload, an annotation value's braces, a `.field` or
  `.param` that turns out to carry annotations) and re-spaces each line's tokens, strings and
  comments kept verbatim. It never joins or splits a line and leaves blank lines where they
  are, because baksmali's own vary with the section. baksmali output formats to itself, which
  the real-app invariants check on every sampled file, so the formatter is also the statement
  of the layout. Edits are per changed line; range formatting lays out the whole document and
  keeps the lines asked for.
- **Hover**: the declaration as smali in a code block (`.class` with its `.super` and
  `.implements`, the `.method` or `.field` line, a platform member's reference as written),
  then one line to take in at a glance: the Java-style signature with short type names and
  the register each parameter arrives in, the owner, the reference count through the member
  family, member and subtype counts for a class. A class or member the workspace does not
  define links to its page on developer.android.com when it is one the site documents (java,
  javax, android, androidx, dalvik but not its hidden annotations, org.json, org.w3c, org.xml),
  else says it is not in the
  workspace. Opcode hovers show the syntax as smali, the description, and the operands, byte
  and format on one line; types, labels and strings are one line each. Registers, resource
  ids and the `.registers` frame keep their own forms.
- **Semantic tokens**: `SemanticTokensProvider` classifies every token of an open file: class,
  member, type and label tokens from the AST's spans (the owner, name and descriptor types of
  each invoke and field access, declarations, `typeRefs`), the rest (directives, modifiers,
  opcodes, registers, literals, strings, comments) from a scan of the lines; where the two
  overlap the AST wins; the debug directives (`.line`, `.local`, `.param`, `.source`, ...) are
  one comment token per line, so a theme dims them like the comments they amount to and the
  code stands out. Never a re-parse. The last full result per open file is kept, with the
  text it came from, so `full/delta` answers with one edit (common prefix and suffix) and an
  unchanged buffer with none; `range` scans only its lines and walks only the methods that
  reach them, which is what an editor asks for first on a 180 K-line file (0.2 ms there, 68 ms
  for the full 342 K tokens). SDK classes and members carry `defaultLibrary`, statics `static`.
  `label` is a custom type in the legend; the others are the standard ones.
- **Inlay hints**: `InlayHints` scans the requested lines once with `Literals.find`, which
  reads the one literal a line can carry: the last operand of an instruction, a `.field` value,
  an annotation element, an `.array-data` element, a `.sparse-switch` key or the `.packed-switch`
  first key. A resource id becomes its value (name in the tooltip), or its name when it has no
  value; any other hex literal becomes its decimal value (`= 1000`). Values -9..9, decimal
  literals, bytes and shorts (`0x41t`), resource-shaped ids the table does not know, and lines
  baksmali already annotated (`0x3f800000    # 1.0f`) get nothing. Resource hover and definition read the same literal.
- **Strings**: `const-string` sites are indexed by value like member usages, so references
  on a string are every load of the same value and its hover carries the count.
- **Registers**: not in the AST (they would double it for nothing navigable). `InstructionText`
  decodes one instruction line (opcode, register tokens with columns, range form, literal) and
  `RegisterFrame` maps `pN` to its frame slot from `.registers`/`.locals` and the parameter
  types; hover and document highlight on a register scan the method's lines on demand, the
  `.registers` line hovers the whole frame (which `vN` the locals, `this` and each parameter
  occupy), and the assembler checks scan the edited file's lines in the diagnostics pass.

### 5.3 What still reads line text

Completion works on the text being typed by nature. The opcode-documentation hover takes the
first word of the line, because instructions without symbols have no AST node. Both read the
open document's buffer, or the file on disk when the document is not open (MCP by position).

---

## 6. MCP mode (`McpMode.kt`)

`smali_index` scans a directory into a fresh `WorkspaceIndex` and swaps in one immutable
`Session(index, providers)`; the old session is dropped before the scan so two indexes of a
large app never coexist. A registration helper declares each tool's arguments once, answers a
missing index or argument uniformly, and turns any failure into a tool error. A call to a
tool that does not exist is the one protocol error: the session's `tools/call` handler answers
it with JSON-RPC "invalid params" (-32602), as the specification asks, where the SDK alone
would return a tool result marked as an error.

| Tool | Answers |
|------|---------|
| `smali_index` | scan a directory |
| `smali_find_definition` | a class by full or simple name; the declaration at a position; a member by `class_name` + `member_name` |
| `smali_search_symbols` | fuzzy symbol search, at most `max_results` (default and cap 500) |
| `smali_get_stats` | counts |
| `smali_find_references` | by position (`uri`, `line`, `character`) or by name (`class_name`, optional `member_name` with `descriptor` or `field_type`); at most `max_results` (default 500, cap 5,000) with the full `total` |
| `smali_hover` | same two forms |
| `smali_diagnostics` | diagnostics for given content |
| `smali_document_symbols` | a file's outline, by `uri` or `class_name` |
| `smali_search_strings` | `const-string` literals, at most `max_results` (default 100, cap 5,000) |
| `smali_call_graph` | callers and callees of a method |
| `smali_xref_summary` | subclasses, implementors, callers, accessors, referencing classes |
| `smali_type_hierarchy` | supertypes and subtypes |
| `smali_read_method` | one method's numbered source lines, resource ids named in a trailing comment |
| `smali_list_package` | the classes under a package prefix and its sub-packages |

Arguments are validated by type (a wrongly typed one is a tool error naming it), tool
handlers run concurrently with the SDK's 0.15 dispatch, and `smali_index` holds a lock so
only one index is built at a time.

---

## 7. Design decisions

- **A hand-written parser, not a grammar.** The project started on an ANTLR grammar, moved
  the model building into a parse listener (5.1 s → 2.7 s on ProtonMail), and then replaced
  the grammar altogether: an object per token and a parse tree per file cost 30× the source in
  garbage for a format that is a list of lines. The replacement is 1.2 K lines of Kotlin against
  1.5 K lines of grammar, a 760-line listener and a generated parser, parses 3.6–4.6× faster, and its operand
  table is derived from the grammar so nothing accepted was lost; the per-file AST
  fingerprint over the corpus proved the output byte-identical before the grammar was removed.
- **Spans in the AST.** One int per member reference gives exact token ranges; the alternative,
  re-reading lines and matching names with `indexOf`, mis-resolved duplicated names and needed
  file reads for closed documents.
- **Full ASTs in memory.** Hover, call hierarchy, diagnostics and the MCP tools all need
  instruction lists. At about 5.9 KB per file after interning and packing, the AST is two
  thirds of the heap; re-parsing on demand would save it at the cost of a disk read per
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
  by default; `-PintegrationHeap=4g -Dsmali.invariants.maxFiles=100000` takes it in.
- Find-references on `Ljava/lang/Object;` answers with every owner, operand and descriptor
  use: 264 K locations on the 76 K-file app, serialized in well under a second but more than
  an editor can show. The LSP answer has no cap (a cancelled request stops early); the MCP
  tool caps its answer and reports the total.

---

## 9. Verification

- Three test tasks, each with the heap its tests need, run one after another by `check`:
  `test` (the unit category, 545 tests), `regressionTest` (8) and `integrationTest` (82 with
  the fixtures present; one skip). CI runs them on JDK 17 and 25 and smoke-tests the jar with
  an LSP and an MCP session.
- `ParserCorpusTest`: every fixture file parses without syntax errors and without losing a
  `.method` or `.field`.
- `RealApkInvariantsTest`: properties that hold for any apktool project (every invoke in the
  workspace resolves, hierarchy sets agree with `.super`/`.implements`, usage locations name
  the member, re-indexing is a no-op), so fixtures can change version without rewriting tests.
- `LspStdioTest` and `McpModeTest` drive the real binaries over stdio with JSON-RPC.
- Before a release, a scripted stdio session against a real app exercises every request at
  about a thousand positions (declarations, references, operands, labels, directives,
  opcodes), compares the answers with the previous build, and checks each definition,
  reference line and rename edit against the source text; the same for the fourteen MCP tools.
- Parser and builder changes are checked with a per-file fingerprint of every AST in the corpus
  against the previous build; a builder change must leave every pre-existing field identical.

---

## 10. File inventory

| File | Role |
|------|------|
| SmaliTextParser.kt | the front end: lexer, statement parser, model building |
| DalvikOpcodeDatabase.kt | opcode documentation for hover and completion |
| McpMode.kt | MCP server |
| WorkspaceIndex.kt | the index |
| DataStructures.kt | the AST model |
| AssemblerChecks.kt | assembler checks for hand edits |
| SmaliLanguageServer.kt | the LSP server: handshake, indexing, capabilities |
| SmaliTextDocumentService.kt | document lifecycle and requests |
| SemanticTokensProvider.kt | semantic tokens |
| HoverProvider.kt | hover |
| ReferenceProvider.kt | references and member families |
| Types.kt | descriptor and class-name helpers |
| InstructionText.kt | instruction scanner and register frame |
| WorkspaceSymbolProvider.kt | workspace symbol search |
| DiagnosticProvider.kt | diagnostics |
| CodeActionProvider.kt | quick fixes from the diagnostics' fix data |
| SmaliFormatter.kt | formatting |
| CompletionProvider.kt | completion |
| ResourceTable.kt | `public.xml` and values per workspace root |
| WorkspaceScanner.kt | parallel scan |
| Registers.kt | register hover, highlight and the frame hover |
| Symbol.kt | `symbolAt` and the symbol kinds |
| RenameProvider.kt | rename |
| CallHierarchyProvider.kt | call hierarchy |
| DocumentHighlightProvider.kt | document highlight |
| SmaliWorkspaceService.kt | watched files, workspace symbols |
| CodeLensProvider.kt | reference and subtype counts |
| TypeHierarchyProvider.kt | type hierarchy |
| JvmTuning.kt | heap settings applied in-process |
| InstructionShapes.kt | opcode → operand shape, derived from the grammar |
| StringPool.kt | per-index interning |
| ResourceLiterals.kt | resource hover, definition and hints |
| WriteQueue.kt | the writer thread and the request helper |
| DocumentSymbolProvider.kt | outline |
| Literals.kt | the one literal a line carries |
| SourceFiles.kt | file reads: bytes, Latin-1 when pure ASCII, else UTF-8 |
| Cli.kt | command line, usage, version |
| DefinitionProvider.kt | definition |
| InlayHints.kt | resource names and decimal values after literals |
| UsageList.kt | packed usage sites |
| LabelMap.kt | packed labels and label references |
| SmaliParser.kt | parser entry |
| FrameworkResources.kt | the bundled `android.R` names |
| ImplementationProvider.kt | implementations |
| Logging.kt | the SLF4J binding: stderr, the log file, levels |
| Main.kt | entry point |
| FoldingRangeProvider.kt | folding ranges |
| StringLiterals.kt | the string operand under the caret |