package com.mojo.app.widget
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mojo.app.R
import com.mojo.app.ui.home.MainActivity

/** Home screen widget: MOJO's face; tapping it opens the app. */
class MojoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_mojo)
            views.setTextViewText(R.id.widget_status, "Tap to open")
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}
