package __PACOTE__

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ScreenSharePlugin::class.java)   // plugin de tela + serviço da call (nativo)
        registerPlugin(HavenNotifyPlugin::class.java)   // notificações do Haven (nativo)
        registerPlugin(HavenAtividadePlugin::class.java) // atividade: jogo aberto / música tocando (nativo)
        super.onCreate(savedInstanceState)
        abrirLink(intent)
        abrirNotificacao(intent, frio = true)
        // Android 13+: sem essa permissão a notificação "Você está em call" não aparece
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirLink(intent)
        abrirNotificacao(intent, frio = false)
    }

    /** Em segundo plano o WebView (a call) não pode ser pausado. */
    override fun onStart() { super.onStart(); HavenNotifyPlugin.emPrimeiroPlano = true }
    override fun onPause() { super.onPause(); manterWebView() }
    override fun onStop() { super.onStop(); HavenNotifyPlugin.emPrimeiroPlano = false; manterWebView() }

    private fun manterWebView() {
        try { bridge?.webView?.let { it.onResume(); it.resumeTimers() } } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        if (isFinishing) {   // fechou o app de vez: encerra o serviço da call (o push continua funcionando)
            try { stopService(Intent(this, CallService::class.java)) } catch (e: Throwable) {}
            try { stopService(Intent(this, NotifService::class.java)) } catch (e: Throwable) {}
        }
        super.onDestroy()
    }

    /** Link https://SEU-SITE/call/sala/... (vindo do Discord) abre direto a sala dentro do app. */
    private fun abrirLink(i: Intent?) {
        val u: Uri = i?.data ?: return
        val base = Uri.parse(bridge.config.serverUrl ?: return)
        if (u.scheme == "https" && u.host == base.host && (u.path ?: "").startsWith("/call/")) {
            bridge.webView.post { bridge.webView.loadUrl(u.toString()) }
        }
    }

    /**
     * Toque numa notificação do Haven: abre o canal (e a mensagem). Com o app fechado, o site ainda não carregou:
     * guarda em `pendente` e o site pega em estado(). Com o app já aberto, avisa o site na hora.
     */
    private fun abrirNotificacao(i: Intent?, frio: Boolean) {
        val c = i?.getStringExtra(HavenNotifyPlugin.EXTRA_C) ?: return
        if (c.isEmpty()) return
        val s = i.getStringExtra(HavenNotifyPlugin.EXTRA_S) ?: ""
        val m = i.getStringExtra(HavenNotifyPlugin.EXTRA_M) ?: "0"
        i.removeExtra(HavenNotifyPlugin.EXTRA_C)           // não repete ao girar a tela / voltar
        HavenNotifyPlugin.limpar(this, c)
        if (frio) { HavenNotifyPlugin.pendente = Triple(s, c, m); return }
        val js = "window.dispatchEvent(new CustomEvent('havenAbrirNotif',{detail:{s:${org.json.JSONObject.quote(s)},c:${org.json.JSONObject.quote(c)},m:${org.json.JSONObject.quote(m)}}}))"
        bridge?.webView?.post { try { bridge.webView.evaluateJavascript(js, null) } catch (e: Throwable) {} }
    }
}
