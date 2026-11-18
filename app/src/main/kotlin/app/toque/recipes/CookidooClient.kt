package app.toque.recipes

import android.content.Context
import android.util.Base64
import app.toque.Prefs
import app.toque.core.json.Json
import app.toque.core.json.int
import app.toque.core.json.obj
import app.toque.core.json.str
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Accès au compte Cookidoo (Thermomix) par l'API de l'appli mobile (non officielle).
 *
 * Connexion OAuth2 « code + PKCE » comme l'appli Cookidoo : le mot de passe ne sert qu'une fois,
 * pour obtenir les jetons ; seuls les jetons sont gardés sur le robot (renouvelés automatiquement).
 * Les certificats racines récents (Let's Encrypt, Amazon, Google) manquent à Android 6 : ils sont
 * fournis avec l'appli (assets/racines.pem) et ajoutés à ceux du système.
 */
class CookidooClient(private val context: Context) {
    class AuthException(message: String) : IOException(message)
    class HttpException(val code: Int, message: String) : IOException(message)

    private val prefs = Prefs(context)

    val loggedIn: Boolean get() = prefs.cookidooRefresh.isNotEmpty()

    // ------------------------------------------------------------------ connexion

    fun login(email: String, password: String) {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val oidc = Json.parse(request("GET", DISCOVERY, browser = true).body).obj()
        val authorize = oidc.str("authorization_endpoint") ?: throw IOException("OIDC")
        val tokenUrl = oidc.str("token_endpoint") ?: throw IOException("OIDC")
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(ByteArray(12).also { SecureRandom().nextBytes(it) })
        val params = linkedMapOf(
            "response_type" to "code", "client_id" to CLIENT_ID, "redirect_uri" to REDIRECT, "market" to "fr",
            "scope" to SCOPE, "state" to state, "code_challenge" to challenge, "code_challenge_method" to "S256", "ui_locales" to "fr-FR",
        )
        // Page de connexion (redirections suivies à la main, avec les cookies de la session).
        var url = authorize + "?" + form(params)
        var html = ""
        for (i in 0 until 10) {
            val r = request("GET", url, cookies = cookies, browser = true, follow = false)
            if (r.code in 300..399 && r.location != null) {
                url = URL(URL(url), r.location).toString()
                continue
            }
            if (r.code != 200) throw IOException("Page de connexion Cookidoo indisponible (${r.code})")
            html = r.body
            break
        }
        val requestId = Regex("<input[^>]*name=[\"']requestId[\"'][^>]*value=[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
            ?: Regex("<input[^>]*value=[\"']([0-9a-f-]{36})[\"'][^>]*name=[\"']requestId[\"']").find(html)?.groupValues?.get(1)
            ?: throw IOException("Page de connexion Cookidoo illisible")
        var target = LOGIN_SRV
        var body: String? = form(mapOf("requestId" to requestId, "username" to email, "password" to password))
        var code: String? = null
        for (i in 0 until 10) {
            val r = request(if (body != null) "POST" else "GET", target, body = body, contentType = FORM, cookies = cookies, browser = true, follow = false)
            val loc = r.location
            if (r.code !in 300..399 || loc == null) break
            if (loc.startsWith(REDIRECT)) {
                val q = query(loc)
                if (q["state"] != state) throw AuthException("Réponse de connexion inattendue")
                code = q["code"]
                break
            }
            target = URL(URL(target), loc).toString()
            if (!target.startsWith(CIAM)) throw AuthException("Redirection inattendue pendant la connexion")
            body = null
        }
        if (code.isNullOrEmpty()) throw AuthException("E-mail ou mot de passe Cookidoo incorrect")
        val t = request("POST", tokenUrl, contentType = FORM, browser = true, body = form(mapOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT,
            "code_verifier" to verifier, "client_id" to CLIENT_ID,
        )))
        if (t.code != 200) throw AuthException("Connexion Cookidoo refusée (${t.code})")
        saveTokens(t.body)
        prefs.cookidooEmail = email
    }

    fun logout() {
        prefs.cookidooAccess = ""
        prefs.cookidooRefresh = ""
        prefs.cookidooExpires = 0
    }

    private fun saveTokens(json: String) {
        val m = Json.parse(json).obj()
        val access = m.str("access_token") ?: throw AuthException("Jetons Cookidoo illisibles")
        prefs.cookidooAccess = access
        m.str("refresh_token")?.let { prefs.cookidooRefresh = it }
        prefs.cookidooExpires = System.currentTimeMillis() + (m.int("expires_in") ?: 43200) * 1000L
    }

    @Synchronized
    private fun token(): String {
        if (!loggedIn) throw AuthException("Compte Cookidoo non connecté (Réglages)")
        if (System.currentTimeMillis() < prefs.cookidooExpires - 5 * 60_000L && prefs.cookidooAccess.isNotEmpty()) return prefs.cookidooAccess
        val oidc = Json.parse(request("GET", DISCOVERY, browser = true).body).obj()
        val r = request("POST", oidc.str("token_endpoint") ?: throw IOException("OIDC"), contentType = FORM, browser = true,
            body = form(mapOf("grant_type" to "refresh_token", "refresh_token" to prefs.cookidooRefresh, "client_id" to CLIENT_ID)))
        if (r.code != 200) {
            if (r.code in 400..401) logout()
            throw AuthException("Session Cookidoo expirée : reconnectez le compte dans les réglages")
        }
        saveTokens(r.body)
        return prefs.cookidooAccess
    }

    // ------------------------------------------------------------------ API

    /** Langue du catalogue → site Cookidoo et code de langue des adresses. */
    private fun host(lang: String) = if (lang == "pl") "https://cookidoo.pl" else "https://cookidoo.fr"
    private fun locale(lang: String) = if (lang == "pl") "pl" else "fr-FR"

    fun get(lang: String, path: String, accept: String = "application/json"): String {
        val url = host(lang) + "/" + path.trimStart('/')
        var r = request("GET", url, headers = mapOf("Authorization" to "Bearer " + token(), "Accept" to accept,
            "Accept-Language" to locale(lang)), browser = true)
        if (r.code == 401) {
            prefs.cookidooExpires = 0
            r = request("GET", url, headers = mapOf("Authorization" to "Bearer " + token(), "Accept" to accept,
                "Accept-Language" to locale(lang)), browser = true)
        }
        if (r.code != 200) throw HttpException(r.code, "Cookidoo : réponse ${r.code}")
        return r.body
    }

    fun recipe(lang: String, id: String): String = get(lang, "recipes/recipe/${locale(lang)}/$id", "application/vnd.vorwerk.recipe.embedded.hal+json")

    fun search(lang: String, query: String, page: Int, size: Int = 20): String {
        val p = linkedMapOf("query" to query, "page" to page.toString(), "pageSize" to size.toString(), "limit" to size.toString())
        return get(lang, "search/" + (if (lang == "pl") "pl" else "fr") + "?" + form(p))
    }

    fun managedLists(lang: String, page: Int): String =
        get(lang, "organize/${locale(lang)}/api/managed-list?page=$page", "application/vnd.vorwerk.organize.managed-list.mobile+json")

    fun customLists(lang: String, page: Int): String =
        get(lang, "organize/${locale(lang)}/api/custom-list?page=$page", "application/vnd.vorwerk.organize.custom-list.mobile+json")

    fun download(url: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        val c = open(url)
        try {
            c.setRequestProperty("User-Agent", BROWSER_UA)
            if (c.responseCode != 200) throw HttpException(c.responseCode, "image ${c.responseCode}")
            return read(c.inputStream, maxBytes)
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ HTTP

    private class Response(val code: Int, val body: String, val location: String?)

    private fun request(
        method: String, url: String, body: String? = null, contentType: String? = null,
        headers: Map<String, String> = emptyMap(), cookies: CookieManager? = null, browser: Boolean = false, follow: Boolean = true,
    ): Response {
        val c = open(url)
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = follow
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", if (browser) BROWSER_UA else "Toque")
            c.setRequestProperty("Accept-Encoding", "gzip")
            if (!headers.containsKey("Accept")) c.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            cookies?.let { cm ->
                val list = cm.cookieStore.get(URI(url))
                if (list.isNotEmpty()) c.setRequestProperty("Cookie", list.joinToString("; ") { "${it.name}=${it.value}" })
            }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            cookies?.let { cm ->
                c.headerFields.filterKeys { it != null && it.equals("Set-Cookie", true) }.values.flatten().forEach { h ->
                    try {
                        HttpCookie.parse(h).forEach { ck -> cm.cookieStore.add(URI(url), ck) }
                    } catch (_: Exception) {
                    }
                }
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = if (stream == null) "" else {
                val s = if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(stream) else stream
                String(read(s, 8 * 1024 * 1024), Charsets.UTF_8)
            }
            return Response(code, text, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = sslFactory(context)
        return c
    }

    private fun read(input: InputStream, max: Int): ByteArray = input.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("réponse trop grande")
        }
        out.toByteArray()
    }

    companion object {
        private const val CIAM = "https://ciam.prod.cookidoo.vorwerk-digital.com"
        private const val DISCOVERY = "$CIAM/.well-known/openid-configuration"
        private const val LOGIN_SRV = "$CIAM/login-srv/login"
        private const val CLIENT_ID = "mobile-android"
        private const val REDIRECT = "com.vorwerk.cookidoo://code-grant"
        private const val SCOPE = "openid profile email offline offline_access"
        private const val FORM = "application/x-www-form-urlencoded; charset=utf-8"
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        private fun form(m: Map<String, String>) = m.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

        private fun query(url: String): Map<String, String> = url.substringAfter('?', "").substringBefore('#').split('&')
            .filter { it.contains('=') }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        @Volatile private var factory: SSLSocketFactory? = null

        /** Certificats du système + racines récentes fournies avec l'appli. */
        fun sslFactory(c: Context): SSLSocketFactory {
            factory?.let { return it }
            val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val cf = CertificateFactory.getInstance("X.509")
            c.assets.open("racines.pem").use { input ->
                cf.generateCertificates(input).forEachIndexed { i, cert -> ks.setCertificateEntry("toque-$i", cert) }
            }
            val bundled = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
            val both = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: java.security.cert.CertificateException) {
                        bundled.checkServerTrusted(chain, authType)
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + bundled.acceptedIssuers
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(both), null)
            return ctx.socketFactory.also { factory = it }
        }
    }
}
