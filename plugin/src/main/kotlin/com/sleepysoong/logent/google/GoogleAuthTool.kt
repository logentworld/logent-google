package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.ToolSpec
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.toolSpec
import kotlinx.serialization.json.JsonObject

/** Google 계정 연결 상태를 다루는 `google_auth` 도구예요. */
internal class GoogleAuthTool(private val auth: GoogleAuth) {
    val spec: ToolSpec = toolSpec(
        name = "google_auth",
        description = "Google OAuth 2.0 Device Authorization Grant로 계정을 연결한다. " +
            "start는 사용자 코드와 승인 URL을 돌려주고, 사용자가 브라우저에서 승인한 뒤 poll을 실행한다. " +
            "status는 연결 여부와 부여된 범위만 보여 주고, logout은 저장된 갱신 토큰을 삭제한다. " +
            "액세스 토큰·갱신 토큰·device_code 값은 절대 출력하지 않는다.",
        required = listOf("action"),
    ) {
        stringProperty("action", "작업이에요", listOf("start", "poll", "status", "logout"))
    }

    suspend fun execute(host: PluginHost, arguments: JsonObject): ToolResult {
        return when (arguments.text("action")?.trim()?.lowercase()) {
            "start" -> start(host)
            "poll" -> poll(host)
            "status" -> status(host)
            "logout" -> logout(host)
            null -> ToolResult("action이 필요해요. start, poll, status, logout 중 하나를 지정해 주세요.", isError = true)
            else -> ToolResult("지원하지 않는 action이에요. start, poll, status, logout 중 하나를 써 주세요.", isError = true)
        }
    }

    private suspend fun start(host: PluginHost): ToolResult =
        auth.beginDeviceAuthorization(host).fold(
            onSuccess = { authorization ->
                val minutes = (authorization.expiresInSeconds / 60).coerceAtLeast(1)
                ToolResult(
                    buildString {
                        append("Google 기기 승인을 시작했어요.\n")
                        append("1. 브라우저나 휴대폰에서 ").append(authorization.verificationUrl).append(" 을 열어요.\n")
                        append("2. 사용자 코드 ").append(authorization.userCode).append(" 를 입력해요.\n")
                        append("3. Google 계정으로 로그인하고 요청된 범위를 승인해요.\n")
                        append("승인한 뒤 google_auth poll을 실행해 주세요. 코드는 약 ")
                            .append(minutes).append("분 동안 유효해요. ")
                        append("승인 전에는 ").append(authorization.intervalSeconds)
                            .append("초 간격으로 poll을 실행해 주세요.")
                    },
                )
            },
            onFailure = { error -> ToolResult(error.message.orEmpty(), isError = true) },
        )

    private suspend fun poll(host: PluginHost): ToolResult =
        auth.pollDeviceAuthorization(host).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is PollOutcome.Connected -> ToolResult(
                        buildString {
                            append("Google 계정 연결이 완료됐어요. 갱신 토큰은 SECRET 설정에만 저장했어요.")
                            val scopes = outcome.scope.orEmpty().trim()
                            if (scopes.isNotEmpty()) {
                                append("\n부여된 범위: ").append(scopes)
                            } else {
                                append("\n부여된 범위는 google_auth status에서 확인할 수 있어요.")
                            }
                        },
                    )

                    is PollOutcome.Waiting -> ToolResult(
                        "아직 사용자가 승인하지 않았어요. 약 ${outcome.seconds}초 뒤에 google_auth poll을 다시 실행해 주세요.",
                    )

                    is PollOutcome.SlowDown -> ToolResult(
                        "Google이 요청 간격을 늘리라고 했어요. 약 ${outcome.intervalSeconds}초 뒤에 google_auth poll을 다시 실행해 주세요.",
                    )

                    PollOutcome.Expired -> ToolResult(
                        "사용자 코드가 만료됐어요. google_auth start로 새 코드를 받아 주세요.",
                        isError = true,
                    )

                    PollOutcome.Denied -> ToolResult(
                        "사용자가 Google 계정 연결을 거부했어요. 필요하면 google_auth start로 다시 시도해 주세요.",
                        isError = true,
                    )
                }
            },
            onFailure = { error -> ToolResult(error.message.orEmpty(), isError = true) },
        )

    private fun status(host: PluginHost): ToolResult {
        val status = auth.status(host)
        val lines = mutableListOf<String>()
        if (status.connected) {
            lines += "Google 계정이 연결되어 있어요."
            lines += if (status.scopes.isBlank()) {
                "기록된 범위가 없어요. google_auth start로 다시 연결하면 범위가 기록돼요."
            } else {
                "부여된 범위: ${status.scopes}"
            }
        } else {
            lines += "Google 계정이 연결되어 있지 않아요. google_auth start로 연결해 주세요."
        }
        if (!status.clientConfigured) {
            lines += "OAuth 클라이언트 ID 또는 보안 비밀이 설정되지 않았어요. Google Cloud Console에서 만든 값을 설정에 입력해 주세요."
        }
        return ToolResult(lines.joinToString("\n"))
    }

    private fun logout(host: PluginHost): ToolResult =
        auth.logout(host).fold(
            onSuccess = {
                ToolResult(
                    "Google 연결을 해제했어요. 저장된 갱신 토큰과 승인 대기 정보를 삭제했어요. " +
                        "Google 계정의 앱 액세스에서도 제거하려면 Google 계정 보안 설정에서 직접 삭제해 주세요.",
                )
            },
            onFailure = { error ->
                ToolResult("갱신 토큰을 삭제하지 못했어요: ${error.message.orEmpty()}", isError = true)
            },
        )
}
