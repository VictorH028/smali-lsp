package xyz.surendrajat.smalilsp

import xyz.surendrajat.smalilsp.cli.McpMode
import kotlin.system.exitProcess

/**
 * Entry point of the jar: `lsp` runs the language server over stdio, `mcp` the MCP server over
 * stdio; both keep the same index and providers in memory. See [parseArgs] for the options.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args) ?: run {
        printUsage()
        exitProcess(2)
    }
    configureLogging(options.verbose, options.logFile)

    when (options.command) {
        "lsp" -> startLsp()
        "mcp" -> McpMode().run()
        "--version", "-v" -> {
            val info = loadVersionInfo()
            println("smali-lsp v${info.version}+${info.commit} (built ${info.buildTime})")
        }
        "help", "--help", "-h" -> printUsage()
        else -> {
            System.err.println("Unknown command: ${options.command}")
            printUsage()
            exitProcess(1)
        }
    }
}
