package tornadofx

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS
import javafx.scene.control.Tooltip
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.*
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.InputStreamEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.*
import org.apache.http.util.EntityUtils
import tornadofx.Rest.Request.Method.*
import java.io.Closeable
import java.io.InputStream
import java.io.StringReader
import java.net.*
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.DeflaterInputStream
import java.util.zip.GZIPInputStream
import javax.json.Json
import javax.json.JsonArray
import javax.json.JsonObject
import javax.json.JsonValue

open class Rest : Controller() {
    companion object {
        var engineProvider: (Rest) -> Engine = ::HttpURLEngine
        val ongoingRequests = FXCollections.observableArrayList<Request>()
        val atomicseq = AtomicLong()

        fun useApacheHttpClient() {
            engineProvider = ::HttpClientEngine
        }
    }

    var engine = engineProvider(this)
    var baseURI: String? = null
    var proxy: Proxy? = null

    fun setBasicAuth(username: String, password: String) = engine.setBasicAuth(username, password)
    fun setDigestAuth(username: String, password: String) = engine.setDigestAuth(username, password)
    fun reset() = engine.reset()

    fun get(path: String, data: JsonValue? = null, processor: ((Request) -> Unit)? = null) = execute(GET, path, data, processor)
    fun get(path: String, data: JsonModel, processor: ((Request) -> Unit)? = null) = get(path, JsonBuilder().apply { data.toJSON(this) }.build(), processor)

    fun put(path: String, data: JsonValue? = null, processor: ((Request) -> Unit)? = null) = execute(PUT, path, data, processor)
    fun put(path: String, data: JsonModel, processor: ((Request) -> Unit)? = null) = put(path, JsonBuilder().apply { data.toJSON(this) }.build(), processor)
    fun put(path: String, data: InputStream, processor: ((Request) -> Unit)? = null) = execute(PUT, path, data, processor)

    fun patch(path: String, data: JsonValue? = null, processor: ((Request) -> Unit)? = null) = execute(PATCH, path, data, processor)
    fun patch(path: String, data: JsonModel, processor: ((Request) -> Unit)? = null) = patch(path, JsonBuilder().apply { data.toJSON(this) }.build(), processor)
    fun patch(path: String, data: InputStream, processor: ((Request) -> Unit)? = null) = execute(PATCH, path, data, processor)

    fun post(path: String, data: JsonValue? = null, processor: ((Request) -> Unit)? = null) = execute(POST, path, data, processor)
    fun post(path: String, data: JsonModel, processor: ((Request) -> Unit)? = null) = post(path, JsonBuilder().apply { data.toJSON(this) }.build(), processor)
    fun post(path: String, data: InputStream, processor: ((Request) -> Unit)? = null) = execute(POST, path, data, processor)

    fun delete(path: String, data: JsonValue? = null, processor: ((Request) -> Unit)? = null) = execute(DELETE, path, data, processor)
    fun delete(path: String, data: JsonModel, processor: ((Request) -> Unit)? = null) = delete(path, JsonBuilder().apply { data.toJSON(this) }.build(), processor)

    fun getURI(path: String): URI {
        try {
            val asURI = URI.create(path.replace(" ", "%20"))
            if (asURI.isAbsolute) return asURI

            val uri = StringBuilder()

            if (baseURI != null)
                uri.append(baseURI)

            if (uri.toString().endsWith("/") && path.startsWith("/"))
                uri.append(path.substring(1))
            else if (!uri.toString().endsWith("/") && !path.startsWith("/"))
                uri.append("/").append(path)
            else
                uri.append(path)

            return URI(uri.toString().replace(" ", "%20"))
        } catch (ex: URISyntaxException) {
            throw RuntimeException(ex)
        }

    }

    fun execute(method: Request.Method, target: String, data: Any? = null, processor: ((Request) -> Unit)? = null): Response {
        val request = engine.request(atomicseq.addAndGet(1), method, getURI(target), data)

        if (processor != null)
            processor(request)

        Platform.runLater { ongoingRequests.add(request) }
        return request.execute()
    }

    abstract class Engine {
        var requestInterceptor: ((Request) -> Unit)? = null
        @Deprecated("Renamed to requestInterceptor", ReplaceWith("requestInterceptor"))
        var authInterceptor: ((Request) -> Unit)? get() = requestInterceptor; set(value) {
            requestInterceptor = value
        }
        var responseInterceptor: ((Response) -> Response)? = null
        abstract fun request(seq: Long, method: Request.Method, uri: URI, entity: Any? = null): Request
        abstract fun setBasicAuth(username: String, password: String)
        abstract fun setDigestAuth(username: String, password: String)
        abstract fun reset()
    }

