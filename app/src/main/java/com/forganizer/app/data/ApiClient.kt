package com.forganizer.app.data

import com.forganizer.core.AiRequestException
import com.forganizer.core.AiUnavailableException
import com.forganizer.core.PlanApi
import com.forganizer.core.PlanRequest
import com.forganizer.core.ProtocolJson
import com.forganizer.core.RawPatch
import com.forganizer.core.RawPlan
import com.forganizer.core.RefineApi
import com.forganizer.core.RefineRequest
import com.forganizer.core.ServerUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
) : PlanApi, RefineApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun base() = ServerUrl.normalize(baseUrl())

    private fun url(path: String) = base() + path

    private fun host(): String = base().toHttpUrlOrNull()?.host ?: base().ifEmpty { "(адрес не задан)" }

    /** Checks the configured address and the models behind it, in plain words. */
    suspend fun check(): String {
        val request = try {
            Request.Builder().url(url("/health")).get().build()
        } catch (e: IllegalArgumentException) {
            return "Некорректный адрес сервера: ${base().ifEmpty { "(пусто)" }}"
        }
        val health: Pair<Boolean, String> = try {
            execute(request).use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.code == 200 && body.contains("\"status\"") -> true to "Сервер отвечает: ${host()}"
                    r.header("x-render-routing") == "no-server" ->
                        false to "Сервис ${host()} не найден на Render (404). Проверьте адрес: он должен совпадать с адресом вашего сервиса."
                    else -> false to "${host()} ответил ${r.code}. Это не сервер Forganizer или адрес указан неверно."
                }
            }
        } catch (e: IOException) {
            false to "Не удалось подключиться к ${host()}: ${e.message ?: e.javaClass.simpleName}. Бесплатный сервер Render может просыпаться до минуты, повторите проверку."
        }
        return if (health.first) health.second + "\n\nМодели:\n" + diagnoseModels() else health.second
    }

    /** Asks the server which configured models answer right now (POST /diag). */
    private suspend fun diagnoseModels(): String = try {
        val req = Request.Builder().url(url("/diag")).header("X-App-Token", token)
            .post("".toRequestBody(jsonType)).build()
        execute(req).use { r ->
            val text = r.body?.string().orEmpty()
            when (r.code) {
                200 -> {
                    val models = ProtocolJson.parseToJsonElement(text).jsonObject["models"]?.jsonArray.orEmpty()
                    if (models.isEmpty()) {
                        "на сервере не заданы модели (переменная MODELS)"
                    } else {
                        models.joinToString("\n") { m ->
                            val o = m.jsonObject
                            val name = o["model"]?.jsonPrimitive?.contentOrNull ?: "?"
                            val ms = o["ms"]?.jsonPrimitive?.intOrNull ?: 0
                            val err = o["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (o["ok"]?.jsonPrimitive?.booleanOrNull == true) "✓ $name (${"%.1f".format(ms / 1000.0)} с)" else "✗ $name: $err"
                        }
                    }
                }
                401 -> "токен приложения не совпадает с APP_TOKEN на сервере"
                404 -> "диагностика недоступна, обновите сервер"
                else -> "диагностика: ответ ${r.code}"
            }
        }
    } catch (e: Exception) {
        "диагностика не выполнена: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Wakes up a sleeping free-tier server before the real request. */
    suspend fun warmUp() {
        runCatching { execute(Request.Builder().url(url("/health")).get().build()).close() }
    }

    override suspend fun plan(request: PlanRequest): RawPlan =
        post("/plan", ProtocolJson.encodeToString(PlanRequest.serializer(), request)) {
            ProtocolJson.decodeFromString(RawPlan.serializer(), it)
        }

    override suspend fun refine(request: RefineRequest): RawPatch =
        post("/refine", ProtocolJson.encodeToString(RefineRequest.serializer(), request)) {
            ProtocolJson.decodeFromString(RawPatch.serializer(), it)
        }

    private suspend fun <T> post(path: String, json: String, decode: (String) -> T): T {
        val http = try {
            Request.Builder()
                .url(url(path))
                .header("X-App-Token", token)
                .post(json.toRequestBody(jsonType))
                .build()
        } catch (e: IllegalArgumentException) {
            throw AiRequestException(0, "Некорректный адрес сервера (${base().ifEmpty { "пусто" }}). Укажите его в Настройках.")
        }
        val response = try {
            execute(http)
        } catch (e: IOException) {
            throw AiUnavailableException("Сервер недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when (r.code) {
                200 -> return try {
                    decode(text)
                } catch (e: Exception) {
                    throw AiUnavailableException("Сервер вернул некорректный ответ")
                }
                503, 502, 504 -> throw AiUnavailableException("Помощник временно недоступен")
                401 -> throw AiRequestException(401, "Сервер ${host()} отклонил токен приложения: APP_TOKEN в сборке не совпадает с APP_TOKEN на сервере")
                413 -> throw AiRequestException(
                    413,
                    "Сервер ${host()} отклонил запрос как слишком большой (${json.toByteArray().size / 1024 + 1} КБ). " +
                        "Проверьте MAX_BODY_BYTES на сервере: нужно 524288 (или удалите эту переменную).",
                )
                400 -> throw AiRequestException(400, "Сервер не принял формат запроса")
                404 -> throw AiRequestException(
                    404,
                    if (r.header("x-render-routing") == "no-server") {
                        "Сервис ${host()} не найден на Render. Проверьте адрес сервера в Настройках."
                    } else {
                        "Сервер ${host()} не знает путь $path. Проверьте адрес сервера в Настройках или обновите сервер."
                    },
                )
                else -> throw AiUnavailableException("Ошибка сервера ${host()} (${r.code})")
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
