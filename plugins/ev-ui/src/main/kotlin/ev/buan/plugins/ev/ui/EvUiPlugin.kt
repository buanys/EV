package ev.buan.plugins.ev.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.ev.ui.ui.EvBrowserScreen
import ev.buan.plugins.ev.ui.ui.EvCharactersScreen
import ev.buan.plugins.ev.ui.ui.EvMoreMenuPanel
import ev.buan.plugins.ev.ui.ui.EvTerminalScreen
import ev.buan.plugins.ev.ui.ui.EvThemePickerScreen
import ev.buan.plugins.ev.ui.ui.EvDrawerContent
import ev.buan.plugins.ev.ui.ui.ellipsize
import ev.buan.plugins.ev.ui.ui.EvHistoryGroup
import ev.buan.plugins.ev.ui.ui.EvMemoryScreen
import ev.buan.plugins.ev.ui.ui.EvModelProvidersScreen
import ev.buan.plugins.ev.ui.ui.EvSessionSummary
import ev.buan.plugins.ev.ui.ui.EvSettingsScreen
import ev.buan.plugins.ev.ui.ui.EvSkillsScreen
import ev.buan.plugins.ev.ui.ui.EvToolsScreen
import ev.buan.plugins.ev.ui.ui.EvApprovalDialog
import ev.buan.plugins.ev.ui.ui.EvChatBubble
import ev.buan.plugins.ev.ui.ui.EvChatDefaults
import ev.buan.plugins.ev.ui.ui.EvMessageEntry
import ev.buan.plugins.ev.ui.ui.EvComposer
import ev.buan.plugins.ev.ui.ui.EvComposerAction
import ev.buan.plugins.ev.ui.ui.EvComposerPanel
import ev.buan.plugins.ev.ui.ui.EvHomeHero
import ev.buan.plugins.ev.ui.ui.EvSuggestion
import ev.buan.plugins.ev.ui.ui.EvTypingIndicator
import ev.buan.ui.EvDrawerScaffold
import ev.buan.ui.EvDrawerSide
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvIcons
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvPageTransitions
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Icon
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
class EvUiPlugin : Plugin<Unit>, AgentSurface, AgentPage {
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
        )

    private var navigation: HostNavigation? = null
    private var directory: ServiceDirectory? = null

    /** Host-owned appearance; the settings screen reads and updates it through this service. */
    private var appearance: AppearanceService? = null

    /** Non-null while applied: without it the page still works, it just forgets on restart. */
    private var sessionStore: ChatSessionStore? = null

    /** Non-null while applied: the character library file, same story as [sessionStore]. */
    private var characterStore: CharacterStore? = null

    /** Non-null while applied: the skills library file, same story as [sessionStore]. */
    private var skillStore: SkillStore? = null

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
     * The character library this plugin instance owns, newest first.
     *
     * Plugin-scoped for the same reason as [sessions]: both surfaces must see the same library and
     * the same per-session selection, and a character saved on one surface has to be selectable on
     * the other without a restart.
     */
    private val characters = mutableStateListOf<EvCharacter>()

    /**
     * The skills library this plugin instance owns, newest first - same scope and reasons as
     * [characters]. A skill is only text: it never runs, it is folded into the turn's system prompt.
     */
    private val skills = mutableStateListOf<EvSkill>()

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
        val storage = ctx.require(UserDataStorage.Key)
        migrateToUserData(ctx.get(PluginStorage.Key), storage)
        val store = ChatSessionStore(storage)
        sessionStore = store
        val charactersStore = CharacterStore(storage)
        characterStore = charactersStore
        val skillsStore = SkillStore(storage)
        skillStore = skillsStore
        val restored = store.load()
        sessions.clear()
        // Newest first: `first()` is the conversation the user last talked in.
        sessions.addAll(restored.sortedByDescending { it.updatedAt })
        if (sessions.isEmpty()) sessions += newSession()
        characters.clear()
        characters.addAll(charactersStore.load().sortedByDescending { it.updatedAt })
        skills.clear()
        skills.addAll(skillsStore.load().sortedByDescending { it.updatedAt })

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
        Rooted { ChatSurface(modifier, onHost = onBack, isRoot = false) }

    @Composable
    override fun Home(modifier: Modifier, onOpenHost: () -> Unit) =
        Rooted { ChatSurface(modifier, onHost = onOpenHost, isRoot = true) }

    /**
     * The approval dialog lives here, not inside [ChatSurface]: the panels (settings, tools,
     * memory, browser) return early from ChatSurface, but a dangerous call can arrive while any of
     * them is open, and the human must see the prompt regardless.
     */
    @Composable
    private fun Rooted(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            content()
            approvalGate.pending.firstOrNull()?.let { prompt ->
                EvApprovalDialog(
                    prompt = prompt,
                    onDecide = { decision -> approvalGate.decide(prompt.id, decision) },
                )
            }
        }
    }

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
                characters = characters,
                skills = skills,
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
        BackHandler(enabled = ui.pages.isOpen || ui.moreMenuOpen) {
            keyboard?.hide()
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
        /** Same contract for the character library: whole-file snapshot, failures logged not thrown. */
        fun persistCharacters() {
            characterStore?.save(characters.toList())
        }

        /** Same contract for the skills library. */
        fun persistSkills() {
            skillStore?.save(skills.toList())
        }

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

        /** Replaces the library contents in place, so both surfaces see the new list, then persists. */
        fun replaceCharacters(next: List<EvCharacter>) {
            characters.clear()
            characters.addAll(next)
            persistCharacters()
        }

        /** Replaces the skills library contents in place, then persists. */
        fun replaceSkills(next: List<EvSkill>) {
            skills.clear()
            skills.addAll(next)
            persistSkills()
        }

        /**
         * Removes a character and drops every session's reference to it.
         *
         * A dangling [ChatSession.characterId] would read as "no role" anyway, but clearing it keeps
         * the saved file honest and stops the id from resurfacing if a new character ever got it.
         */
        fun deleteCharacter(characterId: String) {
            replaceCharacters(characters.without(characterId))
            if (sessions.none { it.characterId == characterId }) return
            sessions.clearingCharacter(characterId).forEachIndexed { index, session ->
                sessions[index] = session
            }
            chat.persist()
        }

        /** Removes a skill and drops every session's reference to it, for the same reasons. */
        fun deleteSkill(skillId: String) {
            replaceSkills(skills.without(skillId))
            if (sessions.none { it.skillId == skillId }) return
            sessions.clearingSkill(skillId).forEachIndexed { index, session ->
                sessions[index] = session
            }
            chat.persist()
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
        EvPage.Settings -> {
            // Without the host appearance service the settings page cannot render, so the
            // conversation stays visible rather than showing an empty surface.
            if (appearanceService != null) {
                EvSettingsScreen(
                    appearance = appearanceService,
                    onBack = { ui.pages.pop() },
                    onOpenPlugins = { navigation?.openPluginManager() },
                    onOpenHostSettings = { navigation?.openSettings() },
                    onOpenThemes = { ui.pages.push(EvPage.SettingsSection(SettingsChild.Themes)) },
                    themeName = availableThemes.firstOrNull { it.id == appearanceService.current.themeId }?.name
                        ?: availableThemes.first().name,
                    onOpenModels = { ui.pages.push(EvPage.SettingsSection(SettingsChild.Models)) },
                    onOpenTools = { ui.pages.push(EvPage.SettingsSection(SettingsChild.Tools)) },
                    onOpenMemory = { ui.pages.push(EvPage.SettingsSection(SettingsChild.Memory)) },
                    onOpenCharacters = { ui.pages.push(EvPage.SettingsSection(SettingsChild.Characters)) },
                    onOpenSkills = { ui.pages.push(EvPage.SettingsSection(SettingsChild.Skills)) },
                    modifier = modifier,
                )
            }
        }

        is EvPage.SettingsSection -> {
            // A second-level settings page keeps the root underneath: back returns to it.
            when (page.section) {
                SettingsChild.Themes -> EvThemePickerScreen(
                    themes = availableThemes,
                    currentId = appearanceService?.current?.themeId.orEmpty(),
                    // The host owns the appearance state: selecting a theme persists it and
                    // recomposes the whole app, this page only asks for the change.
                    onSelect = { id -> appearanceService?.update { it.copy(themeId = id) } },
                    onBack = { ui.pages.pop() },
                    modifier = modifier,
                )

                SettingsChild.Models -> EvModelProvidersScreen(
                    service = { directory?.lookup(ModelProviderService.Key) },
                    onBack = { ui.pages.pop() },
                    modifier = modifier,
                )

                SettingsChild.Tools -> EvToolsScreen(
                    registry = { directory?.lookup(ToolRegistry.Key) },
                    onBack = { ui.pages.pop() },
                    modifier = modifier,
                )

                SettingsChild.Memory -> EvMemoryScreen(
                    sessionId = activeSession.id,
                    memory = { directory?.lookup(MemoryService.Key) },
                    onBack = { ui.pages.pop() },
                    modifier = modifier,
                )

                SettingsChild.Characters -> EvCharactersScreen(
                    characters = characters,
                    selectedId = activeSession.characterId,
                    onBack = { ui.pages.pop() },
                    onSelect = { characterId ->
                        chat.mutate(activeSession.id) { session -> session.copy(characterId = characterId) }
                    },
                    onSave = { saved -> replaceCharacters(characters.upsert(saved)) },
                    onDelete = { characterId -> deleteCharacter(characterId) },
                    modifier = modifier,
                )

                SettingsChild.Skills -> EvSkillsScreen(
                    skills = skills,
                    selectedId = activeSession.skillId,
                    onBack = { ui.pages.pop() },
                    onSelect = { skillId ->
                        chat.mutate(activeSession.id) { session -> session.copy(skillId = skillId) }
                    },
                    onSave = { saved -> replaceSkills(skills.upsert(saved)) },
                    onDelete = { skillId -> deleteSkill(skillId) },
                    modifier = modifier,
                )
            }
        }

        EvPage.Tools -> {
            // Looked up per read, never cached: the tools plugin may be enabled or disabled while
            // this page is open, and the panel has a refresh action for exactly that.
            EvToolsScreen(
                registry = { directory?.lookup(ToolRegistry.Key) },
                onBack = { ui.pages.pop() },
                modifier = modifier,
            )
        }

        EvPage.Browser -> {
            EvBrowserScreen(onBack = { ui.pages.pop() }, modifier = modifier)
        }

        EvPage.Terminal -> {
            // Same tool registry the agent uses: here a human types the command, not a model.
            EvTerminalScreen(
                registry = { directory?.lookup(ToolRegistry.Key) },
                onBack = { ui.pages.pop() },
                modifier = modifier,
            )
        }

        EvPage.Memory -> {
            // The memory plugin may be enabled, disabled or reconfigured while the page is open:
            // the service is looked up on every read, and the panel has a refresh action.
            EvMemoryScreen(
                sessionId = activeSession.id,
                memory = { directory?.lookup(MemoryService.Key) },
                onBack = { ui.pages.pop() },
                modifier = modifier,
            )
        }

        EvPage.Characters -> {
            EvCharactersScreen(
                characters = characters,
                selectedId = activeSession.characterId,
                onBack = { ui.pages.pop() },
                // Selecting stores the id on the session, so one conversation can talk to one
                // role while another talks to none. `mutate` persists sessions.json by itself.
                onSelect = { characterId ->
                    chat.mutate(activeSession.id) { session -> session.copy(characterId = characterId) }
                },
                onSave = { saved -> replaceCharacters(characters.upsert(saved)) },
                onDelete = { characterId -> deleteCharacter(characterId) },
                modifier = modifier,
            )
        }

        EvPage.Skills -> {
            EvSkillsScreen(
                skills = skills,
                selectedId = activeSession.skillId,
                onBack = { ui.pages.pop() },
                // Same per-session semantics as characters: the id lives on the conversation.
                onSelect = { skillId ->
                    chat.mutate(activeSession.id) { session -> session.copy(skillId = skillId) }
                },
                onSave = { saved -> replaceSkills(skills.upsert(saved)) },
                onDelete = { skillId -> deleteSkill(skillId) },
                modifier = modifier,
            )
        }
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
                                onOpenMore = { ui.moreMenuOpen = true },
                            )
                        },
                    )
                },
            )
                }
            }
        }
    }

    private companion object {
        /** Shown only when no plugin publishes AgentService: the original placeholder behaviour. */
        const val UNCONNECTED_PREFIX = "（未接模型）我收到了："

        /** Rotating composer placeholders (spec §7.3); one is picked per conversation. */
        val ComposerPlaceholders = listOf("交给 EV 去完成", "尽管问，带图也行")

        /**
         * Home suggestions, kept honest: every prompt maps to a capability that exists today
         * (the storage file tools, or the built-in browser panel). 分析屏幕 / 打开微信 return when
         * device-context and app-launch tools actually ship.
         */
        val Suggestions = listOf(
            EvSuggestion("列出我的文件", EvGlyphKind.Folder, "列出存储区里有哪些文件"),
            EvSuggestion("记一条笔记", EvGlyphKind.Create, "帮我把「今晚记得给花浇水」追加到 notes/memo.md"),
            EvSuggestion("你能做什么", EvGlyphKind.Help, "你现在有哪些工具，能帮我做什么？"),
            EvSuggestion("浏览网页", EvGlyphKind.World, ""),
        )
    }
}

/** Clock time on a history row; the group label above it already says which day it was. */