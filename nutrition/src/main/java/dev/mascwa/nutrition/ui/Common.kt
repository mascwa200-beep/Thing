package dev.mascwa.nutrition.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.TypedNumber
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The few shapes every screen here is built from.
 *
 * ⚠️ **Stock Material 3 and nothing else, on purpose.** The LCARS application has a whole geometry
 * kit — swept corners, rails, a bespoke type scale — and reusing it was never an option: it is the
 * novelty this app was asked to be free of. What is here is a card, a row and a bar, so that
 * somebody who has used any other Android app already knows how to read it.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * A label on the left, a value on the right — the workhorse of every panel here.
 *
 * ⚠️ **Neither side was bounded, and a `Row` neither wraps nor shrinks its children.** A long label
 * beside a long value measured past the card and the value — the half somebody is actually reading —
 * was the one pushed off the edge, because it is laid out second. It shows on exactly the rows worth
 * seeing: a product with a real brand name, a figure with a unit and an "over" note beside it.
 *
 * The label takes the room it needs up to about half, ellipsising past that; the value gets the rest
 * and is allowed to wrap onto a second line rather than be cut. Which way round that goes is the
 * decision: a truncated LABEL is still a readable row, and a truncated VALUE is a number that means
 * something else.
 */
@Composable
fun StatRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.SpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * A row of controls that WRAPS rather than running off the edge.
 *
 * ⚠️ **The failure it fixes is silent and total.** A `Row` does not wrap and does not shrink its
 * children: whatever does not fit is simply not drawn, with no scrollbar, no fade and nothing to
 * suggest there is more. `RepeatADay`'s three buttons — "Yesterday", "Two days back", "A week back" —
 * needed more width than a card has on any phone, so the third option did not exist as far as anyone
 * could tell.
 *
 * ⚠️ **Wrapping rather than [ChipRow]'s sideways scroll, and the two are not interchangeable.** A
 * scroll is right for chips, where a person can see they are in a rail and push it. It is wrong for
 * buttons: nothing about a cut-off button says there is another one, so the affordance has to be
 * visible rather than discoverable.
 *
 * ⚠️ **The content takes a `RowScope`, not a `FlowRowScope`, and that is deliberate.** `FlowRowScope`
 * extends `RowScope`, so a `RowScope`-receiver lambda can be used where the flow layout wants one —
 * which keeps `weight()` available to callers (two of the rows converted to this rely on it) without
 * `@ExperimentalLayoutApi` leaking into a signature every screen would then have to opt in to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * How much of a target has been eaten.
 *
 * ⚠️ The bar is clamped but the NUMBER beside it is not, so going over shows as a full bar and a
 * figure that keeps climbing. A bar that silently stopped at the target would make 2,900 against
 * 2,000 look identical to hitting it exactly, which is the one case somebody most needs to see.
 */
