package __PACOTE__

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import livekit.org.webrtc.RtpParameters
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
    private var ouvinteTela: DisplayManager.DisplayListener? = null
    private var ultimaDim = ""
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
                val bitrate = (call.getInt("bitrate", 4000000) ?: 4000000).coerceIn(500000, 12000000)
                val h264 = (call.getString("codec") ?: "h264") != "vp8"   // h264 = codificador do celular (leve, bom pra jogo)
                val nitido = call.getBoolean("nitido", false) ?: false     // true = textos/telas (prioriza nitidez); false = jogo (prioriza movimento)
                // Quadro de captura QUADRADO (lado maior = 1280 em 720p ou 1920 em 1080p). O Android encaixa a tela inteira
                // dentro dele, em pé ou deitada, sem distorcer. Assim, se o celular girar no meio da transmissão (jogo deitado),
                // a imagem continua com a resolução cheia, em vez de ficar minúscula num quadro de formato errado.
                val lado = if (altura >= 1080) 1920 else 1280
                val opcoes = RoomOptions(
                    screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
                        isScreencast = nitido,
                        captureParams = VideoCaptureParameter(lado, lado, fps),
                    ),
                    screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
                        videoEncoding = VideoEncoding(bitrate, fps),
                        simulcast = false,   // uma camada só: gasta bem menos CPU/bateria
                        videoCodec = if (h264) "h264" else "vp8",
                        degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,   // se a rede apertar, perde nitidez e não fluidez
                    ),
                )
                // Esta sala extra SÓ publica a tela: não pode mexer no áudio do celular. Sem isso ela pegava o "foco de áudio"
                // (pausava a música) e puxava o som pro alto-falante em vez do fone. NoAudioHandler = não toca em nada de áudio.
                val r = LiveKit.create(context.applicationContext, opcoes, LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler())))
                sala = r
                r.connect(url, token)
                r.localParticipant.setScreenShareEnabled(true, dados)   // SDK 2.12.x: recebe direto o resultado da permissão
                val d = medirTela()
                ultimaDim = d.first.toString() + "x" + d.second.toString()
                vigiarGiro()
                val res = JSObject()
                res.put("w", d.first)
                res.put("h", d.second)
                call.resolve(res)
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
            soltarSaida()   // saiu da call: devolve o roteamento de áudio pro padrão do Android
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

    /** Tamanho REAL da tela agora (já considera em pé/deitada). Os outros usam isso pra enquadrar a transmissão. */
    @Suppress("DEPRECATION")
    private fun medirTela(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    @PluginMethod
    fun dimensoes(call: PluginCall) {
        val d = medirTela()
        val r = JSObject()
        r.put("w", d.first)
        r.put("h", d.second)
        call.resolve(r)
    }

    /** Avisa o site quando a tela gira (funciona também com o app em segundo plano). */
    private fun vigiarGiro() {
        try {
            if (ouvinteTela != null) return
            val dmgr = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    val d = medirTela()
                    val k = d.first.toString() + "x" + d.second.toString()
                    if (k == ultimaDim) return
                    ultimaDim = k
                    val o = JSObject()
                    o.put("w", d.first)
                    o.put("h", d.second)
                    notifyListeners("tela", o)
                }
            }
            ouvinteTela = l
            dmgr.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {}
    }

    private fun largarGiro() {
        try {
            val l = ouvinteTela
            ouvinteTela = null
            if (l != null) (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(l)
        } catch (e: Throwable) {}
    }

    /** Tela cheia estilo Discord: trava deitado/em pé conforme a transmissão ("paisagem", "retrato" ou "livre"). */
    @PluginMethod
    fun orientacao(call: PluginCall) {
        val modo = call.getString("modo") ?: "livre"
        val v = when (modo) {
            "paisagem" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "retrato" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        try { activity.runOnUiThread { try { activity.requestedOrientation = v } catch (e: Throwable) {} } } catch (e: Throwable) {}
        call.resolve()
    }

    /* ---------- saída de áudio da call (telefone / alto-falante / bluetooth / fone), estilo Discord ---------- */
    private fun audio(): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun categoria(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "telefone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "alto-falante"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> "fone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27, 30 -> "bluetooth"   // 26/27/30 = Bluetooth LE
        else -> "externo"
    }

    @Suppress("DEPRECATION")
    @PluginMethod
    fun saidas(call: PluginCall) {
        val arr = JSArray()
        try {
            val am = audio()
            if (Build.VERSION.SDK_INT >= 31) {
                val atual = am.communicationDevice
                for (d in am.availableCommunicationDevices) {
                    val o = JSObject()
                    o.put("id", d.id)
                    o.put("tipo", categoria(d.type))
                    o.put("nome", d.productName?.toString() ?: "")
                    o.put("ativo", atual != null && atual.id == d.id)
                    arr.put(o)
                }
            } else {
                val saidas = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val bt = saidas.firstOrNull { categoria(it.type) == "bluetooth" }
                val fio = saidas.firstOrNull { categoria(it.type) == "fone" }
                val ativoBt = bt != null && am.isBluetoothScoOn
                val ativoAlto = !ativoBt && am.isSpeakerphoneOn
                val ativoFio = !ativoBt && !ativoAlto && fio != null
                fun item(id: Int, tipo: String, nome: String, ativo: Boolean) {
                    val o = JSObject()
                    o.put("id", id)
                    o.put("tipo", tipo)
                    o.put("nome", nome)
                    o.put("ativo", ativo)
                    arr.put(o)
                }
                item(-1, "telefone", "", !ativoBt && !ativoAlto && !ativoFio)
                item(-2, "alto-falante", "", ativoAlto)
                if (fio != null) item(fio.id, "fone", fio.productName?.toString() ?: "", ativoFio)
                if (bt != null) item(bt.id, "bluetooth", bt.productName?.toString() ?: "", ativoBt)
            }
        } catch (e: Throwable) {}
        val r = JSObject()
        r.put("lista", arr)
        call.resolve(r)
    }

    @Suppress("DEPRECATION")
    @PluginMethod
    fun escolherSaida(call: PluginCall) {
        val id = call.getInt("id", -1) ?: -1
        val tipo = call.getString("tipo") ?: ""
        try {
            val am = audio()
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val d = am.availableCommunicationDevices.firstOrNull { it.id == id }
                if (d == null || !am.setCommunicationDevice(d)) { call.reject("saída indisponível"); return }
            } else {
                when (tipo) {
                    "bluetooth" -> { am.isSpeakerphoneOn = false; am.startBluetoothSco(); am.isBluetoothScoOn = true }
                    "alto-falante" -> { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.isSpeakerphoneOn = true }
                    else -> { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.isSpeakerphoneOn = false }
                }
            }
            call.resolve()
        } catch (e: Throwable) {
            call.reject(e.message ?: "falha ao trocar a saída")
        }
    }

    @Suppress("DEPRECATION")
    private fun soltarSaida() {
        try {
            val am = audio()
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.isSpeakerphoneOn = false }
        } catch (e: Throwable) {}
    }

    private suspend fun limpar() {
        largarGiro()
        val r = sala
        sala = null
        try { r?.localParticipant?.setScreenShareEnabled(false) } catch (e: Throwable) {}
        try { r?.disconnect() } catch (e: Throwable) {}
    }
}
