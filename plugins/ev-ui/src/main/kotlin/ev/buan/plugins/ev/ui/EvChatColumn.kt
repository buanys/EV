package ev.buan.plugins.ev.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.ev.ui.ui.EvSuggestion
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import org.cordis.api.ServiceDirectory
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The conversation column: top bar, transcript (or the empty hero), the model card and the composer.
 *
 * Extracted so the plugin class stays orchestration. Everything it renders is passed in; the only
 * state it mutates is [EvChatUiState], which is the surface's own transient UI state.
 */
@Composable
internal fun EvChatColumn(
    modifier: Modifier,
    isRoot: Boolean,
    pageTitle: String,
    onHost: () -> Unit,
    directory: ServiceDirectory?,
    activeSession: ChatSession,
    sessions: List<ChatSession>,
    chat: ChatSessionController,
    ui: EvChatUiState,
    listState: LazyListState,
    workExpansion: SnapshotStateMap<String, Boolean>,
    approvalPending: Boolean,
    placeholder: String,
    suggestions: List<EvSuggestion>,
    onOpenSettings: () -> Unit,
    onOpenSettingsSection: (SettingsChild) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val replying = chat.isReplying
    // The composer shares the column with the conversation. The keyboard then shrinks the
    // list instead of covering it, which is the only arrangement where the newest message
    // stays visible while typing. Scrolling deliberately does not dismiss the keyboard (no
    // imeNestedScroll); tapping the conversation does.
    Column(
        modifier.fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        if (isRoot) {
            // Live lookup per composition (same cheap map read the drawer's status line
            // uses): the capsule mirrors whatever provider the contract resolves as current.
            val currentModel = directory?.lookup(ModelService.Key)?.models?.firstOrNull()?.model
            EvHomeTopBar(
                // An empty conversation has no identity yet - the hero below already says
                // what this page is, so the top bar stays quiet instead of repeating it.
                title = activeSession.takeIf { it.messages.isNotEmpty() }?.displayTitle(),
                modelName = currentModel,
                approvalPending = approvalPending,
                onMenu = {
                    keyboard?.hide()
                    ui.modelCardOpen = false
                    ui.drawerOpen = true
                },
                onCapsule = {
                    keyboard?.hide()
                    ui.modelCardOpen = !ui.modelCardOpen
                },
                onNewChat = { chat.newChat(); keyboard?.hide(); ui.drawerOpen = false; ui.searching = false; ui.searchQuery = "" },
                onMore = {
                    keyboard?.hide()
                    onOpenSettings()
                },
            )
        } else {
            EvPageTopBar(pageTitle, onHost)
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    keyboard?.hide()
                    ui.modelCardOpen = false
                })
            },
        ) {
            // What the agent can do right now, and where the person left off.
            val capabilityLine = run {
                val tools = directory?.lookup(ToolRegistry.Key)?.specs()?.size
                val memory = directory?.lookup(MemoryService.Key) != null
                listOfNotNull(
                    tools?.let { "$it 个工具" },
                    if (memory) "记忆已启用" else "记忆未启用",
                ).joinToString(" · ")
            }
            if (activeSession.messages.isEmpty()) {
                EvChatEmptyState(
                    sessions = sessions,
                    activeSessionId = activeSession.id,
                    capabilityLine = capabilityLine,
                    suggestions = suggestions,
                    onPick = { suggestion ->
                        // An empty prompt is a local action, not a message: today that
                        // is the browser panel, the one suggestion that needs no agent.
                        if (suggestion.prompt.isEmpty()) ui.pages.push(EvPage.Browser)
                        else chat.send(suggestion.prompt)
                    },
                    onResume = { id ->
                        keyboard?.hide()
                        chat.select(id)
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                EvMessageList(
                    sessionId = activeSession.id,
                    messages = activeSession.messages,
                    listState = listState,
                    liveRounds = chat.liveRounds,
                    replying = replying,
                    awaitingModel = chat.awaitingModel,
                    finalizing = chat.finalizing,
                    workExpansion = workExpansion,
                    onRegenerate = { chat.regenerate(it) },
                    onDelete = { chat.delete(it) },
                )
            }

            // The capsule's detail card (spec §5.2): overlays the conversation instead of
            // pushing it, springs down from the top bar, and any outside tap dismisses it.
            // Token spend lives here now - no longer a permanent top-bar resident.
            androidx.compose.animation.AnimatedVisibility(
                visible = ui.modelCardOpen,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
                    fadeIn(EvMotionTokens.fadeFast()),
                exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
                    fadeOut(EvMotionTokens.fadeFast()),
            ) {
                EvModelDetailCard(
                    modelName = directory?.lookup(ModelService.Key)?.models?.firstOrNull()?.model,
                    lastTokens = chat.lastTurnTokens,
                    onSwitchModel = {
                        ui.modelCardOpen = false
                        onOpenSettingsSection(SettingsChild.Models)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        EvComposerArea(
            draft = ui.draft,
            onDraftChange = { ui.draft = it },
            onSend = { chat.send(ui.draft) },
            // Placeholder rotates per conversation, not per recomposition (spec §7.3).
            placeholder = placeholder,
            busy = replying,
            onStop = { chat.stopReply() },
            plusActive = ui.plusPanelOpen,
            // Opening the panel trades the keyboard for it - they never share the screen.
            onPlus = {
                if (!ui.plusPanelOpen) keyboard?.hide()
                ui.plusPanelOpen = !ui.plusPanelOpen
            },
            onFocusChange = { focused -> if (focused) ui.plusPanelOpen = false },
            onOpenBrowser = { ui.plusPanelOpen = false; ui.pages.push(EvPage.Browser) },
            onOpenTools = { ui.plusPanelOpen = false; ui.pages.push(EvPage.Tools) },
            onOpenCharacters = { ui.plusPanelOpen = false; ui.pages.push(EvPage.Characters) },
            onOpenSkills = { ui.plusPanelOpen = false; ui.pages.push(EvPage.Skills) },
        )
    }
}
