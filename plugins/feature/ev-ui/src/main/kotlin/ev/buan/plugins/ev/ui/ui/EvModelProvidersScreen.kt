package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.ui.EvEmptyState
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvThemeTokens
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

    // Count only; "读取失败" / "未接入" is the empty state's job (same rule as tools and memory).
    val subtitle = if (state is ProvidersState.Loaded) "${providers.size} 个 Provider" else null

    EvPage(
        title = "模型服务",
        subtitle = subtitle,
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
        when (state) {
            ProvidersState.Missing -> item {
                EvEmptyState(
                    title = "agent-model 未启用",
                    detail = "没有插件发布模型服务。在「插件中心」启用 agent-model 后回来刷新。",
                    glyph = EvGlyphKind.Atom,
                )
            }

            is ProvidersState.Failed -> item {
                LibraryHint(title = "读取模型服务失败", detail = state.message)
            }

            is ProvidersState.Loaded -> {
                item { ProviderSearchField(search, { search = it }, "搜索 Provider（名称 / 地址 / id）") }
                val visible = filterProviders(providers, search)
                if (visible.isEmpty()) {
                    item {
                        EvEmptyState(
                            title = "没有匹配的 Provider",
                            detail = "换个关键字，或新增一个自定义 Provider。",
                            glyph = EvGlyphKind.Atom,
                        )
                    }
                } else {
                    val current = currentModel(providers)
                    item {
                        EvSettingsGroup {
                            visible.forEachIndexed { index, provider ->
                                if (index > 0) EvSettingsDivider()
                                // A row that cannot be picked must not read at the same weight as
                                // one that can: disabled providers drop back to 45%.
                                Box(modifier = Modifier.alpha(if (provider.enabled) 1f else 0.45f)) {
                                    EvSettingsRow(
                                        title = provider.name,
                                        glyph = EvGlyphKind.Atom,
                                        // The tile carries the status colour, so the one row that
                                        // the next message will actually use is visible at a glance.
                                        tileColor = if (current?.provider == provider.id) {
                                            EvThemeTokens.colors.success
                                        } else {
                                            Color.Unspecified
                                        },
                                        summary = providerSummary(provider, current),
                                        onClick = { detailId = provider.id },
                                    )
                                }
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

