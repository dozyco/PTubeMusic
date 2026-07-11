package com.metrolist.music.utils

import android.content.Context
import com.ytdlpdroid.YTDLPDroid
import com.ytdlpdroid.model.ExtractionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * HLahwani yt-dlp-android(QuickJS) 기반 고속 스트림 추출기.
 * IOS 클라이언트로 itag 251(OPUS ~160kbps)을 cipher 없이 ~1초에 추출한다.
 */
object YtdlpDroidExtractor {
    private const val TAG = "YtdlpDroidExtractor"

    // 추출 결과: itag + 재생 url + 그 url에 써야 할 User-Agent
    data class Result(val itag: Int, val url: String, val userAgent: String)

    @Volatile
    private var instance: YTDLPDroid? = null

    fun init(context: Context) {
        if (instance == null) {
            val cacheDir = File(context.cacheDir, "ytdlpdroid_cache").apply { mkdirs() }
            instance = YTDLPDroid.Builder(cacheDir).build()
        }
    }

    suspend fun getAudioStream(videoId: String): Result? = withContext(Dispatchers.IO) {
        val ytdlp = instance ?: return@withContext null
        try {
            val result = ytdlp.extract(
                "https://music.youtube.com/watch?v=$videoId",
                ExtractionOptions(
                    maxVideoHeight = null,
                    preferH264 = false,
                    preferOpusAudio = true,
                    includeMuxedFallback = false,
                    regionCode = null,
                ),
            )
            val audio = result.audioStream
            val hasRange = audio.url.contains("range=")
        val hasN = audio.url.contains("&n=") || audio.url.contains("?n=")
        val clen = Regex("[?&]clen=([^&]+)").find(audio.url)?.groupValues?.get(1)
        Timber.tag(TAG).d("추출 성공: itag=${audio.itag}, ${audio.bitrate}bps, ${audio.codec}")
        Timber.tag(TAG).d("url분석: range포함=$hasRange, n포함=$hasN, clen=$clen, UA=${result.streamUserAgent}")
            Result(itag = audio.itag, url = audio.url, userAgent = result.streamUserAgent)
        } catch (e: Exception) {
            Timber.tag(TAG).w("추출 실패: ${e.message}")
            null
        }
    }
}