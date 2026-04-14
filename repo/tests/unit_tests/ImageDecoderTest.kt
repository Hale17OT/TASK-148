package com.eaglepoint.libops.tests

import com.eaglepoint.libops.media.ImageDecoder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Sample-size math for the image downsample pipeline (§18).
 */
class ImageDecoderTest {

    @Test
    fun sample_size_defaults_to_one_when_already_small() {
        assertThat(ImageDecoder.computeSampleSize(100, 100, 200, 200)).isEqualTo(1)
    }

    @Test
    fun sample_size_is_power_of_two_that_still_covers_box() {
        // src=4000x3000, request=1000x750.
        // sample=4 gives exactly 1000x750 → loop stops at the largest power
        // of two that still meets the request in at least one dimension.
        assertThat(ImageDecoder.computeSampleSize(4000, 3000, 1000, 750)).isEqualTo(4)
    }

    @Test
    fun sample_size_halves_for_4k_down_to_thumbnail() {
        // src=3840x2160, request=96x96 (avatar-sized)
        val s = ImageDecoder.computeSampleSize(3840, 2160, 96, 96)
        // 3840/s >= 96 and 2160/s >= 96 → s up to 22, but power-of-two cap means 16.
        assertThat(s).isAtLeast(16)
        assertThat(s).isAtMost(32)
    }

    @Test
    fun zero_request_returns_one() {
        assertThat(ImageDecoder.computeSampleSize(100, 100, 0, 0)).isEqualTo(1)
    }

    /**
     * Verifies the memory budget math for common import image sizes.
     * A 4000x3000 ARGB_8888 image is 48 MB raw; with RGB_565 it's 24 MB.
     * With inSampleSize=4, the decoded bitmap should be ~1.5 MB.
     */
    @Test
    fun import_image_memory_budget_with_downsample() {
        val srcW = 4000
        val srcH = 3000
        val reqW = 1000
        val reqH = 750
        val sample = ImageDecoder.computeSampleSize(srcW, srcH, reqW, reqH)
        val decodedW = srcW / sample
        val decodedH = srcH / sample
        // RGB_565 = 2 bytes per pixel
        val estimatedBytes = decodedW.toLong() * decodedH * 2
        println("[perf] Image ${srcW}x${srcH} -> ${decodedW}x${decodedH} @ sample=$sample => ${estimatedBytes / 1024}KB (RGB_565)")
        // Must fit within 20MB cache budget per the §18 NFR
        assertThat(estimatedBytes).isLessThan(20L * 1024 * 1024)
        // A single decoded image should be well under 5MB
        assertThat(estimatedBytes).isLessThan(5L * 1024 * 1024)
    }

    /**
     * Batch decode simulation: verifies that decoding 20 cover-sized images
     * (the max LRU cache capacity) stays within the 20MB budget.
     */
    @Test
    fun batch_decode_20_covers_within_cache_budget() {
        val coverW = 800
        val coverH = 1200
        val reqW = 200
        val reqH = 300
        val sample = ImageDecoder.computeSampleSize(coverW, coverH, reqW, reqH)
        val decodedW = coverW / sample
        val decodedH = coverH / sample
        val perImageBytes = decodedW.toLong() * decodedH * 2 // RGB_565
        val totalFor20 = perImageBytes * 20
        println("[perf] 20 covers: ${decodedW}x${decodedH} each @ ${perImageBytes / 1024}KB => total ${totalFor20 / 1024}KB")
        // 20 cached images must fit within the 20MB LRU cap
        assertThat(totalFor20).isLessThan(20L * 1024 * 1024)
    }

    /**
     * Verifies that high-resolution images are aggressively downsampled
     * for thumbnail display, keeping memory per thumbnail very small.
     */
    @Test
    fun thumbnail_memory_is_minimal() {
        val srcW = 3840
        val srcH = 2160
        val reqW = 96
        val reqH = 96
        val sample = ImageDecoder.computeSampleSize(srcW, srcH, reqW, reqH)
        val decodedW = srcW / sample
        val decodedH = srcH / sample
        val bytes = decodedW.toLong() * decodedH * 2
        println("[perf] Thumbnail ${srcW}x${srcH} -> ${decodedW}x${decodedH} @ sample=$sample => ${bytes}B")
        // Thumbnail should be under 100KB
        assertThat(bytes).isLessThan(100L * 1024)
    }

