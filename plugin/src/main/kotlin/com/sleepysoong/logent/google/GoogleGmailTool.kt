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
import kotlinx.serialization.json.putJsonObject

/** `google_gmail` 도구예요. 본문은 텍스트만 디코드하고 첨부파일은 다루지 않아요. */
internal class GoogleGmailTool(private val api: GoogleApi) {
    val spec: ToolSpec = toolSpec(
        name = "google_gmail",
        description = "Gmail 메일을 검색·읽고 보내거나 초안을 만든다. " +
            "read는 payload의 text/plain을 우선하고 없으면 text/html을 디코드하며 최대 20KB만 돌려준다. " +
            "첨부파일 본문과 바이너리 다운로드는 지원하지 않는다. " +
            "send·draft는 RFC 2822 MIME을 base64url로 인코딩해 전송한다.",
        required = listOf("action"),
    ) {
        stringProperty("action", "작업이에요", listOf("search", "read", "send", "draft", "labels"))
        stringProperty("q", "search Gmail 검색어예요. 예: from:someone@example.com is:unread")
        integerProperty("max_results", "search 결과 수예요. 1~20, 기본 10.", 1, 20)
        stringProperty("message_id", "read 대상 메시지 ID예요.")
        stringProperty("to", "send·draft 받는사람이에요.")
        stringProperty("subject", "send·draft 제목이에요.")
        stringProperty("body", "send·draft 본문이에요. UTF-8 기준 최대 100KB.")
    }

    suspend fun execute(host: PluginHost, arguments: JsonObject): ToolResult =
        when (val action = arguments.text("action")?.trim()?.lowercase()) {
            "search" -> search(host, arguments)
            "read" -> read(host, arguments)
            "send" -> writeMessage(host, arguments, draft = false)
            "draft" -> writeMessage(host, arguments, draft = true)
            "labels" -> labels(host)
            null -> error("action이 필요해요. search, read, send, draft, labels 중 하나를 지정해 주세요.")
            else -> error("지원하지 않는 action이에요: $action")
        }

    private suspend fun search(host: PluginHost, arguments: JsonObject): ToolResult {
        val query = arguments.text("q")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("search에는 q가 필요해요.")
        val maxResults = (arguments.integer("max_results") ?: 10).coerceIn(1, 20)
        val url = "$MESSAGES_ENDPOINT?maxResults=$maxResults&q=" + GoogleMime.percentEncode(query)
        val root = api.getJson(host, url).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Gmail 검색 응답을 해석하지 못했어요.")
        val messages = root.arr("messages").orEmpty()
        if (messages.isEmpty()) return ToolResult("조건에 맞는 메일을 찾지 못했어요.")

        var failed = 0
        val lines = messages.mapNotNull { element ->
            val message = element as? JsonObject ?: return@mapNotNull null
            val id = message.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val threadId = message.str("threadId").orEmpty()
            val metadata = api.getJson(
                host,
                "$MESSAGES_ENDPOINT/${GoogleMime.percentEncode(id)}" +
                    "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date",
            ).getOrNull() as? JsonObject
            if (metadata == null) {
                failed += 1
                return@mapNotNull "• 메타데이터를 읽지 못한 메일이에요. id=$id thread=$threadId"
            }
            val payload = metadata.obj("payload")
            val from = header(payload, "From").orEmpty().ifBlank { "(보낸사람 미상)" }
            val subject = header(payload, "Subject").orEmpty().ifBlank { "(제목 없음)" }
            val date = header(payload, "Date").orEmpty().ifBlank { "(날짜 미상)" }
            val snippet = metadata.str("snippet").orEmpty().replace('\n', ' ').take(300)
            buildString {
                append("• ").append(date).append(" | ").append(from).append("\n")
                append("  ").append(subject).append("\n")
                append("  id: ").append(id).append("  thread: ").append(threadId).append("\n")
                if (snippet.isNotBlank()) append("  ").append(snippet)
            }.trimEnd()
        }
        if (lines.isEmpty()) {
            return error("Gmail 검색 결과는 받았지만 각 메일의 메타데이터를 읽지 못했어요.")
        }
        return ToolResult(
            buildString {
                append(lines.joinToString("\n"))
                if (failed > 0) append("\n\n메타데이터를 읽지 못한 메일: ").append(failed).append("건")
            },
        )
    }

