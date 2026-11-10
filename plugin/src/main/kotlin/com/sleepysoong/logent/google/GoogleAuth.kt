package com.sleepysoong.logent.google

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Google OAuth 설정 키와 엔드포인트예요. */
internal object GoogleAuthKeys {
    const val CLIENT_ID = "client-id"
    const val CLIENT_SECRET = "client-secret"
    const val REFRESH_TOKEN = "refresh-token"
    const val GRANTED_SCOPE = "granted-scope"

    const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
}

/** Google Workspace 도구에 필요한 최소 OAuth 범위예요. */
internal object GoogleScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
    const val CALENDAR = "https://www.googleapis.com/auth/calendar"
    const val PHOTOS_READONLY = "https://www.googleapis.com/auth/photoslibrary.readonly"

    val all: List<String> = listOf(DRIVE_FILE, GMAIL_MODIFY, CALENDAR, PHOTOS_READONLY)
}

/** RFC 8628 기기 승인 응답이에요. device_code는 사용자에게 돌려주지 않아요. */
internal sealed interface DeviceCodeResult {
    data class Success(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Int,
    ) : DeviceCodeResult

    data class Failure(val code: String, val message: String) : DeviceCodeResult
}

/** 토큰 엔드포인트 응답이에요. 성공이 아니면 access token을 담지 않아요. */
internal sealed interface TokenResult {
    data class Success(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
        val scope: String?,
    ) : TokenResult

    data object AuthorizationPending : TokenResult
    data class SlowDown(val intervalSeconds: Int?) : TokenResult
    data object Expired : TokenResult
    data object AccessDenied : TokenResult
    data class Failure(val code: String, val message: String) : TokenResult
}

/** OAuth 요청 본문과 응답 해석만 담당하는 순수 프로토콜이에요. */
internal object OAuthProtocol {
    fun deviceCodeRequestBody(clientId: String, scopes: List<String>): String =
        GoogleMime.formBody(
            "client_id" to clientId,
            "scope" to scopes.joinToString(" "),
        )

    fun tokenPollRequestBody(clientId: String, clientSecret: String, deviceCode: String): String =
        GoogleMime.formBody(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "device_code" to deviceCode,
            "grant_type" to GoogleAuthKeys.DEVICE_GRANT_TYPE,
        )

    fun refreshTokenRequestBody(clientId: String, clientSecret: String, refreshToken: String): String =
        GoogleMime.formBody(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        )

    fun parseDeviceCodeResponse(status: Int, body: String): DeviceCodeResult {
        val root = parseObject(body)
        if (status !in 200..299) {
            val code = root?.str("error").orEmpty()
            val description = root?.str("error_description").orEmpty()
            val message = when {
                description.isNotBlank() -> if (code.isBlank()) description else "$code: $description"
                else -> googleApiError(status, body)
            }
            return DeviceCodeResult.Failure(code.ifBlank { "http_$status" }, message)
        }
        if (root == null) {
            return DeviceCodeResult.Failure("invalid_response", "Google 기기 코드 응답을 해석하지 못했어요.")
        }
        val deviceCode = root.str("device_code")
        val userCode = root.str("user_code")
        val verificationUrl = root.str("verification_url")
            ?: root.str("verification_uri")
            ?: root.str("verification_uri_complete")
        if (deviceCode.isNullOrBlank() || userCode.isNullOrBlank() || verificationUrl.isNullOrBlank()) {
            return DeviceCodeResult.Failure(
                "invalid_response",
                "Google 기기 코드 응답에 device_code·user_code·verification_url이 없어요.",
            )
        }
        return DeviceCodeResult.Success(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            expiresInSeconds = root.longValue("expires_in") ?: 1_800L,
            intervalSeconds = (root.longValue("interval") ?: 5L).toInt().coerceAtLeast(1),
        )
    }

