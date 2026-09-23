package dev.intellijev.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.util.ui.WrapLayout
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import dev.intellijev.core.*
import dev.contextpacker.pack.PackedFile
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.concurrent.Future
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class IntelliJevPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private enum class Work { CONTEXT, BUGS, PROPOSAL }

    private val workLock = Any()
    private val runningWork = mutableMapOf<Work, Future<*>>()
    @Volatile private var disposed = false
    private val service = project.getService(IntelliJevProjectService::class.java)
    private val contextModel = DefaultListModel<ContextCandidate>()
    private val bugsModel = DefaultListModel<BugCandidate>()
    private val runsModel = DefaultListModel<String>()
    private val proposalModel = DefaultListModel<ProposedChange>()
    private val task = JBTextField("Fix coupon expiry boundary and update checkout validation")
    private val tabs = JTabbedPane()
    private var contextGeneration = 0L
    private var bugsGeneration = 0L
    private var proposalGeneration = 0L
    private var lastContextTask: String? = null
    private var selectedFix: String? = null
    private var selectedFixFile: VirtualFile? = null
    private var selectedFixLines: IntRange? = null
    private val contextAnalysis = JBTextArea("Add your TypeSafe key in Settings to rank context with Jev. A coding-model key enables prose explanations.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val bugAnalysis = JBTextArea("Bug Twins is an experimental keyword scan. A coding-model key enables a review plan.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        task.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = invalidateTaskResults()
            override fun removeUpdate(e: DocumentEvent) = invalidateTaskResults()
            override fun changedUpdate(e: DocumentEvent) = invalidateTaskResults()
        })
        tabs.addTab("Context", contextTab())
        tabs.addTab("Related Bugs", bugsTab())
        tabs.addTab("Coding Agent", agentTab())
        tabs.addTab("Runs", runsTab())
        tabs.addTab("Side Question", sideQuestionTab())
        tabs.addTab("Settings", settingsTab())
        add(tabs, BorderLayout.CENTER)
    }

    private fun contextTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val header = JPanel(BorderLayout(6, 0)).apply {
            add(task, BorderLayout.CENTER)
            add(JButton("Scan task context").apply { addActionListener { scanContext() } }, BorderLayout.EAST)
        }
        val list = JBList(contextModel).apply { cellRenderer = ContextRenderer() }
        list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { candidate -> open(candidate.file) } }
        add(header, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), JBScrollPane(contextAnalysis)).apply { resizeWeight = 0.7 }, BorderLayout.CENTER)
        add(JLabel("Scan may send short source previews to Jev and task/path metadata to the coding model when keys are set."), BorderLayout.SOUTH)
    }

    private fun bugsTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Find candidates").apply { addActionListener { scanBugs() } })
            add(JLabel("  Candidates require review; this does not claim a confirmed defect."))
        }
        val list = JBList(bugsModel).apply { cellRenderer = BugRenderer() }
        list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { candidate -> open(candidate.file, candidate.line) } }
        add(toolbar, BorderLayout.NORTH); add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), JBScrollPane(bugAnalysis)).apply { resizeWeight = 0.7 }, BorderLayout.CENTER)
    }

    private fun runsTab(): JComponent = JPanel(BorderLayout()).apply {
        val list = JBList(runsModel)
        add(JButton("Refresh run events").apply { addActionListener { refreshRuns() } }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    private fun sideQuestionTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val notes = project.getService(SideQuestionState::class.java)
        val question = JBTextArea().apply {
            rows = 5
            columns = 20
            text = notes.note()
            emptyText.text = "Write a side question here. It stays separate from task context."
            lineWrap = true
            wrapStyleWord = true
        }
        val saved = JLabel("Stored in this project's local workspace settings.")
        question.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { saved.text = "Unsaved changes." }
            override fun removeUpdate(e: DocumentEvent) { saved.text = "Unsaved changes." }
            override fun changedUpdate(e: DocumentEvent) { saved.text = "Unsaved changes." }
        })
        add(JBScrollPane(question), BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(saved, BorderLayout.CENTER)
            add(JButton("Save isolated note").apply {
                addActionListener {
                    try {
                        notes.save(question.text)
                        saved.text = "Saved locally for this project."
                        service.log("Saved isolated side question")
                        refreshRuns()
                    } catch (e: IllegalArgumentException) {
                        saved.text = e.message ?: "Side question is too long."
                        JOptionPane.showMessageDialog(this@IntelliJevPanel, saved.text, "IntelliJev", JOptionPane.WARNING_MESSAGE)
                    }
                }
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    private fun agentTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val proposalText = JBTextArea("Describe a change, then choose Propose reviewed changes. No file is edited until you click Apply.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        val before = JBTextArea().apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
        val after = JBTextArea().apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
        val list = JBList(proposalModel).apply { cellRenderer = ProposalRenderer() }
        val openDiff = JButton("Open diff").apply {
            isEnabled = false
            toolTipText = "Compare the captured original and proposed text in a read-only diff"
            addActionListener {
                if (disposed || project.isDisposed) return@addActionListener
                val change = list.selectedValue ?: return@addActionListener
                val contentFactory = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(
                    "Proposed change: ${change.file.name}",
                    contentFactory.create(project, change.before, change.file.fileType),
                    contentFactory.create(project, change.after, change.file.fileType),
                    "Captured original (${change.file.name})",
                    "Proposed replacement (${change.file.name})",
                )
                request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                DiffManager.getInstance().showDiff(project, request)
            }
        }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val change = list.selectedValue
                before.text = change?.before.orEmpty()
                after.text = change?.after.orEmpty()
                openDiff.isEnabled = change != null && !disposed && !project.isDisposed
            }
        }
        val apply = JButton("Apply selected change").apply { addActionListener { list.selectedValue?.let { if (applyChange(it)) proposalText.text = "Applied ${it.file.name}. Use Undo to revert." } } }
        val controls = JPanel(WrapLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Propose reviewed changes").apply { addActionListener { runAgent(proposalText) } }); add(Box.createHorizontalStrut(8)); add(openDiff); add(Box.createHorizontalStrut(8)); add(apply)
        }
        val diff = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JPanel(BorderLayout()).apply { add(JLabel("Captured original"), BorderLayout.NORTH); add(JBScrollPane(before), BorderLayout.CENTER) }, JPanel(BorderLayout()).apply { add(JLabel("Proposed replacement"), BorderLayout.NORTH); add(JBScrollPane(after), BorderLayout.CENTER) }).apply { resizeWeight = 0.5 }
        add(controls, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(proposalText), JBScrollPane(list)).apply { resizeWeight = 0.35 }, diff).apply { resizeWeight = 0.35 }, BorderLayout.CENTER)
    }

    private fun settingsTab(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        val settings = IntelliJevSettings.instance()
        val provider = JComboBox(arrayOf("OpenAI", "OpenRouter")).apply { selectedItem = settings.provider() }
        val model = JBTextField(settings.model())
        val generationKey = JPasswordField().apply { toolTipText = "Leave empty to keep the saved key" }
        val jevKey = JPasswordField().apply { toolTipText = "Leave empty to keep the saved TypeSafe key" }
        add(JLabel("TypeSafe Jev key (direct API; stored in IntelliJ Password Safe)")); add(jevKey)
        add(Box.createVerticalStrut(10)); add(JLabel("Coding model provider")); add(provider)
        add(Box.createVerticalStrut(10)); add(JLabel("Coding model ID (OpenRouter example: openai/gpt-4.1-mini)")); add(model)
        add(Box.createVerticalStrut(10)); add(JLabel("Coding model API key (separate from TypeSafe)")); add(generationKey)
        add(Box.createVerticalStrut(10)); add(JButton("Save AI configuration").apply {
            addActionListener {
                IntelliJevSettings.instance().save(provider.selectedItem as String, model.text, String(generationKey.password), String(jevKey.password))
                generationKey.text = ""; jevKey.text = ""
                service.log("Saved IntelliJev configuration"); refreshRuns()
            }
        })
        add(Box.createVerticalStrut(10)); add(JLabel("Jev ranks context and estimates review risk. The coding model proposes edits for your approval."))
    }

    private fun invalidateTaskResults() {
        cancelWork(Work.CONTEXT, Work.PROPOSAL)
        contextGeneration++
        proposalGeneration++
        lastContextTask = null
        proposalModel.clear()
    }

    /** Explicit handoff from the ranked context workspace into the reviewed-edit workflow. */
    fun acceptPackedContext(taskText: String, files: List<PackedFile>): Boolean {
        if (disposed || project.isDisposed || taskText.isBlank()) return false
        val root = project.baseDir ?: return false
        val selected = files.take(8).mapIndexedNotNull { index, packed ->
            val file = root.findFileByRelativePath(packed.path.replace('\\', '/'))
                ?.takeIf { it.isValid && !it.isDirectory && it.length <= 12_000 }
                ?: return@mapIndexedNotNull null
            val role = when (packed.role) {
                "test" -> ContextRole.EXAMPLE
                "dependency" -> ContextRole.CONSTRAINT
                else -> ContextRole.EDIT_TARGET
            }
            ContextCandidate(file, role, 100 - index, "Selected by IntelliJev context ranking")
        }
        if (selected.isEmpty()) return false
        cancelWork(Work.CONTEXT, Work.PROPOSAL)
        task.text = taskText
        contextModel.clear()
        selected.forEach(contextModel::addElement)
        contextAnalysis.text = "${selected.size} selected files are ready for a reviewed proposal."
        lastContextTask = taskText
        tabs.selectedIndex = 2
        service.log("Loaded ${selected.size} ranked files for reviewed edits")
        refreshRuns()
        return true
    }

    fun findContextFromSelection(selection: String?) {
        tabs.selectedIndex = 0
        if (!selection.isNullOrBlank()) task.text = selection.trim().replace(Regex("\\s+"), " ").take(500)
        scanContext()
    }

    fun findRelatedCodeFromSelection(selection: String, file: VirtualFile?, lines: IntRange) {
        selectedFix = selection
        selectedFixFile = file
        selectedFixLines = lines
        tabs.selectedIndex = 1
        scanBugs(useCurrentEditor = false)
    }

    fun scanContext() {
        if (disposed) return
        cancelWork(Work.CONTEXT, Work.PROPOSAL)
        val currentTask = task.text
        val generation = ++contextGeneration
        proposalGeneration++
        lastContextTask = null
        proposalModel.clear()
        background(Work.CONTEXT, "Finding context") {
            val results = service.findContext(currentTask)
            val analysis = service.explainContext(currentTask, results)
            SwingUtilities.invokeLater {
                if (disposed || project.isDisposed || generation != contextGeneration || task.text != currentTask) return@invokeLater
                contextModel.clear(); results.forEach(contextModel::addElement)
                contextAnalysis.text = analysis; lastContextTask = currentTask; refreshRuns()
            }
        }
    }

    fun scanBugs(useCurrentEditor: Boolean = true) {
        if (disposed) return
        cancelWork(Work.BUGS)
        val editor = if (useCurrentEditor) FileEditorManager.getInstance(project).selectedTextEditor else null
        val liveSelection = editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }
        if (useCurrentEditor) {
            if (liveSelection != null && editor != null) {
                selectedFix = liveSelection
                selectedFixFile = FileDocumentManager.getInstance().getFile(editor.document)
                val start = editor.selectionModel.selectionStart
                val end = (editor.selectionModel.selectionEnd - 1).coerceAtLeast(start)
                selectedFixLines = editor.document.getLineNumber(start)..editor.document.getLineNumber(end)
            } else {
                selectedFix = null
                selectedFixFile = null
                selectedFixLines = null
            }
        }
        val fix = selectedFix?.takeIf { it.isNotBlank() }
        val generation = ++bugsGeneration
        if (fix == null) {
            bugsModel.clear()
            bugAnalysis.text = "Select a fix in the editor before finding possible related code."
            return
        }
        val sourceFile = selectedFixFile
        val sourceLines = selectedFixLines
        background(Work.BUGS, "Finding possible related code") {
        val results = service.findBugTwins(fix, sourceFile, sourceLines)
        val analysis = service.explainBugs(results)
        SwingUtilities.invokeLater {
            if (disposed || project.isDisposed || generation != bugsGeneration) return@invokeLater
            bugsModel.clear(); results.forEach(bugsModel::addElement)
            bugAnalysis.text = analysis; refreshRuns()
        }
        }
    }
    private fun runAgent(output: JBTextArea) {
        if (disposed) return
        cancelWork(Work.PROPOSAL)
        val currentTask = task.text
        val selectedContext = if (lastContextTask == currentTask) (0 until contextModel.size()).map { contextModel.getElementAt(it) } else emptyList()
        val generation = ++proposalGeneration
        val contextAtStart = contextGeneration
        background(Work.PROPOSAL, "Running coding agent") {
        val proposal = service.proposeChanges(currentTask, selectedContext)
        SwingUtilities.invokeLater {
            if (disposed || project.isDisposed || generation != proposalGeneration || contextAtStart != contextGeneration || task.text != currentTask) return@invokeLater
            proposalModel.clear(); proposal.changes.forEach(proposalModel::addElement)
            output.text = proposal.summary; refreshRuns()
        }
        }
    }
    private fun applyChange(change: ProposedChange): Boolean {
        if (!change.file.isValid || change.file.isDirectory) {
            JOptionPane.showMessageDialog(this, "The selected file is no longer available. Request a fresh proposal before applying it.", "IntelliJev", JOptionPane.WARNING_MESSAGE)
            return false
        }
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(change.file).hasReadonlyFiles()) {
            JOptionPane.showMessageDialog(this, "The selected file is read-only.", "IntelliJev", JOptionPane.WARNING_MESSAGE)
            return false
        }
        val result = ReviewedChangeApplier.apply(project, change)
        val document = when (result) {
            is ApplyChangeResult.Applied -> result.document
            ApplyChangeResult.Stale -> {
                JOptionPane.showMessageDialog(this, "This file changed after the proposal was generated. Request a fresh proposal before applying it.", "IntelliJev", JOptionPane.WARNING_MESSAGE)
                return false
            }
            ApplyChangeResult.Unavailable -> {
                JOptionPane.showMessageDialog(this, "The selected file is no longer available. Request a fresh proposal before applying it.", "IntelliJev", JOptionPane.WARNING_MESSAGE)
                return false
            }
        }
        FileDocumentManager.getInstance().saveDocument(document)
        proposalModel.removeElement(change)
        service.log("Applied reviewed change to ${change.file.name}"); refreshRuns(); open(change.file)
        return true
    }
    private fun cancelWork(vararg kinds: Work) {
        synchronized(workLock) { kinds.forEach { runningWork.remove(it)?.cancel(true) } }
    }
    private fun background(kind: Work, label: String, work: () -> Unit) {
        synchronized(workLock) {
            if (disposed) return
            service.log(label)
            runningWork[kind] = ApplicationManager.getApplication().executeOnPooledThread(work)
        }
    }
    override fun dispose() {
        synchronized(workLock) {
            disposed = true
            runningWork.values.forEach { it.cancel(true) }
            runningWork.clear()
        }
    }
    private fun refreshRuns() { runsModel.clear(); service.events().forEach { runsModel.addElement("${it.time}  ${it.message}") } }
    private fun open(file: VirtualFile, line: Int = 0) { OpenFileDescriptor(project, file, line, 0).navigate(true) }
}

private class ContextRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, selected, focus) as JLabel
        (value as? ContextCandidate)?.let { c.text = "[${it.role.label}] ${it.file.path}  · ${it.score} ranking points — ${it.reason}" }
        return c
    }
}
private class BugRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, selected, focus) as JLabel
        (value as? BugCandidate)?.let { c.text = "Candidate · ${it.file.name}:${it.line + 1} — ${it.evidence}" }
        return c
    }
}
private class ProposalRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, selected, focus) as JLabel
        (value as? ProposedChange)?.let { c.text = "${it.file.name} — ${it.summary}" }
        return c
    }
}
