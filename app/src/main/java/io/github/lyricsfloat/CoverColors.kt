package io.github.lyricsfloat

import android.graphics.Bitmap
import android.graphics.Color

/** Цвета из обложки трека: акцент для текста и тёмный оттенок для фона. */
object CoverColors {
    /** Яркий акцент из обложки, читаемый на тёмном фоне; null — если обложки нет или она почти серая. */
    fun accent(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        try {
            var bmp = bitmap
            if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            
            val scaledBmp = Bitmap.createScaledBitmap(bmp, 32, 32, true)
            val pixels = IntArray(1024)
            scaledBmp.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            
            val hsv = FloatArray(3)
            val buckets = Array(36) { 0f }
            var totalWeight = 0f
            var weightedHue = 0f
            var weightedSaturation = 0f
            
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 128) continue
                
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
                
                val bucket = (hsv[0] / 10).toInt().coerceIn(0, 35)
                val weight = hsv[1] * hsv[2]
                
                buckets[bucket] += weight
                weightedHue += hsv[0] * weight
                weightedSaturation += hsv[1] * weight
                
                totalWeight += weight
            }
            
            if (totalWeight < 20) return null
            
            val maxBucket = buckets.withIndex().maxByOrNull { it.value }?.index ?: return null
            
            val hue = weightedHue / totalWeight
            val saturation = weightedSaturation / totalWeight
            
            val resultColor = Color.HSVToColor(floatArrayOf(hue, saturation.coerceIn(0.45f, 0.8f), 0.95f))
            return resultColor
        } catch (e: Exception) {
            return null
        }
    }

    /** Тёмный оттенок цвета для фона окна: тот же тон, насыщенность 0.45, яркость 0.16. */
    fun darkTint(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], 0.45f, 0.16f))
    }
}
