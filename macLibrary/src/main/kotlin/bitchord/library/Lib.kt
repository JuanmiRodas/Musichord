package bitchord.library

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A file of the library: its path inside it (one Unicode form, for comparing) and as stored. */
data class LibFile(val rel: String, val actual: String, val size: Long, val id: Long? = null)

/**
 * Where the library lives, and the few things done to it. On the Mac's disk: plain files. On
 * pCloud Drive: pCloud's own API — moves happen on pCloud's side at once (nothing uploaded
 * again), uploads are made and confirmed by the app itself, and nothing goes through pCloud
 * Drive's cache (which held renames, refused deletes and made "(1)" copies of folders whose
 * names differed only in accents' form).
 */
interface Lib {
    fun list(): Map<String, LibFile>
    /** null when done, else why not. */
    fun move(from: LibFile, toRel: String): String?
    /** Moves a file out of the library, to [outRel] beside it (relative to the library's parent folder). */
    fun moveOut(from: LibFile, outRel: String): String?
    fun delete(f: LibFile): String?
    fun put(local: File, toRel: String): String?
    /** A readable copy on this Mac (to read its tags), if there is one. */
    fun local(f: LibFile): File?
    fun exists(rel: String): Boolean
    /** Removes folders left empty. */
    fun tidy()
    val name: String
}

private fun nfc(s: String) = Words.nfc(s)

