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
        super.onCreate(savedInstanceState)
        abrirLink(intent)
        // Android 13+: sem essa permissão a notificação "Você está em call" não aparece
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirLink(intent)
    }

    /** Em segundo plano o WebView (a call) não pode ser pausado. */
    override fun onPause() { super.onPause(); manterWebView() }
    override fun onStop() { super.onStop(); manterWebView() }

    private fun manterWebView() {
        try { bridge?.webView?.let { it.onResume(); it.resumeTimers() } } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        if (isFinishing) {   // fechou o app de vez: encerra o serviço da call
            try { stopService(Intent(this, CallService::class.java)) } catch (e: Throwable) {}
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
}
