package ev.buan.ui

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 控件与动作：顶栏、工具栏、输入框、列表行尾会出现的那些标记。
 *
 * 绘制规则在 `EvGlyphVectors.kt`：24 格、1.7dp 描边、只用圆/圆角矩形/直线。这个文件只放
 * 词汇，不放语法。
 */

/** The drawer. Three rules of equal weight: the least clever way to say "menu", so nobody misreads it. */
val EvIconMenu: ImageVector = glyph("Menu") {
    line {
        moveTo(4.5f, 7.5f); lineTo(19.5f, 7.5f)
        moveTo(4.5f, 12f); lineTo(19.5f, 12f)
        moveTo(4.5f, 16.5f); lineTo(19.5f, 16.5f)
    }
}

val EvIconPlus: ImageVector = glyph("Plus") {
    line {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }
}

val EvIconMic: ImageVector = glyph("Mic") {
    line {
        moveTo(9f, 11f); lineTo(9f, 6f)
        arcTo(3f, 3f, 0f, true, true, 15f, 6f)
        lineTo(15f, 11f)
        arcTo(3f, 3f, 0f, true, true, 9f, 11f)
        moveTo(5.5f, 11f)
        arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f)
        moveTo(12f, 17.5f); lineTo(12f, 20.5f)
    }
}

val EvIconSend: ImageVector = glyph("Send") {
    line {
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f)
    }
}

val EvIconMore: ImageVector = glyph("More") {
    dot { circle(12f, 5.6f, 1.6f) }
    dot { circle(12f, 12f, 1.6f) }
    dot { circle(12f, 18.4f, 1.6f) }
}

val EvIconGrid: ImageVector = glyph("Grid") {
    line {
        moveTo(4.5f, 4.5f); lineTo(10f, 4.5f); lineTo(10f, 10f); lineTo(4.5f, 10f); close()
        moveTo(14f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 10f); lineTo(14f, 10f); close()
        moveTo(4.5f, 14f); lineTo(10f, 14f); lineTo(10f, 19.5f); lineTo(4.5f, 19.5f); close()
        moveTo(14f, 14f); lineTo(19.5f, 14f); lineTo(19.5f, 19.5f); lineTo(14f, 19.5f); close()
    }
}

/** Settings: a dial with teeth cut into the ring, drawn as eight spokes so it stays crisp at 18dp. */
val EvIconGear: ImageVector = glyph("Gear") {
    line {
        moveTo(12f, 3.5f); lineTo(12f, 6f)
        moveTo(12f, 18f); lineTo(12f, 20.5f)
        moveTo(3.5f, 12f); lineTo(6f, 12f)
        moveTo(18f, 12f); lineTo(20.5f, 12f)
        moveTo(6f, 6f); lineTo(7.8f, 7.8f)
        moveTo(16.2f, 16.2f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(16.2f, 7.8f)
        moveTo(7.8f, 16.2f); lineTo(6f, 18f)
        moveTo(12f, 7.8f)
        arcTo(4.2f, 4.2f, 0f, true, true, 12f, 16.2f)
        arcTo(4.2f, 4.2f, 0f, true, true, 12f, 7.8f)
        close()
    }
}

/** Tools: two rails with knobs. "Adjustable" reads at any size; a spanner silhouette does not. */
val EvIconTune: ImageVector = glyph("Tune") {
    line {
        moveTo(4f, 8.5f); lineTo(20f, 8.5f)
        moveTo(4f, 15.5f); lineTo(20f, 15.5f)
    }
    dot {
        circle(8.5f, 8.5f, 2f)
        circle(15.5f, 15.5f, 2f)
    }
}

val EvIconSearch: ImageVector = glyph("Search") {
    line {
        moveTo(10.5f, 4f)
        arcTo(6.5f, 6.5f, 0f, true, true, 10.5f, 17f)
        arcTo(6.5f, 6.5f, 0f, true, true, 10.5f, 4f)
        close()
        moveTo(15.4f, 15.4f); lineTo(20f, 20f)
    }
}

val EvIconRefresh: ImageVector = glyph("Refresh") {
    line {
        moveTo(19f, 12f)
        arcTo(7f, 7f, 0f, true, true, 15.6f, 6.1f)
        moveTo(15.6f, 6.1f); lineTo(19.9f, 6.1f)
        moveTo(15.6f, 6.1f); lineTo(15.6f, 10.4f)
    }
}

val EvIconTrash: ImageVector = glyph("Trash") {
    line {
        moveTo(4.5f, 7f); lineTo(19.5f, 7f)
        moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.3f, 19.2f); lineTo(16.7f, 19.2f); lineTo(17.5f, 7f)
        moveTo(10.4f, 10.5f); lineTo(10.4f, 15.8f)
        moveTo(13.6f, 10.5f); lineTo(13.6f, 15.8f)
    }
}

/** Write / compose: a nib and a stroke. Distinct at a glance from the folder and the note. */
val EvIconEdit: ImageVector = glyph("Edit") {
    line {
        moveTo(15.2f, 4.4f); lineTo(19.6f, 8.8f); lineTo(9.2f, 19.2f); lineTo(4.8f, 19.2f); lineTo(4.8f, 14.8f); close()
        moveTo(13f, 6.6f); lineTo(17.4f, 11f)
    }
}

/** Disclosure arrow. One mark for every "opens further down" affordance in the app. */
val EvIconChevron: ImageVector = glyph("Chevron") {
    line {
        moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f)
    }
}
