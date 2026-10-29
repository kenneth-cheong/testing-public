package io.github.kennethcheong.clauderemote.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.kennethcheong.clauderemote.MainActivity
import io.github.kennethcheong.clauderemote.R
import io.github.kennethcheong.clauderemote.data.Alert
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Watch notifications for the phone's alerts: Claude finished, failed, or wants an approval. */
object Alerts {
    private const val CHANNEL = "claude"

    const val EXTRA_BRIDGE = "bridge"
    const val EXTRA_SESSION = "session"
    private const val EXTRA_APPROVAL = "approval"
    private const val EXTRA_ALLOW = "allow"
    private const val EXTRA_NOTIFICATION = "notification"

    fun show(context: Context, alert: Alert) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Claude finished or needs you", NotificationManager.IMPORTANCE_HIGH),
        )
        val id = notificationId(alert.bridgeId, alert.sessionId)
        val heading = when (alert.kind) {
            Alert.APPROVAL -> "Needs approval"
            Alert.FAILED -> "Claude stopped"
            else -> "Claude finished"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$heading · ${alert.machine}")
            .setContentText(alert.text.ifBlank { alert.title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(alert.title, alert.text).filter { it.isNotBlank() }.joinToString("\n")))
            .setContentIntent(openThread(context, alert, id))
            .setAutoCancel(true)
            .setCategory(if (alert.kind == Alert.APPROVAL) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        alert.approvalId?.let { approval ->
            builder.addAction(0, "Allow", answer(context, alert, approval, allow = true, id))
            builder.addAction(0, "Deny", answer(context, alert, approval, allow = false, id))
        }
        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            // Notification permission withdrawn between the check and here; nothing to show.
        }
    }

    private fun notificationId(bridgeId: String, sessionId: String) = "$bridgeId/$sessionId".hashCode()

    private fun openThread(context: Context, alert: Alert, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_BRIDGE, alert.bridgeId)
            .putExtra(EXTRA_SESSION, alert.sessionId)
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun answer(context: Context, alert: Alert, approvalId: String, allow: Boolean, id: Int): PendingIntent {
        val intent = Intent(context, AnswerReceiver::class.java)
            .putExtra(EXTRA_BRIDGE, alert.bridgeId)
            .putExtra(EXTRA_SESSION, alert.sessionId)
            .putExtra(EXTRA_APPROVAL, approvalId)
            .putExtra(EXTRA_ALLOW, allow)
            .putExtra(EXTRA_NOTIFICATION, id)
        val requestCode = id * 2 + if (allow) 1 else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Allow or Deny from the notification, sent the same way the app sends it (through the phone if it's there). */
    class AnswerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bridgeId = intent.getStringExtra(EXTRA_BRIDGE) ?: return
            val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
            val approvalId = intent.getStringExtra(EXTRA_APPROVAL) ?: return
            val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
            val notification = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
            val app = context.applicationContext
            val saved = BridgeSettings(app).bridges.firstOrNull { it.id == bridgeId } ?: return
            val pending = goAsync()
            scope.launch {
                val outcome = try {
                    BridgeClient(saved.url, saved.token, http = PhoneRelay.http(app)).answerApproval(sessionId, approvalId, allow)
                    if (allow) "Allowed" else "Denied"
                } catch (e: IOException) {
                    "Couldn't send: ${e.message ?: "bridge unreachable"}"
                }
                confirm(app, notification, saved.name, outcome)
                pending.finish()
            }
        }
    }

    /** Swaps the approval notification for a quiet one saying what happened. */
    private fun confirm(context: Context, id: Int, machine: String, outcome: String) {
        val manager = NotificationManagerCompat.from(context)
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(outcome)
            .setContentText(machine)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(10_000)
            .build()
        try {
            manager.notify(id, note)
        } catch (e: SecurityException) {
            // As above.
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
