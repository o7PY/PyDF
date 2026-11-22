package io.github.o7py.margin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.o7py.margin.canvas.ReaderCanvas
import io.github.o7py.margin.data.AppContainer
import io.github.o7py.margin.debug.InputDebugView
import io.github.o7py.margin.input.InputTuning
import io.github.o7py.margin.ui.HomeScreen
import io.github.o7py.margin.ui.MarginTheme
import io.github.o7py.margin.ui.ReaderChrome
import io.github.o7py.margin.ui.ReaderController
import io.github.o7py.margin.ui.ReaderEvent
import io.github.o7py.margin.ui.RecentItem
import io.github.o7py.margin.ui.TuningScreen
import io.github.o7py.margin.ui.isReadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Layering: [ReaderCanvas] (plain View, gets raw MotionEvents incl. Samsung's S Pen actions) below a transparent
 * ComposeView for chrome. Touches that hit no composable fall through to the canvas.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer
    private lateinit var canvas: ReaderCanvas
    private lateinit var controller: ReaderController
    private lateinit var root: FrameLayout
    private var debugView: InputDebugView? = null
    private var saveCopySwitch = false

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { controller.takePermission(uri); controller.open(uri) }
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) controller.saveCopy(uri, saveCopySwitch)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container = (application as MarginApp).container
        canvas = ReaderCanvas(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.reader_bg))
            visibility = View.GONE
        }
        controller = ReaderController(container, lifecycleScope, canvas)
        lifecycleScope.launch { container.settings.tuning.collect { controller.applyTuning(it) } }
        lifecycleScope.launch { container.settings.scrollbar.collect { controller.applyScrollbar(it) } }
        lifecycleScope.launch { container.settings.night.collect { controller.applyNight(it) } }
        lifecycleScope.launch {
            controller.awaitFirstTools()
            container.settings.tools.collect { controller.applyTools(it) }
        }
        val compose = ComposeView(this).apply { setContent { App() } }
        root = FrameLayout(this).apply {
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(compose, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val uri: Uri = i.data ?: return
        controller.takePermission(uri)
        controller.open(uri)
    }

    override fun onPause() {
        super.onPause()
        controller.writeDraftNow()
    }

    /** Hover is observed at window level so the canvas knows about it even under the Compose layer (SPEC §6.3). */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        canvas.observeHover(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun showInputDebug() {
        if (debugView != null) return
        debugView = InputDebugView(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun hideInputDebug(): Boolean {
        val v = debugView ?: return false
        root.removeView(v); debugView = null
        return true
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!hideInputDebug()) super.onBackPressed()
    }

    private enum class Screen { HOME, READER, TUNING }

    @androidx.compose.runtime.Composable
    private fun App() = MarginTheme {
        val ui by controller.ui.collectAsState()
        var screen by remember { mutableStateOf(Screen.HOME) }
        var askUnsaved by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val tuning by container.settings.tuning.collectAsState(InputTuning())
        val recents by remember {
            container.db.recents().observe().map { list ->
                list.map { RecentItem(it, isReadable(this, it.uri), container.drafts.hasDraftFor(it.uri)) }
            }
        }.collectAsState(emptyList(), Dispatchers.IO)

        val docOpen = ui.uri != null
        LaunchedEffect(docOpen) { if (docOpen && screen == Screen.HOME) screen = Screen.READER }
        LaunchedEffect(screen, docOpen) {
            canvas.visibility = if (screen == Screen.READER && docOpen) View.VISIBLE else View.GONE
        }
        // Lock: keep the current orientation while reading
        LaunchedEffect(ui.locked, screen) {
            requestedOrientation = if (ui.locked && screen == Screen.READER) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        LaunchedEffect(Unit) {
            controller.events.collect { e ->
                when (e) {
                    is ReaderEvent.Snack -> launch {
                        val r = snackbar.showSnackbar(e.text, actionLabel = if (e.undo) "Undo" else null, withDismissAction = false)
                        if (r == SnackbarResult.ActionPerformed) controller.undo()
                    }
                    ReaderEvent.NeedsSaveAs -> {
                        snackbar.showSnackbar("This file is read-only — choose where to save a copy")
                        saveCopySwitch = true; createPdf.launch(ui.title.ifBlank { "document.pdf" })
                    }
                    ReaderEvent.Closed -> screen = Screen.HOME
                }
            }
        }

        fun leaveReader() { if (ui.dirty) askUnsaved = true else controller.close() }

        when (screen) {
            Screen.HOME -> HomeScreen(
                recents = recents,
                onOpenPdf = { openPdf.launch(arrayOf("application/pdf")) },
                onOpenRecent = { controller.open(Uri.parse(it.uri)) },
                onRemoveRecent = { r -> lifecycleScope.launch(Dispatchers.IO) { container.db.recents().delete(r.uri); container.drafts.delete(r.uri) } },
            )
            Screen.READER -> {
                BackHandler {
                    when {
                        hideInputDebug() -> {}
                        ui.selection != null -> controller.clearSelection()
                        else -> leaveReader()
                    }
                }
                ReaderChrome(
                    controller = controller, snackbar = snackbar,
                    onBack = ::leaveReader,
                    onSaveCopy = { saveCopySwitch = false; createPdf.launch(copyName(ui.title)) },
                    onOpenTuning = { screen = Screen.TUNING },
                )
            }
            Screen.TUNING -> {
                BackHandler { if (!hideInputDebug()) screen = if (docOpen) Screen.READER else Screen.HOME }
                TuningScreen(
                    t = tuning,
                    onChange = { t -> lifecycleScope.launch { container.settings.saveTuning(t) } },
                    onBack = { screen = if (docOpen) Screen.READER else Screen.HOME },
                    onInputDebug = ::showInputDebug,
                )
            }
        }

        if (askUnsaved) {
            AlertDialog(
                onDismissRequest = { askUnsaved = false },
                title = { Text("Save changes?") },
                text = { Text("You have unsaved changes to “${ui.title}”.") },
                confirmButton = {
                    TextButton(onClick = { askUnsaved = false; controller.save { ok -> if (ok) controller.close() } }) { Text("Save") }
                },
                dismissButton = {
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { askUnsaved = false; controller.discardDraft(); controller.close() }) { Text("Discard") }
                        TextButton(onClick = { askUnsaved = false }) { Text("Cancel") }
                    }
                },
            )
        }
    }

    private fun copyName(title: String): String {
        val base = title.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" }
        return "$base (annotated).pdf"
    }

    @Suppress("unused")
    private suspend fun firstTuning() = container.settings.tuning.first()
}
