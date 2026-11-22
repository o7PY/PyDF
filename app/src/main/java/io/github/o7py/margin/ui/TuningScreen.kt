package io.github.o7py.margin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.o7py.margin.input.InputTuning
import kotlin.math.roundToInt

/** Hidden Developer tuning (SPEC §12.5). Changes apply immediately and persist in DataStore. */
@Composable
fun TuningScreen(t: InputTuning, onChange: (InputTuning) -> Unit, onBack: () -> Unit, onInputDebug: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Developer tuning", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onChange(InputTuning(debugOverlays = t.debugOverlays)) }, Modifier.padding(end = 12.dp).height(48.dp)) { Text("Reset to defaults") }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Draw debug overlays", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(t.debugOverlays, { onChange(t.copy(debugOverlays = it)) })
                }
                Button(onClick = onInputDebug, modifier = Modifier.height(48.dp)) { Text("Open input debug screen (M0)") }
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Section("Palm rejection & navigation")
                SliderRow("Palm size threshold (0 = off)", t.maxTouchMajorDp, 0f..120f, "%.0f dp") { onChange(t.copy(maxTouchMajorDp = it)) }
                SliderRow("Finger pairing window", t.pairWindowMs.toFloat(), 50f..600f, "%.0f ms") { onChange(t.copy(pairWindowMs = it.toLong())) }
                SliderRow("Pen-lift cooldown", t.stylusCooldownMs.toFloat(), 0f..1500f, "%.0f ms") { onChange(t.copy(stylusCooldownMs = it.toLong())) }
                SliderRow("Pinch distance before zoom starts", t.zoomStartDp, 0f..150f, "%.0f dp") { onChange(t.copy(zoomStartDp = it)) }
                SliderRow("Eraser radius", t.eraserRadiusDp, 2f..32f, "%.0f dp") { onChange(t.copy(eraserRadiusDp = it)) }
                Section("Smart pen classifier")
                SliderRow("Max angle to baseline", t.classifierAngleDeg, 5f..45f, "%.0f°") { onChange(t.copy(classifierAngleDeg = it)) }
                SliderRow("Min points in line band", t.classifierBandFraction, 0.4f..1f, "%.2f") { onChange(t.copy(classifierBandFraction = it)) }
                SliderRow("Decision distance", t.decisionDistanceDp, 8f..80f, "%.0f dp") { onChange(t.copy(decisionDistanceDp = it)) }
                SliderRow("Max x reversals", t.classifierMaxReversals.toFloat(), 0f..5f, "%.0f") { onChange(t.copy(classifierMaxReversals = it.roundToInt())) }
                SliderRow("Max vertical span (× line height)", t.classifierMaxSpanLines, 0.5f..3f, "%.2f") { onChange(t.copy(classifierMaxSpanLines = it)) }
                SliderRow("Line band expansion", t.lineExpandFraction, 0f..0.6f, "%.2f") { onChange(t.copy(lineExpandFraction = it)) }
                SliderRow("Max offset from line centre", t.classifierMaxCenterOffset, 0.05f..0.6f, "%.2f") { onChange(t.copy(classifierMaxCenterOffset = it)) }
                SliderRow("Press-and-hold to highlight (0 = off)", t.dwellHighlightMs.toFloat(), 0f..1000f, "%.0f ms") { onChange(t.copy(dwellHighlightMs = it.toLong())) }
                SliderRow("Hold-still radius", t.dwellRadiusDp, 1f..12f, "%.1f dp") { onChange(t.copy(dwellRadiusDp = it)) }
                SliderRow("Tap (ink dot) max duration", t.tapMaxMs.toFloat(), 50f..500f, "%.0f ms") { onChange(t.copy(tapMaxMs = it.toLong())) }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(fmt.format(value), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.coerceIn(range), onChange, valueRange = range, modifier = Modifier.height(48.dp))
    }
}
