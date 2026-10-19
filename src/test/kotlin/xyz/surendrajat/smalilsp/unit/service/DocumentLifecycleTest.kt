package xyz.surendrajat.smalilsp.unit.service

import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.SmaliTextDocumentService
import xyz.surendrajat.smalilsp.SmaliWorkspaceService
import xyz.surendrajat.smalilsp.WriteQueue
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.*
import xyz.surendrajat.smalilsp.shared.TestLSPClient
import java.io.File

/** The index follows the editor's buffer while a file is open and the disk once it is closed. */
class DocumentLifecycleTest {

    @TempDir
    lateinit var dir: File

    private val index = WorkspaceIndex()
    private val writes = WriteQueue()
    private val documents = SmaliTextDocumentService(
        index, DefinitionProvider(index), HoverProvider(index), ReferenceProvider(index), CallHierarchyProvider(index),
        TypeHierarchyProvider(index), CodeLensProvider(index), CompletionProvider(index), RenameProvider(index), writes
    ).also { it.connect(TestLSPClient()); it.indexingComplete = true }
    private val workspace = SmaliWorkspaceService(index, writes)

    private fun source(vararg methods: String) = buildString {
        append(".class public Lcom/Doc;\n.super Ljava/lang/Object;\n")
        for (m in methods) append("\n.method public $m()V\n    .registers 1\n    return-void\n.end method\n")
    }

    private fun methodsOf(uri: String) = index.findFileByUri(uri)?.methods?.map { it.name }

    private fun open(uri: String, text: String) { documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "smali", 1, text))); documents.awaitWrites() }
    private fun close(uri: String) { documents.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri))); documents.awaitWrites() }
    private fun watched(uri: String, type: FileChangeType) { workspace.didChangeWatchedFiles(DidChangeWatchedFilesParams(listOf(FileEvent(uri, type)))); workspace.awaitWrites() }

    @Test
    fun `the editor's text replaces the scanned file on open and the disk version returns on close`() {
        val file = File(dir, "Doc.smali").apply { writeText(source("onDisk")) }
        val uri = file.toPath().toUri().toString()
        index.indexFile(SmaliParser().parse(uri, file.readText())!!)

        open(uri, source("onDisk", "unsaved"))
        assertEquals(listOf("onDisk", "unsaved"), methodsOf(uri), "a restored unsaved buffer is what the user sees")

        close(uri)
        assertEquals(listOf("onDisk"), methodsOf(uri), "closing without saving discards the edit")
    }

    @Test
    fun `a buffer that was never saved leaves the index when closed`() {
        val uri = File(dir, "Never.smali").toPath().toUri().toString()
        open(uri, source("draft"))
        assertEquals(listOf("draft"), methodsOf(uri))
        close(uri)
        assertNull(index.findFileByUri(uri))
    }

    @Test
    fun `a watched change does not override an open buffer`() {
        val file = File(dir, "Doc.smali").apply { writeText(source("onDisk")) }
        val uri = file.toPath().toUri().toString()
        open(uri, source("onDisk", "typed"))
        file.writeText(source("regenerated"))

        watched(uri, FileChangeType.Changed)
        assertEquals(listOf("onDisk", "typed"), methodsOf(uri), "the editor reports its own buffer")

        close(uri)
        assertEquals(listOf("regenerated"), methodsOf(uri), "closed: the disk version is indexed")

        file.writeText(source("again"))
        watched(uri, FileChangeType.Changed)
        assertEquals(listOf("again"), methodsOf(uri), "not open any more: watched changes apply")
    }

    @Test
    fun `a removed workspace folder drops its files and an added one is handed to the server`() {
        val folder = File(dir, "lib").apply { mkdirs() }
        val file = File(folder, "Lib.smali").apply { writeText(".class public Lcom/Lib;\n.super Ljava/lang/Object;") }
        index.indexFile(SmaliParser().parse(file.toPath().toUri().toString(), file.readText())!!)
        val publicXml = xyz.surendrajat.smalilsp.index.ResourceTable.publicXml(folder).apply { parentFile.mkdirs(); writeText("<resources>\n    <public type=\"id\" name=\"go\" id=\"0x7f0a0001\" />\n</resources>\n") }
        assertEquals(1, index.resources.load(publicXml))
        val added = mutableListOf<String>()
        workspace.onFoldersAdded = { folders -> added.addAll(folders.map { it.uri }) }

        val folderUri = folder.toPath().toUri().toString()
        workspace.didChangeWorkspaceFolders(org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams(org.eclipse.lsp4j.WorkspaceFoldersChangeEvent(listOf(org.eclipse.lsp4j.WorkspaceFolder("file:///elsewhere", "new")), listOf(org.eclipse.lsp4j.WorkspaceFolder(folderUri, "lib")))))
        workspace.awaitWrites()
        assertNull(index.findClass("Lcom/Lib;"), "the closed folder's class is gone")
        assertEquals(0, index.resources.size, "and so are its resources")
        assertEquals(listOf("file:///elsewhere"), added)
    }
}
