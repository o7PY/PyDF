package io.github.o7py.margin.canvas

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * "Smart invert" for night reading: inverts lightness but keeps hues (invert, then rotate hue by 180°),
 * so white pages turn dark while blue stays blue and yellow highlights stay yellow-ish.
 */
object NightFilter {
    val matrix: ColorMatrix = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    ).apply {
        // hue rotation by 180° (luminance-preserving, SVG feColorMatrix hueRotate coefficients)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.430f, 0.144f, 0f, 0f,
                    0.426f, 0.430f, 0.144f, 0f, 0f,
                    0.426f, 1.430f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }

    /** Paint for saveLayer: everything drawn inside the layer is inverted when the layer is composited. */
    fun layerPaint() = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
}
