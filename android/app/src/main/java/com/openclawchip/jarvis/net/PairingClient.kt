package com.openclawchip.jarvis.net

import com.openclawchip.jarvis.settings.ManagedPreferences
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PairingRequest(val id: String, val expiresSeconds: Int)
sealed interface PairingResult {
    data object Pending : PairingResult
    data object Expired : PairingResult
    class Paired(val token: String, val revision: Long, val settings: ManagedPreferences) : PairingResult {
        override fun toString() = "Pairing credentials (protected)"
    }
}
class PairingFailure : Exception("Pairing unavailable. Try again shortly.")
interface PairingApi {
    suspend fun request(proof: String): PairingRequest
    suspend fun claim(id: String, proof: String): PairingResult
}

/** Public HTTPS only; private claim material is sent exclusively in capped POST bodies. */
class PairingClient internal constructor(private val base: HttpUrl, private val http: OkHttpClient) : PairingApi {
    constructor(serverUrl: String) : this(secureUrl(serverUrl), OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build())
    override suspend fun request(proof: String): PairingRequest {
        val data = post("request", JSONObject().put("claim", proof))
        return try {
            val id = data.getString("id")
            val expires = data.get("expires_in")
            require(id.matches(Regex("[a-f0-9]{8}")) && (expires is Int || expires is Long))
            val seconds = (expires as Number).toInt()
            require(seconds in 1..180)
            PairingRequest(id, seconds)
        } catch (_: Exception) { throw PairingFailure() }
    }
    override suspend fun claim(id: String, proof: String): PairingResult {
        val data = post("claim", JSONObject().put("id", id).put("claim", proof))
        return try {
            when (data.getString("status")) {
                "pending" -> PairingResult.Pending
                "expired" -> PairingResult.Expired
                "paired" -> {
                    val token = data.getString("token")
                    val revision = data.get("revision")
                    require(token.isNotBlank() && token.length <= 512 && (revision is Int || revision is Long) && (revision as Number).toLong() > 0)
                    val preferences = ManagedPreferences.fromJson(data.getJSONObject("settings"))
                    require(data.getString("server_url") == preferences.serverUrl)
                    PairingResult.Paired(token, (revision as Number).toLong(), preferences)
                }
                else -> throw PairingFailure()
            }
        } catch (_: Exception) { throw PairingFailure() }
    }
    private suspend fun post(action: String, data: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val url = base.newBuilder().encodedPath("/pairing/$action").query(null).fragment(null).build()
        val call = http.newCall(Request.Builder().url(url).post(data.toString().toRequestBody("application/json".toMediaType())).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(PairingFailure())
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (!continuation.isActive || !it.isSuccessful) throw PairingFailure()
                    val source = it.body.source()
                    source.request(8193)
                    require(source.buffer.size <= 8192)
                    JSONObject(source.readUtf8())
                } }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(PairingFailure()) })
            }
        })
    }
    companion object {
        private fun secureUrl(serverUrl: String): HttpUrl {
            ManagedPreferences(serverUrl = serverUrl) // Validate before translating to an HTTPS request.
            return ("https://" + serverUrl.substring(6)).toHttpUrl()
        }
    }
}
