// Cached in-progress git-op state, refreshed by the 3s HEAD poll (the IntelliJ
// counterpart of the VS Code plugin's GitOpState — keep both in sync).
//
// Purpose: the working-log tracker must not fold REPLAYED content — a
// cherry-pick/rebase/merge/revert rewriting open buffers, or a stash apply/pop —
// into the working log as fresh Human typing. A per-event `inProgressGitOp`
// check spawns git and is too costly per document change; this service caches:
//   • the five marker files (same set as GitUtils.inProgressGitOp) checked with
//     File.exists() against a once-resolved git dir, and
//   • the stash reflog's mtime — a stash apply/pop leaves NO marker, but always
//     touches .git/logs/refs/stash (or deletes it when the last stash is
//     popped), so ANY transition opens a short "stash window".
// isActive() is synchronous and allocation-free — safe on every document event.
package ai.blamely.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

@Service(Service.Level.PROJECT)
class GitOpState(@Suppress("unused") private val project: Project?) {

    // Test-only: plain construction without a Project.
    constructor() : this(null)

    /** Per-repository slice of the cached state. A project can span several repos
     *  (content roots in different repos, or a project dir opened above sibling
     *  clones), and a rebase in ONE of them replays buffers just the same — so each
     *  is tracked separately instead of a single "current repo". */
    private class RepoOpState {
        @Volatile var gitDir: String? = null
        @Volatile var markerActive = false
        @Volatile var stashWindowUntilMs = 0L
        @Volatile var lastStashMtimeMs: Long? = null
        @Volatile var stashObserved = false
    }

    private val repos = java.util.concurrent.ConcurrentHashMap<String, RepoOpState>()

    /** Refresh the cached state for repoRoot, on a pooled thread — never per
     *  keystroke. Driven by HeadStateWatcher, which the stash-reflog watch fires the
     *  moment a stash is applied/popped. Pass [knownGitDir] when the caller already
     *  resolved it to skip the one-time `rev-parse` spawn. */
    @JvmOverloads
    fun poll(repoRoot: String, knownGitDir: String? = null) {
        val st = repos.computeIfAbsent(repoRoot) { RepoOpState() }
        if (st.gitDir == null) {
            st.gitDir = knownGitDir?.trim()?.takeIf { it.isNotEmpty() }
                ?: GitUtils.run(repoRoot, "rev-parse", "--path-format=absolute", "--git-dir")
                    ?.trim()?.takeIf { it.isNotEmpty() }
        }
        val g = st.gitDir ?: return
        st.markerActive = OP_MARKERS.any { File(g, it).exists() }

        val stashLog = File(g, "logs/refs/stash")
        val mtime: Long? = if (stashLog.exists()) stashLog.lastModified() else null
        val prev = st.lastStashMtimeMs
        // Any TRANSITION of the stash reflog is stash activity: touched (stash/
        // apply), created (first stash), or DELETED (popping the last stash
        // removes the reflog file — mtime goes null, not newer). The first poll
        // only records the baseline.
        val changed = st.stashObserved && (
            (mtime != null && prev != null && mtime != prev) ||
                (mtime != null && prev == null) ||
                (mtime == null && prev != null)
            )
        if (changed) {
            st.stashWindowUntilMs = System.currentTimeMillis() + STASH_WINDOW_MS
        }
        st.lastStashMtimeMs = mtime
        st.stashObserved = true
    }

    /** True while a marker op is in progress, or within the stash window, in ANY
     *  polled repo — the tracker suppresses per project, not per repo. */
    fun isActive(): Boolean {
        val now = System.currentTimeMillis()
        return repos.values.any { it.markerActive || now < it.stashWindowUntilMs }
    }

    companion object {
        private val OP_MARKERS = listOf(
            "CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply",
        )

        // How long after a stash-reflog change buffer rewrites still count as
        // replays. The CLI's commit-time reconcile recovers anything the editor
        // mis-folds either way.
        private const val STASH_WINDOW_MS = 10_000L
    }
}
