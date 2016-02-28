package codehistoryminer.plugin.ui

import codehistoryminer.publicapi.analysis.values.Table
import codehistoryminer.publicapi.analysis.values.TableList
import codehistoryminer.core.miner.Event
import codehistoryminer.core.miner.EventWrapper
import codehistoryminer.core.visualizations.Visualization
import codehistoryminer.core.visualizations.VisualizedAnalyzer
import codehistoryminer.plugin.CodeHistoryMinerPlugin
import codehistoryminer.plugin.historystorage.HistoryGrabberConfig
import codehistoryminer.plugin.historystorage.HistoryStorage
import codehistoryminer.plugin.ui.http.HttpUtil
import com.intellij.ide.BrowserUtil
import com.intellij.ide.GeneralSettings
import com.intellij.ide.actions.ShowFilePathAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerAdapter
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.ui.UIUtil
import liveplugin.CanCallFromAnyThread
import liveplugin.PluginUtil
import liveplugin.implementation.Misc
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable

import javax.swing.event.HyperlinkEvent

import static codehistoryminer.core.visualizations.VisualizedAnalyzer.Bundle.*

import static codehistoryminer.plugin.ui.templates.PluginTemplates.getPluginTemplate
import static com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT
import static com.intellij.notification.NotificationType.INFORMATION
import static liveplugin.PluginUtil.registerAction

@SuppressWarnings("GrMethodMayBeStatic")
class UI {
	CodeHistoryMinerPlugin minerPlugin
	HistoryStorage storage
	Log log
	private ProjectManagerAdapter listener

	def init() {
		def grabHistory = grabHistory()
		def projectStats = projectStats()
		def currentFileHistoryStats = currentFileHistoryStats()
		def openReadme = openReadme()

		def actionGroup = new ActionGroup("Code History Mining", true) {
			@Override AnAction[] getChildren(@Nullable AnActionEvent anActionEvent) {
				def codeHistoryActions = storage.filesWithCodeHistory().collect{ createActionsOnHistoryFile(it) }
				[grabHistory, Separator.instance] + codeHistoryActions +
				[Separator.instance, currentFileHistoryStats, projectStats, openReadme]
			}
		}
		registerAction("CodeHistoryMiningMenu", "", "VcsGroups", "Code History Mining", actionGroup)
		registerAction("CodeHistoryMiningPopup", "alt shift H", "", "Show Code History Mining Popup") { AnActionEvent actionEvent ->
			JBPopupFactory.instance.createActionGroupPopup(
					"Code History Mining",
					actionGroup,
					actionEvent.dataContext,
					JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
					true
			).showCenteredInCurrentWindow(actionEvent.project)
		}
		registerAction("CodeHistoryMiningRunQuery", "alt C, alt Q", "", "Run Code History Query") { AnActionEvent event ->
			minerPlugin.runCurrentFileAsHistoryQueryScript(event.project)
		}

		listener = new ProjectManagerAdapter() {
			@Override void projectOpened(Project project) { minerPlugin.onProjectOpened(project) }
			@Override void projectClosed(Project project) { minerPlugin.onProjectClosed(project) }
		}
		ProjectManager.instance.addProjectManagerListener(listener)
		ProjectManager.instance.openProjects.each{ minerPlugin.onProjectOpened(it) }
	}

	def dispose(oldUI) {
		def oldListener = oldUI?.listener
		if (oldListener != null) {
			ProjectManager.instance.removeProjectManagerListener(oldListener)
		}
	}

	def showGrabbingDialog(HistoryGrabberConfig grabberConfig, Project project, Closure onApplyCallback, Closure onGrabCallback) {
		GrabHistoryDialog.showDialog(grabberConfig, "Grab History Of Current Project", project, onApplyCallback) { HistoryGrabberConfig userInput ->
			onGrabCallback.call(userInput)
		}
	}

	def showInBrowser(String html, String projectName, String visualizationName) {
		def url = HttpUtil.loadIntoHttpServer(html, projectName, visualizationName + ".html", log)

		// need to check if browser configured correctly because it looks like IntelliJ won't do it
		if (browserConfiguredIncorrectly()) {
			PluginUtil.invokeLaterOnEDT{
				Messages.showWarningDialog(
						"It seems that browser is not configured correctly.\nPlease check Settings -> Web Browsers config.",
						"Code History Mining"
				)
			}
			// don't return and try to open url anyway in case the above check is wrong
		}
		BrowserUtil.browse(url)
	}

