package ev.buan.chat

import com.itos.coui.text.CouiTextKind
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import org.cordis.api.UiSlot
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.itos.coui.chip.ChipTab
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.theme.CouiTheme
import ev.buan.ui.LocalEvBackdrop
import ev.buan.ui.EvButton
import ev.buan.ui.EvCollapsible
import ev.buan.ui.Chevron
import ev.buan.ui.EvColors
import ev.buan.SlotHost
import ev.buan.ui.EvComposer
import ev.buan.ui.EvDivider
import ev.buan.ui.EvEmpty
import ev.buan.ui.EvFilledIconButton
import ev.buan.ui.EvIconButton
import ev.buan.ui.EvIcon
import ev.buan.ui.EvNotice
import ev.buan.ui.EvPageScaffold
import ev.buan.ui.EvSidePage

import ev.buan.ui.EvText
import ev.buan.ui.EvRow
import ev.buan.ui.EvPageInset
import ev.buan.ui.EvSection
import ev.buan.ui.EvTone
import ev.buan.ui.rememberEvScroll
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiShape
import com.itos.coui.shape.CouiCapsuleShape

/**
 * 首页：对话。
 *
 * ## 这一屏只有一个 hero：**正在跑的这一轮**
 *
 * 屏幕上绝大多数东西都是安静的——历史消息用正文、工具块收成一行、输入区不动。
 * 唯一允许复合表达（字重 + 颜色 + 形变 + 弹簧）的是"它现在在干什么"。
 * 上一版所有元素一个音量，于是用户看不出进度，只能一行行读。
 *
 * ## 没有顶部栏
 *
 * 两个悬浮圆压在内容之上：更多在左、加号在右。它们不占布局高度，
 * 内容从下面穿过去——这样多显示一行消息，而不是永远空着一条带子。
 */