class LocalLib(private val root: String) : Lib {
    override val name = "disco"
    private val rootFile = File(root)
    override fun list(): Map<String, LibFile> = rootFile.walkTopDown().onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && it.extension.lowercase() in Builder.Formats.LIBRARY && !it.name.startsWith(".") }
        .associate { f -> val r = f.path.removePrefix(root).trimStart('/'); nfc(r) to LibFile(nfc(r), r, f.length()) }
    override fun move(from: LibFile, toRel: String): String? = runCatching {
        val to = File(rootFile, toRel); to.parentFile.mkdirs()
        Files.move(File(rootFile, from.actual).toPath(), to.toPath()); null
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    override fun delete(f: LibFile): String? = runCatching { Files.delete(File(rootFile, f.actual).toPath()); null }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    override fun moveOut(from: LibFile, outRel: String): String? = runCatching {
        val to = File(rootFile.parentFile, outRel); to.parentFile.mkdirs()
        Files.move(File(rootFile, from.actual).toPath(), to.toPath()); null
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    override fun put(local: File, toRel: String): String? = runCatching {
        val to = File(rootFile, toRel); to.parentFile.mkdirs()
        Files.move(local.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); null
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    override fun local(f: LibFile) = File(rootFile, f.actual)
    override fun exists(rel: String) = File(rootFile, rel).exists()
    override fun tidy() {
        rootFile.walkBottomUp().filter { it.isDirectory && it != rootFile && !it.path.contains("/.") }.forEach { d ->
            val left = d.listFiles().orEmpty()
            if (left.all { it.name == ".DS_Store" }) { left.forEach { it.delete() }; d.delete() }
        }
    }
}

/**
 * pCloud's API (https://docs.pcloud.com): signed in once from Ajustes with the account's email and
 * password, which go only to pCloud; what is kept is the token pCloud gives back
 * (`<app data>/pcloud.json`), never the password.
 */
class PCloudApi(val host: String, private val auth: String, private val oauth: Boolean = false) {
    /** A session token (`auth=`) or, signed in through pCloud's page, an OAuth token (`access_token=`). */
    private val authParam get() = if (oauth) "access_token" else "auth"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val q = (params + (authParam to auth)).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val c = URL("https://$host/$method?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 120000
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        if (o.optInt("result") != 0) error("pCloud $method: ${o.optInt("result")} ${o.optString("error")}")
        return o
    }

    /** Uploads [file] as [name] into a folder (replacing a file of that name), in one go. */
    fun upload(folderId: Long, name: String, file: File): JSONObject {
        val boundary = "----bitchord" + System.nanoTime()
        val c = URL("https://$host/uploadfile?$authParam=${enc(auth)}&folderid=$folderId&nopartial=1&renameifexists=0").openConnection() as HttpURLConnection
        c.doOutput = true; c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 600000
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "'")}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        c.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        c.outputStream.use { out -> out.write(head); file.inputStream().use { it.copyTo(out, 1 shl 16) }; out.write(tail) }
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        if (o.optInt("result") != 0) error("pCloud uploadfile: ${o.optInt("result")} ${o.optString("error")}")
        return o
    }

    companion object {
        private val file get() = File(Store.dir, "pcloud.json")

        fun saved(): PCloudApi? = runCatching { JSONObject(file.readText()).let { PCloudApi(it.getString("host"), it.getString("auth"), it.optBoolean("oauth")) } }.getOrNull()

        /** pCloud's sign-in page (OAuth, token flow) for the app registered in pCloud's "My Apps" — the same one as the phone. */
        const val REDIRECT = "https://localhost/bitchord-pcloud"
        fun authorizeUrl(clientId: String) = "https://my.pcloud.com/oauth2/authorize?client_id=" + URLEncoder.encode(clientId.trim(), "UTF-8") +
            "&response_type=token&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")

        /**
         * The address the browser ends at after signing in ("https://localhost/bitchord-pcloud#access_token=…"),
         * pasted back: its token kept.
         */
        fun fromRedirect(url: String): Result<PCloudApi> = runCatching {
            val fragment = url.trim().substringAfter('#', "").ifBlank { url.trim().substringAfter('?', "") }
            val p = fragment.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            val token = p["access_token"]?.takeIf { it.isNotBlank() } ?: error("esa dirección no lleva el permiso de pCloud (access_token)")
            val host = p["hostname"]?.takeIf { it.isNotBlank() } ?: if (p["locationid"] == "2") "eapi.pcloud.com" else "api.pcloud.com"
            val api = PCloudApi(host, token, oauth = true)
            val email = runCatching { api.call("userinfo").optString("email") }.getOrElse { error("pCloud no acepta ese permiso: ${it.message}") }
            file.writeText(JSONObject().put("host", host).put("auth", token).put("oauth", true).put("email", email).toString())
            file.setReadable(false, false); file.setReadable(true, true)
            api
        }

        fun account(): String? = runCatching { JSONObject(file.readText()).optString("email").ifBlank { null } }.getOrNull()

        private fun post(host: String, method: String, params: Map<String, String>): JSONObject? = runCatching {
            val body = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val c = URL("https://$host/$method").openConnection() as HttpURLConnection
            c.doOutput = true; c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 30000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            JSONObject((if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText())
        }.getOrNull()

        private fun device() = mapOf(
            "deviceid" to "bitchord-library-mac", "device" to "BitChord Library (Mac)", "os" to "4",
            // A year, renewed by use; revocable from pcloud.com → Settings → Devices.
            "authexpire" to (365 * 24 * 3600).toString(), "authinactiveexpire" to (90 * 24 * 3600).toString(),
        )

        private fun keep(host: String, o: JSONObject, email: String): PCloudApi? {
            val t = o.optString("auth").ifBlank { return null }
            val h = o.optString("apiserver").ifBlank { host }
            file.writeText(JSONObject().put("host", h).put("auth", t).put("email", email).toString())
            file.setReadable(false, false); file.setReadable(true, true)
            return PCloudApi(h, t)
        }

        /** A code pCloud sent by email (or SMS/app for two-step accounts), waiting for [finishLogin]. */
        @Volatile private var pending: Pair<String, String>? = null // token → host

        /**
         * Signs in the way pCloud's own apps do (and the phone): `login` with a device; when
         * pCloud wants a code it emails one and answers with a token for it. Returns true when
         * signed in, false when a code is needed ([finishLogin]).
         */
        fun startLogin(email: String, password: String): Result<Boolean> = runCatching {
            var last = "sin respuesta"
            for (host in listOf("eapi.pcloud.com", "api.pcloud.com")) {
                val o = post(host, "login", mapOf("getauth" to "1", "username" to email, "password" to password) + device()) ?: continue
                if (o.optInt("result", -1) == 0 && keep(host, o, email) != null) return@runCatching true
                val token = o.optString("token")
                if (token.isNotBlank()) { pending = token to o.optString("apiserver").ifBlank { host }; return@runCatching false }
                last = "${o.optInt("result")}: ${o.optString("error")}"
                if (o.optInt("result") != 2321 && o.optInt("result") != 2000) break
            }
            error("pCloud no aceptó el inicio de sesión ($last)")
        }

        fun finishLogin(email: String, code: String): Result<PCloudApi> = runCatching {
            val (token, host) = pending ?: error("pCloud no ha enviado ningún código: vuelve a iniciar sesión")
            val o = post(host, "tfa_login", mapOf("getauth" to "1", "token" to token, "code" to code.trim(), "trustdevice" to "1") + device()) ?: error("pCloud no respondió")
            if (o.optInt("result", -1) != 0) error("pCloud no aceptó el código (${o.optInt("result")}: ${o.optString("error")})")
            pending = null
            keep(host, o, email) ?: error("pCloud no devolvió permiso")
        }

        fun logout() { file.delete() }
    }
}

class PCloudLib(private val api: PCloudApi, private val mountRoot: String) : Lib {
    override val name = "pCloud"
    /** "/Users/juanmi/pCloud Drive/Biblioteca" → "/Biblioteca". */
    private val remoteRoot = "/" + mountRoot.substringAfter("/pCloud Drive").trim('/')
    private val folders = java.util.concurrent.ConcurrentHashMap<String, Long>() // rel (one form, lower case) → folder id
    private val folderNames = java.util.concurrent.ConcurrentHashMap<String, String>() // rel (one form, lower case) → its name as pCloud has it
    private val files = java.util.concurrent.ConcurrentHashMap<String, LibFile>()

