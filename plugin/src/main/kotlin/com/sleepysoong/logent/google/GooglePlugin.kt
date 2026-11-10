package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.LogentPlugin
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginSetting
import com.sleepysoong.logent.plugin.PluginSettingKind
import com.sleepysoong.logent.plugin.PluginTool
import com.sleepysoong.logent.plugin.ToolAccess
import com.sleepysoong.logent.plugin.ToolResult

/**
 * Google Workspace 도구를 제공하는 독립 플러그인이에요.
 *
 * 인증은 OAuth 2.0 Device Authorization Grant를 쓰고, 액세스 토큰은 메모리에만,
 * 갱신 토큰·클라이언트 자격 증명은 SECRET 설정에만 저장해요.
 * HTTP는 호스트의 `PluginHost.http`만 사용하고 바이너리 전송은 지원하지 않아요.
 */
class GooglePlugin : LogentPlugin {
    override val id: String = "com.sleepysoong.logent.google"
    override val name: String = "Google"
    override val version: String = BuildConfig.PLUGIN_VERSION
    override val apiVersion: Int = 4

    @Volatile
    private var host: PluginHost? = null

    private val auth = GoogleAuth()
    private val api = GoogleApi(auth)
    private val authTool = GoogleAuthTool(auth)
    private val driveTool = GoogleDriveTool(api)
    private val gmailTool = GoogleGmailTool(api)
    private val calendarTool = GoogleCalendarTool(api)
    private val photosTool = GooglePhotosTool(api)
    private val appsTool = GoogleAppsTool()

    override fun settings(): List<PluginSetting> = listOf(
        PluginSetting(
            key = GoogleAuthKeys.CLIENT_ID,
            title = "OAuth 클라이언트 ID",
            description = "Google Cloud Console에서 만든 OAuth 클라이언트 ID예요. " +
                "SECRET으로만 저장하고 응답·로그·프롬프트에 넣지 않아요.",
            kind = PluginSettingKind.SECRET,
        ),
        PluginSetting(
            key = GoogleAuthKeys.CLIENT_SECRET,
            title = "OAuth 클라이언트 보안 비밀",
            description = "OAuth 클라이언트 보안 비밀이에요. " +
                "SECRET으로만 저장하고 응답·로그·프롬프트에 넣지 않아요.",
            kind = PluginSettingKind.SECRET,
        ),
        PluginSetting(
            key = GoogleAuthKeys.REFRESH_TOKEN,
            title = "OAuth 갱신 토큰",
            description = "google_auth poll이 연결 성공 시 자동으로 저장해요. 직접 편집하지 마세요. " +
                "SECRET으로만 저장해요.",
            kind = PluginSettingKind.SECRET,
        ),
        PluginSetting(
            key = GoogleAuthKeys.GRANTED_SCOPE,
            title = "부여된 OAuth 범위",
            description = "google_auth가 부여된 범위를 기록해요. 직접 편집하지 않아요.",
            kind = PluginSettingKind.TEXT,
        ),
    )

    override fun tools(): List<PluginTool> = listOf(
        PluginTool(
            spec = authTool.spec,
            access = setOf(ToolAccess.WRITE),
            serialized = true,
            childVisible = false,
            label = "구글 인증",
        ) { arguments, _ ->
            runTool { current -> authTool.execute(current, arguments) }
        },
        PluginTool(
            spec = driveTool.spec,
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "구글 드라이브",
        ) { arguments, _ ->
            runTool { current -> driveTool.execute(current, arguments) }
        },
        PluginTool(
            spec = gmailTool.spec,
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "지메일",
        ) { arguments, _ ->
            runTool { current -> gmailTool.execute(current, arguments) }
        },
        PluginTool(
            spec = calendarTool.spec,
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "구글 캘린더",
        ) { arguments, _ ->
            runTool { current -> calendarTool.execute(current, arguments) }
        },
        PluginTool(
            spec = photosTool.spec,
            access = setOf(ToolAccess.READ_ONLY),
            childVisible = true,
            label = "구글 포토",
        ) { arguments, _ ->
            runTool { current -> photosTool.execute(current, arguments) }
        },
        PluginTool(
            spec = appsTool.spec,
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "구글 앱",
        ) { arguments, _ ->
            runTool { current -> appsTool.execute(current, arguments) }
        },
    )

    override fun promptSection(): String = buildString {
        append("Google 도구를 쓰기 전에 google_auth start로 기기 승인을 시작하고, ")
        append("사용자가 verification_url에서 user_code를 승인한 뒤 google_auth poll로 연결을 완료해요. ")
        append("액세스 토큰·갱신 토큰·device_code는 사용자에게 보여 주거나 프롬프트에 넣지 않아요. ")
        append("Drive·Gmail·Calendar·Photos는 텍스트·메타데이터만 다루고 바이너리 업로드·다운로드는 지원하지 않아요. ")
        append("연결이 만료됐다는 오류가 나오면 임의로 반복하지 말고 google_auth start로 다시 연결하도록 안내해요.")
    }

    override suspend fun ensureReady(host: PluginHost): Result<Unit> {
        this.host = host
        return Result.success(Unit)
    }

    override fun onSessionReset() {
        auth.clearMemory()
    }

    private suspend fun runTool(block: suspend (PluginHost) -> ToolResult): ToolResult {
        val current = host ?: return ToolResult(
            "Google 플러그인이 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요.",
            isError = true,
        )
        return try {
            block(current)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ToolResult(
                "Google 요청을 처리하지 못했어요: ${failure.message ?: failure::class.simpleName.orEmpty()}",
                isError = true,
            )
        }
    }
}
