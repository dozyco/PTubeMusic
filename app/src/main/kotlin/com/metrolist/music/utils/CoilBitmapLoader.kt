/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import timber.log.Timber

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap = createBitmap(64, 64)

    private fun Bitmap.createIndependentCopy(): Bitmap {
        if (isRecycled) return createFallbackBitmap()
        return try {
            val copy = createBitmap(width, height)
            val canvas = android.graphics.Canvas(copy)
            canvas.drawBitmap(this, 0f, 0f, null)
            copy
        } catch (e: Exception) {
            Timber.tag("CoilBitmapLoader").w(e, "Failed to create independent copy")
            createFallbackBitmap()
        }
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                bitmap?.createIndependentCopy() ?: createFallbackBitmap()
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to decode bitmap data")
                createFallbackBitmap()
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            // 앨범아트 로딩은 일시적 네트워크 오류로 실패하는 경우가 있는데, 즉시 빈 이미지로
            // 폴백하면 차량 재생 화면에 앨범아트가 비어 보인다. 짧은 백오프로 몇 번 재시도해
            // 일시적 실패를 걸러낸 뒤에만 폴백한다.
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(uri)
                            .allowHardware(false)
                            .build()

                    when (val result = context.imageLoader.execute(request)) {
                        is SuccessResult -> {
                            try {
                                return@future result.image.toBitmap().createIndependentCopy()
                            } catch (e: Exception) {
                                Timber.tag("CoilBitmapLoader").w(e, "Failed to convert image to bitmap")
                                return@future createFallbackBitmap()
                            }
                        }

                        is ErrorResult -> {
                            // 마지막 시도가 아니면 잠깐 쉬었다가 재시도 (지수 백오프)
                            if (attempt < MAX_ATTEMPTS - 1) {
                                Timber.tag("CoilBitmapLoader")
                                    .d("Artwork load failed (attempt ${attempt + 1}/$MAX_ATTEMPTS), retrying: $uri")
                                delay(RETRY_BASE_DELAY_MS * (attempt + 1))
                            } else {
                                Timber.tag("CoilBitmapLoader")
                                    .w(result.throwable, "Artwork load failed after $MAX_ATTEMPTS attempts: $uri")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("CoilBitmapLoader").w(e, "Failed to load bitmap from uri (attempt ${attempt + 1})")
                    if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_BASE_DELAY_MS * (attempt + 1))
                }
            }
            createFallbackBitmap()
        }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 400L
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        val artworkUri = metadata.artworkUri ?: metadata.extras?.getString("artwork_uri")?.toUri() ?: return null
        return loadBitmap(artworkUri)
    }
}
