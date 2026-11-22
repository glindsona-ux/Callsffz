"""Rode DEPOIS de `npx cap add android` (e antes do `cap sync`).
- grava www/config.js com o endereço do site
- põe as permissões de microfone/câmera no AndroidManifest
- liga os links https://SEU-SITE/call/sala/... para abrirem no app (App Links)
- troca o ícone do app, se existir icon.png na raiz do repositório
- usa a chave fixa de assinatura, se existir android/ffz.keystore
"""
import json, pathlib, re, shutil, sys

LIVEKIT_ANDROID = "2.12.3"   # versão do SDK nativo do LiveKit (transmissão de tela do celular)
KOTLIN = "2.0.21"

raiz = pathlib.Path(__file__).resolve().parent.parent
cfg = json.loads((raiz / "calls.config.json").read_text(encoding="utf-8"))
base = cfg["baseUrl"].rstrip("/")
host = re.sub(r"^https?://", "", base).split("/")[0]

(raiz / "www" / "config.js").write_text(f'window.CALLS_BASE = "{base}";\n', encoding="utf-8")

man = raiz / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
if not man.exists():
    sys.exit("android/ não existe ainda: rode `npx cap add android` primeiro.")
x = man.read_text(encoding="utf-8")

perms = ["CAMERA", "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS", "INTERNET", "BLUETOOTH_CONNECT"]
for p in perms:
    linha = f'<uses-permission android:name="android.permission.{p}" />'
    if linha not in x:
        x = x.replace("<application", linha + "\n    <application", 1)

if "android:autoVerify" not in x:
    filtro = f'''
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" android:host="{host}" android:pathPrefix="/call/sala" />
            </intent-filter>
        '''
    x = x.replace("</activity>", filtro + "</activity>", 1)

man.write_text(x, encoding="utf-8")
print("OK: manifest e config.js prontos para", host)

# ---- chave fixa de assinatura (mantém a mesma impressão digital em todo build)
ks = raiz / "android" / "ffz.keystore"
gradle = raiz / "android" / "app" / "build.gradle"
if ks.exists() and gradle.exists():
    g = gradle.read_text(encoding="utf-8")
    if "ffz.keystore" not in g:
        bloco = '''android {
    signingConfigs {
        debug {
            storeFile file("../ffz.keystore")
            storePassword "ffzcalls"
            keyAlias "ffz"
            keyPassword "ffzcalls"
        }
    }'''
        g = g.replace("android {", bloco, 1)
        gradle.write_text(g, encoding="utf-8")
    print("OK: chave fixa de assinatura ligada")
else:
    print("AVISO: sem chave fixa; o APK será assinado com a chave de teste")

