package com.forganizer.app.data

import com.forganizer.core.AiRequestException
import com.forganizer.core.AiUnavailableException
import com.forganizer.core.PlanApi
import com.forganizer.core.PlanRequest
import com.forganizer.core.ProtocolJson
import com.forganizer.core.RawPlan
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Client for the plan server. Only metadata (names, sizes, dates) is ever sent. */
class HttpPlanApi(
    private val baseUrl: () -> String,
    private val token: String,
) : PlanApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = baseUrl().trimEnd('/') + path

    /** Wakes up a sleeping free-tier server before the real request. */
    suspend fun warmUp() {
        runCatching { execute(Request.Builder().url(url("/health")).get().build()).close() }
    }

    override suspend fun plan(request: PlanRequest): RawPlan {
        val body = ProtocolJson.encodeToString(PlanRequest.serializer(), request).toRequestBody(jsonType)
        val http = Request.Builder()
            .url(url("/plan"))
            .header("X-App-Token", token)
            .post(body)
            .build()
        val response = try {
            execute(http)
        } catch (e: IOException) {
            throw AiUnavailableException("Сервер недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when (r.code) {
                200 -> return try {
                    ProtocolJson.decodeFromString(RawPlan.serializer(), text)
                } catch (e: Exception) {
                    throw AiUnavailableException("Сервер вернул некорректный ответ")
                }
                503, 502, 504 -> throw AiUnavailableException("ИИ временно недоступен")
                401 -> throw AiRequestException(401, "Сервер отклонил токен приложения")
                413 -> throw AiRequestException(413, "Слишком большой запрос")
                400 -> throw AiRequestException(400, "Сервер не принял формат запроса")
                else -> throw AiUnavailableException("Ошибка сервера (${r.code})")
            }
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}
