package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.ToolSpec
import com.sleepysoong.logent.plugin.integer
import com.sleepysoong.logent.plugin.integerProperty
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.toolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `google_photos` 도구예요.
 *
 * Photos Library API는 원본 다운로드에 baseUrl과 OAuth Authorization 헤더가 필요하고
 * 응답이 바이너리라서 `PluginHost.http`의 String 계약으로는 받을 수 없어요.
 * 그래서 메타데이터만 다루고 다운로드는 지원하지 않는다고 명확히 알려요.
 */
internal class GooglePhotosTool(private val api: GoogleApi) {
    val spec: ToolSpec = toolSpec(
        name = "google_photos",
        description = "Google 포토의 미디어 항목과 앨범 메타데이터를 조회한다. " +
            "list는 최근 항목, albums는 앨범 목록, search는 album_id 또는 날짜 범위로 검색한다. " +
            "원본 이미지·영상 다운로드는 baseUrl에 OAuth 인증이 필요한 바이너리 응답이라 지원하지 않는다. " +
            "이 도구는 파일을 저장하거나 삭제하지 않는다.",
        required = listOf("action"),
    ) {
        stringProperty("action", "작업이에요", listOf("list", "albums", "search"))
        integerProperty("page_size", "결과 수예요. 1~50, 기본 25.", 1, 50)
        stringProperty("page_token", "다음 페이지 토큰이에요.")
        stringProperty("album_id", "search 앨범 ID예요.")
        stringProperty("start_date", "search 시작 날짜예요. yyyy-MM-dd")
        stringProperty("end_date", "search 끝 날짜예요. yyyy-MM-dd")
    }

    suspend fun execute(host: PluginHost, arguments: JsonObject): ToolResult =
        when (val action = arguments.text("action")?.trim()?.lowercase()) {
            "list" -> list(host, arguments)
            "albums" -> albums(host, arguments)
            "search" -> search(host, arguments)
            null -> error("action이 필요해요. list, albums, search 중 하나를 지정해 주세요.")
            else -> error("지원하지 않는 action이에요: $action")
        }