    private suspend fun read(host: PluginHost, arguments: JsonObject): ToolResult {
        val id = arguments.text("message_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("read에는 message_id가 필요해요.")
        val root = api.getJson(
            host,
            "$MESSAGES_ENDPOINT/${GoogleMime.percentEncode(id)}?format=full",
        ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Gmail 메시지 응답을 해석하지 못했어요.")
        val payload = root.obj("payload") ?: return error("Gmail 메시지에 payload가 없어요.")
        val headers = buildString {
            append("보낸사람: ").append(header(payload, "From").orEmpty().ifBlank { "-" }).append("\n")
            append("받는사람: ").append(header(payload, "To").orEmpty().ifBlank { "-" }).append("\n")
            append("날짜: ").append(header(payload, "Date").orEmpty().ifBlank { "-" }).append("\n")
            append("제목: ").append(header(payload, "Subject").orEmpty().ifBlank { "-" })
        }
        val decoded = GoogleMime.decodeGmailBody(payload, MAX_BODY_CHARS)
        if (decoded.text.isBlank()) {
            val attachments = GoogleMime.collectAttachmentNames(payload)
            return ToolResult(
                buildString {
                    append(headers).append("\n\n본문 텍스트가 비어 있어요.")
                    if (attachments.isNotEmpty()) {
                        append("\n첨부파일: ").append(attachments.joinToString(", "))
                        append("\n첨부파일 본문과 바이너리 다운로드는 지원하지 않아요.")
                    }
                },
            )
        }
        val safeBody = decoded.text
            .replace("<untrusted_email_body>", "&lt;untrusted_email_body&gt;")
            .replace("</untrusted_email_body>", "&lt;/untrusted_email_body&gt;")
        return ToolResult(
            buildString {
                append(headers)
                append("\n\n본문").append(if (decoded.isHtml) "(HTML)" else "").append(":\n")
                append("<untrusted_email_body>\n").append(safeBody).append("\n</untrusted_email_body>")
                if (decoded.truncated) {
                    append("\n\n본문이 ").append(MAX_BODY_CHARS).append("자를 넘어 잘렸어요.")
                }
            },
        )
    }

    private suspend fun writeMessage(host: PluginHost, arguments: JsonObject, draft: Boolean): ToolResult {
        val to = arguments.text("to")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return error("${if (draft) "draft" else "send"}에는 to가 필요해요.")
        val subject = arguments.text("subject") ?: return error("${if (draft) "draft" else "send"}에는 subject가 필요해요.")
        val body = arguments.text("body") ?: return error("${if (draft) "draft" else "send"}에는 body가 필요해요.")
        if (body.toByteArray(Charsets.UTF_8).size > MAX_SEND_BYTES) {
            return error("본문이 너무 커요. UTF-8 기준 ${MAX_SEND_BYTES / 1000}KB 이하만 보낼 수 있어요.")
        }
        val raw = runCatching { GoogleMime.buildGmailRaw(to, subject, body) }.getOrElse { failure ->
            return error(failure.message.orEmpty())
        }
        val response: JsonObject? = if (draft) {
            api.postJson(
                host,
                DRAFTS_ENDPOINT,
                buildJsonObject {
                    putJsonObject("message") { put("raw", raw) }
                },
            ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
        } else {
            api.postJson(
                host,
                SEND_ENDPOINT,
                buildJsonObject { put("raw", raw) },
            ).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
        }
        if (response == null) return error("Gmail 응답을 해석하지 못했어요.")
        val messageId = response.str("id")
            ?: response.obj("message")?.str("id")
        val threadId = response.str("threadId")
            ?: response.obj("message")?.str("threadId")
        return ToolResult(
            buildString {
                append(if (draft) "메일 초안을 만들었어요." else "메일을 보냈어요.")
                messageId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
                threadId?.takeIf { it.isNotBlank() }?.let { append(" thread=").append(it) }
            },
        )
    }

    private suspend fun labels(host: PluginHost): ToolResult {
        val root = api.getJson(host, LABELS_ENDPOINT).getOrElse { return error(it.message.orEmpty()) } as? JsonObject
            ?: return error("Gmail 라벨 응답을 해석하지 못했어요.")
        val labels = root.arr("labels").orEmpty()
        if (labels.isEmpty()) return ToolResult("Gmail 라벨이 없어요.")
        val lines = labels.mapNotNull { element ->
            val label = element as? JsonObject ?: return@mapNotNull null
            val name = label.str("name").orEmpty()
            val id = label.str("id").orEmpty()
            val type = label.str("type").orEmpty()
            "- $name ($type) id=$id"
        }
        return ToolResult(lines.joinToString("\n"))
    }

    private fun header(payload: JsonObject?, name: String): String? =
        payload?.arr("headers").orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("name")?.equals(name, ignoreCase = true) == true }
            ?.str("value")

    private fun error(message: String): ToolResult = ToolResult(message, isError = true)

    private companion object {
        const val GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val MESSAGES_ENDPOINT = "$GMAIL_BASE/messages"
        const val SEND_ENDPOINT = "$GMAIL_BASE/messages/send"
        const val DRAFTS_ENDPOINT = "$GMAIL_BASE/drafts"
        const val LABELS_ENDPOINT = "$GMAIL_BASE/labels"
        const val MAX_BODY_CHARS = 20_000
        const val MAX_SEND_BYTES = 100_000
    }
}
