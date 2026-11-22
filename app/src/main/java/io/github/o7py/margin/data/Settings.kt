package io.github.o7py.margin.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.o7py.margin.input.InputTuning
import io.github.o7py.margin.model.Palette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

/** Last-used color/width per tool, remembered app-wide (SPEC §8.4). */
data class ToolPrefs(
    val penColor: Int = Palette.pen[0],
    val penWidthIndex: Int = 1,
    val highlighterColor: Int = Palette.highlighter[0],
    val highlighterWidthIndex: Int = 0,
    val pageBackground: Int = Palette.pageBackgrounds[0],
)

class Settings(private val context: Context) {
    private object K {
        val maxTouchMajor = floatPreferencesKey("t_maxTouchMajorDp")
        val pairWindow = longPreferencesKey("t_pairWindowMs")
        val cooldown = longPreferencesKey("t_stylusCooldownMs")
        val angle = floatPreferencesKey("t_angle")
        val band = floatPreferencesKey("t_band")
        val distance = floatPreferencesKey("t_distance")
        val reversals = intPreferencesKey("t_reversals")
        val span = floatPreferencesKey("t_span")
        val expand = floatPreferencesKey("t_expand")
        val center = floatPreferencesKey("t_center")
        val tap = longPreferencesKey("t_tap")
        val eraser = floatPreferencesKey("t_eraser")
        val debug = booleanPreferencesKey("t_debug")
        val zoomStart = floatPreferencesKey("t_zoomStart")
        val dwellMs = longPreferencesKey("t_dwellMs")
        val dwellR = floatPreferencesKey("t_dwellR")
        val penColor = intPreferencesKey("penColor")
        val penWidth = intPreferencesKey("penWidth")
        val hlColor = intPreferencesKey("hlColor")
        val hlWidth = intPreferencesKey("hlWidth")
        val pageBg = intPreferencesKey("pageBg")
    }

    private val scrollbarKey = booleanPreferencesKey("scrollbar")
    val scrollbar: Flow<Boolean> = context.store.data.map { it[scrollbarKey] ?: false }
    suspend fun setScrollbar(v: Boolean) { context.store.edit { it[scrollbarKey] = v } }

    private val nightKey = booleanPreferencesKey("night")
    val night: Flow<Boolean> = context.store.data.map { it[nightKey] ?: false }
    suspend fun setNight(v: Boolean) { context.store.edit { it[nightKey] = v } }

    val tuning: Flow<InputTuning> = context.store.data.map { p -> p.toTuning() }
    val tools: Flow<ToolPrefs> = context.store.data.map { p ->
        val d = ToolPrefs()
        ToolPrefs(
            p[K.penColor] ?: d.penColor, p[K.penWidth] ?: d.penWidthIndex,
            p[K.hlColor] ?: d.highlighterColor, p[K.hlWidth] ?: d.highlighterWidthIndex,
            p[K.pageBg] ?: d.pageBackground,
        )
    }

    private fun Preferences.toTuning(): InputTuning {
        val d = InputTuning()
        return InputTuning(
            maxTouchMajorDp = this[K.maxTouchMajor] ?: d.maxTouchMajorDp,
            pairWindowMs = this[K.pairWindow] ?: d.pairWindowMs,
            stylusCooldownMs = this[K.cooldown] ?: d.stylusCooldownMs,
            classifierAngleDeg = this[K.angle] ?: d.classifierAngleDeg,
            classifierBandFraction = this[K.band] ?: d.classifierBandFraction,
            decisionDistanceDp = this[K.distance] ?: d.decisionDistanceDp,
            classifierMaxReversals = this[K.reversals] ?: d.classifierMaxReversals,
            classifierMaxSpanLines = this[K.span] ?: d.classifierMaxSpanLines,
            lineExpandFraction = this[K.expand] ?: d.lineExpandFraction,
            classifierMaxCenterOffset = this[K.center] ?: d.classifierMaxCenterOffset,
            tapMaxMs = this[K.tap] ?: d.tapMaxMs,
            eraserRadiusDp = this[K.eraser] ?: d.eraserRadiusDp,
            debugOverlays = this[K.debug] ?: d.debugOverlays,
            zoomStartDp = this[K.zoomStart] ?: d.zoomStartDp,
            dwellHighlightMs = this[K.dwellMs] ?: d.dwellHighlightMs,
            dwellRadiusDp = this[K.dwellR] ?: d.dwellRadiusDp,
        )
    }

    suspend fun saveTuning(t: InputTuning) {
        context.store.edit { p ->
            p[K.maxTouchMajor] = t.maxTouchMajorDp; p[K.pairWindow] = t.pairWindowMs; p[K.cooldown] = t.stylusCooldownMs
            p[K.angle] = t.classifierAngleDeg; p[K.band] = t.classifierBandFraction; p[K.distance] = t.decisionDistanceDp
            p[K.reversals] = t.classifierMaxReversals; p[K.span] = t.classifierMaxSpanLines; p[K.expand] = t.lineExpandFraction
            p[K.center] = t.classifierMaxCenterOffset; p[K.tap] = t.tapMaxMs; p[K.eraser] = t.eraserRadiusDp; p[K.debug] = t.debugOverlays
            p[K.zoomStart] = t.zoomStartDp
            p[K.dwellMs] = t.dwellHighlightMs; p[K.dwellR] = t.dwellRadiusDp
        }
    }

    suspend fun saveTools(t: ToolPrefs) {
        context.store.edit { p ->
            p[K.penColor] = t.penColor; p[K.penWidth] = t.penWidthIndex
            p[K.hlColor] = t.highlighterColor; p[K.hlWidth] = t.highlighterWidthIndex; p[K.pageBg] = t.pageBackground
        }
    }
}
