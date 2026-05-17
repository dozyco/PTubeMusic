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

class AlbumArtContentProvider : ContentProvider() {

    companion object {
        // content:// URI 와 원본 웹 URL 의 매핑을 저장하는 정적 맵
        private val uriMap = mutableMapOf<Uri, Uri>()

        /**
         * 웹 이미지 URL 을 content:// URI 로 변환하고 매핑을 저장한다.
         * 차량 시스템에는 이 content:// URI 를 넘긴다.
         */
        fun mapUri(uri: Uri): Uri {
            // 웹 URL 경로의 '/' 를 ':' 로 바꿔 단일 path 로 만든다 (Google 공식 방식)
            val path = uri.encodedPath?.substring(1)?.replace('/', ':') ?: return Uri.EMPTY
            android.util.Log.d("PTUBE_ART", "mapUri: input=$uri, path=$path")
            val contentUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .path(path)
                .build()
            uriMap[contentUri] = uri
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
                .build()
            val result = runBlocking { context.imageLoader.execute(request) }
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
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
