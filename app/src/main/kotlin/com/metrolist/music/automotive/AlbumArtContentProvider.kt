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
                    "=w1080-h1080-l90-rj"
                )
                .replace(
                    Regex("=s\\d+(-[^=&]*)?"),
                    "=s1080-l90-rj"
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
            // Coil 로 이미지 다운로드 (동기). 차량은 로딩 UI 를 보여주며 기다린다.
            val request = ImageRequest.Builder(context)
                .data(remoteUri.toString())
                .size(Size.ORIGINAL)
                .build()
            val result = runBlocking { context.imageLoader.execute(request) }
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                }
            } else {
                throw FileNotFoundException("Failed to download art: $remoteUri")
            }
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
