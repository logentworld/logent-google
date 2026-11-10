package com.sleepysoong.logent.google

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMimeTest {
    @Test
    fun base64Url_roundTripsUtf8WithoutPadding() {
        val text = """{"한글":"값"}"""
        val encoded = GoogleMime.base64UrlEncode(text)
        assertFalse(encoded.contains("="))
        assertEquals(text, GoogleMime.base64UrlDecodeToString(encoded))
        assertEquals("hello", GoogleMime.base64UrlDecodeToString("aGVsbG8"))
    }

    @Test
    fun isTextMime_acceptsTextStructuredAndStructuredSuffixes() {
        assertTrue(GoogleMime.isTextMime("text/plain"))
        assertTrue(GoogleMime.isTextMime("text/csv; charset=UTF-8"))
        assertTrue(GoogleMime.isTextMime("application/json"))
        assertTrue(GoogleMime.isTextMime("application/ld+json"))
        assertTrue(GoogleMime.isTextMime("application/xml"))
        assertTrue(GoogleMime.isTextMime("image/svg+xml"))
        assertFalse(GoogleMime.isTextMime("image/png"))
        assertFalse(GoogleMime.isTextMime("application/pdf"))
        assertFalse(GoogleMime.isTextMime("application/vnd.google-apps.document"))
    }

    @Test
    fun isValidMimeType_rejectsHeaderInjectionCharacters() {
        assertTrue(GoogleMime.isValidMimeType("text/plain"))
        assertTrue(GoogleMime.isValidMimeType("application/ld+json"))
        assertFalse(GoogleMime.isValidMimeType("text/plain\r\nX-Injected: yes"))
        assertFalse(GoogleMime.isValidMimeType("text"))
        assertFalse(GoogleMime.isValidMimeType("text/"))
    }

    @Test
    fun buildDriveMultipart_wrapsMetadataAndContent() {
        val body = GoogleMime.buildDriveMultipart(
            boundary = "BOUNDARY",
            metadataJson = """{"name":"a.txt","mimeType":"text/plain"}""",
            content = "본문",
            mimeType = "text/plain",
        )
        assertTrue(body.startsWith("--BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
        assertTrue(body.contains("""{"name":"a.txt","mimeType":"text/plain"}"""))
        assertTrue(body.contains("\r\n--BOUNDARY\r\nContent-Type: text/plain\r\n\r\n본문\r\n--BOUNDARY--"))
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMime.buildDriveMultipart("bad\r\nboundary", "{}", "x", "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMime.buildDriveMultipart("BOUNDARY", "{}", "x", "text/plain\r\nX: y")
        }
    }

    @Test
    fun escapeDriveQueryValue_escapesBackslashAndQuote() {
        assertEquals("O\\'Brien \\\\ report", GoogleMime.escapeDriveQueryValue("O'Brien \\ report"))
        assertEquals(
            "fullText contains 'O\\'Brien \\\\ report'",
            GoogleMime.driveSearchQuery("O'Brien \\ report"),
        )
    }

    @Test
    fun percentEncode_isRfc3986() {
        assertEquals("a%20b%26c%2F%ED%95%9C", GoogleMime.percentEncode("a b&c/한"))
    }

    @Test
    fun formBody_encodesKeysAndValues() {
        assertEquals(
            "client_id=client%20id&scope=scope.one%20scope.two",
            GoogleMime.formBody("client_id" to "client id", "scope" to "scope.one scope.two"),
        )
    }

    @Test
    fun rfc3339_acceptsOffsetsAndRejectsBadValues() {
        assertTrue(GoogleMime.isValidRfc3339("2026-01-01T00:00:00Z"))
        assertTrue(GoogleMime.isValidRfc3339("2026-01-01T09:00:00+09:00"))
        assertTrue(GoogleMime.isValidRfc3339("2026-01-01T09:00:00.123456+09:00"))
        assertFalse(GoogleMime.isValidRfc3339("2026-01-01"))
        assertFalse(GoogleMime.isValidRfc3339("2026-01-01T00:00:00"))
        assertFalse(GoogleMime.isValidRfc3339("2026-13-01T00:00:00Z"))
        assertFalse(GoogleMime.isValidRfc3339("tomorrow"))
    }

    @Test
    fun photoDate_rejectsImpossibleDates() {
        assertEquals(
            """{"year":2026,"month":2,"day":28}""",
            GoogleMime.photoDate("2026-02-28").toString(),
        )
        assertNull(GoogleMime.photoDate("2026-02-31"))
        assertNull(GoogleMime.photoDate("2026-2-28"))
        assertNull(GoogleMime.photoDate("점심"))
    }

    @Test
    fun calendarEventBody_usesProvidedTimeZoneAndSkipsBlankOptionals() {
        val body = GoogleMime.calendarEventBody(
            summary = "회의",
            start = "2026-01-01T09:00:00+09:00",
            end = "2026-01-01T10:00:00+09:00",
            timeZone = "Asia/Seoul",
            description = " ",
            location = "회의실",
        )
        assertEquals("회의", body["summary"].toString().trim('"'))
        assertEquals("Asia/Seoul", body["start"].toString().let { it.substringAfter("\"timeZone\":\"").substringBefore("\"") })
        assertFalse(body.containsKey("description"))
        assertTrue(body.containsKey("location"))
    }

    @Test
    fun buildMimeMessage_encodesNonAsciiHeadersAndBase64Body() {
        val mime = GoogleMime.buildMimeMessage(
            to = "홍길동 <hong@example.com>",
            subject = "안녕하세요",
            body = "본문입니다",
        )
        assertTrue(mime.startsWith("To: =?UTF-8?B?"))
        assertTrue(mime.contains("<hong@example.com>\r\n"))
        assertTrue(mime.contains("Subject: =?UTF-8?B?"))
        assertTrue(mime.contains("MIME-Version: 1.0\r\n"))
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8\r\n"))
        assertTrue(mime.contains("Content-Transfer-Encoding: base64\r\n"))
        val bodyBase64 = mime.substringAfter("\r\n\r\n").trim()
        assertEquals(
            "본문입니다",
            String(Base64.getMimeDecoder().decode(bodyBase64), Charsets.UTF_8),
        )
    }

    @Test
    fun buildGmailRaw_isBase64UrlOfRfc2822() {
        val raw = GoogleMime.buildGmailRaw("a@example.com", "제목", "본문")
        assertFalse(raw.contains("="))
        val mime = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(mime.contains("To: a@example.com\r\n"))
        assertTrue(mime.contains("Subject: =?UTF-8?B?"))
        assertTrue(mime.endsWith("\r\n"))
    }

    @Test
    fun buildMimeMessage_rejectsHeaderInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMime.buildGmailRaw("a@example.com", "제목\r\nBcc: evil@example.com", "본문")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMime.buildGmailRaw("a@example.com\r\nBcc: evil@example.com", "제목", "본문")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMime.buildGmailRaw("not-an-address", "제목", "본문")
        }
    }

    @Test
    fun decodeGmailBody_prefersPlainTextInMultipart() {
        val payload = Json.parseToJsonElement(
            """
            {
              "mimeType": "multipart/alternative",
              "parts": [
                {"mimeType": "text/plain", "body": {"data": "${GoogleMime.base64UrlEncode("일반 본문")}" }},
                {"mimeType": "text/html", "body": {"data": "${GoogleMime.base64UrlEncode("<b>HTML 본문</b>")}" }}
              ]
            }
            """.trimIndent(),
        ).jsonObject
        val decoded = GoogleMime.decodeGmailBody(payload, maxChars = 100)
        assertEquals("일반 본문", decoded.text)
        assertFalse(decoded.isHtml)
        assertFalse(decoded.truncated)
    }

    @Test
    fun decodeGmailBody_skipsAttachments() {
        val payload = Json.parseToJsonElement(
            """
            {
              "mimeType": "multipart/mixed",
              "parts": [
                {"mimeType": "text/plain", "body": {"data": "${GoogleMime.base64UrlEncode("본문")}"}},
                {"mimeType": "text/plain", "filename": "첨부.txt", "body": {"data": "${GoogleMime.base64UrlEncode("첨부 내용")}"}},
                {"mimeType": "text/plain", "body": {"attachmentId": "x", "data": "${GoogleMime.base64UrlEncode("첨부2")}"}}
              ]
            }
            """.trimIndent(),
        ).jsonObject
        val decoded = GoogleMime.decodeGmailBody(payload, maxChars = 100)
        assertEquals("본문", decoded.text)
    }

    @Test
    fun decodeGmailBody_fallsBackToHtmlAndTruncates() {
        val html = "<p>" + "가".repeat(30) + "</p>"
        val payload = Json.parseToJsonElement(
            """{"mimeType":"text/html","body":{"data":"${GoogleMime.base64UrlEncode(html)}"}}""",
        ).jsonObject
        val decoded = GoogleMime.decodeGmailBody(payload, maxChars = 10)
        assertTrue(decoded.isHtml)
        assertTrue(decoded.truncated)
        assertEquals(html.take(10), decoded.text)
    }

    @Test
    fun collectAttachmentNames_walksNestedParts() {
        val payload = Json.parseToJsonElement(
            """
            {
              "mimeType": "multipart/mixed",
              "parts": [
                {"mimeType": "text/plain", "filename": "", "body": {"data": "aGk"}},
                {"mimeType": "application/pdf", "filename": "보고서.pdf", "body": {"attachmentId": "x"}},
                {"mimeType": "multipart/related", "parts": [
                  {"mimeType": "image/png", "filename": "사진.png"}
                ]}
              ]
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(listOf("보고서.pdf", "사진.png"), GoogleMime.collectAttachmentNames(payload))
    }
}