    override fun list(): Map<String, LibFile> {
        files.clear(); folders.clear(); folderNames.clear()
        val o = api.call("listfolder", mapOf("path" to remoteRoot, "recursive" to "1")).getJSONObject("metadata")
        folders[""] = o.getLong("folderid")
        fun walk(m: JSONObject, rel: String) {
            val list = m.optJSONArray("contents") ?: return
            for (k in 0 until list.length()) {
                val x = list.getJSONObject(k)
                val n = x.getString("name")
                if (n.startsWith(".")) continue
                val r = if (rel.isEmpty()) n else "$rel/$n"
                if (x.optBoolean("isfolder")) { folders.putIfAbsent(nfc(r).lowercase(), x.getLong("folderid")); folderNames.putIfAbsent(nfc(r).lowercase(), n); walk(x, r) }
                else if (n.substringAfterLast('.').lowercase() in Builder.Formats.LIBRARY) files[nfc(r)] = LibFile(nfc(r), r, x.optLong("size"), x.getLong("fileid"))
            }
        }
        walk(o, "")
        return HashMap(files)
    }

    @Synchronized private fun folder(rel: String): Long {
        if (rel.isEmpty()) return folders[""]!!
        folders[nfc(rel).lowercase()]?.let { id ->
            // Same folder, other capitals ("Buck-Tick" for "BUCK-TICK"): renamed to the plan's spelling.
            val want = nfc(rel.substringAfterLast('/'))
            val have = folderNames[nfc(rel).lowercase()]
            if (have != null && have != want) runCatching { api.call("renamefolder", mapOf("folderid" to id.toString(), "toname" to want)); folderNames[nfc(rel).lowercase()] = want }
            return id
        }
        val parent = folder(rel.substringBeforeLast('/', ""))
        val id = api.call("createfolderifnotexists", mapOf("folderid" to parent.toString(), "name" to nfc(rel.substringAfterLast('/')))).getJSONObject("metadata").getLong("folderid")
        folders[nfc(rel).lowercase()] = id
        folderNames[nfc(rel).lowercase()] = nfc(rel.substringAfterLast('/'))
        return id
    }

    override fun move(from: LibFile, toRel: String): String? = runCatching {
        val to = folder(toRel.substringBeforeLast('/', ""))
        api.call("renamefile", mapOf("fileid" to from.id.toString(), "tofolderid" to to.toString(), "toname" to nfc(toRel.substringAfterLast('/'))))
        files.remove(from.rel); files[nfc(toRel)] = from.copy(rel = nfc(toRel), actual = toRel); null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    override fun delete(f: LibFile): String? = runCatching { api.call("deletefile", mapOf("fileid" to f.id.toString())); files.remove(f.rel); null }.getOrElse { it.message }

    override fun moveOut(from: LibFile, outRel: String): String? = runCatching {
        var parent = remoteRoot.substringBeforeLast('/').ifEmpty { "" }
        var id = if (parent.isEmpty()) 0L else api.call("listfolder", mapOf("path" to parent, "nofiles" to "1")).getJSONObject("metadata").getLong("folderid")
        for (seg in outRel.substringBeforeLast('/').split('/').filter { it.isNotEmpty() }) {
            id = api.call("createfolderifnotexists", mapOf("folderid" to id.toString(), "name" to nfc(seg))).getJSONObject("metadata").getLong("folderid")
        }
        api.call("renamefile", mapOf("fileid" to from.id.toString(), "tofolderid" to id.toString(), "toname" to nfc(outRel.substringAfterLast('/'))))
        files.remove(from.rel); null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    override fun put(local: File, toRel: String): String? = runCatching {
        val dir = folder(toRel.substringBeforeLast('/', ""))
        val m = api.upload(dir, nfc(toRel.substringAfterLast('/')), local).getJSONArray("metadata").getJSONObject(0)
        files[nfc(toRel)] = LibFile(nfc(toRel), toRel, m.optLong("size"), m.getLong("fileid"))
        local.delete(); null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    override fun local(f: LibFile): File? = File(mountRoot, f.actual).takeIf { Scanner.get(it.path) != null }

    override fun exists(rel: String) = files.containsKey(nfc(rel)) || files.keys.any { it.equals(nfc(rel), ignoreCase = true) }

    override fun tidy() {
        val o = runCatching { api.call("listfolder", mapOf("path" to remoteRoot, "recursive" to "1")).getJSONObject("metadata") }.getOrNull() ?: return
        fun walk(m: JSONObject): Boolean { // true when it holds nothing worth keeping
            val list = m.optJSONArray("contents") ?: return true
            var empty = true
            for (k in 0 until list.length()) {
                val x = list.getJSONObject(k)
                val n = x.getString("name")
                if (x.optBoolean("isfolder")) {
                    if (n.startsWith(".")) { empty = false; continue }
                    if (walk(x)) runCatching { api.call("deletefolderrecursive", mapOf("folderid" to x.getLong("folderid").toString())) } else empty = false
                } else if (n != ".DS_Store") empty = false
            }
            return empty
        }
        walk(o)
    }

    companion object {
        fun open(root: String): PCloudLib? = PCloudApi.saved()?.let { PCloudLib(it, root) }
    }
}
