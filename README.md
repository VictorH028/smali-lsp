# smali-lsp

A Language Server Protocol (LSP) server with built-in MCP server for [Smali](https://github.com/google/smali) — the bytecode language of Android apps.

## Features

### LSP Server (for IDEs)

- **Go to Definition** — jump from any instruction to its target (classes, methods, fields, labels)
- **Find References** — cross-file xrefs with inheritance-aware polymorphic matching
- **Hover** — method signatures, field types, class info, SDK class detection
- **Call Hierarchy** — incoming/outgoing call graphs for any method
- **Type Hierarchy** — supertypes/subtypes navigation for classes
- **Code Lens** — inline reference counts on methods and fields
- **Completion** — class names, method/field members, opcodes
- **Rename** — rename methods, fields, and labels across workspace
- **Document Symbols** — class outline with methods and fields
- **Workspace Symbols** — fuzzy search across the entire codebase
- **Diagnostics** — syntax errors and unresolved class references
- **Label Navigation** — goto/if-\*/switch targets, `.catch` blocks and switch tables within methods
- **Every Dalvik instruction parses** (224 opcode tokens, including `invoke-custom`, `invoke-polymorphic` and method handles); navigation covers invoke-\*, field access, type operands, labels and strings

### MCP Server (for AI agents)

Built-in [MCP](https://modelcontextprotocol.io) server for full semantic understanding of decompiled APKs by AI agents — various tools covering indexing, navigation, search, call graphs, and cross-references.

<details>
<summary>Available tools</summary>

| Tool                     | Description                                                                      |
| ------------------------ | -------------------------------------------------------------------------------- |
| `smali_index`            | Index a directory of smali files                                                 |
| `smali_find_definition`  | Go to definition for a symbol at a position                                      |
| `smali_search_symbols`   | Fuzzy search across classes, methods, fields                                     |
| `smali_get_stats`        | Index statistics (class/method/field/string counts)                              |
| `smali_find_references`  | Find all references to a symbol, by position or by class/member name             |
| `smali_hover`            | Get hover info (signatures, types, class details), by position or by name        |
| `smali_diagnostics`      | Compute diagnostics for a file                                                   |
| `smali_document_symbols` | Get document outline (classes, methods, fields)                                  |
| `smali_search_strings`   | Search const-string literals by substring                                        |
| `smali_call_graph`       | Incoming/outgoing call graph for a method                                        |
| `smali_xref_summary`     | Cross-reference report (subclasses, implementors, callers, accessors, referencing classes) |
| `smali_type_hierarchy`   | Supertypes and subtypes for a class (inheritance chain)                          |

</details>


## Usage

### LSP Server

The server communicates over **stdio** in standard LSP protocol — no daemon, no port, just stdio:

```bash
java -jar build/libs/smali-lsp-<version>.jar lsp
```

Configure your editor's LSP client to launch this command for `.smali` files. The server indexes the workspace on startup and watches it for changes.

Warnings go to stderr; `--verbose` logs at INFO and `--log-file <path>` also writes the log to a file.

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

Measured on three apktool projects (Mastodon v2.13.4, ProtonMail 7.11.5 and a 76,532-file production app) on a 12-core Apple Silicon laptop, JDK 21:

| | Mastodon (2,517 files) | ProtonMail (19,637 files) | 76 K-file app |
| --- | --- | --- | --- |
| Index the workspace (parallel, cold) | 1.6 s | 2.7 s | 8.8 s |
| Heap after indexing (AST + index) | — | 383 MB | 1.3 GB |
| Re-parse and re-index one file on edit, p95 | — | 0.6 ms | 0.8 ms |

- Definition, hover, references, rename and code lens are index lookups: well under a millisecond each
- Every file of all three apps parses with zero syntax errors and no lost members (98,686 files)
- Every workspace invoke and field access resolves to its declaration, as checked by the fixture-invariant tests
- Single-thread parse throughput: 0.14 ms per file (ProtonMail, 2.7 s for all files)

## Building

Requires Java 17+; the Gradle wrapper (9.6) downloads itself.

```bash
./gradlew shadowJar
```

Output: `build/libs/smali-lsp-<version>.jar`

## Architecture

```
ANTLR4 Grammar → SmaliParser → ASTBuilder → WorkspaceIndex → LSP/MCP Providers
                                                ↑
                                    WorkspaceScanner (parallel)
```

- **Parser**: ANTLR4 with SLL prediction; the AST is built by a parse listener and records the span of every navigable token
- **Index**: files by canonical URI, class hierarchy sets, and packed usage lists retired per file by a generation counter, so a re-index is O(file)
- **Providers**: resolve the caret from the AST (`SmaliFile.symbolAt`) and answer from the index; one writer thread applies edits, requests wait for the edits sent before them
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

This project is licensed under the [GNU General Public License v3.0](LICENSE), except files under `src/main/antlr/` which are MIT licensed (see header in each file).

## Credits

- @psygate for https://github.com/psygate/smali-antlr4-grammar
