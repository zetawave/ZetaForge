package com.zetaforge.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The ZetaForge mark: a rounded, gradient tile with a forged "Z" bolt.
 *
 * Drawn rather than imported so it scales to any size, follows the theme colours
 * and costs nothing in APK size.
 */
@Composable
fun ZetaLogo(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    tileColors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
    ),
    markColor: Color = Color.White,
) {
    Canvas(modifier.size(size)) {
        val side = this.size.minDimension
        val radius = side * 0.28f

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = tileColors,
                start = Offset.Zero,
                end = Offset(side, side),
            ),
            size = androidx.compose.ui.geometry.Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )

        // A "Z" whose lower half is offset like a lightning bolt: the forge spark.
        val bolt = Path().apply {
            moveTo(side * 0.30f, side * 0.26f)
            lineTo(side * 0.74f, side * 0.26f)
            lineTo(side * 0.48f, side * 0.50f)
            lineTo(side * 0.68f, side * 0.50f)
            lineTo(side * 0.30f, side * 0.78f)
            lineTo(side * 0.44f, side * 0.57f)
            lineTo(side * 0.26f, side * 0.57f)
            close()
        }
        drawPath(bolt, color = markColor)
    }
}