@Composable
fun ProgressRow(
    label: String,
    eaten: Double,
    target: Int,
    unit: String,
    tint: Color? = null,
    /**
     * Which way this number binds. Defaulted to a floor, so a caller that has not thought about it
     * gets the quiet treatment rather than an accidental warning.
     */
    macro: MacroTargets.Macro = MacroTargets.Macro.PROTEIN,
) {
    val share = if (target > 0) (eaten / target).coerceIn(0.0, 1.0) else 0.0
    // ⚠️ **The bar clamps at one, so six hundred calories over drew exactly the same full bar as
    // landing on the target.** The figures beside it were right, but a bar is the thing read at a
    // glance and it reported "done" for both.
    //
    // ⚠️ **Said in WORDS rather than in a colour, and that is this module's theme deciding it.**
    // `NutritionTheme` takes the device's dynamic scheme on Android 12 and later, so every role
    // above `primary` is whatever the user's wallpaper produced — `tertiary` could read as cheerful
    // on one phone and as a warning on the next, which makes it useless for carrying a meaning. The
    // one role Material does define for this is `error`, and over budget is not an error: this app
    // measures expenditure from what you log, so a day rendered as a failure is a day somebody
    // quietly stops logging, and that puts a hole in the twenty-eight-day window for four weeks.
    // A plain "600 over" is a fact where a coloured bar is a verdict.
    //
    // Only for a BUDGET. Passing a FLOOR is the point of having one — see `MacroTargets.Bound` for
    // why protein and fat are floors the planner raises people up to.
    val over = if (target > 0) eaten.roundToInt() - target else 0
    val note = if (over > 0 && macro.bound == MacroTargets.Bound.BUDGET) " — $over over" else ""
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatRow(
            label,
            if (target > 0) "${round(eaten)} / $target $unit$note" else "${round(eaten)} $unit",
        )
        LinearProgressIndicator(
            progress = { share.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = tint ?: MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A pick-one row. Scrolls sideways because five activity levels do not fit a phone.
 *
 * ⚠️ **Here rather than private to a screen, because the third caller was about to be written.** It
 * began as a private helper in `PlanScreen`, and this repo has now corrected a duplicated definition
 * six times — a palette five times over, the day-start rule three times, "render bytes as MB" four
 * times, one of them wrong. A chip row is small enough that copying it looks harmless and drifts
 * exactly the same way: one copy gains a scroll, another does not, and the two rows on two pages
 * behave differently for no reason anybody can see.
 */
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * A figure a person reads, in their own conventions.
 *
 * ⚠️ `Locale.getDefault()`, deliberately, and the opposite of the rule this project keeps relearning
 * for files: a number crossing a boundary another program parses is `Locale.US`; a number on a screen
 * belongs to whoever is looking at it.
 */
fun round(v: Double, places: Int = 0): String =
    String.format(Locale.getDefault(), "%.${places}f", v)

/**
 * A number field's own copy of what is being typed.
 *
 * ⚠️ **Without this a fully controlled numeric field records a number a hundred times too large** —
 * typing `1.25` recorded 125. The rule, the measurements behind it and the three conditions live on
 * [TypedNumber], shared with the LCARS application so the two cannot answer differently; what is
 * here is the two lines of `remember` around it.
 *
 * ⚠️ Both assignments are unconditional, which is [TypedNumber.textFor]'s stated contract: letting
 * `lastSeen` go stale makes the second rule fire on a value the field itself produced, and in the
 * servings case that means the box can be cleared once and then never again.
 */
@Composable
fun rememberTypedNumber(value: String): MutableState<String> {
    val text = remember { mutableStateOf(value) }
    val lastSeen = remember { mutableStateOf(value) }
    text.value = TypedNumber.textFor(text.value, lastSeen.value, value)
    lastSeen.value = value
    return text
}

/**
 * Two lines over the same days: what you ate, and what you burned.
 *
 * ⚠️ **The first drawing of any kind in this application, and it stays deliberately plain.** No axes,
 * no gridlines, no tooltips — the numbers that matter are stated in words directly beneath it, and
 * this is the shape of them. What it adds over the sentence is the thing a sentence cannot carry: a
 * total that is climbing, or a fortnight where the two lines crossed.
 *
 * ⚠️ **Both series are named in words beside the chart, not distinguished by colour alone.**
 * [NutritionTheme] takes the device's dynamic colour scheme on Android 12+, so `primary` and
 * `tertiary` are whatever the wallpaper made them and could be near-identical on some phones — and
 * anybody reading it with a colour-vision difference has no hue to go on either way. The legend is
 * the identification; the colour is a hint.
 *
 * [eaten] is a list of RUNS, so a stretch with nothing logged is a break in the line rather than a
 * straight join across it — see `EnergyBalance.intakeRuns` for why that distinction is not cosmetic.
 * A run of one point draws a dot, since a line needs two.
 */
@Composable
fun EnergyChart(
    eaten: List<List<Pair<Long, Double>>>,
    burned: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
) {
    val eatenColor = MaterialTheme.colorScheme.primary
    val burnedColor = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outlineVariant

    val all = eaten.flatten() + burned
    if (all.size < 2) {
        Box(modifier)
        return
    }
    val tMin = all.minOf { it.first }
    val tMax = all.maxOf { it.first }
    val tSpan = (tMax - tMin).takeIf { it > 0L } ?: 1L
    var vMin = all.minOf { it.second }
    var vMax = all.maxOf { it.second }
    if (vMax - vMin < 1e-9) {
        vMin -= 1.0
        vMax += 1.0
    }
    val vSpan = vMax - vMin

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${vMax.roundToInt()} kcal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${vMin.roundToInt()} kcal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            fun px(t: Long) = ((t - tMin).toDouble() / tSpan * size.width).toFloat()
            fun py(v: Double) = (size.height - (v - vMin) / vSpan * size.height).toFloat()

            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)

            fun polyline(points: List<Pair<Long, Double>>, tint: Color) {
                if (points.size == 1) {
                    // A lone logged day between two gaps. A line needs two points; a dot is the
                    // honest way to say that one day is there.
                    drawCircle(tint, radius = 2.5f, center = Offset(px(points[0].first), py(points[0].second)))
                    return
                }
                val path = Path()
                points.forEachIndexed { i, (t, v) ->
                    val x = px(t)
                    val y = py(v)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, tint, style = Stroke(width = 2.5f))
            }

            eaten.forEach { polyline(it, eatenColor) }
            polyline(burned, burnedColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartKey("Eaten", eatenColor)
            ChartKey("Burned", burnedColor)
        }
    }
}

@Composable
private fun ChartKey(label: String, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(3.dp).width(16.dp).background(tint))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
