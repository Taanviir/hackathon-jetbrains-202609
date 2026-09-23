package dev.contextpacker.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import dev.contextpacker.ContextPackerService
import dev.contextpacker.DecisionProvider
import dev.contextpacker.Keys
import dev.contextpacker.MissingKeyException
import dev.contextpacker.PackReport
import dev.contextpacker.PackerSettings
import dev.contextpacker.Prompt
import dev.contextpacker.llm.ChatClient
import dev.contextpacker.pack.PackedFile
import dev.contextpacker.pack.Packer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private const val PREVIEW_DEBOUNCE_MS = 700L

/** Type a task, pack, check the picks, then copy them as a prompt or ask an LLM directly. */
class PackerPanel(
    private val project: Project,
    private val reviewPackedContext: ((String, List<PackedFile>) -> Unit)? = null,
) : JPanel(BorderLayout()), Disposable {
    private val service = project.service<ContextPackerService>()
    private val settings = project.service<PackerSettings>()
    private var displayedProvider = service.provider

    private val task = JBTextArea(3, 40).apply {
        font = JBUI.Fonts.label()
        border = JBUI.Borders.empty(4, 6)
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Describe the change, e.g. \"Add exponential backoff to retries\""
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && (e.isControlDown || e.isMetaDown)) runPack()
            }
        })
    }
    private val packButton = JButton("Pack context", AllIcons.Actions.Find).apply { addActionListener { runPack() } }
    private val cancelButton = JButton("Cancel").apply {
        isEnabled = false
        addActionListener {
            cancelPreview()
            promptSerial++
            answerSerial++
            promptJob?.cancel()
            job?.cancel()
            answerJob?.cancel()
            answer.text = "Cancelled"
            status.text = "Cancelled"
            isEnabled = false
        }
    }
    private val provider = JComboBox(DecisionProvider.entries.toTypedArray()).apply {
        selectedItem = displayedProvider
        toolTipText = "Fast keywords reads source locally with no model. Laya uses a local server; Jev uses your configured API."
        addActionListener {
            val selected = selectedItem as? DecisionProvider ?: return@addActionListener
            if (selected != displayedProvider) {
                service.provider = selected
                displayedProvider = selected
                invalidateProviderContext("Switched to ${selected.label}; pack again")
            }
        }
    }
    private val settingsButton = JButton("Settings…").apply {
        toolTipText = "Edit Jev and Laya separately; API keys stay in the IDE password store."
        addActionListener {
            val selected = provider.selectedItem as DecisionProvider
            if (ProviderSettingsDialog(project, settings, selected).showAndGet()) {
                displayedProvider = settings.provider
                provider.selectedItem = displayedProvider
                invalidateProviderContext("Provider settings saved; pack again")
            }
        }
    }
    private val providerSummary = JBTextArea(2, 40).apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.label()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val status = JBTextArea(5, 24).apply {
        text = " "
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.label()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    private val picks = DefaultListModel<PackedFile>()
    private val list = JBList(picks).apply {
        cellRenderer = PickRenderer()
        emptyText.text = "Picked files show up here"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedValue?.let(::open)
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE) dropSelected()
            }
        })
    }

    private val answer = JBTextArea().apply {
        font = JBUI.Fonts.label()
        border = JBUI.Borders.empty(4, 6)
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask OpenRouter sends the task plus the picked files to a cloud model"
    }

    private var lastReport: PackReport? = null
    private var job: Job? = null
    private var previewJob: Job? = null
    private var previewSerial = 0L
    /** Suppress a new preview while replaying an agent's task into the editor. */
    private var settingTask = false
    private var replacingPicks = false
    private var promptJob: Job? = null
    private var answerJob: Job? = null
    @Volatile private var requestSerial = 0
    private var answerSerial = 0
    private var promptSerial = 0
    @Volatile private var inputSerial = 0
    private var taskSerial = 0
    private var removePackListener: (() -> Unit)? = null
    @Volatile private var disposed = false

    init {
        border = JBUI.Borders.empty(6)
        task.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTaskChanged()
            override fun removeUpdate(e: DocumentEvent) = onTaskChanged()
            override fun changedUpdate(e: DocumentEvent) = onTaskChanged()
        })
        picks.addListDataListener(object : ListDataListener {
            private fun changed() {
                if (!replacingPicks) cancelPreview()
                invalidatePromptAndAnswer()
            }
            override fun intervalAdded(e: ListDataEvent) = changed()
            override fun intervalRemoved(e: ListDataEvent) = changed()
            override fun contentsChanged(e: ListDataEvent) = changed()
        })
        val top = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(task), BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 4)).apply {
                add(JPanel(WrapLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(provider); add(settingsButton); add(packButton); add(cancelButton)
                }, BorderLayout.NORTH)
                add(providerSummary, BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        val actions = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(button("Add open file", AllIcons.General.Add) { addOpenFile() })
            add(button("Drop", AllIcons.General.Remove) { dropSelected() })
            add(button("Copy prompt", AllIcons.Actions.Copy) { copyPrompt() })
            if (reviewPackedContext != null) add(button("Review changes", AllIcons.Actions.Execute) {
                val report = lastReport
                if (report == null || report.result.preview || report.result.task != task.text.trim()) {
                    status.text = "Pack this task before opening reviewed edits"
                } else {
                    val selected = (0 until picks.size()).map(picks::getElementAt)
                    if (selected.isEmpty()) status.text = "Select at least one file for reviewed edits"
                    else reviewPackedContext.invoke(report.result.task, selected)
                }
            })
            add(button("Ask OpenRouter (cloud)", AllIcons.Actions.Execute) { askLlm() }.apply {
                toolTipText = "Sends the task and selected file contents to OpenRouter. API charges may apply."
            })
        }
        val middle = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
        val split = JBSplitter(true, 0.6f).apply {
            firstComponent = middle
            secondComponent = JBScrollPane(answer)
        }
        add(top, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        updateProviderSummary()

        // An agent's pack shows up here too, so the human can see exactly what context it was handed,
        // including one made before this window was first opened.
        removePackListener = service.onPack(replayLast = true) { report ->
            val localIntent = requestSerial
            val inputAtNotice = inputSerial
            if (report.source != "tool window" && !report.result.preview) service.scope.launch(Dispatchers.EDT) {
                if (!disposed && localIntent == requestSerial && inputAtNotice == inputSerial &&
                    service.lastReport === report && report.settingsRevision == settings.revision
                ) {
                    showExternalReport(report)
                }
            }
        }
    }

    private fun setTask(text: String) {
        settingTask = true
        try { task.text = text } finally { settingTask = false }
    }

    private fun cancelPreview() {
        previewSerial++
        previewJob?.cancel()
        previewJob = null
    }

    private fun updateProviderSummary() {
        val selected = provider.selectedItem as DecisionProvider
        val configuration = try { settings.configuration(selected) } catch (e: IllegalArgumentException) {
            providerSummary.text = "${selected.label} settings need attention: ${e.message}"
            return
        }
        val length = task.text.trim().length
        val overLimit = if (length > configuration.taskLimit) " · Task is $length characters; shorten before packing" else ""
        providerSummary.text = configuration.description + overLimit
    }

    /** A provider or profile change makes every previous result and handoff stale, but keeps the task text. */
    private fun invalidateProviderContext(message: String) {
        cancelPreview()
        requestSerial++
        inputSerial++
        promptSerial++
        answerSerial++
        job?.cancel()
        promptJob?.cancel()
        answerJob?.cancel()
        job = null
        promptJob = null
        answerJob = null
        lastReport = null
        replacingPicks = true
        try { picks.clear() } finally { replacingPicks = false }
        answer.text = ""
        packButton.isEnabled = true
        cancelButton.isEnabled = false
        status.toolTipText = null
        status.text = message
        updateProviderSummary()
        schedulePreview()
    }

    /** Search-as-you-type uses local full-corpus keywords only; Pack uses the selected provider. */
    private fun schedulePreview() {
        cancelPreview()
        val text = task.text.trim()
        val selected = provider.selectedItem as DecisionProvider
        val limit = runCatching { settings.configuration(selected).taskLimit }.getOrNull() ?: return
        if (disposed || text.length < 12 || text.split(Regex("\\s+")).size < 3 ||
            text.length > limit ||
            (0 until picks.size()).any { picks[it].bm25Rank == null }
        ) return
        val serial = previewSerial
        val taskAtStart = taskSerial
        val requestAtStart = requestSerial
        val inputAtStart = inputSerial
        val providerAtStart = provider.selectedItem
        previewJob = service.scope.launch {
            try {
                delay(PREVIEW_DEBOUNCE_MS)
                service.preview(text)
                    .takeIf { it.provider == DecisionProvider.KEYWORDS && it.result.preview }
                    ?.let { report ->
                        withContext(Dispatchers.EDT) {
                            if (!disposed && serial == previewSerial && taskAtStart == taskSerial &&
                                requestAtStart == requestSerial && inputAtStart == inputSerial &&
                                provider.selectedItem == providerAtStart && task.text.trim() == text
                            ) show(report)
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                // A local preview is optional; the explicit Pack action reports errors.
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (serial == previewSerial) previewJob = null
                }
            }
        }
    }

    private fun button(text: String, icon: javax.swing.Icon, action: () -> Unit) =
        JButton(text, icon).apply { addActionListener { action() } }

    private fun invalidatePromptAndAnswer() {
        inputSerial++
        promptSerial++
        answerSerial++
        promptJob?.cancel()
        answerJob?.cancel()
        answer.text = ""
        cancelButton.isEnabled = job?.isActive == true
    }

    private fun onTaskChanged() {
        taskSerial++
        invalidatePromptAndAnswer()
        updateProviderSummary()
        if (job?.isActive == true) {
            requestSerial++
            job?.cancel()
            packButton.isEnabled = true
            provider.isEnabled = true
            settingsButton.isEnabled = true
            cancelButton.isEnabled = false
            status.text = "Task changed; pack again"
        }
        if (!settingTask) {
            if (lastReport != null) {
                val pinned = (0 until picks.size()).map(picks::getElementAt).filter { it.bm25Rank == null }
                lastReport = null
                replacingPicks = true
                try {
                    picks.clear()
                    pinned.forEach(picks::addElement)
                } finally {
                    replacingPicks = false
                }
                status.text = if (pinned.isEmpty()) "Task changed; local keyword preview pending"
                    else "Task changed; pinned files kept. Press Pack for a new ranking"
            }
            schedulePreview()
        }
    }

    private fun showExternalReport(report: PackReport) {
        cancelPreview()
        requestSerial++
        job?.cancel()
        invalidatePromptAndAnswer()
        packButton.isEnabled = true
        provider.isEnabled = true
        settingsButton.isEnabled = true
        cancelButton.isEnabled = false
        setTask(report.result.task)
        show(report)
    }

    override fun dispose() {
        disposed = true
        removePackListener?.invoke()
        removePackListener = null
        cancelPreview()
        job?.cancel()
        promptJob?.cancel()
        answerJob?.cancel()
    }

    private fun runPack() {
        val text = task.text.trim().ifEmpty { return }
        cancelPreview()
        val selectedProvider = provider.selectedItem as DecisionProvider
        val taskLimit = try { settings.configuration(selectedProvider).taskLimit } catch (e: IllegalArgumentException) {
            status.text = "Provider settings need attention: ${e.message}"
            return
        }
        if (text.length > taskLimit) {
            status.text = "${selectedProvider.label} accepts at most $taskLimit task characters; shorten this task by ${text.length - taskLimit}."
            status.toolTipText = null
            return
        }
        job?.cancel()
        promptSerial++
        promptJob?.cancel()
        answerSerial++
        answerJob?.cancel()
        val serial = ++requestSerial
        val taskAtStart = taskSerial
        packButton.isEnabled = false
        status.toolTipText = null
        lastReport = null
        answer.text = ""
        picks.clear()
        cancelButton.isEnabled = true
        job = service.scope.launch {
            try {
                val report = service.pack(text, requestedProvider = selectedProvider) { msg -> launch(Dispatchers.EDT) {
                    if (!disposed && serial == requestSerial) status.text = "$msg…"
                } }
                withContext(Dispatchers.EDT) {
                    if (!disposed && serial == requestSerial && taskAtStart == taskSerial) show(report)
                }
            } catch (e: MissingKeyException) {
                withContext(Dispatchers.EDT) { if (!disposed && serial == requestSerial) status.text = e.message }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (!disposed && serial == requestSerial) status.text = "Cancelled"
                }
                throw e
            } catch (e: Exception) {
                // Status line gets the readable part; the tooltip keeps the server's own words.
                withContext(Dispatchers.EDT) {
                    if (!disposed && serial == requestSerial) {
                        status.text = "Failed: " + (e.message ?: e::class.simpleName.orEmpty()).substringBefore(" (HTTP")
                        status.toolTipText = e.message
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (!disposed && serial == requestSerial) {
                        packButton.isEnabled = true
                        cancelButton.isEnabled = answerJob?.isActive == true
                    }
                }
            }
        }
    }

    private fun show(report: PackReport) {
        lastReport = report
        replacingPicks = true
        try {
            picks.clear()
            report.result.files.forEach(picks::addElement)
        } finally {
            replacingPicks = false
        }
        val r = report.result
        if (r.preview) {
            status.text = "Local keyword preview · %d of %,d files · %.1f s\nNo model calls · Pack for the selected provider".format(
                r.files.size, r.candidates, report.totalMs / 1000.0,
            )
            status.toolTipText = "Full-corpus BM25 over local source and paths. No model or network request; press Pack to use the selected provider."
            return
        }
        val failed = if (r.failedBatches > 0) " · ${r.failedBatches} incomplete batches" else ""
        val cost = when (report.provider) {
            DecisionProvider.KEYWORDS -> "API fee $0 · local compute excluded"
            DecisionProvider.LAYA -> "local · API fee $0"
            DecisionProvider.JEV -> report.costUsd?.let { "est. API $%.4f".format(it) } ?: "API fee unavailable"
        }
        val tokens = when {
            report.provider == DecisionProvider.KEYWORDS -> "0 API tokens"
            report.usageKnown -> "%.1fk tokens".format(report.inputTokens / 1000.0)
            else -> "tokens unavailable"
        }
        val calls = if (report.provider == DecisionProvider.KEYWORDS) "full-corpus BM25 · no model calls"
            else "${report.jevCalls} ${report.provider.name} requests" +
                if (report.cachedRequests > 0) " · ${report.cachedRequests} cached" else ""
        status.text = listOfNotNull(
            if (report.source == "tool window") null else "Asked by ${report.source} · ${report.provider.label}",
            "%d of %,d files · %.1f s".format(r.files.size, r.candidates, report.totalMs / 1000.0),
            "$calls · $tokens",
            "$cost$failed",
        ).joinToString("\n")
        status.toolTipText = if (report.provider == DecisionProvider.KEYWORDS) {
            "Read ${report.scoredCandidates} files in ${report.sketchMs} ms; ranked the full corpus with BM25 in ${r.pass1Ms} ms. No model or network request."
        } else {
            "sketch %d ms · pass 1 + BM25 %d ms · remaining pass 2 %d ms · stage 3 %d ms · %s · scored %d candidates%s".format(
                report.sketchMs, r.pass1Ms, r.pass2Ms, r.stage3Ms, report.jevModel, report.scoredCandidates,
                if (report.provider == DecisionProvider.LAYA) " after keyword prefilter; sequential passes; ${report.cachedRequests} cache hits (${report.cachedInputTokens} input tokens reused); local compute cost excluded"
                else "; passes overlap; phase times measure elapsed wall time",
            )
        }
    }

    private fun open(file: PackedFile) {
        service.fileFor(file.path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun dropSelected() {
        list.selectedValuesList.forEach(picks::removeElement)
    }

    /** Pin an open, readable project file that the ranking missed. */
    private fun addOpenFile() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (file == null) {
            status.text = "Open a project file before adding it."
            return
        }
        val path = project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) }
        if (path == null) {
            status.text = "Only files inside this project can be added."
            return
        }
        if (service.fileFor(path) == null) {
            status.text = service.sourceProblem(path) ?: "This file is no longer available."
            return
        }
        if ((0 until picks.size()).none { picks[it].path == path }) {
            val modelScore = if ((lastReport?.provider ?: provider.selectedItem) == DecisionProvider.KEYWORDS) 0.0 else 1.0
            picks.add(0, PackedFile(path, relevance = modelScore, score = modelScore, bm25Rank = null, isTest = Packer.isTest(path)))
        }
    }

    private fun currentPrompt(onReady: (String, Int) -> Unit) {
        cancelPreview()
        promptJob?.cancel()
        answerJob?.cancel()
        answerSerial++
        val intent = ++promptSerial
        val paths = (0 until picks.size()).map { picks[it].path }
        val taskText = task.text.trim()
        val packAtStart = requestSerial
        val inputAtStart = inputSerial
        answer.text = ""
        if (paths.isEmpty() || taskText.isEmpty()) {
            status.text = "Describe a task and select at least one file first"
            cancelButton.isEnabled = job?.isActive == true
            return
        }
        cancelButton.isEnabled = true
        promptJob = service.scope.launch {
            try {
                val texts = service.texts(paths)
                val available = paths.mapNotNull { p -> texts[p]?.let { p to it } }
                check(available.isNotEmpty()) { "None of the selected files could be read." }
                val missing = paths.filter { it !in texts }
                val prompt = Prompt.build(taskText, available) + if (missing.isEmpty()) "" else
                    "\nSelected files unavailable; read these separately: " + missing.take(Prompt.MAX_FILES)
                        .joinToString { it.take(512).replace('\n', ' ').replace('\r', ' ') } + "\n"
                withContext(Dispatchers.EDT) {
                    if (!disposed && intent == promptSerial && packAtStart == requestSerial && inputAtStart == inputSerial) {
                        onReady(prompt, minOf(available.size, Prompt.MAX_FILES))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    if (!disposed && intent == promptSerial && packAtStart == requestSerial && inputAtStart == inputSerial) {
                        status.text = "Could not build prompt: ${e.message}"
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (!disposed && intent == promptSerial) {
                        promptJob = null
                        cancelButton.isEnabled = job?.isActive == true || answerJob?.isActive == true
                    }
                }
            }
        }
    }

    private fun copyPrompt() = currentPrompt { prompt, fileCount ->
        val handoff = Prompt.SYSTEM + "\n\n" + prompt
        CopyPasteManager.getInstance().setContents(StringSelection(handoff))
        status.text = "Copied a %,d-character prompt with %d files".format(handoff.length, fileCount)
    }

    private fun askLlm() = currentPrompt { prompt, _ ->
        answerJob?.cancel()
        val serial = ++answerSerial
        val packSerial = requestSerial
        answer.text = "Asking ${ChatClient.DEFAULT_MODEL}…"
        cancelButton.isEnabled = true
        answerJob = service.scope.launch {
            try {
                val key = Keys.OPENROUTER.get() ?: throw MissingKeyException(Keys.OPENROUTER)
                val started = System.nanoTime()
                val reply = ChatClient(key).complete(Prompt.SYSTEM, prompt)
                val secs = (System.nanoTime() - started) / 1e9
                val text = reply.content + "\n\n— %s · %.1f s · %s in / %s out tokens%s".format(
                    ChatClient.DEFAULT_MODEL, secs,
                    reply.promptTokens?.let { "%,d".format(it) } ?: "unknown",
                    reply.completionTokens?.let { "%,d".format(it) } ?: "unknown",
                    reply.cost?.let { " · $%.4f".format(it) } ?: "",
                ) + if (reply.truncated) "\nThe response reached the model's output limit and may be incomplete." else ""
                withContext(Dispatchers.EDT) {
                    if (!disposed && serial == answerSerial && packSerial == requestSerial) {
                        answer.text = text; answer.caretPosition = 0
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (!disposed && serial == answerSerial && packSerial == requestSerial) answer.text = "Cancelled"
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    if (!disposed && serial == answerSerial && packSerial == requestSerial) answer.text = "Failed: ${e.message}"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (!disposed && serial == answerSerial && packSerial == requestSerial) cancelButton.isEnabled = job?.isActive == true
                }
            }
        }
    }

    private inner class PickRenderer : ColoredListCellRenderer<PackedFile>() {
        override fun customizeCellRenderer(list: JList<out PackedFile>, value: PackedFile, index: Int, selected: Boolean, focus: Boolean) {
            val keywords = (lastReport?.provider ?: provider.selectedItem) == DecisionProvider.KEYWORDS
            if (keywords) append(value.bm25Rank?.let { "#$it  " } ?: "pinned  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            else append("%.2f  ".format(value.score), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val role = if (keywords) null else value.role
            val tag = role ?: if (value.isTest) "test" else null
            if (tag != null) append("%-5s ".format(tag), if (role == "edit") SimpleTextAttributes.LINK_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append(value.path.substringAfterLast('/'), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  " + value.path.substringBeforeLast('/', ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            toolTipText = if (keywords) value.path + (value.bm25Rank?.let { " · full-corpus BM25 keyword rank #$it" } ?: " · added by hand")
            else value.path + (value.bm25Rank?.let { " · model relevance %.2f · keyword rank $it".format(value.relevance) } ?: " · added by hand") +
                (role?.let { label -> value.roleConfidence?.let { " · role $label %.2f".format(it) } } ?: "")
        }
    }
}
