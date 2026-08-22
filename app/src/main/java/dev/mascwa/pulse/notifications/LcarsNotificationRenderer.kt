package dev.mascwa.pulse.notifications

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.BriefRowKind
import dev.mascwa.pulse.core.telemetry.BriefUrgency
import dev.mascwa.pulse.core.telemetry.UnifiedBrief
import dev.mascwa.pulse.ui.currentStardateText

/**
 * Renders a [UnifiedBrief] into the two RemoteViews of the one LCARS notification (collapsed + expanded).
 * Deliberately restricted to the three RemoteViews calls that cannot misfire across OEM system UIs —
 * [RemoteViews.setTextViewText], [RemoteViews.setViewVisibility] and
 * `setInt(id, "setBackgroundResource", res)` — over LinearLayout-only layouts (see the layout files for
 * why overlap is impossible by construction). The system template frame around these views comes from
 * DecoratedCustomViewStyle in [Notifier.notifyBrief].
 */
object LcarsNotificationRenderer {

    /** (row container, tag view, text view) for the five expanded rows, in layout order. */
    private val ROWS = listOf(
        Triple(R.id.lcars_row1, R.id.lcars_row1_tag, R.id.lcars_row1_text),
        Triple(R.id.lcars_row2, R.id.lcars_row2_tag, R.id.lcars_row2_text),
        Triple(R.id.lcars_row3, R.id.lcars_row3_tag, R.id.lcars_row3_text),
        Triple(R.id.lcars_row4, R.id.lcars_row4_tag, R.id.lcars_row4_text),
        Triple(R.id.lcars_row5, R.id.lcars_row5_tag, R.id.lcars_row5_text),
    )

    fun collapsed(context: Context, brief: UnifiedBrief): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notification_lcars)
        // The collapsed tag is the alert condition when one is active, else the LCARS wordmark block.
        val (tagText, tagBg) = when (brief.urgency) {
            BriefUrgency.RED -> "RED ALERT" to R.drawable.lcars_tag_alert_red
            BriefUrgency.YELLOW -> "YELLOW ALERT" to R.drawable.lcars_tag_alert_yellow
            BriefUrgency.ROUTINE -> "LCARS" to R.drawable.lcars_tag_news
        }
        rv.setTextViewText(R.id.lcars_tag, tagText)
        rv.setInt(R.id.lcars_tag, "setBackgroundResource", tagBg)
        rv.setTextViewText(R.id.lcars_headline, brief.headline)
        if (brief.tempLabel != null) {
            rv.setTextViewText(R.id.lcars_temp, brief.tempLabel)
            rv.setViewVisibility(R.id.lcars_temp, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.lcars_temp, View.GONE)
        }
        return rv
    }

    fun expanded(context: Context, brief: UnifiedBrief): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notification_lcars_big)
        // When the board was drawn. Computed here rather than carried on UnifiedBrief because the
        // brief is a pure core with no clock, and because a tray notification is re-rendered from
        // the same brief on every post — reading the clock at render time is what keeps the caption
        // honest rather than showing the stardate of whenever the board was first composed.
        rv.setTextViewText(R.id.lcars_stardate, "STARDATE " + currentStardateText())
        brief.rows.take(ROWS.size).forEachIndexed { i, row ->
            val (rowId, tagId, textId) = ROWS[i]
            rv.setViewVisibility(rowId, View.VISIBLE)
            rv.setTextViewText(tagId, context.getString(labelFor(row.kind)))
            rv.setInt(tagId, "setBackgroundResource", tagBgFor(row.kind, brief.urgency))
            rv.setTextViewText(textId, row.text)
        }
        for (i in brief.rows.size until ROWS.size) {
            rv.setViewVisibility(ROWS[i].first, View.GONE)
        }
        return rv
    }

    private fun labelFor(kind: BriefRowKind): Int = when (kind) {
        BriefRowKind.ALERT -> R.string.notif_row_alert
        BriefRowKind.NEWS -> R.string.notif_row_news
        BriefRowKind.MARKETS -> R.string.notif_row_markets
        BriefRowKind.WEATHER -> R.string.notif_row_weather
        BriefRowKind.AGENDA -> R.string.notif_row_agenda
        BriefRowKind.ADVISORY -> R.string.notif_row_advisory
        BriefRowKind.LESSON -> R.string.notif_row_lesson
        BriefRowKind.HEALTH -> R.string.notif_row_health
    }

    /** Row tags use their fixed LCARS colour; the ALERT tag alone follows the live alert condition. */
    private fun tagBgFor(kind: BriefRowKind, urgency: BriefUrgency): Int = when (kind) {
        BriefRowKind.ALERT -> when (urgency) {
            BriefUrgency.RED -> R.drawable.lcars_tag_alert_red
            BriefUrgency.YELLOW -> R.drawable.lcars_tag_alert_yellow
            BriefUrgency.ROUTINE -> R.drawable.lcars_tag_news
        }
        BriefRowKind.NEWS -> R.drawable.lcars_tag_news
        BriefRowKind.MARKETS -> R.drawable.lcars_tag_markets
        BriefRowKind.WEATHER -> R.drawable.lcars_tag_weather
        BriefRowKind.AGENDA -> R.drawable.lcars_tag_agenda
        BriefRowKind.ADVISORY -> R.drawable.lcars_tag_advisory
        BriefRowKind.LESSON -> R.drawable.lcars_tag_lesson
        BriefRowKind.HEALTH -> R.drawable.lcars_tag_health
    }
}
