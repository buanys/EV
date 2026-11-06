package ev.buan.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path

/**
 * 内容与实体：一个东西是"什么"，不是"能对它做什么"。
 *
 * 与动作分开的理由很实际：同一个动作（比如"打开"）在不同实体上要用同一套箭头，
 * 而同一个实体（比如"网页"）在不同位置必须长得一样。按这一刀分，加新图标时不用猜
 * 该放哪一半——先问它表示的是动词还是名词。
 *
 * 绘制规则在 `EvGlyphVectors.kt`：24 格、1.7dp 描边、只用圆/圆角矩形/直线。这个文件只放
 * 词汇，不放语法。
 */

/** A conversation: rounded body with a tail that points at the speaker. */
val EvIconChat: ImageVector = glyph("Chat") {
    line {
        moveTo(7f, 5.5f); lineTo(17f, 5.5f)
        arcTo(4f, 4f, 0f, false, true, 21f, 9.5f)
        lineTo(21f, 13f)
        arcTo(4f, 4f, 0f, false, true, 17f, 17f)
        lineTo(12f, 17f)
        lineTo(8f, 20.5f)
        lineTo(8f, 17f)
        lineTo(7f, 17f)
        arcTo(4f, 4f, 0f, false, true, 3f, 13f)
        lineTo(3f, 9.5f)
        arcTo(4f, 4f, 0f, false, true, 7f, 5.5f)
        close()
    }
}

/** The web: a real globe with a meridian and the equator, so it survives 20dp. */
val EvIconGlobe: ImageVector = glyph("Globe") {
    line {
        moveTo(4f, 12f)
        arcTo(8f, 8f, 0f, true, true, 20f, 12f)
        arcTo(8f, 8f, 0f, true, true, 4f, 12f)
        close()
        moveTo(12f, 4f)
        arcTo(4f, 8f, 0f, true, true, 12f, 20f)
        arcTo(4f, 8f, 0f, true, true, 12f, 4f)
        close()
        moveTo(4f, 12f); lineTo(20f, 12f)
    }
}

/** Memory: three stacked planes. */
val EvIconMemory: ImageVector = glyph("Memory") {
    line {
        moveTo(12f, 3.5f); lineTo(20f, 8f); lineTo(12f, 12.5f); lineTo(4f, 8f); close()
        moveTo(4f, 12f); lineTo(12f, 16.5f); lineTo(20f, 12f)
        moveTo(4f, 16f); lineTo(12f, 20.5f); lineTo(20f, 16f)
    }
}

val EvIconPerson: ImageVector = glyph("Person") {
    line {
        moveTo(12f, 4.5f)
        arcTo(3.8f, 3.8f, 0f, true, true, 12f, 12.1f)
        arcTo(3.8f, 3.8f, 0f, true, true, 12f, 4.5f)
        close()
        moveTo(4.5f, 20f)
        curveTo(4.5f, 16f, 7.9f, 13.5f, 12f, 13.5f)
        curveTo(16.1f, 13.5f, 19.5f, 16f, 19.5f, 20f)
    }
}

val EvIconDoc: ImageVector = glyph("Doc") {
    line {
        moveTo(6.5f, 3.5f); lineTo(13.5f, 3.5f); lineTo(18f, 8f); lineTo(18f, 20.5f)
        lineTo(6.5f, 20.5f); close()
        moveTo(13.5f, 3.5f); lineTo(13.5f, 8f); lineTo(18f, 8f)
        moveTo(9.5f, 12f); lineTo(15f, 12f)
        moveTo(9.5f, 15.5f); lineTo(15f, 15.5f)
    }
}

val EvIconNote: ImageVector = glyph("Note") {
    line {
        moveTo(8f, 4.5f); lineTo(16f, 4.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 18.5f, 7f)
        lineTo(18.5f, 17f)
        arcTo(2.5f, 2.5f, 0f, false, true, 16f, 19.5f)
        lineTo(8f, 19.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 5.5f, 17f)
        lineTo(5.5f, 7f)
        arcTo(2.5f, 2.5f, 0f, false, true, 8f, 4.5f)
        close()
        moveTo(9f, 9.5f); lineTo(15f, 9.5f)
        moveTo(9f, 13f); lineTo(15f, 13f)
        moveTo(9f, 16.5f); lineTo(12.5f, 16.5f)
    }
}

