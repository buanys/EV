package ev.buan.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * EV's own icon set.
 *
 * The rule this file exists for: the app had no icon language of its own. Every glyph was a miuix
 * system symbol borrowed by "nearest metaphor" - a heart for the assistant, a chain for the
 * browser, a dial for the web - so the interface read as a system settings app with a chat page
 * bolted on. Borrowed metaphors cannot be made premium; the mark has to be drawn.
 *
 * The language, fixed here so every future glyph is consistent:
 *  - 24 x 24 grid, live area 3.5..20.5, optical centre 12,12
 *  - 1.7dp stroke, round caps and joins, no fill except deliberate dots
 *  - built from circles, rounded rectangles and straight lines only - no illustration
 *  - one glyph, one meaning; if two ideas need the same mark, one of them is wrong
 */
private const val STROKE = 2.0f
private val ink = SolidColor(Color.Black)

private fun glyph(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

private fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) = path(
    fill = null,
    stroke = ink,
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

private fun ImageVector.Builder.dot(block: PathBuilder.() -> Unit) = path(
    fill = ink,
    pathBuilder = block,
)

/** A filled dot, drawn as two arcs: PathBuilder has no addOval in this Compose version. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

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

val EvIconMore: ImageVector = glyph("More") {
    dot { circle(12f, 5.6f, 1.6f) }
    dot { circle(12f, 12f, 1.6f) }
    dot { circle(12f, 18.4f, 1.6f) }
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

/** Write / compose: a nib and a stroke. Distinct at a glance from the folder and the note. */
val EvIconEdit: ImageVector = glyph("Edit") {
    line {
        moveTo(15.2f, 4.4f); lineTo(19.6f, 8.8f); lineTo(9.2f, 19.2f); lineTo(4.8f, 19.2f); lineTo(4.8f, 14.8f); close()
        moveTo(13f, 6.6f); lineTo(17.4f, 11f)
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

/** Disclosure arrow. One mark for every "opens further down" affordance in the app. */
val EvIconChevron: ImageVector = glyph("Chevron") {
    line {
        moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f)
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
