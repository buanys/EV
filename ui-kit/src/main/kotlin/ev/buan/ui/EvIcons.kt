package ev.buan.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The only icons plugins can reach.
 *
 * The icon pack is an `implementation` dependency of ui-kit, not `api`, so a plugin cannot compile
 * against `material-icons-*` even if the host happens to ship it. Exposing a curated set here keeps
 * the visual language consistent and turns "plugin referenced an icon the host does not have" from
 * a runtime ClassNotFoundException into a compile error.
 */
object EvIcons {
    val ArrowBack: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
}
