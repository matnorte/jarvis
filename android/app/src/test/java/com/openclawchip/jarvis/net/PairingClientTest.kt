package com.openclawchip.jarvis.net

import com.openclawchip.jarvis.settings.ManagedPreferences
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingClientTest {
    private val prefs = """{"server_url":"wss://jarvis.openclawchip.tech/ws","wake_sensitivity":0.6,"end_silence_ms":700,"interrupt_by_speech":false,"start_on_boot":false,"vision_enabled":true,"front_camera":false}"""
    @Test fun pairingUsesPostBodiesAndReturnsCredentialsOnlyAfterApproval() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("""{"id":"0123abcd","expires_in":180}""").build())
            server.enqueue(MockResponse.Builder().body("""{"status":"pending"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"status":"paired","token":"dummy-device-token","revision":2,"server_url":"wss://jarvis.openclawchip.tech/ws","settings":$prefs}""").build())
            server.start()
            val client = PairingClient(server.url("/ws"), OkHttpClient())
            val proof = "x".repeat(43)
            val request = client.request(proof)
            assertEquals("0123abcd", request.id)
            assertEquals(PairingResult.Pending, client.claim(request.id, proof))
            val paired = client.claim(request.id, proof) as PairingResult.Paired
            assertEquals("dummy-device-token", paired.token)
            assertEquals(ManagedPreferences(), paired.settings)
            assertFalse(paired.toString().contains("dummy-device-token"))
            val sent = server.takeRequest()
            assertEquals("POST", sent.method); assertEquals("/pairing/request", sent.url.encodedPath)
            assertNull(sent.url.query); assertNull(sent.headers["Authorization"])
            assertEquals(proof, JSONObject(sent.body!!.utf8()).getString("claim"))
        }
    }

    @Test fun invalidAndOversizedRepliesNeverExposeTheirContents() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("private-response-content").build())
            server.enqueue(MockResponse.Builder().body("x".repeat(9000)).build())
            server.enqueue(MockResponse.Builder().code(403).body("private-response-content").build())
            server.start()
            val client = PairingClient(server.url("/ws"), OkHttpClient())
            repeat(3) {
                try { client.request("x".repeat(43)); fail("expected safe failure") }
                catch (error: PairingFailure) {
                    generateSequence<Throwable>(error) { it.cause }.forEach { assertFalse(it.toString().contains("private-response")) }
                }
            }
        }
    }
}
