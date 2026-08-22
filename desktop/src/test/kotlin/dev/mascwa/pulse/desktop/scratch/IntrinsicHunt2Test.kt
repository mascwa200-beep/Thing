package dev.mascwa.pulse.desktop.scratch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.desktop.theme.ChartSeries
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGauge
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsRail
import dev.mascwa.pulse.desktop.theme.LcarsScreenFrame
import dev.mascwa.pulse.desktop.theme.LcarsStatBlock
import dev.mascwa.pulse.desktop.theme.LcarsTimeChart
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import org.junit.Test

/** THROWAWAY wave 2. Mechanism proof, then the real loaded SPACE WEATHER tree. */
class IntrinsicHunt2Test {

    private val sizes = listOf(
        1400 to 900, 700 to 400, 400 to 300, 400 to 60, 400 to 24, 400 to 12,
        400 to 4, 400 to 1, 60 to 400, 24 to 24, 1 to 1,
    )
    private val densities = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun hunt(name: String, content: @Composable () -> Unit) {
        val fails = linkedMapOf<String, String>()
        for (d in densities) for ((w, h) in sizes) {
            val out = runCatching {
                renderComposeScene(width = w, height = h, density = Density(d)) {
                    PulseDesktopTheme { content() }
                }
            }
            val e = out.exceptionOrNull() ?: continue
            fails.putIfAbsent(e.cause?.message ?: e.message ?: e.toString(), "${w}x$h @$d")
        }
        if (fails.isEmpty()) println("  ok      $name")
        else fails.forEach { (m, at) -> println("  THREW   $name  [first at $at]  ->  $m") }
    }

    private data class LiarElement(val h: Int) : ModifierNodeElement<LiarNode>() {
        override fun create() = LiarNode(h)
        override fun update(node: LiarNode) { node.h = h }
    }

