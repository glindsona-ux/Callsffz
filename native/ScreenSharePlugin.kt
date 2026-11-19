package __PACOTE__

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import org.webrtc.RtpParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transmissão de tela do celular. O site (JS) chama:
 *   Capacitor.Plugins.ScreenShare.start({ url, token })   -> pede permissão, conecta no LiveKit e publica a tela
 *   Capacitor.Plugins.ScreenShare.stop()
 * A tela entra na call como um participante extra "<id>:tela" (o site esconde esse participante e mostra só o vídeo).
 */
@CapacitorPlugin(name = "ScreenShare")
class ScreenSharePlugin : Plugin() {
    private var sala: Room? = null
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @PluginMethod
    fun start(call: PluginCall) {
        if (call.getString("url").isNullOrEmpty() || call.getString("token").isNullOrEmpty()) {
            call.reject("faltam url/token"); return
        }
        if (sala != null) { call.reject("já está transmitindo"); return }
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(call, mpm.createScreenCaptureIntent(), "aoPermitirTela")
    }

    @ActivityCallback
    private fun aoPermitirTela(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val dados: Intent? = result.data
        if (result.resultCode != Activity.RESULT_OK || dados == null) { call.reject("permissão negada"); return }
        val url = call.getString("url") ?: ""
        val token = call.getString("token") ?: ""
        ligarServicoDeVoz()
        escopo.launch {
            try {
                // Qualidade vinda do site (menu "Transmissão de tela"). Padrão: fluidez, 720p 30 fps.
                val fps = (call.getInt("fps", 30) ?: 30).coerceIn(10, 60)
                val altura = call.getInt("altura", 720) ?: 720
                val bitrate = (call.getInt("bitrate", 2500000) ?: 2500000).coerceIn(500000, 8000000)
                val h264 = (call.getString("codec") ?: "h264") != "vp8"   // h264 = codificador do celular (leve, bom pra jogo)
                // resolução da captura: mantém a proporção da tela do celular, lado maior = 1280 (720p) ou 1920 (1080p)
                val dm = context.resources.displayMetrics
                val maior = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
                val alvo = if (altura >= 1080) 1920f else 1280f
                val esc = minOf(1f, alvo / maior)
                val larg = ((dm.widthPixels * esc).toInt() / 2) * 2
                val alt = ((dm.heightPixels * esc).toInt() / 2) * 2
                val opcoes = RoomOptions(
                    screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
                        isScreencast = true,
                        captureParams = VideoCaptureParameter(larg, alt, fps),
                    ),
                    screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
                        videoEncoding = VideoEncoding(bitrate, fps),
                        simulcast = false,   // uma camada só: gasta bem menos CPU/bateria
                        videoCodec = if (h264) "h264" else "vp8",
                        degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,   // se a rede apertar, perde nitidez e não fluidez
                    ),
                )
                val r = LiveKit.create(context.applicationContext, opcoes)
                sala = r
                r.connect(url, token)
                r.localParticipant.setScreenShareEnabled(true, dados)   // SDK 2.12.x: recebe direto o resultado da permissão
                call.resolve()
            } catch (e: Throwable) {
                limpar()
                call.reject(e.message ?: "falha ao transmitir")
            }
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        escopo.launch { limpar(); call.resolve() }
    }

    /** Liga o serviço que mantém a call viva em segundo plano. Só funciona com o microfone já liberado. */
    private fun ligarServicoDeVoz(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return try { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)); true } catch (e: Throwable) { false }
    }

    /** Site chama ao entrar (ligar=true) e ao sair da call (ligar=false). */
    @PluginMethod
    fun servico(call: PluginCall) {
        val ligar = call.getBoolean("ligar", true) ?: true
        if (ligar) {
            val r = JSObject()
            r.put("ativo", ligarServicoDeVoz())
            call.resolve(r)
        } else {
            try { context.stopService(Intent(context, CallService::class.java)) } catch (e: Throwable) {}
            call.resolve()
        }
    }

    /** Pede (uma vez) pro Android não "economizar" este app em segundo plano. */
    @PluginMethod
    fun bateria(call: PluginCall) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
        } catch (e: Throwable) {}
        call.resolve()
    }

    private suspend fun limpar() {
        val r = sala
        sala = null
        try { r?.localParticipant?.setScreenShareEnabled(false) } catch (e: Throwable) {}
        try { r?.disconnect() } catch (e: Throwable) {}
    }
}
