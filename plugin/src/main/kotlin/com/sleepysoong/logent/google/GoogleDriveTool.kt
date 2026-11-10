package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.ToolSpec
import com.sleepysoong.logent.plugin.integer
import com.sleepysoong.logent.plugin.integerProperty
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.toolSpec
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** `google_drive` 도구예요. 텍스트·메타데이터만 다루고 바이너리는 다루지 않아요. */
internal class GoogleDriveTool(private val api: GoogleApi) {
    val spec: ToolSpec = toolSpec(
        name = "google_drive",
        description = "Google Drive 파일을 검색·조회하고 텍스트 파일과 폴더를 만들거나 이동·휴지통 처리한다. " +
            "read_text는 text/*, JSON, XML 같은 텍스트 MIME만 최대 512KB까지 읽는다. " +
            "바이너리 업로드·다운로드와 Google 문서·시트 같은 네이티브 파일의 직접 읽기는 지원하지 않는다. " +
            "drive.file 범위라서 이 플러그인이 만들거나 연 파일, 공유받은 파일만 접근할 수 있다.",
        required = listOf("action"),
    ) {
        stringProperty(
            "action",
            "작업이에요",
            listOf("search", "get", "read_text", "create_text", "create_folder", "move", "trash"),
        )
        stringProperty("q", "search 검색어예요. 파일 이름·내용 일부를 넘겨요.")
        integerProperty("page_size", "search 결과 수예요. 1~50, 기본 20.", 1, 50)
        stringProperty("page_token", "search 다음 페이지 토큰이에요.")
        stringProperty("file_id", "get·read_text·move·trash 대상 파일 ID예요.")
        stringProperty("name", "create_text·create_folder 이름이에요.")
        stringProperty("content", "create_text 본문이에요. UTF-8 기준 최대 200KB예요.")
        stringProperty("parent_id", "create_text·create_folder·move 부모 폴더 ID예요.")
        stringProperty("mime_type", "create_text MIME 타입이에요. 기본 text/plain.")
    }

    suspend fun execute(host: PluginHost, arguments: JsonObject): ToolResult =
        when (val action = arguments.text("action")?.trim()?.lowercase()) {
            "search" -> search(host, arguments)
            "get" -> get(host, arguments)
            "read_text" -> readText(host, arguments)
            "create_text" -> createText(host, arguments)
            "create_folder" -> createFolder(host, arguments)
            "move" -> move(host, arguments)
            "trash" -> trash(host, arguments)
            null -> error("action이 필요해요. search, get, read_text, create_text, create_folder, move, trash 중 하나를 지정해 주세요.")
            else -> error("지원하지 않는 action이에요: $action")
        }

