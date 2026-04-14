package com.eaglepoint.libops.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.InputStream
import kotlin.math.max

/**
 * Off-main-thread image decoding with bounded LRU caching (§18).
 *
 * - Two-pass decode: first `inJustDecodeBounds=true` to read dimensions,
 *   then `inSampleSize` chosen so the decoded bitmap fits the request box.
 * - LRU cache sized to 1/8 of available heap, capped at 20 MB so the full
 *   app stays under the prompt's image memory budget.
 */
object ImageDecoder {

    // 1/8th of heap, capped at 20MB (§18 NFR).
    private val cache: LruCache<String, Bitmap> = run {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = minOf(maxMemoryKb / 8, 20 * 1024)
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun decodeStream(
        key: String,
        reqWidthPx: Int,
        reqHeightPx: Int,
        streamFactory: () -> InputStream,
    ): Bitmap? {
        cache.get(key)?.let { return it }

        // Pass 1: bounds only
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        streamFactory().use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val sample = computeSampleSize(
            srcWidth = boundsOpts.outWidth,
            srcHeight = boundsOpts.outHeight,
            reqWidth = reqWidthPx,
            reqHeight = reqHeightPx,
        )

        // Pass 2: actual decode, downsampled
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565  // half the bytes of ARGB_8888
        }
        val bitmap = streamFactory().use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    /**
     * Returns the largest power-of-two [inSampleSize] such that the decoded
     * bitmap still covers the requested box. Exposed for testing.
     */
    fun computeSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        var sample = 1
        val halfW = srcWidth / 2
        val halfH = srcHeight / 2
        while ((halfW / sample) >= reqWidth && (halfH / sample) >= reqHeight) {
            sample *= 2
        }
        return max(1, sample)
    }

    fun approximateMemoryBytes(): Long = cache.snapshot().values.sumOf { it.byteCount.toLong() }

    fun clear() = cache.evictAll()
}
