package com.sightings.atlas.data

import com.sightings.atlas.model.Sighting
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveTikTokFetcherTest {

    @Test
    fun testLiveTikTokFetchUkClipsAsync() {
        // Clean up test cache file if exists
        File("live_social_cache.json").delete()

        val latch = CountDownLatch(1)
        var discoveredClips = emptyList<Sighting>()

        // Mock context using reflection/proxy or null filesDir handling
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File {
                val dir = File("build/tmp/testContext")
                dir.mkdirs()
                return dir
            }
        }

        LiveTikTokFetcher.fetchUkClipsAsync(
            context = mockContext,
            existingIds = emptySet(),
            force = true,
            onNewClips = { clips ->
                discoveredClips = clips
                latch.countDown()
            }
        )

        // Wait up to 10 seconds for live background discovery
        val completed = latch.await(10, TimeUnit.SECONDS)

        println("Live fetch completed: $completed")
        println("Discovered live UK TikTok clips: ${discoveredClips.size}")
        for (c in discoveredClips.take(5)) {
            println("  -> [${c.video?.id}] ${c.city} (${c.lat}, ${c.lon}): ${c.summary}")
            assertTrue("City should be in UK or United Kingdom", c.region == "United Kingdom" || c.city.contains("UK") || c.city.contains("United Kingdom") || LiveTikTokFetcher.isInUkRegion(c.lat, c.lon))
        }

        assertTrue("Should discover live UK TikTok clips", discoveredClips.isNotEmpty())

        // Clean up
        File("build/tmp/testContext/live_social_cache.json").delete()
    }
}
