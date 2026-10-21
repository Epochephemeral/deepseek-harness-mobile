package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsAnimations
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Centered empty state: whale mark, hero headline, optional subtitle, an optional mono
 * "Preview" pill, and suggestion chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyHero(
    headline: String,
    subtitle: String?,
    chips: List<String> = emptyList(),
    onChipClick: (String) -> Unit = {},
    showPreview: Boolean = false,
) {
    val colors = DsTheme.colors
    // A short fade-and-rise on arrival, so an empty screen settles instead of appearing in a frame.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, tween(360, easing = DsAnimations.emphasizedDecelerate)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = arrival.value
                translationY = (1f - arrival.value) * 12.dp.toPx()
            }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhaleMark(Modifier.size(64.dp))
        Text(
            headline,
            style = DsType.hero26,
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        subtitle?.let {
            Text(
                it,
                style = DsType.base16,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (showPreview) Text(
            "Preview",
            style = DsType.xsmall12.copy(fontFamily = DsType.codeFont, color = colors.accent),
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(colors.accentTertiary)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                // Centred under the centred headline; left-aligned chips under it read as a list
                // that had drifted off the page.
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    DsPill(text = chip, onClick = { onChipClick(chip) })
                }
            }
        }
    }
}

/** 64dp accentTertiary disc with the DeepSeek Mobile logo mark. */
/** The product mark on a tinted disc. Shared so a screen can use it without the whole hero. */
@Composable
internal fun WhaleMark(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Box(modifier.clip(CircleShape).background(colors.accentTertiary), contentAlignment = Alignment.Center) {
        // The launcher vector keeps the mark inside the adaptive-icon safe zone
        // (~46% of the 108dp canvas), so oversize the image to fill the disc.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(100.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EmptyHeroPreview() {
    DshTheme {
        EmptyHero(
            headline = "Nothing running yet",
            subtitle = "Ask the harness anything, or pick a suggestion below.",
            chips = listOf("Summarize this repo", "Run the test suite", "Explain a diff"),
            onChipClick = {},
        )
    }
}
