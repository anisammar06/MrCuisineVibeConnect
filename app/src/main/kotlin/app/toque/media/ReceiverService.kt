package app.toque.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.voice.VoiceService
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Récepteur « Envoyer vers Gotuj z Kasią » : petit serveur HTTP sur le réseau local, utilisé par
 * l'appli compagnon du téléphone. Chaque envoi porte le code d'appairage (en-tête X-Code).
 *
 *  GET  /info               nom et version (sans code, pour la découverte)
 *  POST /check              vérifie le code
 *  POST /link   (texte)     lien partagé : YouTube → NewPipe, média → lecteur, page → navigateur
 *  POST /file?name=&mime=   fichier (Content-Length obligatoire) : enregistré puis affiché
 *
 * Le robot s'annonce aussi sur le Wi-Fi (NSD, « _gotuj._tcp ») pour être trouvé sans adresse.
 */
class ReceiverService : Service() {
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        if (!prefs.receiverEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) open()
        return START_STICKY
    }

    private fun open() {
        val socket = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            ToqueApp.of(this).log.write("réception", "port $PORT indisponible : $e")
            stopSelf()
            return
        }
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "toque-reception").start()
        announce()
        ToqueApp.of(this).log.write("réception", "en écoute sur le port $PORT")
    }

    private fun announce() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = NAME
                serviceType = SERVICE_TYPE
                port = PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo?) {}
                override fun onRegistrationFailed(i: NsdServiceInfo?, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo?, code: Int) {}
            }
            val m = getSystemService(Context.NSD_SERVICE) as NsdManager
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            registration = l
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            registration?.let { nsd?.unregisterService(it) }
        } catch (_: Exception) {
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        pool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ HTTP minimal

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val req = read(input) ?: return
            handle(req, out)
        } catch (e: Exception) {
            ToqueApp.of(this).log.write("réception", "erreur : $e")
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val first = line(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val target = parts[1]
        val query = HashMap<String, String>()
        target.substringAfter('?', "").split('&').filter { it.contains('=') }.forEach {
            query[decode(it.substringBefore('='))] = decode(it.substringAfter('='))
        }
        return Request(parts[0].uppercase(), target.substringBefore('?'), query, headers, input)
    }

    private fun decode(s: String) = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    /** Ligne d'en-tête (octets jusqu'à CRLF), sans lire au-delà : le corps reste intact. */
    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 8192) return null
        }
        return b.toString("UTF-8")
    }

    private fun reply(out: OutputStream, code: Int, data: Map<String, Any?>) {
        val body = Json.write(data).toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error"
        }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun handle(r: Request, out: OutputStream) {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        if (r.path == "/info") {
            reply(out, 200, linkedMapOf("app" to "gotuj", "name" to NAME, "version" to version))
            return
        }
        if (r.path == "/courses" || r.path.startsWith("/courses/")) {
            courses(r, out)
            return
        }
        if (r.path == "/assistant" || r.path.startsWith("/assistant/")) {
            assistantKeys(r, out)
            return
        }
        if (r.headers["x-code"] != prefs.receiverCode) {
            reply(out, 401, linkedMapOf("ok" to false, "message" to "Code d'appairage incorrect"))
            return
        }
        when (r.path) {
            "/check" -> reply(out, 200, linkedMapOf("ok" to true, "message" to "Robot appairé"))
            "/link" -> {
                val len = r.headers["content-length"]?.toLongOrNull() ?: 0L
                if (len <= 0 || len > 64 * 1024) {
                    reply(out, 400, linkedMapOf("ok" to false, "message" to "Lien vide ou trop long"))
                    return
                }
                val text = String(readFully(r.body, len.toInt()), Charsets.UTF_8)
                val url = Links.firstUrl(text)
                if (url == null) {
                    reply(out, 400, linkedMapOf("ok" to false, "message" to "Aucun lien trouvé"))
                    return
                }
                Links.prepare(this)
                main.post { Links.open(this, url) }
                reply(out, 200, linkedMapOf("ok" to true, "message" to "Lien ouvert sur le robot"))
            }
            "/file" -> {
                val len = r.headers["content-length"]?.toLongOrNull() ?: -1L
                if (len <= 0) {
                    reply(out, 400, linkedMapOf("ok" to false, "message" to "Taille du fichier manquante"))
                    return
                }
                val free = Received.dir(this).usableSpace
                if (len > free - 200L * 1024 * 1024) {
                    reply(out, 413, linkedMapOf("ok" to false, "message" to "Plus assez de place sur le robot"))
                    return
                }
                val name = r.query["name"].orEmpty().ifEmpty { "fichier" }
                val mime = r.query["mime"].orEmpty()
                val target = Received.target(this, name)
                val tmp = File(target.path + ".part")
                FileOutputStream(tmp).use { copy(r.body, it, len) }
                if (tmp.length() != len || !tmp.renameTo(target)) {
                    tmp.delete()
                    reply(out, 400, linkedMapOf("ok" to false, "message" to "Transfert interrompu"))
                    return
                }
                Received.trim(this, prefs.receivedMaxMb)
                ToqueApp.of(this).log.write("réception", "${target.name} (${Received.size(len)})")
                main.post { MiniPlayer.play(this, target.absolutePath, Received.kindOf(target.name, mime), target.name) }
                reply(out, 200, linkedMapOf("ok" to true, "message" to "Affiché sur le robot"))
            }
            else -> reply(out, 404, linkedMapOf("ok" to false, "message" to "Inconnu"))
        }
    }

    // ------------------------------------------------------------------ liste de courses (téléphone)

    /**
     * Page web de la liste de courses, ouverte en scannant le code QR de l'écran Liste de courses.
     * Le code d'appairage est dans l'adresse (?k=). Les articles se cochent depuis le téléphone ;
     * hors Wi-Fi (au magasin), la page déjà ouverte reste utilisable et coche en local.
     */
    private fun courses(r: Request, out: OutputStream) {
        if (r.query["k"] != prefs.receiverCode) {
            raw(out, 401, "text/html", "<meta name=viewport content='width=device-width'><p style='font:18px sans-serif'>Code incorrect : scannez à nouveau le code QR affiché sur le robot.</p>")
            return
        }
        when (r.path) {
            "/courses" -> raw(out, 200, "text/html", COURSES_PAGE)
            "/courses/data" -> raw(out, 200, "application/json", itemsJson())
            "/courses/toggle" -> {
                val id = r.query["id"].orEmpty()
                val done = r.query["done"] == "1"
                app.toque.kitchen.Shopping.edit(this) { it.setDone(id, done) }
                raw(out, 200, "application/json", itemsJson())
            }
            "/courses/add" -> {
                val len = (r.headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 4096)
                val text = String(readFully(r.body, len), Charsets.UTF_8)
                app.toque.kitchen.Shopping.edit(this) { l -> text.lines().forEach { l.addText(it) } }
                raw(out, 200, "application/json", itemsJson())
            }
            else -> raw(out, 404, "text/plain", "Inconnu")
        }
    }

    // ------------------------------------------------------------------ clés de l'assistant (téléphone)

    /** Page où l'on colle les clés API depuis le téléphone ; elles ne sont jamais renvoyées. */
    private fun assistantKeys(r: Request, out: OutputStream) {
        if (r.query["k"] != prefs.receiverCode) {
            raw(out, 401, "text/html", "<meta name=viewport content='width=device-width'><p style='font:18px sans-serif'>Code incorrect : scannez à nouveau le code QR affiché sur le robot.</p>")
            return
        }
        when (r.path) {
            "/assistant" -> raw(out, 200, "text/html", ASSISTANT_PAGE)
            "/assistant/status" -> raw(out, 200, "application/json", assistantStatus())
            "/assistant/keys" -> {
                val len = (r.headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 8192)
                val m = try {
                    Json.parse(String(readFully(r.body, len), Charsets.UTF_8)).obj()
                } catch (_: Exception) {
                    emptyMap()
                }
                val ant = m.str("anthropic").orEmpty().trim()
                val oai = m.str("openai").orEmpty().trim()
                val sid = m.str("spotify_id").orEmpty().trim()
                val ssec = m.str("spotify_secret").orEmpty().trim()
                if (sid.length >= 20) prefs.spotifyClientId = sid
                if (ssec.length >= 20) prefs.spotifyClientSecret = ssec
                if (ant.startsWith("sk-ant-")) prefs.anthropicKey = ant
                if (oai.startsWith("sk-")) prefs.openaiKey = oai
                if (ant.isNotEmpty() || oai.isNotEmpty()) {
                    prefs.assistantEnabled = true
                    ToqueApp.of(this).log.write("assistant", "clés mises à jour depuis le téléphone")
                    main.post { VoiceService.restart(this) }
                }
                raw(out, 200, "application/json", assistantStatus())
            }
            else -> raw(out, 404, "text/plain", "Inconnu")
        }
    }

    private fun assistantStatus(): String = Json.write(linkedMapOf(
        "anthropic" to prefs.anthropicKey.isNotBlank(), "openai" to prefs.openaiKey.isNotBlank(),
        "voice" to prefs.voiceEnabled, "enabled" to prefs.assistantEnabled,
        "spotify" to (prefs.spotifyClientId.isNotBlank() && prefs.spotifyClientSecret.isNotBlank()),
    ))

    private fun itemsJson(): String = Json.write(app.toque.kitchen.Shopping.get(this).items.map {
        linkedMapOf("id" to it.id, "text" to it.display, "recipes" to it.recipes.joinToString(" · "), "done" to it.done)
    })

    private fun raw(out: OutputStream, code: Int, type: String, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else if (code == 401) "Unauthorized" else "Not Found"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nCache-Control: no-store\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val k = input.read(buf, off, n - off)
            if (k < 0) break
            off += k
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun copy(input: InputStream, out: OutputStream, len: Long) {
        val buf = ByteArray(64 * 1024)
        var left = len
        while (left > 0) {
            val k = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k < 0) break
            out.write(buf, 0, k)
            left -= k
        }
    }

    companion object {
        const val PORT = 8770
        const val NAME = "Gotuj z Kasią"
        const val SERVICE_TYPE = "_gotuj._tcp."

        private val ASSISTANT_PAGE = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Assistant du robot</title>
<style>
body{font:17px system-ui,sans-serif;margin:0;padding:18px;background:#181410;color:#f3ece4}
h1{font-size:22px;margin:0 0 6px}p{color:#bfb3a5;line-height:1.4}
label{display:block;margin:18px 0 6px;font-weight:600}
input{width:100%;box-sizing:border-box;font:16px monospace;padding:12px;border-radius:10px;border:1px solid #5a4a3a;background:#241d17;color:#fff}
button{margin-top:20px;width:100%;padding:14px;font:600 18px system-ui;border:0;border-radius:12px;background:#f0a040;color:#1a0e05}
.st{margin-top:16px;padding:12px;border-radius:10px;background:#241d17}
a{color:#f0a040}
</style></head><body>
<h1>Assistant du robot</h1>
<p>Collez vos clés API. Elles sont enregistrées sur le robot et ne s'affichent plus jamais. Laissez un champ vide pour garder la clé actuelle.</p>
<label>Clé Anthropic (Claude) — commence par sk-ant-</label>
<input id="a" type="password" autocomplete="off" placeholder="sk-ant-…">
<p style="font-size:14px">À créer sur <a href="https://console.anthropic.com/settings/keys">console.anthropic.com</a></p>
<label>Clé OpenAI (voix) — commence par sk-</label>
<input id="o" type="password" autocomplete="off" placeholder="sk-…">
<p style="font-size:14px">À créer sur <a href="https://platform.openai.com/api-keys">platform.openai.com</a></p>
<label>Spotify — Client ID (recherche dans Spotify, facultatif)</label>
<input id="si" type="password" autocomplete="off" placeholder="Client ID">
<label>Spotify — Client secret</label>
<input id="ss" type="password" autocomplete="off" placeholder="Client secret">
<p style="font-size:14px">Sur <a href="https://developer.spotify.com/dashboard">developer.spotify.com</a> (compte Premium) : Create app, nom libre, Redirect URI <code>http://127.0.0.1:8888/callback</code>, cochez « Web API », puis copiez Client ID et Client secret.</p>
<button id="b">Enregistrer sur le robot</button>
<div class="st" id="s">…</div>
<script>
var k=new URLSearchParams(location.search).get('k');
function show(j){document.getElementById('s').innerHTML='Clé Anthropic : '+(j.anthropic?'✅ enregistrée':'❌ manquante')+'<br>Clé OpenAI : '+(j.openai?'✅ enregistrée':'❌ manquante')+'<br>Recherche Spotify : '+(j.spotify?'✅ configurée':'— non configurée')+(j.voice?'':'<br>⚠️ Activez la commande vocale dans les réglages du robot.');}
fetch('/assistant/status?k='+k).then(function(r){return r.json()}).then(show);
document.getElementById('b').onclick=function(){
 var a=document.getElementById('a').value.trim(),o=document.getElementById('o').value.trim(),si=document.getElementById('si').value.trim(),ss=document.getElementById('ss').value.trim();
 if(a&&a.indexOf('sk-ant-')!==0){alert('La clé Anthropic doit commencer par sk-ant-');return}
 if(o&&o.indexOf('sk-')!==0){alert('La clé OpenAI doit commencer par sk-');return}
 fetch('/assistant/keys?k='+k,{method:'POST',body:JSON.stringify({anthropic:a,openai:o,spotify_id:si,spotify_secret:ss})}).then(function(r){return r.json()}).then(function(j){
  ['a','o','si','ss'].forEach(function(x){document.getElementById(x).value=''});show(j);});
};
</script></body></html>"""

        private val COURSES_PAGE = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#16171b">
<title>Courses · Gotuj z Kasią</title>
<style>
body{margin:0;background:#16171b;color:#f3f1ec;font:18px/1.35 system-ui,sans-serif}
header{position:sticky;top:0;background:#16171b;padding:16px 18px 10px;border-bottom:1px solid #2a2c33}
h1{margin:0;font-size:22px;font-weight:600}#st{color:#9a978f;font-size:14px;margin-top:2px}
ul{list-style:none;margin:0;padding:6px 10px 120px}
li{display:flex;align-items:center;gap:14px;padding:14px 10px;border-bottom:1px solid #24262c}
li .b{width:28px;height:28px;border-radius:9px;border:2px solid #555;flex:none;display:flex;align-items:center;justify-content:center;font-size:18px}
li.d .b{border-color:#5fd38d;color:#5fd38d}li.d .t{text-decoration:line-through;color:#77746d}
.r{color:#8b887f;font-size:13px}
form{position:fixed;bottom:0;left:0;right:0;display:flex;gap:8px;padding:12px;background:#1d1f24}
input{flex:1;font-size:18px;padding:12px;border-radius:12px;border:1px solid #333;background:#121317;color:#fff}
button{font-size:17px;padding:12px 16px;border-radius:12px;border:0;background:#ff9a3c;color:#1a0e05;font-weight:600}
#copy{background:#2a2c33;color:#f3f1ec}
</style></head><body>
<header><h1>Liste de courses</h1><div id="st">Chargement…</div></header>
<ul id="l"></ul>
<form id="f"><input id="i" placeholder="Ajouter un article" autocomplete="off"><button>+</button><button type="button" id="copy">Copier</button></form>
<script>
var K=new URLSearchParams(location.search).get('k'),items=[];
try{items=JSON.parse(localStorage.getItem('courses')||'[]')}catch(e){}
function st(t){document.getElementById('st').textContent=t}
function draw(){var u=document.getElementById('l');u.innerHTML='';var left=0;
items.slice().sort(function(a,b){return a.done-b.done}).forEach(function(x){if(!x.done)left++;
var li=document.createElement('li');if(x.done)li.className='d';
li.innerHTML='<span class=b>'+(x.done?'✓':'')+'</span><span><span class=t></span><br><span class=r></span></span>';
li.querySelector('.t').textContent=x.text;li.querySelector('.r').textContent=x.recipes;
li.onclick=function(){x.done=!x.done;save();draw();send('/courses/toggle?k='+K+'&id='+x.id+'&done='+(x.done?1:0))};u.appendChild(li)});
if(!items.length)u.innerHTML='<li>Liste vide</li>';return left}
function save(){try{localStorage.setItem('courses',JSON.stringify(items))}catch(e){}}
function ok(j){items=j;save();var n=draw();st(n+' à acheter · synchronisé avec le robot')}
function send(p,body){fetch(p,{method:body===undefined?'POST':'POST',body:body}).then(function(r){return r.json()}).then(ok).catch(function(){st('Hors du Wi-Fi : cochés sur ce téléphone seulement')})}
function load(){fetch('/courses/data?k='+K).then(function(r){return r.json()}).then(ok).catch(function(){draw();st('Hors du Wi-Fi : dernière liste reçue')})}
document.getElementById('f').onsubmit=function(e){e.preventDefault();var v=document.getElementById('i').value.trim();if(!v)return;document.getElementById('i').value='';send('/courses/add?k='+K,v)};
document.getElementById('copy').onclick=function(){var t=items.filter(function(x){return!x.done}).map(function(x){return'• '+x.text}).join('\n');
var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();try{document.execCommand('copy');st('Liste copiée : collez-la dans une note')}catch(e){}a.remove()};
draw();load();document.addEventListener('visibilitychange',function(){if(!document.hidden)load()});
</script></body></html>"""

        fun start(c: Context) {
            if (Prefs(c).receiverEnabled) c.startService(Intent(c, ReceiverService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ReceiverService::class.java))
        }
    }
}