    interface Request {
        enum class Method { GET, PUT, POST, DELETE, PATCH }

        val seq: Long
        val method: Method
        val uri: URI
        val entity: Any?
        fun addHeader(name: String, value: String)
        fun getHeader(name: String): String?
        fun execute(): Response
        fun reset()
    }

    interface Response : Closeable {
        val request: Request
        val statusCode: Int
        val reason: String
        fun text(): String?
        fun consume(): Response
        val headers: Map<String, List<String>>
        fun header(name: String): String? = headers.get(name)?.first()
        fun list(): JsonArray {
            try {
                val content = text()

                if (content == null || content.isEmpty())
                    return Json.createArrayBuilder().build()

                val json = Json.createReader(StringReader(content)).use { it.read() }

                return when (json) {
                    is JsonArray -> json
                    is JsonObject -> Json.createArrayBuilder().add(json).build()
                    else -> throw IllegalArgumentException("Unknown json result value")
                }
            } finally {
                consume()
            }
        }

        fun one(): JsonObject {
            try {
                val content = text()

                if (content == null || content.isEmpty())
                    return Json.createObjectBuilder().build()

                val json = Json.createReader(StringReader(content)).use { it.read() }

                return when (json) {
                    is JsonArray -> {
                        if (json.isEmpty())
                            return Json.createObjectBuilder().build()
                        else
                            return json.getJsonObject(0)
                    }
                    is JsonObject -> json
                    else -> throw IllegalArgumentException("Unknown json result value")
                }
            } finally {
                consume()
            }
        }

        fun content(): InputStream
        fun bytes(): ByteArray
        fun ok() = statusCode == 200
    }
}

class HttpURLEngine(val rest: Rest) : Rest.Engine() {
    override fun setBasicAuth(username: String, password: String) {
        requestInterceptor = { request ->
            val b64 = Base64.getEncoder().encodeToString("$username:$password".toByteArray(UTF_8))
            request.addHeader("Authorization", "Basic $b64")
        }
    }

    override fun reset() {
        requestInterceptor = null
    }

    override fun request(seq: Long, method: Rest.Request.Method, uri: URI, entity: Any?) =
            HttpURLRequest(this, seq, method, uri, entity)

    override fun setDigestAuth(username: String, password: String) {
        responseInterceptor = { response ->
            if (response.statusCode == 401) handleDigestAuthentication(this, response, username, password) else response
        }
    }

}

internal fun handleDigestAuthentication(engine: Rest.Engine, response: Rest.Response, username: String, password: String): Rest.Response {
    val p = response.digestParams
    val request = response.request
    if (request.getHeader("Authorization") != null) return response
    if (p != null) {
        val algorithm = p["algorithm"] ?: "MD5"
        val digest = MessageDigest.getInstance(algorithm)
        val path = request.uri.path
        val realm = p["realm"]!!
        val nonce = p["nonce"]!!
        val opaque = p["opaque"] ?: "00000000000000000000000000000000"
        val ha1 = digest.concat(username, realm, password)
        val ha2 = digest.concat(request.method.name, path)
        val encoded = digest.concat(ha1, nonce, ha2)
        val header = """Digest username="$username", realm="$realm", nonce="$nonce", uri="$path", response="$encoded", algorithm=$algorithm, opaque="$opaque""""
        request.reset()
        request.addHeader("Authorization", header)
        return request.execute()
    }
    return response
}

internal fun MessageDigest.concat(vararg values: String): String {
    reset()
    update(values.joinToString(":").toByteArray(StandardCharsets.ISO_8859_1))
    return digest().hex
}

val Rest.Response.digestParams: Map<String, String>? get() {
    fun String.clean() = trim('\t', ' ', '"')
    return headers["WWW-Authenticate"]
            ?.filter { it.startsWith("Digest ") }
            ?.first()
            ?.substringAfter("Digest ")
            ?.split(",")
            ?.map {
                val (name, value) = it.split("=", limit = 2)
                name.clean() to value.clean()
            }
            ?.toMap()
}

private val HexChars = "0123456789abcdef".toCharArray()

val ByteArray.hex get() = map(Byte::toInt).map { "${HexChars[(it and 0xF0).ushr(4)]}${HexChars[it and 0x0F]}" }.joinToString("")

