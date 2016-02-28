package codehistoryminer.plugin.integrationtest

import codehistoryminer.core.historystorage.EventStorageReader
import codehistoryminer.core.historystorage.EventStorageWriter
import codehistoryminer.plugin.CodeHistoryMinerPlugin
import codehistoryminer.plugin.historystorage.HistoryGrabberConfig
import codehistoryminer.plugin.historystorage.HistoryStorage
import codehistoryminer.plugin.historystorage.QueryScriptsStorage
import codehistoryminer.plugin.ui.UI
import codehistoryminer.plugin.vcsaccess.VcsActions
import codehistoryminer.publicapi.lang.Cancelled
import codehistoryminer.publicapi.lang.Date
import codehistoryminer.publicapi.lang.Time
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import org.junit.Test

import static codehistoryminer.core.lang.DateTimeTestUtil.*
import static codehistoryminer.plugin.integrationtest.GroovyStubber.*

class CodeHistoryMinerPluginTest {

	@Test def "on VCS update does nothing if already grabbed on this date"() {
		// given
		def grabbedVcs = false
		def historyStorage = stub(HistoryStorage, [
				eventStorageReader: returns(stub(EventStorageReader, [
						storedDateRange: returns(dateRange("01/11/2012", "20/11/2012"))
				])),
				eventStorageWriter: returns(stub(EventStorageWriter, [:])),
				loadGrabberConfigFor: returns(someConfig.withLastGrabTime(time("09:00 23/11/2012")))
		])
		def vcsAccess = stub(VcsActions,
				[readMinedCommits: { List<codehistoryminer.core.lang.DateRange> dateRanges, Project project, boolean grabChangeSizeInLines, readListener, Cancelled cancelled ->
					grabbedVcs = true
					[].iterator()
				}])

		def ui = stub(UI, [runInBackground: runOnTheSameThread])
		def miner = new CodeHistoryMinerPlugin(ui, historyStorage, stub(QueryScriptsStorage), vcsAccess, new codehistoryminer.core.lang.Measure())

		// when / then
		def now = time("23/11/2012", TimeZone.default)
		miner.grabHistoryOnVcsUpdate(someProject, now)
		assert !grabbedVcs
	}

	@Test def "on VCS update grabs history from today to the latest event in file history"() {
		// given
		List<codehistoryminer.core.lang.DateRange> grabbedDateRanges = null
		def historyStorage = stub(HistoryStorage, [
				eventStorageReader: returns(stub(EventStorageReader, [
                        storedDateRange: returns(dateRange("01/11/2012", "20/11/2012"))
				])),
				eventStorageWriter: returns(stub(EventStorageWriter, [:])),
				loadGrabberConfigFor: returns(someConfig.withLastGrabTime(time("13:40 20/11/2012")))
		])
		def vcsAccess = stub(VcsActions,
				[readMinedCommits: { List<codehistoryminer.core.lang.DateRange> dateRanges, Project project, boolean grabChangeSizeInLines, progress, cancelled ->
					grabbedDateRanges = dateRanges
					[].iterator()
				}])
		def ui = stub(UI, [runInBackground: runOnTheSameThread])
		def miner = new CodeHistoryMinerPlugin(ui, historyStorage, stub(QueryScriptsStorage), vcsAccess, new codehistoryminer.core.lang.Measure())

		// when
		def now = time("23/11/2012")
        miner.grabHistoryOnVcsUpdate(someProject, now)

        // then
        assert grabbedDateRanges == [new codehistoryminer.core.lang.DateRange(date("20/11/2012"), date("23/11/2012"))]
    }

	@Test def "on grab history should register VCS update listener"() {
		// given
		def listeningToProject = ""
		def ui = stub(UI, [
				showGrabbingDialog: { config, project, onApplyConfig, Closure onOkCallback ->
					def grabOnVcsUpdate = true
					onOkCallback(new HistoryGrabberConfig(Date.today().shiftDays(-300), Date.today(), "some.csv", false, grabOnVcsUpdate, Time.zero()))
				}
		])
		def vcsAccess = stub(VcsActions, [
				readMinedCommits: returns([].iterator()),
				addVcsUpdateListenerFor: { String projectName, listener -> listeningToProject = projectName }
		])
		def miner = new CodeHistoryMinerPlugin(ui, stub(HistoryStorage), stub(QueryScriptsStorage), vcsAccess, new codehistoryminer.core.lang.Measure())

		// when / then
		miner.grabHistoryOf(someProject)
		assert listeningToProject == someProject.name
	}

	@Test void "should only grab history of one project at a time"() {
		// given
		def showedGrabberDialog = 0
		def showedGrabbingInProgress = 0
		def ui = stub(UI, [
				showGrabbingDialog: { config, project, onApplyConfig, Closure onOkCallback ->
					showedGrabberDialog++
					onOkCallback(someConfig)
				},
				showGrabbingInProgressMessage: does{ showedGrabbingInProgress++ },
		])
		def vcsAccess = stub(VcsActions, [readMinedCommits: returns([].iterator())])
		def miner = new CodeHistoryMinerPlugin(ui, stub(HistoryStorage), stub(QueryScriptsStorage), vcsAccess, new codehistoryminer.core.lang.Measure())

		// when / then
		miner.grabHistoryOf(someProject)
		assert showedGrabberDialog == 1
		assert showedGrabbingInProgress == 0

		miner.grabHistoryOf(someProject)
		assert showedGrabberDialog == 1
		assert showedGrabbingInProgress == 1
	}

	private static final runOnTheSameThread = { taskDescription, closure -> closure([:] as ProgressIndicator) }
	private static final someProject = stub(Project, [getName: returns("someProject")])
	private static final someConfig = HistoryGrabberConfig.defaultConfig()
}