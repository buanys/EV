package ev.buan.plugins.ev.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.ev.ui.ui.EvMoreMenuPanel
import ev.buan.ui.EvDrawerScaffold
import ev.buan.ui.EvDrawerSide
import ev.buan.ui.EvPageTransitions
import ev.buan.ui.EvThemeTokens
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentPage
import org.cordis.api.AgentSurface
import org.cordis.api.AppearanceService
import org.cordis.api.HostNavigation
import org.cordis.api.PluginStorage
import org.cordis.api.UserDataStorage
import org.cordis.api.migrateToUserData
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import java.util.UUID

/**
 * Chat page, shipped as a jar plugin. The shell follows Ev's home and drawer; conversation state
 * lives here, while the actual agent is whichever plugin publishes [AgentService].
 *
 * Implements AgentPage rather than AgentUi because draft text, the active session and the drawer
 * are real state; a second composition must not silently create a second conversation.
 *
 * Sessions are stored in this plugin's own [PluginStorage] area as one `sessions.json` document
 * (see [ChatSessionDocuments]). The list below is the UI's source of truth: [apply] restores it, and
 * every edit - new chat, send, the reply (or its "not connected" / failure placeholder), delete,
 * regenerate - rewrites the whole document atomically through [ChatSessionStore]. A missing or
 * unreadable file restores as an empty history, so the page opens on one fresh "新的对话" instead of
 * failing; a failed write is logged and loses only that snapshot, never the turn on screen.
 *
 * Capability used: `PLUGIN_STORAGE`, declared in the container metadata. The host does not lend the
 * storage service until the user grants it (ADR-0003), so an ungranted install keeps no history.
 */
class EvUiPlugin : Plugin<Unit>, AgentSurface, AgentPage , org.cordis.api.ManifestProvider {
    override val name = "ev-ui"
    override val title = "EV UI"
    override val body = "EV 风格的对话界面"
    override val pageTitle = "聊天"

    override val inject: Dependencies =
        // PluginStorage stays declared only so the old copy can be migrated; the live data is in
        // UserDataStorage, which the host keeps when this plugin is disabled, reinstalled or unapproved.
        dependencies(
            HostNavigation.Key,
            ServiceDirectory.Key,
            PluginStorage.Key,
            UserDataStorage.Key,
            AppearanceService.Key,
            // 屏幕提示层。声明在这里才允许用 ctx.get 做可选查询：宿主旧版本时它是 null，
            // 设置页那一行会显示"未提供"，插件照常起。
            org.cordis.api.HudService.Key,
        )

    private var navigation: HostNavigation? = null
    private var directory: ServiceDirectory? = null

    /** Host-owned appearance; the settings screen reads and updates it through this service. */
    private var appearance: AppearanceService? = null

    /** 屏幕提示层。走 ctx.get 而不是 directory.lookup：后者查不到 cordis.provide 发布的服务。 */
    private var hudPreviewOn = androidx.compose.runtime.mutableStateOf(false)
    private var hud: org.cordis.api.HudService? = null

    /** Non-null while applied: without it the page still works, it just forgets on restart. */
    private var sessionStore: ChatSessionStore? = null

    /**
     * The conversations this plugin instance owns - one list, not one per composition.
     *
     * The host renders the chat twice (the root home surface and the plugin's own page activity), so
     * two independent lists would let whichever surface writes second overwrite the other one's
     * saved history. Constructed with a fresh conversation so a surface that composes before [apply]
     * finishes has something to show; [apply] then replaces the contents with what storage restored.
     */
    private val sessions = mutableStateListOf(newSession())

    /**
     * The session a turn is running for, shared for the same reason as [sessions]: two surfaces
     * must not both send into one conversation, and the typing row has to be visible in whichever
     * surface the user is looking at.
     */
    /**
     * Manual expand state per work process, keyed by message id (or "live" while running).
     *
     * Absent means "auto": expanded while that turn is still running, collapsed once it ends. A
     * present value wins forever after - a block the user opened must not close under their finger.
     */
    private val workExpansion = mutableStateMapOf<String, Boolean>()

    /**
     * The approval gate this UI publishes for the brain's dangerous tools. Plugin-scoped like
     * [sessions]: both surfaces show the same prompt, and one tap answers the one waiting call.
     */
    private val approvalGate = ApprovalGate()

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        navigation = ctx.require(HostNavigation.Key)
        directory = ctx.require(ServiceDirectory.Key)
        appearance = ctx.require(AppearanceService.Key)
        hud = ctx.get(org.cordis.api.HudService.Key)
        val storage = ctx.require(UserDataStorage.Key)
        migrateToUserData(ctx.get(PluginStorage.Key), storage)
        val store = ChatSessionStore(storage)
        sessionStore = store
        val restored = store.load()
        sessions.clear()
        // Newest first: `first()` is the conversation the user last talked in.
        sessions.addAll(restored.sortedByDescending { it.updatedAt })
        if (sessions.isEmpty()) sessions += newSession()

