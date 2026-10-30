package app.trip

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.Instant

/**
 * Home-screen widget: the leg, what to do now and a countdown to the next step. Redrawn by every
 * TripNotifier.refresh (the alarm chain) and by the system every 30 minutes as a fallback; the
 * countdown ticks on its own in between.
 */
class TripWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = update(ctx)

    companion object {
        fun update(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, TripWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(ctx, Instant.now()))
        }

        /** Asks the launcher to add the widget; the launcher shows its own confirmation. */
        fun pin(ctx: Context): Boolean {
            val manager = AppWidgetManager.getInstance(ctx)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(ctx, TripWidget::class.java), null, null)
        }

        private fun views(ctx: Context, now: Instant): RemoteViews {
            val s = statusAt(now, isDone(ctx, "checkin"))
            val v = RemoteViews(ctx.packageName, R.layout.widget_now)
            v.setTextViewText(R.id.widget_leg, "${s.leg.name} · ${s.chip(now)}".uppercase())
            v.setTextViewText(R.id.widget_title, s.doTitle)
            v.setTextViewText(R.id.widget_detail, s.doDetail)
            v.setViewVisibility(R.id.widget_detail, if (s.doDetail.isBlank()) View.GONE else View.VISIBLE)
            val next = s.next?.takeIf { s.leg.kind != Kind.STAY }
            if (next != null) {
                v.setTextViewText(R.id.widget_next, "Next ${next.time} · ${next.title}")
                // Chronometer runs on the elapsedRealtime clock, so the target is shifted into it.
                val base = SystemClock.elapsedRealtime() + (next.at.toEpochMilli() - now.toEpochMilli())
                v.setChronometer(R.id.widget_count, base, "in %s", true)
                v.setChronometerCountDown(R.id.widget_count, true)
                v.setViewVisibility(R.id.widget_next_row, View.VISIBLE)
            } else {
                v.setChronometer(R.id.widget_count, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.widget_next_row, View.GONE)
            }
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            return v
        }
    }
}
