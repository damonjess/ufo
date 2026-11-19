package com.sightings.atlas.data

import com.sightings.atlas.model.Sighting
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveTikTokFetcherTest {

    @Test
    fun testDeepScanPullsHundredsOfTikTokAndYouTubeVideos() {
        val dir = File("build/tmp/testContext").apply { mkdirs() }
        File(dir, "live_dual_uk_cache_v4.json").delete()

        val latch = CountDownLatch(1)
        var discoveredClips = emptyList<Sighting>()
        var batchCount = 0

        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = dir
            override fun getApplicationContext(): android.content.Context = this
        }

        LiveTikTokFetcher.fetchUkClipsAsync(
            context = mockContext,
            existingIds = emptySet(),
            force = true,
            onBatch = { batch ->
                if (batch.isNotEmpty()) batchCount++
            },
            onNewClips = { clips ->
                discoveredClips = clips
                latch.countDown()
            },
        )

        val completed = latch.await(45, TimeUnit.SECONDS)
        val tiktoks = discoveredClips.filter { it.video?.isTikTok == true }
        val youtubes = discoveredClips.filter { it.video != null && !it.video.isTikTok }

        println("Deep scan completed=$completed batches=$batchCount total=${discoveredClips.size} tiktoks=${tiktoks.size} youtube=${youtubes.size}")
        for (c in tiktoks.take(3)) {
            println("  TK -> [${c.video?.id}] ${c.country} @ ${c.city} (${c.lat}, ${c.lon}) [${c.date}]: ${c.summary}")
        }
        for (c in youtubes.take(3)) {
            println("  YT -> [${c.video?.id}] ${c.country} @ ${c.city} (${c.lat}, ${c.lon}) [${c.date}]: ${c.summary}")
        }

        assertTrue("Deep scan should finish within timeout", completed)
        assertTrue("Should discover at least 100 TikTok clips (got ${tiktoks.size})", tiktoks.size >= 100)
        assertTrue("Should discover at least 100 YouTube videos (got ${youtubes.size})", youtubes.size >= 100)
        assertTrue("Should discover 200+ combined clips (got ${discoveredClips.size})", discoveredClips.size >= 200)
        assertTrue("Should stream multiple batches progressively (got $batchCount)", batchCount >= 3)

        val uniqueIds = discoveredClips.mapNotNull { it.video?.id }.toSet()
        assertTrue("All discovered video IDs must be unique", uniqueIds.size == discoveredClips.size)

        for (c in discoveredClips) {
            assertTrue("Clip must have finite coordinates", c.lat.isFinite() && c.lon.isFinite())
            assertTrue("Latitude must be valid", c.lat in -90.0..90.0)
            assertTrue("Longitude must be valid", c.lon in -180.0..180.0)
        }

        File(dir, "live_dual_uk_cache_v4.json").delete()
    }
}
