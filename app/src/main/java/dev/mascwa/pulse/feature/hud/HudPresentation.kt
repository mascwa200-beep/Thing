package dev.mascwa.pulse.feature.hud

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The glanceable J.A.R.V.I.S. HUD rendered on a connected external/wireless display (e.g. display
 * glasses) via Android's [Presentation]. Deliberately plain high-contrast Views (no Compose) so it
 * renders reliably on any secondary display with no Compose-in-Presentation lifecycle pitfalls. All
 * updates are null-safe so a malformed value can never crash the host app.
 */
class HudPresentation(context: Context, display: Display) : Presentation(context, display) {

    private var clock: TextView? = null
    private var nav: TextView? = null
    private var brief: TextView? = null
    private var reply: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(56, 56, 56, 56)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        clock = line(ctx, 52f, NEON_GREEN, bold = true).also { column.addView(it) }
        nav = line(ctx, 34f, NEON_CYAN, bold = true).also { column.addView(it) }
        brief = line(ctx, 24f, Color.WHITE).also { column.addView(it) }
        reply = line(ctx, 24f, NEON_AMBER).also { column.addView(it) }
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        }
        setContentView(scroll)
    }

    private fun line(ctx: Context, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            textSize = sizeSp
            setTextColor(color)
            setPadding(0, 14, 0, 14)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    /** Update the HUD. Safe to call before/after the window exists. [navText] is the active-waypoint
     *  guidance line (arrow + distance + bearing), hidden when there's no tracked waypoint/fix. */
    fun render(clockText: String, navText: String?, briefText: String, replyText: String?) {
        runCatching {
            clock?.text = clockText
            nav?.text = navText?.takeIf { it.isNotBlank() } ?: ""
            nav?.visibility = if (navText.isNullOrBlank()) View.GONE else View.VISIBLE
            brief?.text = briefText
            reply?.text = replyText?.takeIf { it.isNotBlank() }?.let { "J.A.R.V.I.S.: $it" } ?: ""
            reply?.visibility = if (replyText.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        val NEON_GREEN = Color.parseColor("#7CFFB2")
        val NEON_AMBER = Color.parseColor("#FFD166")
        val NEON_CYAN = Color.parseColor("#5EE7FF")
    }
}
