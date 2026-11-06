package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.StatusError
import ev.buan.ui.StatusSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderModelsTab(
    provider: ModelProvider,
    providers: List<ModelProvider>,
    service: () -> ModelProviderService?,
    onChanged: () -> Unit,
) {
    var search by remember(provider.id) { mutableStateOf("") }
    var expanded by remember(provider.id) { mutableStateOf<String?>(null) }
    var adding by remember(provider.id) { mutableStateOf(false) }
    var newName by remember(provider.id) { mutableStateOf("") }
    var newId by remember(provider.id) { mutableStateOf("") }
    var confirmingDelete by remember(provider.id) { mutableStateOf<String?>(null) }
    var message by remember(provider.id) { mutableStateOf<String?>(null) }
    var messageIsError by remember(provider.id) { mutableStateOf(false) }
    var fetching by remember(provider.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = currentModel(providers)

    fun report(text: String, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    /** Saves [updated] through the service; reports instead of throwing. */
    fun save(updated: ModelProvider, okMessage: String? = null): Boolean {
        val target = try {
            service()
        } catch (failure: Exception) {
            report("无法访问模型服务：${providerError(failure)}", isError = true)
            return false
        } ?: run {
            report("agent-model 未启用，无法保存", isError = true)
            return false
        }
        try {
            target.saveProvider(updated)
        } catch (failure: Exception) {
            report("保存失败：${providerError(failure)}", isError = true)
            return false
        }
        okMessage?.let { report(it, isError = false) }
        onChanged()
        return true
    }

    fun fetchRemote() {
        val target = try {
            service()
        } catch (failure: Exception) {
            report("无法访问模型服务：${providerError(failure)}", isError = true)
            return
        } ?: run {
            report("agent-model 未启用，无法拉取", isError = true)
            return
        }
        fetching = true
        message = null
        scope.launch {
            try {
                val fetched = target.fetchRemoteModels(provider.id)
                save(mergeFetchedModels(provider, fetched), "拉取到 ${fetched.size} 个模型，已合并")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                report("拉取失败：${providerError(failure)}", isError = true)
            } finally {
                fetching = false
            }
        }
    }

    fun selectModel(model: ModelDescriptor) {
        // The contract resolves the chat's model as the first selected one, so the page clears the
        // other providers' picks in the same gesture.
        val target = try {
            service()
        } catch (failure: Exception) {
            report("无法访问模型服务：${providerError(failure)}", isError = true)
            return
        } ?: run {
            report("agent-model 未启用，无法保存", isError = true)
            return
        }
        val changes = selectCurrentModel(providers, provider.id, model.id)
        try {
            changes.forEach { target.saveProvider(it) }
        } catch (failure: Exception) {
            report("保存失败：${providerError(failure)}", isError = true)
            return
        }
        report("已设为当前模型：${model.displayName}", isError = false)
        onChanged()
    }

    Column(Modifier.fillMaxWidth()) {
        EvSettingsGroup {
            EvSettingsRow(
                title = if (fetching) "拉取中…" else "拉取远端模型",
                glyph = EvGlyphKind.Info,
                summary = "GET {Base URL}/models；自定义模型会保留",
                onClick = if (fetching) null else ({ fetchRemote() }),
            )
            EvSettingsDivider()
            EvSettingsRow(
                title = if (adding) "收起新增表单" else "添加自定义模型",
                glyph = EvGlyphKind.Plus,
                summary = "手动填写展示名与 Model ID",
                onClick = { adding = !adding },
            )
        }

        if (adding) {
            LibraryFieldLabel("展示名")
            LibraryTextField(newName, { newName = it }, "例如：DeepSeek Chat", singleLine = true)
            LibraryFieldLabel("Model ID")
            LibraryTextField(newId, { newId = it }, "例如：deepseek-chat", singleLine = true)
            Text(
                text = "保存这个模型",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "保存自定义模型") {
                        val problem = modelDraftError(newName, newId)
                        if (problem != null) {
                            report(problem, isError = true)
                        } else {
                            val model = ModelDescriptor(
                                id = newId.trim(),
                                displayName = newName.trim(),
                                custom = true,
                            )
                            if (save(provider.copy(models = upsertModel(provider.models, model)))) {
                                adding = false
                                newName = ""
                                newId = ""
                                report("已添加 ${model.displayName}", isError = false)
                            }
                        }
                    }
                    .padding(start = 28.dp, top = 10.dp, bottom = 6.dp),
            )
        }

        ProviderSearchField(search, { search = it }, "搜索模型（展示名 / id）")

        val visible = filterModels(provider.models, search)
        Text(
            text = "${visible.size} / ${provider.models.size} 个模型",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp),
        )
        if (visible.isEmpty()) {
            LibraryHint(
                title = if (provider.models.isEmpty()) "还没有模型" else "没有匹配的模型",
                detail = if (provider.models.isEmpty()) {
                    "先拉取远端模型，或手动添加一个。"
                } else {
                    "换个关键字试试。"
                },
            )
        } else {
            EvSettingsGroup {
                visible.forEachIndexed { index, model ->
                    if (index > 0) EvSettingsDivider()
                    Column(Modifier.fillMaxWidth()) {
                        EvSettingsRow(
                            title = model.displayName,
                            glyph = EvGlyphKind.Sparkle,
                            summary = modelSummary(model, provider, current),
                            trailing = EvSettingsTrailing.Switch,
                            checked = provider.selectedModelId == model.id,
                            onCheckedChange = { checked -> if (checked) selectModel(model) },
                            onClick = { expanded = if (expanded == model.id) null else model.id },
                        )
                        if (expanded == model.id) {
                            ModelParametersEditor(
                                model = model,
                                onApply = { parameters ->
                                    if (save(provider.copy(models = upsertModel(provider.models, model.copy(parameters = parameters))))) {
                                        expanded = null
                                    }
                                },
                            )
                            if (model.custom) {
                                if (confirmingDelete == model.id) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "删除后无法恢复",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = "取消",
                                            style = MiuixTheme.textStyles.body1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier
                                                .clickable(onClickLabel = "取消删除模型") { confirmingDelete = null }
                                                .padding(horizontal = 6.dp),
                                        )
                                        Text(
                                            text = "确认删除",
                                            style = MiuixTheme.textStyles.body1,
                                            color = StatusError,
                                            modifier = Modifier
                                                .clickable(onClickLabel = "确认删除模型") {
                                                    if (save(removeModel(provider, model.id), "已删除 ${model.displayName}")) {
                                                        confirmingDelete = null
                                                        expanded = null
                                                    }
                                                }
                                                .padding(horizontal = 6.dp),
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "删除这个自定义模型",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = StatusError,
                                        modifier = Modifier
                                            .clickable(onClickLabel = "删除自定义模型") { confirmingDelete = model.id }
                                            .padding(start = 58.dp, bottom = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (message != null) {
            LibraryHint(
                title = null,
                detail = message!!,
                tint = if (messageIsError) StatusError else StatusSuccess,
            )
        }
    }
}

/** The three per-model call parameters; blank means "let the server decide". */
@Composable
private fun ModelParametersEditor(model: ModelDescriptor, onApply: (ModelParameters) -> Unit) {
    var temperature by remember(model) { mutableStateOf(model.parameters.temperature?.toString().orEmpty()) }
    var topP by remember(model) { mutableStateOf(model.parameters.topP?.toString().orEmpty()) }
    var maxTokens by remember(model) { mutableStateOf(model.parameters.maxTokens?.toString().orEmpty()) }
    var error by remember(model) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 12.dp)) {
        Text(
            text = "模型参数 · ${parametersSummary(model.parameters)}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        LibraryTextField(temperature, { temperature = it }, "temperature（0–2，留空用默认值）", singleLine = true)
        Spacer(Modifier.height(6.dp))
        LibraryTextField(topP, { topP = it }, "topP（0–1，留空用默认值）", singleLine = true)
        Spacer(Modifier.height(6.dp))
        LibraryTextField(maxTokens, { maxTokens = it }, "maxTokens（正整数，留空用默认值）", singleLine = true)
        if (error != null) {
            Text(
                text = error!!,
                style = MiuixTheme.textStyles.footnote1,
                color = StatusError,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "应用参数",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "应用模型参数") {
                        when (val parsed = parseModelParameters(temperature, topP, maxTokens)) {
                            is ParametersParse.Valid -> {
                                error = null
                                onApply(parsed.parameters)
                            }

                            is ParametersParse.Invalid -> error = parsed.message
                        }
                    }
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "清空",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clickable(onClickLabel = "清空模型参数") {
                        temperature = ""
                        topP = ""
                        maxTokens = ""
                        error = null
                        onApply(ModelParameters())
                    }
                    .padding(vertical = 4.dp),
            )
        }
    }
}
