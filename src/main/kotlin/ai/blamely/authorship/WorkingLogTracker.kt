// IntelliJ editor live-tracker (docs/attribution-v2-design.md §9) — the platform
// counterpart to the VS Code WorkingLogTracker. CompletionDetector classifies each
// document change and calls onEdit with the full pre/post text + author; we keep a
// per-file FileTracker IN MEMORY and flush the working log to .git/blamely.
//
// All engine + I/O work runs on a single background thread (never the EDT), and the
// flush is debounced. Gated by the blamely.attributionV2 setting (default off):
// writes working-log files only; gutter/note unchanged until the Phase 3 flip.
package ai.blamely.authorship

import ai.blamely.git.GitUtils
import ai.blamely.settings.BlamelySettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class WorkingLogTracker(private val project: Project) : Disposable {
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "blamely-worklog").apply { isDaemon = true } }
    private val trackers = ConcurrentHashMap<String, FileTracker>()
    private val flushTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val inFlightDeletions = java.util.concurrent.atomic.AtomicInteger(0)

    /** Called by CompletionDetector for each classified change (AI + human), off
     *  the EDT via the executor. prevText is the file content BEFORE this change —
     *  the baseline when the file is first seen this session. */
    fun onEdit(absPath: String, prevText: String, newText: String, author: Author) {
        if (absPath.isBlank() || newText == prevText || !BlamelySettings.getInstance().attributionV2) return
        // Replayed content (cherry-pick/rebase/merge/revert in progress, or a
        // stash apply/pop within the stash window) is NOT fresh authorship —
        // folding it would poison the working log as Human typing. Skip the fold
        // AND discard the file's in-memory tracker so the next real edit
        // re-seeds from the on-disk working log + baseline (the pre-op truth).
        // The CLI's commit-time reconcile recovers anything mis-folded anyway.
        if (project.getService(ai.blamely.git.GitOpState::class.java)?.isActive() == true) {
            discardFile(absPath)
            return
        }
        exec.submit {
            try {
                val ft = trackers.getOrPut(absPath) { seedTracker(absPath, prevText) }
                ft.applyEdit(newText, author)
                // An AI edit that REMOVED lines: the working log only describes surviving
                // content, so committed deletions would default to Human. Record the
                // deleted baseline lines (via the CLI, reusing the engine) so they
                // attribute to the tool. Gated to AI edits that shrink the file.
                if (author.type == AuthorType.AI && lineCount(newText) < lineCount(prevText)) {
                    recordDeletion(absPath, newText, author)
                }
                flushTasks.remove(absPath)?.cancel(false)
                flushTasks[absPath] = exec.schedule({ flush(absPath) }, FLUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // best-effort: a working-log failure must never disrupt the IDE
            }
        }
    }

    /** Flush every tracked file immediately (e.g. on IDE focus loss, before a commit
     *  reads the working log). Runs on the worklog executor for map safety. With
     *  force=true, re-persist even non-dirty trackers — used on a same-SHA branch
     *  switch so each file's working log exists under the NEW branch's dir. */
    fun flushAll(force: Boolean = false) {
        exec.submit {
            for (path in trackers.keys.toList()) {
                flushTasks.remove(path)?.cancel(false)
                flush(path, force)
            }
        }
    }

    /** Flush a single file's tracker now (e.g. on document save) so a commit that reads
     *  the working log right after sees the latest state. Mirrors the VS Code plugin's
     *  onDidSaveTextDocument flush. */
    fun flushFile(absPath: String) {
        exec.submit {
            flushTasks.remove(absPath)?.cancel(false)
            flush(absPath)
        }
    }

    /** Flush then evict a file's tracker (e.g. on editor close) so it doesn't linger in
     *  memory. Mirrors the VS Code plugin's onDidCloseTextDocument map eviction. */
    fun dropFile(absPath: String) {
        exec.submit {
            flushTasks.remove(absPath)?.cancel(false)
            flush(absPath)
            trackers.remove(absPath)
        }
    }

    /** Evict a file's tracker WITHOUT flushing — used when a replay (git op /
     *  stash pop / external reload) rewrote the buffer: the in-flight state may
     *  already be poisoned, so it is discarded and the next real edit re-seeds
     *  from the on-disk working log + baseline. Mirrors the VS Code plugin's
     *  resetDocument. */
    fun discardFile(absPath: String) {
        exec.submit {
            flushTasks.remove(absPath)?.cancel(false)
            trackers.remove(absPath)
        }
    }

    /** A commit moved HEAD: the just-committed edits are now history. Drop the
     *  in-memory trackers so the next edit re-baselines against the committed content
     *  instead of accumulating against a stale baseline. */
    fun onHeadChanged() {
        exec.submit {
            flushTasks.values.forEach { it.cancel(false) }
            flushTasks.clear()
            trackers.clear()
        }
    }

    /** A same-SHA branch switch (`git checkout -b feature`): the in-memory edits are
     *  still uncommitted work, but their on-disk log currently lives only under the OLD
     *  branch's dir. flush() re-resolves ctx per call, so a forced flush re-writes each
     *  file's log under the new branch/base before a commit there reads it. Keeps the
     *  trackers (unlike onHeadChanged) since nothing was committed. */
    fun onBranchChanged() {
        flushAll(force = true)
    }

    /** Build a file's FileTracker on first edit, SEEDING from the on-disk working log +
     *  baseline so attribution authored outside this editor session (an agent Write the
     *  keystroke tracker never saw — e.g. Claude Code creating the file) is preserved.
     *  Without this the first in-editor edit rebuilds from null, defaults every untouched
     *  AI line to Human, and the flush clobbers it. Runs on the worklog thread (resolveCtx
     *  spawns git, never the EDT). Falls back to a fresh seed from firstPrev on any miss. */
    private fun seedTracker(absPath: String, firstPrev: String): FileTracker {
        try {
            val ctx = resolveCtx(absPath)
            if (ctx != null) {
                val prior = WorkingLogStore.loadWorkingLog(ctx.repoRoot, ctx.branch, ctx.baseSha, ctx.rel)
                val baseFile = WorkingLogStore.baselinePath(ctx.repoRoot, ctx.branch, ctx.baseSha, ctx.rel)
                val stored = if (baseFile.exists()) baseFile.readText() else null
                // The prior log's line numbers describe the STORED baseline; only adopt
                // the log when that baseline is present, so the diff aligns.
                if (prior != null && stored != null) {
                    return FileTracker(stored, prior)
                }
            }
        } catch (_: Exception) {
            // best-effort: fall back to a fresh seed below
        }
        return FileTracker(firstPrev, null)
    }

    private fun lineCount(s: String): Int = if (s.isEmpty()) 0 else s.count { it == '\n' } + 1

    /** Record AI-deleted baseline lines via `blamely record-deletion` (current content
     *  piped on stdin, since the buffer may be unsaved). Fire-and-forget; output
     *  discarded so it never blocks the worklog thread.
     *
     *  Bounded on both axes, because nothing waits on the child: without a deadline
     *  one that blocks (SQLite lock, unreachable daemon) lives until the IDE exits,
     *  and without a cap a burst of AI deletes spawns unboundedly many at once. */
    private fun recordDeletion(absPath: String, content: String, author: Author) {
        try {
            val bin = blamelyBinaryPath()
            if (!java.io.File(bin).exists()) return
            if (inFlightDeletions.get() >= MAX_INFLIGHT_DELETIONS) return
            val args = mutableListOf(bin, "record-deletion", absPath, "--gen-type", author.genType.ifEmpty { "completion" })
            if (author.tool.isNotEmpty()) { args.add("--tool"); args.add(author.tool) }
            if (author.model.isNotEmpty()) { args.add("--model"); args.add(author.model) }
            val pb = ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            val p = pb.start()
            inFlightDeletions.incrementAndGet()
            // Runs on normal exit AND on the timeout, so the counter always comes back
            // down. destroyForcibly, not destroy: a child ignoring SIGTERM or already
            // stopped would otherwise stay in the process table.
            p.onExit().orTimeout(RECORD_DELETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete { _, _ ->
                    if (p.isAlive) p.destroyForcibly()
                    inFlightDeletions.decrementAndGet()
                }
            try {
                p.outputStream.use { it.write(content.toByteArray()); it.flush() }
            } catch (_: Exception) {
                // child already gone — onExit still releases the slot
            }
        } catch (_: Exception) {
        }
    }

    private fun flush(absPath: String, force: Boolean = false) {
        try {
            val ft = trackers[absPath] ?: return
            // force re-persists a clean tracker under a (possibly new) branch/base dir.
            if (!force && !ft.isDirty()) return
            val ctx = resolveCtx(absPath) ?: return
            val wl = ft.current() ?: return
            WorkingLogStore.save(ctx.repoRoot, ctx.branch, ctx.baseSha, ctx.rel, wl, ft.content())
            ft.markFlushed()
        } catch (_: Exception) {
        }
    }

    private data class Ctx(val repoRoot: String, val branch: String, val baseSha: String, val rel: String)

    /**
     * The working log's key (repo, branch, base commit, relative path).
     *
     * Called on every seed AND every debounced flush — i.e. roughly every 400ms per
     * file while the user types. It used to spawn `rev-parse --abbrev-ref HEAD` and
     * `rev-parse HEAD` each time, through a ProcessBuilder with NO timeout, so a git
     * that hung pinned the single worklog thread permanently. Both values are read
     * straight out of the git dir now; git is spawned only if HEAD is unreadable.
     *
     * This also fixes the detached-HEAD branch name. `--abbrev-ref HEAD` returns the
     * literal "HEAD" when detached rather than failing, so the plugin wrote logs under
     * `working_logs/HEAD/` while the CLI (which uses symbolic-ref, see
     * internal/authorship/capture.go) and the VS Code plugin both use "DETACHED" —
     * the CLI could not find what the IDE had written.
     */
    private fun resolveCtx(absPath: String): Ctx? {
        val repoRoot = GitUtils.getRepoRoot(absPath) ?: return null
        val rel = GitUtils.toRepoRelativePath(repoRoot, absPath) ?: return null
        val gitDir = GitUtils.gitDir(repoRoot)
        val state = gitDir?.let { GitUtils.readHeadState(it) }
        if (state != null) {
            return Ctx(repoRoot, state.branch ?: "DETACHED", state.sha ?: "INITIAL", rel)
        }
        val branch = GitUtils.getBranchName(repoRoot) ?: "DETACHED"
        val head = GitUtils.run(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf { it.isNotEmpty() } ?: "INITIAL"
        return Ctx(repoRoot, branch, head, rel)
    }

    override fun dispose() {
        exec.shutdownNow()
    }

    companion object {
        // Short, so a Tab-accept immediately followed by a commit is persisted before
        // the commit reads the working log.
        private const val FLUSH_DEBOUNCE_MS = 400L

        // Ceilings for the fire-and-forget `blamely record-deletion` children (see
        // recordDeletion) — nothing waits on them, so they need their own bounds.
        private const val RECORD_DELETION_TIMEOUT_MS = 10_000L
        private const val MAX_INFLIGHT_DELETIONS = 4
    }
}
