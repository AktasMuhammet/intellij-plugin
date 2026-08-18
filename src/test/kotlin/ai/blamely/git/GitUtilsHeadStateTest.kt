package ai.blamely.git

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [GitUtils.readHeadState] is the process-free replacement for `rev-parse HEAD` +
 * `symbolic-ref --short HEAD` on the HEAD check path, so it has to agree with git on
 * every shape of HEAD the check can encounter: unborn, on a branch, detached, and with
 * the branch ref packed away.
 */
class GitUtilsHeadStateTest {

    @TempDir
    lateinit var tempDir: File

    private val repo: String get() = tempDir.absolutePath

    @BeforeEach
    fun setUp() {
        GitUtils.clearRepoRootCache()
        runGit(repo, "init", "-b", "main")
        runGit(repo, "config", "user.email", "test@blamely.test")
        runGit(repo, "config", "user.name", "Blamely Test")
    }

    @AfterEach
    fun tearDown() {
        GitUtils.clearRepoRootCache()
    }

    private fun gitDir(): String = GitUtils.gitDir(repo)!!

    private fun commit(body: String): String {
        File(tempDir, "a.txt").writeText(body)
        runGit(repo, "add", ".")
        runGit(repo, "commit", "-m", body)
        return runGit(repo, "rev-parse", "HEAD").trim()
    }

    @Test
    fun `unborn branch reports the branch with no tip`() {
        val state = GitUtils.readHeadState(gitDir())
        assertNotNull(state, "HEAD must be readable on a fresh repo")
        assertNull(state!!.sha, "unborn branch has no tip")
        assertEquals("main", state.branch)
    }

    @Test
    fun `matches git across successive commits`() {
        val gitDir = gitDir()
        val first = commit("one\n")
        assertEquals(GitUtils.HeadState(first, "main"), GitUtils.readHeadState(gitDir))

        val second = commit("two\n")
        assertEquals(GitUtils.HeadState(second, "main"), GitUtils.readHeadState(gitDir))
    }

    @Test
    fun `reports a slash-named branch and null branch when detached`() {
        val gitDir = gitDir()
        val sha = commit("one\n")

        runGit(repo, "checkout", "-b", "feature/nested")
        assertEquals(GitUtils.HeadState(sha, "feature/nested"), GitUtils.readHeadState(gitDir))

        runGit(repo, "checkout", "--detach", sha)
        val detached = GitUtils.readHeadState(gitDir)
        assertEquals(sha, detached?.sha, "detached HEAD still resolves the sha")
        assertNull(detached?.branch, "detached HEAD has no branch")
    }

    @Test
    fun `resolves a ref git has packed away`() {
        val gitDir = gitDir()
        val sha = commit("one\n")

        runGit(repo, "pack-refs", "--all")
        assertFalse(
            File(gitDir, "refs/heads/main").exists(),
            "precondition: pack-refs removed the loose ref",
        )
        assertEquals(GitUtils.HeadState(sha, "main"), GitUtils.readHeadState(gitDir))
    }

    @Test
    fun `returns null when HEAD is unreadable so callers fall back to git`() {
        assertNull(GitUtils.readHeadState(File(tempDir, "not-a-git-dir").absolutePath))
    }

    private fun runGit(cwd: String, vararg args: String): String {
        val pb = ProcessBuilder("git", *args)
            .directory(File(cwd))
            .redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }
}
