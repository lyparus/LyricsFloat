package io.github.lyricsfloat

import android.graphics.Bitmap
import android.graphics.Color

/** Цвета из обложки трека: акцент для текста и тёмный оттенок для фона. */
object CoverColors {
    /** Яркий акцент из обложки, читаемый на тёмном фоне; null — если обложки нет или она почти серая. */
    fun accent(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        return try {
            val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            val small = Bitmap.createScaledBitmap(src, 32, 32, true)
            val pixels = IntArray(32 * 32)
            small.getPixels(pixels, 0, 32, 0, 0, 32, 32)

            // Раскладываем насыщенные пиксели по 36 корзинам оттенка (по 10°).
            val hsv = FloatArray(3)
            val weights = FloatArray(36)
            val hueSums = FloatArray(36)
            val satSums = FloatArray(36)
            var counted = 0
            for (p in pixels) {
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
                val bucket = (hsv[0] / 10).toInt().coerceIn(0, 35)
                val weight = hsv[1] * hsv[2]
                weights[bucket] += weight
                hueSums[bucket] += hsv[0] * weight
                satSums[bucket] += hsv[1] * weight
                counted++
            }
            // Почти серая обложка — своего цвета нет.
            if (counted < 20) return null

            // Самый «весомый» оттенок и его среднее внутри корзины.
            val best = weights.indices.maxByOrNull { weights[it] } ?: return null
            val hue = hueSums[best] / weights[best]
            val saturation = satSums[best] / weights[best]
            Color.HSVToColor(floatArrayOf(hue, saturation.coerceIn(0.45f, 0.8f), 0.95f))
        } catch (e: Exception) {
            null
        }
    }

    /** Тёмный оттенок цвета для фона окна: тот же тон, насыщенность 0.45, яркость 0.16. */
    fun darkTint(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], 0.45f, 0.16f))
    }
}
