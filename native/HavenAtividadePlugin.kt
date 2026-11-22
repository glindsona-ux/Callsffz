package __PACOTE__

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Atividade do HAVEN no Android: qual JOGO está aberto e qual MÚSICA está tocando. O site (atividade.js) chama a cada ~15 s:
 *   Capacitor.Plugins.HavenAtividade.ler()            -> { uso, notif, jogo?: {pacote, nome, jogo}, musica?: {pacote, titulo, artista, app, tocando, dur, pos, capa(jpeg b64)} }  (jogo.icone = png b64)
 *   Capacitor.Plugins.HavenAtividade.estado()         -> { uso, notif }
 *   Capacitor.Plugins.HavenAtividade.abrirAcessoUso() / abrirAcessoNotif()   -> abre a tela de permissão do Android
 *
 * Duas permissões que a PESSOA libera nas configurações (o Android não deixa o app liberar sozinho):
 *   • "Acesso de uso"            -> UsageStatsManager: descobre o app em primeiro plano (é assim que separa Free Fire de Free Fire MAX)
 *   • "Acesso às notificações"   -> MediaSessionManager: lê título/artista do que está tocando em QUALQUER player
 * PRIVACIDADE: só sai do celular um app que seja JOGO (categoria "jogo" do Android ou a lista CONHECIDOS abaixo). Nenhum outro app aberto é enviado.
 * No Android 13+ com APK instalado fora da Play Store, o "Acesso às notificações" pode vir bloqueado: Configurações > Apps > HAVEN > ⋮ > "Permitir configurações restritas".
 */
@CapacitorPlugin(name = "HavenAtividade")
class HavenAtividadePlugin : Plugin() {

    companion object {
        private const val GRACA_MS = 120_000L          // depois de sair do jogo (ex.: voltar pro HAVEN pra responder), ainda conta por 2 min
        @Volatile private var ultimoJogo: String? = null
        @Volatile private var ultimoJogoEm = 0L
        // imagens já codificadas (não refazer a cada 15 s): chave -> base64
        @Volatile private var capaChave: String? = null
        @Volatile private var capaB64: String = ""
        private val iconesJogo = HashMap<String, String>()

        /** Espelho do JOGOS do atividade.py: jogos conhecidos mesmo que o Android não os marque como "jogo". */
        private val CONHECIDOS = setOf(
            "com.dts.freefireth", "com.dts.freefiremax", "com.mojang.minecraftpe", "com.roblox.client",
            "com.activision.callofduty.shooter", "com.tencent.ig", "com.pubg.imobile", "com.supercell.brawlstars",
            "com.supercell.clashofclans", "com.supercell.clashroyale", "com.mobile.legends", "com.riotgames.league.wildrift",
            "com.riotgames.league.teamfighttactics", "com.epicgames.fortnite", "com.miHoYo.GenshinImpact", "com.kiloo.subwaysurf",
            "com.innersloth.spacemafia", "com.ea.gp.fifamobile", "com.konami.pesam"
        )
    }

    private fun temAcessoUso(): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val modo = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else
            @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        modo == AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

