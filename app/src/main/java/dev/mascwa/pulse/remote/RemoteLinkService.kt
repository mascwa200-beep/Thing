package dev.mascwa.pulse.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.PairingProof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Hosts the remote-control listener for as long as the user wants the link up.
 *
 * A dedicated foreground service rather than a poll inside `ActiveMatrixService`: that service only exists
 * when the resident assistant is enabled, and the link has to be independently switchable — the user's
 * whole ask was to turn this on and off whenever. Running as a foreground service also means the ongoing
 * notification makes "this phone is currently accepting remote commands" impossible to miss, which is a
 * feature rather than a nuisance for something that opens a socket.
 *
 * Service running == link listening. Stopping it closes the socket outright; there is no residual
 * listener, and the "Stop" notification action makes that one tap away.
 */
class RemoteLinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Written on Dispatchers.IO, read on the main thread — @Volatile gives the happens-before that
    // plain vars would not, so a pairing request can't observe a half-published server.
    @Volatile private var server: RemoteServer? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var pairingCode: String? = null
    @Volatile private var peers: RemotePeers? = null

    /** Set synchronously in onStartCommand so a second start can't race the first into a double bind. */
    @Volatile private var starting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground FIRST, before any action branching. If the OS created this service
        // fresh to deliver ACTION_PAIR (or ACTION_STOP) and we returned without calling
        // startForeground(), Android would kill the app with ForegroundServiceDidNotStartInTimeException.
        try {
            startForegroundCompat(ongoing(statusLine(null)))
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAIR -> {
                if (server == null && !starting) begin()
                beginPairing()
                return START_STICKY
            }
            ACTION_UNPAIR -> {
                // Revocation has to reach the *live* listener, not just settings: the peer set is an
                // in-memory snapshot, so without this every previously-paired computer would keep
                // command access until the next restart while the UI claimed otherwise.
                scope.launch { runCatching { peers?.forgetAll() } }
                updateOngoing("All computers unpaired.")
                return START_STICKY
            }
        }

        if (server == null && !starting) begin()
        return START_STICKY
    }

    private fun begin() {
        starting = true
        val container = (applicationContext as? PulseApplication)?.container
        if (container == null) {
            starting = false
            updateOngoing("Could not start — app not ready.")
            return
        }

        val identity = RemoteIdentity()
        if (!identity.isAvailable()) {
            // Without a hardware-backed key there is no way to prove this phone's identity, and a link
            // that cannot authenticate is worse than no link. Refuse rather than degrade.
            starting = false
            updateOngoing("Could not start — this device has no usable secure key.")
            return
        }

        val peers = RemotePeers(container.settingsRepository).also { this.peers = it }
        val executor = RemoteCommandExecutor(
            context = applicationContext,
            settings = container.settingsRepository,
            audit = container.auditLedgerStore,
            onRefreshRequested = { RemoteActions.refreshNow(applicationContext) },
            onBriefRequested = { RemoteActions.publishBrief(applicationContext, container) },
            onRadioStop = { RemoteActions.stopRadio(applicationContext) },
            onRadioPlay = { id -> RemoteActions.playRadio(applicationContext, container, id) },
        )

        scope.launch {
            peers.refresh()
            val configured = runCatching { container.settingsRepository.current().remote.port }
                .getOrDefault(RemoteServer.DEFAULT_PORT)
            val srv = RemoteServer(
                identity = identity,
                executor = executor,
                peers = peers,
                onEvent = { message -> updateOngoing(statusLine(message)) },
            )
            // Claim the slot BEFORE binding. If onDestroy lands between start() and publication, a
            // later assignment would leave the listener running with nothing holding a reference to
            // close it — a socket leaked for the life of the process.
            server = srv
            val port = srv.start(configured)
            starting = false
            if (port == null) {
                server = null
                updateOngoing("Could not open port $configured. Another app may be using it.")
                return@launch
            }
            boundPort = port
            updateOngoing(statusLine(null))
        }
    }

    /** Opens a pairing window and puts the code in the notification so it is readable from the shade. */
    private fun beginPairing() {
        val srv = server
        if (srv == null) {
            updateOngoing("Link is not running yet.")
            return
        }
        val code = PairingProof.newCode()
        pairingCode = code
        srv.beginPairing(code)
        updateOngoing("Pairing code $code — expires in 2 minutes. Address: ${address()}")
        scope.launch {
            kotlinx.coroutines.delay(RemoteServer.PAIRING_TTL_MS)
            if (pairingCode == code) {
                pairingCode = null
                srv.cancelPairing()
                updateOngoing(statusLine(null))
            }
        }
    }

    private fun statusLine(event: String?): String {
        if (event != null) return event
        val addr = address()
        return if (addr == null) "Listening — connect from your computer." else "Listening on $addr"
    }

    /** This phone's LAN address, which the user types into the desktop. */
    private fun address(): String? {
        val port = boundPort ?: return null
        val ip = wifiAddress() ?: return null
        return "$ip:$port"
    }

    /**
     * Finds the phone's IPv4 address on the local network. Enumerates interfaces rather than using
     * `WifiManager.connectionInfo` — that path is deprecated, returns 0 without location permission on
     * modern Android, and does not cover a device tethering or on Ethernet.
     */
    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override fun onDestroy() {
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        // connectedDevice, deliberately NOT dataSync: on targetSdk 35 a dataSync FGS cannot be started
        // from BOOT_COMPLETED (so the boot revival would silently never work) and is capped at 6 hours
        // per day, after which onTimeout() fires and the app must stop or ANR. A listener waiting for a
        // paired computer is exactly what connectedDevice describes, and its permission is already
        // declared for the vitals service.
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun createChannel() {
        val manager = notificationManager() ?: return
        val channel = NotificationChannel(
            CHANNEL, "Remote link", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while this phone is accepting remote commands from a paired computer." }
        runCatching { manager.createNotificationChannel(channel) }
    }

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pair = PendingIntent.getService(
            this, 2, Intent(this, RemoteLinkService::class.java).setAction(ACTION_PAIR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(this, R.color.lcars_condition_routine))
            .setSubText("REMOTE LINK")
            .setContentTitle("Remote link")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            // The pairing code and this phone's LAN address ride in this notification; neither belongs
            // on a locked screen where anyone walking past can read them.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, "Pair", pair)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateOngoing(text: String) {
        runCatching { notificationManager()?.notify(NOTIF_ID, ongoing(text)) }
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        private const val CHANNEL = "remote_link"
        private const val NOTIF_ID = 7321
        private const val ACTION_STOP = "dev.mascwa.pulse.remote.STOP"
        private const val ACTION_PAIR = "dev.mascwa.pulse.remote.PAIR"
        private const val ACTION_UNPAIR = "dev.mascwa.pulse.remote.UNPAIR"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteLinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteLinkService::class.java))
        }

        /** Ask the running service to display a fresh pairing code. */
        fun requestPairing(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteLinkService::class.java).setAction(ACTION_PAIR),
            )
        }

        /**
         * Revoke every paired computer on the *live* listener. Clearing the setting alone is not
         * enough — the peer set is an in-memory snapshot, so without this the revocation would not
         * take effect until the service restarted.
         */
        fun requestUnpairAll(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteLinkService::class.java).setAction(ACTION_UNPAIR),
            )
        }
    }
}
