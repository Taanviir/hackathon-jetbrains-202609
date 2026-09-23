package dev.contextpacker.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.contextpacker.DecisionProvider
import dev.contextpacker.JevProfile
import dev.contextpacker.LayaProfile
import dev.contextpacker.PackerSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.SpinnerNumberModel

/** Edit independent provider profiles; only OK writes the project settings. API keys remain in PasswordSafe. */
internal class ProviderSettingsDialog(
    project: Project,
    private val settings: PackerSettings,
    private val selectedProvider: DecisionProvider,
) : DialogWrapper(project) {
    private val draft = settings.editablePreferences().apply { provider = selectedProvider.name.lowercase() }

    private val backend = JComboBox(arrayOf("Inherit launcher", "auto", "typesafe", "gateway"))
    private val batch = number(draft.jev.batch, 1, 100)
    private val jevPool = number(draft.jev.pool, 1, 200)
    private val perCall = number(draft.jev.perCall, 1, 20)
    private val jevFullChars = number(draft.jev.fullChars, 1, 32_000)
    private val stage3K = number(draft.jev.stage3K, 1, 20)
    private val compareTop = JCheckBox().apply { isSelected = draft.jev.compareTop }
    private val assignRoles = JCheckBox().apply { isSelected = draft.jev.assignRoles }
    private val jevTimeout = number(draft.jev.timeoutSeconds, 1, 300)

    private val endpoint = JBTextField(draft.laya.endpoint, 36).apply {
        toolTipText = "Blank inherits CONTEXT_PACKER_LAYA_URL, otherwise http://127.0.0.1:8770/api/predict."
    }
    private val layaModel = JBTextField(draft.laya.model, 20).apply {
        toolTipText = "Blank inherits CONTEXT_PACKER_LAYA_MODEL, otherwise english."
    }
    private val maxCandidates = number(draft.laya.maxCandidates, 1, 500)
    private val layaPool = number(draft.laya.pool, 1, 500)
    private val layaFullChars = number(draft.laya.fullChars, 1, 1_000)
    private val layaTimeout = number(draft.laya.timeoutSeconds, 1, 600)
    private val error = JLabel(" ").apply { foreground = JBColor.RED }

    init {
        title = "Context Packer provider settings"
        setJev(draft.jev)
        setLaya(draft.laya)
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        add(JTabbedPane().apply {
            addTab("Jev (API)", profilePanel(
                "Cloud scoring across all eligible source. Tasks up to 8,000 characters. API keys: Tools → Context Packer: Set API Keys…",
                listOf(
                    "Backend" to backend, "Sketches per call" to batch, "Rerank pool" to jevPool,
                    "Full-source files per call" to perCall, "Full-source chars per file" to jevFullChars,
                    "Stage 3 comparison files" to stage3K, "Compare top files" to compareTop,
                    "Assign file roles" to assignRoles, "Request timeout (seconds)" to jevTimeout,
                ),
            ) { setJev(JevProfile()) })
            addTab("Laya (local)", profilePanel(
                "Current English local adapter: tasks up to 500 characters; excerpt cap up to 1,000 characters including path/sketch text. A tiny cap may leave no source text. No cloud fallback.",
                listOf(
                    "Loopback endpoint" to endpoint, "Model" to layaModel,
                    "Keyword shortlist" to maxCandidates, "Rerank pool" to layaPool,
                    "Excerpt cap incl path/sketch" to layaFullChars, "Request timeout (seconds)" to layaTimeout,
                ),
            ) { setLaya(LayaProfile()) })
            selectedIndex = if (selectedProvider == DecisionProvider.LAYA) 1 else 0
        }, BorderLayout.CENTER)
        add(error, BorderLayout.SOUTH)
    }

    private fun readPreferences(): PackerSettings.Preferences {
        listOf(batch, jevPool, perCall, jevFullChars, stage3K, jevTimeout,
            maxCandidates, layaPool, layaFullChars, layaTimeout).forEach(JSpinner::commitEdit)
        return draft.copy(
            jev = draft.jev.copy(
                backend = (backend.selectedItem as String).takeUnless { it == "Inherit launcher" } ?: "",
                batch = batch.intValue(), pool = jevPool.intValue(), perCall = perCall.intValue(),
                fullChars = jevFullChars.intValue(), stage3K = stage3K.intValue(),
                compareTop = compareTop.isSelected, assignRoles = assignRoles.isSelected,
                timeoutSeconds = jevTimeout.intValue(),
            ),
            laya = draft.laya.copy(
                endpoint = endpoint.text.trim(), model = layaModel.text.trim(),
                maxCandidates = maxCandidates.intValue(), pool = layaPool.intValue(),
                fullChars = layaFullChars.intValue(), timeoutSeconds = layaTimeout.intValue(),
            ),
        )
    }

    override fun doValidate(): ValidationInfo? = try {
        settings.validatePreferences(readPreferences())
        error.text = " "
        null
    } catch (e: Exception) {
        ValidationInfo(e.message ?: "Check the provider settings and try again.")
    }

    override fun doOKAction() {
        val invalid = doValidate()
        if (invalid != null) {
            error.text = invalid.message
            return
        }
        settings.applyPreferences(readPreferences())
        super.doOKAction()
    }

    private fun setJev(value: JevProfile) {
        backend.selectedItem = value.backend.ifBlank { "Inherit launcher" }
        batch.value = value.batch
        jevPool.value = value.pool
        perCall.value = value.perCall
        jevFullChars.value = value.fullChars
        stage3K.value = value.stage3K
        compareTop.isSelected = value.compareTop
        assignRoles.isSelected = value.assignRoles
        jevTimeout.value = value.timeoutSeconds
        error.text = " "
    }

    private fun setLaya(value: LayaProfile) {
        endpoint.text = value.endpoint
        layaModel.text = value.model
        maxCandidates.value = value.maxCandidates
        layaPool.value = value.pool
        layaFullChars.value = value.fullChars
        layaTimeout.value = value.timeoutSeconds
        error.text = " "
    }

    private fun profilePanel(
        description: String,
        fields: List<Pair<String, JComponent>>,
        restore: () -> Unit,
    ): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        add(JLabel("<html>$description</html>"), BorderLayout.NORTH)
        add(JPanel(GridLayout(0, 2, 10, 5)).apply {
            fields.forEach { (label, control) -> add(JLabel(label)); add(control) }
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(JButton("Restore this provider's defaults").apply { addActionListener { restore() } })
        }, BorderLayout.SOUTH)
    }

    private fun number(value: Int, min: Int, max: Int) = JSpinner(SpinnerNumberModel(value, min, max, 1))
    private fun JSpinner.intValue() = (value as Number).toInt()
}
