package codehistoryminer.plugin.vcsaccess

import codehistoryminer.core.miner.filechange.FileChangeMiner
import codehistoryminer.core.miner.linchangecount.LineAndCharChangeMiner
import codehistoryminer.publicapi.lang.Cancelled
import codehistoryminer.core.lang.DateRange
import codehistoryminer.core.lang.Measure
import codehistoryminer.core.miner.todo.TodoCountMiner
import codehistoryminer.core.vcsreader.CommitProgressIndicator
import codehistoryminer.plugin.vcsaccess.implementation.IJFileTypes
import codehistoryminer.plugin.vcsaccess.implementation.wrappers.VcsProjectWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsRoot
import com.intellij.openapi.vcs.update.UpdatedFilesListener
import com.intellij.util.messages.MessageBusConnection
import liveplugin.PluginUtil
import org.jetbrains.annotations.Nullable
import vcsreader.Change
import vcsreader.vcs.commandlistener.VcsCommand

import static codehistoryminer.core.lang.Misc.withDefault
import static com.intellij.openapi.vcs.update.UpdatedFilesListener.UPDATED_FILES
import static com.intellij.openapi.vfs.VfsUtil.getCommonAncestor

class VcsActions {
	private final Measure measure
	private final VcsActionsLog log
	private final Map<String, MessageBusConnection> connectionByProjectName = [:]

    VcsActions(Measure measure = new Measure(), @Nullable VcsActionsLog log = null) {
		this.measure = measure
		this.log = log
	}

    Iterator<codehistoryminer.core.miner.MinedCommit> readMinedCommits(List<DateRange> dateRanges, Project project, boolean grabChangeSizeInLines,
                                                                       ideIndicator, Cancelled cancelled) {
	    def fileTypes = new IJFileTypes()
        def noContentListener = new codehistoryminer.core.miner.MinerListener() {
            @Override void failedToMine(Change change, String message, Throwable throwable) {
                log.failedToMine(message + ": " + change.toString() + ". " + throwable?.message)
            }
        }
        def miners = grabChangeSizeInLines ?
                [new FileChangeMiner(), new LineAndCharChangeMiner(fileTypes, noContentListener), new TodoCountMiner(fileTypes)] :
                [new FileChangeMiner()]
        def vcsProject = new VcsProjectWrapper(project, vcsRootsIn(project), commonVcsRootsAncestor(project), log)

	    def listener = new codehistoryminer.core.miner.MiningMachine.Listener() {
		    @Override void onUpdate(CommitProgressIndicator indicator) { ideIndicator?.fraction = indicator.fraction() }
		    @Override void beforeCommand(VcsCommand command) {}
		    @Override void afterCommand(VcsCommand command) {}
		    @Override void onVcsError(String error) { log.errorReadingCommits(error) }
		    @Override void onException(Exception e) { log.errorReadingCommits(e.message) }
		    @Override void failedToMine(Change change, String description, Throwable throwable) {
			    log.onExtractChangeEventException(throwable)
		    }
	    }

	    def config = new codehistoryminer.core.miner.MiningMachine.Config(miners, fileTypes, TimeZone.getDefault())
			    .withListener(listener)
			    .withCacheFileContent(false)
	            .withVcsRequestSizeInDays(1)
	    def miningMachine = new codehistoryminer.core.miner.MiningMachine(config)
	    miningMachine.mine(vcsProject, dateRanges, cancelled)
    }

    def addVcsUpdateListenerFor(String projectName, Closure closure) {
		if (connectionByProjectName.containsKey(projectName)) return

		Project project = ProjectManager.instance.openProjects.find{ it.name == projectName }
		if (project == null) return

		def connection = project.messageBus.connect(project)
		connection.subscribe(UPDATED_FILES, new UpdatedFilesListener() {
			@Override void consume(Set<String> files) {
				PluginUtil.invokeLaterOnEDT{
					closure.call(project)
				}
			}
		})
		connectionByProjectName.put(projectName, connection)
	}

	def removeVcsUpdateListenerFor(String projectName) {
		def connection = connectionByProjectName.get(projectName)
		if (connection == null) return
		connection.disconnect()
	}

    @SuppressWarnings("GrMethodMayBeStatic")
    def dispose(oldVcsAccess) {
		oldVcsAccess.connectionByProjectName.values().each {
			it.disconnect()
		}
	}

    @SuppressWarnings("GrMethodMayBeStatic")
    boolean noVCSRootsIn(Project project) {
        vcsRootsIn(project).size() == 0
    }

    static List<VcsRoot> vcsRootsIn(Project project) {
        ProjectLevelVcsManager.getInstance(project).allVcsRoots
    }

    static String commonVcsRootsAncestor(Project project) {
        withDefault("", getCommonAncestor(vcsRootsIn(project).collect { it.path })?.canonicalPath)
    }
}