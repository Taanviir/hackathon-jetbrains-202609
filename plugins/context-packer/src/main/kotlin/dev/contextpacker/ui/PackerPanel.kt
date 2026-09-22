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
import dev.contextpacker.Keys
import dev.contextpacker.MissingKeyException
import dev.contextpacker.PackReport
import dev.contextpacker.Prompt
import dev.contextpacker.llm.ChatClient
import dev.contextpacker.pack.PackedFile
import dev.contextpacker.pack.Packer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    init {
        border = JBUI.Borders.empty(6)
        val top = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(task), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(packButton, BorderLayout.WEST)
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
        packButton.isEnabled = false
        picks.clear()
        job = service.scope.launch {
            try {
                val report = service.pack(text, "tool window") { msg -> launch(Dispatchers.EDT) { status.text = "$msg…" } }
                withContext(Dispatchers.EDT) { show(report) }
            } catch (e: MissingKeyException) {
                withContext(Dispatchers.EDT) { status.text = e.message }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Status line gets the readable part; the tooltip keeps the server's own words.
                withContext(Dispatchers.EDT) {
                    status.text = "Failed: " + (e.message ?: e::class.simpleName.orEmpty()).substringBefore(" (HTTP")
                    status.toolTipText = e.message
                }
            } finally {
                withContext(Dispatchers.EDT) { packButton.isEnabled = true }
            }
        }
    }

    private fun show(report: PackReport) {
        lastReport = report
        report.result.files.forEach(picks::addElement)
        val r = report.result
        val failed = if (report.failedCalls > 0) " · ${report.failedCalls} calls failed" else ""
        val who = if (report.source == "tool window") "" else "Asked by ${report.source} · "
        status.text = "%s%d of %,d files in %.1f s · %d Jev calls · %.1fk tokens · $%.4f%s".format(
            who, r.files.size, r.candidates, report.totalMs / 1000.0, report.jevCalls,
            report.inputTokens / 1000.0, report.costUsd, failed,
        )
        status.toolTipText = "sketch %d ms · pass 1 + BM25 %d ms · pass 2 %d ms · %s".format(
            report.sketchMs, r.pass1Ms, r.pass2Ms, report.jevModel,
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

    private fun currentPrompt(onReady: (String) -> Unit) {
        val paths = (0 until picks.size()).map { picks[it].path }
        val taskText = task.text.trim()
        if (paths.isEmpty() || taskText.isEmpty()) return
        service.scope.launch {
            val texts = service.texts(paths)
            val prompt = Prompt.build(taskText, paths.mapNotNull { p -> texts[p]?.let { p to it } })
            withContext(Dispatchers.EDT) { onReady(prompt) }
        }
    }

    private fun copyPrompt() = currentPrompt { prompt ->
        CopyPasteManager.getInstance().setContents(StringSelection(prompt))
        status.text = "Copied a %,d-character prompt with %d files".format(prompt.length, picks.size())
    }

    private fun askLlm() = currentPrompt { prompt ->
        answer.text = "Asking ${ChatClient.DEFAULT_MODEL}…"
        service.scope.launch {
            val text = try {
                val key = Keys.OPENROUTER.get() ?: throw MissingKeyException(Keys.OPENROUTER)
                val started = System.nanoTime()
                val reply = ChatClient(key).complete(Prompt.SYSTEM, prompt)
                val secs = (System.nanoTime() - started) / 1e9
                reply.content + "\n\n— %s · %.1f s · %,d in / %,d out tokens%s".format(
                    ChatClient.DEFAULT_MODEL, secs, reply.promptTokens, reply.completionTokens,
                    reply.cost?.let { " · $%.4f".format(it) } ?: "",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            withContext(Dispatchers.EDT) { answer.text = text; answer.caretPosition = 0 }
        }
    }

    private class PickRenderer : ColoredListCellRenderer<PackedFile>() {
        override fun customizeCellRenderer(list: JList<out PackedFile>, value: PackedFile, index: Int, selected: Boolean, focus: Boolean) {
            append("%.2f  ".format(value.score), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.isTest) append("test  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append(value.path.substringAfterLast('/'), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  " + value.path.substringBeforeLast('/', ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            toolTipText = value.path + (value.bm25Rank?.let { " · Jev %.2f · keyword rank $it".format(value.relevance) } ?: " · added by hand")
        }
    }
}
