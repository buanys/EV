package ev.buan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons

/**
 * 把 [EvIcon] 解析成实际图形。
 *
 * 顺序是：**主题给的 → 框架默认**。主题没覆盖就回落到 COUI 的那一套，
 * 所以"换图标集"只需要提供一个 `EvIconSet`，页面一行都不用动。
 *
 * 函数而不是 object 的取值器：COUI 的图标是 `@Composable` getter（从 drawable 加载），
 * 只能在组合里读。写成 `@Composable fun` 让这个限制显式可见，而不是藏在属性里。
 */
@Composable
fun EvIcon(name: EvIcon): ImageVector {
    LocalEvIconSet.current.icon(name)?.let { return it }
    return when (name) {
        EvIcon.Back -> CouiIcons.Outlined.Back
        EvIcon.Close -> CouiIcons.Outlined.Close
        EvIcon.Add -> CouiIcons.Outlined.Add
        EvIcon.More -> CouiIcons.Outlined.More
        EvIcon.Search -> CouiIcons.Outlined.Search
        EvIcon.Setting -> CouiIcons.Filled.Setting
        EvIcon.Mic -> CouiIcons.Filled.Microphone
        EvIcon.Bell -> CouiIcons.Filled.Bell
        // COUI 的图标集里没有箭头，自己画一个：竖线 + 两边斜线，端点是圆的。
        // 用描边而不是填充，18dp 下笔画的粗细才不会糊成一坨。
        EvIcon.Send -> EvSendArrow
        // 一律用 COUI 自带图标：自家画的线条粗细、圆角、光学尺寸都和它不一致，
        // 混在一起就是"两套图标"的样子。
        EvIcon.Apps -> CouiIcons.Filled.Grid
        EvIcon.NewChat -> CouiIcons.Outlined.WandSpark
        EvIcon.Shield -> CouiIcons.Filled.ShieldCheck
        // 抽屉里的图标必须**同一重量**：`BookOpen` 是描边、`AI` 是细花瓣，
        // 和 `ShieldCheck`／`Grid`／`Setting` 这三个实心图标并排时，
        // 一列五个里两个轻三个重——眼睛会以为是两种状态。
        // 换成两个实心的：SafeBox（存储/文件）、Radar（感知，Agent 的语义也对得上）。
        EvIcon.Folder -> CouiIcons.Filled.SafeBox
        EvIcon.Agent -> CouiIcons.Filled.Radar
    }
}

/** 文件管理：一个文件夹（带页签的轮廓，20dp 下仍读得出是"目录"）。 */
private val EvFolderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EvFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(3f, 7f)
                lineTo(9.5f, 7f)
                lineTo(11.5f, 9.2f)
                lineTo(21f, 9.2f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/**
 * 权限中心：一面盾。
 *
 * 用六边形直线画，不用曲线：20dp 下曲率会糊成一块，直线反而读得出"盾"。
 */
private val EvShieldIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EvShield",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(12f, 3f)
                lineTo(20f, 6f)
                lineTo(20f, 12f)
                lineTo(12f, 21f)
                lineTo(4f, 12f)
                lineTo(4f, 6f)
                close()
            },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/**
 * 新建对话：一支笔。
 *
 * 加号只表达"添加"，表达不了"新写一条"——IM 里这个位置放的一直是笔。
 * 20dp 下只描一个五边形轮廓，不加笔尖分界线：多一条线就糊成一团。
 */
private val EvNewChatIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EvNewChat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(4f, 20f)
                lineTo(4f, 15.6f)
                lineTo(15.6f, 4f)
                lineTo(20f, 8.4f)
                lineTo(8.4f, 20f)
                close()
            },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/**
 * 四宫格（插件管理用）。四个实心小方块而不是描边：小尺寸下描边会挤成一片，
 * 实心块之间的负空间本身就是图形。
 */
private val EvAppsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EvApps",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
                moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
                moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(14f); close()
            },
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** 发送箭头（↑）。24 的视口，描边 2.2，圆头圆角，缩到 18dp 仍然干净。 */
private val EvSendArrow: ImageVector by lazy {
    ImageVector.Builder(
        name = "EvSendArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(12f, 19f)
                lineTo(12f, 5.5f)
                moveTo(5.5f, 12f)
                lineTo(12f, 5.5f)
                lineTo(18.5f, 12f)
            },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
