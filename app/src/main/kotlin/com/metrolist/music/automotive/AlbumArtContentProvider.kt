/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Google 공식 UAMP 예제(AlbumArtContentProvider.kt, Apache-2.0)를 기반으로
 * Coil3 이미지 로더에 맞게 변환한 ContentProvider.
 *
 * 동작 원리:
 *  1. mapUri(웹URL) 호출 → content:// URI 생성 + (content URI ↔ 웹 URL) 매핑 저장
 *  2. 차량 미디어 시스템이 content:// URI 의 openFile() 요청
 *  3. openFile() 에서 매핑된 웹 URL 을 Coil 로 다운로드 → 캐시 파일로 저장 → 파일 디스크립터 반환
 */

package com.metrolist.music.automotive

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import androidx.core.net.toUri
import coil3.size.Size

class AlbumArtContentProvider : ContentProvider() {

    companion object {
        // content:// URI 와 원본 웹 URL 의 매핑을 저장하는 정적 맵
        private val uriMap = mutableMapOf<Uri, Uri>()
        // 고해상도 변환 URL 이 실패할 때 폴백할 원본 URL 매핑
        private val origMap = mutableMapOf<Uri, Uri>()

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /**
         * 웹 이미지 URL 을 content:// URI 로 변환하고 매핑을 저장한다.
         * 차량 시스템에는 이 content:// URI 를 넘긴다.
         */
        fun mapUri(uri: Uri): Uri {
            // 1. URL 의 사이즈 부분을 고해상도로 교체 (=w120-h120 → =w544-h544)
            // YouTube/Google CDN URL 패턴: ...=w{숫자}-h{숫자}[-기타옵션]
            val originalUrl = uri.toString()
            val hiResUrl = originalUrl
                .replace(
                    Regex("=w\\d+-h\\d+(-[^=&]*)?"),
                    "=w1080-h1080-l90"
                )
                .replace(
                    Regex("=s\\d+(-[^=&]*)?"),
                    "=s1080-l90"
                )
            val hiResUri = if (hiResUrl != originalUrl) hiResUrl.toUri() else uri
            android.util.Log.d("PTUBE_ART", "mapUri 변환: $originalUrl → $hiResUrl")

            // 2. content:// URI 생성 (기존 로직 그대로)
            val path = hiResUri.encodedPath?.substring(1)?.replace('/', ':') ?: return Uri.EMPTY
            val contentUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .path(path)
                .build()
            uriMap[contentUri] = hiResUri  // 고해상도 URL 로 매핑 저장
            origMap[contentUri] = uri      // 폴백용 원본 URL 저장
            return contentUri
        }

        /**
         * 이미지 바이트를 다운로드한다.
         * - User-Agent 지정(구글 CDN throttle/거부 완화)
         * - 리다이렉트 추종
         * openFile 은 목록의 아이템마다 호출되고 차량이 이를 기다리므로, 여기서 오래 블로킹하면
         * 로딩 전체가 느려진다. 그래서 재시도 없이 빠른 타임아웃으로 1회만 시도하고 실패 시 null.
         * (간헐 실패 대응은 재시도가 아니라 UA·리다이렉트·상위에서의 원본 URL 폴백으로 처리)
         */
        private fun downloadBytes(urlStr: String, attempts: Int = 1): ByteArray? {
            repeat(attempts) { i ->
                try {
                    var current = urlStr
                    var redirects = 0
                    while (redirects < 5) {
                        val conn = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 6_000
                            readTimeout = 6_000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", UA)
                            setRequestProperty("Accept", "image/*,*/*")
                        }
                        val code = conn.responseCode
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (loc.isNullOrBlank()) break
                            current = if (loc.startsWith("http")) loc
                            else java.net.URL(java.net.URL(current), loc).toString()
                            redirects++
                            continue
                        }
                        if (code !in 200..299) {
                            conn.disconnect()
                            android.util.Log.w("PTUBE_ART", "HTTP $code for $current")
                            break
                        }
                        val bytes = conn.inputStream.use { it.readBytes() }
                        conn.disconnect()
                        if (bytes.isNotEmpty()) return bytes
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PTUBE_ART", "download 실패(시도 ${i + 1}/$attempts): ${e.message}")
                }
                if (i < attempts - 1) Thread.sleep(300L)
            }
            return null
        }

