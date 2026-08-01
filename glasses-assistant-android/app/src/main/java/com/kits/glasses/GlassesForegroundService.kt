package com.kits.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kits.glasses.automation.PhoneControlService
import com.kits.glasses.brain.CommandPlanner
import com.kits.glasses.core.Cue
import com.kits.glasses.core.GlassesCore
import com.kits.glasses.driver.bluetooth.GenericBluetoothDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The runtime loop. Owns the glasses device and the command loop; runs as a
 * microphone foreground service so it can capture voice while the phone is
 * pocketed.
 *
 * One voice turn:
 *   trigger -> cue(LISTENING) -> Stt.listenOnce -> cue(THINKING)
 *           -> snapshot screen -> CommandPlanner.plan -> perform -> speak result
 */
class GlassesForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var driver: GlassesCore
    private lateinit var planner: CommandPlanner

    /** Guards against overlapping turns if Talk is tapped repeatedly. */
    @Volatile
    private var turnInFlight: Boolean = false

    override fun onCreate() {
        super.onCreate()
        driver = GenericBluetoothDriver(this)
        planner = CommandPlanner(ApiKeys.anthropic)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_TRIGGER) {
            startVoiceTurn()
        }
        return START_STICKY
    }

    private fun startVoiceTurn() {
        if (turnInFlight) return
        turnInFlight = true
        scope.launch {
            try {
                runTurn()
            } finally {
                turnInFlight = false
            }
        }
    }

    private suspend fun runTurn() {
        if (!driver.isConnected) driver.connect()

        // NOTE: SpeechRecognizer owns the mic itself. We do not open a SCO
        // session in the driver during recognition (that would contend for
        // the mic). We cue first, then release audio to the recognizer.
        driver.cue(Cue.LISTENING)
        val transcript = Stt.listenOnce(this)
        if (transcript.isBlank()) {
            driver.cue(Cue.ERROR)
            driver.speak("I didn't catch that.")
            return
        }

        driver.cue(Cue.THINKING)
        val screen = PhoneControlService.instance?.snapshotScreen()
        if (screen == null) {
            driver.speak("Phone control isn't enabled. Please enable the accessibility service.")
            return
        }

        val command = planner.plan(transcript, screen)
        val performed = PhoneControlService.instance?.perform(command) ?: false

        val reply = command.say
            ?: if (performed) "Done." else "I couldn't do that."
        driver.speak(reply)
        driver.cue(if (performed) Cue.DONE else Cue.ERROR)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_desc) }
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(getString(R.string.notif_running_desc))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.launch { driver.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TRIGGER = "com.kits.glasses.action.TRIGGER"
        private const val CHANNEL_ID = "glasses_assistant_runtime"
        private const val NOTIF_ID = 1001

        /** Start the service (if needed) and run one voice turn. */
        fun triggerVoiceTurn(context: Context) {
            val intent = Intent(context, GlassesForegroundService::class.java)
                .setAction(ACTION_TRIGGER)
            context.startForegroundService(intent)
        }

        /** Start the runtime without triggering a turn. */
        fun start(context: Context) {
            val intent = Intent(context, GlassesForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }
}
