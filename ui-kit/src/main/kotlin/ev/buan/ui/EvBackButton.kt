package ev.buan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Back affordance used by [EvPage]; kept separate so pages can place it themselves. */
@Composable
fun EvBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Icon(
        imageVector = EvIcons.ArrowBack,
        contentDescription = "返回",
        tint = MiuixTheme.colorScheme.onBackground,
        modifier = modifier
            .clickable { onClick() }
            .padding(12.dp),
    )
}
