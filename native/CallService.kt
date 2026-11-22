package __PACOTE__

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Mantém a call viva quando o app vai pro segundo plano (abrir o Zap, entrar no jogo...).
 * Serviço em primeiro plano (microfone) + trava de CPU + trava de Wi-Fi: sem isso o Android congela o app
 * e a conexão cai ("signalReconnecting"). Liga ao entrar na call e desliga ao sair.
 */
class CallService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

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
            .setContentTitle("HAVEN")
            .setContentText("Você está em call. Toque para voltar.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1201, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1201, n)
        } catch (e: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        travar()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun travar() {
        try {
            if (cpu == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ffzcall:cpu").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)   // solta sozinho em 6h por segurança
                }
            }
        } catch (e: Throwable) {}
        try {
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ffzcall:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun soltar() {
        try { if (cpu?.isHeld == true) cpu?.release() } catch (e: Throwable) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (e: Throwable) {}
        cpu = null
        wifi = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()   // arrastou o app da lista de recentes: encerra o serviço
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        soltar()
        super.onDestroy()
    }
}