	def openFileInIdeEditor(File file, Project project) {
		PluginUtil.invokeLaterOnEDT{
			PluginUtil.openInEditor(file.absolutePath, project)
		}
	}

	def showGrabbingInProgressMessage(Project project) {
		UIUtil.invokeLaterIfNeeded{
			Messages.showInfoMessage(project, "Grabbing project history is already in progress. Please wait for it to finish or cancel it.", "Code History Mining")
		}
	}

	def showNoVcsRootsMessage(Project project) {
		UIUtil.invokeLaterIfNeeded{
			Messages.showWarningDialog(project, "Cannot grab project history because there are no VCS roots setup for it.", "Code History Mining")
		}
	}

	def showNoEventsInStorageMessage(String fileName, Project project) {
		UIUtil.invokeLaterIfNeeded{
			Messages.showInfoMessage(project, "There is no data in ${fileName} so nothing to visualize.", "Code History Mining")
		}
	}

	def showGrabbingFinishedMessage(String message, Project project) {
		UIUtil.invokeLaterIfNeeded{
			show(message, "Code History Mining", INFORMATION, "Code History Mining", new NotificationListener() {
				@Override void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
					openInIde(new File(event.URL.path), project)
				}
			})
		}
	}

	def runInBackground(String taskDescription, Closure closure) {
		PluginUtil.doInBackground(taskDescription, closure)
	}

	def showFileHistoryStatsToolWindow(Project project, Map statsMap) {
		PluginUtil.invokeOnEDT {
			FileHistoryStatsToolWindow.showIn(project, statsMap)
		}
	}

	def showFileHasNoVcsHistory(VirtualFile virtualFile) {
		PluginUtil.show("File ${virtualFile.name} has no VCS history")
	}

	def failedToLoadAnalyzers(String scriptFilePath) {
		PluginUtil.show("Failed to load analyzers from '$scriptFilePath'", "", NotificationType.WARNING)
	}

	def showNoHistoryForQueryScript(String scriptFileName) {
		PluginUtil.show("No history file was found for '$scriptFileName' query script")
	}

	def showQueryScriptError(String scriptFileName, String message, Project project) {
		PluginUtil.showInConsole(message, scriptFileName, project, ERROR_OUTPUT)
	}

	def showAnalyzerError(String analyzerName, String message, Project project) {
		PluginUtil.showInConsole(message, analyzerName, project, ERROR_OUTPUT)
	}

	def showAnalyzerResult(result, String projectName, Project project) {
		if (result instanceof Visualization) {
			def html = result.template
					.pasteInto(pluginTemplate)
					.fillProjectName(projectName)
					.inlineImports()
					.text
			showInBrowser(html, projectName, "")

		} else if (result instanceof Table) {
			openFileInIdeEditor(AnalyzerResultHandlers.saveAsCsvFile(result, projectName + "-result"), project)

		} else if (result instanceof TableList) {
			result.tables.each { table -> showAnalyzerResult(table, projectName, project) }

		} else if (result instanceof Collection && !result.empty) {
			def first = result.first()
			if (first instanceof EventWrapper) {
				result = result.collect{ it.event }
				first = result.first()
			}
			if (first instanceof Map || first instanceof Event) {
				openFileInIdeEditor(AnalyzerResultHandlers.saveAsCsvFile(result, projectName + "-result"), project)
			} else {
				result.each { showAnalyzerResult(it, projectName, project) }
			}
		} else if (result instanceof File) {
			openFileInIdeEditor(result, project)
		} else {
			PluginUtil.show(result)
		}
	}

	private static boolean browserConfiguredIncorrectly() {
		def settings = GeneralSettings.instance
		!settings.useDefaultBrowser && !new File(settings.browserPath).exists()
	}

	private grabHistory() {
		registerAction("GrabProjectHistory", "", "", "Grab Project History"){ AnActionEvent event ->
			minerPlugin.grabHistoryOf(event.project)
		}
	}

	private projectStats() {
		new AnAction("Amount of Files in Project") {
			@Override void actionPerformed(AnActionEvent event) {
				FileAmountToolWindow.showIn(event.project, UI.this.minerPlugin.fileCountByFileExtension(event.project))
			}
		}
	}

	private AnAction createActionsOnHistoryFile(File file) {
		Closure<AnAction> createRunQueryAction = {
			new AnAction("Open Query Editor") {
				@Override void actionPerformed(AnActionEvent event) {
					minerPlugin.openQueryEditorFor(event.project, file)
				}
			}
		}
		Closure<AnAction> runAnalyzerAction = { VisualizedAnalyzer analyzer ->
			new AnAction(analyzer.name()) {
				@Override void actionPerformed(AnActionEvent event) {
					minerPlugin.runAnalyzer(file, event.project, analyzer, analyzer.name())
				}
			}
		}
		new DefaultActionGroup(file.name, true).with {
			add(runAnalyzerAction(all))
			add(runAnalyzerAction(commitLogAsGraph))
			add(Separator.instance)
			add(runAnalyzerAction(codeChurnChart))
			add(runAnalyzerAction(amountOfCommittersChart))
			add(runAnalyzerAction(commitsByCommitterChart))
			add(runAnalyzerAction(amountOfTodosChart))
			add(runAnalyzerAction(amountOfFilesInCommitChart))
			add(runAnalyzerAction(amountOfChangingFilesChart))
			add(runAnalyzerAction(changeSizeByFileTypeChart))
			add(runAnalyzerAction(changesTreemap))
			add(runAnalyzerAction(filesInTheSameCommitGraph))
			add(runAnalyzerAction(committersChangingSameFilesGraph))
			add(runAnalyzerAction(commitTimePunchcard))
			add(runAnalyzerAction(timeBetweenCommitsHistogram))
			add(runAnalyzerAction(commitMessagesWordChart))
			add(runAnalyzerAction(commitMessageWordCloud))
			add(Separator.instance)
			add(createRunQueryAction())
			add(showInFileManager(file))
			add(openInIdeAction(file))
			add(renameFileAction(file.name))
			add(deleteFileAction(file.name))
			it
		}
	}

	private currentFileHistoryStats() {
		new AnAction("Current File History Stats") {
			@Override void actionPerformed(AnActionEvent event) {
				UI.this.minerPlugin.showCurrentFileHistoryStats(event.project)
			}
		}
	}

	private static openReadme() {
		new AnAction("Read Me (page on GitHub)") {
			@Override void actionPerformed(AnActionEvent event) {
				BrowserUtil.open("https://github.com/dkandalov/code-history-mining#how-to-use")
			}
		}
	}

	private static openInIdeAction(File file) {
		new AnAction("Open in IDE") {
			@Override void actionPerformed(AnActionEvent event) {
				openInIde(file, event.project)
			}
		}
	}

	private static openInIde(File file, Project project) {
		def virtualFile = VirtualFileManager.instance.findFileByUrl("file://" + file.canonicalPath)
		if (virtualFile != null) {
			FileEditorManager.getInstance(project).openFile(virtualFile, true)
		}
	}

	private static showInFileManager(File file) {
		new AnAction("Show in File Manager") {
			@Override void actionPerformed(AnActionEvent event) {
				ShowFilePathAction.openFile(file)
			}
		}
	}

	private renameFileAction(String fileName) {
		new AnAction("Rename") {
			@Override void actionPerformed(AnActionEvent event) {
				def newFileName = Messages.showInputDialog("New file name:", "Rename File", null, fileName, new InputValidator() {
					@Override boolean checkInput(String newFileName) { UI.this.storage.isValidNewFileName(newFileName) }
					@Override boolean canClose(String newFileName) { true }
				})
				if (newFileName != null) UI.this.storage.rename(fileName, newFileName)
			}
		}
	}

	private deleteFileAction(String fileName) {
		new AnAction("Delete") {
			@Override void actionPerformed(AnActionEvent event) {
				int userAnswer = Messages.showOkCancelDialog("Delete ${fileName}?", "Delete File", "&Delete", "&Cancel", UIUtil.getQuestionIcon())
				if (userAnswer == Messages.OK) storage.delete(fileName)
			}
		}
	}

	@CanCallFromAnyThread
	static show(@Nullable message, @Nullable String title = "", NotificationType notificationType = INFORMATION,
	            String groupDisplayId = "", @Nullable NotificationListener notificationListener = null) {
		PluginUtil.invokeLaterOnEDT {
			message = Misc.asString(message)
			// this is because Notification doesn't accept empty messages
			if (message.trim().empty) message = "[empty message]"

			def notification = new Notification(groupDisplayId, title, message, notificationType, notificationListener)
			ApplicationManager.application.messageBus.syncPublisher(Notifications.TOPIC).notify(notification)
		}
	}

	interface Log {
		def httpServerIsAboutToLoadHtmlFile(String fileName)
		def errorOnHttpRequest(String message)
	}
}