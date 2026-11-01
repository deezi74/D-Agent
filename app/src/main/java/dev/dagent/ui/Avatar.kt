package dev.dagent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/**
 * The agent's face: the person's own picture if they chose one, otherwise a monogram on an
 * ember gradient. While the agent works a ring spins around it.
 */
@Composable
fun AgentAvatar(name: String, path: String, size: Dp, busy: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (busy) {
            val t = rememberInfiniteTransition(label = "ring")
            val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "angle")
            Canvas(Modifier.fillMaxSize().rotate(angle)) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, p.accent, p.accent2)),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    style = Stroke(width = this.size.minDimension * 0.07f),
                )
            }
        }
        val inner = if (busy) size * 0.82f else size
        val file = path.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
        if (file != null) {
            AsyncImage(
                model = file, contentDescription = name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(inner).clip(CircleShape),
            )
        } else {
            Box(
                Modifier.size(inner).clip(CircleShape).background(Brush.linearGradient(listOf(p.accent, p.accent2))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.trim().take(1).uppercase().ifEmpty { "D" },
                    color = Color(0xFF1A0E08), fontWeight = FontWeight.Black,
                    fontSize = (inner.value * 0.48f).sp,
                )
            }
        }
    }
}
