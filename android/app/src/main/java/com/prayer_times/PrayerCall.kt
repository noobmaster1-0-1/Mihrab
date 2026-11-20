package com.prayer_times

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.app.Person
import androidx.core.app.NotificationCompat
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's self-managed Telecom endpoint for a prayer alert.
 *
 * This is an app-owned call (not a cellular/VoIP call). Telecom owns the
 * answer/reject lifecycle; the CallStyle notification is what companion
 * devices can mirror. The original Mihrab alarm remains responsible for sound
 * and its existing full-screen alarm screen.
 */
class PrayerCallConnectionService : ConnectionService() {
  override fun onCreateIncomingConnection(
    connectionManagerPhoneAccount: PhoneAccountHandle?,
    request: ConnectionRequest?,
  ): Connection {
    val outerExtras = request?.extras ?: Bundle()
    val extras = outerExtras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: outerExtras
    val callId = extras.getString(EXTRA_CALL_ID).orEmpty()
    val prayer = extras.getString(EXTRA_PRAYER).orEmpty()
    val targetDate = extras.getString(EXTRA_DATE).orEmpty()
    val notificationId = extras.getString(EXTRA_NOTIFICATION_ID).orEmpty()
    val isTest = extras.getBoolean(EXTRA_TEST, false)

    val connection = PrayerConnection(this, callId, prayer, targetDate, notificationId, isTest)
    connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
    connection.setAddress(Uri.fromParts("sip", "$callId@prayer.mihrab.invalid", null), TelecomManager.PRESENTATION_ALLOWED)
    connection.setCallerDisplayName(if (isTest) "Mihrab test alert" else "$prayer prayer", TelecomManager.PRESENTATION_ALLOWED)
    connection.setAudioModeIsVoip(true)
    connection.setRinging()
    active[callId] = connection
    postCallNotification(this, callId, prayer, isTest)
    return connection
  }

  override fun onCreateIncomingConnectionFailed(
    connectionManagerPhoneAccount: PhoneAccountHandle?,
    request: ConnectionRequest?,
  ) {
    val extras = request?.extras ?: Bundle()
    cancelCallNotification(this, extras.getString(EXTRA_CALL_ID).orEmpty())
  }

  companion object {
    const val ACTION_ANSWER = "com.prayer_times.PRAYER_CALL_ANSWER"
    const val ACTION_DECLINE = "com.prayer_times.PRAYER_CALL_DECLINE"
    const val EXTRA_CALL_ID = "prayerCallId"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_DATE = "targetDate"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_TEST = "isTest"
    private const val CHANNEL_ID = "prayer-incoming-call"
    private const val ACCOUNT_ID = "mihrab-prayer-alerts"
    private const val NOTIFICATION_BASE = 73000

    internal val active = ConcurrentHashMap<String, PrayerConnection>()

    fun showIncoming(context: Context, prayer: String, targetDate: String, notificationId: String, isTest: Boolean = false) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
      val handle = PhoneAccountHandle(
        android.content.ComponentName(context, PrayerCallConnectionService::class.java),
        ACCOUNT_ID,
      )
      val account = PhoneAccount.builder(handle, "Mihrab prayer alerts")
        .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
        .build()
      runCatching { telecom.registerPhoneAccount(account) }

      val callId = if (isTest) "test-${System.currentTimeMillis()}" else notificationId
      if (active.containsKey(callId)) return
      val extras = Bundle().apply {
        putString(EXTRA_CALL_ID, callId)
        putString(EXTRA_PRAYER, prayer)
        putString(EXTRA_DATE, targetDate)
        putString(EXTRA_NOTIFICATION_ID, notificationId)
        putBoolean(EXTRA_TEST, isTest)
      }
      runCatching { telecom.addNewIncomingCall(handle, extras) }
    }

    fun answer(context: Context, callId: String) {
      active[callId]?.answer()
    }

    fun decline(context: Context, callId: String) {
      active[callId]?.reject()
    }

    internal fun finishCall(context: Context, callId: String) {
      active.remove(callId)
      cancelCallNotification(context, callId)
    }

