package __PACOTE__

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * PUSH (Firebase Cloud Messaging): o servidor manda uma mensagem "só dados" e o Android ACORDA o app, mesmo fechado,
 * pra montar a notificação aqui. Se o app está na tela, quem avisa é o próprio site (banner + som), então não repete.
 * O mesmo aviso que chegar também pelo canal ao vivo não duplica (HavenNotifyPlugin.mostrar ignora mensagem já mostrada).
 */
class HavenFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // o site pega o token atual em HavenNotify.pegarToken() toda vez que o app abre e registra no servidor
        getSharedPreferences("haven", Context.MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val tp = d["tp"] ?: "msg"
        val c = d["c"] ?: ""
        if (c.isEmpty() && tp != "teste") return
        if (HavenNotifyPlugin.emPrimeiroPlano && tp != "teste") return
        HavenNotifyPlugin.mostrar(
            applicationContext,
            d["titulo"] ?: "HAVEN", d["t"] ?: "", d["s"] ?: "", c, d["m"] ?: "0", tp, d["nome"] ?: ""
        )
    }
}
