package __PACOTE__

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * "Manter o Haven conectado": serviço em primeiro plano discreto que impede o Android de matar o app
 * quando você sai da tela. Com o processo vivo, o canal ao vivo (SSE) do site continua recebendo e o app mostra as
 * notificações. NÃO é push de verdade: se você arrastar o app da lista de recentes ou o Android economizar bateria
 * demais (Doze), os avisos podem atrasar ou parar. (Push garantido exige Firebase/FCM + servidor, ainda não incluído.)
 */
class NotifService : Service() {
    companion object {
        @Volatile var rodando = false
        private const val CANAL = "haven_servico"
        private const val ID = 1202

        fun iniciar(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, NotifService::class.java)) }
        fun parar(ctx: Context) { ctx.stopService(Intent(ctx, NotifService::class.java)) }
    }

    private var cpu: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CANAL, "Haven conectado", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Mantém o Haven ativo para receber avisos com a tela apagada"
                    setShowBadge(false)
                }
            )
        }
        val abrir = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (abrir != null) PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat_haven)
            .setContentTitle("Haven conectado")
            .setContentText("Recebendo avisos em segundo plano")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        } catch (e: Throwable) {
            rodando = false
            stopSelf()
            return START_NOT_STICKY
        }
        rodando = true
        try {
            if (cpu == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haven:notif").apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)   // solta sozinho em 12h; reabre ao abrir o app de novo
                }
            }
        } catch (e: Throwable) {}
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        rodando = false
        try { if (cpu?.isHeld == true) cpu?.release() } catch (e: Throwable) {}
        cpu = null
        super.onDestroy()
    }
}
