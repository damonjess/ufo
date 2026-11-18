package com.sightings.atlas.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Zero-asset tactical sound engine. Waveforms are synthesized once into cacheDir and
 * played through SoundPool so UI callbacks never decode audio themselves.
 */
object CyberSfx {

    private const val SAMPLE_RATE = 44100
    private const val PREFS_NAME = "atlas_prefs"
    private const val KEY_SFX_ENABLED = "sfx_enabled"
    private const val FILE_COUNT = 7

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var ready = false

    private var soundPool: SoundPool? = null
    private var sfxTick = 0
    private var sfxLock = 0
    private var sfxPing = 0
    private var sfxCipher = 0
    private var sfxSlideUp = 0
    private var sfxSlideDown = 0
    private var sfxConfirm = 0

    private var lastTickAt = 0L
    private var lastRotaryAt = 0L
    private var lastCipherAt = 0L

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_SFX_ENABLED, true)
        if (soundPool != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()

        var loadedCount = 0
        pool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                loadedCount++
                if (loadedCount >= FILE_COUNT) ready = true
            }
        }
        soundPool = pool

        Executors.newSingleThreadExecutor().execute {
            val dir = File(appCtx.cacheDir, "cyber_sfx_v1").apply { mkdirs() }
            sfxTick = pool.load(ensureWav(dir, "tick.wav") { synthTick() }, 1)
            sfxLock = pool.load(ensureWav(dir, "lock.wav") { synthTargetLock() }, 1)
            sfxPing = pool.load(ensureWav(dir, "ping.wav") { synthRadarPing() }, 1)
            sfxCipher = pool.load(ensureWav(dir, "cipher.wav") { synthCipherBurst() }, 1)
            sfxSlideUp = pool.load(ensureWav(dir, "slide_up.wav") { synthSweep(320.0, 1480.0, 0.11) }, 1)
            sfxSlideDown = pool.load(ensureWav(dir, "slide_dn.wav") { synthSweep(1360.0, 290.0, 0.11) }, 1)
            sfxConfirm = pool.load(ensureWav(dir, "confirm.wav") { synthConfirm() }, 1)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        if (enabled == value) return
        enabled = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SFX_ENABLED, value)
            .apply()
        if (value) confirm()
    }

    fun toggle(context: Context): Boolean {
        setEnabled(context, !enabled)
        return enabled
    }

    /** Crisp UI button/chip micro-chirp. */
    fun tick(pitch: Float = 1.0f, volume: Float = 0.28f) {
        if (!enabled || !ready) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < 25L) return
        lastTickAt = now
        soundPool?.play(sfxTick, volume, volume, 1, 0, pitch.coerceIn(0.5f, 2.0f))
    }

    /** Subtle tactile encoder click while spinning/dragging the globe. */
    fun rotaryTick(zoom: Double) {
        if (!enabled || !ready) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRotaryAt < 50L) return
        lastRotaryAt = now
        val pitch = (0.82f + (kotlin.math.ln(zoom.coerceAtLeast(1.0)).toFloat() * 0.12f))
            .coerceIn(0.75f, 1.55f)
        soundPool?.play(sfxTick, 0.13f, 0.13f, 0, 0, pitch)
    }

    /** Pitch-shifted tick as the user scrubs across years (fraction 0.0..1.0). */
    fun scrubTick(fraction: Float) {
        val pitch = 0.75f + fraction.coerceIn(0f, 1f) * 0.70f
        tick(pitch = pitch, volume = 0.22f)
    }

    /** 3-stage satellite target-lock trill when selecting a sighting. */
    fun targetLock() {
        if (!enabled || !ready) return
        soundPool?.play(sfxLock, 0.36f, 0.36f, 2, 0, 1.0f)
    }

    /** Submarine / GEOINT sonar ping for Area Radar Lock & Near Me. */
    fun radarPing(pitch: Float = 1.0f) {
        if (!enabled || !ready) return
        soundPool?.play(sfxPing, 0.34f, 0.34f, 2, 0, pitch.coerceIn(0.7f, 1.4f))
    }

    /** Fast FSK data-decryption burst for tab switches & cipher text reveals. */
    fun cipherBurst() {
        if (!enabled || !ready) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCipherAt < 120L) return
        lastCipherAt = now
        soundPool?.play(sfxCipher, 0.22f, 0.22f, 1, 0, 1.0f)
    }

    /** Sci-fi servo sweep when opening/closing the console drawer or zooming. */
    fun consoleSlide(opening: Boolean) {
        if (!enabled || !ready) return
        val id = if (opening) sfxSlideUp else sfxSlideDown
        soundPool?.play(id, 0.26f, 0.26f, 1, 0, 1.0f)
    }

    /** Optics zoom pulse. */
    fun zoomPulse(zoomIn: Boolean) {
        if (!enabled || !ready) return
        val id = if (zoomIn) sfxSlideUp else sfxSlideDown
        soundPool?.play(id, 0.20f, 0.20f, 1, 0, 1.45f)
    }

    /** Harmonic triad chime for Case File save, export, or import completion. */
    fun confirm() {
        if (!enabled || !ready) return
        soundPool?.play(sfxConfirm, 0.34f, 0.34f, 2, 0, 1.0f)
    }

    /* ---------------- Procedural PCM waveform synthesis ---------------- */

    private fun ensureWav(dir: File, name: String, generator: () -> ShortArray): String {
        val file = File(dir, name)
        if (!file.exists() || file.length() <= 44L) {
            writeWav(file, generator())
        }
        return file.absolutePath
    }

    private fun synthTick(): ShortArray {
        val n = (SAMPLE_RATE * 0.024).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t * 170.0)
            val freq = 2600.0 * exp(-t * 45.0) + 900.0
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val sample = (sin(phase) + 0.35 * sin(phase * 2.4)) * env * 0.7
            out[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthTargetLock(): ShortArray {
        val n = (SAMPLE_RATE * 0.27).toInt()
        val out = ShortArray(n)
        val freqs = doubleArrayOf(1320.0, 1760.0, 2349.3, 3136.0)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val step = (t / 0.052).toInt().coerceAtMost(3)
            val localT = t - step * 0.052
            val decayRate = if (step < 3) 48.0 else 16.0
            val env = (1.0 - exp(-localT * 600.0)) * exp(-localT * decayRate)
            val f = freqs[step]
            phase += 2.0 * PI * f / SAMPLE_RATE
            val mod = sin(phase * 2.0) * 0.45
            val sig = (sin(phase + mod) + 0.25 * sin(phase * 0.5)) * env * 0.65
            out[i] = (sig.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthRadarPing(): ShortArray {
        val n = (SAMPLE_RATE * 0.38).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (t / 0.004).coerceAtMost(1.0)
            val env = attack * exp(-t * 11.5)
            val f1 = 1567.98
            val f2 = 3135.96
            val s = (
                sin(2.0 * PI * f1 * t) * 0.8 +
                    sin(2.0 * PI * f2 * t) * 0.2 * exp(-t * 25.0)
                ) * env * 0.7
            out[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthCipherBurst(): ShortArray {
        val n = (SAMPLE_RATE * 0.135).toInt()
        val out = ShortArray(n)
        val pips = doubleArrayOf(2100.0, 3400.0, 1850.0, 3950.0, 2650.0, 4200.0, 2300.0, 3100.0, 3700.0)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val idx = (t / 0.015).toInt().coerceIn(0, pips.lastIndex)
            val localT = t - idx * 0.015
            val env = exp(-localT * 95.0) * (1.0 - (t / 0.135) * 0.4)
            phase += 2.0 * PI * pips[idx] / SAMPLE_RATE
            val s = sin(phase) * env * 0.55
            out[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthSweep(startHz: Double, endHz: Double, durationSec: Double): ShortArray {
        val n = (SAMPLE_RATE * durationSec).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val env = sin(PI * p)
            val freq = startHz * Math.pow(endHz / startHz, p)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val s = (sin(phase) + 0.2 * sin(phase * 2.0)) * env * 0.55
            out[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthConfirm(): ShortArray {
        val n = (SAMPLE_RATE * 0.24).toInt()
        val out = ShortArray(n)
        val notes = doubleArrayOf(880.0, 1174.66, 1760.0)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val step = (t / 0.065).toInt().coerceAtMost(2)
            val localT = t - step * 0.065
            val env = (1.0 - exp(-localT * 500.0)) * exp(-localT * (if (step == 2) 14.0 else 32.0))
            phase += 2.0 * PI * notes[step] / SAMPLE_RATE
            val s = (sin(phase) + 0.3 * sin(phase * 2.0)) * env * 0.6
            out[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (sample in pcm) {
            buf.putShort(sample)
        }
        FileOutputStream(file).use { it.write(buf.array()) }
    }
}