    fun parseTokenResponse(status: Int, body: String): TokenResult {
        val root = parseObject(body)
        val error = root?.str("error")
        if (status in 200..299 && error.isNullOrBlank()) {
            val accessToken = root?.str("access_token")
            if (accessToken.isNullOrBlank()) {
                return TokenResult.Failure("invalid_response", "Google 토큰 응답에 access_token이 없어요.")
            }
            return TokenResult.Success(
                accessToken = accessToken,
                refreshToken = root.str("refresh_token"),
                expiresInSeconds = root.longValue("expires_in") ?: 3_600L,
                scope = root.str("scope"),
            )
        }
        return when (error) {
            "authorization_pending" -> TokenResult.AuthorizationPending
            "slow_down" -> TokenResult.SlowDown(root.longValue("interval")?.toInt())
            "expired_token" -> TokenResult.Expired
            "access_denied" -> TokenResult.AccessDenied
            else -> {
                val code = error ?: "http_$status"
                val description = root?.str("error_description").orEmpty()
                val message = when {
                    description.isNotBlank() -> if (error.isNullOrBlank()) description else "$error: $description"
                    else -> googleApiError(status, body)
                }
                TokenResult.Failure(code, message)
            }
        }
    }

    /** slow_down을 받으면 Google 권장대로 간격을 5초 늘려요. */
    fun nextPollInterval(currentSeconds: Int, slowDownInterval: Int? = null): Int =
        (slowDownInterval ?: (currentSeconds + 5)).coerceAtLeast(currentSeconds + 1)

    private fun parseObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.longValue(key: String): Long? =
        (this[key]?.jsonPrimitive?.contentOrNull)?.toLongOrNull()
}

/** 사용자가 승인을 마치기 전까지 저장하는 기기 코드 상태예요. */
internal data class PendingDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Int,
    val nextPollAtMillis: Long,
) {
    fun withNextPoll(nowMillis: Long, interval: Int = intervalSeconds): PendingDeviceCode =
        copy(
            intervalSeconds = interval,
            nextPollAtMillis = nowMillis + interval * 1_000L,
        )
}

/**
 * device_code는 `host.dataDir`의 소유자 전용(0600) 파일에만 저장해요.
 * 로그·도구 결과·설정 화면에는 노출하지 않아요.
 */
internal class PendingDeviceCodeStore(private val host: PluginHost) {
    private val target: File?
        get() = host.dataDir.takeIf { it.isNotBlank() }?.let { File(it, FILE_NAME) }

    fun save(state: PendingDeviceCode): Result<Unit> {
        val file = target ?: return Result.failure(
            IllegalStateException(
                "플러그인 데이터 디렉터리를 사용할 수 없어 기기 코드를 저장하지 못했어요. 호스트가 dataDir을 제공하지 않았어요.",
            ),
        )
        return runCatching {
            val parent = file.parentFile
            parent?.mkdirs()
            val temporary = File(parent, "${file.name}.tmp")
            temporary.delete()
            temporary.createNewFile()
            restrict(temporary)
            val properties = Properties().apply {
                setProperty("device_code", state.deviceCode)
                setProperty("user_code", state.userCode)
                setProperty("verification_url", state.verificationUrl)
                setProperty("expires_at", state.expiresAtMillis.toString())
                setProperty("interval", state.intervalSeconds.toString())
                setProperty("next_poll_at", state.nextPollAtMillis.toString())
            }
            FileOutputStream(temporary).use { properties.store(it, null) }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            restrict(file)
        }
    }

    fun load(): Result<PendingDeviceCode?> {
        val file = target ?: return Result.success(null)
        if (!file.isFile) return Result.success(null)
        return runCatching {
            val properties = Properties()
            FileInputStream(file).use { properties.load(it) }
            val deviceCode = properties.getProperty("device_code") ?: return@runCatching null
            val userCode = properties.getProperty("user_code") ?: return@runCatching null
            val verificationUrl = properties.getProperty("verification_url") ?: return@runCatching null
            PendingDeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUrl = verificationUrl,
                expiresAtMillis = properties.getProperty("expires_at")?.toLongOrNull() ?: 0L,
                intervalSeconds = properties.getProperty("interval")?.toIntOrNull() ?: 5,
                nextPollAtMillis = properties.getProperty("next_poll_at")?.toLongOrNull() ?: 0L,
            )
        }
    }

    fun clear() {
        val file = target ?: return
        file.delete()
        file.parentFile?.let { File(it, "${file.name}.tmp").delete() }
    }

    private fun restrict(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    private companion object {
        const val FILE_NAME = "google-device-auth.properties"
    }
}

/** start 결과로 사용자에게 보여 줄 값이에요. device_code는 없어요. */
internal data class DeviceAuthorization(
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Int,
)

