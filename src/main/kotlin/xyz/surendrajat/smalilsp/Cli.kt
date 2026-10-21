package xyz.surendrajat.smalilsp

/** Command line of the `smali-lsp` jar: `<command> [--verbose] [--log-file <path>]`, the version resource, and the usage text. */
data class VersionInfo(val version: String, val commit: String, val buildTime: String)

internal fun loadVersionInfo(): VersionInfo {
    val props = java.util.Properties()
    val stream = SmaliLanguageServer::class.java.getResourceAsStream("/version.properties")
    if (stream != null) {
        stream.use { props.load(it) }
    }

    val version = props.getProperty("version", "unknown")
    val commit = props.getProperty("commit", "unknown")
    val buildTime = props.getProperty("buildTime", "unknown")
    return VersionInfo(version, commit, buildTime)
}

internal data class Options(val command: String, val verbose: Boolean, val logFile: String?)

/** `<command> [--verbose] [--log-file <path>]`; null when an option is malformed. */
internal fun parseArgs(args: Array<String>): Options? {
    var command: String? = null
    var verbose = false
    var logFile: String? = null
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--verbose" -> verbose = true
            "--log-file" -> {
                logFile = args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") } ?: run {
                    System.err.println("--log-file needs a path")
                    return null
                }
                i++
            }
            else -> if (command == null) command = arg else {
                System.err.println("Unexpected argument: $arg")
                return null
            }
        }
        i++
    }
    return Options(command ?: "help", verbose, logFile)
}

internal fun printUsage() {
    val info = loadVersionInfo()
    println("""
        smali-lsp v${info.version} — Language Server & MCP server for Smali

        Usage: java -jar smali-lsp-<version>.jar <command> [options]

        Commands:
          lsp               Start LSP server over stdio (for IDE integration)
          mcp               Start MCP server over stdio (for AI agent integration)

        Options:
          --verbose         Log at INFO instead of WARN (warnings always go to stderr)
          --log-file <path> Also write the log to this file
          --version         Show version info
          --help            Show this help message
    """.trimIndent())
}
