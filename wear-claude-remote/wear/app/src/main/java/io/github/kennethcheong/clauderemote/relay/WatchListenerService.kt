package io.github.kennethcheong.clauderemote.relay

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.kennethcheong.clauderemote.data.Alert
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import io.github.kennethcheong.clauderemote.data.Relay
import io.github.kennethcheong.clauderemote.data.SharedBridge

/** Answers the phone app: hands over the paired computers, shows its alerts, and passes on "something changed". */
class WatchListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != Relay.PATH_BRIDGES) return null
        val bridges = BridgeSettings(this).bridges.map { SharedBridge(it.id, it.url, it.name, it.token) }
        return Tasks.forResult(Relay.encode(bridges))
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Relay.PATH_ALERT -> Alerts.show(this, Relay.decode<Alert>(event.data))
            Relay.PATH_CHANGED -> PhoneRelay.notifyChanged()
        }
    }
}
