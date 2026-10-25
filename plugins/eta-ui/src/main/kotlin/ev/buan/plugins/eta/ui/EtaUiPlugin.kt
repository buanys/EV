package ev.buan.plugins.eta.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.eta.ui.ui.EtaBrowserScreen
import ev.buan.plugins.eta.ui.ui.EtaCharactersScreen
import ev.buan.plugins.eta.ui.ui.EtaDrawerContent
import ev.buan.plugins.eta.ui.ui.EtaHistoryGroup
import ev.buan.plugins.eta.ui.ui.EtaMemoryScreen
import ev.buan.plugins.eta.ui.ui.EtaModelProvidersScreen
import ev.buan.plugins.eta.ui.ui.EtaSessionSummary
import ev.buan.plugins.eta.ui.ui.EtaSettingsScreen
import ev.buan.plugins.eta.ui.ui.EtaSkillsScreen
import ev.buan.plugins.eta.ui.ui.EtaToolsScreen
import ev.buan.plugins.eta.ui.ui.EvApprovalDialog
import ev.buan.plugins.eta.ui.ui.EvChatBubble
import ev.buan.plugins.eta.ui.ui.EvChatDefaults
import ev.buan.plugins.eta.ui.ui.EvMessageEntry
import ev.buan.plugins.eta.ui.ui.EvComposer
import ev.buan.plugins.eta.ui.ui.EvHomeHero
import ev.buan.plugins.eta.ui.ui.EvSuggestion
import ev.buan.plugins.eta.ui.ui.EvTypingIndicator
import ev.buan.ui.EvDrawerScaffold
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Second-level pages reachable from the settings root.
 *
 * They keep the settings page open underneath: back returns to the root, while the drawer's own
 * entries (工具/记忆/角色/Skills) still open the same screens straight from the chat.
 */
private enum class SettingsChild {
    Models,
    Tools,
    Memory,
    Characters,
    Skills,
}

