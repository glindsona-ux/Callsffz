import json, pathlib, re, sys

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
