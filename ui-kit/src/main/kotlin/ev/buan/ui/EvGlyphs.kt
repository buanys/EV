package ev.buan.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.MindMap
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * The semantic glyphs the design uses, resolved to the Miuix icon set.
 *
 * These used to be hand-drawn Canvas paths because the host shipped icons-core only. Miuix ships its
 * own 156-symbol set as a normal dependency, so a glyph is now a name mapping: the shapes, optical
 * weight and corner language come from the icon set instead of from hand-tuned lines, and a symbol
 * that changes upstream updates everywhere at once.
 *
 * The enum and the [EvGlyph] signature are unchanged on purpose - they are part of the published
 * ui-kit contract that plugins compile against.
 */
enum class EvGlyphKind {
    Menu, Plus, Mic, Atom, Send, Screen, Chat, Globe, Memory, More,
    Bell, Clock, Person, Grid, Gear, Search, Doc, Lock, Sparkle, Refresh, Trash,
    Tool, Theme, Info,
}

@Composable
fun EvGlyph(
    kind: EvGlyphKind,
    color: Color,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") strokeWidth: Float = 1.6f,
) {
    Image(
        imageVector = kind.icon,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

/**
 * Closest Miuix symbol for each semantic glyph. Four kinds have no exact counterpart:
 * `Atom` (agent/model), `Sparkle` (assistant), `Globe` (web) and `Memory` (context) use the nearest
 * metaphor the set offers rather than reintroducing a one-off drawing.
 */
private val EvGlyphKind.icon: ImageVector
    get() = when (this) {
        EvGlyphKind.Menu -> MiuixIcons.Sidebar
        EvGlyphKind.Plus -> MiuixIcons.Add
        EvGlyphKind.Mic -> MiuixIcons.Mic
        EvGlyphKind.Atom -> MiuixIcons.MindMap
        EvGlyphKind.Send -> MiuixIcons.Send
        EvGlyphKind.Screen -> MiuixIcons.ScreenCapture
        EvGlyphKind.Chat -> MiuixIcons.Messages
        EvGlyphKind.Globe -> MiuixIcons.Link
        EvGlyphKind.Memory -> MiuixIcons.Layers
        EvGlyphKind.More -> MiuixIcons.More
        EvGlyphKind.Bell -> MiuixIcons.Alarm
        EvGlyphKind.Clock -> MiuixIcons.Timer
        EvGlyphKind.Person -> MiuixIcons.Contacts
        EvGlyphKind.Grid -> MiuixIcons.GridView
        EvGlyphKind.Gear -> MiuixIcons.Settings
        EvGlyphKind.Search -> MiuixIcons.Search
        EvGlyphKind.Doc -> MiuixIcons.File
        EvGlyphKind.Lock -> MiuixIcons.Lock
        EvGlyphKind.Sparkle -> MiuixIcons.Favorites
        EvGlyphKind.Refresh -> MiuixIcons.Refresh
        EvGlyphKind.Trash -> MiuixIcons.Delete
        EvGlyphKind.Tool -> MiuixIcons.Tune
        EvGlyphKind.Theme -> MiuixIcons.Theme
        EvGlyphKind.Info -> MiuixIcons.Info
    }
