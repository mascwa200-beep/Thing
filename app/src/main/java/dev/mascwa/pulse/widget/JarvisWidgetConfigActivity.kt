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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared configuration screen launched when any of the four J.A.R.V.I.S. widgets is placed. Lets the user
 * pick the look — HIGH CONTRAST (solid panel) or TRANSPARENT (text only over the wallpaper) — then renders
 * the widget and finishes. The mode is stored per widget instance in [JarvisWidgetPrefs].
 */
class JarvisWidgetConfigActivity : ComponentActivity() {

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
        setContent { ConfigUi(::choose) }
    }

    private fun choose(mode: JarvisWidgetMode) {
        JarvisWidgetPrefs.setMode(this, appWidgetId, mode)
        val mgr = AppWidgetManager.getInstance(this)
        val type = JarvisWidgetType.forProvider(mgr.getAppWidgetInfo(appWidgetId)?.provider?.className)
            ?: JarvisWidgetType.STATUS
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { JarvisWidgetRenderer.render(this@JarvisWidgetConfigActivity, mgr, appWidgetId, type) }
            withContext(Dispatchers.Main) {
                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
        }
    }
}

@Composable
private fun ConfigUi(onPick: (JarvisWidgetMode) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0E16)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("J.A.R.V.I.S. WIDGET", color = Color(0xFF2DE2E6), fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text("Choose a look", color = Color(0xFFA9B8CE), fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onPick(JarvisWidgetMode.HIGH) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2DE2E6), contentColor = Color(0xFF06121A)),
        ) { Text("HIGH CONTRAST", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onPick(JarvisWidgetMode.LOW) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141B28), contentColor = Color(0xFFE6EFFA)),
        ) { Text("TRANSPARENT (text only)", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
        Text("Re-add the widget to change this later.", color = Color(0xFF5E708C), fontSize = 11.sp)
    }
}
