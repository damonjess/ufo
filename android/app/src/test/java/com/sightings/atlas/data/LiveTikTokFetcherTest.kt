package com.sightings.atlas.data

import com.sightings.atlas.model.Sighting
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveTikTokFetcherTest {

    @Test
    fun testLiveClipFetchAddsWorldwidePinnedClips() {
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
            override fun getApplicationContext(): android.content.Context {
                return this
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

        // Wait up to 15 seconds for live background discovery
        val completed = latch.await(15, TimeUnit.SECONDS)

        println("Live fetch completed: $completed")
            println("Discovered live worldwide clips: ${discoveredClips.size}")
            for (c in discoveredClips.take(5)) {
                println("  -> [${c.video?.id}] ${c.city} (${c.lat}, ${c.lon}): ${c.summary}")
                assertTrue("Live clips must have finite coordinates", c.lat.isFinite() && c.lon.isFinite())
                assertTrue("Latitude must be worldwide-safe", c.lat in -90.0..90.0)
                assertTrue("Longitude must be worldwide-safe", c.lon in -180.0..180.0)
            }

            assertTrue("Should discover live worldwide clips", discoveredClips.isNotEmpty())

        // Clean up
        File("build/tmp/testContext/live_social_cache.json").delete()
    }
}
