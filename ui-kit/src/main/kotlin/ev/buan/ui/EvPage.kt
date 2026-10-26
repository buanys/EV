package ev.buan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Standard page: a fixed [EvPageHeader] over one scrolling list.
 *
 * 2026-10-26: replaced Miuix's `Scaffold` + `SmallTopAppBar`. The scaffold's only remaining job
 * was insets, and its top bar drew the page name at list-row size (see [EvPageHeader]). The
 * collapse-on-scroll behaviour went with it: a shrinking header fights the one thing the audit
 * asked for - a page you can recognise the moment it opens.
 *
 * Host and plugins share this so every screen keeps the same header, insets and scroll feel.
 */
@Composable
fun EvPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    contentPadding: PaddingValues = EvPageBodyPadding,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    Column(
        modifier
            .fillMaxSize()
            .background(EvThemeTokens.colors.surface0),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            EvPageHeader(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                actions = actions,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = contentPadding,
            content = content,
        )
    }
}