    private fun temAcessoNotif(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (e: Throwable) { false }

    /** Último app que foi pra frente e ainda não saiu (lê os eventos das últimas 6 h). */
    @Suppress("DEPRECATION")
    private fun primeiroPlano(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val fim = System.currentTimeMillis()
        val ev = usm.queryEvents(fim - 6 * 3600_000L, fim)
        val e = UsageEvents.Event()
        var fg: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> fg = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (fg == e.packageName) fg = null
            }
        }
        return fg
    }

    /** Recorta no centro (quadrado), reduz pra `lado` px e devolve base64. JPEG pra capas, PNG pra ícones (têm transparência). */
    private fun bitmapB64(src: Bitmap?, lado: Int, jpeg: Boolean): String {
        if (src == null || src.width <= 0 || src.height <= 0) return ""
        return try {
            val m = minOf(src.width, src.height)
            val quad = Bitmap.createBitmap(src, (src.width - m) / 2, (src.height - m) / 2, m, m)
            val peq = Bitmap.createScaledBitmap(quad, lado, lado, true)
            val out = ByteArrayOutputStream()
            if (jpeg) peq.compress(Bitmap.CompressFormat.JPEG, 80, out) else peq.compress(Bitmap.CompressFormat.PNG, 100, out)
            val b = out.toByteArray()
            if (b.size > 40_000) "" else Base64.encodeToString(b, Base64.NO_WRAP)   // o servidor também recusa > 45 KB
        } catch (e: Throwable) { "" }
    }

    /** Ícone do app instalado (é assim que Free Fire e Free Fire MAX aparecem cada um com o seu). */
    private fun iconeApp(pacote: String): String {
        iconesJogo[pacote]?.let { return it }
        val b64 = try {
            val d = context.packageManager.getApplicationIcon(pacote)
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 192
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 192
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
            bitmapB64(bmp, 96, false)
        } catch (e: Throwable) { "" }
        if (b64.isNotEmpty()) iconesJogo[pacote] = b64
        return b64
    }

    /** (nome do app, é jogo?) */
    @Suppress("DEPRECATION")
    private fun infoApp(pacote: String): Pair<String, Boolean> {
        val pm = context.packageManager
        val ai = try { pm.getApplicationInfo(pacote, 0) } catch (e: Throwable) { return Pair(pacote, pacote in CONHECIDOS) }
        val nome = try { pm.getApplicationLabel(ai).toString() } catch (e: Throwable) { pacote }
        val jogo = (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) ||
            (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 || pacote in CONHECIDOS
        return Pair(nome, jogo)
    }

    private fun jogoAtual(): JSObject? {
        val agora = System.currentTimeMillis()
        val fg = primeiroPlano()
        var pacote: String? = null
        if (fg != null && fg != context.packageName) {
            if (infoApp(fg).second) { pacote = fg; ultimoJogo = fg; ultimoJogoEm = agora }
            else ultimoJogo = null                                   // foi pra outro app: o jogo acabou
        } else if (ultimoJogo != null && agora - ultimoJogoEm < GRACA_MS) {
            pacote = ultimoJogo                                      // HAVEN na frente (ou tela apagada) logo depois do jogo
        }
        if (pacote == null) return null
        val (nome, jogo) = infoApp(pacote)
        return JSObject().put("pacote", pacote).put("nome", nome).put("jogo", jogo).put("icone", iconeApp(pacote))
    }

    private fun musicaAtual(): JSObject? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessoes = msm.getActiveSessions(ComponentName(context, HavenMediaListener::class.java))
        for (c in sessoes) {
            if (c.packageName == context.packageName) continue
            val st = c.playbackState ?: continue
            if (st.state != PlaybackState.STATE_PLAYING) continue
            val md = c.metadata ?: continue
            val titulo = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: continue
            val artista = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
            // st.position é a posição NO MOMENTO da última atualização do player (o Spotify só atualiza em play/seek/troca de faixa).
            // A posição de agora = position + tempo que passou desde então × velocidade.
            var pos = st.position
            if (st.lastPositionUpdateTime > 0) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            pos = pos.coerceAtLeast(0L)
            if (dur > 0) pos = pos.coerceAtMost(dur)
            // capa do álbum (cada player guarda numa chave diferente)
            val chave = c.packageName + "|" + titulo + "|" + artista
            if (chave != capaChave) {
                val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                capaB64 = bitmapB64(bmp, 160, true)
                capaChave = if (bmp != null) chave else null   // sem capa ainda (o Spotify às vezes demora): tenta de novo no próximo ciclo
            }
            return JSObject().put("pacote", c.packageName).put("titulo", titulo).put("artista", artista)
                .put("app", infoApp(c.packageName).first).put("tocando", true).put("dur", dur).put("pos", pos).put("capa", capaB64)
        }
        return null
    }

    @PluginMethod
    fun estado(call: PluginCall) {
        call.resolve(JSObject().put("uso", temAcessoUso()).put("notif", temAcessoNotif()))
    }

    @PluginMethod
    fun ler(call: PluginCall) {
        val uso = temAcessoUso()
        val notif = temAcessoNotif()
        val r = JSObject().put("uso", uso).put("notif", notif)
        if (uso) try { jogoAtual()?.let { r.put("jogo", it) } } catch (e: Throwable) {}
        if (notif) try { musicaAtual()?.let { r.put("musica", it) } } catch (e: Throwable) {}
        call.resolve(r)
    }

    @PluginMethod
    fun abrirAcessoUso(call: PluginCall) {
        val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(i) } catch (e: Throwable) {
            try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Throwable) {}
        }
        call.resolve()
    }

    @PluginMethod
    fun abrirAcessoNotif(call: PluginCall) {
        try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) {}
        call.resolve()
    }
}
