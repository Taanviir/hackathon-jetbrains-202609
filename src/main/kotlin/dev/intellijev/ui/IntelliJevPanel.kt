package dev.intellijev.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import dev.intellijev.core.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class IntelliJevPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val service = project.getService(IntelliJevProjectService::class.java)
    private val contextModel = DefaultListModel<ContextCandidate>()
    private val bugsModel = DefaultListModel<BugCandidate>()
    private val runsModel = DefaultListModel<String>()
    private val proposalModel = DefaultListModel<ProposedChange>()
    private val task = JBTextField("Fix coupon expiry boundary and update checkout validation")
    private val contextAnalysis = JBTextArea("Add your TypeSafe key in Settings to rank context with Jev. A coding-model key enables prose explanations.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val bugAnalysis = JBTextArea("Bug Twins is an experimental keyword scan. A coding-model key enables a review plan.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val tabs = JTabbedPane()
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
        add(JLabel("Scans common source/configuration formats locally. It does not submit code until you run the Coding Agent."), BorderLayout.SOUTH)
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
        val question = JBTextArea("Ask a tangential question here. This text stays separate from the task context.", 5, 20)
        add(JBScrollPane(question), BorderLayout.CENTER)
        add(JButton("Save isolated note").apply { addActionListener { service.log("Saved isolated side question"); refreshRuns() } }, BorderLayout.SOUTH)
    }

    private fun agentTab(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val proposalText = JBTextArea("Describe a change, then choose Propose reviewed changes. No file is edited until you click Apply.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        val before = JBTextArea().apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
        val after = JBTextArea().apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
        val list = JBList(proposalModel).apply { cellRenderer = ProposalRenderer() }
        list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { change -> before.text = change.before; after.text = change.after } }
        val apply = JButton("Apply selected change").apply { addActionListener { list.selectedValue?.let { applyChange(it); proposalText.text = "Applied ${it.file.name}. Use Undo to revert." } } }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Propose reviewed changes").apply { addActionListener { runAgent(proposalText) } }); add(Box.createHorizontalStrut(8)); add(apply)
        }
        val diff = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JPanel(BorderLayout()).apply { add(JLabel("Current file"), BorderLayout.NORTH); add(JBScrollPane(before), BorderLayout.CENTER) }, JPanel(BorderLayout()).apply { add(JLabel("Proposed replacement"), BorderLayout.NORTH); add(JBScrollPane(after), BorderLayout.CENTER) }).apply { resizeWeight = 0.5 }
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

    fun scanContext() = background("Finding context") {
        val results = service.findContext(task.text)
        val analysis = service.explainContext(task.text, results)
        SwingUtilities.invokeLater { contextModel.clear(); results.forEach(contextModel::addElement); contextAnalysis.text = analysis; refreshRuns() }
    }
    fun scanBugs() = background("Finding related-bug candidates") {
        val results = service.findBugTwins()
        val analysis = service.explainBugs(results)
        SwingUtilities.invokeLater { bugsModel.clear(); results.forEach(bugsModel::addElement); bugAnalysis.text = analysis; refreshRuns() }
    }
    private fun runAgent(output: JBTextArea) {
        val currentTask = task.text
        val selectedContext = (0 until contextModel.size()).map { contextModel.getElementAt(it) }
        background("Running coding agent") {
        val proposal = service.proposeChanges(currentTask, selectedContext)
        SwingUtilities.invokeLater { proposalModel.clear(); proposal.changes.forEach(proposalModel::addElement); output.text = proposal.summary; refreshRuns() }
        }
    }
    private fun applyChange(change: ProposedChange) {
        val document = FileDocumentManager.getInstance().getDocument(change.file) ?: return
        if (document.text != change.before) {
            JOptionPane.showMessageDialog(this, "This file changed after the proposal was generated. Request a fresh proposal before applying it.", "IntelliJev", JOptionPane.WARNING_MESSAGE)
            return
        }
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(change.file).hasReadonlyFiles()) {
            JOptionPane.showMessageDialog(this, "The selected file is read-only.", "IntelliJev", JOptionPane.WARNING_MESSAGE)
            return
        }
        WriteCommandAction.runWriteCommandAction(project, "Apply IntelliJev proposal", null, Runnable {
            document.setText(change.after)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        proposalModel.removeElement(change)
        service.log("Applied reviewed change to ${change.file.name}"); refreshRuns(); open(change.file)
    }
    private fun background(label: String, work: () -> Unit) {
        service.log(label); ApplicationManager.getApplication().executeOnPooledThread(work)
    }
    private fun refreshRuns() { runsModel.clear(); service.events().forEach { runsModel.addElement("${it.time}  ${it.message}") } }
    private fun open(file: VirtualFile, line: Int = 0) { FileEditorManager.getInstance(project).openFile(file, true) }
}

private class ContextRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, selected, focus) as JLabel
        (value as? ContextCandidate)?.let { c.text = "[${it.role.label}] ${it.file.path}  · ${it.score}% — ${it.reason}" }
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
