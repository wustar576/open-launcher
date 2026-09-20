package app.lawnchair.bugreport

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.lawnchair.util.requireSystemService
import com.android.launcher3.BuildConfig
import com.android.launcher3.R

class BugReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val report = intent.getParcelableExtra<BugReport>("report")!!
        when (intent.action) {
            COPY_ACTION -> copyReport(context, report)
        }
    }

    private fun copyReport(context: Context, report: BugReport) {
        val clipData = ClipData.newPlainText(context.getString(R.string.lawnchair_bug_report), report.contents)
        context.requireSystemService<ClipboardManager>().setPrimaryClip(clipData)
        Toast.makeText(context, R.string.copied_toast, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "${BuildConfig.APPLICATION_ID}.BugReport"

        private const val GROUP_KEY = "${BuildConfig.APPLICATION_ID}.crashes"
        private const val GROUP_ID = 0

        private const val COPY_ACTION = "${BuildConfig.APPLICATION_ID}.bugreport.COPY"

        fun notify(context: Context, report: BugReport) {
            val manager: NotificationManager = context.requireSystemService()
            val notificationId = report.id
            val builder = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(report.getTitle(context))
                .setContentText(report.description)
                .setSmallIcon(R.drawable.ic_bug_notification)
                .setColor(ContextCompat.getColor(context, R.color.bugNotificationColor))
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY)
                .setShowWhen(true)
                .setWhen(report.timestamp)

            val count = manager.activeNotifications.count { it.groupKey == GROUP_KEY }
            val summary = if (count > 99 || count < 0) {
                context.getString(R.string.bugreport_group_summary_multiple)
            } else {
                context.getString(R.string.bugreport_group_summary, count)
            }
            val groupBuilder = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.bugreport_channel_name))
                .setContentText(summary)
                .setSmallIcon(R.drawable.ic_bug_notification)
                .setColor(ContextCompat.getColor(context, R.color.bugNotificationColor))
                .setStyle(
                    Notification.InboxStyle()
                        .setBigContentTitle(summary)
                        .setSummaryText(context.getString(R.string.bugreport_channel_name)),
                )
                .setGroupSummary(true)
                .setGroup(GROUP_KEY)

            val fileUri = report.getFileUri(context)
            if (fileUri != null) {
                val openIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(fileUri, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val pendingOpenIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    openIntent,
                    FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
                )
                builder.setContentIntent(pendingOpenIntent)
            }

            val pendingShareIntent = PendingIntent.getActivity(
                context,
                notificationId,
                report.createShareIntent(context),
                FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
            )
            val icon = Icon.createWithResource(context, R.drawable.ic_share)
            val shareActionBuilder = Notification.Action.Builder(
                icon,
                context.getString(R.string.action_share),
                pendingShareIntent,
            )
            builder.addAction(shareActionBuilder.build())

            val copyIntent = Intent(COPY_ACTION)
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra("report", report)
            val pendingCopyIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                copyIntent,
                FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
            )
            val copyIcon = Icon.createWithResource(context, R.drawable.ic_copy)
            val copyActionBuilder = Notification.Action.Builder(
                copyIcon,
                context.getString(R.string.action_copy),
                pendingCopyIntent,
            )
            builder.addAction(copyActionBuilder.build())

            manager.notify(notificationId, builder.build())
            manager.notify(GROUP_ID, groupBuilder.build())
        }
    }
}
