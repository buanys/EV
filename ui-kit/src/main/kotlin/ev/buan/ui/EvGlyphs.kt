package ev.buan.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyph catalogue.
 *
 * Every mark is drawn in this repository (see EvGlyphVectors.kt) rather than borrowed from the
 * platform icon set: a shared icon language is the one part of "premium" that cannot be reached by
 * tuning spacing, and "nearest available metaphor" is exactly how a heart ended up meaning
 * "assistant" and a chain meaning "browser".
 */
enum class EvGlyphKind {
    Menu, Plus, Mic, Atom, Send, Screen, Chat, Globe, World, Memory, More,
    Bell, Clock, Person, Grid, Gear, Search, Doc, Note, Folder, Lock, Sparkle, Refresh, Trash,
    Tool, Theme, Info, Help, Create, Skill, Chevron,
}

@Composable
fun EvGlyph(
    kind: EvGlyphKind,
    color: Color,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") strokeWidth: Float = 1.7f,
) {
    Image(
        imageVector = kind.icon,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

private val EvGlyphKind.icon: ImageVector
    get() = when (this) {
        EvGlyphKind.Menu -> EvIconMenu
        EvGlyphKind.Plus -> EvIconPlus
        EvGlyphKind.Create -> EvIconEdit
        EvGlyphKind.Mic -> EvIconMic
        EvGlyphKind.Atom -> EvIconChip8
        EvGlyphKind.Gear -> EvIconHost
        EvGlyphKind.Send -> EvIconSend
        EvGlyphKind.Screen -> EvIconScreen
        EvGlyphKind.Chat -> EvIconChat
        // A page is a place: the globe is the only mark in the set that says "somewhere out there".
        EvGlyphKind.Globe -> EvIconGlobe
        EvGlyphKind.World -> EvIconGlobe
        EvGlyphKind.Memory -> EvIconMemory
        EvGlyphKind.More -> EvIconMore
        EvGlyphKind.Bell -> EvIconBell
        EvGlyphKind.Clock -> EvIconClock
        EvGlyphKind.Person -> EvIconPerson
        EvGlyphKind.Grid -> EvIconGrid
        EvGlyphKind.Search -> EvIconSearch
        EvGlyphKind.Doc -> EvIconDoc
        EvGlyphKind.Note -> EvIconNote
        EvGlyphKind.Folder -> EvIconFolder
        EvGlyphKind.Lock -> EvIconLock
        EvGlyphKind.Sparkle -> EvIconSparkle
        EvGlyphKind.Refresh -> EvIconRefresh
        EvGlyphKind.Trash -> EvIconTrash
        EvGlyphKind.Tool -> EvIconTune
        EvGlyphKind.Theme -> EvIconBrightness
        EvGlyphKind.Info -> EvIconInfo
        EvGlyphKind.Help -> EvIconHelp
        EvGlyphKind.Skill -> EvIconSkill
        EvGlyphKind.Chevron -> EvIconChevron
    }
