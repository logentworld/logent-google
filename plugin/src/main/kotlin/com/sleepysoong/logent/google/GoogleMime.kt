package com.sleepysoong.logent.google

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Google API 요청에 쓰는 순수 인코딩·검증 함수들이에요.
 *
 * 네트워크·Android 프레임워크와 분리되어 있어 JVM 테스트에서 그대로 검증할 수 있어요.
 */
internal object GoogleMime {
    private const val HEX = "0123456789ABCDEF"
    private const val MAX_HEADER_LENGTH = 998
    private const val ENCODED_WORD_BYTES = 45

    private val RFC3339 = Regex(
        """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$""",
    )
    private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val MIME_TYPE = Regex("""^[A-Za-z0-9!#&^_.+-]+/[A-Za-z0-9!#&^_.+-]+$""")

    /** 헤더에 그대로 넣어도 되는 단순 MIME 타입인지 확인해요. */
    fun isValidMimeType(value: String): Boolean = MIME_TYPE.matches(value.trim())

    /** Drive·Gmail에서 텍스트로 다룰 수 있는 MIME인지 판단해요. */
    fun isTextMime(mimeType: String): Boolean {
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.startsWith("text/")) return true
        if (mime.endsWith("+json") || mime.endsWith("+xml")) return true
        return mime in setOf(
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/javascript",
            "application/x-javascript",
            "application/x-ndjson",
            "application/yaml",
            "application/x-yaml",
            "application/toml",
            "application/sql",
            "application/graphql",
            "application/csv",
        )
    }

    /** RFC 3986 형식으로 쿼리·폼 값을 인코딩해요. 공백은 `%20`이에요. */
    fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            ) {
                append(char)
            } else {
                append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
    }

    /** `application/x-www-form-urlencoded` 본문을 만들어요. */
    fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${percentEncode(key)}=${percentEncode(value)}" }

    fun base64UrlEncode(text: String): String = base64UrlEncode(text.toByteArray(Charsets.UTF_8))

    fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(text: String): ByteArray {
        val cleaned = text.filterNot { it.isWhitespace() }
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return Base64.getUrlDecoder().decode(padded)
    }

    fun base64UrlDecodeToString(text: String): String =
        String(base64UrlDecode(text), Charsets.UTF_8)

    /** Drive 쿼리 문법에서 작은따옴표와 역슬래시를 이스케이프해요. */
    fun escapeDriveQueryValue(value: String): String = buildString {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                else -> append(char)
            }
        }
    }

    /**
     * Drive `uploadType=multipart` 본문을 만들어요.
     *
     * 호스트 HTTP가 PATCH를 허용하지 않으므로 파일 생성은 multipart POST 한 번으로 끝내요.
     */
    fun buildDriveMultipart(
        boundary: String,
        metadataJson: String,
        content: String,
        mimeType: String,
    ): String {
        require(boundary.isNotBlank() && boundary.none { it == '\r' || it == '\n' || it == '"' }) {
            "multipart 경계 값이 올바르지 않아요."
        }
        require(isValidMimeType(mimeType)) { "multipart 본문 MIME 타입이 올바르지 않아요." }
        return buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(mimeType).append("\r\n\r\n")
            append(content).append("\r\n")
            append("--").append(boundary).append("--")
        }
    }

    /** 사용자가 준 검색어를 Drive `fullText` 쿼리로 감싸요. */
    fun driveSearchQuery(term: String): String =
        "fullText contains '${escapeDriveQueryValue(term)}'"

    /** RFC 3339 날짜·시간을 Instant로 파싱해요. 형식이 틀리면 null이에요. */
    fun rfc3339Instant(value: String): Instant? {
        val trimmed = value.trim()
        if (!RFC3339.matches(trimmed)) return null
        return runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
    }

    fun isValidRfc3339(value: String): Boolean = rfc3339Instant(value) != null

    /** Photos API 날짜 필터에 쓰는 `yyyy-MM-dd`를 구성 요소로 나눠요. */
    fun photoDate(value: String): JsonObject? {
        val trimmed = value.trim()
        if (!DATE_ONLY.matches(trimmed)) return null
        val date = runCatching { LocalDate.parse(trimmed) }.getOrNull() ?: return null
        return buildJsonObject {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
        }
    }

    /** 캘린더 일정 생성 본문을 만들어요. 시간대는 기기 기본 시간대를 그대로 써요. */
    fun calendarEventBody(
        summary: String,
        start: String,
        end: String,
        timeZone: String,
        description: String? = null,
        location: String? = null,
    ): JsonObject = buildJsonObject {
        put("summary", summary)
        putJsonObject("start") {
            put("dateTime", start)
            put("timeZone", timeZone)
        }
        putJsonObject("end") {
            put("dateTime", end)
            put("timeZone", timeZone)
        }
        if (!description.isNullOrBlank()) put("description", description)
        if (!location.isNullOrBlank()) put("location", location)
    }

    /**
     * RFC 2822 MIME 본문을 만들어요. 본문은 UTF-8 base64로 인코딩해요.
     * 헤더에 줄바꿈을 넣으려는 입력은 거부해요.
     */
    fun buildMimeMessage(to: String, subject: String, body: String): String {
        val toHeader = formatAddressHeader(to)
        val subjectHeader = encodeHeaderValue(subject)
        val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray(Charsets.US_ASCII))
            .encodeToString(body.toByteArray(Charsets.UTF_8))
        return buildString {
            append("To: ").append(toHeader).append("\r\n")
            append("Subject: ").append(subjectHeader).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encodedBody)
            append("\r\n")
        }
    }

    /** Gmail `messages.send`·`drafts.create`에 넣는 base64url `raw` 값을 만들어요. */
    fun buildGmailRaw(to: String, subject: String, body: String): String =
        base64UrlEncode(buildMimeMessage(to, subject, body))

    /** 비ASCII 제목을 RFC 2047 encoded-word로 바꿔요. */
    fun encodeHeaderValue(value: String): String {
        val sanitized = sanitizeHeader(value, "제목")
        if (sanitized.all { it.code in 32..126 }) return sanitized
        val chunks = splitUtf8(sanitized, ENCODED_WORD_BYTES)
        return chunks.joinToString(" ") { chunk ->
            "=?UTF-8?B?${Base64.getEncoder().encodeToString(chunk.toByteArray(Charsets.UTF_8))}?="
        }
    }

    /**
     * Gmail payload에서 text/plain을 우선하고, 없으면 text/html을 디코드해요.
     * [maxChars]를 넘으면 잘라내고 `truncated`를 표시해요.
     */
    fun decodeGmailBody(payload: JsonObject, maxChars: Int = 20_000): DecodedBody {
        val plain = StringBuilder()
        val html = StringBuilder()

        fun walk(part: JsonObject) {
            val mime = part.str("mimeType").orEmpty().lowercase(Locale.ROOT)
            val body = part["body"] as? JsonObject
            val isAttachment = !part.str("filename").isNullOrBlank() || body?.str("attachmentId") != null
            val data = body?.str("data")
            if (!isAttachment && !data.isNullOrBlank()) {
                val decoded = runCatching { base64UrlDecodeToString(data) }.getOrNull()
                if (!decoded.isNullOrEmpty()) {
                    when {
                        mime.startsWith("text/plain") -> plain.append(decoded)
                        mime.startsWith("text/html") -> html.append(decoded)
                    }
                }
            }
            (part["parts"] as? JsonArray)?.forEach { child ->
                (child as? JsonObject)?.let(::walk)
            }
        }

        walk(payload)
        val useHtml = plain.isBlank() && html.isNotBlank()
        val text = if (useHtml) html.toString() else plain.toString()
        val truncated = text.length > maxChars
        return DecodedBody(
            text = if (truncated) text.take(maxChars) else text,
            isHtml = useHtml,
            truncated = truncated,
        )
    }

    /** 메일 payload에서 첨부파일 이름을 모아요. */
    fun collectAttachmentNames(payload: JsonObject): List<String> {
        val names = mutableListOf<String>()
        fun walk(part: JsonObject) {
            part.str("filename")?.takeIf { it.isNotBlank() }?.let { names += it }
            (part["parts"] as? JsonArray)?.forEach { child ->
                (child as? JsonObject)?.let(::walk)
            }
        }
        walk(payload)
        return names.distinct()
    }

    private fun formatAddressHeader(value: String): String {
        val sanitized = sanitizeHeader(value, "받는사람")
        require(sanitized.isNotEmpty()) { "받는사람을 입력해 주세요." }
        val open = sanitized.lastIndexOf('<')
        val close = sanitized.lastIndexOf('>')
        if (open >= 0 && close > open) {
            val display = sanitized.substring(0, open).trim().removeSurrounding("\"")
            val address = sanitized.substring(open + 1, close).trim()
            validateAddress(address)
            if (display.isEmpty()) return address
            val encodedDisplay = if (display.all { it.code in 32..126 }) {
                "\"${display.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            } else {
                encodeHeaderValue(display)
            }
            return "$encodedDisplay <$address>"
        }
        validateAddress(sanitized)
        return sanitized
    }

    private fun validateAddress(address: String) {
        val valid = address.length in 3..320 &&
            address.count { it == '@' } == 1 &&
            address.none { it.isWhitespace() || it == '<' || it == '>' }
        require(valid) { "받는사람 이메일 주소가 올바르지 않아요." }
    }

    private fun sanitizeHeader(value: String, field: String): String {
        require(value.none { it == '\r' || it == '\n' }) { "${field} 헤더에 줄바꿈을 넣을 수 없어요." }
        val trimmed = value.trim()
        require(trimmed.length <= MAX_HEADER_LENGTH) { "${field} 헤더가 너무 길어요." }
        return trimmed
    }

    private fun splitUtf8(value: String, maxBytes: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        for (char in value) {
            val charBytes = char.toString().toByteArray(Charsets.UTF_8).size
            if (currentBytes + charBytes > maxBytes && current.isNotEmpty()) {
                chunks += current.toString()
                current.setLength(0)
                currentBytes = 0
            }
            current.append(char)
            currentBytes += charBytes
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}

internal data class DecodedBody(
    val text: String,
    val isHtml: Boolean,
    val truncated: Boolean,
)

/** 응답 JSON에서 문자열을 안전하게 읽어요. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
