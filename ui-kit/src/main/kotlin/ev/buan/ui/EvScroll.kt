package ev.buan.ui

import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.itos.coui.scroll.CouiOverscrollEffect
import com.itos.coui.scroll.rememberCouiOverscrollEffect

/**
 * 列表滚动手感：COUI 的边界阻尼回弹。
 *
 * `rememberCouiOverscrollEffect` 是 COUI 公开出来的那一半——拉过边界时内容会**跟手位移**、
 * 松手弹回，而不是安卓默认那种"硬边 + 一道蓝光"。
 *
 * 另一半（惯性曲线 `rememberCouiScrollFlingBehavior`）在库里是 `internal`，
 * 外部组件拿不到；所以这里只挂 overscroll，fling 用系统默认。
 * 不要为了"配成一对"去反射那个 internal 函数——版本一升就崩，
 * 而缺的那一半只是减速曲线略有差别，不影响回弹本身。
 *
 * ```kotlin
 * val scroll = rememberEvScroll()
 * LazyColumn(Modifier.fillMaxSize().then(scroll.modifier)) { … }
 * ```
 */
class EvScroll internal constructor(val effect: CouiOverscrollEffect) {
    /** 挂到 `LazyColumn` / `Column` 上的滚动修饰符。 */
    val modifier: Modifier = Modifier.overscroll(effect)
}

/** 造一份列表滚动配置。放在页面级记住，别在 item 里调。 */
@Composable
fun rememberEvScroll(): EvScroll {
    val effect = rememberCouiOverscrollEffect()
    return remember(effect) { EvScroll(effect) }
}