        // ARTIST 카드 등 정사각형으로 꽉 채워야 하는 경우용.
        // 표식(crop=1)을 매핑에 저장해서 openFile 에서 letterbox 대신 center-crop 한다.
        private val cropSet = mutableSetOf<Uri>()

        fun mapUriCrop(uri: Uri): Uri {
            val contentUri = mapUri(uri)
            cropSet.add(contentUri)
            return contentUri
        }

        // AndroidManifest.xml 의 provider authorities 와 정확히 일치해야 함
        private const val AUTHORITY = "com.metrolist.music.debug.albumart"
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = this.context ?: return null
        // content:// URI 로부터 원본 웹 URL 을 찾는다
        val remoteUri = uriMap[uri] ?: throw FileNotFoundException(uri.path)
        android.util.Log.d("PTUBE_ART", "openFile: requested uri=$uri, mapped=${uriMap[uri]}")

        // 캐시 파일 경로 (uri.path 기반, '/' 는 '_' 로 치환해 파일명 안전화)
        val safeName = (uri.path ?: "art").replace('/', '_').replace(':', '_')
        val file = File(context.cacheDir, safeName)

        if (!file.exists()) {
            try {
                // 1. 견고한 다운로드: 고해상도 URL 재시도 → 실패 시 원본 URL 폴백.
                //    (차량 재생 화면 앨범아트가 간헐적으로 실패하던 문제 대응)
                var bytes = downloadBytes(remoteUri.toString())
                if (bytes == null) {
                    val fallback = origMap[uri]?.toString()
                    if (fallback != null && fallback != remoteUri.toString()) {
                        android.util.Log.d("PTUBE_ART", "고해상도 실패 → 원본 URL 폴백: $fallback")
                        bytes = downloadBytes(fallback)
                    }
                }
                if (bytes == null) throw FileNotFoundException("Download failed after retries: $remoteUri")

                // 2. 비트맵으로 디코딩
                val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw FileNotFoundException("Decode failed: $remoteUri")

                // 3. crop 대상이면 center-crop (정사각형 꽉 채움), 아니면 letterbox (검은 배경)
                val finalBitmap = if (cropSet.contains(uri)) {
                    centerCrop(decoded)
                } else {
                    letterbox(decoded)
                }

                // 4. PNG 무손실 저장
                FileOutputStream(file).use { out ->
                    finalBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                android.util.Log.d("PTUBE_ART", "Saved: ${finalBitmap.width}x${finalBitmap.height} (orig ${decoded.width}x${decoded.height}) for $remoteUri")
            } catch (e: Exception) {
                android.util.Log.e("PTUBE_ART", "Failed: $remoteUri", e)
                if (file.exists()) file.delete()
                throw FileNotFoundException("Failed to download art: $remoteUri - ${e.message}")
            }
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * 작은 변 기준 정사각형으로 가운데를 잘라낸다 (center-crop).
     * 직사각형 이미지를 정사각형 슬롯에 꽉 채울 때 사용 (ARTIST 카드 등).
     */
    private fun centerCrop(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        if (w == h) return src
        return try {
            val s = minOf(w, h)  // 작은 변 기준
            val x = (w - s) / 2
            val y = (h - s) / 2
            android.graphics.Bitmap.createBitmap(src, x, y, s, s)
        } catch (e: Throwable) {
            src
        }
    }

    /**
     * 가로·세로가 다르면 큰 변 기준 정사각형 캔버스를 만들고 검은색으로 채운 뒤
     * 원본을 가운데 배치(letterbox). 이미 정사각형이면 원본 그대로 반환.
     */
    private fun letterbox(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        if (w == h) return src  // 이미 1:1 → 그대로
        return try {
            val s = maxOf(w, h)  // 큰 변 기준 정사각형
            val out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(0xFF000000.toInt())  // 검은색 100% (반투명 금지)
            c.drawBitmap(src, (s - w) / 2f, (s - h) / 2f, null)  // 가운데
            out
        } catch (e: Throwable) {
            src  // 실패 시 원본 폴백
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun getType(uri: Uri): String? = null
}