val EvIconFolder: ImageVector = glyph("Folder") {
    line {
        moveTo(4f, 8f)
        curveTo(4f, 6.9f, 4.9f, 6f, 6f, 6f)
        lineTo(9.5f, 6f)
        lineTo(11.5f, 8.5f)
        lineTo(18f, 8.5f)
        curveTo(19.1f, 8.5f, 20f, 9.4f, 20f, 10.5f)
        lineTo(20f, 17f)
        curveTo(20f, 18.1f, 19.1f, 19f, 18f, 19f)
        lineTo(6f, 19f)
        curveTo(4.9f, 19f, 4f, 18.1f, 4f, 17f)
        close()
    }
}

val EvIconInfo: ImageVector = glyph("Info") {
    line {
        moveTo(12f, 4f)
        arcTo(8f, 8f, 0f, true, true, 12f, 20f)
        arcTo(8f, 8f, 0f, true, true, 12f, 4f)
        close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
    }
    dot { circle(12f, 8.4f, 1.3f) }
}

val EvIconHelp: ImageVector = glyph("Help") {
    line {
        // A bare question mark, no enclosing circle: inside a 2x2 grid the circle read as a
        // sibling of the globe. The silhouette is what distinguishes an entry, not the detail.
        moveTo(8.6f, 9.8f)
        curveTo(8.6f, 7.0f, 10.5f, 5.4f, 12.7f, 5.4f)
        curveTo(15.0f, 5.4f, 16.8f, 7.0f, 16.8f, 9.3f)
        curveTo(16.8f, 12.6f, 12.5f, 12.8f, 12.5f, 16.6f)
    }
    dot { circle(12.5f, 19.2f, 1.4f) }
}

/**
 * The assistant: EV's signature mark. A four-point spark on the diagonals, drawn with curves rather
 * than a polygon so it reads as light instead of as a compass rose or a star rating.
 */
val EvIconSparkle: ImageVector = glyph("Sparkle") {
    line {
        moveTo(12f, 3f)
        curveTo(12.9f, 9.3f, 14.7f, 11.1f, 21f, 12f)
        curveTo(14.7f, 12.9f, 12.9f, 14.7f, 12f, 21f)
        curveTo(11.1f, 14.7f, 9.3f, 12.9f, 3f, 12f)
        curveTo(9.3f, 11.1f, 11.1f, 9.3f, 12f, 3f)
        close()
    }
}

/** Agent model: a chip. Squares and pins, no glow, no gear teeth. */
val EvIconChip: ImageVector = glyph("Chip") {
    line {
        moveTo(9f, 9f); lineTo(15f, 9f); lineTo(15f, 15f); lineTo(9f, 15f); close()
        moveTo(12f, 3.5f); lineTo(12f, 6.5f)
        moveTo(12f, 17.5f); lineTo(12f, 20.5f)
        moveTo(3.5f, 12f); lineTo(6.5f, 12f)
        moveTo(17.5f, 12f); lineTo(20.5f, 12f)
    }
}

val EvIconLock: ImageVector = glyph("Lock") {
    line {
        moveTo(6f, 10.5f); lineTo(18f, 10.5f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(8.5f, 10.5f); lineTo(8.5f, 8f)
        arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 8f)
        lineTo(15.5f, 10.5f)
    }
}

val EvIconClock: ImageVector = glyph("Clock") {
    line {
        moveTo(12f, 4f)
        arcTo(8f, 8f, 0f, true, true, 12f, 20f)
        arcTo(8f, 8f, 0f, true, true, 12f, 4f)
        close()
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }
}