    private suspend fun search(host: PluginHost, arguments: JsonObject): ToolResult {
        val term = arguments.text("q")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("search에는 q가 필요해요.")
        if (term.length > 500) return error("검색어는 500자 이하여야 해요.")
        val pageSize = (arguments.integer("page_size") ?: 20).coerceIn(1, 50)
        val url = buildString {
            append(FILES_ENDPOINT)
            append("?q=").append(GoogleMime.percentEncode(GoogleMime.driveSearchQuery(term)))
            append("&pageSize=").append(pageSize)
            append("&fields=").append(GoogleMime.percentEncode(SEARCH_FIELDS))
            arguments.text("page_token")?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                append("&pageToken=").append(GoogleMime.percentEncode(token))
            }
        }
        val root = api.getJson(host, url).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Drive 검색 응답을 해석하지 못했어요.")
        val files = root.arr("files").orEmpty()
        if (files.isEmpty()) return ToolResult("조건에 맞는 Drive 파일을 찾지 못했어요.")
        val lines = files.mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            buildString {
                append("- ").append(file.str("name").orEmpty().ifBlank { "(이름 없음)" })
                append(" (").append(file.str("mimeType").orEmpty().ifBlank { "MIME 미상" }).append(")\n")
                append("  id: ").append(file.str("id").orEmpty()).append("\n")
                append("  수정: ").append(file.str("modifiedTime").orEmpty().ifBlank { "-" })
                append(" · 크기: ").append(humanSize(file.str("size"))).append("\n")
                file.str("webViewLink")?.takeIf { it.isNotBlank() }?.let {
                    append("  링크: ").append(it)
                }
            }.trimEnd()
        }
        val next = root.str("nextPageToken")
        return ToolResult(
            buildString {
                append(lines.joinToString("\n"))
                if (!next.isNullOrBlank()) {
                    append("\n\n다음 페이지 토큰: ").append(next)
                }
            },
        )
    }

    private suspend fun get(host: PluginHost, arguments: JsonObject): ToolResult {
        val fileId = arguments.text("file_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("get에는 file_id가 필요해요.")
        val url = "$FILES_ENDPOINT/${GoogleMime.percentEncode(fileId)}?fields=" +
            GoogleMime.percentEncode(GET_FIELDS)
        val file = api.getJson(host, url).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Drive 파일 응답을 해석하지 못했어요.")
        return ToolResult(
            buildString {
                append("이름: ").append(file.str("name").orEmpty().ifBlank { "(이름 없음)" }).append("\n")
                append("id: ").append(file.str("id").orEmpty()).append("\n")
                append("MIME: ").append(file.str("mimeType").orEmpty()).append("\n")
                append("크기: ").append(humanSize(file.str("size"))).append("\n")
                append("수정: ").append(file.str("modifiedTime").orEmpty().ifBlank { "-" }).append("\n")
                append("생성: ").append(file.str("createdTime").orEmpty().ifBlank { "-" }).append("\n")
                append("휴지통: ").append(if ((file["trashed"] as? JsonPrimitive)?.contentOrNull == "true") "예" else "아니요").append("\n")
                val parents = file.arr("parents").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (parents.isNotEmpty()) append("부모: ").append(parents.joinToString(", ")).append("\n")
                file.str("description")?.takeIf { it.isNotBlank() }?.let { append("설명: ").append(it).append("\n") }
                file.str("webViewLink")?.takeIf { it.isNotBlank() }?.let { append("링크: ").append(it) }
            }.trimEnd(),
        )
    }

    private suspend fun readText(host: PluginHost, arguments: JsonObject): ToolResult {
        val fileId = arguments.text("file_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("read_text에는 file_id가 필요해요.")
        val encodedId = GoogleMime.percentEncode(fileId)
        val metadata = api.getJson(
            host,
            "$FILES_ENDPOINT/$encodedId?fields=id,name,mimeType,size",
        ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Drive 파일 메타데이터를 해석하지 못했어요.")
        val mimeType = metadata.str("mimeType").orEmpty()
        if (mimeType.startsWith("application/vnd.google-apps.")) {
            return error(
                "Google 문서·시트·슬라이드 같은 네이티브 파일은 alt=media로 읽을 수 없어요 ($mimeType). " +
                    "Drive에서 텍스트 형식으로 내보낸 파일을 사용해 주세요.",
            )
        }
        if (!GoogleMime.isTextMime(mimeType)) {
            return error(
                "이 파일은 텍스트가 아니에요 ($mimeType). 이 플러그인은 텍스트·JSON·XML만 읽고 " +
                    "바이너리 다운로드는 지원하지 않아요.",
            )
        }
        val response = api.request(
            host = host,
            method = "GET",
            url = "$FILES_ENDPOINT/$encodedId?alt=media",
            maxResponseBytes = 512 * 1024,
        ).getOrElse { return error(it.message.orEmpty()) }
        if (response.status !in 200..299) {
            return error(googleApiError(response.status, response.body))
        }
        val name = metadata.str("name").orEmpty().ifBlank { fileId }
        if (response.body.isEmpty()) {
            return ToolResult("파일이 비어 있어요: $name ($mimeType)")
        }
        val safeBody = response.body
            .replace("<untrusted_drive_text>", "&lt;untrusted_drive_text&gt;")
            .replace("</untrusted_drive_text>", "&lt;/untrusted_drive_text&gt;")
        return ToolResult(
            buildString {
                append("파일: ").append(name).append(" (").append(mimeType).append(")\n\n")
                append("<untrusted_drive_text>\n").append(safeBody).append("\n</untrusted_drive_text>")
            },
        )
    }

    private suspend fun createText(host: PluginHost, arguments: JsonObject): ToolResult {
        val name = arguments.text("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("create_text에는 name이 필요해요.")
        if (name.length > 200) return error("파일 이름은 200자 이하여야 해요.")
        val content = arguments.text("content") ?: return error("create_text에는 content가 필요해요.")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return error("본문이 너무 커요. UTF-8 기준 ${MAX_TEXT_BYTES / 1000}KB 이하만 업로드할 수 있어요.")
        }
        val mimeType = arguments.text("mime_type")?.trim()?.takeIf { it.isNotEmpty() } ?: "text/plain"
        if (!GoogleMime.isValidMimeType(mimeType)) {
            return error("mime_type이 올바른 MIME 타입이 아니에요: $mimeType")
        }
        if (mimeType.startsWith("application/vnd.google-apps.")) {
            return error("Google 네이티브 형식으로는 파일을 만들 수 없어요. text/* 또는 텍스트 application MIME을 써 주세요.")
        }
        if (!GoogleMime.isTextMime(mimeType)) {
            return error("create_text는 텍스트 MIME만 만들 수 있어요: $mimeType. 바이너리 업로드는 지원하지 않아요.")
        }
        val parentId = arguments.text("parent_id")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", mimeType)
            if (parentId != null) {
                putJsonArray("parents") { add(parentId) }
            }
        }
        val boundary = newBoundary(content)
        val multipart = GoogleMime.buildDriveMultipart(
            boundary = boundary,
            metadataJson = metadata.toString(),
            content = content,
            mimeType = mimeType,
        )
        val created = api.request(
            host = host,
            method = "POST",
            url = UPLOAD_ENDPOINT + "?uploadType=multipart&fields=" +
                GoogleMime.percentEncode("id,name,mimeType,webViewLink"),
            body = multipart,
            contentType = "multipart/related; boundary=$boundary",
            maxResponseBytes = 64 * 1024,
        ).getOrElse { failure ->
            return error("Drive 파일을 만들지 못했어요: ${failure.message.orEmpty()}")
        }
        if (created.status !in 200..299) {
            return error(googleApiError(created.status, created.body))
        }
        val uploaded = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(created.body) }
            .getOrNull() as? JsonObject
            ?: return error("Drive 파일 생성 응답을 해석하지 못했어요.")
        val fileId = uploaded.str("id")?.takeIf { it.isNotBlank() }
            ?: return error("Drive가 생성된 파일 ID를 반환하지 않았어요.")
        return ToolResult(
            buildString {
                append("텍스트 파일을 만들었어요. id=").append(fileId)
                append(", 이름=").append(uploaded.str("name") ?: name)
                uploaded.str("webViewLink")?.takeIf { it.isNotBlank() }?.let {
                    append("\n링크: ").append(it)
                }
            },
        )
    }

    private suspend fun createFolder(host: PluginHost, arguments: JsonObject): ToolResult {
        val name = arguments.text("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("create_folder에는 name이 필요해요.")
        val parentId = arguments.text("parent_id")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) {
                putJsonArray("parents") { add(parentId) }
            }
        }
        val created = api.postJson(
            host,
            "$FILES_ENDPOINT?fields=" + GoogleMime.percentEncode("id,name,webViewLink"),
            metadata,
        ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Drive 폴더 생성 응답을 해석하지 못했어요.")
        val id = created.str("id")
        return ToolResult(
            buildString {
                append("폴더를 만들었어요. id=").append(id.orEmpty())
                append(", 이름=").append(created.str("name") ?: name)
                created.str("webViewLink")?.takeIf { it.isNotBlank() }?.let { append("\n링크: ").append(it) }
            },
        )
    }

    private suspend fun move(host: PluginHost, arguments: JsonObject): ToolResult {
        val fileId = arguments.text("file_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("move에는 file_id가 필요해요.")
        val parentId = arguments.text("parent_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("move에는 새 부모 parent_id가 필요해요.")
        val encodedId = GoogleMime.percentEncode(fileId)
        val current = api.getJson(
            host,
            "$FILES_ENDPOINT/$encodedId?fields=parents",
        ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("파일의 현재 부모 정보를 해석하지 못했어요.")
        val oldParents = current.arr("parents").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it != parentId }
        // Drive v3 update는 PATCH만 받고 호스트 HTTP는 PATCH를 허용하지 않아요.
        // 같은 Drive API의 v2 update는 PUT을 받으므로 부모 이동에만 v2를 써요.
        val url = buildString {
            append("https://www.googleapis.com/drive/v2/files/$encodedId")
            append("?addParents=").append(GoogleMime.percentEncode(parentId))
            if (oldParents.isNotEmpty()) {
                append("&removeParents=").append(GoogleMime.percentEncode(oldParents.joinToString(",")))
            }
            append("&fields=").append(GoogleMime.percentEncode("id,title"))
        }
        val response = api.request(
            host = host,
            method = "PUT",
            url = url,
            body = "{}",
            contentType = "application/json; charset=UTF-8",
            maxResponseBytes = 64 * 1024,
        ).getOrElse { return error(it.message.orEmpty()) }
        if (response.status !in 200..299) {
            return error(googleApiError(response.status, response.body))
        }
        val moved = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(response.body) }
            .getOrNull() as? JsonObject
        return ToolResult(
            "파일을 옮겼어요. id=${moved?.str("id") ?: fileId}, " +
                "이름=${moved?.str("title").orEmpty()}, 부모=$parentId",
        )
    }

    private suspend fun trash(host: PluginHost, arguments: JsonObject): ToolResult {
        val fileId = arguments.text("file_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("trash에는 file_id가 필요해요.")
        // v2 files.trash는 POST라서 호스트의 GET/POST/PUT/DELETE 계약을 지켜요.
        val response = api.request(
            host = host,
            method = "POST",
            url = "https://www.googleapis.com/drive/v2/files/${GoogleMime.percentEncode(fileId)}/trash" +
                "?fields=" + GoogleMime.percentEncode("id,title"),
            maxResponseBytes = 64 * 1024,
        ).getOrElse { return error(it.message.orEmpty()) }
        if (response.status !in 200..299) {
            return error(googleApiError(response.status, response.body))
        }
        val trashed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(response.body) }
            .getOrNull() as? JsonObject
        return ToolResult(
            "파일을 휴지통으로 옮겼어요. id=${trashed?.str("id") ?: fileId}, " +
                "이름=${trashed?.str("title").orEmpty()}",
        )
    }

    private fun newBoundary(content: String): String {
        var boundary = "logent_google_" + java.util.UUID.randomUUID().toString().replace("-", "")
        while (content.contains(boundary)) {
            boundary = "logent_google_" + java.util.UUID.randomUUID().toString().replace("-", "")
        }
        return boundary
    }

    private fun humanSize(raw: String?): String {
        val bytes = raw?.toLongOrNull() ?: return "-"
        if (bytes < 1_000) return "${bytes}B"
        if (bytes < 1_000_000) return String.format(Locale.ROOT, "%.1fKB", bytes / 1_000.0)
        if (bytes < 1_000_000_000) return String.format(Locale.ROOT, "%.1fMB", bytes / 1_000_000.0)
        return String.format(Locale.ROOT, "%.1fGB", bytes / 1_000_000_000.0)
    }

    private fun error(message: String): ToolResult = ToolResult(message, isError = true)

    private companion object {
        const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val MAX_TEXT_BYTES = 200_000
        const val SEARCH_FIELDS =
            "nextPageToken,files(id,name,mimeType,modifiedTime,size,webViewLink)"
        const val GET_FIELDS =
            "id,name,mimeType,size,modifiedTime,createdTime,webViewLink,parents,trashed,description"
    }
}
