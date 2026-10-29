package app.trip

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Haptics(private val view: View) {
    private fun go(c: Int) { view.performHapticFeedback(c) }
    fun tap() = go(HapticFeedbackConstants.CONTEXT_CLICK)
    fun tick() = go(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = go(HapticFeedbackConstants.CONFIRM)
    fun reject() = go(HapticFeedbackConstants.REJECT)
    fun toggle(on: Boolean) = go(
        if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else HapticFeedbackConstants.CONTEXT_CLICK,
    )
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Copies to the clipboard with a confirm tick. Android shows its own "copied" preview. */
@Composable
fun rememberCopy(): (String, String) -> Unit {
    val ctx = LocalContext.current
    val h = rememberHaptics()
    return remember(ctx, h) { { label, value -> ctx.copy(label, value); h.confirm() } }
}

/** Fades and lifts a block into place on first appearance; [index] staggers blocks down the page. */
fun Modifier.rise(index: Int): Modifier = if (index > 9) this else composed {
    val a = remember { Animatable(0f) }
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(40L * index)
        a.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * lift }
}

/**
 * Wraps [LazyListScope] so every block on a page rises in turn. Give a [key] to any block that comes
 * and goes, and to everything after it: unkeyed blocks are tracked by position, so one appearing above
 * would hand them a new slot, dropping their state and any gesture in progress.
 */
class RiseScope(private val scope: LazyListScope) {
    private var n = 0
    fun item(key: Any? = null, content: @Composable () -> Unit) {
        val i = n++
        scope.item(key = key) { Box(Modifier.rise(i)) { content() } }
    }
    fun <T> items(list: List<T>, content: @Composable (T) -> Unit) = list.forEach { x -> item { content(x) } }
}

/** Split-flap style text: each changed character rolls up into place. */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(180))) togetherWith
                        (slideOutVertically(tween(220)) { -it } + fadeOut(tween(150)))
                },
                label = "roll$i",
            ) { c -> if (c == ':') DotColon(style, color) else Text(c.toString(), style = style, color = color, maxLines = 1) }
        }
    }
}

/** Doto's own colon reads as "‡" at display sizes, so draw two square dots on its grid instead. */
@Composable
private fun DotColon(style: TextStyle, color: Color) {
    val d = LocalDensity.current
    val dot = with(d) { (style.fontSize * 0.11f).toDp() }
    Canvas(Modifier.width(dot * 2.6f).height(with(d) { style.lineHeight.toDp() })) {
        val s = dot.toPx()
        listOf(0.38f, 0.62f).forEach { y ->
            drawRect(color, Offset(size.width / 2 - s / 2, size.height * y - s / 2), androidx.compose.ui.geometry.Size(s, s))
        }
    }
}

private const val PLANE = "M21,16v-2l-8,-5V3.5C13,2.67 12.33,2 11.5,2S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5L21,16z"

/**
 * The Ø-plane mark. [draw] runs 0→1: the ring sweeps round, the contrail draws, the plane flies in
 * along the slash and the red dot lands last. At 1 it matches the launcher icon.
 */
@Composable
fun OMark(modifier: Modifier = Modifier, draw: Float = 1f) {
    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    val red = MaterialTheme.colorScheme.primary
    val plane = remember { PathParser().parsePathString(PLANE).toPath() }
    Canvas(modifier) {
        fun seg(a: Float, b: Float) = ((draw - a) / (b - a)).coerceIn(0f, 1f)
        val ring = seg(0f, 0.45f)
        val trail = seg(0.3f, 0.6f)
        val fly = seg(0.45f, 0.85f)
        val dot = seg(0.8f, 1f)
        val k = size.minDimension / 66f
        translate(-21f * k, -21f * k) {
            scale(k, pivot = Offset.Zero) {
                drawArc(fg, -130f, 360f * ring, false, Offset(30f, 30f), androidx.compose.ui.geometry.Size(48f, 48f), style = Stroke(5.75f, cap = if (ring < 1f) StrokeCap.Round else StrokeCap.Butt))
                if (trail > 0f) drawLine(fg, Offset(32.1f, 79.3f), Offset(32.1f + (49f - 32.1f) * trail, 79.3f + (59.8f - 79.3f) * trail), 5.75f)
                if (dot > 0f) drawCircle(red, 3.6f * dot, Offset(77f, 79.3f))
                if (fly > 0f) {
                    val e = FastOutSlowInEasing.transform(fly)
                    // Flies from the lower-left along the slash direction to its resting place.
                    val dx = (1f - e) * -28f
                    val dy = (1f - e) * 32f
                    translate(56f - 11.5f + dx, 51.5f - 12f + dy) {
                        rotate(40.8f, pivot = Offset(11.5f, 12f)) {
                            scale(0.95f, pivot = Offset(11.5f, 12f)) {
                                drawPath(plane, bg, alpha = e, style = Stroke(3.4f, join = StrokeJoin.Round))
                                drawPath(plane, fg, alpha = e)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Launch moment: the mark draws itself over a full-bleed background, with a tick when the dot lands. */
@Composable
fun Intro(onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    val h = rememberHaptics()
    LaunchedEffect(Unit) {
        launch { delay(820); h.confirm() }
        p.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        delay(220)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            OMark(Modifier.size(120.dp), draw = p.value)
            Spacer(Modifier.height(20.dp))
            Label(TRIP_TITLE, modifier = Modifier.graphicsLayer { alpha = ((p.value - 0.6f) / 0.4f).coerceIn(0f, 1f) })
        }
    }
}
