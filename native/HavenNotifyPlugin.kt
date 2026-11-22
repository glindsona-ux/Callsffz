package __PACOTE__

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Notificações do HAVEN no Android. O site (JS) chama:
 *   Capacitor.Plugins.HavenNotify.estado()            -> { permitido, servico, bateria, pendente? }
 *   Capacitor.Plugins.HavenNotify.pedirPermissao()    -> { permitido }
 *   Capacitor.Plugins.HavenNotify.mostrar({ titulo, texto, s, c, m, id, tp, nome })
 *   Capacitor.Plugins.HavenNotify.limpar({ c })       -> some a notificação desse canal (c vazio = todas)
 *   Capacitor.Plugins.HavenNotify.iniciarServico() / pararServico()   -> serviço que mantém o app conectado em segundo plano
 *   Capacitor.Plugins.HavenNotify.abrirConfig() / abrirBateria()
 * Quando você toca numa notificação, o app abre direto no canal/mensagem (MainActivity avisa o site).
 */
@CapacitorPlugin(
    name = "HavenNotify",
    permissions = [Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notificacoes")]
)
class HavenNotifyPlugin : Plugin() {

    companion object {
        const val CANAL_MSG = "haven_msg"
        const val CANAL_MEN = "haven_men"
        const val GRUPO = "haven_grupo"
        const val EXTRA_S = "haven_s"
        const val EXTRA_C = "haven_c"
        const val EXTRA_M = "haven_m"

        /** Toque numa notificação com o app fechado: o site lê isso em estado() assim que carrega. */
        @Volatile var pendente: Triple<String, String, String>? = null
        private val linhas = HashMap<String, ArrayList<String>>()   // últimas mensagens de cada canal (pra empilhar)

        /** true enquanto o app está visível (MainActivity.onStart/onStop). Com ele na tela, o push não notifica: o site mostra o banner. */
        @Volatile var emPrimeiroPlano = false
        private val vistos = LinkedHashSet<String>()                 // canal:mensagem já mostrados (o aviso pode chegar por push E pelo canal ao vivo)

        fun criarCanais(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL_MSG, "Mensagens", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Mensagens dos canais que você acompanha"
            })
            nm.createNotificationChannel(NotificationChannel(CANAL_MEN, "Menções e respostas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Quando alguém te marca com @ ou responde uma mensagem sua"
                enableVibration(true)
            })
        }

        fun permitido(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

        fun idDoCanal(c: String): Int = 3000 + (c.hashCode() and 0x3fffffff) % 900000

        fun mostrar(ctx: Context, titulo: String, texto: String, s: String, c: String, m: String, tp: String, nome: String) {
            if (!permitido(ctx)) return
            if (c.isNotEmpty() && m.isNotEmpty() && m != "0") {      // mesma mensagem por dois caminhos: mostra uma vez só
                val k = "$c:$m"
                val nova = synchronized(vistos) {
                    if (vistos.contains(k)) false else { vistos.add(k); if (vistos.size > 300) vistos.remove(vistos.first()); true }
                }
                if (!nova) return
            }
            criarCanais(ctx)
            val abrir = Intent(ctx, MainActivity::class.java).apply {
                action = "haven.abrir." + c + "." + m
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_S, s); putExtra(EXTRA_C, c); putExtra(EXTRA_M, m)
            }
            val id = if (c.isEmpty()) 2999 else idDoCanal(c)
            val pi = PendingIntent.getActivity(ctx, id, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val menc = tp == "men" || tp == "resp" || tp == "todos" || tp == "teste"
            val l = synchronized(linhas) {
                val lista = linhas.getOrPut(c) { ArrayList() }
                lista.add(if (nome.isNotEmpty() && tp != "teste") "$nome: $texto" else texto)
                while (lista.size > 6) lista.removeAt(0)
                ArrayList(lista)
            }
            val b = NotificationCompat.Builder(ctx, if (menc) CANAL_MEN else CANAL_MSG)
                .setSmallIcon(R.drawable.ic_stat_haven)
                .setColor(0xFF5B8CFF.toInt())
                .setContentTitle(titulo)
                .setContentText(texto)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(GRUPO)
                .setOnlyAlertOnce(false)
                .setPriority(if (menc) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            if (l.size > 1) {
                val st = NotificationCompat.InboxStyle().setBigContentTitle(titulo)
                l.forEach { st.addLine(it) }
                b.setStyle(st).setNumber(l.size)
            } else {
                b.setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            }
            try {
                val nm = NotificationManagerCompat.from(ctx)
                nm.notify(id, b.build())
                // resumo do grupo (Android < 7 e agrupamento correto nas versões novas)
                val resumo = NotificationCompat.Builder(ctx, CANAL_MSG)
                    .setSmallIcon(R.drawable.ic_stat_haven).setColor(0xFF5B8CFF.toInt())
                    .setContentTitle("HAVEN").setContentText("Novas mensagens")
                    .setGroup(GRUPO).setGroupSummary(true).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(ctx, 2998, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .build()
                nm.notify(2998, resumo)
            } catch (e: SecurityException) {}
        }

        fun limpar(ctx: Context, c: String) {
            val nm = NotificationManagerCompat.from(ctx)
            if (c.isEmpty()) {
                synchronized(linhas) { linhas.clear() }
                nm.cancelAll()
            } else {
                synchronized(linhas) { linhas.remove(c) }
                nm.cancel(idDoCanal(c))
            }
        }
    }

    override fun load() { criarCanais(context) }

    private fun estadoJS(): JSObject {
        val o = JSObject()
        o.put("permitido", permitido(context))
        o.put("servico", NotifService.rodando)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        o.put("bateria", Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(context.packageName))
        val p = pendente
        if (p != null) {
            pendente = null
            o.put("pendente", JSObject().put("s", p.first).put("c", p.second).put("m", p.third))
        }
        return o
    }

    @PluginMethod
    fun estado(call: PluginCall) { call.resolve(estadoJS()) }

    @PluginMethod
    fun pedirPermissao(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notificacoes") != PermissionState.GRANTED) {
            requestPermissionForAlias("notificacoes", call, "aposPermissao")
        } else {
            call.resolve(JSObject().put("permitido", permitido(context)))
        }
    }

    @PermissionCallback
    private fun aposPermissao(call: PluginCall) {
        criarCanais(context)
        call.resolve(JSObject().put("permitido", permitido(context)))
    }

    @PluginMethod
    fun mostrar(call: PluginCall) {
        mostrar(
            context, call.getString("titulo") ?: "HAVEN", call.getString("texto") ?: "", call.getString("s") ?: "",
            call.getString("c") ?: "", call.getString("m") ?: "0", call.getString("tp") ?: "msg", call.getString("nome") ?: ""
        )
        call.resolve()
    }

    /** Token do Firebase (FCM) deste aparelho: o site manda pro servidor, que passa a empurrar os avisos mesmo com o app fechado. */
    @PluginMethod
    fun pegarToken(call: PluginCall) {
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {   // APK feito sem google-services.json
                call.resolve(JSObject().put("disponivel", false)); return
            }
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                val o = JSObject()
                val tk = if (t.isSuccessful) t.result else null
                if (!tk.isNullOrEmpty()) { o.put("disponivel", true); o.put("token", tk) } else o.put("disponivel", false)
                call.resolve(o)
            }
        } catch (e: Throwable) {
            call.resolve(JSObject().put("disponivel", false))
        }
    }

    @PluginMethod
    fun limpar(call: PluginCall) { limpar(context, call.getString("c") ?: ""); call.resolve() }

    @PluginMethod
    fun definirBadge(call: PluginCall) { call.resolve() }   // o ícone do app já mostra a bolinha pelas notificações

    @PluginMethod
    fun iniciarServico(call: PluginCall) {
        try { NotifService.iniciar(context); call.resolve(JSObject().put("servico", true)) }
        catch (e: Throwable) { call.reject("Não foi possível iniciar o serviço: ${e.message}") }
    }

    @PluginMethod
    fun pararServico(call: PluginCall) { NotifService.parar(context); call.resolve(JSObject().put("servico", false)) }

    @PluginMethod
    fun abrirConfig(call: PluginCall) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (e: Throwable) {}
        call.resolve()
    }

    @PluginMethod
    fun abrirBateria(call: PluginCall) {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Throwable) {
            try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Throwable) {}
        }
        call.resolve()
    }
}
