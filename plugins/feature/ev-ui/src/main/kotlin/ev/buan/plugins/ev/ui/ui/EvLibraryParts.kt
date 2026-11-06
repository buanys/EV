package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.StatusError
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Building blocks shared by the character and skill editors.
 *
 * Both libraries are the same object with different words - a name, a description and one block of
 * instruction text - so the text field, the section label, the hint and the two-step delete live here
 * once. Keeping them here also keeps the two screens' diff small enough to read: what differs between
 * a character and a skill is the copy the user sees, not the widgets.
 *
 * Internal on purpose: this is plugin chrome, not part of the design system (ui-kit owns that).
 */

@Composable
internal fun LibraryFieldLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** One plain text field: BasicTextField in a quiet slate, no material3 control. */
@Composable
internal fun LibraryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minHeight: Dp = 0.dp,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .defaultMinSize(minHeight = minHeight)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onBackground, fontSize = 15.sp),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun LibraryHint(title: String?, detail: String, tint: Color? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = tint ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = tint ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** The first step of a delete: a destructive-looking row that opens the confirmation. */
@Composable
internal fun LibraryDeleteRow(title: String, summary: String, onRequest: () -> Unit) {
    EvSettingsGroup {
        EvSettingsRow(
            title = title,
            glyph = EvGlyphKind.Trash,
            tileColor = StatusError,
            summary = summary,
            onClick = onRequest,
        )
    }
}

/** The second step of a delete: an explicit confirm, never a one-tap destructive action. */
@Composable
internal fun LibraryDeleteConfirm(title: String, detail: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    EvSettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    text = "取消",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clickable(onClickLabel = "取消删除", onClick = onCancel)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "确认删除",
                    style = MiuixTheme.textStyles.body1,
                    color = StatusError,
                    modifier = Modifier
                        .clickable(onClickLabel = "确认删除", onClick = onConfirm)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
    }
}