@Composable
fun EvChatScreen(
    state: ChatState,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sideOpen by remember { mutableStateOf(false) }
    // 胶囊点开的是**浮在这一页上**的小面板：要的是"看一眼就走"，
    // 不是离开当前对话去另一个页面。
    var details by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // 只有已经贴底时才跟着滚：用户往上翻历史时不要抢位置。
            if (last >= state.items.size - 2) listState.animateScrollToItem(state.items.size - 1)
        }
    }

    // 菜单里只放真实字段：模型名、进程号、运行时长、上下文规模。
    val status = remember(details) { state.runtimeStatus() }
    val agentItems = remember(details, status, state.items.size, state.modelLabel) {
        buildList {
            add(CouiMenuItem(value = "model", text = "模型 · ${state.modelLabel ?: "未连接"}", enabled = false))
            add(
                CouiMenuItem(
                    value = "pid",
                    text = status?.pid?.let { "进程 · PID $it" } ?: "进程 · 未启动",
                    enabled = false,
                ),
            )
            val startedAt = status?.startedAtMillis
            if (startedAt != null) {
                val seconds = (System.currentTimeMillis() - startedAt) / 1000
                add(CouiMenuItem(value = "uptime", text = "已运行 · ${seconds}s", enabled = false))
            }
            add(
                CouiMenuItem(
                    value = "context",
                    text = "上下文 · ${state.items.count { it is ChatItem.User }} 轮",
                    enabled = false,
                ),
            )
            add(CouiMenuItem(value = "agent", text = "打开 Agent 页", dividerBefore = true))
        }
    }

    EvPageScaffold(
        modifier = modifier,
        // 顶部两个是次要入口，用 EvIconButton 而不是 EvFab：Fab 是"这一屏唯一的主操作"，
        // 56dp 的玻璃球压在最上层会盖过内容，而且这片背后是空白，材质采样不到东西。
        floating = {
            // 侧页打开时不画这两个：它们是页面的悬浮入口，而侧页是模态的。
            // 留着的话"更多"正好压在侧页标题上，等于按钮把面板咬掉一口。
            if (!sideOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = EvPageInset, end = EvPageInset, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EvIconButton(EvIcon.More, "更多", onClick = { sideOpen = true })
                    // 胶囊直接就是 COUI 的 ChipTab：超椭圆、玻璃材质、按下形变都由它给，
                    // 连点开时的锚点也用同一个 Composable 的坐标，不需要量像素。
                    val anchor = rememberCouiMenuAnchor()
                    val chipBackdrop = LocalEvBackdrop.current
                    Box(Modifier.couiMenuAnchor(anchor)) {
                        if (chipBackdrop != null) {
                            ChipTab(
                                selected = details,
                                onClick = { details = !details },
                                label = state.modelLabel ?: "未连接",
                                backdrop = chipBackdrop,
                                dark = CouiTheme.isDark,
                            )
                        } else {
                            // 没有 backdrop 就没有玻璃可采样；退回素胶囊，但仍是同一个锚点。
                            Box(
                                Modifier
                                    .clip(CouiCapsuleShape)
                                    .background(EvColors.surface1)
                                    .clickable { details = !details }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                EvText(state.modelLabel ?: "未连接", kind = CouiTextKind.PreferenceSummary)
                            }
                        }
                    }
                    Menu(
                        expanded = details,
                        onDismissRequest = { details = false },
                        items = agentItems,
                        onItemClick = { item ->
                            if (item.value == "agent") {
                                details = false
                                onOpenAgent()
                            }
                        },
                        anchor = anchor,
                    )
                    EvIconButton(EvIcon.NewChat, "新建对话", onClick = { state.newConversation() })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            val scroll = rememberEvScroll()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(scroll.modifier),
                    contentPadding = PaddingValues(start = 0.dp, end = 0.dp, top = 56.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 首页顶部主视觉：可替换槽位，同一时刻只有一个赢家。
                    item { SlotHost(UiSlot.HomeHero) }
                    if (state.items.isEmpty()) {
                        item {
                            // 空态不是"居中一句话"，是这一页的**页头**。
                            //
                            // 上一版把它垂直居中：上下各空 750px，四块东西浮在黑色里。
                            // 那不是留白，是没填。现在贴着顶部排——页头在上、入口在中段、
                            // 输入框在底部，三段间距 88 / 44 / 弹性，全部走 8 的倍数。
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .fillParentMaxHeight(1f)
                                    .padding(horizontal = EvPageInset),
                            ) {
                                // 页头：贴着顶部。空态不是"居中一句话"，是这一页的题头。
                                Column(Modifier.padding(top = 72.dp)) {
                                    EvText(
                                        if (state.connected) "说点什么，它就动手。" else "还没连上对话插件。",
                                        kind = CouiTextKind.PageTitle,
                                        tone = EvTone.Normal,
                                        weight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    EvText(
                                        if (state.connected) "截屏、读屏、点按——它用手机上的手。" else "连接后就可以让 EV 动手。",
                                        kind = CouiTextKind.Body,
                                        tone = EvTone.Muted,
                                    )
                                }
                                // 入口紧跟题头，不往下飘。
                                //
                                // 上一版把它压在 62% 高度（两个 weight 上下夹），想让空白"分成两段"，
                                // 结果题头和入口之间出现一道 800px 的裂缝：读起来是"上面一句话、
                                // 下面一坨列表"，中间断了。这一屏的主角是对话区，空本来就该
                                // 整块留在下面——入口贴着题头，空就成了一整块"还没说话的舞台"。
                                Spacer(Modifier.height(40.dp))
                                if (state.connected) {
                                    // 三个入口不套卡片：一张卡片会把三行压成一个"块"，
                                    // 而它们本来就是三个并列的出口。无卡片的行 + 从文字起笔的分隔线，
                                    // 是这一屏唯一需要的容器。
                                    Column(Modifier.fillMaxWidth()) {
                                        HomeEntry("插件", "装了什么、开着没", EvIcon.Apps, onOpenPlugins)
                                        EvDivider(startOffset = 52.dp, endOffset = 52.dp)
                                        HomeEntry("权限", "它现在能碰到什么", EvIcon.Shield, onOpenPermissions)
                                        EvDivider(startOffset = 52.dp, endOffset = 52.dp)
                                        HomeEntry("设置", "外观、模型与进程", EvIcon.Setting, onOpenSettings)
                                    }
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    items(state.items, key = { "${it::class.simpleName}-${it.id}" }) { item ->
                        when (item) {
                            is ChatItem.User -> UserBubble(item.text)
                            is ChatItem.Assistant -> {
                                EvText(
                                    item.text,
                                    kind = CouiTextKind.Body,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SlotHost(UiSlot.MessageActions)
                            }

                            is ChatItem.Tool -> EvCollapsible(
                                title = item.name,
                                summary = item.preview ?: item.argsJson,
                                tone = item.tone,
                            ) {
                                EvText(item.argsJson, kind = CouiTextKind.Description, tone = EvTone.Muted)
                            }

                            is ChatItem.Notice -> EvNotice(item.text, tone = EvTone.Warning)
                        }
                    }
                }

                state.notice?.let { notice ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 8.dp)) {
                        EvNotice(notice, tone = EvTone.Warning)
                    }
                }
            }

            SlotHost(UiSlot.HomeAboveComposer, Modifier.padding(horizontal = EvPageInset))
            EvComposer(
                value = state.input,
                onValueChange = state::onInputChange,
                modifier = Modifier.padding(horizontal = EvPageInset, vertical = 14.dp),
            ) {
                if (state.sending) {
                    // 跑着的时候是"停下来"。同一个组件换图标语义，
                    // 不去改它的颜色——危险色应该留给"会丢东西"的操作。
                    EvFilledIconButton(
                        icon = EvIcon.Close,
                        label = "停止",
                        onClick = state::stop,
                    )
                } else {
                    EvFilledIconButton(
                        icon = EvIcon.Send,
                        label = "发送",
                        onClick = state::send,
                        enabled = state.input.isNotBlank(),
                    )
                }
            }
        }

        // 侧页的 hero 是"这台机器现在什么状态"：连接与否决定这一屏能不能用。
        // 三行入口只是它下面的动作，不该和状态平起平坐。
        EvSidePage(visible = sideOpen, onDismiss = { sideOpen = false }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
                EvText("EV", kind = CouiTextKind.Description, tone = EvTone.Muted)
                Spacer(Modifier.height(10.dp))
                EvText(
                    if (state.connected) "已连接" else "还没连上",
                    kind = CouiTextKind.PageTitle,
                )
                Spacer(Modifier.height(6.dp))
                EvText(
                    if (state.connected) "说点什么，它就动手。" else "对话插件还没就位。",
                    kind = CouiTextKind.PreferenceSummary,
                    tone = EvTone.Muted,
                )
            }
            EvDivider()
            Spacer(Modifier.height(6.dp))
            SideEntry("文件管理", EvIcon.Folder) { onOpenFiles(); sideOpen = false }
            SideEntry("权限中心", EvIcon.Shield) { onOpenPermissions(); sideOpen = false }
            SideEntry("插件管理", EvIcon.Apps) { onOpenPlugins(); sideOpen = false }
            // Agent 的运行时状态单独一页：进程、参数、重启——它比"插件是否启用"更需要被看清。
            SideEntry("Agent", EvIcon.Apps) { onOpenAgent(); sideOpen = false }
            SideEntry("设置", EvIcon.Setting) { onOpenSettings(); sideOpen = false }
            SlotHost(
                UiSlot.DrawerItems,
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            EvText(
                if (state.connected) "已连接 · EV 0.1.0" else "未连接 · EV 0.1.0",
                kind = CouiTextKind.Description,
                tone = EvTone.Muted,
                modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
            )
        }
    }
}

/** 用户消息：右对齐的窄气泡。一眼能找到"我说了什么"。 */
@Composable
private fun UserBubble(text: String) {
    val colors = EvColors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(colors.surface2, CouiShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            EvText(text, kind = CouiTextKind.Body)
        }
    }
}

/** 侧页的一行：图标 + 标题。图标压在左侧 19dp，标题从同一条基线上开始。 */
@Composable
private fun SideEntry(label: String, icon: EvIcon, onClick: () -> Unit) {
    val colors = EvColors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberVectorPainter(EvIcon(icon)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.textSecondary),
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(14.dp))
        EvText(label, kind = CouiTextKind.Body)
    }
}

/** 浮层里的一行：左边标签，右边值。 */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        EvText(label, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted)
        EvText(value, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Normal)
    }
}

/**
 * 首页的一个入口。
 *
 * 用 COUI 的 `Item`，但**不套 `Group`**：Group 会给整组加一层卡片底，
 * 三行并排的出口因此被压成一个灰块，右边还空一大半。
 * 单行 + 一条分隔线是本屏需要的全部容器。
 */
@Composable
private fun HomeEntry(
    title: String,
    summary: String,
    icon: EvIcon,
    onClick: () -> Unit,
) {
    EvRow(
        title = title,
        summary = summary,
        icon = icon,
        onClick = onClick,
        // 每行上下各 10dp：COUI 的行高是给"设置列表"设计的，
        // 二十行挤在一页里正合适；这里总共只有三行，撑满一屏需要更大的呼吸。
        modifier = Modifier.padding(vertical = 10.dp),
        trailing = { Chevron() },
    )
}
