package org.http4k.client

import org.apache.http.Header
import org.apache.http.StatusLine
import org.apache.http.client.config.CookieSpecs.IGNORE_COOKIES
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.entity.InputStreamEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.http4k.core.BodyMode
import org.http4k.core.BodyMode.Memory
import org.http4k.core.BodyMode.Stream
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.CLIENT_TIMEOUT
import java.net.SocketTimeoutException
import java.net.URI

class ApacheClient(
    private val client: CloseableHttpClient = defaultApacheHttpClient(),
    private val responseBodyMode: BodyMode = Memory,
    private val requestBodyMode: BodyMode = Memory
) : HttpHandler {

    override fun invoke(request: Request): Response = try {
        client.execute(request.toApacheRequest()).toHttp4kResponse()
    } catch (e: ConnectTimeoutException) {
        Response(CLIENT_TIMEOUT.describeClientError(e))
    } catch (e: SocketTimeoutException) {
        Response(CLIENT_TIMEOUT.describeClientError(e))
    }

    private fun CloseableHttpResponse.toHttp4kResponse(): Response =
        with(Response(statusLine.toTarget()).headers(allHeaders.toTarget())) {
            entity?.let { body(responseBodyMode(it.content)) } ?: this
        }

    private fun Request.toApacheRequest(): HttpRequestBase = object : HttpEntityEnclosingRequestBase() {
        init {
            val request = this@toApacheRequest
            uri = URI(request.uri.toString())
            entity = when (requestBodyMode) {
                Stream -> InputStreamEntity(request.body.stream, request.header("content-length")?.toLong() ?: -1)
                Memory -> ByteArrayEntity(request.body.payload.array())
            }
            request.headers.filter { !it.first.equals("content-length", true) }.map { addHeader(it.first, it.second) }
        }

        override fun getMethod(): String = this@toApacheRequest.method.name
    }

    private fun StatusLine.toTarget() = Status(statusCode, reasonPhrase)

    private fun Array<Header>.toTarget(): Headers = listOf(*this.map { it.name to it.value }.toTypedArray())

    companion object {
        private fun defaultApacheHttpClient() = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setCookieSpec(IGNORE_COOKIES)
                .build()).build()

    }
}
