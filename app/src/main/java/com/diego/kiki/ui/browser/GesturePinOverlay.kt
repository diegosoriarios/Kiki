package com.diego.kiki.ui.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diego.kiki.automation.AutoScroller
import com.diego.kiki.automation.GesturePins
import com.diego.kiki.automation.pinDistancePx
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Full-size overlay over the WebView: two draggable pins (A = start, B = end)
 * define the swipe the auto-scroller will replay. Coordinates are stored as
 * fractions of this overlay's size (which matches the WebView) so replays land
 * in place even after rotation.
 */
@Composable
fun GesturePinOverlay(
    onStart: (GesturePins) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var start by remember { mutableStateOf(Offset(0.5f, 0.68f)) }
    var end by remember { mutableStateOf(Offset(0.5f, 0.3f)) }
    var draggingStart by remember { mutableStateOf(true) }
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val pins = GesturePins(start.x, start.y, end.x, end.y)
        val pinsOk = pinDistancePx(pins, wPx, hPx) >= AutoScroller.MIN_PIN_DISTANCE_PX
        val lineColor = MaterialTheme.colorScheme.primary
        val pinFill = MaterialTheme.colorScheme.primary
        val pinLabel = MaterialTheme.colorScheme.onPrimary
        val pinTextStyle = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            color = pinLabel
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { touch ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val dStart = hypot(touch.x - start.x * w, touch.y - start.y * h)
                            val dEnd = hypot(touch.x - end.x * w, touch.y - end.y * h)
                            draggingStart = dStart <= dEnd
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val fx = (change.position.x / w).coerceIn(0.05f, 0.95f)
                            val fy = (change.position.y / h).coerceIn(0.05f, 0.95f)
                            if (draggingStart) {
                                start = Offset(fx, fy)
                            } else {
                                end = Offset(fx, fy)
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val a = Offset(start.x * size.width, start.y * size.height)
                val b = Offset(end.x * size.width, end.y * size.height)

                drawLine(
                    color = lineColor,
                    start = a,
                    end = b,
                    strokeWidth = 4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
                )

                val angle = Math.toDegrees(
                    atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                ).toFloat()
                rotate(degrees = angle, pivot = b) {
                    val head = Path().apply {
                        moveTo(b.x, b.y)
                        lineTo(b.x - 26f, b.y - 12f)
                        lineTo(b.x - 26f, b.y + 12f)
                        close()
                    }
                    drawPath(head, color = lineColor)
                }

                listOf(a to "A", b to "B").forEach { (pos, label) ->
                    drawCircle(color = pinFill, radius = 34f, center = pos)
                    drawCircle(
                        color = pinLabel,
                        radius = 34f,
                        center = pos,
                        style = Stroke(width = 3f)
                    )
                    val measured = textMeasurer.measure(label, style = pinTextStyle)
                    withTransform({ translate(pos.x - measured.size.width / 2f, pos.y - measured.size.height / 2f) }) {
                        drawText(measured)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ) {
                Text(
                    text = "Drag pin A (start) and pin B (end) to set the swipe,\nthen start — the app will repeat it automatically",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Spacer(modifier = Modifier.padding(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onStart(pins) },
                    enabled = pinsOk
                ) {
                    Text("Start auto-swipe")
                }
            }
        }
    }
}
