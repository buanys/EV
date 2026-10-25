package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.StatusError
import ev.buan.ui.StatusSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Model providers: the list of OpenAI-compatible endpoints and one provider's configuration.
 *
 * The page is a client of [ModelProviderService] and owns no model state of its own: every read goes
 * through [readProviders] (a fresh `providers()` call per refresh) and every write through the
 * service, so what is on screen is always what agent-model stored. Provider and model edits are
 * local drafts until "保存"; the service normalizes them (trim, trailing slash, built-in flag) on
 * write, and the page reloads afterwards so it shows the stored shape.
 *
 * The contract deliberately has no system prompt, and neither has this page: characters and skills
 * carry instructions per turn, not provider configuration.
 */
@Composable
internal fun EvModelProvidersScreen(
    service: () -> ModelProviderService?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var refreshToken by remember { mutableStateOf(0) }
    val state = remember(refreshToken) { readProviders(service) }
    var search by remember { mutableStateOf("") }
    var detailId by remember { mutableStateOf<String?>(null) }
    var newDraft by remember { mutableStateOf<ModelProvider?>(null) }

    fun reload() {
        refreshToken++
    }

    val providers = (state as? ProvidersState.Loaded)?.providers.orEmpty()

    val creating = newDraft
    if (creating != null) {
        ProviderEditor(
            provider = creating,
            stored = null,
            providers = providers,
            service = service,
            onBack = { newDraft = null },
            onChanged = {},
            onCreated = { saved ->
                newDraft = null
                detailId = saved.id
                reload()
            },
            onDeleted = { newDraft = null },
        )
        return
    }

    val stored = providers.firstOrNull { it.id == detailId }
    if (stored != null) {
        ProviderEditor(
            provider = stored,
            stored = stored,
            providers = providers,
            service = service,
            onBack = {
                detailId = null
                reload()
            },
            onChanged = { reload() },
            onCreated = {},
            onDeleted = {
                detailId = null
                reload()
            },
        )
        return
    }

    EvPage(
        title = "模型服务",
        modifier = modifier,
        onBack = onBack,
        actions = {
            Text(
                text = "刷新",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "刷新模型服务") { reload() }
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        item {
            LibraryHint(
                title = null,
                detail = "这里只配置模型服务本身：地址、Key 与模型。角色与 Skills 在各自的页面里。",
            )
        }
        when (state) {
            ProvidersState.Missing -> item {
                LibraryHint(
                    title = "agent-model 未启用",
                    detail = "没有插件发布模型服务。在「插件中心」启用 agent-model 后回来刷新。",
                )
            }

            is ProvidersState.Failed -> item {
                LibraryHint(title = "读取模型服务失败", detail = state.message)
            }

            is ProvidersState.Loaded -> {
                item { ProviderSearchField(search, { search = it }, "搜索 Provider（名称 / 地址 / id）") }
                val visible = filterProviders(providers, search)
                item { EvSettingsSection("${visible.size} / ${providers.size} 个 Provider") }
                if (visible.isEmpty()) {
                    item { LibraryHint(title = "没有匹配的 Provider", detail = "换个关键字，或新增一个自定义 Provider。") }
                } else {
                    val current = currentModel(providers)
                    item {
                        EvSettingsGroup {
                            visible.forEachIndexed { index, provider ->
                                if (index > 0) EvSettingsDivider()
                                EvSettingsRow(
                                    title = provider.name,
                                    glyph = EvGlyphKind.Atom,
                                    summary = providerSummary(provider, current),
                                    onClick = { detailId = provider.id },
                                )
                            }
                        }
                    }
                }
                item {
                    EvSettingsGroup {
                        EvSettingsRow(
                            title = "新增自定义 Provider",
                            glyph = EvGlyphKind.Plus,
                            summary = "OpenAI 兼容接口；内置 Provider 直接在列表里配置",
                            onClick = { newDraft = newCustomProvider() },
                        )
                    }
                }
            }
        }
    }
}

/** One provider page: configuration, or its models. Tabs only exist for a saved provider. */
@Composable
private fun ProviderEditor(
    provider: ModelProvider,
    stored: ModelProvider?,
    providers: List<ModelProvider>,
    service: () -> ModelProviderService?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onCreated: (ModelProvider) -> Unit,
    onDeleted: () -> Unit,
) {
    var tab by remember(provider.id) { mutableStateOf(ProviderTab.CONFIG) }
    val isNew = stored == null

    EvPage(
        title = provider.name.ifBlank { "新增 Provider" },
        onBack = onBack,
    ) {
        if (!isNew) {
            item { ProviderTabRow(selected = tab, onSelect = { tab = it }) }
        }
        item {
            if (!isNew && tab == ProviderTab.MODELS) {
                ProviderModelsTab(
                    provider = provider,
                    providers = providers,
                    service = service,
                    onChanged = onChanged,
                )
            } else {
                ProviderConfigTab(
                    provider = provider,
                    stored = stored,
                    service = service,
                    onChanged = onChanged,
                    onCreated = onCreated,
                    onDeleted = onDeleted,
                )
            }
        }
    }
}

private enum class ProviderTab(val label: String) {
    CONFIG("配置"),
    MODELS("模型"),
}

@Composable
private fun ProviderTabRow(selected: ProviderTab, onSelect: (ProviderTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ProviderTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f) else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.04f),
                    )
                    .clickable(onClickLabel = "打开${tab.label}") { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    style = MiuixTheme.textStyles.body1,
                    color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ModelProvider,
    stored: ModelProvider?,
    service: () -> ModelProviderService?,
    onChanged: () -> Unit,
    onCreated: (ModelProvider) -> Unit,
    onDeleted: () -> Unit,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var enabled by remember(provider.id) { mutableStateOf(provider.enabled) }
    var headers by remember(provider.id) { mutableStateOf(headerRows(provider.headers)) }
    var keyVisible by remember(provider.id) { mutableStateOf(false) }
    var message by remember(provider.id) { mutableStateOf<String?>(null) }
    var messageIsError by remember(provider.id) { mutableStateOf(false) }
    var testResult by remember(provider.id) { mutableStateOf<String?>(null) }
    var testOk by remember(provider.id) { mutableStateOf(false) }
    var testing by remember(provider.id) { mutableStateOf(false) }
    var confirmingRemove by remember(provider.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val draft = provider.copy(
        name = name.trim(),
        // Trim the same way the model plugin normalizes on write, so "saved" and "unchanged" agree
        // and the test-connection guard below does not trip over a trailing slash.
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        enabled = enabled,
        headers = headerMap(headers),
    )
    val error = providerDraftError(draft)

    fun report(text: String, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun save() {
        val problem = providerDraftError(draft)
        if (problem != null) {
            report(problem, isError = true)
            return
        }
        val target = try {
            service()
        } catch (failure: Exception) {
            report("无法访问模型服务：${providerError(failure)}", isError = true)
            return
        } ?: run {
            report("agent-model 未启用，无法保存", isError = true)
            return
        }
        try {
            target.saveProvider(draft)
        } catch (failure: Exception) {
            report("保存失败：${providerError(failure)}", isError = true)
            return
        }
        report("已保存", isError = false)
        testResult = null
        if (stored == null) onCreated(draft) else onChanged()
    }

    fun test() {
        val saved = stored ?: run {
            report("先保存配置，再测试连接", isError = true)
            return
        }
        if (draft != saved) {
            report("配置已修改：先保存，再测试连接", isError = true)
            return
        }
        val target = try {
            service()
        } catch (failure: Exception) {
            report("无法访问模型服务：${providerError(failure)}", isError = true)
            return
        } ?: run {
            report("agent-model 未启用，无法测试", isError = true)
            return
        }
        message = null
        testResult = null
        testing = true
        scope.launch {
            try {
                // testConnection reports the outcome instead of throwing; only cancellation escapes.
                val result = target.testConnection(saved.id)
                testResult = result.message
                testOk = result.success
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                report("测试失败：${providerError(failure)}", isError = true)
            } finally {
                testing = false
            }
        }
    }

    fun remove() {
        val saved = stored ?: return
        val target = try {
            service()
        } catch (failure: Exception) {
            report("无法访问模型服务：${providerError(failure)}", isError = true)
            return
        } ?: run {
            report("agent-model 未启用，无法修改", isError = true)
            return
        }
        try {
            if (saved.builtIn) target.resetProvider(saved.id) else target.deleteProvider(saved.id)
        } catch (failure: Exception) {
            report("操作失败：${providerError(failure)}", isError = true)
            return
        }
        onDeleted()
    }

    Column(Modifier.fillMaxWidth()) {
        LibraryFieldLabel("名称")
        LibraryTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "例如：DeepSeek",
            singleLine = true,
        )

        LibraryFieldLabel("Base URL")
        LibraryTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            placeholder = "https://api.example.com/v1",
            singleLine = true,
        )

        LibraryFieldLabel("API Key")
        LibraryTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            placeholder = "sk-…（留空表示不需要 Key）",
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        )
        Text(
            text = if (keyVisible) "隐藏 Key" else "显示 Key",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClickLabel = if (keyVisible) "隐藏 API Key" else "显示 API Key") { keyVisible = !keyVisible }
                .padding(start = 28.dp, top = 8.dp),
        )

        Spacer(Modifier.height(10.dp))
        EvSettingsRow(
            title = "Endpoint 模式",
            glyph = EvGlyphKind.Send,
            summary = "chat completions（OpenAI 兼容）",
            trailing = EvSettingsTrailing.None,
        )
        EvSettingsRow(
            title = "启用",
            glyph = EvGlyphKind.Atom,
            summary = if (enabled) "参与模型排序" else "不参与模型排序",
            trailing = EvSettingsTrailing.Switch,
            checked = enabled,
            onCheckedChange = { enabled = it },
        )
        if (provider.builtIn && apiKey.isBlank()) {
            LibraryHint(
                title = null,
                detail = "没有 API Key 的内置 Provider 会被模型插件自动禁用。",
            )
        }

        LibraryFieldLabel("自定义 Headers")
        if (headers.isEmpty()) {
            Text(
                text = "暂无自定义 Header",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 28.dp, bottom = 6.dp),
            )
        }
        headers.forEachIndexed { index, row ->
            LibraryTextField(
                value = row.key,
                onValueChange = { value -> headers = headers.toMutableList().also { it[index] = row.copy(key = value) } },
                placeholder = "Header 名，例如 X-Org",
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            LibraryTextField(
                value = row.value,
                onValueChange = { value -> headers = headers.toMutableList().also { it[index] = row.copy(value = value) } },
                placeholder = "Header 值",
                singleLine = true,
            )
            Text(
                text = "删除这一行",
                style = MiuixTheme.textStyles.footnote1,
                color = StatusError,
                modifier = Modifier
                    .clickable(onClickLabel = "删除这个 Header") {
                        headers = headers.toMutableList().also { it.removeAt(index) }
                    }
                    .padding(start = 28.dp, top = 6.dp, bottom = 2.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = "添加 Header",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClickLabel = "添加一个 Header") { headers = headers + HeaderRow("", "") }
                .padding(start = 28.dp, top = 6.dp, bottom = 10.dp),
        )

        if (error != null) {
            LibraryHint(title = null, detail = error, tint = StatusError)
        }
        if (message != null) {
            LibraryHint(
                title = null,
                detail = message!!,
                tint = if (messageIsError) StatusError else StatusSuccess,
            )
        }
        if (testResult != null) {
            LibraryHint(
                title = null,
                detail = testResult!!,
                tint = if (testOk) StatusSuccess else StatusError,
            )
        }

        EvSettingsGroup {
            EvSettingsRow(
                title = "保存配置",
                glyph = EvGlyphKind.Send,
                summary = if (error == null) "写入 providers.json" else error,
                onClick = { save() },
            )
            EvSettingsDivider()
            EvSettingsRow(
                title = if (testing) "测试中…" else "测试连接",
                glyph = EvGlyphKind.Globe,
                summary = "GET {Base URL}/models，验证地址与 Key",
                onClick = if (testing) null else ({ test() }),
            )
        }

        if (stored != null) {
            if (confirmingRemove) {
                LibraryDeleteConfirm(
                    title = if (stored.builtIn) "重置「${stored.name}」？" else "删除「${stored.name}」？",
                    detail = if (stored.builtIn) {
                        "恢复内置默认值（地址、名称与默认模型），已填写的 Key 会清空。"
                    } else {
                        "这个自定义 Provider 与它的模型配置会一起删除。"
                    },
                    onConfirm = { remove() },
                    onCancel = { confirmingRemove = false },
                )
            } else {
                LibraryDeleteRow(
                    title = if (stored.builtIn) "重置为内置默认值" else "删除这个 Provider",
                    summary = if (stored.builtIn) "内置 Provider 只能重置，不能删除" else "只删除这个 Provider，不影响其它 Provider",
                    onRequest = { confirmingRemove = true },
                )
            }
        }
    }
}

@Composable
private fun ProviderModelsTab(
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
                glyph = EvGlyphKind.Globe,
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

@Composable
private fun ProviderSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onBackground, fontSize = 14.sp),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
