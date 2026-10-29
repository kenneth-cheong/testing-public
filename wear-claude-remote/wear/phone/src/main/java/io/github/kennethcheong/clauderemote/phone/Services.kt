package io.github.kennethcheong.clauderemote.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.kennethcheong.clauderemote.data.HttpCall
import io.github.kennethcheong.clauderemote.data.Relay
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Answers the watch over the Data Layer: bridge calls to relay, and "my computers changed". */
class RelayListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != Relay.PATH_HTTP) return null
        return Tasks.call(executor) { Relay.encode(Watcher.relay(Relay.decode<HttpCall>(request))) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Relay.PATH_BRIDGES_CHANGED) Watcher.bridgesChanged()
    }

    private companion object {
        val executor = Executors.newCachedThreadPool()
    }
}

/**
 * Keeps [Watcher] polling with an ongoing notification. ColorOS freezes background apps,
 * so without this the phone would stop relaying and alerting as soon as you leave the app.
 */
class WatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(CHANNEL, "Relay running", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(this, ID, status("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job == null) {
            job = scope.launch {
                Watcher.run(applicationContext) { line ->
                    try {
                        NotificationManagerCompat.from(this@WatcherService).notify(ID, status(line))
                    } catch (e: SecurityException) {
                        // Without notification permission the service still runs; the status just isn't shown.
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun status(line: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Relaying for your watch")
        .setContentText(line)
        .setOngoing(true)
        .setLocalOnly(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        private const val CHANNEL = "relay"
        private const val ID = 1
        private const val PREFS = "relay"
        private const val KEY_ON = "on"

        fun isOn(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ON, true)

        /** Turns the relay on or off, and remembers it for the next boot. */
        fun set(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
            val intent = Intent(context, WatcherService::class.java)
            if (on) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        }
    }
}

/** Starts the relay again after the phone restarts or the app is updated, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && WatcherService.isOn(context)) WatcherService.set(context, true)
    }
}