class HttpURLRequest(val engine: HttpURLEngine, override val seq: Long, override val method: Rest.Request.Method, override val uri: URI, override val entity: Any?) : Rest.Request {
    lateinit var connection: HttpURLConnection
    val headers = mutableMapOf<String, String>()

    init {
        reset()
    }

    override fun reset() {
        val url = uri.toURL()
        connection = (if (engine.rest.proxy != null) url.openConnection(engine.rest.proxy) else url.openConnection()) as HttpURLConnection
        headers += "Accept-Encoding" to "gzip, deflate"
        headers += "Content-Type" to "application/json"
        headers += "Accept" to "application/json"
        headers += "User-Agent" to "TornadoFX/Java ${System.getProperty("java.version")}"
        headers += "Connection" to "Keep-Alive"
    }

    override fun execute(): Rest.Response {
        engine.requestInterceptor?.invoke(this)

        for ((key, value) in headers)
            connection.addRequestProperty(key, value)

        connection.requestMethod = method.toString()

        if (entity != null) {
            if (headers["Content-Type"] == null)
                connection.addRequestProperty("Content-Type", "application/json")

            connection.doOutput = true

            val data = when (entity) {
                is JsonModel -> entity.toJSON().toString().toByteArray(UTF_8)
                is JsonValue -> entity.toString().toByteArray(UTF_8)
                is InputStream -> entity.readBytes()
                else -> throw IllegalArgumentException("Don't know how to handle entity of type ${entity.javaClass}")
            }
            connection.addRequestProperty("Content-Length", data.size.toString())
            connection.connect()
            connection.outputStream.write(data)
            connection.outputStream.flush()
            connection.outputStream.close()
        } else {
            connection.connect()
        }

        val response = HttpURLResponse(this)
        if (connection.doOutput) response.bytes()

        engine.responseInterceptor?.invoke(response)

        return response
    }

    override fun addHeader(name: String, value: String) {
        headers[name] = value
    }

    override fun getHeader(name: String) = headers[name]
}

class HttpURLResponse(override val request: HttpURLRequest) : Rest.Response {
    override val statusCode: Int get() = request.connection.responseCode
    private var bytesRead: ByteArray? = null

    override fun close() {
        consume()
    }

    override fun consume(): Rest.Response {
        try {
            if (bytesRead == null) {
                bytes()
                return this
            }

            with(request.connection) {
                if (doInput) content().close()
            }
        } catch (ignored: Throwable) {
            ignored.printStackTrace()
        }
        Platform.runLater { Rest.ongoingRequests.remove(request) }
        return this
    }

    override val reason: String get() = request.connection.responseMessage

    override fun text() = bytes().toString(UTF_8)

    override fun content() = request.connection.errorStream ?: request.connection.inputStream

    override fun bytes(): ByteArray {
        if (bytesRead != null) return bytesRead!!

        try {
            val unwrapped = when (request.connection.contentEncoding) {
                "gzip" -> GZIPInputStream(content())
                "deflate" -> DeflaterInputStream(content())
                else -> content()
            }
            bytesRead = unwrapped.readBytes()
        } catch (error: Exception) {
            bytesRead = ByteArray(0)
            throw error
        } finally {
            consume()
        }
        return bytesRead!!
    }

    override val headers get() = request.connection.headerFields
}

class HttpClientEngine(val rest: Rest) : Rest.Engine() {
    lateinit var client: CloseableHttpClient
    lateinit var context: HttpClientContext

    init {
        reset()
    }

    override fun request(seq: Long, method: Rest.Request.Method, uri: URI, entity: Any?) =
            HttpClientRequest(this, client, seq, method, uri, entity)

    override fun setBasicAuth(username: String, password: String) {
        if (rest.baseURI == null) throw IllegalArgumentException("You must configure the baseURI first.")

        val uri = URI.create(rest.baseURI)

        val scheme = if (uri.scheme == null) "http" else uri.scheme
        val port = if (uri.port > -1) uri.port else if (scheme == "http") 80 else 443
        val host = HttpHost(uri.host, port, scheme)

        val credsProvider = BasicCredentialsProvider().apply {
            setCredentials(AuthScope(host), UsernamePasswordCredentials(username, password))
        }

        context.authCache = BasicAuthCache()
        context.authCache.put(host, BasicScheme())

        client = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build()
    }

    override fun setDigestAuth(username: String, password: String) {
        responseInterceptor = { response ->
            if (response.statusCode == 401) handleDigestAuthentication(this, response, username, password) else response
        }
    }

    override fun reset() {
        client = HttpClientBuilder.create().build()
        context = HttpClientContext.create()
    }
}

