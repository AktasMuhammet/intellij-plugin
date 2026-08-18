package ai.blamely.git

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A project dir opened ABOVE its repos — `project/{backend,frontend}`, each its own
 * clone — is in no git repo itself, and `git rev-parse` only searches upward. Before
 * [GitUtils.discoverRepoRoots] the gutter, tool window and HEAD watch all resolved
 * nothing there, so Blamely looked completely dead even though the edits were
 * captured. These tests pin the downward scan and its bounds.
 */
class GitUtilsDiscoverReposTest {

    @TempDir
    lateinit var ws: File

    @BeforeEach
    fun setUp() = GitUtils.clearRepoRootCache()

    @AfterEach
    fun tearDown() = GitUtils.clearRepoRootCache()

    private fun initRepo(rel: String): String {
        val dir = File(ws, rel).also { it.mkdirs() }
        val pb = ProcessBuilder("git", "init").directory(dir).redirectErrorStream(true)
        pb.start().also { it.inputStream.bufferedReader().readText() }.waitFor()
        return dir.canonicalPath
    }

    private fun canonical(paths: List<String>) = paths.map { File(it).canonicalPath }.sorted()

    @Test
    fun `inside a repo returns exactly that repo`() {
        val backend = initRepo("backend")
        assertEquals(listOf(backend), canonical(GitUtils.discoverRepoRoots(backend)))

        GitUtils.clearRepoRootCache()
        val deep = File(ws, "backend/src/app").also { it.mkdirs() }
        assertEquals(listOf(backend), canonical(GitUtils.discoverRepoRoots(deep.absolutePath)))
    }

    @Test
    fun `above sibling clones returns each of them`() {
        val backend = initRepo("backend")
        val frontend = initRepo("frontend")
        assertEquals(
            listOf(backend, frontend).sorted(),
            canonical(GitUtils.discoverRepoRoots(ws.absolutePath)),
        )
    }

    @Test
    fun `finds a repo one grouping level down`() {
        val api = initRepo("services/api")
        assertEquals(listOf(api), canonical(GitUtils.discoverRepoRoots(ws.absolutePath)))
    }

    @Test
    fun `skips dependency trees and hidden dirs`() {
        val app = initRepo("app")
        initRepo("node_modules/some-dep")
        initRepo(".cache/clone")
        assertEquals(listOf(app), canonical(GitUtils.discoverRepoRoots(ws.absolutePath)))
    }

    @Test
    fun `stops at a repo boundary rather than descending into submodules`() {
        val outer = initRepo("outer")
        initRepo("outer/inner")
        GitUtils.clearRepoRootCache()
        assertEquals(listOf(outer), canonical(GitUtils.discoverRepoRoots(ws.absolutePath)))
    }

    @Test
    fun `returns nothing when there is no repo at or below the dir`() {
        File(ws, "docs").mkdirs()
        assertTrue(GitUtils.discoverRepoRoots(ws.absolutePath).isEmpty())
        assertTrue(GitUtils.discoverRepoRoots("").isEmpty())
    }

    @Test
    fun `collection overload dedupes repos shared by several roots`() {
        val backend = initRepo("backend")
        val frontend = initRepo("frontend")
        val sub = File(ws, "backend/src").also { it.mkdirs() }
        val got = GitUtils.discoverRepoRoots(listOf(ws.absolutePath, backend, sub.absolutePath))
        assertEquals(listOf(backend, frontend).sorted(), canonical(got))
    }
}
