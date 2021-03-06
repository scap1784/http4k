package org.http4k.server

import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import org.http4k.asByteBuffer
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.ACCEPTED
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.OK
import org.http4k.core.StreamBody
import org.http4k.core.with
import org.http4k.hamkrest.hasBody
import org.http4k.hamkrest.hasStatus
import org.http4k.lens.binary
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.util.RetryRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Random


abstract class ServerContract(private val serverConfig: (Int) -> ServerConfig, private val client: HttpHandler,
                              private val requiredMethods: Array<Method> = Method.values()) {
    private var server: Http4kServer? = null

    @Rule
    @JvmField
    var retryRule = RetryRule(1)

    private val port = Random().nextInt(1000) + 8000

    private val size = 1000 * 1024
    private val random = (0 until size).map { '.' }.joinToString("")

    @Before
    fun before() {

        val routes =
            requiredMethods.map {
                "/" + it.name bind it to { _: Request -> Response(OK).body(it.name) }
            }.plus(listOf(
                    "/headers" bind GET to { _: Request ->
                        Response(ACCEPTED)
                            .header("content-type", "text/plain")
                    },
                    "/large" bind GET to { Response(OK).body((0..size).map { '.' }.joinToString("")) },
                    "/large" bind POST to { Response(OK).body((0..size).map { '.' }.joinToString("")) },
                    "/stream" bind GET to { Response(OK).with(Body.binary(ContentType.TEXT_PLAIN).toLens() of Body("hello".asByteBuffer())) },
                    "/presetlength" bind GET to { Response(OK).header("Content-Length", "0") },
                    "/echo" bind POST to { req: Request -> Response(OK).body(req.bodyString()) },
                    "/request-headers" bind GET to { request: Request -> Response(OK).body(request.headerValues("foo").joinToString(", ")) },
                    "/length" bind { req: Request ->
                        when (req.body) {
                            is StreamBody -> Response(OK).body(req.body.length.toString())
                            else -> Response(INTERNAL_SERVER_ERROR)
                        }
                    },
                    "/uri" bind GET to { req: Request -> Response(OK).body(req.uri.toString()) },
                    "/boom" bind GET to { _: Request -> throw IllegalArgumentException("BOOM!") }
                ))

        server = routes(*routes.toTypedArray()).asServer(serverConfig(port)).start()
    }

    @Test
    fun `can call an endpoint with all supported Methods`() {
        for (method in requiredMethods) {

            val response = client(Request(method, "http://localhost:$port/" + method.name))

            assertThat(response.status, equalTo(OK))
            if (method == Method.HEAD) assertThat(response.body, equalTo(Body.EMPTY))
            else assertThat(response.bodyString(), equalTo(method.name))
        }
    }

    @Test
    open fun `can return a large body - GET`() {
        val response = client(Request(GET, "http://localhost:$port/large").body("hello mum"))

        assertThat(response.status, equalTo(OK))
        assertThat(response.bodyString().length, equalTo(random.length + 1))
    }

    @Test
    open fun `can return a large body - POST`() {
        val response = client(Request(POST, "http://localhost:$port/large").body("hello mum"))

        assertThat(response.status, equalTo(OK))
        assertThat(response.bodyString().length, equalTo(random.length + 1))
    }

    @Test
    fun `gets the body from the request`() {
        val response = client(Request(POST, "http://localhost:$port/echo").body("hello mum"))

        assertThat(response.status, equalTo(OK))
        assertThat(response.bodyString(), equalTo("hello mum"))
    }

    @Test
    fun `returns headers`() {
        val response = client(Request(GET, "http://localhost:$port/headers"))

        assertThat(response.status, equalTo(ACCEPTED))
        assertThat(response.header("content-type"), equalTo("text/plain"))
    }

    @Test
    fun `length is set on body if it is sent`() {
        val response = client(Request(POST, "http://localhost:$port/length")
            .body("12345").header("Content-Length", "5"))
        response shouldMatch hasStatus(OK).and(hasBody("5"))
    }

    @Test
    fun `length is ignored on body if it not well formed`() {
        val response = client(Request(POST, "http://localhost:$port/length").header("Content-Length", "nonsense").body("12345"))
        response shouldMatch hasStatus(OK).and(hasBody("5"))
    }

    @Test
    fun `length is zero on GET body`() {
        val response = client(Request(GET, "http://localhost:$port/length"))
        response shouldMatch hasStatus(OK).and(hasBody("0"))
    }

    @Test
    fun `gets the uri from the request`() {
        val response = client(Request(GET, "http://localhost:$port/uri?bob=bill"))

        assertThat(response.status, equalTo(OK))
        assertThat(response.bodyString(), equalTo("/uri?bob=bill"))
    }

    @Test
    fun `endpoint that blows up results in 500`() {
        val response = client(Request(GET, "http://localhost:$port/boom"))

        assertThat(response.status, equalTo(INTERNAL_SERVER_ERROR))
    }

    @Test
    fun `can handle multiple request headers`() {
        val response = client(Request(GET, "http://localhost:$port/request-headers").header("foo", "one").header("foo", "two").header("foo", "three"))

        assertThat(response.status, equalTo(OK))
        assertThat(response.bodyString(), equalTo("one, two, three"))
    }

    @Test
    fun `deals with streaming response`() {
        val response = client(Request(GET, "http://localhost:$port/stream"))

        assertThat(response.status, equalTo(OK))
        assertThat(response.bodyString(), equalTo("hello"))
    }

    @Test
    fun `ok when length already set`() {
        val response = client(Request(GET, "http://localhost:$port/presetlength"))

        assertThat(response.status, equalTo(OK))
    }

    @After
    fun after() {
        server?.stop()
    }

}