class HttpClientRequest(val engine: HttpClientEngine, val client: CloseableHttpClient, override val seq: Long, override val method: Rest.Request.Method, override val uri: URI, override val entity: Any?) : Rest.Request {
    lateinit var request: HttpRequestBase

    init {
        reset()
    }

    override fun reset() {
        when (method) {
            GET -> request = HttpGet(uri)
            PUT -> request = HttpPut(uri)
            POST -> request = HttpPost(uri)
            DELETE -> request = HttpDelete(uri)
            PATCH -> request = HttpPatch(uri)
        }
        addHeader("Accept-Encoding", "gzip, deflate")
        addHeader("Content-Type", "application/json")
        addHeader("Accept", "application/json")
    }

    override fun execute(): Rest.Response {
        if (engine.rest.proxy != null) {
            val hp = engine.rest.proxy as Proxy
            val sa = hp.address() as? InetSocketAddress
            if (sa != null) {
                val scheme = if (engine.rest.baseURI?.startsWith("https") ?: false) "https" else "http"
                val proxy = HttpHost(sa.address, sa.port, scheme)
                request.config = RequestConfig.custom().setProxy(proxy).build()
            }
        }
        engine.requestInterceptor?.invoke(this)

        if (entity != null && request is HttpEntityEnclosingRequestBase) {

            val r = request as HttpEntityEnclosingRequestBase

            when (entity) {
                is JsonModel -> r.entity = StringEntity(entity.toJSON().toString(), UTF_8)
                is JsonValue -> r.entity = StringEntity(entity.toString(), UTF_8)
                is InputStream -> r.entity = InputStreamEntity(entity)
                else -> throw IllegalArgumentException("Don't know how to handle entity of type ${entity.javaClass}")
            }
        }

        val httpResponse = client.execute(request, engine.context)

        val response = HttpClientResponse(this, httpResponse)
        engine.responseInterceptor?.invoke(response)
        return response
    }

    override fun addHeader(name: String, value: String) = request.addHeader(name, value)
    override fun getHeader(name: String) = request.getFirstHeader("name")?.value
}

class HttpClientResponse(override val request: HttpClientRequest, val response: CloseableHttpResponse) : Rest.Response {
    override val statusCode: Int get() = response.statusLine.statusCode
    override val reason: String get() = response.statusLine.reasonPhrase

    override fun close() {
        consume()
    }

    override fun text(): String {
        try {
            return EntityUtils.toString(response.entity, UTF_8)
        } finally {
            consume()
        }
    }


    override fun consume(): Rest.Response {
        EntityUtils.consumeQuietly(response.entity)
        try {
            if (response is CloseableHttpResponse) response.close()
            return this
        } finally {
            Platform.runLater { Rest.ongoingRequests.remove(request) }
        }
    }

    override fun content() = response.entity.content

    override fun bytes(): ByteArray {
        try {
            return EntityUtils.toByteArray(response.entity)
        } finally {
            consume()
        }
    }

    override val headers get() = response.allHeaders.map { it.name to listOf(it.value) }.toMap()
}

inline fun <reified T : JsonModel> JsonObject.toModel(): T {
    val model = T::class.java.newInstance()
    model.updateModel(this)
    return model
}

inline fun <reified T : JsonModel> JsonArray.toModel(): ObservableList<T> {
    return FXCollections.observableArrayList(map { (it as JsonObject).toModel<T>() })
}

class RestProgressBar : Fragment() {
    override val root = progressbar {
        prefWidth = 75.0
        isVisible = false
    }

    init {
        Rest.ongoingRequests.addListener(ListChangeListener<Rest.Request> { c ->
            val size = c.list.size

            Platform.runLater {
                val tooltip = c.list.map { r -> "%s %s".format(r.method, r.uri) }.joinToString("\n")

                root.tooltip = Tooltip(tooltip)
                root.isVisible = size > 0

                if (size == 0) {
                    root.progress = 100.0
                } else if (size == 1) {
                    root.progress = INDETERMINATE_PROGRESS
                } else {
                    val pct = 1.0 / size.toDouble()
                    root.progress = pct
                }
            }
        })
    }
}

val String.urlEncoded: String get() = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

val Map<*, *>.queryString: String get() {
    val q = StringBuilder()
    forEach { k, v ->
        if (k != null) {
            q.append(if (q.isEmpty()) "?" else "&")
            q.append(k.toString().urlEncoded)
            if (v != null) q.append("=${v.toString().urlEncoded}")
        }
    }
    return q.toString()
}