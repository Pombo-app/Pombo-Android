package com.pombo.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One voice band: energy (0–1) and where in the band it sits (0 = bottom edge, 1 = top edge). */
data class BandReading(val level: Float, val pitch: Float)

/** Voice wave: three lines, one per voice band, the same drawing as the web's audioWave.js. */
object VoiceWave {
    class Line(val cycles: Float, val opacity: Float, val from: Float, val to: Float, val speed: Float)

    val lines = listOf(
        Line(1.2f, 0.75f, 5f, 35f, 0.08f),
        Line(1.8f, 0.45f, 35f, 65f, 0.11f),
        Line(2.5f, 0.3f, 65f, 95f, 0.15f)
    )
    const val IDLE = 1f
    private const val MAX = 10f
    private val BANDS_HZ = listOf(80f to 500f, 500f to 2000f, 2000f to 6000f)
    val RESTING = lines.map { BandReading(0f, 0.5f) }

    fun restCentre(index: Int) = (lines[index].from + lines[index].to) / 2

    /** y (0–24) of line [index] at x (0–100). */
    fun y(index: Int, x: Float, amplitude: Float, phase: Float, centre: Float): Float {
        val fromCentre = min(1f, abs(x - centre) / 70f)
        val edge = sin(PI * x / 100).toFloat()
        val taper = cos(fromCentre * PI / 2).toFloat() * edge
        return 12f + amplitude * taper * sin(2 * PI * lines[index].cycles * fromCentre - phase).toFloat()
    }

    fun amplitude(level: Float) = IDLE + level * MAX

    private const val FFT = 512

    /**
     * Band readings from the last [FFT] mono samples, on the same dB scale as the
     * web's AnalyserNode byte data (−100 dB to −30 dB).
     */
    fun analyse(samples: FloatArray, sampleRate: Int): List<BandReading> {
        if (samples.size < FFT || sampleRate <= 0) return RESTING
        val re = FloatArray(FFT)
        val im = FloatArray(FFT)
        val start = samples.size - FFT
        for (i in 0 until FFT) {
            val hann = 0.5f - 0.5f * cos(2 * PI * i / (FFT - 1)).toFloat()
            re[i] = samples[start + i] * hann
        }
        fft(re, im)
        val bins = FloatArray(FFT / 2) { b ->
            val mag = sqrt(re[b] * re[b] + im[b] * im[b]) / FFT
            val db = if (mag > 0f) 20f * log10(mag) else -100f
            ((db + 100f) / 70f).coerceIn(0f, 1f)
        }
        val hzPerBin = sampleRate.toFloat() / FFT
        return BANDS_HZ.map { (lo, hi) ->
            val from = maxOf(1, (lo / hzPerBin).toInt())
            val to = min(bins.size - 1, kotlin.math.ceil(hi / hzPerBin).toInt())
            var sum = 0f
            var weighted = 0f
            for (b in from..to) {
                sum += bins[b]
                weighted += bins[b] * (b - from)
            }
            BandReading(
                level = sum / (to - from + 1),
                pitch = if (sum > 0f) weighted / sum / maxOf(1, to - from) else 0.5f
            )
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cos(angle * k).toFloat()
                    val s = sin(angle * k).toFloat()
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            len = len shl 1
        }
    }
}

class WaveMotion {
    val levels = FloatArray(3)
    val phases = FloatArray(3)
    val centres = FloatArray(3) { VoiceWave.restCentre(it) }

    fun reset() {
        levels.fill(0f)
        phases.fill(0f)
        for (i in 0 until 3) centres[i] = VoiceWave.restCentre(i)
    }

    fun step(readings: List<BandReading>) {
        readings.forEachIndexed { i, r ->
            val line = VoiceWave.lines[i]
            levels[i] += (r.level - levels[i]) * 0.35f
            centres[i] += (line.from + (line.to - line.from) * r.pitch - centres[i]) * 0.15f
            phases[i] += line.speed * (1 + levels[i] * 2)
        }
    }
}

fun DrawScope.drawVoiceWave(motion: WaveMotion?, color: Color) {
    val sx = size.width / 100f
    val sy = size.height / 24f
    VoiceWave.lines.forEachIndexed { i, line ->
        val amplitude = if (motion == null) VoiceWave.IDLE else VoiceWave.amplitude(motion.levels[i])
        val phase = motion?.phases?.get(i) ?: 0f
        val centre = motion?.centres?.get(i) ?: VoiceWave.restCentre(i)
        val path = Path()
        var x = 0f
        while (x <= 100f) {
            val p = Offset(x * sx, VoiceWave.y(i, x, amplitude, phase, centre) * sy)
            if (x == 0f) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            x += 2f
        }
        drawPath(path, color.copy(alpha = line.opacity), style = Stroke(width = 1.5f * density, cap = StrokeCap.Round))
    }
}
