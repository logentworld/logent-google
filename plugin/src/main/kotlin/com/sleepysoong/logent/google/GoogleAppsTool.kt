package com.sleepysoong.logent.google

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.ToolSpec
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.toolSpec
import kotlinx.serialization.json.JsonObject

/** Google 앱의 Android 패키지와 웹 대체 URL이에요. */
internal enum class GoogleAppsTarget(
    val packageName: String,
    val webUrl: String,
    val displayName: String,
) {
    GMAIL("com.google.android.gm", "https://mail.google.com/", "지메일"),
    DRIVE("com.google.android.apps.docs", "https://drive.google.com/", "구글 드라이브"),
    PHOTOS("com.google.android.apps.photos", "https://photos.google.com/", "구글 포토"),
    CALENDAR("com.google.android.calendar", "https://calendar.google.com/", "구글 캘린더"),
    MAPS("com.google.android.apps.maps", "https://maps.google.com/", "구글 지도"),
    YOUTUBE("com.google.android.youtube", "https://www.youtube.com/", "유튜브"),
    ;

    companion object {
        fun from(value: String?): GoogleAppsTarget? {
            val normalized = value?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

/** `google_apps` 도구예요. 설치된 앱을 우선 열고, 없으면 웹 URL을 열어요. */
internal class GoogleAppsTool {
    val spec: ToolSpec = toolSpec(
        name = "google_apps",
        description = "Android 기기에서 지메일·드라이브·포토·캘린더·지도·유튜브 앱을 연다. " +
            "앱이 설치되어 있지 않으면 같은 서비스의 웹 페이지를 연다. " +
            "파일을 읽거나 쓰지 않고 앱 화면만 띄운다.",
        required = listOf("app"),
    ) {
        stringProperty(
            "app",
            "열 앱이에요",
            listOf("gmail", "drive", "photos", "calendar", "maps", "youtube"),
        )
    }

    fun execute(host: PluginHost, arguments: JsonObject): ToolResult {
        val target = GoogleAppsTarget.from(arguments.text("app"))
            ?: return error("app이 필요해요. gmail, drive, photos, calendar, maps, youtube 중 하나를 지정해 주세요.")
        val context = host.androidContext as? Context
            ?: return error("Android 앱 실행을 지원하지 않는 환경이에요. Android Logent에서 실행해 주세요.")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
        val intent = if (launchIntent != null) {
            launchIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(target.webUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(intent)
            if (launchIntent != null) {
                ToolResult("${target.displayName} 앱을 열었어요.")
            } else {
                ToolResult("${target.displayName} 앱이 설치되어 있지 않아 웹 페이지를 열었어요: ${target.webUrl}")
            }
        } catch (_: ActivityNotFoundException) {
            error("${target.displayName} 앱과 웹 페이지를 열 수 있는 앱을 찾지 못했어요. 브라우저가 설치되어 있는지 확인해 주세요.")
        } catch (exception: SecurityException) {
            error("${target.displayName} 앱을 열 권한이 없어요: ${exception.message.orEmpty()}")
        }
    }

    private fun error(message: String): ToolResult = ToolResult(message, isError = true)
}
