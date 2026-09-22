package dev.contextpacker.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
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
import dev.contextpacker.Prompt
import dev.contextpacker.llm.ChatClient
import dev.contextpacker.pack.PackedFile
import dev.contextpacker.pack.Packer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** Type a task, pack, check the picks, then copy them as a prompt or ask an LLM directly. */
class PackerPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val service = project.service<ContextPackerService>()

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
        addActionListener { job?.cancel(); answerJob?.cancel() }
    }
    private val provider = JComboBox(DecisionProvider.entries.toTypedArray()).apply {
        selectedItem = service.provider
        toolTipText = "Laya runs locally at 127.0.0.1:8770 and needs no API key. Jev uses your configured API."
        addActionListener { service.provider = selectedItem as DecisionProvider }
    }
    private val status = JBLabel(" ").apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }

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
        emptyText.text = "Ask LLM sends the task plus the picked files in one prompt"
    }

    private var lastReport: PackReport? = null
    private var job: Job? = null
    private var answerJob: Job? = null
    private var requestSerial = 0
    private var answerSerial = 0

    init {
        border = JBUI.Borders.empty(6)
        val top = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(task), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(provider); add(packButton); add(cancelButton)
                }, BorderLayout.WEST)
                add(status, BorderLayout.CENTER)
                status.border = JBUI.Borders.emptyLeft(8)
            }, BorderLayout.SOUTH)
        }
        val actions = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(button("Add open file", AllIcons.General.Add) { addOpenFile() })
            add(button("Drop", AllIcons.General.Remove) { dropSelected() })
            add(button("Copy prompt", AllIcons.Actions.Copy) { copyPrompt() })
            add(button("Ask LLM", AllIcons.Actions.Execute) { askLlm() })
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

        // An agent's pack shows up here too, so the human can see exactly what context it was handed,
        // including one made before this window was first opened.
        service.lastReport?.takeIf { it.source != "tool window" }?.let { report ->
            task.text = report.result.task
            show(report)
        }
        service.onPack { report ->
            if (report.source != "tool window") service.scope.launch(Dispatchers.EDT) {
                task.text = report.result.task
                picks.clear()
                show(report)
            }
        }
    }

    private fun button(text: String, icon: javax.swing.Icon, action: () -> Unit) =
        JButton(text, icon).apply { addActionListener { action() } }

    private fun runPack() {
        val text = task.text.trim().ifEmpty { return }
        job?.cancel()
        answerJob?.cancel()
        val serial = ++requestSerial
        packButton.isEnabled = false
        provider.isEnabled = false
        cancelButton.isEnabled = true
        status.toolTipText = null
        lastReport = null
        answer.text = ""
        picks.clear()
        job = service.scope.launch {
            try {
                val report = service.pack(text) { msg -> launch(Dispatchers.EDT) {
                    if (serial == requestSerial) status.text = "$msg…"
                } }
                withContext(Dispatchers.EDT) { if (serial == requestSerial) show(report) }
            } catch (e: MissingKeyException) {
                withContext(Dispatchers.EDT) { if (serial == requestSerial) status.text = e.message }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (serial == requestSerial) status.text = "Cancelled"
                }
                throw e
            } catch (e: Exception) {
                // Status line gets the readable part; the tooltip keeps the server's own words.
                withContext(Dispatchers.EDT) {
                    if (serial == requestSerial) {
                        status.text = "Failed: " + (e.message ?: e::class.simpleName.orEmpty()).substringBefore(" (HTTP")
                        status.toolTipText = e.message
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (serial == requestSerial) {
                        packButton.isEnabled = true
                        provider.isEnabled = true
                        cancelButton.isEnabled = answerJob?.isActive == true
                    }
                }
            }
        }
    }

    private fun show(report: PackReport) {
        lastReport = report
        report.result.files.forEach(picks::addElement)
        val r = report.result
        val failed = if (r.failedBatches > 0) " · ${r.failedBatches} incomplete batches" else ""
        val cost = if (report.provider == DecisionProvider.LAYA) "local · API fee $0"
            else report.costUsd?.let { "est. API $%.4f".format(it) } ?: "API fee unavailable"
        val tokens = if (report.usageKnown) "%.1fk tokens".format(report.inputTokens / 1000.0) else "tokens unavailable"
        val who = if (report.source == "tool window") "" else "Asked by ${report.source} · "
        status.text = "%s%d of %,d files in %.1f s · %d %s calls · %s · %s%s".format(
            who, r.files.size, r.candidates, report.totalMs / 1000.0, report.jevCalls,
            report.provider.name, tokens, cost, failed,
        )
        status.toolTipText = "sketch %d ms · pass 1 + BM25 %d ms · pass 2 %d ms · %s · scored %d candidates%s".format(
            report.sketchMs, r.pass1Ms, r.pass2Ms, report.jevModel, report.scoredCandidates,
            if (report.provider == DecisionProvider.LAYA) " after keyword prefilter; local compute cost excluded" else "",
        )
    }

    private fun open(file: PackedFile) {
        service.fileFor(file.path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun dropSelected() {
        list.selectedValuesList.forEach(picks::removeElement)
    }

    /** Pin a file Jev missed. */
    private fun addOpenFile() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val path = project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) } ?: return
        if ((0 until picks.size()).none { picks[it].path == path }) {
            picks.add(0, PackedFile(path, relevance = 1.0, score = 1.0, bm25Rank = null, isTest = Packer.isTest(path)))
        }
    }

    private fun currentPrompt(onReady: (String, Int) -> Unit) {
        val paths = (0 until picks.size()).map { picks[it].path }
        val taskText = task.text.trim()
        val serial = requestSerial
        if (paths.isEmpty() || taskText.isEmpty()) return
        service.scope.launch {
            try {
                val texts = service.texts(paths)
                val available = paths.mapNotNull { p -> texts[p]?.let { p to it } }
                check(available.isNotEmpty()) { "None of the selected files could be read." }
                val missing = paths.filter { it !in texts }
                val prompt = Prompt.build(taskText, available) + if (missing.isEmpty()) "" else
                    "\nSelected files unavailable; read these separately: " + missing.take(Prompt.MAX_FILES)
                        .joinToString { it.take(512).replace('\n', ' ').replace('\r', ' ') } + "\n"
                withContext(Dispatchers.EDT) {
                    if (serial == requestSerial) onReady(prompt, minOf(available.size, Prompt.MAX_FILES))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) { if (serial == requestSerial) status.text = "Could not build prompt: ${e.message}" }
            }
        }
    }

    private fun copyPrompt() = currentPrompt { prompt, fileCount ->
        CopyPasteManager.getInstance().setContents(StringSelection(prompt))
        status.text = "Copied a %,d-character prompt with %d files".format(prompt.length, fileCount)
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
                val text = reply.content + "\n\n— %s · %.1f s · %,d in / %,d out tokens%s".format(
                    ChatClient.DEFAULT_MODEL, secs, reply.promptTokens, reply.completionTokens,
                    reply.cost?.let { " · $%.4f".format(it) } ?: "",
                )
                withContext(Dispatchers.EDT) {
                    if (serial == answerSerial && packSerial == requestSerial) {
                        answer.text = text; answer.caretPosition = 0
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (serial == answerSerial && packSerial == requestSerial) answer.text = "Cancelled"
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    if (serial == answerSerial && packSerial == requestSerial) answer.text = "Failed: ${e.message}"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (serial == answerSerial && packSerial == requestSerial) cancelButton.isEnabled = job?.isActive == true
                }
            }
        }
    }

    private class PickRenderer : ColoredListCellRenderer<PackedFile>() {
        override fun customizeCellRenderer(list: JList<out PackedFile>, value: PackedFile, index: Int, selected: Boolean, focus: Boolean) {
            append("%.2f  ".format(value.score), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.isTest) append("test  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append(value.path.substringAfterLast('/'), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  " + value.path.substringBeforeLast('/', ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            toolTipText = value.path + (value.bm25Rank?.let { " · relevance %.2f · keyword rank $it".format(value.relevance) } ?: " · added by hand")
        }
    }
}