    private class LiarNode(var h: Int) : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val p = measurable.measure(constraints)
            return layout(p.width, p.height) { p.place(0, 0) }
        }
        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = h
        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = h
        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
            measurable.minIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
            measurable.maxIntrinsicWidth(height)
    }

    private fun Modifier.reportsHeight(h: Int): Modifier = this.then(LiarElement(h))

    private fun report(name: String, content: @Composable () -> Unit) {
        val out = runCatching {
            renderComposeScene(width = 400, height = 300, density = Density(1.5f)) {
                Layout(
                    content = { PulseDesktopTheme { content() } },
                    measurePolicy = object : MeasurePolicy {
                        override fun MeasureScope.measure(
                            measurables: List<Measurable>,
                            constraints: Constraints,
                        ): MeasureResult {
                            measurables.forEach {
                                println("      $name -> minIntrinsicHeight(400) = ${it.minIntrinsicHeight(400)}")
                                println("      $name -> minIntrinsicWidth(300)  = ${it.minIntrinsicWidth(300)}")
                            }
                            val p = measurables.map { it.measure(constraints) }
                            return layout(constraints.maxWidth, constraints.maxHeight) {
                                p.forEach { it.place(0, 0) }
                            }
                        }
                    },
                )
            }
        }
        out.exceptionOrNull()?.let { println("      $name -> THREW ${it.cause?.message ?: it.message}") }
    }

    @Test
    fun `group D - does a negative intrinsic height propagate into a width query`() {
        println("\n===== GROUP D: mechanism =====")

        hunt("D01 Column(width Min), child REPORTS intrinsic height -12") {
            Box(Modifier.height(50.dp)) {
                Column(Modifier.width(IntrinsicSize.Min)) { Box(Modifier.reportsHeight(-12).size(20.dp)) }
            }
        }
        hunt("D02 same, child subtree is a Row (a propagator)") {
            Box(Modifier.height(50.dp)) {
                Column(Modifier.width(IntrinsicSize.Min)) { Row(Modifier.reportsHeight(-12)) { Text("x") } }
            }
        }
        hunt("D03 Row(height Min), child reports intrinsic height -12") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.reportsHeight(-12).width(20.dp).fillMaxHeight())
                Box(Modifier.weight(1f).padding(8.dp))
            }
        }

        println("  -- what do weighted rows/columns RETURN as an intrinsic size? --")
        report("D04 Column ordinary weights") {
            Column { Box(Modifier.weight(1f).size(20.dp)); Box(Modifier.weight(2f).size(20.dp)) }
        }
        report("D05 Column tiny weight 1e-7") {
            Column { Box(Modifier.weight(1e-7f).size(20.dp)); Box(Modifier.weight(1f).size(20.dp)) }
        }
        report("D06 Column Float.MIN_VALUE weight") {
            Column { Box(Modifier.weight(Float.MIN_VALUE).size(20.dp)) }
        }
        report("D07 Row tiny weight 1e-7") {
            Row { Box(Modifier.weight(1e-7f).size(20.dp)); Box(Modifier.weight(1f).size(20.dp)) }
        }
        report("D08 the real LcarsRail") { LcarsRail("SPACE_WEATHER", Modifier.width(24.dp).fillMaxHeight()) }

        hunt("D09 tiny-weight Column inside Column(width Min)") {
            Box(Modifier.height(40.dp)) {
                Column(Modifier.width(IntrinsicSize.Min)) {
                    Column { Box(Modifier.weight(1e-7f).size(20.dp)); Box(Modifier.weight(1f).size(20.dp)) }
                    Box(Modifier.size(20.dp))
                }
            }
        }
        hunt("D10 tiny-weight Row inside Row(height Min)") {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Row { Box(Modifier.weight(1e-7f).size(20.dp)); Box(Modifier.weight(1f).size(20.dp)) }
            }
        }
        hunt("D11 tiny-weight Column inside Row(height Min) - real dialog shape") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(24.dp).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(1e-7f))
                    Box(Modifier.fillMaxWidth().weight(1f))
                }
                Column(Modifier.weight(1f).padding(16.dp)) { Text("body") }
            }
        }
    }

    @Test
    fun `group E - the real kit and a faithful loaded SPACE WEATHER tree`() {
        println("\n===== GROUP E: real kit =====")

        hunt("E01 LcarsRail alone, real weights") {
            LcarsRail("SPACE_WEATHER", Modifier.width(24.dp).fillMaxHeight())
        }
        hunt("E02 LcarsScreenFrame with rail") {
            LcarsScreenFrame("Space weather", Modifier.fillMaxSize(), rail = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LcarsDataRow("Planetary Kp", "4.3")
                }
            }
        }
        hunt("E03 LcarsGauge + LcarsTimeChart") {
            val now = System.currentTimeMillis()
            Column(Modifier.fillMaxSize()) {
                LcarsGauge(4.3, 0.0, 9.0, Modifier.fillMaxWidth().height(120.dp), label = "Kp")
                LcarsTimeChart(
                    listOf(ChartSeries(label = "s", color = androidx.compose.ui.graphics.Color.Cyan, points = (0..40).map { (now + it * 300_000L) to (it % 7).toDouble() })),
                    Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }
        hunt("E04 a faithful LOADED SPACE WEATHER body") {
            val now = System.currentTimeMillis()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                LcarsHeaderBar("Now", Modifier.padding(top = 12.dp))
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column {
                        LcarsDataRow("Planetary Kp", "4.3")
                        LcarsDataRow("Storm level", "G1 minor")
                        LcarsDataRow("Aurora chance", "Possible")
                        LcarsDataRow("Solar wind", "512 km/s")
                        LcarsDataRow("IMF Bz", "-4.1 nT")
                    }
                }
                LcarsHeaderBar("Geomagnetic", Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsGauge(4.3, 0.0, 9.0, Modifier.weight(1f).height(120.dp), label = "Kp")
                    LcarsTimeChart(
                        listOf(ChartSeries(label = "s", color = androidx.compose.ui.graphics.Color.Cyan, points = (0..40).map { (now + it * 300_000L) to (it % 7).toDouble() })),
                        Modifier.weight(2f).height(120.dp),
                    )
                }
                LcarsHeaderBar("X-ray flux", Modifier.padding(top = 12.dp), trailing = "GOES")
                LcarsTimeChart(
                    listOf(ChartSeries(label = "s", color = androidx.compose.ui.graphics.Color.Cyan, points = (0..60).map { (now + it * 300_000L) to (it % 5) * 1e-8 })),
                    Modifier.fillMaxWidth().height(140.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    LcarsStatBlock("KP", "4.3", Modifier.weight(1f))
                    LcarsStatBlock("BZ", "-4.1", Modifier.weight(1f))
                    LcarsStatBlock("WIND", "512", Modifier.weight(1f))
                }
            }
        }
        hunt("E05 the two-pane shape with the loaded body") {
            LcarsScreenFrame("Space weather", Modifier.fillMaxSize(), rail = false, railWidth = 232.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(232.dp).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        repeat(6) { LcarsDataRow("Row $it", "value") }
                        LcarsGauge(4.3, 0.0, 9.0, Modifier.fillMaxWidth().height(120.dp), label = "Kp")
                    }
                }
            }
        }
        hunt("E06 material3 Slider + Surface in the frame") {
            LcarsScreenFrame("Anomalies", Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) { Slider(value = 0.5f, onValueChange = {}); Surface { Text("x") } }
            }
        }
        hunt("E07 LazyColumn of data rows in the frame") {
            LcarsScreenFrame("Library", Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) { items(20) { LcarsDataRow("Row $it", "value") } }
            }
        }
        hunt("E08 LcarsFrame nested three deep") {
            LcarsFrame(Modifier.fillMaxWidth()) {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    LcarsFrame(Modifier.fillMaxWidth()) {
                        Column { LcarsDataRow("a", "b"); LcarsDataRow("c", "d") }
                    }
                }
            }
        }
    }
}