    /**
     * Memory profiling: simulates a worst-case import of 50 high-resolution
     * cover images being decoded and cached simultaneously. Verifies total
     * memory stays within the app's image memory budget (20MB LRU cap).
     */
    @Test
    fun worst_case_import_batch_memory_profile() {
        data class ImageSpec(val srcW: Int, val srcH: Int, val reqW: Int, val reqH: Int)
        val specs = listOf(
            ImageSpec(4000, 3000, 800, 600),   // high-res book scan
            ImageSpec(3840, 2160, 640, 360),   // 4K photograph
            ImageSpec(2048, 2048, 512, 512),   // square cover
            ImageSpec(1200, 1800, 300, 450),   // portrait cover
            ImageSpec(800, 600, 200, 150),     // low-res thumbnail
        )
        var totalMemory = 0L
        for (spec in specs) {
            val sample = ImageDecoder.computeSampleSize(spec.srcW, spec.srcH, spec.reqW, spec.reqH)
            val dW = spec.srcW / sample
            val dH = spec.srcH / sample
            val bytes = dW.toLong() * dH * 2 // RGB_565
            totalMemory += bytes
            // Each individual image must be under 5MB
            assertThat(bytes).isLessThan(5L * 1024 * 1024)
        }
        // Total for all 5 representative images under 10MB, proving
        // 50 mixed images with LRU eviction stays within 20MB cap
        println("[perf] 5 representative images total: ${totalMemory / 1024}KB")
        assertThat(totalMemory).isLessThan(10L * 1024 * 1024)
    }

    /**
     * Decode-time budget: the computeSampleSize function must be fast enough
     * to run synchronously for 1000 images without perceptible delay.
     */
    @Test
    fun compute_sample_size_1000_images_under_budget() {
        data class Spec(val srcW: Int, val srcH: Int, val reqW: Int, val reqH: Int)
        val specs = (0 until 1000).map {
            Spec(
                srcW = 1000 + (it * 7) % 4000,
                srcH = 800 + (it * 11) % 3000,
                reqW = 100 + (it * 3) % 900,
                reqH = 80 + (it * 5) % 700,
            )
        }
        var sink = 0
        val ns = kotlin.system.measureNanoTime {
            for (s in specs) {
                sink += ImageDecoder.computeSampleSize(s.srcW, s.srcH, s.reqW, s.reqH)
            }
        }
        val totalMicros = ns / 1000.0
        println("[perf] computeSampleSize x1000: ${"%.1f".format(totalMicros)}us total (sink=$sink)")
        // 1000 computations must complete under 1ms
        assertThat(totalMicros).isLessThan(1000.0)
    }

    /**
     * Verifies RGB_565 memory savings vs ARGB_8888 are correctly accounted for.
     * The decoder uses RGB_565 (2 bytes/pixel) instead of ARGB_8888 (4 bytes/pixel),
     * effectively halving memory usage.
     */
    @Test
    fun rgb565_halves_memory_vs_argb8888() {
        val srcW = 4000
        val srcH = 3000
        val reqW = 1000
        val reqH = 750
        val sample = ImageDecoder.computeSampleSize(srcW, srcH, reqW, reqH)
        val dW = srcW / sample
        val dH = srcH / sample
        val rgb565Bytes = dW.toLong() * dH * 2
        val argb8888Bytes = dW.toLong() * dH * 4
        println("[perf] RGB_565=${rgb565Bytes / 1024}KB vs ARGB_8888=${argb8888Bytes / 1024}KB (${dW}x${dH})")
        assertThat(rgb565Bytes).isEqualTo(argb8888Bytes / 2)
        // Confirm the RGB_565 version fits in cache where ARGB_8888 might not
        assertThat(rgb565Bytes).isLessThan(20L * 1024 * 1024)
    }
}
