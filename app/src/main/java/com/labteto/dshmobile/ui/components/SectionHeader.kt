package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsShapes
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/** Section title row with an optional right-aligned accent action. */
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = DsType.std14Strong,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            // A pill-shaped target a thumb can find, not an 11sp word with 4dp of padding: this is
            // where "Scan network" lives, the main way a first-time user finds their harness.
            Text(
                action,
                style = DsType.small13Strong,
                color = colors.accent,
                modifier = if (onAction != null) {
                    Modifier
                        .clip(DsShapes.pillFull)
                        .clickable(onClick = onAction)
                        .heightIn(min = 36.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier.padding(4.dp)
                },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SectionHeaderPreview() {
    DshTheme {
        SectionHeader(
            title = "Tool calls",
            action = "Clear",
            onAction = {},
        )
    }
}
