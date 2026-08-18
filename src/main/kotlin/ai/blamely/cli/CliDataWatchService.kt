package ai.blamely.cli

import ai.blamely.git.GitUtils
import ai.blamely.git.HeadStateWatcher
import ai.blamely.utils.BlamelyLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Native file watching for the daemon's attribution data, giving IntelliJ the
 * same steady-state freshness as the VS Code plugin's FileSystemWatchers:
 *
 *   - `.git/blamely/` subtree (working logs): an EXTERNAL tool (Claude Code CLI, the
 *     blamely daemon) writing an attribution while the IDE is open repaints the
 *     gutter within ~200ms instead of waiting for a save or the 30s poll.
 *   - `.git/HEAD`: a commit/checkout done in a terminal triggers the HEAD check
 *     instantly instead of at the next 3s poll tick.
 *
 * Why the old BulkFileListener alone missed these: IntelliJ's fsnotifier only
 * reports paths that are (a) under an explicit watch root and (b) loaded into
 * the VFS snapshot. `.git` internals are neither by default — git4idea watches
 * only what it needs. So we add explicit watch roots (fsnotifier is native and
 * recursive on macOS/Linux/Windows) and markDirtyAndRefresh the subtree so
 * future external writes materialize as VFileEvents.
 */
@Service(Service.Level.PROJECT)
class CliDataWatchService(private val project: Project) : Disposable {

    /** Written on a pooled thread by registerWatchRoots, read from dispose() on another. */
    private val watchRequests: MutableSet<LocalFileSystem.WatchRequest> = ConcurrentHashMap.newKeySet()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val headAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Normalized (forward-slash) `<gitDir>/blamely` prefixes per repo root. */
    @Volatile private var watchedBlamelyDirs: List<String> = emptyList()

    /** Normalized `<gitDir>/HEAD` paths. */
    @Volatile private var watchedHeadFiles: Set<String> = emptySet()

    /** Normalized git-dir paths, so a direct child named like a git-op marker
     *  (CHERRY_PICK_HEAD, rebase-merge, …) can be recognized without a path scan. */
    @Volatile private var watchedGitDirs: Set<String> = emptySet()

