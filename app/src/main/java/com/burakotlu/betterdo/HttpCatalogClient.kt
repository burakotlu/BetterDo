package com.burakotlu.betterdo

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpCatalogClient(private val allowLocalHttp: Boolean = false) : CatalogClient {
    override fun fetch(source: String): String {
        var url = URL(source)
        repeat(4) {
            require(url.userInfo == null && url.ref == null) { "Invalid catalog URL" }
            require(url.protocol == "https" || (allowLocalHttp && url.protocol == "http" && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "HTTPS is required" }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: throw IOException("Missing redirect URL"))
                } else {
                    if (code != HttpURLConnection.HTTP_OK) throw IOException("Catalog HTTP $code")
                    val maximum = 2_000_000
                    if (connection.contentLengthLong > maximum) throw IOException("Catalog too large")
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maximum) throw IOException("Catalog too large")
                            output.write(buffer, 0, count)
                        }
                    }
                    return output.toString("UTF-8")
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many redirects")
    }
}
