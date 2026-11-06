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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
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

/** One provider page: configuration, or its models. Tabs only exist for a saved provider. */
@Composable
internal fun ProviderEditor(
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
                glyph = EvGlyphKind.Info,
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