    private suspend fun list(host: PluginHost, arguments: JsonObject): ToolResult {
        val pageSize = (arguments.integer("page_size") ?: 25).coerceIn(1, 50)
        val url = buildString {
            append(MEDIA_ITEMS_ENDPOINT)
            append("?pageSize=").append(pageSize)
            arguments.text("page_token")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("&pageToken=").append(GoogleMime.percentEncode(it))
            }
        }
        val root = api.getJson(host, url).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Google 포토 응답을 해석하지 못했어요.")
        return mediaResult(root)
    }

    private suspend fun albums(host: PluginHost, arguments: JsonObject): ToolResult {
        val pageSize = (arguments.integer("page_size") ?: 25).coerceIn(1, 50)
        val url = buildString {
            append(ALBUMS_ENDPOINT)
            append("?pageSize=").append(pageSize)
            arguments.text("page_token")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("&pageToken=").append(GoogleMime.percentEncode(it))
            }
        }
        val root = api.getJson(host, url).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Google 포토 앨범 응답을 해석하지 못했어요.")
        val albums = root.arr("albums").orEmpty()
        if (albums.isEmpty()) return ToolResult("Google 포토 앨범이 없어요.")
        val lines = albums.mapNotNull { element ->
            val album = element as? JsonObject ?: return@mapNotNull null
            buildString {
                append("- ").append(album.str("title").orEmpty().ifBlank { "(제목 없음)" })
                append(" · ").append(album.str("itemCount") ?: "?").append("개\n")
                append("  id: ").append(album.str("id").orEmpty())
            }
        }
        return ToolResult(
            buildString {
                append(lines.joinToString("\n"))
                appendPaging(root)
            },
        )
    }

    private suspend fun search(host: PluginHost, arguments: JsonObject): ToolResult {
        val albumId = arguments.text("album_id")?.trim()?.takeIf { it.isNotEmpty() }
        val startRaw = arguments.text("start_date")?.trim()?.takeIf { it.isNotEmpty() }
        val endRaw = arguments.text("end_date")?.trim()?.takeIf { it.isNotEmpty() }
        if (albumId == null && startRaw == null && endRaw == null) {
            return error("search에는 album_id 또는 start_date/end_date가 필요해요.")
        }
        if (albumId != null && (startRaw != null || endRaw != null)) {
            return error("album_id와 날짜 범위는 함께 지정할 수 없어요.")
        }
        if (startRaw != null && endRaw != null && startRaw > endRaw) {
            return error("start_date는 end_date보다 앞서야 해요.")
        }
        val startDate = startRaw?.let { raw ->
            GoogleMime.photoDate(raw) ?: return error("start_date가 yyyy-MM-dd 형식이 아니에요: $raw")
        }
        val endDate = endRaw?.let { raw ->
            GoogleMime.photoDate(raw) ?: return error("end_date가 yyyy-MM-dd 형식이 아니에요: $raw")
        }
        val pageSize = (arguments.integer("page_size") ?: 25).coerceIn(1, 50)
        val pageToken = arguments.text("page_token")?.trim()?.takeIf { it.isNotEmpty() }
        val body = buildJsonObject {
            put("pageSize", pageSize)
            pageToken?.let { put("pageToken", it) }
            if (albumId != null) {
                put("albumId", albumId)
            } else {
                putJsonObject("filters") {
                    putJsonObject("dateFilter") {
                        putJsonArray("ranges") {
                            add(buildJsonObject {
                                startDate?.let { put("startDate", it) }
                                endDate?.let { put("endDate", it) }
                            })
                        }
                    }
                }
            }
        }
        val root = api.postJson(host, SEARCH_ENDPOINT, body).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Google 포토 검색 응답을 해석하지 못했어요.")
        return mediaResult(root)
    }

    private fun mediaResult(root: JsonObject): ToolResult {
        val items = root.arr("mediaItems").orEmpty()
        if (items.isEmpty()) {
            return ToolResult(
                "조건에 맞는 Google 포토 항목이 없어요.\n" +
                    "참고: 원본 이미지·영상 다운로드는 지원하지 않아요.",
            )
        }
        val lines = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val metadata = item.obj("mediaMetadata")
            buildString {
                append("- ").append(item.str("filename").orEmpty().ifBlank { "(파일 이름 없음)" })
                append(" (").append(item.str("mimeType").orEmpty()).append(")\n")
                append("  id: ").append(item.str("id").orEmpty())
                metadata?.str("creationTime")?.takeIf { it.isNotBlank() }?.let {
                    append(" · 생성: ").append(it)
                }
                val width = metadata?.str("width")
                val height = metadata?.str("height")
                if (!width.isNullOrBlank() && !height.isNullOrBlank()) {
                    append(" · ").append(width).append("x").append(height)
                }
            }
        }
        return ToolResult(
            buildString {
                append(lines.joinToString("\n"))
                appendPaging(root)
                append("\n\n참고: 원본 이미지·영상 다운로드는 지원하지 않아요. ")
                append("원본은 baseUrl에 OAuth 인증이 필요한 바이너리 응답이라 이 플러그인의 HTTP 계약으로 받을 수 없어요. ")
                append("Google 포토 앱이나 웹에서 직접 내려받아 주세요.")
            },
        )
    }

    private fun StringBuilder.appendPaging(root: JsonObject) {
        root.str("nextPageToken")?.takeIf { it.isNotBlank() }?.let {
            append("\n\n다음 페이지 토큰: ").append(it)
        }
    }

    private fun error(message: String): ToolResult = ToolResult(message, isError = true)

    private companion object {
        const val PHOTOS_BASE = "https://photoslibrary.googleapis.com/v1"
        const val MEDIA_ITEMS_ENDPOINT = "$PHOTOS_BASE/mediaItems"
        const val SEARCH_ENDPOINT = "$PHOTOS_BASE/mediaItems:search"
        const val ALBUMS_ENDPOINT = "$PHOTOS_BASE/albums"
    }
}
