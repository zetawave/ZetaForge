package com.zetaforge.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.zetaforge.app.R

/**
 * The ZetaForge mark.
 *
 * The real brand mark, not an approximation of it: the asset is derived from the
 * logo at build time, so the app and the logo cannot drift apart. It is a white
 * silhouette on transparency, which is what lets it sit on the brand tile here
 * and be tinted to the foreground colour elsewhere.
 */
@Composable
fun ZetaLogo(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    tileColors: List<Color> = ZetaBrand.tile,
    markColor: Color = Color.White,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(tileColors)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.zeta_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(markColor),
            modifier = Modifier
                .size(size)
                .padding(size * 0.20f),
        )
    }
}

/** The mark on its own, tinted to whatever the surface needs. */
@Composable
fun ZetaMark(
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.zeta_mark),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

/** Brand colours taken from the logo, for the places that must match it. */
object ZetaBrand {
    val deep = Color(0xFF0D1738)
    val mid = Color(0xFF16326B)
    val light = Color(0xFF27568F)
    val tile = listOf(deep, light)
}
