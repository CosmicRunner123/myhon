package eu.kanade.tachiyomi.data.coil

import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ComicThumbnailFetcherTest {

    @Test
    fun `pages are sampled down as far as keeps them at least the thumbnail width`() {
        thumbnailSampleSize(300, 450) shouldBe 1
        thumbnailSampleSize(THUMBNAIL_WIDTH * 2 - 1, 1000) shouldBe 1
        thumbnailSampleSize(THUMBNAIL_WIDTH * 2, 1000) shouldBe 2
        thumbnailSampleSize(2400, 3500) shouldBe 4
        thumbnailSampleSize(6000, 9000) shouldBe 16
    }

    @Test
    fun `long strips are sampled down further, to stay within the pixel limit`() {
        val sampleSize = thumbnailSampleSize(690, 30000)
        sampleSize shouldBe 4
        (690L / sampleSize) * (30000 / sampleSize) shouldBeLessThanOrEqual MAX_DECODED_PIXELS
    }
}
