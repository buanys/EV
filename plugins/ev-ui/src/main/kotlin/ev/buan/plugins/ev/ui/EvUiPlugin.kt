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
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.ev.ui.ui.EvBrowserScreen
import ev.buan.plugins.ev.ui.ui.EvCharactersScreen
import ev.buan.plugins.ev.ui.ui.EvDrawerContent
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
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvIcons
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
    private var replyingSessionId by mutableStateOf<String?>(null)

    /** Live rounds of the in-flight turn; empty when nothing is running. */
    private var liveRounds by mutableStateOf<List<RoundTrace>>(emptyList())

    /**
     * Three dots mean exactly one thing: the model is thinking. While a tool runs there is real
     * progress to show, so the dots disappear instead of animating over work that has an answer.
     */
    private var awaitingModel by mutableStateOf(false)

    /** True during the wrap-up summary call, so the dots can say what they are waiting for. */
    private var finalizing by mutableStateOf(false)

    /** The job behind each in-flight turn, so the composer's stop button can cancel exactly one. */
    private val replyJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * The approval gate this UI publishes for the brain's dangerous tools. Plugin-scoped like
     * [sessions]: both surfaces show the same prompt, and one tap answers the one waiting call.
     */
    private val approvalGate = ApprovalGate()

    /** Last completed turn's token spend, shown in the model capsule's detail card. */
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
        // The composer's `+` panel and the top bar's model detail card: both are L1 surfaces that
        // spring out of the control that summoned them and close on any outside tap.
        var plusPanelOpen by remember { mutableStateOf(false) }
        var modelCardOpen by remember { mutableStateOf(false) }
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
        // Which live rounds the user opened (true) or closed (false); absent means "auto", i.e.
        // expanded while running and collapsed once done.
        val roundExpansion = remember(activeSession.id) { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

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
            liveRounds = emptyList()
            awaitingModel = true
            finalizing = false
            val job = scope.launch {
                try {
                    val agent = directory?.lookup(AgentService.Key)
                    if (agent == null) {
                        delay(420)
                        append(sessionId, assistantMessage(UNCONNECTED_PREFIX + prompt))
                    } else {
                        var finished: AgentReply? = null
                        var rounds = emptyList<RoundTrace>()
                        agent.sendStreaming(
                            sessionId = sessionId,
                            text = prompt,
                            systemPrompt = sessionSystemPrompt(
                                characters = characters,
                                skills = skills,
                                session = sessions.firstOrNull { it.id == sessionId },
                            ),
                        ).collect { progress ->
                            when (progress) {
                                is AgentProgress.RoundStarted -> {
                                    awaitingModel = false
                                    rounds = reduce(rounds, progress)
                                    liveRounds = rounds
                                }

                                is AgentProgress.ToolStarted,
                                is AgentProgress.ToolAwaitingApproval,
                                is AgentProgress.ToolFinished,
                                -> {
                                    awaitingModel = false
                                    rounds = reduce(rounds, progress)
                                    liveRounds = rounds
                                }

                                is AgentProgress.RoundFinished -> {
                                    // The next thing that happens is another model call.
                                    awaitingModel = true
                                    rounds = reduce(rounds, progress)
                                    liveRounds = rounds
                                }

                                is AgentProgress.Finalizing -> {
                                    awaitingModel = true
                                    finalizing = true
                                    rounds = reduce(rounds, progress)
                                    liveRounds = rounds
                                }

                                is AgentProgress.Done -> finished = progress.reply
                            }
                        }
                        val reply = finished ?: error("智能体没有返回最终结果")
                        val answer = reply.message.content.ifBlank { "（模型没有返回文本）" }
                        lastTurnTokens = reply.usage?.totalTokens
                        append(
                            sessionId,
                            assistantMessage(
                                answer,
                                // New messages are summarised by `traces`; carrying the flat
                                // event list as well would render the same work twice after a
                                // reload. Old messages keep their events and render as before.
                                events = emptyList(),
                                stopNote = stopNoteOf(reply.stop),
                                traces = rounds.map { it.toSummary() },
                            ),
                        )
                    }
                } catch (cancel: CancellationException) {
                    // The stop button lands here: close the spinners first, otherwise they would
                    // keep turning forever because no ToolFinished will ever arrive.
                    liveRounds = cancelOutstanding(liveRounds)
                    append(sessionId, assistantMessage("（已停止）"))
                    throw cancel
                } catch (error: Throwable) {
                    append(
                        sessionId,
                        assistantMessage("（对话失败）${error.message ?: error::class.simpleName}"),
                    )
                } finally {
                    if (replyingSessionId == sessionId) replyingSessionId = null
                    liveRounds = emptyList()
                    awaitingModel = false
                    finalizing = false
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

        /** One fresh conversation, selected immediately - shared by the drawer and the top bar. */
        fun newChat() {
            val fresh = newSession()
            sessions += fresh
            persist()
            activeSessionId = fresh.id
            keyboard?.hide()
            drawerOpen = false
            searching = false
            searchQuery = ""
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
            // Follow the newest entry: the typing indicator when it is showing, otherwise the last
            // message. The list is top-aligned now, so "newest" is the bottom of the content.
            val last = activeSession.messages.lastIndex + if (replying) 1 else 0
            if (activeSession.messages.isNotEmpty() && last >= 0) {
                listState.animateScrollToItem(last)
            }
        }

        val appearanceService = appearance
        val child = settingsChild
        if (showSettings && appearanceService != null) {
            if (child != null) {
                // A second-level settings page: back returns to the settings root, not to the chat,
                // and the system back button walks the same path (see the BackHandler above).
                when (child) {
                    SettingsChild.Models -> EvModelProvidersScreen(
                        service = { directory?.lookup(ModelProviderService.Key) },
                        onBack = { settingsChild = null },
                        modifier = modifier,
                    )

                    SettingsChild.Tools -> EvToolsScreen(
                        registry = { directory?.lookup(ToolRegistry.Key) },
                        onBack = { settingsChild = null },
                        modifier = modifier,
                    )

                    SettingsChild.Memory -> EvMemoryScreen(
                        sessionId = activeSession.id,
                        memory = { directory?.lookup(MemoryService.Key) },
                        onBack = { settingsChild = null },
                        modifier = modifier,
                    )

                    SettingsChild.Characters -> EvCharactersScreen(
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

                    SettingsChild.Skills -> EvSkillsScreen(
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
            EvSettingsScreen(
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
            EvToolsScreen(
                registry = { directory?.lookup(ToolRegistry.Key) },
                onBack = { showTools = false },
                modifier = modifier,
            )
            return
        }

        if (showBrowser) {
            EvBrowserScreen(onBack = { showBrowser = false }, modifier = modifier)
            return
        }

        if (showMemory) {
            // The memory plugin may be enabled, disabled or reconfigured while the page is open: the
            // service is looked up on every read, and the panel has a refresh action for exactly that.
            EvMemoryScreen(
                sessionId = activeSession.id,
                memory = { directory?.lookup(MemoryService.Key) },
                onBack = { showMemory = false },
                modifier = modifier,
            )
            return
        }

        if (showCharacters) {
            EvCharactersScreen(
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
            EvSkillsScreen(
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
                EvDrawerContent(
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
                    onNewChat = { newChat() },
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
                        // Ev's own settings page, inside this plugin: the host page stays reachable
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
                if (isRoot) {
                    // Live lookup per composition (same cheap map read the drawer's status line
                    // uses): the capsule mirrors whatever provider the contract resolves as current.
                    val currentModel = directory?.lookup(ModelService.Key)?.models?.firstOrNull()?.model
                    HomeTopBar(
                        modelName = currentModel,
                        approvalPending = approvalGate.pending.isNotEmpty(),
                        onMenu = {
                            keyboard?.hide()
                            modelCardOpen = false
                            drawerOpen = true
                        },
                        onCapsule = {
                            keyboard?.hide()
                            modelCardOpen = !modelCardOpen
                        },
                        onNewChat = { newChat() },
                        onMore = {
                            keyboard?.hide()
                            settingsChild = null
                            showSettings = true
                        },
                    )
                } else {
                    PageTopBar(onHost)
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            keyboard?.hide()
                            modelCardOpen = false
                        })
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
                            contentPadding = PaddingValues(
                                start = EvChatDefaults.PagePadding,
                                end = EvChatDefaults.PagePadding,
                                top = 8.dp,
                                // The composer is laid out beside the list, not over it, so this is
                                // only breathing room - no reserved composer height.
                                bottom = 16.dp,
                            ),
                        ) {
                            // Top-aligned, oldest first: a short conversation grows down from under
                            // the top bar instead of floating above the composer (spec §3.4).
                            val ordered = activeSession.messages
                            itemsIndexed(ordered, key = { _, it -> it.id }) { index, message ->
                                // Variable rhythm instead of a uniform gap (spec §3.1): same-side
                                // neighbours sit 4dp apart, a side switch opens 12dp.
                                val previous = ordered.getOrNull(index - 1)
                                val next = ordered.getOrNull(index + 1)
                                val gap = when {
                                    index == 0 -> 0.dp
                                    previous?.fromUser == message.fromUser -> EvChatDefaults.SameSideGap
                                    else -> EvChatDefaults.SwitchSideGap
                                }
                                EvMessageEntry(
                                    animate = message.id !in seenIds,
                                    modifier = Modifier.padding(top = gap),
                                ) {
                                  Column {
                                    message.traces.forEach { trace -> EvTraceSummaryBlock(trace) }
                                    EvChatBubble(
                                        text = message.text,
                                        fromUser = message.fromUser,
                                        events = if (message.traces.isEmpty()) message.events else emptyList(),
                                        lastInCluster = message.fromUser && next?.fromUser != true,
                                        streaming = replying && index == ordered.lastIndex && !message.fromUser,
                                        onRegenerate = if (message.fromUser) null else {
                                            { regenerate(message.id) }
                                        },
                                        onDelete = if (message.fromUser) null else {
                                            { delete(message.id) }
                                        },
                                    )
                                    message.stopNote?.let { note ->
                                        Text(
                                            text = note,
                                            style = EvThemeTokens.type.caption.style(
                                                EvThemeTokens.colors.textTertiary,
                                            ),
                                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                        )
                                    }
                                  }
                                }
                            }
                            // Live rounds first: they appear while the agent works, in the spot
                            // the reply will occupy. Each round auto-expands while running and
                            // collapses to one line when it ends - unless the user opened it.
                            liveRounds.forEach { live ->
                                val auto = live.state == RoundState.Running
                                val expandedNow = roundExpansion.value[live.round] ?: auto
                                item(key = "live-${live.round}") {
                                    EvRoundBlock(
                                        round = live,
                                        expanded = expandedNow,
                                        onToggle = {
                                            roundExpansion.value = roundExpansion.value.toMutableMap().apply {
                                                put(live.round, !expandedNow)
                                            }
                                        },
                                        modifier = Modifier.padding(
                                            start = EvChatDefaults.PagePadding,
                                            end = EvChatDefaults.PagePadding,
                                        ),
                                    )
                                }
                            }
                            // Dots mean one thing only: the model is thinking. While a tool runs
                            // there is real progress on screen, so they disappear.
                            if (replying && awaitingModel) {
                                item { EvTypingIndicator(label = if (finalizing) "正在总结…" else null) }
                            }
                        }
                    }

                    // The capsule's detail card (spec §5.2): overlays the conversation instead of
                    // pushing it, springs down from the top bar, and any outside tap dismisses it.
                    // Token spend lives here now - no longer a permanent top-bar resident.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = modelCardOpen,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
                            fadeIn(EvMotionTokens.fadeFast()),
                        exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
                            fadeOut(EvMotionTokens.fadeFast()),
                    ) {
                        ModelDetailCard(
                            modelName = directory?.lookup(ModelService.Key)?.models?.firstOrNull()?.model,
                            lastTokens = lastTurnTokens,
                            onSwitchModel = {
                                modelCardOpen = false
                                settingsChild = SettingsChild.Models
                                showSettings = true
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                // The `+` panel grows upward out of the composer's own slot, so panel and composer
                // share one vertical boundary while it springs open (spec §7.3).
                androidx.compose.animation.AnimatedVisibility(
                    visible = plusPanelOpen,
                    enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
                        fadeIn(EvMotionTokens.fadeFast()),
                    exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
                        fadeOut(EvMotionTokens.fadeFast()),
                ) {
                    EvComposerPanel(
                        actions = listOf(
                            EvComposerAction(EvGlyphKind.Globe, "浏览器") {
                                plusPanelOpen = false
                                showBrowser = true
                            },
                            EvComposerAction(EvGlyphKind.Tool, "工具") {
                                plusPanelOpen = false
                                showTools = true
                            },
                            EvComposerAction(EvGlyphKind.Person, "角色") {
                                plusPanelOpen = false
                                showCharacters = true
                            },
                            EvComposerAction(EvGlyphKind.Sparkle, "Skills") {
                                plusPanelOpen = false
                                showSkills = true
                            },
                        ),
                    )
                }

                EvComposer(
                    value = draft,
                    onValueChange = { draft = it },
                    onSend = { send(draft) },
                    // Placeholder rotates per conversation, not per recomposition (spec §7.3).
                    placeholder = ComposerPlaceholders[
                        Math.floorMod(activeSession.id.hashCode(), ComposerPlaceholders.size)
                    ],
                    busy = replying,
                    onStop = { stopReply() },
                    plusActive = plusPanelOpen,
                    onPlus = {
                        // Opening the panel trades the keyboard for it - they never share the screen.
                        if (!plusPanelOpen) keyboard?.hide()
                        plusPanelOpen = !plusPanelOpen
                    },
                    onFocusChange = { focused -> if (focused) plusPanelOpen = false },
                    modifier = Modifier.imePadding(),
                )
            }
        }
    }

    /**
     * The detail card under the model capsule: what model is live, what the last turn cost, and
     * the one-tap way into model switching. Reading the directory again here is deliberate - the
     * card always shows the state of this very moment, not a snapshot from when it was opened.
     */
    @Composable
    private fun ModelDetailCard(
        modelName: String?,
        lastTokens: Int?,
        onSwitchModel: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val tokens = EvThemeTokens.colors
        Column(
            modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                .background(tokens.surface3)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "当前模型",
                style = EvThemeTokens.type.micro.style(tokens.textTertiary),
            )
            Text(
                text = modelName ?: "未接模型",
                style = EvThemeTokens.type.title.style(if (modelName != null) tokens.textPrimary else tokens.textTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "上轮消耗 ${lastTokens?.let { "$it tok" } ?: "—"}",
                style = EvThemeTokens.type.caption.style(tokens.textSecondary),
                modifier = Modifier.padding(top = 6.dp),
            )
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(tokens.dividerGhost),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(EvThemeTokens.radii.small))
                    .clickable(onClickLabel = "切换模型", onClick = onSwitchModel)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvGlyph(EvGlyphKind.Atom, color = tokens.accent, modifier = Modifier.size(16.dp))
                Text(
                    text = "切换模型",
                    style = EvThemeTokens.type.label.style(tokens.accent),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    /**
     * The three-segment top bar (spec §5.2): drawer entry on the left, the context capsule in the
     * middle, and one capsule of actions on the right. The capsule reads the current model live;
     * tapping it opens the detail card, and the spend chip that used to sit here moved inside it.
     */
    @Composable
    private fun HomeTopBar(
        modelName: String?,
        approvalPending: Boolean,
        onMenu: () -> Unit,
        onCapsule: () -> Unit,
        onNewChat: () -> Unit,
        onMore: () -> Unit,
    ) {
        val tokens = EvThemeTokens.colors
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "菜单", onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(EvGlyphKind.Menu, color = tokens.textPrimary, modifier = Modifier.size(20.dp))
                // An approval waiting on the human is the one thing worth a badge here.
                if (approvalPending) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(tokens.accent),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(tokens.surface2)
                    .clickable(onClickLabel = "模型状态", onClick = onCapsule)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (modelName != null) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(tokens.success),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = modelName ?: "未接模型",
                    style = EvThemeTokens.type.caption.style(
                        if (modelName != null) tokens.textPrimary else tokens.textTertiary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(tokens.surface2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "新会话", onClick = onNewChat),
                    contentAlignment = Alignment.Center,
                ) {
                    EvGlyph(EvGlyphKind.Plus, color = tokens.textPrimary, modifier = Modifier.size(18.dp))
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "更多", onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    EvGlyph(EvGlyphKind.More, color = tokens.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    /** Opened as a normal page: the management entry becomes a back arrow. */
    @Composable
    private fun PageTopBar(onBack: () -> Unit) {
        val tokens = EvThemeTokens.colors
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clickable(onClickLabel = "返回") { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = EvIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = tokens.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = pageTitle,
                style = EvThemeTokens.type.title.style(tokens.textPrimary),
            )
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
): List<EvHistoryGroup> {
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
            EvHistoryGroup(
                label = label,
                sessions = list.map { EvSessionSummary(it.id, it.title, it.id == selectedId) },
            )
        }
}