# ---- ícone do app (adaptive icon completo: Android 8+ usa o foreground, o resto usa o PNG)
icone = raiz / "icon.png"
res = raiz / "android" / "app" / "src" / "main" / "res"
if icone.exists():
    try:
        from PIL import Image, ImageDraw
        img = Image.open(icone).convert("RGBA")
        lado = min(img.size)
        esq, topo = (img.width - lado) // 2, (img.height - lado) // 2
        img = img.crop((esq, topo, esq + lado, topo + lado))
        # apaga TODO ícone padrão do Capacitor (xml/png/webp) para não sobrar nenhum
        for pasta in res.glob("mipmap-*"):
            shutil.rmtree(pasta, ignore_errors=True)
        for f in list(res.glob("drawable*/ic_launcher_*")):
            f.unlink()
        for f in list(res.glob("values*/ic_launcher_background.xml")):
            f.unlink()
        dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
        for d, t in dens.items():
            pasta = res / f"mipmap-{d}"
            pasta.mkdir(parents=True, exist_ok=True)
            q = img.resize((t, t), Image.LANCZOS)
            q.save(pasta / "ic_launcher.png")
            m = Image.new("L", (t * 4, t * 4), 0)
            ImageDraw.Draw(m).ellipse((0, 0, t * 4 - 1, t * 4 - 1), fill=255)
            r = q.copy(); r.putalpha(m.resize((t, t), Image.LANCZOS)); r.save(pasta / "ic_launcher_round.png")
            # foreground do adaptive icon: canvas 108dp, arte ocupando ~62% (zona segura)
            c = t * 108 // 48
            fg = Image.new("RGBA", (c, c), (0, 0, 0, 0))
            arte = img.resize((int(c * 0.62), int(c * 0.62)), Image.LANCZOS)
            fg.paste(arte, ((c - arte.width) // 2, (c - arte.height) // 2))
            fg.save(pasta / "ic_launcher_foreground.png")
        (res / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
               '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
               '    <background android:drawable="@color/ffz_icon_bg"/>\n'
               '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
               '</adaptive-icon>\n')
        (res / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(xml, encoding="utf-8")
        (res / "mipmap-anydpi-v26" / "ic_launcher_round.xml").write_text(xml, encoding="utf-8")
        (res / "values").mkdir(parents=True, exist_ok=True)
        (res / "values" / "ffz_colors.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ffz_icon_bg">#FFFFFF</color></resources>\n',
            encoding="utf-8")
        print("OK: ícone trocado (adaptive + legado)")
    except Exception as e:
        sys.exit(f"ERRO: não consegui trocar o ícone: {e}")   # falha o build em vez de ficar com o ícone padrão
else:
    print("AVISO: sem icon.png na raiz; ficou o ícone padrão")


# ---- transmissão de tela nativa (Android): plugin Kotlin + SDK do LiveKit
try:
    pacote = cfg["appId"]
    java = raiz / "android" / "app" / "src" / "main" / "java" / pacote.replace(".", "/")
    java.mkdir(parents=True, exist_ok=True)
    for velho in java.glob("MainActivity.*"):
        velho.unlink()                                  # troca o MainActivity.java padrão pelo .kt
    for arq in (raiz / "native").glob("*.kt"):
        (java / arq.name).write_text(arq.read_text(encoding="utf-8").replace("__PACOTE__", pacote), encoding="utf-8")

    # manifest: permissões + serviços
    x = man.read_text(encoding="utf-8")
    for p_ in ["FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PROJECTION", "FOREGROUND_SERVICE_MICROPHONE", "POST_NOTIFICATIONS",
               "WAKE_LOCK", "ACCESS_WIFI_STATE", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "FOREGROUND_SERVICE_SPECIAL_USE", "VIBRATE"]:
        linha = f'<uses-permission android:name="android.permission.{p_}" />'
        if linha not in x:
            x = x.replace("<application", linha + "\n    <application", 1)
    if "CallService" not in x:
        servicos = (f'    <service android:name=".CallService" android:exported="false" android:foregroundServiceType="microphone" />\n'
                    '    <service android:name="io.livekit.android.room.track.screencapture.ScreenCaptureService" '
                    'android:exported="false" android:foregroundServiceType="mediaProjection" />\n    ')
        x = x.replace("</application>", servicos + "</application>", 1)
    if "NotifService" not in x:
        servico_n = ('    <service android:name=".NotifService" android:exported="false" android:foregroundServiceType="specialUse">\n'
                     '        <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Mantem o app conectado para receber avisos de mensagens com a tela apagada" />\n'
                     '    </service>\n    ')
        x = x.replace("</application>", servico_n + "</application>", 1)
    man.write_text(x, encoding="utf-8")

    # ícone pequeno das notificações (Android exige silhueta de uma cor só): balão de conversa
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    (res / "drawable" / "ic_stat_haven.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        '    <path android:fillColor="#FFFFFFFF" android:pathData="M4,3h16a2,2 0 0 1 2,2v11a2,2 0 0 1 -2,2H9l-5,4v-4a2,2 0 0 1 -2,-2V5a2,2 0 0 1 2,-2z"/>\n'
        '</vector>\n', encoding="utf-8")

    andr = raiz / "android"
    # variables.gradle: o LiveKit pede minSdk 24 e compileSdk 35
    var = andr / "variables.gradle"
    v = var.read_text(encoding="utf-8")
    v = re.sub(r"minSdkVersion\s*=\s*\d+", "minSdkVersion = 24", v)
    v = re.sub(r"compileSdkVersion\s*=\s*\d+", "compileSdkVersion = 35", v)
    var.write_text(v, encoding="utf-8")
    gp = andr / "gradle.properties"
    t = gp.read_text(encoding="utf-8") if gp.exists() else ""
    if "suppressUnsupportedCompileSdk" not in t:
        gp.write_text(t.rstrip() + "\nandroid.suppressUnsupportedCompileSdk=35\n", encoding="utf-8")

    # build.gradle (raiz): plugin do Kotlin + repositório extra
    rb = andr / "build.gradle"
    b = rb.read_text(encoding="utf-8")
    if "kotlin-gradle-plugin" not in b:
        b = re.sub(r"(classpath\s+['\"]com\.android\.tools\.build:gradle:[^'\"]+['\"])",
                   r"\1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:" + KOTLIN + "'", b, count=1)
    if "jitpack.io" not in b:
        b = re.sub(r"(allprojects\s*\{\s*repositories\s*\{)", r"\1\n        maven { url 'https://jitpack.io' }", b, count=1)
    rb.write_text(b, encoding="utf-8")

    # app/build.gradle: Kotlin + dependências
    ab = andr / "app" / "build.gradle"
    a = ab.read_text(encoding="utf-8")
    if "kotlin-android" not in a:
        a = a.replace("apply plugin: 'com.android.application'", "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'", 1)
        a = re.sub(r"(?m)^android\s*\{", "android {\n    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }\n    kotlinOptions { jvmTarget = '17' }", a, count=1)
        a += f"""

dependencies {{
    implementation "io.livekit:livekit-android:{LIVEKIT_ANDROID}"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
}}
"""
    ab.write_text(a, encoding="utf-8")
    print("OK: transmissão de tela nativa preparada (LiveKit Android " + LIVEKIT_ANDROID + ")")
except Exception as e:
    sys.exit(f"ERRO ao preparar a transmissão de tela: {e}")
