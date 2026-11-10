package com.sleepysoong.logent.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleApiErrorTest {
    @Test
    fun googleApiError_extractsGoogleErrorMessage() {
        val message = googleApiError(
            404,
            """{"error":{"code":404,"message":"File not found: abc","status":"NOT_FOUND"}}""",
        )
        assertTrue(message.contains("찾지 못했어요"))
        assertTrue(message.contains("File not found: abc"))
        assertTrue(message.contains("HTTP 404"))
    }

    @Test
    fun googleApiError_extractsOAuthErrorDescription() {
        val message = googleApiError(400, """{"error":"invalid_grant","error_description":"Token expired"}""")
        assertTrue(message.contains("invalid_grant"))
        assertTrue(message.contains("Token expired"))
    }

    @Test
    fun googleApiError_mapsAuthAndRateLimitWithoutBody() {
        assertTrue(googleApiError(401, "").contains("google_auth"))
        assertTrue(googleApiError(429, "").contains("호출 한도"))
        assertTrue(googleApiError(503, "").contains("일시적으로"))
        assertEquals("Google 요청이 실패했어요 (HTTP 418)", googleApiError(418, ""))
    }

    @Test
    fun googleApiError_truncatesLongBodiesAndStripsNewlines() {
        val long = "x".repeat(1_000)
        val message = googleApiError(400, """{"error":{"message":"$long"}}""")
        assertTrue(message.length < 500)
        assertTrue(!message.contains("\n"))
    }
}
