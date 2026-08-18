package ai.blamely.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import ai.blamely.utils.Platform
import java.io.File

private val GIT_CANDIDATES = listOf(
    "git",
    "/usr/bin/git",
    "/usr/local/bin/git",
    "/opt/homebrew/bin/git"
)

/** Minimal git helpers for read-only oobeya-cli display. */
object GitUtils {

    fun run(project: Project, vararg args: String): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, *args)
    }

    fun run(cwd: String, vararg args: String): String? {
        for (gitExe in GIT_CANDIDATES) {
            if (gitExe != "git" && !File(gitExe).canExecute()) continue
            val out = runWithGit(cwd, gitExe, *args)
            if (out != null) return out
        }
        return null
    }

    private fun runWithGit(cwd: String, gitExe: String, vararg args: String): String? {
        // Proc.run bounds the child (git runs on the 3s poll loop — a hung git
        // would otherwise pin an alarm thread forever) and discards stderr, so
        // a git warning can no longer corrupt the parsed stdout.
        val out = ai.blamely.utils.Proc.run(
            listOf(gitExe, *args),
            dir = File(cwd),
            timeoutMs = 10_000, maxBytes = 32 * 1024 * 1024,
        )
        return out?.ifBlank { null }
    }

    data class DiffShortStat(val insertions: Int, val deletions: Int, val filesChanged: Int = 0)

    fun parseDiffShortStat(line: String): DiffShortStat {
        val t = line.trim()
        if (t.isEmpty()) return DiffShortStat(0, 0, 0)
        var files = 0
        Regex("(\\d+) files? changed").find(t)?.let { files = it.groupValues[1].toInt() }
        var ins = 0
        Regex("(\\d+) insertions?").find(t)?.let { ins = it.groupValues[1].toInt() }
        var del = 0
        Regex("(\\d+) deletions?").find(t)?.let { del = it.groupValues[1].toInt() }
        return DiffShortStat(ins, del, files)
    }

    fun getWorkingTreeDiffShortStat(cwd: String): DiffShortStat {
        val out = run(cwd, "diff", "--shortstat", "HEAD") ?: return DiffShortStat(0, 0, 0)
        return parseDiffShortStat(out)
    }

    fun parseNumstat(output: String): Map<String, Pair<Int, Int>> {
        val map = linkedMapOf<String, Pair<Int, Int>>()
        for (line in output.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val addStr = parts[0].trim()
            val delStr = parts[1].trim()
            val path = Platform.normalizePath(parts.drop(2).joinToString("\t").trim())
            if (path.isEmpty() || addStr == "-" || delStr == "-") continue
            val a = addStr.toIntOrNull() ?: continue
            val d = delStr.toIntOrNull() ?: continue
            map[path] = a to d
        }
        return map
    }

    fun getWorkingTreeNumstatVsHead(cwd: String): Map<String, Pair<Int, Int>> {
        val out = run(cwd, "diff", "--numstat", "HEAD") ?: return emptyMap()
        return parseNumstat(out)
    }

    /**
     * Numstat for the current "session": working-tree diff if dirty,
     * otherwise falls back to the last commit diff (HEAD~1..HEAD) so data
     * remains visible after a commit on a clean working tree.
     */
    fun getSessionNumstat(cwd: String): Map<String, Pair<Int, Int>> {
        val wt = getWorkingTreeNumstatVsHead(cwd)
        if (wt.isNotEmpty()) return wt
        val out = run(cwd, "diff", "--numstat", "HEAD~1..HEAD") ?: return emptyMap()
        return parseNumstat(out)
    }

    private val repoRootCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getRepoRoot(project: Project): String? {
        val basePath = project.basePath ?: return null
        repoRootCache[basePath]?.let { return it }
        val app = ApplicationManager.getApplication()
        if (!app.isWriteAccessAllowed) {
            try {
                val result = app.runReadAction<String?> {
                    val baseDir = File(basePath)
                    if (!baseDir.exists()) return@runReadAction null
                    val vf = LocalFileSystem.getInstance().findFileByIoFile(baseDir) ?: return@runReadAction basePath
                    // getRepositoryForFileQuick — NOT getRepositoryForFile — because this
                    // can run on the EDT (the gutter's applyGutterForEditor calls getRepoRoot
                    // there). getRepositoryForFile triggers a SYNCHRONOUS repository update,
                    // which IntelliJ forbids on the EDT ("Do not call synchronous repository
                    // update in EDT"). The quick variant returns the already-registered repo
                    // without updating; on a cache miss we still fall back to `git rev-parse`.
                    GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(vf)?.root?.path ?: basePath
                }
                if (result != null) {
                    repoRootCache[basePath] = result
                    return result
                }
            } catch (_: Exception) {
            }
        }
        val root = run(basePath, "rev-parse", "--show-toplevel")?.trim() ?: basePath
        repoRootCache[basePath] = root
        return root
    }

    fun getRepoRoot(path: String): String? {
        if (path.isBlank()) return null
        repoRootCache[path]?.let { return it }
        val raw = File(path)
        val target = when {
            !raw.exists() -> raw
            raw.isDirectory -> raw
            else -> raw.parentFile ?: return null
        }
        val cwd = target.absolutePath
        val root = run(cwd, "rev-parse", "--show-toplevel")?.trim() ?: cwd
        repoRootCache[path] = root
        return root
    }

    fun clearRepoRootCache() {
        repoRootCache.clear()
        gitDirCache.clear()
        discoveredReposCache.clear()
    }

    // ── Nested repo discovery ───────────────────────────────────────────────

    /** How deep below a project dir we look for repos. 1 covers
     *  `project/{backend,frontend}`; 3 also covers `project/services/api`. */
    private const val MAX_CHILD_REPO_DEPTH = 3
    /** A dir holding more clones than this is a checkout root, not a project. */
    private const val MAX_CHILD_REPOS = 25
    /** Dependency/build trees: no attributable source, huge directory counts, and
     *  sometimes vendored .git dirs that would be reported as the user's repos. */
    private val SKIP_SCAN_DIRS = setOf(
        "node_modules", "vendor", "target", "build", "dist", "out", "bin", "obj",
        "coverage", "venv", "Pods", "DerivedData", "__pycache__", "tmp", "temp",
    )

    private val discoveredReposCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * The git repositories [dir] covers.
     *
     * Normally exactly one: the repo containing [dir]. But a project opened ABOVE
     * its repos — a directory holding separate `backend/` and `frontend/` clones —
     * is in no repo at all, and `git rev-parse` cannot help because it only searches
     * upward. Everything keyed off the project dir (gutter, tool window, HEAD watch)
     * then found nothing, even though Blamely had captured the edits correctly. So
     * when the dir isn't in a repo we scan a bounded distance DOWNWARD instead.
     *
     * Mirrors gitutil.DiscoverRepos in the CLI and discoverRepoRoots in the VS Code
     * plugin; keep the three in step.
     */
    fun discoverRepoRoots(dir: String): List<String> {
        if (dir.isBlank()) return emptyList()
        discoveredReposCache[dir]?.let { return it }
        val base = File(dir)
        // getRepoRoot falls back to the path itself when git finds no repo, so an
        // actual .git is what tells the two cases apart.
        val own = getRepoRoot(dir)
        val roots = if (own != null && File(own, ".git").exists()) {
            listOf(own)
        } else {
            val found = ArrayList<String>()
            scanForRepos(base, 0, found)
            found.sorted()
        }
        if (roots.isNotEmpty()) discoveredReposCache[dir] = roots
        return roots
    }

    private fun scanForRepos(dir: File, depth: Int, found: MutableList<String>) {
        if (depth > MAX_CHILD_REPO_DEPTH || found.size >= MAX_CHILD_REPOS) return
        val entries = dir.listFiles() ?: return
        // A .git entry makes this a repo root: take it and stop descending — a repo
        // nested inside a work tree is a submodule, already covered by its parent.
        if (entries.any { it.name == ".git" }) {
            // Canonical, matching what `git rev-parse --show-toplevel` returns, so the
            // same repo reached by two spellings (macOS /var → /private/var, or a
            // content root inside it) dedupes instead of being scanned twice.
            found.add(try { dir.canonicalPath } catch (_: Exception) { dir.path })
            return
        }
        for (e in entries) {
            if (!e.isDirectory) continue
            if (e.name.startsWith(".") || e.name in SKIP_SCAN_DIRS) continue
            // Never follow a symlink: it can escape the project or loop, and a
            // symlinked repo is still reachable by its real path.
            if (java.nio.file.Files.isSymbolicLink(e.toPath())) continue
            scanForRepos(e, depth + 1, found)
        }
    }

    /** Every distinct repo for a project: the repo containing its base dir and
     *  content roots, or each clone nested beneath them. */
    fun discoverRepoRoots(paths: Collection<String>): List<String> {
        val roots = LinkedHashSet<String>()
        for (p in paths) roots.addAll(discoverRepoRoots(p))
        return roots.toList()
    }

    private val gitDirCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Absolute git dir for [repoRoot] (worktree/submodule-safe), cached. Resolving
     * this is the only part of reading HEAD that still needs a git process, so it
     * happens once per repo rather than on every check.
     */
    fun gitDir(repoRoot: String): String? {
        gitDirCache[repoRoot]?.let { return it }
        val dir = run(repoRoot, "rev-parse", "--path-format=absolute", "--git-dir")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        gitDirCache[repoRoot] = dir
        return dir
    }

    /** Current HEAD as read from the git dir. [sha] is null on an unborn branch
     *  (fresh `git init`), [branch] is null when HEAD is detached. */
    data class HeadState(val sha: String?, val branch: String?)

    private val OBJECT_ID = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")

    /** Directory holding this repo's refs. A LINKED WORKTREE has its own HEAD but
     *  shares `refs/` and `packed-refs` with the main repo, via `commondir`. */
    private fun commonDir(gitDir: String): String =
        try {
            val raw = File(gitDir, "commondir").readText().trim()
            if (raw.isEmpty()) gitDir else File(gitDir, raw).canonicalPath
        } catch (_: Exception) {
            gitDir
        }

    /** Resolve a full ref name (`refs/heads/main`) to its object id, or null. */
    private fun resolveRef(gitDir: String, ref: String): String? {
        val common = commonDir(gitDir)
        // Loose ref first: a commit always writes one. packed-refs only holds refs
        // that `git gc` / `git pack-refs` has since folded away.
        val bases = if (common == gitDir) listOf(gitDir) else listOf(gitDir, common)
        for (base in bases) {
            try {
                val raw = File(base, ref).readText().trim()
                if (OBJECT_ID.matches(raw)) return raw
            } catch (_: Exception) {
                // not a loose ref under this base
            }
        }
        return try {
            File(common, "packed-refs").useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    // '# pack-refs with: ...' header, and '^<sha>' peel lines for tags.
                    if (line.isEmpty() || line[0] == '#' || line[0] == '^') return@firstNotNullOfOrNull null
                    val sp = line.indexOf(' ')
                    if (sp > 0 && line.substring(sp + 1).trim() == ref) line.substring(0, sp) else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * HEAD's commit and branch WITHOUT spawning git — the process-free equivalent of
     * `rev-parse HEAD` + `symbolic-ref --short HEAD`. Every operation that moves HEAD
     * rewrites `.git/HEAD` or the branch ref, so this is as current as a spawn.
     *
     * Returns null only when HEAD itself is unreadable (not a git dir, or a race with
     * git rewriting it) — callers then fall back to spawning git. An unborn branch is
     * NOT a failure: it yields a state with a null sha, as `rev-parse` failing would.
     */
    fun readHeadState(gitDir: String): HeadState? {
        val raw = try {
            File(gitDir, "HEAD").readText().trim()
        } catch (_: Exception) {
            return null
        }
        if (raw.startsWith("ref:")) {
            val ref = raw.removePrefix("ref:").trim()
            // `symbolic-ref --short` strips refs/heads/; for the rare non-branch
            // symbolic HEAD keep the full ref so it stays a stable, distinct name.
            val branch = if (ref.startsWith("refs/heads/")) ref.removePrefix("refs/heads/") else ref
            return HeadState(resolveRef(gitDir, ref), branch.takeIf { it.isNotEmpty() })
        }
        return HeadState(raw.takeIf { OBJECT_ID.matches(it) }, null)
    }

    /**
     * Canonical absolute-path key for the in-memory BlameMap. Unlike repo-relative
     * paths — which collide across repos (backend/src/index.ts and frontend/src/index.ts
     * both reduce to "src/index.ts") — an absolute path is unambiguous, so a project
     * spanning several git repos can store every file's blame in one map. Canonicalized
     * so the producer (repoRoot + relPath) and the gutter (VirtualFile.path) agree even
     * when the repo lives under a symlinked path.
     */
    fun blameKey(absolutePath: String): String =
        try {
            Platform.normalizePath(File(absolutePath).canonicalFile.path)
        } catch (_: Exception) {
            Platform.normalizePath(absolutePath)
        }

    /** Path relative to git repo root (matches oobeya-cli / daemon `file_path` keys). */
    fun toRepoRelativePath(repoRoot: String, absolutePath: String): String? {
        if (repoRoot.isBlank() || absolutePath.isBlank()) return null
        return try {
            val root = File(repoRoot).canonicalFile
            val file = File(absolutePath).canonicalFile
            val rel = file.relativeToOrNull(root)?.path ?: return null
            if (rel.isEmpty() || rel.startsWith("..")) null
            else Platform.normalizePath(rel)
        } catch (_: Exception) {
            null
        }
    }

    fun getBranch(project: Project): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, "rev-parse", "--abbrev-ref", "HEAD")
    }

    /**
     * Short name of the checked-out branch for the repo at [cwd], or null when
     * HEAD is detached. Used to tag edits with their branch-based work session;
     * symbolic-ref (unlike --abbrev-ref) fails on detached HEAD rather than
     * returning the literal "HEAD".
     */
    fun getBranchName(cwd: String): String? =
        run(cwd, "symbolic-ref", "--quiet", "--short", "HEAD")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Reports whether the repo at [cwd] is mid-way through a history-rewriting
     * operation (cherry-pick, merge, revert, rebase). Edits the editor observes
     * during these are replays of existing content, not fresh authorship, so the
     * detectors pause recording while one is in progress.
     */
    fun inProgressGitOp(cwd: String): Boolean {
        val gitDir = run(cwd, "rev-parse", "--absolute-git-dir")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        val dir = java.io.File(gitDir)
        return listOf("CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")
            .any { java.io.File(dir, it).exists() }
    }

    fun getNoteContent(cwd: String, sha: String): String? =
        run(cwd, "notes", "--ref=blamely", "show", sha)
}
