/**
 * PTubeMusic - yt-dlp 기반 고음질(itag 774) 스트림 추출기
 *
 * 기존 cipher 경로가 6월 player의 서명을 못 풀어 HIGH(774)를 못 가져오는 문제를
 * yt-dlp(외부 JS런타임 내장)로 우회한다. PTubeMusic이 이미 보유한 쿠키를 그대로 사용한다.
 * 추출 결과 URL은 만료 전까지 캐싱하고, 진행 중인 추출은 공유하여 중복 추출을 막는다.
 */
package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.YouTube
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object YtdlpStreamExtractor {
    private const val TAG = "YtdlpStreamExtractor"

    // 완료된 URL 캐시 (videoId+itag -> (url, 만료시각ms))
    private data class CacheEntry(val url: String, val expiresAtMs: Long)
    private val cache = HashMap<String, CacheEntry>()
    private val cacheMutex = Mutex()
    // googlevideo URL은 보통 6시간 유효. 안전하게 5시간만 캐싱.
    private const val CACHE_TTL_MS = 5 * 60 * 60 * 1000L

    // 진행 중인 추출 작업 공유 (videoId+itag -> Deferred<URL?>)
    // 같은 곡을 동시에 두 번 추출하는 것을 막는다.
    private val inFlight = HashMap<String, Deferred<String?>>()
    private val inFlightMutex = Mutex()
    private val extractorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Timber.tag(TAG).d("YtdlpStreamExtractor 초기화 완료")
    }

    suspend fun getStreamUrl(
        videoId: String,
        itag: Int? = null,
    ): String? {
        val context = appContext
        if (context == null) {
            Timber.tag(TAG).w("appContext 없음 - init()이 안 불림")
            return null
        }

        val cacheKey = "$videoId:${itag ?: "best"}"

        // 1) 완료된 캐시 확인
        cacheMutex.withLock {
            val cached = cache[cacheKey]
            if (cached != null && System.currentTimeMillis() < cached.expiresAtMs) {
                Timber.tag(TAG).d("캐시 HIT: $cacheKey (재추출 안 함)")
                return cached.url
            }
        }

        // 2) 진행 중인 추출이 있으면 그걸 기다림 (중복 추출 방지)
        val deferred: Deferred<String?>
        inFlightMutex.withLock {
            val existing = inFlight[cacheKey]
            if (existing != null && existing.isActive) {
                Timber.tag(TAG).d("진행 중인 추출 공유: $cacheKey (새로 추출 안 함)")
                deferred = existing
            } else {
                Timber.tag(TAG).d("새 추출 작업 시작: $cacheKey")
                deferred = extractorScope.async {
                    doExtract(context, videoId, itag, cacheKey)
                }
                inFlight[cacheKey] = deferred
            }
        }

        return try {
            deferred.await()
        } finally {
            // 완료된 작업은 in-flight 목록에서 제거
            inFlightMutex.withLock {
                if (inFlight[cacheKey] === deferred) {
                    inFlight.remove(cacheKey)
                }
            }
        }
    }

    private suspend fun doExtract(
        context: Context,
        videoId: String,
        itag: Int?,
        cacheKey: String,
    ): String? = withContext(Dispatchers.IO) {
        // yt-dlp 캐시 디렉토리 (player.js/서명 해독 결과 저장 → 재사용)
        val cacheDirPath = File(context.cacheDir, "ytdlp_cache").apply { mkdirs() }.absolutePath

        try {
            val cookieFile = writeCookieFile(context)

            val req = YoutubeDLRequest("https://music.youtube.com/watch?v=$videoId")
            if (cookieFile != null) {
                req.addOption("--cookies", cookieFile.absolutePath)
            }
            req.addOption("--extractor-args", "youtube:player_client=web_music")
            val formatStr = if (itag != null) "$itag/bestaudio" else "bestaudio"
            req.addOption("-f", formatStr)
            req.addOption("--no-playlist")
            req.addOption("--no-check-formats")
            req.addOption("--cache-dir", cacheDirPath)
            req.addOption("--no-write-comments")
            req.addOption("--extractor-args", "youtube:skip=hls,dash,translated_subs")
            req.addOption("-v")

            Timber.tag(TAG).d("yt-dlp 추출 시작: videoId=$videoId, itag=$itag")

            val info = YoutubeDL.getInstance().getInfo(req)
            val url = info.url

            if (url.isNullOrEmpty()) {
                Timber.tag(TAG).w("yt-dlp가 URL을 못 가져옴 (formatId=${info.formatId})")
                null
            } else {
                Timber.tag(TAG).d("yt-dlp 추출 성공: formatId=${info.formatId}, ext=${info.ext}")
                cacheMutex.withLock {
                    cache[cacheKey] = CacheEntry(url, System.currentTimeMillis() + CACHE_TTL_MS)
                }
                url
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "yt-dlp 추출 실패: ${e.message}")
            null
        }
    }

    private fun writeCookieFile(context: Context): File? {
        val cookieString = YouTube.cookie
        if (cookieString.isNullOrEmpty()) {
            Timber.tag(TAG).d("쿠키 없음 (비로그인 상태)")
            return null
        }

        return try {
            val file = File(context.filesDir, "ytdlp_cookies.txt")
            val sb = StringBuilder()
            sb.append("# Netscape HTTP Cookie File\n")
            sb.append("# Generated by PTubeMusic for yt-dlp\n\n")

            cookieString.split(";").forEach { pair ->
                val trimmed = pair.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val name = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    if (name.isNotEmpty()) {
                        sb.append(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t$name\t$value\n")
                    }
                }
            }

            file.writeText(sb.toString())
            file
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "쿠키 파일 작성 실패")
            null
        }
    }
}