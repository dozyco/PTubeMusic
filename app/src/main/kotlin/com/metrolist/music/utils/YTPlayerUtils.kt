/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.cipher.FunctionNameExtractor
import com.metrolist.music.utils.cipher.PlayerJsFetcher
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,  // Try embedded player first for age-restricted content
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        IOS,
        WEB,
        WEB_CREATOR
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        // googlevideo는 URL을 발급한 클라이언트와 다른 User-Agent로 요청하면 403을 줄 수 있어
        // 스트림 URL을 얻은 클라이언트의 UA를 재생 요청까지 전달한다.
        val streamUserAgent: String? = null,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        Timber.tag(TAG).d("Content type detection (preliminary):")
        Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(TAG).d("Authentication status: ${if (isLoggedIn) "LOGGED_IN" else "ANONYMOUS"}")

        // Get signature timestamp (same as before for normal content)
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")

        // Generate PoToken
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        // Try WEB_REMIX with signature timestamp and poToken (same as before)
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        var mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrThrow()

        // Debug uploaded track response
        if (isUploadedTrack || playlistId?.contains("MLPT") == true) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainPlayerResponse.videoDetails?.title}, videoId=${mainPlayerResponse.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainPlayerResponse.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainPlayerResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // Age-restricted: use WEB_CREATOR directly (no NewPipe needed from here)
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // If we still don't have a valid response, throw

        var audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamUserAgent: String? = null
        var streamPlayerResponse: PlayerResponse? = null
        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Timber.tag(TAG)
                .i("Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            isAgeRestricted -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamUserAgent = null
            var streamAlreadyValidated = false

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // Skip NewPipe for age-restricted content (NewPipe doesn't use our auth)
                val responseToUse = if (wasOriginallyAgeRestricted) {
                    Timber.tag(logTag).d("Skipping NewPipe for age-restricted content")
                    streamPlayerResponse
                } else {
                    // Try to get streams using newPipePlayer method
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                if (audioConfig == null) {
                    audioConfig = responseToUse.playerConfig?.audioConfig

                    if (audioConfig != null) {
                        Timber.tag(logTag).d("AudioConfig obtained from response of client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    } else {
                        Timber.tag(logTag).d("No audioConfig found in responseToUse.")
                    }
                }

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                // 일반 경로(cipher/NewPipe 해독)를 먼저 시도한다. NewPipe 추출기가 최신이면
                // WEB_REMIX의 고음질(itag 774) URL을 여기서 바로 얻는다.
                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)

                if ((audioQuality == AudioQuality.HIGH || audioQuality == AudioQuality.VERY_HIGH)
                    && clientIndex == -1   // MAIN_CLIENT(WEB_REMIX) 시도일 때만
                ) {
                    val isHighFormat = format.itag == 774 || format.audioQuality == "AUDIO_QUALITY_HIGH"
                    // 일반 경로가 실패했거나 선택된 포맷이 고음질이 아닐 때만 yt-dlp 폴백.
                    if (streamUrl == null || !isHighFormat) {
                        Timber.tag(TAG).d("yt-dlp 고음질 폴백 시도 (현재 itag=${format.itag}, streamUrl=${if (streamUrl == null) "null" else "있음"})")
                        // 1순위: YtdlpDroid(QuickJS, 고속) 시도
                        val ytdlpDroidResult = YtdlpDroidExtractor.getAudioStream(videoId)
                        var ytdlpUrl = ytdlpDroidResult?.url
                        // 2순위: 실패하면 기존 yt-dlp(Python, 느림)
                        if (ytdlpUrl == null) {
                            ytdlpUrl = YtdlpStreamExtractor.getStreamUrl(videoId, itag = 774)
                        }
                        // yt-dlp가 가져온 포맷 확인 — 이미 확보한 스트림보다 나을 때만 교체.
                        // (yt-dlp가 774 대신 251 같은 저음질을 주면 기존 스트림을 유지한다.)
                        val matchedFormat = ytdlpDroidResult?.itag?.let { droidItag ->
                            responseToUse.streamingData?.adaptiveFormats
                                ?.firstOrNull { it.itag == droidItag }
                        }
                        val ytdlpIsBetter = streamUrl == null ||
                            (matchedFormat != null && matchedFormat.bitrate > format.bitrate)
                        if (ytdlpUrl != null && ytdlpIsBetter) {
                            if (matchedFormat != null) {
                                format = matchedFormat
                            }
                            // yt-dlp URL은 이미 완성형 → n-transform/poToken/validate 건너뛰고 바로 사용.
                            Timber.tag(TAG).i("yt-dlp 폴백 URL 채택: videoId=$videoId, itag=${format.itag}")
                            streamUrl = ytdlpUrl
                            streamUserAgent = ytdlpDroidResult?.userAgent
                            streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds ?: 21540
                            break
                        } else if (ytdlpUrl != null) {
                            Timber.tag(TAG).d("yt-dlp 결과(itag=${ytdlpDroidResult?.itag})가 기존 스트림(itag=${format.itag})보다 낫지 않음 → 기존 스트림 유지")
                        } else {
                            Timber.tag(TAG).d("yt-dlp 폴백 실패 → 기존 흐름 계속")
                        }
                    }
                }

                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }
                streamUserAgent = currentClient.userAgent

                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                Timber.tag(TAG).d("=== N-TRANSFORM DECISION ===")
                Timber.tag(TAG).d("Content type analysis:")
                Timber.tag(TAG).d("  musicVideoType: $musicVideoType")
                Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")
                Timber.tag(TAG).d("  wasOriginallyAgeRestricted: $wasOriginallyAgeRestricted")
                Timber.tag(TAG).d("Client analysis:")
                Timber.tag(TAG).d("  currentClient: ${currentClient.clientName}")
                Timber.tag(TAG).d("  useWebPoTokens: ${currentClient.useWebPoTokens}")

                // Apply n-transform and PoToken for web clients (WEB, WEB_REMIX, WEB_CREATOR, TVHTML5)
                val needsNTransform = currentClient.useWebPoTokens ||
                        currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")

                Timber.tag(TAG).d("N-transform decision:")
                Timber.tag(TAG).d("  needsNTransform: $needsNTransform")
                Timber.tag(TAG).d("  Reason: useWebPoTokens=${currentClient.useWebPoTokens}, " +
                        "clientInList=${currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")}")

                if (needsNTransform) {
                    // 로그인 + sts + PoToken으로 받은 WEB_REMIX URL은 대개 그대로 유효하며,
                    // 잘못 계산된 n-변환이 오히려 403을 유발한다(2026-07 실측).
                    // 원본 URL이 이미 유효하면 n-변환을 건너뛴다.
                    if (validateStatus(streamUrl, currentClient.userAgent)) {
                        Timber.tag(TAG).d("Original URL already valid — skipping n-transform")
                        streamAlreadyValidated = true
                    } else try {
                        Timber.tag(TAG).d("Applying n-transform to stream URL...")
                        Timber.tag(TAG).d("  Original URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  Original URL preview: ${streamUrl.take(100)}...")

                        val originalUrl = streamUrl
                        // NewPipe 추출기의 n-변환을 우선 사용 (player.js 갱신에 가장 잘 대응),
                        // 실패 시 자체 WebView 방식(CipherDeobfuscator)으로 폴백.
                        val newPipeTransformed = NewPipeExtractor.getUrlWithNTransformed(videoId, streamUrl)
                        streamUrl = if (newPipeTransformed != null && newPipeTransformed != originalUrl) {
                            Timber.tag(TAG).d("N-transform applied via NewPipe")
                            newPipeTransformed
                        } else {
                            CipherDeobfuscator.transformNParamInUrl(streamUrl)
                        }

                        Timber.tag(TAG).d("  Transformed URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  URL changed: ${originalUrl != streamUrl}")

                        // Append pot= parameter with streaming data poToken
                        val needsPoToken = currentClient.useWebPoTokens && poToken?.streamingDataPoToken != null
                        Timber.tag(TAG).d("PoToken decision:")
                        Timber.tag(TAG).d("  needsPoToken: $needsPoToken")
                        Timber.tag(TAG).d("  hasStreamingDataPoToken: ${poToken?.streamingDataPoToken != null}")

                        if (needsPoToken) {
                            Timber.tag(TAG).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in streamUrl) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                            Timber.tag(TAG).d("  Final URL length (with pot): ${streamUrl.length}")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")
                        Timber.tag(TAG).e("Stack trace: ${e.stackTraceToString().take(500)}")
                        // Continue with original URL
                    }
                } else {
                    Timber.tag(TAG).d("Skipping n-transform (not required for this client/content)")
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    /** skip [validateStatus] for last client */
                    Timber.tag(logTag).d("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    Timber.tag(TAG)
                        .i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                }

                // n-변환 스킵 판정에서 이미 검증됐으면 같은 URL을 다시 HEAD로 검증하지 않는다 (~1.5초 절약)
                if (streamAlreadyValidated || validateStatus(streamUrl, currentClient.userAgent)) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    // Log for release builds
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: All clients failed for uploaded track videoId=$videoId")
            }
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            // YouTube often surfaces generic reasons (e.g. "error 2000") for restricted or
            // unavailable streams; Metrolist cannot recover those without official playback.
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: Playability not OK for uploaded track - status=${streamPlayerResponse.playabilityStatus.status}, reason=$errorReason")
            }
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl.take(100)}...")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            streamUserAgent,
        )
    }.onFailure { e ->
        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }
    /**
     * Player response intended for metadata / playback-tracking retrieval.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val isLoggedIn = YouTube.cookie != null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        var poToken: PoTokenResult? = null
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
            } catch (_: Exception) { }
        }
        return YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp.timestamp, poToken?.playerRequestPoToken)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata player response") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata player response") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats ?: return null

        val audioCapableFormats = adaptiveFormats.filter { it.isAudio }
        if (audioCapableFormats.isEmpty()) return null

        val maxBitrate = audioCapableFormats.maxOfOrNull { it.bitrate } ?: return null

        fun scoreCodec(mimeType: String): Int = when {
            mimeType.contains("opus", ignoreCase = true) -> 2
            mimeType.contains("mp4a", ignoreCase = true) -> 1
            else -> 0
        }

        val format = when (audioQuality) {
            AudioQuality.VERY_HIGH -> {
                audioCapableFormats.maxWithOrNull(
                    compareBy<PlayerResponse.StreamingData.Format> { it.bitrate }
                        .thenBy { format ->
                            when (format.audioQuality) {
                                "AUDIO_QUALITY_HIGH" -> 3
                                "AUDIO_QUALITY_MEDIUM" -> 2
                                "AUDIO_QUALITY_LOW" -> 1
                                else -> 0
                            }
                        }
                        .thenBy { it.audioChannels ?: 2 }
                        .thenBy { scoreCodec(it.mimeType) }
                )
            }

            AudioQuality.HIGH -> {
                audioCapableFormats.maxWithOrNull(
                    compareBy<PlayerResponse.StreamingData.Format> { format ->
                        when (format.audioQuality) {
                            "AUDIO_QUALITY_HIGH" -> 3
                            "AUDIO_QUALITY_MEDIUM" -> 2
                            "AUDIO_QUALITY_LOW" -> 1
                            else -> 0
                        }
                    }.thenBy { it.audioChannels ?: 2 }
                        .thenBy { scoreCodec(it.mimeType) }
                        .thenBy { it.bitrate }
                )
            }

            AudioQuality.LOW -> {
                val cappedFormats = audioCapableFormats.filter { it.bitrate <= 128000 }
                val lowFormat = cappedFormats
                    .filter { it.isOriginal }
                    .maxByOrNull { it.bitrate }
                    ?: cappedFormats.maxByOrNull { it.bitrate }
                    ?: audioCapableFormats
                        .filter { it.isOriginal }
                        .minByOrNull { kotlin.math.abs(it.bitrate.toDouble() - 128000.0) }
                    ?: audioCapableFormats.maxByOrNull { it.bitrate }

                if (lowFormat != null) {
                    Timber.tag(logTag).d("Selected LOW format: itag=${lowFormat.itag}, bitrate: ${lowFormat.bitrate}")
                }

                lowFormat
            }

            AudioQuality.AUTO -> {
                val targetBitrate = if (connectivityManager.isActiveNetworkMetered) 128000.0 else maxBitrate.toDouble()
                val cappedFormats = audioCapableFormats.filter { it.bitrate <= targetBitrate }
                val autoFormat = cappedFormats
                    .filter { it.isOriginal }
                    .maxByOrNull { it.bitrate }
                    ?: cappedFormats.maxByOrNull { it.bitrate }
                    ?: audioCapableFormats
                        .filter { it.isOriginal }
                        .minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                    ?: audioCapableFormats.maxByOrNull { it.bitrate }

                if (autoFormat != null) {
                    Timber.tag(logTag).d("Selected AUTO format: itag=${autoFormat.itag}, bitrate: ${autoFormat.bitrate}")
                }

                autoFormat
            }
        }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: itag=${format.itag}, mimeType=${format.mimeType}, bitrate=${format.bitrate}, audioQuality label: ${format.audioQuality}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, userAgent: String? = null): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)

            // googlevideo는 URL 발급 클라이언트와 UA가 다르면 403을 줄 수 있으므로
            // 해당 클라이언트의 UA로 검증한다.
            userAgent?.let { requestBuilder.header("User-Agent", it) }

            // Add authentication cookie for privately owned tracks
            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
                println("[PLAYBACK_DEBUG] Added cookie to validation request")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val isSuccessful = response.isSuccessful
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private suspend fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained via NewPipe: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp via NewPipe")
                    reportException(error)
                }
                // Fallback: extract signatureTimestamp directly from player.js when NewPipe fails.
                // This keeps playback working when the NewPipe extractor is outdated for a new
                // player version, as long as the player.js still embeds signatureTimestamp inline.
                val fallbackSts = runCatching {
                    Timber.tag(logTag).d("Trying player.js fallback for signature timestamp")
                    val (playerJs, hash) = PlayerJsFetcher.getPlayerJs()
                        ?: error("PlayerJsFetcher returned null")
                    Timber.tag(logTag).d("Got player.js (hash=$hash), extracting signatureTimestamp")
                    FunctionNameExtractor.extractSignatureTimestamp(playerJs)
                        ?: error("extractSignatureTimestamp returned null for hash=$hash")
                }.onSuccess { sts ->
                    Timber.tag(logTag).d("Signature timestamp obtained via player.js fallback: $sts")
                }.onFailure { e ->
                    Timber.tag(logTag).e(e, "player.js fallback for signature timestamp also failed")
                }.getOrNull()
                SignatureTimestampResult(fallbackSts, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // NewPipe 서명 해독을 먼저 시도 — 최신 추출기가 새 player.js에 가장 잘 대응하고,
        // 자체 WebView 방식(CipherDeobfuscator)은 실패 시 매번 ~1초를 낭비하므로 폴백으로 내린다.
        // 인증이 필요 없고 player.js의 cipher 알고리즘만 적용하므로 비공개 트랙에도 안전하다.
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("NewPipe failed; trying custom cipher deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Skip StreamInfo fallback for age-restricted or private content
        // (StreamInfo fetch may fail without auth for these)
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping StreamInfo fallback for age-restricted/private content")
            return null
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }

    /**
     * 첫 곡 재생 지연을 줄이기 위한 워밍업.
     * PoToken 생성용 WebView(BotGuard)와 스트리밍 토큰을 미리 초기화해 두면
     * 첫 곡의 PoToken 생성이 ~2.5초 → 수백 ms 로 줄어든다.
     * 서비스 시작 시 백그라운드에서 한 번 호출하면 된다. 실패해도 무해하다.
     */
    fun prewarmPoToken() {
        runCatching {
            val sessionId = if (YouTube.cookie != null) YouTube.dataSyncId else YouTube.visitorData
            if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
                Timber.tag(logTag).d("Prewarming PoToken generator (sessionId 준비됨)")
                poTokenGenerator.getWebClientPoToken("dQw4w9WgXcQ", sessionId)
                Timber.tag(TAG).i("PoToken prewarm complete")
            } else {
                Timber.tag(logTag).d("PoToken prewarm skipped (no sessionId yet)")
            }
        }.onFailure {
            Timber.tag(logTag).d("PoToken prewarm failed (무해): ${it.message}")
        }
    }
}