    private fun ensureChannel(context: Context) {
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Prayer call alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming-call style prayer alerts"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
          },
        )
      }
    }

    private fun actionIntent(context: Context, action: String, callId: String, requestCode: Int): PendingIntent {
      val intent = Intent(context, PrayerCallActionReceiver::class.java).apply {
        this.action = action
        putExtra(EXTRA_CALL_ID, callId)
      }
      return PendingIntent.getBroadcast(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    private fun postCallNotification(context: Context, callId: String, prayer: String, isTest: Boolean) {
      ensureChannel(context)
      val decline = actionIntent(context, ACTION_DECLINE, callId, callId.hashCode())
      val answer = actionIntent(context, ACTION_ANSWER, callId, callId.hashCode() xor 0x51A7)
      val builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_prayer)
        .setContentTitle(if (isTest) "Prayer alert test" else prayer)
        .setContentText(if (isTest) "Testing watch call controls" else "Prayer time")
        .setCategory(Notification.CATEGORY_CALL)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setAutoCancel(false)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val person = Person.Builder().setName(if (isTest) "Mihrab test" else "$prayer prayer").setImportant(true).build()
        builder.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
      } else {
        builder.addAction(0, "Decline", decline)
        builder.addAction(0, "Answer", answer)
      }
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.notify(NOTIFICATION_BASE + callId.hashCode().and(0x7fff), builder.build())
    }

    private fun cancelCallNotification(context: Context, callId: String) {
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.cancel(NOTIFICATION_BASE + callId.hashCode().and(0x7fff))
    }
  }

  class PrayerConnection(
    private val context: Context,
    private val callId: String,
    private val prayer: String,
    private val targetDate: String,
    private val notificationId: String,
    private val isTest: Boolean,
  ) : Connection() {
    override fun onAnswer(videoState: Int) {
      complete(answered = true)
    }

    override fun onReject() {
      complete(answered = false)
    }

    override fun onDisconnect() {
      finishCall(context, callId)
      setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
      destroy()
    }

    fun answer() = complete(answered = true)
    fun reject() = complete(answered = false)

    private fun complete(answered: Boolean) {
      if (state == STATE_DISCONNECTED) return
      if (answered && !isTest && prayer in listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")) {
        val service = Intent(context, PrayerAlarmHeadlessService::class.java).apply {
          putExtra("action", "log")
          putExtra("id", notificationId)
          putExtra("title", prayer)
          putExtra("data", Bundle().apply {
            putString("prayer", prayer)
            putString("targetDate", targetDate)
          })
        }
        runCatching {
          context.startService(service)
          com.facebook.react.HeadlessJsTaskService.acquireWakeLockNow(context)
        }
      }
      if (notificationId.isNotEmpty()) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId.hashCode())
      }
      finishCall(context, callId)
      setDisconnected(DisconnectCause(if (answered) DisconnectCause.LOCAL else DisconnectCause.REJECTED))
      destroy()
    }
  }
}

class PrayerCallActionReceiver : android.content.BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(PrayerCallConnectionService.EXTRA_CALL_ID) ?: return
    when (intent.action) {
      PrayerCallConnectionService.ACTION_ANSWER -> PrayerCallConnectionService.answer(context, id)
      PrayerCallConnectionService.ACTION_DECLINE -> PrayerCallConnectionService.decline(context, id)
    }
  }
}

class PrayerCallModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  override fun getName() = "PrayerCall"

  @ReactMethod
  fun startTestCall(prayer: String, promise: Promise) {
    val allowed = setOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
    if (prayer !in allowed) {
      promise.reject("E_INVALID_PRAYER", "Unknown prayer")
      return
    }
    runCatching {
      PrayerCallConnectionService.showIncoming(reactContext, prayer, "", "test-${System.currentTimeMillis()}", true)
    }.onSuccess { promise.resolve(null) }
      .onFailure { promise.reject("E_PRAYER_CALL", it) }
  }
}

class PrayerCallPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
    listOf(PrayerCallModule(reactContext))

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
