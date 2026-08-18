package ai.blamely.git

import ai.blamely.cli.CliDataService
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm

/**
 * Tracks the repo's HEAD SHA and branch name, reacting to commits and
 * same-SHA branch switches. Extracted from BlamelyStartupActivity's pollHead
 * so the check can ALSO be fired instantly by CliDataWatchService's native
 * `.git/HEAD` file watch — the 3s poll stays as the correctness backstop (it
 * additionally refreshes GitOpState, which the working-log tracker consults
 * synchronously on every keystroke).
 */
@Service(Service.Level.PROJECT)
class HeadStateWatcher(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    // HEAD/branch are tracked PER REPO: a project can span several (content roots
    // in different repos, or a project dir opened above sibling clones, where
    // `git rev-parse` on the project dir finds no repo at all).
    private data class HeadState(val head: String?, val branch: String?)
    private val lastState = java.util.concurrent.ConcurrentHashMap<String, HeadState>()
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        schedule()
    }

    private fun schedule() {
        if (project.isDisposed) return
        alarm.addRequest({
            checkNow()
            schedule()
        }, BACKSTOP_INTERVAL_MS)
    }

    /**
     * Runs one HEAD/branch check immediately. Safe to call from any thread and
     * from concurrent triggers (poll tick + VFS HEAD event) — overlapping calls
     * coalesce into one.
     */
    fun checkNow() {
        if (project.isDisposed) return
        if (!checking.compareAndSet(false, true)) return
        try {
            val repos = project.getService(CliDataService::class.java)?.projectRepoRoots()
                ?: GitUtils.discoverRepoRoots(listOfNotNull(project.basePath))
            for (repoRoot in repos) {
                checkRepo(repoRoot)
            }
        } finally {
            checking.set(false)
        }
    }

    private fun checkRepo(repoRoot: String) {
        val gitDir = GitUtils.gitDir(repoRoot)
        // Refresh the cached git-op / stash-window state the working-log
        // tracker consults synchronously on every change (see GitOpState).
        project.getService(GitOpState::class.java)?.poll(repoRoot, gitDir)
        // Read HEAD out of the git dir instead of spawning `rev-parse HEAD` plus
        // `symbolic-ref` on every check. git is spawned only if HEAD is unreadable
        // (not a git dir, or a race with git rewriting it).
        val state = gitDir?.let { GitUtils.readHeadState(it) }
        val head: String?
        val branch: String
        if (state != null) {
            head = state.sha
            branch = state.branch ?: "DETACHED"
        } else {
            head = GitUtils.run(repoRoot, "rev-parse", "HEAD")?.trim()
            branch = GitUtils.getBranchName(repoRoot) ?: "DETACHED"
        }
        val last = lastState[repoRoot]
        if (head != null && head != last?.head) {
            val wasInitial = last?.head == null
            lastState[repoRoot] = HeadState(head, branch)
            project.getService(CliDataService::class.java)?.refresh()
            refreshUi()
            // A real commit (not the first observation) → drop the trackers'
            // in-memory edits so the next edit re-baselines against the
            // committed content rather than a stale baseline.
            if (!wasInitial) {
                project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.onHeadChanged()
            }
        } else if (head != null && last?.branch != null && branch != last.branch) {
            // Same HEAD SHA, different branch — `git checkout -b feature` (or
            // switching to an existing branch at the same tip). No commit
            // happened, so the in-memory edits are still live; re-persist them
            // under the NEW branch's working-log dir before a commit there
            // reads it, and refresh so the gutter re-scopes to the branch.
            lastState[repoRoot] = HeadState(head, branch)
            project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.onBranchChanged()
            project.getService(CliDataService::class.java)?.refresh()
        }
    }

    companion object {
        // The check is driven by CliDataWatchService's native watches on `.git/HEAD`
        // (commits, checkouts) and `.git/logs/refs/stash` + the git-op markers (which
        // feed GitOpState). This timer is only the backstop for filesystems where
        // fsnotifier delivers nothing — hence a minute rather than the former 3s, which
        // spawned `rev-parse HEAD` and `symbolic-ref` on every tick.
        private const val BACKSTOP_INTERVAL_MS = 60_000
    }

    private fun refreshUi() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WindowManager.getInstance().getStatusBar(project)
                ?.updateWidget(ai.blamely.ui.BlamelyStatusBarWidget.WIDGET_ID)
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
        }
    }

    override fun dispose() {}
}
