package __PACOTE__

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Mantém o microfone da call vivo quando o app vai pro segundo plano (ex.: você abre o jogo durante a transmissão). */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val canal = "ffz_call"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(canal, "Call em andamento", NotificationManager.IMPORTANCE_LOW))
        }
        val abrir = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (abrir != null) PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
        val n = NotificationCompat.Builder(this, canal)
            .setContentTitle("FFZ Call")
            .setContentText("Você está em call transmitindo a tela")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1201, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1201, n)
        } catch (e: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
