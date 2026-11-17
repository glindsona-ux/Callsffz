"""Rode DEPOIS de `npx cap add android` (e antes do `cap sync`).
- grava www/config.js com o endereço do site
- põe as permissões de microfone/câmera no AndroidManifest
- liga os links https://SEU-SITE/call/sala/... para abrirem no app (App Links)
- troca o ícone do app, se existir icon.png na raiz do repositório
- usa a chave fixa de assinatura, se existir android/ffz.keystore
"""
import json, pathlib, re, shutil, sys

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

# ---- ícone do app
icone = raiz / "icon.png"
res = raiz / "android" / "app" / "src" / "main" / "res"
if icone.exists():
    try:
        from PIL import Image, ImageDraw
        img = Image.open(icone).convert("RGBA")
        lado = min(img.size)
        esq, topo = (img.width - lado) // 2, (img.height - lado) // 2
        img = img.crop((esq, topo, esq + lado, topo + lado))
        for dens, t in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
            pasta = res / f"mipmap-{dens}"
            pasta.mkdir(parents=True, exist_ok=True)
            q = img.resize((t, t), Image.LANCZOS)
            q.save(pasta / "ic_launcher.png")
            m = Image.new("L", (t * 4, t * 4), 0)
            ImageDraw.Draw(m).ellipse((0, 0, t * 4 - 1, t * 4 - 1), fill=255)
            r = q.copy()
            r.putalpha(m.resize((t, t), Image.LANCZOS))
            r.save(pasta / "ic_launcher_round.png")
        # remove o ícone adaptativo padrão para valer o PNG acima
        shutil.rmtree(res / "mipmap-anydpi-v26", ignore_errors=True)
        print("OK: ícone trocado")
    except Exception as e:
        print("AVISO: não consegui trocar o ícone:", e)
else:
    print("AVISO: sem icon.png na raiz; ficou o ícone padrão")
