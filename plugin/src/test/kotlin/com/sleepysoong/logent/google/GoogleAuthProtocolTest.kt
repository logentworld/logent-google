package com.sleepysoong.logent.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthProtocolTest {
    @Test
    fun deviceCodeRequestBody_encodesClientIdAndScopes() {
        val body = OAuthProtocol.deviceCodeRequestBody(
            clientId = "client id",
            scopes = listOf("scope.one", "scope.two"),
        )
        assertEquals(
            "client_id=client%20id&scope=scope.one%20scope.two",
            body,
        )
        assertFalse(body.contains("client_secret"))
    }

    @Test
    fun tokenPollRequestBody_usesDeviceGrantAndEncodesValues() {
        val body = OAuthProtocol.tokenPollRequestBody(
            clientId = "client id",
            clientSecret = "sec&ret",
            deviceCode = "device/code",
        )
        assertEquals(
            "client_id=client%20id&client_secret=sec%26ret&device_code=device%2Fcode" +
                "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            body,
        )
    }

    @Test
    fun refreshTokenRequestBody_usesRefreshGrant() {
        val body = OAuthProtocol.refreshTokenRequestBody("id", "secret", "1//refresh")
        assertEquals(
            "client_id=id&client_secret=secret&refresh_token=1%2F%2Frefresh&grant_type=refresh_token",
            body,
        )
    }

    @Test
    fun parseDeviceCodeResponse_readsUserFacingValues() {
        val result = OAuthProtocol.parseDeviceCodeResponse(
            200,
            """
            {
              "device_code": "secret-device-code",
              "user_code": "ABCD-EFGH",
              "verification_url": "https://www.google.com/device",
              "expires_in": 1800,
              "interval": 5
            }
            """.trimIndent(),
        )
        assertTrue(result is DeviceCodeResult.Success)
        result as DeviceCodeResult.Success
        assertEquals("ABCD-EFGH", result.userCode)
        assertEquals("https://www.google.com/device", result.verificationUrl)
        assertEquals(1_800L, result.expiresInSeconds)
        assertEquals(5, result.intervalSeconds)
        assertEquals("secret-device-code", result.deviceCode)
    }

    @Test
    fun parseDeviceCodeResponse_acceptsVerificationUriAndRejectsMissingValues() {
        val result = OAuthProtocol.parseDeviceCodeResponse(
            200,
            """{"device_code":"d","user_code":"u","verification_uri":"https://example.com/device"}""",
        )
        assertTrue(result is DeviceCodeResult.Success)
        assertEquals("https://example.com/device", (result as DeviceCodeResult.Success).verificationUrl)

        val missing = OAuthProtocol.parseDeviceCodeResponse(
            200,
            """{"device_code":"d","user_code":"u"}""",
        )
        assertTrue(missing is DeviceCodeResult.Failure)
    }

    @Test
    fun parseDeviceCodeResponse_reportsHttpError() {
        val result = OAuthProtocol.parseDeviceCodeResponse(
            400,
            """{"error":"invalid_client","error_description":"Unauthorized"}""",
        )
        assertTrue(result is DeviceCodeResult.Failure)
        result as DeviceCodeResult.Failure
        assertEquals("invalid_client", result.code)
        assertTrue(result.message.contains("Unauthorized"))
    }

    @Test
    fun parseTokenResponse_returnsSuccessValues() {
        val result = OAuthProtocol.parseTokenResponse(
            200,
            """
            {
              "access_token": "ya29.access",
              "refresh_token": "1//refresh",
              "expires_in": 3599,
              "scope": "https://www.googleapis.com/auth/drive.file other"
            }
            """.trimIndent(),
        )
        assertTrue(result is TokenResult.Success)
        result as TokenResult.Success
        assertEquals("ya29.access", result.accessToken)
        assertEquals("1//refresh", result.refreshToken)
        assertEquals(3_599L, result.expiresInSeconds)
        assertEquals("https://www.googleapis.com/auth/drive.file other", result.scope)
    }

    @Test
    fun parseTokenResponse_distinguishesPendingSlowDownExpiredAndDenied() {
        assertEquals(
            TokenResult.AuthorizationPending,
            OAuthProtocol.parseTokenResponse(428, """{"error":"authorization_pending"}"""),
        )
        val slowDown = OAuthProtocol.parseTokenResponse(429, """{"error":"slow_down"}""")
        assertTrue(slowDown is TokenResult.SlowDown)
        assertNull((slowDown as TokenResult.SlowDown).intervalSeconds)

        assertEquals(
            TokenResult.Expired,
            OAuthProtocol.parseTokenResponse(400, """{"error":"expired_token"}"""),
        )
        assertEquals(
            TokenResult.AccessDenied,
            OAuthProtocol.parseTokenResponse(400, """{"error":"access_denied"}"""),
        )
    }

    @Test
    fun parseTokenResponse_reportsUnknownErrorWithoutTokens() {
        val result = OAuthProtocol.parseTokenResponse(
            400,
            """{"error":"invalid_grant","error_description":"Bad Request"}""",
        )
        assertTrue(result is TokenResult.Failure)
        result as TokenResult.Failure
        assertEquals("invalid_grant", result.code)
        assertTrue(result.message.contains("Bad Request"))
    }

    @Test
    fun parseTokenResponse_rejectsSuccessWithoutAccessToken() {
        val result = OAuthProtocol.parseTokenResponse(200, """{"refresh_token":"1//r"}""")
        assertTrue(result is TokenResult.Failure)
        assertEquals("invalid_response", (result as TokenResult.Failure).code)
    }

    @Test
    fun nextPollInterval_addsFiveSecondsAndHonorsSlowDown() {
        assertEquals(10, OAuthProtocol.nextPollInterval(5))
        assertEquals(12, OAuthProtocol.nextPollInterval(5, 12))
        assertEquals(11, OAuthProtocol.nextPollInterval(10, 1))
    }
}
