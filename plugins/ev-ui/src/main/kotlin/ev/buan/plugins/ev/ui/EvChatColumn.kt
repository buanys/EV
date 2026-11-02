package ev.buan.plugins.ev.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import ev.buan.ui.evGlassLayer
import ev.buan.ui.evGlassOver
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Spacer
import ev.buan.ui.EvGlyphKind
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
import androidx.compose.ui.graphics.RectangleShape
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
    /** Opens the right-hand quick menu. */
    onOpenMore: () -> Unit,
    /** Opens the model picker page; there is no in-place selection API yet. */
    onPickModel: (String) -> Unit = {},
    onOpenSettingsSection: (SettingsChild) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val availableModels = remember(directory) {
        directory?.lookup(ModelService.Key)?.models?.map { it.model }?.distinct().orEmpty()
    }
    val currentModel = availableModels.firstOrNull()
    val replying = chat.isReplying
    // The transcript fills the screen and the bars float above it: a surface can only blur what is
    // behind it, and behind a bar in a Column there is nothing. The trade-off is that messages
    // scroll under the bars. The backdrop comes from the drawer scaffold, which owns the capture -
    // a second capture source nested inside it blanked every painted card, which is why there is
    // exactly one per screen.

    Box(
        modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
    ) {
        // 1) The source: the conversation, inset so a message is never permanently hidden.
        Column(
            Modifier.fillMaxSize()
                .padding(top = 52.dp, bottom = 96.dp),
        ) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        keyboard?.hide()
                        ui.modelSheetOpen = false
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
                        streamingText = chat.streamingText,
                        replying = replying,
                        awaitingModel = chat.awaitingModel,
                        finalizing = chat.finalizing,
                        workExpansion = workExpansion,
                        onRegenerate = { chat.regenerate(it) },
                        onDelete = { chat.delete(it) },
                    )
                }
            }
        }

        // 2) Top: a progressive scrim and two loose controls. No bar.
        //
        // A bar has to be a solid thing, and a solid thing over a conversation is always either
        // hiding content or pretending the content stops there. A gradient that grows as you
        // scroll keeps the buttons readable while leaving the page feeling open-ended.
        val scrolled by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            }
        }
        val scrim by animateFloatAsState(
            targetValue = if (scrolled) 1f else 0f,
            animationSpec = EvMotionTokens.reducedAware(EvThemeTokens.reducedMotion) {
                EvMotionTokens.fadeFast()
            },
            label = "topScrim",
        )

        if (isRoot) {
            val scrimBase = EvThemeTokens.colors.surface0
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(128.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        scrimBase.copy(alpha = 0.55f + 0.45f * scrim),
                                        scrimBase.copy(alpha = 0f),
                                    ),
                                ),
                            )
                        },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EvFloatingTopButton(EvGlyphKind.Menu, "打开侧边栏") {
                        keyboard?.hide()
                        ui.modelSheetOpen = false
                        ui.drawerOpen = true
                    }
                    Spacer(Modifier.weight(1f))
                    EvFloatingTopButton(EvGlyphKind.Create, "新建对话") {
                        chat.newChat()
                        keyboard?.hide()
                        ui.drawerOpen = false
                        ui.searching = false
                        ui.searchQuery = ""
                    }
                    Spacer(Modifier.width(8.dp))
                    EvFloatingTopButton(EvGlyphKind.More, "更多") {
                        keyboard?.hide()
                        ui.modelSheetOpen = false
                        onOpenMore()
                    }
                }
            }
        } else {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                Box(Modifier.matchParentSize().evGlassLayer(RectangleShape))
                EvPageTopBar(pageTitle, onHost)
            }
        }

        // 3) Composer: the area owns its own glass (only the field, not the panels above it).
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            EvComposerArea(
                modelName = currentModel,
                models = availableModels,
                modelSheetOpen = ui.modelSheetOpen,
                onModelClick = {
                    keyboard?.hide()
                    ui.plusPanelOpen = false
                    ui.modelSheetOpen = !ui.modelSheetOpen
                },
                onPickModel = { name ->
                    ui.modelSheetOpen = false
                    onPickModel(name)
                },
                draft = ui.draft,
                onDraftChange = { ui.draft = it },
                onSend = { chat.send(ui.draft) },
                placeholder = placeholder,
                busy = replying,
                onStop = { chat.stopReply() },
                plusActive = ui.plusPanelOpen,
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
}