internal sealed interface PollOutcome {
    data class Connected(val scope: String?) : PollOutcome
    data class Waiting(val seconds: Int) : PollOutcome
    data class SlowDown(val intervalSeconds: Int) : PollOutcome
    data object Expired : PollOutcome
    data object Denied : PollOutcome
}

internal data class AuthStatus(
    val connected: Boolean,
    val clientConfigured: Boolean,
    val scopes: String,
)

/**
 * Google OAuth 2.0 Device Authorization Grant와 액세스 토큰 수명을 관리해요.
 *
 * 액세스 토큰은 이 객체의 메모리에만 두고, 갱신 토큰은 SECRET 설정에만 저장해요.
 */
internal class GoogleAuth {
    private data class CachedAccess(val value: String, val expiresAtMillis: Long)

    @Volatile
    private var cachedAccess: CachedAccess? = null

    fun clearMemory() {
        cachedAccess = null
    }

    fun isConnected(host: PluginHost): Boolean = refreshToken(host) != null

    fun status(host: PluginHost): AuthStatus = AuthStatus(
        connected = refreshToken(host) != null,
        clientConfigured = clientId(host) != null && clientSecret(host) != null,
        scopes = host.setting(GoogleAuthKeys.GRANTED_SCOPE).orEmpty().trim(),
    )

    suspend fun beginDeviceAuthorization(host: PluginHost): Result<DeviceAuthorization> {
        val clientId = clientId(host) ?: return Result.failure(
            IllegalStateException(
                "OAuth 클라이언트 ID가 설정되지 않았어요. Google Cloud Console에서 OAuth 클라이언트를 만들고 설정에 입력해 주세요.",
            ),
        )
        if (clientSecret(host) == null) {
            return Result.failure(
                IllegalStateException(
                    "OAuth 클라이언트 보안 비밀이 설정되지 않았어요. Google Cloud Console의 클라이언트 정보에서 보안 비밀을 복사해 설정에 입력해 주세요.",
                ),
            )
        }
        if (isConnected(host)) {
            return Result.failure(
                IllegalStateException(
                    "이미 Google 계정이 연결되어 있어요. 다시 연결하려면 google_auth logout을 먼저 실행해 주세요.",
                ),
            )
        }

        val request = PluginHttpRequest(
            method = "POST",
            url = GoogleAuthKeys.DEVICE_CODE_ENDPOINT,
            headers = mapOf(
                "Content-Type" to GoogleAuthKeys.FORM_CONTENT_TYPE,
                "Accept" to "application/json",
            ),
            body = OAuthProtocol.deviceCodeRequestBody(clientId, GoogleScopes.all),
            maxResponseBytes = 64 * 1024,
        )
        val response = host.http(request).getOrElse { error ->
            return Result.failure(
                IllegalStateException("Google 기기 코드를 요청하지 못했어요: ${error.message.orEmpty()}"),
            )
        }
        return when (val parsed = OAuthProtocol.parseDeviceCodeResponse(response.status, response.body)) {
            is DeviceCodeResult.Success -> {
                val now = System.currentTimeMillis()
                val pending = PendingDeviceCode(
                    deviceCode = parsed.deviceCode,
                    userCode = parsed.userCode,
                    verificationUrl = parsed.verificationUrl,
                    expiresAtMillis = now + parsed.expiresInSeconds * 1_000L,
                    intervalSeconds = parsed.intervalSeconds,
                    nextPollAtMillis = now + parsed.intervalSeconds * 1_000L,
                )
                PendingDeviceCodeStore(host).save(pending).getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("승인 대기 정보를 저장하지 못했어요: ${error.message.orEmpty()}"),
                    )
                }
                Result.success(
                    DeviceAuthorization(
                        userCode = parsed.userCode,
                        verificationUrl = parsed.verificationUrl,
                        expiresInSeconds = parsed.expiresInSeconds,
                        intervalSeconds = parsed.intervalSeconds,
                    ),
                )
            }

