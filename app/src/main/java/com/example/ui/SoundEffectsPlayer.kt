package com.example.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.IOException
import kotlin.math.sin

class SoundEffectsPlayer {

    private val sampleRate = 22050
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Synthesizes and plays a short, crisp tactile mechanical click for UI interaction.
     */
    fun playClick() {
        val durationMs = 35
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val sample = ShortArray(numSamples)

        val startFreq = 1200.0
        val endFreq = 350.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val progress = i.toDouble() / numSamples
            // Exponential pitch down
            val freq = startFreq + (endFreq - startFreq) * (progress * progress)
            // Quadratic envelope decay for high crispness
            val envelope = (1.0 - progress) * (1.0 - progress)
            sample[i] = (sin(2.0 * Math.PI * freq * t) * envelope * Short.MAX_VALUE * 0.45).toInt().toShort()
        }

        playPcm(sample)
    }

    /**
     * Synthesizes and plays a quick mechanical tick "clack" for rotating elements (slot machine reels).
     */
    fun playSpinTick() {
        val durationMs = 25
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val sample = ShortArray(numSamples)

        val baseFreq = 850.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val progress = i.toDouble() / numSamples
            // Fast exponential decay to simulate a neat impact tick
            val envelope = Math.exp(-progress * 8.0)
            sample[i] = (sin(2.0 * Math.PI * baseFreq * t) * envelope * Short.MAX_VALUE * 0.50).toInt().toShort()
        }

        playPcm(sample)
    }

    /**
     * Synthesizes and plays a gorgeous, retro C-Major positive jackpot arpeggio for wins.
     */
    fun playWin() {
        // Arpeggio frequencies: C5, E5, G5, C6, E6, G6
        val frequencies = doubleArrayOf(523.25, 659.25, 783.99, 1046.50, 1318.51, 1567.98)
        val toneDurationMs = 110
        val totalDurationMs = frequencies.size * toneDurationMs
        val numSamples = (sampleRate * (totalDurationMs / 1000.0)).toInt()
        val sample = ShortArray(numSamples)

        val samplesPerTone = (sampleRate * (toneDurationMs / 1000.0)).toInt()

        for (i in 0 until numSamples) {
            val toneIdx = (i / samplesPerTone).coerceAtMost(frequencies.size - 1)
            val freq = frequencies[toneIdx]

            val localIdx = i % samplesPerTone
            val t = localIdx.toDouble() / sampleRate
            val progressInTone = localIdx.toDouble() / samplesPerTone

            // Decay slightly within each individual note to make them pluck distinctively
            val envelope = 1.0 - progressInTone * 0.45

            // Modulate subtle vibrato on top of frequencies for extra gaming style
            val vibrato = 1.0 + 0.012 * sin(2.0 * Math.PI * 8.0 * (i.toDouble() / sampleRate))
            val modulatedFreq = freq * vibrato

            sample[i] = (sin(2.0 * Math.PI * modulatedFreq * t) * envelope * Short.MAX_VALUE * 0.45).toInt().toShort()
        }

        playPcm(sample)
    }

    /**
     * Plays the synthesised PCM short array on standard Android AudioTrack in a self-managed background thread
     * to keep CPU execution and UI recompositions absolutely buttery smooth.
     */
    private fun playPcm(sample: ShortArray) {
        kotlin.concurrent.thread(start = true) {
            var audioTrack: AudioTrack? = null
            try {
                val bufferSize = sample.size * 2
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(sample, 0, sample.size)
                audioTrack.play()

                // Static modes play asynchronously, we sleep the background thread for the sound's duration 
                // to keep AudioTrack alive, then cleanly dispose of it.
                val playDurationMs = (sample.size * 1000L) / sampleRate
                Thread.sleep(playDurationMs + 100L)

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }
}
