package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps vehicle coaching and speech alive when navigation occupies the foreground. */
class GuideService : Service() {
    private val app get() = application as GuideApp
    private var notificationJob: Job? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private data class Notice(val parked: Boolean, val quiet: Boolean, val enabled: Boolean)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "핸즈온 가이드 주행 안내", NotificationManager.IMPORTANCE_LOW))
        // Meet the foreground-service deadline before performing any SDK IPC.
        startForeground(NOTIFICATION, notification(Notice(false, app.controller.coach.isQuiet, app.controller.store.settings.coachingOn)))
        app.startRuntime()
        val controller = app.controller
        notificationJob = app.scope.launch {
            combine(controller.signals, controller.coach.ui, controller.store.version) { s, _, _ ->
                Notice(isParked(s), controller.coach.isQuiet, controller.store.settings.coachingOn)
            }.distinctUntilChanged().collect { state ->
                withContext(Dispatchers.IO) { manager.notify(NOTIFICATION, notification(state)) }
            }
        }
        val audio = getSystemService(AudioManager::class.java)
        fun updateMode(mode: Int) {
            controller.hub.put(Signals.CALL_ACTIVE, mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
        }
        updateMode(audio.mode)
        if (Build.VERSION.SDK_INT >= 31) {
            modeListener = AudioManager.OnModeChangedListener(::updateMode).also { audio.addOnModeChangedListener(mainExecutor, it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.scope.launch {
            app.awaitReady()
            // Notification buttons disappear while driving, and stale PendingIntents are guarded too.
            if (isParked(app.controller.signals.value)) when (intent?.action) {
                QUIET_DRIVE -> app.controller.coach.quietForDrive()
                RESUME -> app.controller.coach.resumeCoaching()
            }
        }
        return START_STICKY
    }

    private fun notification(state: Notice): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAGS)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("가이드가 함께 보고 있어요")
            .setContentText(when { !state.enabled -> "차가 한 일과 경고등만 알려드려요"
                state.quiet -> "정보 팁은 조용히 · 안전·경고 안내는 계속해요"
                else -> "필요한 순간만 짧게 알려드려요" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (state.parked && state.enabled) {
            val action = if (state.quiet) RESUME else QUIET_DRIVE
            val pending = PendingIntent.getService(this, 1, Intent(this, GuideService::class.java).setAction(action), FLAGS)
            builder.addAction(0, if (state.quiet) "다시 알려줘" else "이번 주행은 조용히", pending)
        }
        return builder.build()
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        if (Build.VERSION.SDK_INT >= 31) modeListener?.let {
            getSystemService(AudioManager::class.java).removeOnModeChangedListener(it)
        }
        app.stopRuntime()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "guide-coaching"
        private const val NOTIFICATION = 2101
        const val QUIET_DRIVE = "io.github.beomsun0829.handsonguide.QUIET_DRIVE"
        const val RESUME = "io.github.beomsun0829.handsonguide.RESUME"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, GuideService::class.java))
        fun isParked(s: Signals): Boolean = s.gear == "P" && s.speedKmh == 0.0 && s.bool(Signals.UXR_DO) != true
    }
}
