package com.mojo.app.widget
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.mojo.app.R

class MojoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_mojo)
            views.setTextViewText(R.id.widget_status, "Listening")
            manager.updateAppWidget(id, views)
        }
    }
    companion object {
        fun setLastCommand(context: Context, cmd: String) {}
    }
}