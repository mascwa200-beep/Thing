package dev.mascwa.pulse.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.data.settings.AccentColor
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.NightwireTheme
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen shown when a Computer widget is placed: pick **what it shows** and **how it looks**,
 * then it renders and finishes.
 *
 * ⚠️ It only ever asked the second question. The first was answered by *which of four providers*
 * you dragged out of the picker, which is why there were four — and why two of them had drifted
 * into being indistinguishable. Asking it here is what let the four collapse into one.
 *
 * It was also the last screen in the app rendering outside the console theme: no theme wrapper at
 * all, raw Material buttons, and a hardcoded cyberpunk cyan two palettes out of date. That made it
 * the single reachable path to the vestigial default in `Theme.kt`, so wrapping it closes that too.
 */
class ComputerWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default result: if the user backs out, the widget host cancels placement.
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            // Both arguments are vestigial — the app has one fixed palette and `NightwireTheme`
            // builds from `tosPalette` regardless (see its KDoc). Passed only to satisfy the
            // signature, which is deliberately unchanged so no other call site had to move.
            NightwireTheme(accent = AccentColor.CYAN, amoledBlack = false) {
                ConfigUi(::choose)
            }
        }
    }

    private fun choose(content: ComputerWidgetContent, mode: ComputerWidgetMode) {
        ComputerWidgetPrefs.set(this, appWidgetId, mode, content)
        val mgr = AppWidgetManager.getInstance(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ComputerWidgetRenderer.render(this@ComputerWidgetConfigActivity, mgr, appWidgetId) }
            withContext(Dispatchers.Main) {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            }
        }
    }
}

@Composable
private fun ConfigUi(onDone: (ComputerWidgetContent, ComputerWidgetMode) -> Unit) {
    val c = Pulse.colors
    var content by remember { mutableStateOf(ComputerWidgetContent.DEFAULT) }
    var mode by remember { mutableStateOf(ComputerWidgetMode.HIGH) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.void)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "COMPUTER WIDGET",
            fontFamily = ChakraPetch, color = c.accent,
            fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "WHAT SHOULD IT SHOW",
            fontFamily = ChakraPetch, color = c.muted, fontSize = 11.sp, letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))
        ComputerWidgetContent.entries.forEach { option ->
            LcarsButton(
                text = "${option.title}   ${describe(option)}",
                onClick = { content = option },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                color = if (content == option) c.accent else c.muted,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "HOW SHOULD IT LOOK",
            fontFamily = ChakraPetch, color = c.muted, fontSize = 11.sp, letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))
        LcarsButton(
            text = "PANEL",
            onClick = { mode = ComputerWidgetMode.HIGH },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            color = if (mode == ComputerWidgetMode.HIGH) c.accent else c.muted,
        )
        LcarsButton(
            text = "TRANSPARENT",
            onClick = { mode = ComputerWidgetMode.LOW },
            modifier = Modifier.fillMaxWidth(),
            color = if (mode == ComputerWidgetMode.LOW) c.accent else c.muted,
        )

        Spacer(Modifier.height(20.dp))
        LcarsButton(
            text = "PLACE IT",
            onClick = { onDone(content, mode) },
            modifier = Modifier.fillMaxWidth(),
            color = c.positive,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Re-add the widget to change this later.",
            fontFamily = ChakraPetch, color = c.faint, fontSize = 11.sp,
        )
    }
}

private fun describe(content: ComputerWidgetContent): String = when (content) {
    ComputerWidgetContent.STATUS -> "greeting, battery, network"
    ComputerWidgetContent.OBJECTIVE -> "what you're tracking"
    ComputerWidgetContent.FINDING -> "the latest thing I found"
    ComputerWidgetContent.BRIEF -> "weather and your focus"
}