/**
 * Chat page, shipped as a jar plugin. The shell follows Eta's home and drawer; conversation state
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
class EtaUiPlugin : Plugin<Unit>, AgentSurface, AgentPage {
    override val name = "eta-ui"
    override val title = "Eta UI"
    override val body = "Eta 风格的对话界面"
    override val pageTitle = "聊天"

    override val inject: Dependencies =
        dependencies(HostNavigation.Key, ServiceDirectory.Key, PluginStorage.Key, AppearanceService.Key)

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
    private val characters = mutableStateListOf<EtaCharacter>()

    /**
     * The skills library this plugin instance owns, newest first - same scope and reasons as
     * [characters]. A skill is only text: it never runs, it is folded into the turn's system prompt.
     */
    private val skills = mutableStateListOf<EtaSkill>()

    /**
     * The session a turn is running for, shared for the same reason as [sessions]: two surfaces
     * must not both send into one conversation, and the typing row has to be visible in whichever
     * surface the user is looking at.
     */
    private var replyingSessionId by mutableStateOf<String?>(null)

    /** The job behind each in-flight turn, so the composer's stop button can cancel exactly one. */
    private val replyJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * The approval gate this UI publishes for the brain's dangerous tools. Plugin-scoped like
     * [sessions]: both surfaces show the same prompt, and one tap answers the one waiting call.
     */
    private val approvalGate = ApprovalGate()

    /** Last completed turn's model and token spend, shown in the home top bar. */
    private var lastTurnModel by mutableStateOf<String?>(null)
    private var lastTurnTokens by mutableStateOf<Int?>(null)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        navigation = ctx.require(HostNavigation.Key)
        directory = ctx.require(ServiceDirectory.Key)
        appearance = ctx.require(AppearanceService.Key)
        val storage = ctx.require(PluginStorage.Key)
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

    private fun newSessionId(): String = "ev.buan.plugins.eta.ui/${UUID.randomUUID()}"

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
        var activeSessionId by remember { mutableStateOf(sessions.first().id) }
        var draft by remember { mutableStateOf("") }
        var drawerOpen by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var settingsChild by remember { mutableStateOf<SettingsChild?>(null) }
        var showTools by remember { mutableStateOf(false) }
        var showBrowser by remember { mutableStateOf(false) }
        var showMemory by remember { mutableStateOf(false) }
        var showCharacters by remember { mutableStateOf(false) }
        var showSkills by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current

        // The settings, tools, memory, characters and skills panels are sheets inside this page, not
        // host activities: the system back button has to return to the conversation instead of leaving
        // the chat (or the app). The browser panel installs its own handler: it must walk the page
        // history first.
        BackHandler(
            enabled = showSettings || showTools || showMemory || showCharacters || showSkills,
        ) {
            keyboard?.hide()
            when {
                // Settings walks its own stack first: a second-level page returns to the root.
                settingsChild != null -> settingsChild = null
                showSettings -> showSettings = false
                showTools -> showTools = false
                showMemory -> showMemory = false
                showCharacters -> showCharacters = false
                showSkills -> showSkills = false
            }
        }

        val activeSession = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()
        val replying = replyingSessionId == activeSession.id

        /**
         * Writes the whole document after an edit. Synchronous because the file is small and a
         * snapshot must reflect the edit that the user just saw; [ChatSessionStore] swallows and
         * logs its own failures, so a full disk cannot take the page down with it.
         */
        fun persist() {
            store?.save(sessions.toList())
        }

        /** Same contract for the character library: whole-file snapshot, failures logged not thrown. */
        fun persistCharacters() {
            characterStore?.save(characters.toList())
        }

        /** Same contract for the skills library. */
        fun persistSkills() {
            skillStore?.save(skills.toList())
        }

        /** Replaces the library contents in place, so both surfaces see the new list, then persists. */
        fun replaceCharacters(next: List<EtaCharacter>) {
            characters.clear()
            characters.addAll(next)
            persistCharacters()
        }

        /** Replaces the skills library contents in place, then persists. */
        fun replaceSkills(next: List<EtaSkill>) {
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
            persist()
        }

        /** Removes a skill and drops every session's reference to it, for the same reasons. */
        fun deleteSkill(skillId: String) {
            replaceSkills(skills.without(skillId))
            if (sessions.none { it.skillId == skillId }) return
            sessions.clearingSkill(skillId).forEachIndexed { index, session ->
                sessions[index] = session
            }
            persist()
        }

        fun mutate(id: String, transform: (ChatSession) -> ChatSession) {
            val index = sessions.indexOfFirst { it.id == id }
            if (index >= 0) {
                sessions[index] = transform(sessions[index])
                // Every conversation edit - send, reply, delete, regenerate - ends up here.
                persist()
            }
        }

        fun append(sessionId: String, message: ChatMessage) {
            mutate(sessionId) { session ->
                session.copy(
                    messages = session.messages + message,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        /**
         * Runs one agent turn for [prompt] and appends the assistant bubble.
         *
         * Shared by send and regenerate: a regenerated reply is a normal turn, the only difference
         * is that its user bubble already exists. The agent is looked up on every turn, never
         * cached: the brain may be installed, enabled or disabled after this page was opened.
         *
         * The turn carries the session's character as `systemPrompt` when one is selected. The
         * prompt is *not* added to the bubble text and the brain does not store it, so a role changes
         * what the model sees without touching the transcript or the memory file.
         */
        fun requestReply(sessionId: String, prompt: String) {
            if (replyingSessionId == sessionId) return
            replyingSessionId = sessionId
            val job = scope.launch {
                try {
                    val agent = directory?.lookup(AgentService.Key)
                    if (agent == null) {
                        delay(420)
                        append(sessionId, assistantMessage(UNCONNECTED_PREFIX + prompt))
                    } else {
                        val reply = agent.send(
                            sessionId = sessionId,
                            text = prompt,
                            systemPrompt = sessionSystemPrompt(
                                characters = characters,
                                skills = skills,
                                session = sessions.firstOrNull { it.id == sessionId },
                            ),
                        )
                        val answer = reply.message.content.ifBlank { "（模型没有返回文本）" }
                        lastTurnModel = reply.model?.model
                        lastTurnTokens = reply.usage?.totalTokens
                        append(sessionId, assistantMessage(answer, events = reply.events))
                    }
                } catch (cancel: CancellationException) {
                    // The stop button lands here: leave a tombstone so the transcript shows the
                    // turn was cut short, then let the cancellation keep propagating.
                    append(sessionId, assistantMessage("（已停止）"))
                    throw cancel
                } catch (error: Throwable) {
                    append(
                        sessionId,
                        assistantMessage("（对话失败）${error.message ?: error::class.simpleName}"),
                    )
                } finally {
                    if (replyingSessionId == sessionId) replyingSessionId = null
                    replyJobs.remove(sessionId)
                }
            }
            replyJobs[sessionId] = job
        }

        /** Cancels the in-flight turn of the active session; the brain unwinds as a cancellation. */
        fun stopReply() {
            val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
            replyJobs[target.id]?.cancel()
        }

        fun send(text: String) {
            val body = text.trim()
            if (body.isEmpty()) return
            val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
            if (replyingSessionId == target.id) return
            mutate(target.id) { session ->
                session.copy(
                    messages = session.messages + userMessage(body),
                    title = if (session.messages.none { it.fromUser }) body.take(18) else session.title,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            draft = ""
            requestReply(target.id, body)
        }

        /**
         * Drops exactly one bubble. The agent's own memory keeps whatever that turn appended: the
         * contract has no per-message delete, so this is a view-level edit.
         *
         * No-op while this session is replying - deleting a message under an in-flight turn would
         * interleave a reply with a conversation it was not generated from.
         */
        fun delete(messageId: String) {
            val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
            if (replyingSessionId == target.id) return
            mutate(target.id) { session ->
                session.copy(
                    messages = withoutMessage(session.messages, messageId),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        /**
         * Asks the same question again: the history is cut back to its user bubble (kept, not
         * re-appended) and a fresh reply is requested for it.
         */
        fun regenerate(messageId: String) {
            val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
            if (replyingSessionId == target.id) return
            val plan = regenerationOf(target.messages, messageId) ?: return
            mutate(target.id) { session ->
                session.copy(messages = plan.messages, updatedAt = System.currentTimeMillis())
            }
            requestReply(target.id, plan.prompt)
        }

        LaunchedEffect(activeSession.id, activeSession.messages.lastOrNull()?.id, replying) {
            if (activeSession.messages.isNotEmpty()) listState.animateScrollToItem(0)
        }

        val appearanceService = appearance
        val child = settingsChild
        if (showSettings && appearanceService != null) {
            if (child != null) {
                // A second-level settings page: back returns to the settings root, not to the chat,
                // and the system back button walks the same path (see the BackHandler above).
                when (child) {
                    SettingsChild.Models -> EtaModelProvidersScreen(
                        service = { directory?.lookup(ModelProviderService.Key) },
                        onBack = { settingsChild = null },
                        modifier = modifier,
                    )

                    SettingsChild.Tools -> EtaToolsScreen(
                        registry = { directory?.lookup(ToolRegistry.Key) },
                        onBack = { settingsChild = null },
                        modifier = modifier,
                    )

                    SettingsChild.Memory -> EtaMemoryScreen(
                        sessionId = activeSession.id,
                        memory = { directory?.lookup(MemoryService.Key) },
                        onBack = { settingsChild = null },
                        modifier = modifier,
                    )

                    SettingsChild.Characters -> EtaCharactersScreen(
                        characters = characters,
                        selectedId = activeSession.characterId,
                        onBack = { settingsChild = null },
                        onSelect = { characterId ->
                            mutate(activeSession.id) { session -> session.copy(characterId = characterId) }
                        },
                        onSave = { saved -> replaceCharacters(characters.upsert(saved)) },
                        onDelete = { characterId -> deleteCharacter(characterId) },
                        modifier = modifier,
                    )

                    SettingsChild.Skills -> EtaSkillsScreen(
                        skills = skills,
                        selectedId = activeSession.skillId,
                        onBack = { settingsChild = null },
                        onSelect = { skillId ->
                            mutate(activeSession.id) { session -> session.copy(skillId = skillId) }
                        },
                        onSave = { saved -> replaceSkills(skills.upsert(saved)) },
                        onDelete = { skillId -> deleteSkill(skillId) },
                        modifier = modifier,
                    )
                }
                return
            }
            // Settings replaces the conversation rather than covering it: the chat keeps its state
            // (draft, scroll position, active session) because everything is remembered above this
            // branch, and the BackHandler installed above turns system back into "close".
            EtaSettingsScreen(
                appearance = appearanceService,
                onBack = { showSettings = false },
                onOpenPlugins = { navigation?.openPluginManager() },
                onOpenHostSettings = { navigation?.openSettings() },
                onOpenModels = { settingsChild = SettingsChild.Models },
                onOpenTools = { settingsChild = SettingsChild.Tools },
                onOpenMemory = { settingsChild = SettingsChild.Memory },
                onOpenCharacters = { settingsChild = SettingsChild.Characters },
                onOpenSkills = { settingsChild = SettingsChild.Skills },
                modifier = modifier,
            )
            return
        }

        if (showTools) {
            // Looked up per read, never cached: the tools plugin may be enabled or disabled while
            // this page is open, and the panel has a refresh action for exactly that.
            EtaToolsScreen(
                registry = { directory?.lookup(ToolRegistry.Key) },
                onBack = { showTools = false },
                modifier = modifier,
            )
            return
        }

        if (showBrowser) {
            EtaBrowserScreen(onBack = { showBrowser = false }, modifier = modifier)
            return
        }

        if (showMemory) {
            // The memory plugin may be enabled, disabled or reconfigured while the page is open: the
            // service is looked up on every read, and the panel has a refresh action for exactly that.
            EtaMemoryScreen(
                sessionId = activeSession.id,
                memory = { directory?.lookup(MemoryService.Key) },
                onBack = { showMemory = false },
                modifier = modifier,
            )
            return
        }

        if (showCharacters) {
            EtaCharactersScreen(
                characters = characters,
                selectedId = activeSession.characterId,
                onBack = { showCharacters = false },
                // Selecting stores the id on the session, so one conversation can talk to one role
                // while another talks to none. `mutate` persists sessions.json by itself.
                onSelect = { characterId ->
                    mutate(activeSession.id) { session -> session.copy(characterId = characterId) }
                },
                onSave = { saved -> replaceCharacters(characters.upsert(saved)) },
                onDelete = { characterId -> deleteCharacter(characterId) },
                modifier = modifier,
            )
            return
        }

        if (showSkills) {
            EtaSkillsScreen(
                skills = skills,
                selectedId = activeSession.skillId,
                onBack = { showSkills = false },
                // Same per-session semantics as characters: the id lives on the conversation.
                onSelect = { skillId ->
                    mutate(activeSession.id) { session -> session.copy(skillId = skillId) }
                },
                onSave = { saved -> replaceSkills(skills.upsert(saved)) },
                onDelete = { skillId -> deleteSkill(skillId) },
                modifier = modifier,
            )
            return
        }

        EvDrawerScaffold(
            open = drawerOpen,
            onClose = { drawerOpen = false },
            drawer = {
                EtaDrawerContent(
                    groups = historyGroups(sessions, activeSession.id, searchQuery),
                    // Identity line: how many of the three agent capabilities are live right now.
                    statusLine = run {
                        val online = listOf(
                            directory?.lookup(ModelService.Key),
                            directory?.lookup(ToolRegistry.Key),
                            directory?.lookup(MemoryService.Key),
                        ).count { it != null }
                        "本地 · 能力 $online/3 在线"
                    },
                    searching = searching,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSelectSession = { id ->
                        activeSessionId = id
                        searching = false
                        searchQuery = ""
                        keyboard?.hide()
                        drawerOpen = false
                    },
                    onNewChat = {
                        val fresh = newSession()
                        sessions += fresh
                        persist()
                        activeSessionId = fresh.id
                        keyboard?.hide()
                        drawerOpen = false
                        searching = false
                        searchQuery = ""
                    },
                    onPlugins = {
                        navigation?.openPluginManager()
                        drawerOpen = false
                    },
                    onModel = {
                        // The drawer is the second way into the same in-plugin provider page as
                        // Settings -> 模型服务; host plugin management is only reached from there.
                        keyboard?.hide()
                        drawerOpen = false
                        settingsChild = SettingsChild.Models
                        showSettings = true
                    },
                    onTools = {
                        // The read-only panel for whatever agent-tools publishes; the settings page's
                        // own "工具" row keeps pointing at the plugin manager for enabling it.
                        keyboard?.hide()
                        drawerOpen = false
                        showTools = true
                    },
                    onBrowser = {
                        keyboard?.hide()
                        drawerOpen = false
                        showBrowser = true
                    },
                    onMemory = {
                        // The active conversation is what the panel reads and can clear; clearing it
                        // leaves the chat bubbles above untouched.
                        keyboard?.hide()
                        drawerOpen = false
                        showMemory = true
                    },
                    onCharacters = {
                        // Roles are per session: the panel edits the selected conversation's choice.
                        keyboard?.hide()
                        drawerOpen = false
                        showCharacters = true
                    },
                    onSkills = {
                        // Skills are per session too; nothing is executed, the text only reaches the
                        // model inside this conversation's system prompt.
                        keyboard?.hide()
                        drawerOpen = false
                        showSkills = true
                    },
                    onSettings = {
                        // Eta's own settings page, inside this plugin: the host page stays reachable
                        // from the drawer's "宿主设置" row on the settings screen itself.
                        keyboard?.hide()
                        drawerOpen = false
                        settingsChild = null
                        showSettings = true
                    },
                    onSearch = {
                        searching = !searching
                        if (!searching) searchQuery = ""
                    },
                )
            },
        ) {
            // The composer shares the column with the conversation. The keyboard then shrinks the
            // list instead of covering it, which is the only arrangement where the newest message
            // stays visible while typing. Scrolling deliberately does not dismiss the keyboard (no
            // imeNestedScroll); tapping the conversation does.
            Column(
                modifier.fillMaxSize()
                    .background(MiuixTheme.colorScheme.background),
            ) {
                if (isRoot) HomeTopBar(onHost, onMenu = {
                    keyboard?.hide()
                    drawerOpen = true
                }) else PageTopBar(onHost)

                Box(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                        detectTapGestures(onTap = { keyboard?.hide() })
                    },
                ) {
                    if (activeSession.messages.isEmpty()) {
                        EvHomeHero(
                            suggestions = Suggestions,
                            onPick = { suggestion ->
                                // An empty prompt is a local action, not a message: today that is
                                // the browser panel, the one suggestion that needs no agent at all.
                                if (suggestion.prompt.isEmpty()) showBrowser = true else send(suggestion.prompt)
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        // Messages present when the session opened render still; only arrivals
                        // after this snapshot play the entrance spring (spec §6.3).
                        val seenIds = remember(activeSession.id) {
                            activeSession.messages.mapTo(hashSetOf()) { it.id }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                            contentPadding = PaddingValues(
                                start = EvChatDefaults.PagePadding,
                                end = EvChatDefaults.PagePadding,
                                top = 8.dp,
                                // The composer is laid out beside the list, not over it, so this is
                                // only breathing room - no reserved composer height.
                                bottom = 16.dp,
                            ),
                        ) {
                            // Declared first so reverseLayout pins it to the bottom: the typing
                            // indicator sits under the newest message, where the reply will land.
                            if (replying) {
                                item { EvTypingIndicator() }
                            }
                            val reversed = activeSession.messages.asReversed()
                            itemsIndexed(reversed, key = { _, it -> it.id }) { index, message ->
                                // Variable rhythm instead of a uniform gap (spec §3.1): same-side
                                // neighbours sit 4dp apart, a side switch opens 12dp.
                                val newer = reversed.getOrNull(index - 1)
                                val gap = when {
                                    index == 0 -> 0.dp
                                    newer?.fromUser == message.fromUser -> EvChatDefaults.SameSideGap
                                    else -> EvChatDefaults.SwitchSideGap
                                }
                                EvMessageEntry(
                                    animate = message.id !in seenIds,
                                    modifier = Modifier.padding(top = gap),
                                ) {
                                    EvChatBubble(
                                        text = message.text,
                                        fromUser = message.fromUser,
                                        events = message.events,
                                        lastInCluster = message.fromUser && newer?.fromUser != true,
                                        streaming = replying && index == 0 && !message.fromUser,
                                        onRegenerate = if (message.fromUser) null else {
                                            { regenerate(message.id) }
                                        },
                                        onDelete = if (message.fromUser) null else {
                                            { delete(message.id) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                EvComposer(
                    value = draft,
                    onValueChange = { draft = it },
                    onSend = { send(draft) },
                    busy = replying,
                    onStop = { stopReply() },
                    modifier = Modifier.imePadding(),
                )
            }
        }
    }

    /** Root: menu on the left, capability dots and real usage chips on the right. */
    @Composable
    private fun HomeTopBar(onHost: () -> Unit, onMenu: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { onMenu() },
                contentAlignment = Alignment.CenterStart,
            ) {
                EvGlyph(
                    EvGlyphKind.Menu,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            CapabilityDots()
            Spacer(Modifier.size(10.dp))
            // The model and spend of the last completed turn; "未接模型" / "— tok" before the first.
            StatChip(lastTurnModel ?: "未接模型")
            Spacer(Modifier.size(8.dp))
            StatChip(lastTurnTokens?.let { "$it tok" } ?: "— tok")
        }
    }

    /**
     * Three tiny LEDs: model / tools / memory, live from the service directory. The lookups are
     * map reads - cheap enough to run in composition, refreshing whenever the page recomposes
     * (a turn ends, the draft changes, a panel opens or closes).
     */
    @Composable
    private fun CapabilityDots() {
        val dir = directory
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapabilityDot(on = dir?.lookup(ModelService.Key) != null)
            CapabilityDot(on = dir?.lookup(ToolRegistry.Key) != null)
            CapabilityDot(on = dir?.lookup(MemoryService.Key) != null)
        }
    }

    @Composable
    private fun CapabilityDot(on: Boolean) {
        Box(
            Modifier
                .padding(horizontal = 2.5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (on) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                ),
        )
    }

    /** Opened as a normal page: the management entry becomes a back arrow. */
    @Composable
    private fun PageTopBar(onBack: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(
                    EvGlyphKind.Send,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = pageTitle,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onBackground,
            )
        }
    }

    @Composable
    private fun StatChip(text: String) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    private companion object {
        /** Shown only when no plugin publishes AgentService: the original placeholder behaviour. */
        const val UNCONNECTED_PREFIX = "（未接模型）我收到了："

        /**
         * Home suggestions, kept honest: every prompt maps to a capability that exists today
         * (the storage file tools, or the built-in browser panel). 分析屏幕 / 打开微信 return when
         * device-context and app-launch tools actually ship.
         */
        val Suggestions = listOf(
            EvSuggestion("列出我的文件", EvGlyphKind.Doc, "列出存储区里有哪些文件"),
            EvSuggestion("记一条笔记", EvGlyphKind.Plus, "帮我把「今晚记得给花浇水」追加到 notes/memo.md"),
            EvSuggestion("你能做什么", EvGlyphKind.Sparkle, "你现在有哪些工具，能帮我做什么？"),
            EvSuggestion("浏览网页", EvGlyphKind.Globe, ""),
        )
    }
}

private fun historyGroups(
    sessions: List<ChatSession>,
    selectedId: String,
    query: String,
): List<EtaHistoryGroup> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val visible = if (query.isBlank()) {
        sessions
    } else {
        sessions.filter { it.title.contains(query, ignoreCase = true) }
    }
    return visible
        .sortedByDescending { it.updatedAt }
        .groupBy { session ->
            val date = Instant.ofEpochMilli(session.updatedAt).atZone(zone).toLocalDate()
            when {
                date == today -> "今天"
                date == today.minusDays(1) -> "昨天"
                else -> "${date.monthValue}月${date.dayOfMonth}日"
            }
        }
        .map { (label, list) ->
            EtaHistoryGroup(
                label = label,
                sessions = list.map { EtaSessionSummary(it.id, it.title, it.id == selectedId) },
            )
        }
}