        // The human's voice in the agent loop: the brain suspends on this gate before running any
        // dangerous tool. Disabling the UI denies everything still waiting (fail-closed) and
        // retracts the gate, so a call can never be answered by a UI that no longer exists.
        val dir = checkNotNull(directory)
        dir.publish(ApprovalService.Key, approvalGate)
        effect.collect {
            approvalGate.denyAll()
            if (dir.lookup(ApprovalService.Key) === approvalGate) {
                dir.retract(ApprovalService.Key)
            }
        }
    }

    private fun newSessionId(): String = "ev.buan.plugins.ev.ui/${UUID.randomUUID()}"

    private fun newSession(): ChatSession =
        ChatSession(newSessionId(), DEFAULT_SESSION_TITLE, emptyList(), System.currentTimeMillis())

    @Composable
    override fun Page(modifier: Modifier, onBack: () -> Unit) =
        EvApprovalRoot(approvalGate) { ChatSurface(modifier, onHost = onBack, isRoot = false) }

    @Composable
    override fun Home(modifier: Modifier, onOpenHost: () -> Unit) =
        EvApprovalRoot(approvalGate) { ChatSurface(modifier, onHost = onOpenHost, isRoot = true) }

    /**
     * The approval dialog lives here, not inside [ChatSurface]: the panels (settings, tools,
     * memory, browser) return early from ChatSurface, but a dangerous call can arrive while any of
     * them is open, and the human must see the prompt regardless.
     */
    @Composable
    private fun ChatSurface(modifier: Modifier, onHost: () -> Unit, isRoot: Boolean) {
        val store = sessionStore
        // One holder for the transient UI flags: the drawer and the chat column take this object
        // instead of a dozen loose parameters, and the surface keeps only turn logic.
        val ui = remember { EvChatUiState() }
        // Lives above the drawer so the group stays open the next time the drawer slides in:
        // re-collapsing on every open would punish anyone who actually uses those rows.
        // Every full-screen surface lives on one stack instead of its own boolean: the render path
        // and the back handler both read it, so a new page cannot end up half-wired.
        // The composer's `+` panel and the top bar's model detail card: both are L1 surfaces that
        // spring out of the control that summoned them and close on any outside tap.
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // Turn and conversation state lives in the controller; this surface only reads it.
        val chat = remember {
            ChatSessionController(
                sessions = sessions,
                store = sessionStore,
                directory = directory,
                scope = scope,
                newSession = { newSession() },
                unconnectedPrefix = UNCONNECTED_PREFIX,
            )
        }
        val keyboard = LocalSoftwareKeyboardController.current

        // The settings, tools, memory, characters and skills panels are sheets inside this page, not
        // host activities: the system back button has to return to the conversation instead of leaving
        // the chat (or the app). The browser panel installs its own handler: it must walk the page
        // history first.
        BackHandler(enabled = ui.pages.isOpen || ui.moreMenuOpen || ui.modelSheetOpen || ui.plusPanelOpen) {
            keyboard?.hide()
            if (ui.modelSheetOpen) {
                // The model sheet belongs to the composer: back closes it, it does not navigate.
                ui.modelSheetOpen = false
                return@BackHandler
            }
            if (ui.plusPanelOpen) {
                ui.plusPanelOpen = false
                return@BackHandler
            }
            if (ui.moreMenuOpen) {
                // The quick menu is a transient surface: back closes it before it pops a page.
                ui.moreMenuOpen = false
                return@BackHandler
            }
            // One pop, always: a second-level settings page returns to the settings root, and the
            // root returns to the conversation. The browser installs its own handler deeper in the
            // tree, so back walks page history before it ever reaches this one.
            ui.pages.pop()
        }

        val activeSession = chat.activeSession
        val replying = chat.isReplying

        /**
         * Writes the whole document after an edit. Synchronous because the file is small and a
         * snapshot must reflect the edit that the user just saw; [ChatSessionStore] swallows and
         * logs its own failures, so a full disk cannot take the page down with it.
         */
        /**
         * Opens settings, optionally straight onto one of its sections.
         *
         * The root is always pushed first, so back from a section lands on settings rather than the
         * conversation - the drawer's 模型服务 row and the model card's 切换 both rely on that.
         * Without the host appearance service the page cannot render, so the push is skipped and
         * the tap does nothing instead of opening an empty surface.
         */
        fun openSettings(section: SettingsChild? = null) {
            if (appearance == null) return
            ui.pages.push(EvPage.Settings)
            if (section != null) ui.pages.push(EvPage.SettingsSection(section))
        }

        LaunchedEffect(activeSession.id, activeSession.messages.lastOrNull()?.id, replying) {
            // Follow the newest entry: the typing indicator when it is showing, otherwise the last
            // message. The list is top-aligned now, so "newest" is the bottom of the content.
            val last = activeSession.messages.lastIndex + if (replying) 1 else 0
            if (activeSession.messages.isNotEmpty() && last >= 0) {
                listState.animateScrollToItem(last)
            }
        }

        val appearanceService = appearance
        // Themes come from a theme plugin through the host catalog. The built-in registry is only
        // the fallback for "no theme plugin installed", which is the shipped state.
        val themeCatalog = directory?.lookup(org.cordis.api.ThemeCatalogService.Key)
        val availableThemes = themeCatalog?.available() ?: ev.buan.ui.EvThemeRegistry.all()
        // One dispatch for every full-screen surface. Adding a page is one branch here plus one push
        // at the call site; the back handler above needs no change, and cannot fall out of sync.
        val reducedMotion = EvThemeTokens.reducedMotion
        // Pages used to swap instantly. One container now owns that change: entering drifts in from
        // the side it belongs to, going back reverses it, and reduced motion degrades to a fade.
        androidx.compose.animation.AnimatedContent(
            targetState = ui.pages.current,
            transitionSpec = {
                if (ui.pages.lastWasPop) EvPageTransitions.backward(reducedMotion)
                else EvPageTransitions.forward(reducedMotion)
            },
            label = "page",
        ) { page ->
            when (page) {
        // Conversation.
                null -> {
            EvDrawerScaffold(
                open = ui.drawerOpen,
                onClose = { ui.drawerOpen = false },
                drawer = {
                    EvChatDrawer(
                        ui = ui,
                        sessions = sessions,
                        chat = chat,
                        activeSessionId = activeSession.id,
                        statusLine = run {
                            val online = listOf(
                                directory?.lookup(ModelService.Key),
                                directory?.lookup(ToolRegistry.Key),
                                directory?.lookup(MemoryService.Key),
                            ).count { it != null }
                            "本地 · 能力 $online/3 在线"
                        },
                        newSession = { newSession() },
                        openPlugins = { navigation?.openPluginManager() },
                        openSettings = { openSettings() },
                        openSettingsSection = { section -> openSettings(section) },
                    )
                },
                content = {
                    // The quick menu is the same kind of surface as the drawer, from the other edge, so
                    // it reuses the same scaffold instead of growing a second animation implementation.
                    EvDrawerScaffold(
                        open = ui.moreMenuOpen,
                        onClose = { ui.moreMenuOpen = false },
                        side = EvDrawerSide.End,
                        drawer = {
                            EvMoreMenuPanel(
                                onTerminal = {
                                    ui.moreMenuOpen = false
                                    ui.pages.push(EvPage.Terminal)
                                },
                                onBrowser = {
                                    ui.moreMenuOpen = false
                                    ui.pages.push(EvPage.Browser)
                                },
                                // Two tiles are deliberately empty: the grid is 2 x 2 so adding the
                                // third and fourth tool does not reshape it.
                                onPlaceholderOne = {},
                                onPlaceholderTwo = {},
                            )
                        },
                        content = {
                            EvChatColumn(
                                modifier = modifier,
                                isRoot = isRoot,
                                pageTitle = pageTitle,
                                onHost = onHost,
                                directory = directory,
                                activeSession = activeSession,
                                sessions = sessions,
                                chat = chat,
                                ui = ui,
                                listState = listState,
                                workExpansion = workExpansion,
                                approvalPending = approvalGate.pending.isNotEmpty(),
                                // Placeholder rotates per conversation, not per recomposition (spec §7.3).
                                placeholder = ComposerPlaceholders[
                                    Math.floorMod(activeSession.id.hashCode(), ComposerPlaceholders.size)
                                ],
                                suggestions = Suggestions,
                                onOpenSettings = { openSettings() },
                                onOpenSettingsSection = { section -> openSettings(section) },
                                onPickModel = { openSettings(SettingsChild.Models) },
                                onOpenMore = { ui.moreMenuOpen = true },
                            )
                        },
                    )
                },
            )
                }

                else -> EvFullPage(
                    page = page,
                    modifier = modifier,
                    appearance = appearanceService,
                    directory = directory,
                    hud = hud,
                    hudPreview = hudPreviewOn.value,
                    onToggleHudPreview = { on ->
                        hudPreviewOn.value = on
                        if (on) {
                            hud?.show(
                                org.cordis.api.HudState(
                                    step = 3,
                                    tool = "screen_tap",
                                    note = "调试预览",
                                    status = "操作中",
                                ),
                            )
                        } else {
                            hud?.hide()
                        }
                    },
                    availableThemes = availableThemes,
                    onPop = { ui.pages.pop() },
                    onPush = { ui.pages.push(it) },
                    onOpenSection = { openSettings(it) },
                    onOpenPluginManager = { navigation?.openPluginManager() },
                    onOpenHostSettings = { navigation?.openSettings() },
                )
            }
        }
    }

    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = evUiManifest

}