val EvIconBell: ImageVector = glyph("Bell") {
    line {
        moveTo(6.5f, 16.5f); lineTo(6.5f, 11f)
        curveTo(6.5f, 8f, 9f, 5.5f, 12f, 5.5f)
        curveTo(15f, 5.5f, 17.5f, 8f, 17.5f, 11f)
        lineTo(17.5f, 16.5f)
        close()
        moveTo(5f, 16.5f); lineTo(19f, 16.5f)
        moveTo(10.3f, 19.5f); lineTo(13.7f, 19.5f)
    }
}

val EvIconScreen: ImageVector = glyph("Screen") {
    line {
        moveTo(5.5f, 5f); lineTo(18.5f, 5f); lineTo(18.5f, 15f); lineTo(5.5f, 15f); close()
        moveTo(12f, 15f); lineTo(12f, 19f)
        moveTo(8.5f, 19.5f); lineTo(15.5f, 19.5f)
    }
}

/** Theme: a half-filled disc. The only glyph that carries a fill on purpose. */
val EvIconTheme: ImageVector = glyph("Theme") {
    line {
        moveTo(12f, 4f)
        arcTo(8f, 8f, 0f, true, true, 12f, 20f)
        arcTo(8f, 8f, 0f, true, true, 12f, 4f)
        close()
        moveTo(12f, 4f); lineTo(12f, 20f)
    }
}

/** Skills: the "instruction" mark. Text prompts are what a Skill is - not a wand, not a document. */
val EvIconSkill: ImageVector = glyph("Skill") {
    line {
        moveTo(9f, 7.5f); lineTo(5f, 12f); lineTo(9f, 16.5f)
        moveTo(15f, 7.5f); lineTo(19f, 12f); lineTo(15f, 16.5f)
        moveTo(13.4f, 6.5f); lineTo(10.6f, 17.5f)
    }
}

/** Theme: a disc with one half filled. Reads as "brightness" at any size, unlike a line in a circle. */
val EvIconBrightness: ImageVector = glyph("Brightness") {
    line {
        moveTo(12f, 4f)
        arcTo(8f, 8f, 0f, true, true, 12f, 20f)
        arcTo(8f, 8f, 0f, true, true, 12f, 4f)
        close()
    }
    path(fill = ink) {
        // left half filled
        moveTo(12f, 4f)
        arcTo(8f, 8f, 0f, false, false, 12f, 20f)
        lineTo(12f, 4f)
        close()
    }
}

/** Model service: a chip with pins on all four sides - eight stubs is what makes it read as silicon. */
val EvIconChip8: ImageVector = glyph("Chip") {
    line {
        moveTo(8.5f, 8.5f); lineTo(15.5f, 8.5f); lineTo(15.5f, 15.5f); lineTo(8.5f, 15.5f); close()
        moveTo(10.5f, 3f); lineTo(10.5f, 8.5f)
        moveTo(13.5f, 3f); lineTo(13.5f, 8.5f)
        moveTo(10.5f, 15.5f); lineTo(10.5f, 21f)
        moveTo(13.5f, 15.5f); lineTo(13.5f, 21f)
        moveTo(3f, 10.5f); lineTo(8.5f, 10.5f)
        moveTo(3f, 13.5f); lineTo(8.5f, 13.5f)
        moveTo(15.5f, 10.5f); lineTo(21f, 10.5f)
        moveTo(15.5f, 13.5f); lineTo(21f, 13.5f)
    }
}

/** Host: the device itself. A gear here read as a brightness dial and meant nothing. */
val EvIconHost: ImageVector = glyph("Host") {
    line {
        moveTo(8f, 3.5f); lineTo(16f, 3.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 18.5f, 6f)
        lineTo(18.5f, 18f)
        arcTo(2.5f, 2.5f, 0f, false, true, 16f, 20.5f)
        lineTo(8f, 20.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 5.5f, 18f)
        lineTo(5.5f, 6f)
        arcTo(2.5f, 2.5f, 0f, false, true, 8f, 3.5f)
        close()
        moveTo(10.5f, 17.5f); lineTo(13.5f, 17.5f)
    }
}
