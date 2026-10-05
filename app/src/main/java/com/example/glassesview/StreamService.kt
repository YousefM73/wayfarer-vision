package com.example.glassesview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Keeps the app alive while it serves the stream to a computer with the screen off or another
 * app in front. Without a visible activity or a foreground service Android soon freezes the
 * process; this service is that foreground service, and its notification shows where to
 * connect and can stop the stream. It also keeps the CPU and Wi-Fi awake, since the phone is
 * relaying video the whole time.
 */
class StreamService : Service() {

  companion object {
    private const val CHANNEL = "stream"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_STOP = "com.example.glassesview.STOP_STREAM"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"

    /** Emits when the notification's Stop is tapped. */
    val stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Starts the service, or refreshes its notification. Only from the foreground. */
    fun start(context: Context, title: String, text: String) {
      ContextCompat.startForegroundService(context, intent(context, title, text))
    }

    /** Changes the notification's text; allowed from the background, unlike [start]. */
    fun update(context: Context, title: String, text: String) {
      val manager = context.getSystemService(NotificationManager::class.java)
      createChannel(manager)
      manager.notify(NOTIFICATION_ID, notification(context, title, text))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, StreamService::class.java))
    }

    private fun intent(context: Context, title: String, text: String) =
        Intent(context, StreamService::class.java)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_TEXT, text)

    private fun createChannel(manager: NotificationManager) {
      manager.createNotificationChannel(
          NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(context: Context, title: String, text: String): Notification {
      val open =
          PendingIntent.getActivity(
              context,
              0,
              Intent(context, MainActivity::class.java),
              PendingIntent.FLAG_IMMUTABLE,
          )
      val stop =
          PendingIntent.getService(
              context,
              1,
              Intent(context, StreamService::class.java).setAction(ACTION_STOP),
              PendingIntent.FLAG_IMMUTABLE,
          )
      return Notification.Builder(context, CHANNEL)
          .setSmallIcon(R.drawable.ic_stream)
          .setContentTitle(title)
          .setContentText(text)
          .setContentIntent(open)
          .setOngoing(true)
          .addAction(Notification.Action.Builder(null, "Stop", stop).build())
          .build()
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onCreate() {
    super.onCreate()
    createChannel(getSystemService(NotificationManager::class.java))
    wakeLock =
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassesView:stream")
            .also { it.acquire() }
    // Low-latency mode only works with the screen on; high-performance mode is the one that
    // keeps Wi-Fi out of power saving while the screen is off.
    @Suppress("DEPRECATION")
    wifiLock =
        getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GlassesView:stream")
            .also { it.acquire() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopRequests.tryEmit(Unit)
      stopSelf()
      return START_NOT_STICKY
    }
    ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification(
            this,
            intent?.getStringExtra(EXTRA_TITLE) ?: "Streaming",
            intent?.getStringExtra(EXTRA_TEXT) ?: "",
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    wifiLock?.release()
    wakeLock?.release()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