    /** Normalized `<gitDir>/logs/refs/stash` paths — a stash apply/pop leaves no
     *  marker file, it only touches (or deletes) this reflog. */
    @Volatile private var watchedStashLogs: Set<String> = emptySet()

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        if (Disposer.isDisposed(this)) return
        // Subscribe synchronously: connect(this) registers a Disposer child, so it must
        // happen while this service is provably alive. Doing it after the pooled-thread
        // IO below opened a window where project close disposed us first, and
        // Disposer.register threw IncorrectOperationException on the dead parent.
        subscribeVfs()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed || Disposer.isDisposed(this)) return@executeOnPooledThread
            registerWatchRoots()
        }
    }

    private fun registerWatchRoots() {
        val cliData = project.getService(CliDataService::class.java) ?: return
        val lfs = LocalFileSystem.getInstance()
        val blamelyDirs = ArrayList<String>()
        val headFiles = HashSet<String>()
        val gitDirs = HashSet<String>()
        val stashLogs = HashSet<String>()
        for (root in cliData.projectRepoRoots()) {
            // Worktree/submodule-safe git dir (where `.git` is a file, the real
            // dir lives elsewhere) — strictly better than hardcoding `root/.git`.
            val gitDir = GitUtils.gitDir(root) ?: File(root, ".git").path
            val blamelyDir = File(gitDir, "blamely").path
            val headFile = File(gitDir, "HEAD").path
            val stashLog = File(gitDir, "logs/refs/stash").path

            // Watching gitDir/blamely (not all of .git) avoids refresh churn from
            // index/objects writes. addRootToWatch tolerates not-yet-existing paths.
            lfs.addRootToWatch(blamelyDir, /*watchRecursively=*/true)?.let { watchRequests.add(it) }
            lfs.addRootToWatch(headFile, /*watchRecursively=*/false)?.let { watchRequests.add(it) }
            // Git-op state (see GitOpState): the markers are DIRECT CHILDREN of the git
            // dir, so a non-recursive root covers them without pulling in objects/.
            // Index and lock churn under it is filtered out in the listener below.
            lfs.addRootToWatch(gitDir, /*watchRecursively=*/false)?.let { watchRequests.add(it) }
            lfs.addRootToWatch(stashLog, /*watchRecursively=*/false)?.let { watchRequests.add(it) }
            blamelyDirs.add(normalize(blamelyDir))
            headFiles.add(normalize(headFile))
            gitDirs.add(normalize(gitDir))
            stashLogs.add(normalize(stashLog))

            // Load into the VFS snapshot so fsnotifier notifications for these
            // paths become VFileEvents (an unknown path never fires an event).
            VfsUtil.markDirtyAndRefresh(/*async=*/true, /*recursive=*/true, /*reloadChildren=*/true, File(blamelyDir))
            VfsUtil.markDirtyAndRefresh(true, false, false, File(headFile))
            VfsUtil.markDirtyAndRefresh(true, false, true, File(gitDir))
            VfsUtil.markDirtyAndRefresh(true, false, false, File(stashLog))
        }
        // dispose() may have run while we were resolving git dirs and refreshing the VFS;
        // in that case it saw an empty set, so the roots added above would leak.
        if (Disposer.isDisposed(this)) {
            releaseWatchRoots()
            return
        }
        watchedBlamelyDirs = blamelyDirs
        watchedHeadFiles = headFiles
        watchedGitDirs = gitDirs
        watchedStashLogs = stashLogs
        if (blamelyDirs.isNotEmpty()) {
            BlamelyLogger.debug("CliDataWatchService: watching ${blamelyDirs.size} blamely dir(s) + HEAD")
        }
    }

    private fun subscribeVfs() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    var workingLogHit = false
                    var headHit = false
                    for (event in events) {
                        val p = normalize(event.path)
                        if (p in watchedHeadFiles || p in watchedStashLogs) {
                            headHit = true
                            continue
                        }
                        // A git-op marker appearing or disappearing (cherry-pick,
                        // merge, revert, rebase) changes GitOpState, which checkNow
                        // refreshes. Everything else directly under the git dir —
                        // index, *.lock, COMMIT_EDITMSG — is ordinary churn.
                        if (p.substringBeforeLast('/', "") in watchedGitDirs &&
                            p.substringAfterLast('/') in GIT_OP_MARKERS
                        ) {
                            headHit = true
                            continue
                        }
                        val underBlamely = watchedBlamelyDirs.any { p.startsWith("$it/") }
                        // Generic fallback covers repos discovered after start().
                        if (underBlamely || p.contains("/.git/blamely/working_logs/")) {
                            if (p.contains("/working_logs/")) workingLogHit = true
                            // The daemon lazily creates <branch>/<base>/ dirs; load a
                            // newly created directory into the VFS so writes INSIDE it
                            // fire content-change events too.
                            if (event is VFileCreateEvent && event.file?.isDirectory == true) {
                                VfsUtil.markDirtyAndRefresh(true, true, true, File(event.path))
                            }
                        }
                    }
                    if (workingLogHit) {
                        refreshAlarm.cancelAllRequests()
                        refreshAlarm.addRequest({
                            if (!project.isDisposed) project.getService(CliDataService::class.java)?.refresh()
                        }, 200)
                    }
                    if (headHit) {
                        headAlarm.cancelAllRequests()
                        headAlarm.addRequest({
                            if (!project.isDisposed) project.getService(HeadStateWatcher::class.java)?.checkNow()
                        }, 100)
                    }
                }
            },
        )
    }

    private fun normalize(path: String): String = path.replace('\\', '/')

    private companion object {
        /** Same set as GitOpState.OP_MARKERS — keep in sync. */
        private val GIT_OP_MARKERS = setOf(
            "CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply",
        )
    }

    override fun dispose() {
        releaseWatchRoots()
    }

    private fun releaseWatchRoots() {
        if (watchRequests.isEmpty()) return
        val requests = watchRequests.toList()
        watchRequests.clear()
        LocalFileSystem.getInstance().removeWatchedRoots(requests)
    }
}
