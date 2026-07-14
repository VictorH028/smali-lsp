package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.SmaliWorkspaceService
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test: on watched-file Created/Changed events the service re-indexed
 * the file without first evicting whatever class that URI previously defined.
 * WorkspaceIndex.indexFile() only cleans up entries keyed by the NEW class name,
 * so when apktool regenerates a file with a different class in it, the old class
 * lingered as a ghost definition (go-to-definition navigated to a class that no
 * longer exists at that URI).
 */
class WatchedFileClassRenameRegressionTest {

    @Test
    fun `class renamed in place evicts the previous class entry`(@TempDir tmp: Path) {
        val index = WorkspaceIndex()
        val service = SmaliWorkspaceService(index)
        val file = tmp.resolve("Foo.smali").toFile()
        val uri = file.toURI().toString()

        file.writeText(".class public Lcom/example/Foo;\n.super Ljava/lang/Object;\n")
        service.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Created)))
        )
        assertTrue(index.hasClass("Lcom/example/Foo;"))

        // apktool regenerated the file with a different class in it
        file.writeText(".class public Lcom/example/Bar;\n.super Ljava/lang/Object;\n")
        service.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Changed)))
        )

        assertTrue(index.hasClass("Lcom/example/Bar;"), "new class must be indexed")
        assertFalse(index.hasClass("Lcom/example/Foo;"),
            "ghost entry for the previous class at this URI must be evicted")
    }
}
