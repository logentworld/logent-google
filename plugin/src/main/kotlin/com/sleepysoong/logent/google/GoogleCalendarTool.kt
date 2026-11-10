package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.ToolSpec
import com.sleepysoong.logent.plugin.integer
import com.sleepysoong.logent.plugin.integerProperty
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.toolSpec
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject

/** `google_calendar` 도구예요. primary 캘린더만 다뤄요. */
internal class GoogleCalendarTool(private val api: GoogleApi) {
    val spec: ToolSpec = toolSpec(
        name = "google_calendar",
        description = "기본(primary) Google 캘린더의 일정을 조회·생성·삭제한다. " +
            "time_min·time_max·start·end는 RFC3339 형식이어야 한다. " +
            "생성 시 시간대는 기기 기본 시간대를 쓴다.",
        required = listOf("action"),
    ) {
        stringProperty("action", "작업이에요", listOf("list", "create", "delete"))
        stringProperty("time_min", "list 시작 시각이에요. RFC3339 예: 2026-01-01T00:00:00+09:00")
        stringProperty("time_max", "list 끝 시각이에요. RFC3339")
        integerProperty("max_results", "list 결과 수예요. 1~50, 기본 20.", 1, 50)
        stringProperty("summary", "create 일정 제목이에요.")
        stringProperty("start", "create 시작 시각이에요. RFC3339")
        stringProperty("end", "create 끝 시각이에요. RFC3339")
        stringProperty("description", "create 일정 설명이에요.")
        stringProperty("location", "create 장소예요.")
        stringProperty("event_id", "delete 일정 ID예요.")
    }

    suspend fun execute(host: PluginHost, arguments: JsonObject): ToolResult =
        when (val action = arguments.text("action")?.trim()?.lowercase()) {
            "list" -> list(host, arguments)
            "create" -> create(host, arguments)
            "delete" -> delete(host, arguments)
            null -> error("action이 필요해요. list, create, delete 중 하나를 지정해 주세요.")
            else -> error("지원하지 않는 action이에요: $action")
        }

    private suspend fun list(host: PluginHost, arguments: JsonObject): ToolResult {
        val timeMin = arguments.text("time_min")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("list에는 time_min이 필요해요.")
        val timeMax = arguments.text("time_max")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("list에는 time_max가 필요해요.")
        val min = GoogleMime.rfc3339Instant(timeMin)
            ?: return error("time_min이 RFC3339 형식이 아니에요. 예: 2026-01-01T00:00:00+09:00")
        val max = GoogleMime.rfc3339Instant(timeMax)
            ?: return error("time_max가 RFC3339 형식이 아니에요. 예: 2026-01-31T23:59:59+09:00")
        if (min >= max) return error("time_min은 time_max보다 앞서야 해요.")
        val maxResults = (arguments.integer("max_results") ?: 20).coerceIn(1, 50)
        val url = buildString {
            append(EVENTS_ENDPOINT)
            append("?singleEvents=true&orderBy=startTime&maxResults=").append(maxResults)
            append("&timeMin=").append(GoogleMime.percentEncode(timeMin))
            append("&timeMax=").append(GoogleMime.percentEncode(timeMax))
        }
        val root = api.getJson(host, url).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("캘린더 응답을 해석하지 못했어요.")
        val items = root.arr("items").orEmpty()
        if (items.isEmpty()) return ToolResult("해당 기간에 일정이 없어요.")
        val lines = items.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            buildString {
                append("- ").append(eventTime(event.obj("start")))
                append(" ~ ").append(eventTime(event.obj("end")))
                append(" | ").append(event.str("summary").orEmpty().ifBlank { "(제목 없음)" }).append("\n")
                event.str("location")?.takeIf { it.isNotBlank() }?.let { append("  장소: ").append(it).append("\n") }
                append("  id: ").append(event.str("id").orEmpty())
                event.str("description")?.takeIf { it.isNotBlank() }?.let {
                    append("\n  설명: ").append(it.replace('\n', ' ').take(300))
                }
            }
        }
        return ToolResult(lines.joinToString("\n"))
    }

    private suspend fun create(host: PluginHost, arguments: JsonObject): ToolResult {
        val summary = arguments.text("summary")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("create에는 summary가 필요해요.")
        val start = arguments.text("start")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("create에는 start가 필요해요.")
        val end = arguments.text("end")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("create에는 end가 필요해요.")
        val startInstant = GoogleMime.rfc3339Instant(start)
            ?: return error("start가 RFC3339 형식이 아니에요. 예: 2026-01-01T09:00:00+09:00")
        val endInstant = GoogleMime.rfc3339Instant(end)
            ?: return error("end가 RFC3339 형식이 아니에요. 예: 2026-01-01T10:00:00+09:00")
        if (startInstant >= endInstant) return error("start는 end보다 앞서야 해요.")
        val description = arguments.text("description")?.trim()?.takeIf { it.isNotEmpty() }
        val location = arguments.text("location")?.trim()?.takeIf { it.isNotEmpty() }
        val timeZone = TimeZone.getDefault().id
        val body = GoogleMime.calendarEventBody(
            summary = summary,
            start = start,
            end = end,
            timeZone = timeZone,
            description = description,
            location = location,
        )
        val created = api.postJson(
            host,
            "$EVENTS_ENDPOINT?fields=" + GoogleMime.percentEncode("id,htmlLink,summary,start,end"),
            body,
        ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("캘린더 일정 생성 응답을 해석하지 못했어요.")
        return ToolResult(
            buildString {
                append("일정을 만들었어요.")
                created.str("id")?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
                append("\n제목: ").append(created.str("summary") ?: summary)
                append("\n시간대: ").append(timeZone)
                created.str("htmlLink")?.takeIf { it.isNotBlank() }?.let { append("\n링크: ").append(it) }
            },
        )
    }

    private suspend fun delete(host: PluginHost, arguments: JsonObject): ToolResult {
        val eventId = arguments.text("event_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("delete에는 event_id가 필요해요.")
        api.delete(host, "$EVENTS_ENDPOINT/${GoogleMime.percentEncode(eventId)}")
            .getOrElse { return error(it.message.orEmpty()) }
        return ToolResult("일정을 삭제했어요. id=$eventId")
    }

    private fun eventTime(time: JsonObject?): String {
        time?.str("dateTime")?.takeIf { it.isNotBlank() }?.let { return it }
        return time?.str("date") ?: "-"
    }

    private fun error(message: String): ToolResult = ToolResult(message, isError = true)

    private companion object {
        const val EVENTS_ENDPOINT = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
    }
}
