package com.astralimit.dogfit

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class EdgeImpulseUploaderTest {

    @Test
    fun uploadCsvFile_returnsSuccess_on2xxResponse() {
        var receivedApiKey = ""
        var receivedLabel = ""
        var receivedBody = ""

        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/training/data") { exchange ->
            receivedApiKey = exchange.requestHeaders.getFirst("x-api-key") ?: ""
            receivedLabel = exchange.requestHeaders.getFirst("x-label") ?: ""
            receivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = "ok"
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()

        try {
            val csv = File.createTempFile("caminar_test_", ".csv").apply {
                writeText(
                    """
                    timestamp,ax,ay,az,gx,gy,gz
                    1000,1,2,3,4,5,6
                    1100,2,3,4,5,6,7
                    """.trimIndent()
                )
                deleteOnExit()
            }

            val uploader = EdgeImpulseUploader("http://localhost:${server.address.port}/api/training/data")
            val (ok, error) = uploader.uploadCsvFile(csv, apiKey = "test-key", deviceName = "dog-1")

            assertTrue("Expected upload to be successful, got error: $error", ok)
            assertEquals("", error)
            assertEquals("test-key", receivedApiKey)
            assertTrue(receivedLabel.isNotBlank())
            assertTrue(receivedBody.contains("\"payload\""))
            assertTrue(receivedBody.contains("\"device_name\":\"dog-1\""))
        } finally {
            server.stop(0)
        }
    }
}
