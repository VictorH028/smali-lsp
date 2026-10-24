# smali-lsp

A Language Server Protocol (LSP) server with built-in MCP server for [Smali](https://github.com/google/smali) — the bytecode language of Android apps.

## Features

### LSP Server (for IDEs)

- **Go to Definition** — classes, methods, fields, labels and resource ids
- **Find References** — cross-file xrefs with inheritance-aware polymorphic matching; string literals too
- **Hover** — the declaration as smali and a one-line signature with each parameter's register; registers, frames, labels, strings, resource ids, opcode docs; platform members link to the Android reference
- **Semantic tokens** — every token classified, full, delta and range: highlighting for editors without a smali grammar
- **Inlay hints** — resource names or values after `0x7f…` and `android.R` ids, the decimal value after other hex literals
- **Call Hierarchy** — incoming/outgoing call graphs for any method
- **Type Hierarchy** — supertypes/subtypes navigation for classes
- **Code Lens** — reference counts on classes, methods and fields; subclass and implementation counts
- **Completion** — the members the opcode and register count allow, classes of the kind the operand needs, labels, registers, directives, opcodes as snippets
- **Rename** — rename methods, fields, and labels across workspace
- **Document Symbols** — class outline with methods and fields
- **Workspace Symbols** — fuzzy search across the entire codebase
- **Diagnostics** — syntax errors, labels, undefined classes in the app's own packages, and what the assembler or verifier would reject after a hand edit
- **Quick fixes** — one code action per diagnostic with a single right answer
- **Formatting** — baksmali's layout for the document or a range; blank lines, strings and comments stay as written
- **Implementations, document highlight, folding** — overrides and implementors, occurrences of a symbol or register in its file, foldable blocks
- **Label Navigation** — goto/if-\*/switch targets, `.catch` blocks and switch tables within methods
- **Every Dalvik instruction parses** — 224 opcodes, including `invoke-custom`, `invoke-polymorphic` and method handles

<details>
<summary>Supported requests</summary>

| Request | Notes |
| --- | --- |
| `initialize` / `initialized` / `shutdown` / `exit` | workspace folders or `rootUri`; indexing after `initialized`, with progress |
| `textDocument/didOpen` `didChange` `didClose` | full sync; the buffer while open, the disk version after close |
| `textDocument/publishDiagnostics` | syntax errors at once, undefined classes once indexing has finished |
| `textDocument/codeAction` | quick fixes for the diagnostics on the requested lines |
| `textDocument/formatting` · `rangeFormatting` | baksmali's layout, one edit per changed line |
| `textDocument/definition` `hover` `references` `implementation` `documentHighlight` | references follow overrides and calls through subclasses |
| `textDocument/foldingRange` | methods, annotations, switch tables, array data |
| `textDocument/documentSymbol` · `workspace/symbol` | outline; fuzzy search, best 500 |
| `textDocument/codeLens` + `codeLens/resolve` | counts resolved lazily |
| `textDocument/inlayHint` | resource names and values, decimal values |
| `textDocument/semanticTokens/full` `full/delta` `range` | from the open file's model, never a re-parse |
| `textDocument/completion` | trigger characters `L`, `>`, `/`, `:`, `.`, `{` |
| `textDocument/prepareRename` + `rename` | methods, fields, labels |
| `textDocument/prepareCallHierarchy` + `callHierarchy/incomingCalls` `outgoingCalls` | |
| `textDocument/prepareTypeHierarchy` + `typeHierarchy/supertypes` `subtypes` | |
| `workspace/didChangeWatchedFiles` | `**/*.smali` and `res/values/public.xml`, registered dynamically |
| `workspace/didChangeWorkspaceFolders` | added folders are indexed, removed ones dropped |
| `$/cancelRequest` | honoured by symbol search and reference lists |

</details>

### MCP Server (for AI agents)

Built-in [MCP](https://modelcontextprotocol.io) server for full semantic understanding of decompiled APKs by AI agents — various tools covering indexing, navigation, search, call graphs, and cross-references.

<details>
<summary>Available tools</summary>

| Tool                     | Description                                                                      |
| ------------------------ | -------------------------------------------------------------------------------- |
| `smali_index`            | Index a directory of smali files                                                 |
| `smali_find_definition`  | Go to definition, by position or by class and member name                        |
| `smali_search_symbols`   | Fuzzy search across classes, methods, fields                                     |
| `smali_get_stats`        | Index statistics (class/method/field/string counts)                              |
| `smali_find_references`  | Find all references to a symbol, by position or by name (bounded)               |
| `smali_hover`            | Get hover info (signatures, types, class details)                                |
| `smali_diagnostics`      | Compute diagnostics for a file                                                   |
| `smali_document_symbols` | Get document outline (classes, methods, fields)                                  |
| `smali_search_strings`   | Search const-string literals by substring                                        |
| `smali_call_graph`       | Incoming/outgoing call graph for a method                                        |
| `smali_xref_summary`     | Full cross-reference report (subclasses, implementors, callers, field accessors) |
| `smali_type_hierarchy`   | Supertypes and subtypes for a class (inheritance chain)                          |
| `smali_read_method`      | One method's source, numbered, resource ids annotated                            |
| `smali_list_package`     | Classes under a package prefix and its sub-packages                              |

</details>


## Usage

### LSP Server

The server communicates over **stdio** in standard LSP protocol — no daemon, no port, just stdio:

```bash
java -jar build/libs/smali-lsp-<version>.jar lsp
```

Configure your editor's LSP client to launch this command for `.smali` files. The server indexes the workspace on startup and watches it for changes. Warnings go to stderr; `--verbose` and `--log-file <path>` give more.

<details>
<summary>VS Code (via APKLab)</summary>

[APKLab](https://github.com/APKLab/APKLab) integrates smali-lsp automatically. Install APKLab -> Run update tools command and the LSP starts when you open a `.smali` file.
Alternatively, you can set `apklab.smaliLspPath` to your `smali-lsp-<version>.jar`.

</details>

<details>
<summary>Neovim (via nvim-lspconfig)</summary>

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.smali_lsp then
  configs.smali_lsp = {
    default_config = {
      cmd = { 'java', '-jar', '/path/to/smali-lsp-<version>.jar', 'lsp' },
      filetypes = { 'smali' },
      root_dir = function(fname)
        return lspconfig.util.root_pattern('AndroidManifest.xml', 'apktool.yml', '.git')(fname)
      end,
    },
  }
end

lspconfig.smali_lsp.setup {}
```

</details>

<details>
<summary>Helix</summary>

```toml
# ~/.config/helix/languages.toml
[[language]]
name = "smali"
scope = "source.smali"
file-types = ["smali"]
language-servers = ["smali-lsp"]

[language-server.smali-lsp]
command = "java"
args = ["-jar", "/path/to/smali-lsp-<version>.jar", "lsp"]
```

</details>

<details>
<summary>Emacs (eglot)</summary>

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(smali-mode . ("java" "-jar" "/path/to/smali-lsp-<version>.jar" "lsp"))))
```

</details>

### MCP Server

```bash
java -jar build/libs/smali-lsp-<version>.jar mcp
```

Runs as an [MCP](https://modelcontextprotocol.io) server over stdio, exposing smali analysis tools to AI agents (Claude, Cursor, etc.).

Add to your MCP config (`.vscode/mcp.json`,`claude_desktop_config.json`, Cursor settings, etc.):

```json
{
  "mcpServers": {
    "smali-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/smali-lsp-<version>.jar", "mcp"]
    }
  }
}
```

## Performance

Measured on three apktool projects on a 12-core Apple Silicon laptop, JDK 21:

| | Mastodon (2,517 files) | ProtonMail (19,637 files) | 76 K-file app |
| --- | --- | --- | --- |
| Index the workspace, cold / warm JVM | 1.0 / 0.3 s | 2.2 / 1.1 s | 6.1 / 3.3 s |
| Heap after indexing | — | 109 MB | 415 MB |
| Re-index one file on edit, p95 | — | 0.2 ms | 0.6 ms |
| Workspace symbol search | — | 4–14 ms | 19–25 ms |

Definition, hover, references, rename and code lens are index lookups, well under a millisecond. All 98,686 files of the three apps parse without errors.

## Building

Requires Java 17+; the Gradle wrapper (9.8) downloads itself.

```bash
./gradlew shadowJar
```

Output: `build/libs/smali-lsp-<version>.jar`. `./gradlew installApklab` copies it to `~/.apklab/smali-lsp-dev.jar` for APKLab.

## Architecture

```
SmaliTextParser (hand-written) → SmaliParser → WorkspaceIndex → LSP/MCP Providers
                                                ↑
                                    WorkspaceScanner (parallel)
```

- **Parser**: hand-written lexer and statement parser, 0.04 ms per file, token spans in the AST
- **Index**: files by canonical URI, class hierarchy sets, packed usage lists retired per file
- **Providers**: resolve the caret from the AST and answer from the index; one writer thread, requests wait for earlier edits
- **Scanner**: one coroutine worker per core

See [DESIGN.md](DESIGN.md) for the measured costs and the reasoning.

## Running Tests

Run the categories separately; each test fork holds a parsed app in memory:

```bash
./gradlew test --tests "*.unit.*"
./gradlew test --tests "*.regression.*"
./gradlew test --tests "*.integration.*"
```

The integration category uses apktool projects under `test-data/` when present (public app releases work; the directory is gitignored) and skips those checks otherwise.

## License

Copyright (C) 2026 Surendrajat

This project is licensed under the [GNU General Public License v3.0](LICENSE).

## Credits

- @psygate for https://github.com/psygate/smali-antlr4-grammar, the grammar the parser's operand table was derived from
