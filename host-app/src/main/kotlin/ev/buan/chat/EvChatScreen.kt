package ev.buan.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import org.cordis.api.UiSlot
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvButton
import ev.buan.ui.EvCollapsible
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
import ev.buan.ui.EvTextKind
import ev.buan.ui.EvTone

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
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // 只有已经贴底时才跟着滚：用户往上翻历史时不要抢位置。
            if (last >= state.items.size - 2) listState.animateScrollToItem(state.items.size - 1)
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
                Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    EvIconButton(
                        EvIcon.More,
                        "更多",
                        onClick = { sideOpen = true },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .background(EvColors.surface1, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        EvText(
                            if (state.connected) "新对话" else "新对话 · 未连接",
                            kind = EvTextKind.Caption,
                            tone = EvTone.Muted,
                        )
                    }
                    EvIconButton(
                        EvIcon.NewChat,
                        "新建对话",
                        onClick = { state.newConversation() },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 56.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 首页顶部主视觉：可替换槽位，同一时刻只有一个赢家。
                    item { SlotHost(UiSlot.HomeHero) }
                    if (state.items.isEmpty()) {
                        item {
                            // 空态居视口中心：贴在顶部会让整块屏幕看起来"掉了东西"，
                            // 居中的一行字是这一屏唯一的元素，位置就是它的语义。
                            Box(Modifier.fillMaxWidth().fillParentMaxHeight(), contentAlignment = Alignment.Center) {
                                // 空态是这一屏唯一的视觉锚点，用 Title 而不是 Caption：
                                // 13sp 的灰字在这块黑色里读起来像"内容加载失败"。
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    EvText(
                                        if (state.connected) "说点什么，它就动手。" else "还没连上对话插件。",
                                        kind = EvTextKind.Title,
                                        tone = EvTone.Normal,
                                    )
                                    EvText(
                                        if (state.connected) "当前对话已经准备好。" else "连接后就可以让 EV 动手。",
                                        kind = EvTextKind.Caption,
                                        tone = EvTone.Muted,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
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
                                    kind = EvTextKind.Body,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SlotHost(UiSlot.MessageActions)
                            }

                            is ChatItem.Tool -> EvCollapsible(
                                title = item.name,
                                summary = item.preview ?: item.argsJson,
                                tone = item.tone,
                            ) {
                                EvText(item.argsJson, kind = EvTextKind.Micro, tone = EvTone.Muted)
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

            SlotHost(UiSlot.HomeAboveComposer, Modifier.padding(horizontal = 16.dp))
            EvComposer(
                value = state.input,
                onValueChange = state::onInputChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                EvText("EV", kind = EvTextKind.Micro, tone = EvTone.Muted)
                Spacer(Modifier.height(10.dp))
                EvText(
                    if (state.connected) "已连接" else "还没连上",
                    kind = EvTextKind.Headline,
                )
                Spacer(Modifier.height(6.dp))
                EvText(
                    if (state.connected) "说点什么，它就动手。" else "对话插件还没就位。",
                    kind = EvTextKind.Caption,
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
                kind = EvTextKind.Micro,
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
                .background(colors.surface2, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            EvText(text, kind = EvTextKind.Body)
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
        EvText(label, kind = EvTextKind.Body)
    }
}