            is DeviceCodeResult.Failure -> Result.failure(IllegalStateException(parsed.message))
        }
    }

    suspend fun pollDeviceAuthorization(host: PluginHost): Result<PollOutcome> {
        val clientId = clientId(host) ?: return Result.failure(
            IllegalStateException("OAuth 클라이언트 ID가 설정되지 않았어요."),
        )
        val clientSecret = clientSecret(host) ?: return Result.failure(
            IllegalStateException("OAuth 클라이언트 보안 비밀이 설정되지 않았어요."),
        )
        val store = PendingDeviceCodeStore(host)
        val pending = store.load().getOrElse { error ->
            return Result.failure(
                IllegalStateException("승인 대기 정보를 읽지 못했어요: ${error.message.orEmpty()}"),
            )
        } ?: return Result.failure(
            IllegalStateException("진행 중인 기기 승인이 없어요. google_auth start로 먼저 시작해 주세요."),
        )

        val now = System.currentTimeMillis()
        if (now >= pending.expiresAtMillis) {
            store.clear()
            return Result.success(PollOutcome.Expired)
        }
        if (now < pending.nextPollAtMillis) {
            val remaining = ((pending.nextPollAtMillis - now + 999) / 1_000).toInt().coerceAtLeast(1)
            return Result.success(PollOutcome.Waiting(remaining))
        }

        val request = PluginHttpRequest(
            method = "POST",
            url = GoogleAuthKeys.TOKEN_ENDPOINT,
            headers = mapOf(
                "Content-Type" to GoogleAuthKeys.FORM_CONTENT_TYPE,
                "Accept" to "application/json",
            ),
            body = OAuthProtocol.tokenPollRequestBody(clientId, clientSecret, pending.deviceCode),
            maxResponseBytes = 64 * 1024,
        )
        val response = host.http(request).getOrElse { error ->
            return Result.failure(
                IllegalStateException("Google 토큰 엔드포인트에 연결하지 못했어요: ${error.message.orEmpty()}"),
            )
        }

        return when (val parsed = OAuthProtocol.parseTokenResponse(response.status, response.body)) {
            is TokenResult.Success -> {
                val refreshToken = parsed.refreshToken
                if (refreshToken.isNullOrBlank()) {
                    store.clear()
                    return Result.failure(
                        IllegalStateException(
                            "승인은 완료됐지만 갱신 토큰을 받지 못했어요. Google 계정의 앱 액세스에서 이 앱을 제거한 뒤 " +
                                "google_auth start로 다시 연결해 주세요.",
                        ),
                    )
                }
                host.writeSetting(GoogleAuthKeys.REFRESH_TOKEN, refreshToken).getOrElse { error ->
                    store.clear()
                    return Result.failure(
                        IllegalStateException("갱신 토큰을 SECRET 설정에 저장하지 못했어요: ${error.message.orEmpty()}"),
                    )
                }
                parsed.scope?.takeIf { it.isNotBlank() }?.let { scope ->
                    host.writeSetting(GoogleAuthKeys.GRANTED_SCOPE, scope)
                }
                remember(parsed.accessToken, parsed.expiresInSeconds)
                store.clear()
                Result.success(PollOutcome.Connected(parsed.scope))
            }

            TokenResult.AuthorizationPending -> {
                store.save(pending.withNextPoll(now)).getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("승인 대기 정보를 갱신하지 못했어요: ${error.message.orEmpty()}"),
                    )
                }
                Result.success(PollOutcome.Waiting(pending.intervalSeconds))
            }

            is TokenResult.SlowDown -> {
                val interval = OAuthProtocol.nextPollInterval(pending.intervalSeconds, parsed.intervalSeconds)
                store.save(pending.withNextPoll(now, interval)).getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("승인 대기 정보를 갱신하지 못했어요: ${error.message.orEmpty()}"),
                    )
                }
                Result.success(PollOutcome.SlowDown(interval))
            }

            TokenResult.Expired -> {
                store.clear()
                Result.success(PollOutcome.Expired)
            }

            TokenResult.AccessDenied -> {
                store.clear()
                Result.success(PollOutcome.Denied)
            }

            is TokenResult.Failure -> Result.failure(IllegalStateException(parsed.message))
        }
    }

    /** 메모리 캐시가 유효하면 그대로, 아니면 refresh_token으로 한 번 갱신해요. */
    suspend fun accessToken(host: PluginHost): Result<String> {
        val cached = cachedAccess
        if (cached != null && cached.expiresAtMillis > System.currentTimeMillis()) {
            return Result.success(cached.value)
        }
        return refreshAccessToken(host)
    }

    suspend fun refreshAccessToken(host: PluginHost): Result<String> {
        val clientId = clientId(host) ?: run {
            cachedAccess = null
            return Result.failure(IllegalStateException("OAuth 클라이언트 ID가 설정되지 않았어요."))
        }
        val clientSecret = clientSecret(host) ?: run {
            cachedAccess = null
            return Result.failure(IllegalStateException("OAuth 클라이언트 보안 비밀이 설정되지 않았어요."))
        }
        val refreshToken = refreshToken(host) ?: run {
            cachedAccess = null
            return Result.failure(
                IllegalStateException(
                    "Google 계정이 연결되어 있지 않아요. google_auth start로 연결해 주세요.",
                ),
            )
        }

        val request = PluginHttpRequest(
            method = "POST",
            url = GoogleAuthKeys.TOKEN_ENDPOINT,
            headers = mapOf(
                "Content-Type" to GoogleAuthKeys.FORM_CONTENT_TYPE,
                "Accept" to "application/json",
            ),
            body = OAuthProtocol.refreshTokenRequestBody(clientId, clientSecret, refreshToken),
            maxResponseBytes = 64 * 1024,
        )
        val response = host.http(request).getOrElse { error ->
            cachedAccess = null
            return Result.failure(
                IllegalStateException("Google 액세스 토큰을 갱신하지 못했어요: ${error.message.orEmpty()}"),
            )
        }
        return when (val parsed = OAuthProtocol.parseTokenResponse(response.status, response.body)) {
            is TokenResult.Success -> {
                parsed.refreshToken?.takeIf { it.isNotBlank() }?.let { renewed ->
                    host.writeSetting(GoogleAuthKeys.REFRESH_TOKEN, renewed).getOrElse { error ->
                        cachedAccess = null
                        return Result.failure(
                            IllegalStateException("새 갱신 토큰을 SECRET 설정에 저장하지 못했어요: ${error.message.orEmpty()}"),
                        )
                    }
                }
                parsed.scope?.takeIf { it.isNotBlank() }?.let { scope ->
                    host.writeSetting(GoogleAuthKeys.GRANTED_SCOPE, scope)
                }
                remember(parsed.accessToken, parsed.expiresInSeconds)
                Result.success(parsed.accessToken)
            }

            is TokenResult.Failure -> {
                cachedAccess = null
                val message = when (parsed.code) {
                    "invalid_grant" ->
                        "Google 연결이 만료됐거나 해제됐어요. google_auth start로 다시 연결해 주세요."
                    "invalid_client" ->
                        "OAuth 클라이언트 ID·보안 비밀이 올바르지 않아요. Google Cloud Console 값을 다시 확인해 주세요."
                    "unauthorized_client" ->
                        "이 OAuth 클라이언트는 기기 승인 방식을 지원하지 않아요. 클라이언트 유형을 확인해 주세요."
                    else ->
                        "Google 액세스 토큰을 갱신하지 못했어요 (${parsed.code}). google_auth start로 다시 연결해 주세요."
                }
                Result.failure(IllegalStateException(message))
            }

            else -> {
                cachedAccess = null
                Result.failure(
                    IllegalStateException(
                        "Google 토큰 갱신 응답을 이해하지 못했어요. google_auth start로 다시 연결해 주세요.",
                    ),
                )
            }
        }
    }

    fun logout(host: PluginHost): Result<Unit> {
        cachedAccess = null
        PendingDeviceCodeStore(host).clear()
        val result = host.writeSetting(GoogleAuthKeys.REFRESH_TOKEN, "")
        host.writeSetting(GoogleAuthKeys.GRANTED_SCOPE, "")
        return result
    }

    private fun remember(accessToken: String, expiresInSeconds: Long) {
        val lifetime = (expiresInSeconds * 1_000L) - 60_000L
        cachedAccess = CachedAccess(
            value = accessToken,
            expiresAtMillis = System.currentTimeMillis() + lifetime.coerceAtLeast(30_000L),
        )
    }

    private fun clientId(host: PluginHost): String? =
        host.setting(GoogleAuthKeys.CLIENT_ID)?.trim()?.takeIf { it.isNotEmpty() }

    private fun clientSecret(host: PluginHost): String? =
        host.setting(GoogleAuthKeys.CLIENT_SECRET)?.trim()?.takeIf { it.isNotEmpty() }

    private fun refreshToken(host: PluginHost): String? =
        host.setting(GoogleAuthKeys.REFRESH_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
}
