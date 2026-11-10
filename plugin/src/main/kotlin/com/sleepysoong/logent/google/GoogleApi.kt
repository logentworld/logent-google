package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class GoogleApiException(message: String) : IllegalStateException(message)

/**
 * 인증 헤더·401 재시도·오류 변환을 담당하는 Google HTTP 계층이에요.
 *
 * 액세스 토큰은 요청 헤더로만 전달하고 결과·오류·로그에 남기지 않아요.
 * 401이면 갱신 토큰으로 한 번만 갱신하고 같은 요청을 한 번만 재시도해요.
 */
internal class GoogleApi(private val auth: GoogleAuth) {
    suspend fun getJson(
        host: PluginHost,
        url: String,
        maxResponseBytes: Int = 1_048_576,
    ): Result<JsonElement> {
        val response = request(host, "GET", url, maxResponseBytes = maxResponseBytes)
            .getOrElse { return Result.failure(it) }
        return parseJson(response)
    }

    suspend fun postJson(
        host: PluginHost,
        url: String,
        body: JsonElement,
        maxResponseBytes: Int = 1_048_576,
    ): Result<JsonElement> {
        val response = request(
            host = host,
            method = "POST",
            url = url,
            body = body.toString(),
            contentType = "application/json; charset=UTF-8",
            maxResponseBytes = maxResponseBytes,
        ).getOrElse { return Result.failure(it) }
        return parseJson(response)
    }

    suspend fun delete(host: PluginHost, url: String): Result<Unit> {
        val response = request(host, "DELETE", url, maxResponseBytes = 64 * 1024)
            .getOrElse { return Result.failure(it) }
        if (response.status !in 200..299) {
            return Result.failure(GoogleApiException(googleApiError(response.status, response.body)))
        }
        return Result.success(Unit)
    }

    suspend fun request(
        host: PluginHost,
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        maxResponseBytes: Int = 1_048_576,
    ): Result<PluginHttpResponse> {
        val token = auth.accessToken(host).getOrElse { return Result.failure(it) }

        fun build(accessToken: String): PluginHttpRequest {
            val headers = buildMap {
                put("Accept", "application/json")
                if (contentType != null) put("Content-Type", contentType)
                put("Authorization", "Bearer $accessToken")
            }
            return PluginHttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
                maxResponseBytes = maxResponseBytes,
            )
        }

        val first = host.http(build(token)).getOrElse { error ->
            return Result.failure(
                IllegalStateException("Google API 요청을 보내지 못했어요: ${error.message.orEmpty()}"),
            )
        }
        if (first.status != 401) return Result.success(first)

        val renewed = auth.refreshAccessToken(host).getOrElse { error ->
            return Result.failure(
                IllegalStateException(
                    "Google 인증을 갱신하지 못했어요. google_auth start로 다시 연결해 주세요. " +
                        error.message.orEmpty(),
                ),
            )
        }
        val second = host.http(build(renewed)).getOrElse { error ->
            return Result.failure(
                IllegalStateException("Google API 재시도 요청을 보내지 못했어요: ${error.message.orEmpty()}"),
            )
        }
        return Result.success(second)
    }

    private fun parseJson(response: PluginHttpResponse): Result<JsonElement> {
        if (response.status !in 200..299) {
            return Result.failure(GoogleApiException(googleApiError(response.status, response.body)))
        }
        if (response.body.isBlank()) return Result.success(JsonNull)
        return runCatching { Json.parseToJsonElement(response.body) }
            .recoverCatching {
                throw GoogleApiException("Google 응답을 해석하지 못했어요 (HTTP ${response.status}).")
            }
    }
}

/** Google·OAuth 오류 본문에서 사람이 읽을 메시지를 만들어요. 토큰 값은 담지 않아요. */
internal fun googleApiError(status: Int, body: String): String {
    val detail = extractErrorDetail(body)
    val summary = when (status) {
        400 -> "Google 요청이 올바르지 않아요"
        401 -> "Google 인증이 거부됐어요. google_auth status로 연결 상태를 확인하고, 필요하면 google_auth start로 다시 연결해 주세요"
        403 -> "Google 계정에 이 작업을 할 권한이 없어요. 동의 화면에서 필요한 범위를 제공했는지 확인해 주세요"
        404 -> "대상 리소스를 찾지 못했어요"
        429 -> "Google API 호출 한도를 넘었어요. 잠시 후 다시 시도해 주세요"
        in 500..599 -> "Google 서버가 일시적으로 응답하지 않았어요. 잠시 후 다시 시도해 주세요"
        else -> "Google 요청이 실패했어요"
    }
    return if (detail.isBlank()) {
        "$summary (HTTP $status)"
    } else {
        "$summary (HTTP $status: $detail)"
    }
}

private fun extractErrorDetail(body: String): String {
    if (body.isBlank()) return ""
    val parsed = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
    val detail = when (val error = parsed["error"]) {
        is JsonObject -> error.str("message")
        is JsonPrimitive -> {
            val code = error.contentOrNull
            val description = parsed.str("error_description")
            when {
                code.isNullOrBlank() -> null
                description.isNullOrBlank() -> code
                else -> "$code: $description"
            }
        }
        else -> null
    }
    return detail.orEmpty().replace('\n', ' ').replace('\r', ' ').take(400)
}
