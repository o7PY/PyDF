package io.github.o7py.margin.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.o7py.margin.R
import io.github.o7py.margin.data.ToolPrefs
import io.github.o7py.margin.model.PageEntry
import io.github.o7py.margin.model.Palette
import io.github.o7py.margin.model.Tool

private val TopBarHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChrome(
    controller: ReaderController,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSaveCopy: () -> Unit,
    onOpenTuning: () -> Unit,
) {
    val ui by controller.ui.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val reduce = LocalReduceMotion.current
    val dur = if (reduce) 0 else 180
    val density = androidx.compose.ui.platform.LocalDensity.current
    var panelOpen by rememberSaveable { mutableStateOf(landscape) }
    var popoverTool by remember { mutableStateOf<Tool?>(null) }
    var insertSheet by remember { mutableStateOf<InsertRequest?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var about by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(controller.tools) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val barHeight = with(density) { barHeightPx.toDp() }
    val panelWidth = 196.dp
    val full = ui.fullscreen
    // entering fullscreen hides everything, including the page panel
    LaunchedEffect(full) { if (full) { panelOpen = false; popoverTool = null; controller.canvas.setTopInset(0f) } }
    val bump by controller.floatingBump.collectAsState()
    // the floating toolbar hides itself after a quiet period (not while a popover is open)
    LaunchedEffect(ui.floatingBar, bump, popoverTool, panelOpen) {
        if (ui.floatingBar && popoverTool == null && !panelOpen) { kotlinx.coroutines.delay(4500); controller.hideFloatingBar() }
    }
    val topPad = if (full) 0.dp else barHeight
    val panelVisible = panelOpen && ui.pages.isNotEmpty()
    LaunchedEffect(panelVisible) {
        controller.canvas.setLeftInset(if (panelVisible) with(density) { panelWidth.roundToPx() } else 0)
    }

    Box(Modifier.fillMaxSize()) {
        // ---------------- page panel (left, below the toolbar)
        AnimatedVisibility(
            visible = panelOpen && ui.pages.isNotEmpty(),
            enter = slideInHorizontally(tween(dur)) { -it }, exit = slideOutHorizontally(tween(dur)) { -it },
            modifier = Modifier.align(Alignment.TopStart).padding(top = topPad),
        ) {
            PagePanel(
                controller, ui.pages, ui.currentPage, ui.outline,
                Modifier.width(panelWidth).fillMaxHeight(),
                onInsert = { insertSheet = it }, onDelete = { deleteIndex = it },
            )
        }

        // ---------------- the one toolbar (docked) / floating toolbar (fullscreen)
        val toolbar: @Composable (Boolean) -> Unit = { floating ->
            Toolbar(
                ui = ui, tools = tools, floating = floating,
                onFullscreen = controller::toggleFullscreen,
                onBack = onBack,
                onTool = { t ->
                    controller.floatingBump.value++
                    if (t == ui.tool && t != Tool.ERASER) popoverTool = if (popoverTool == t) null else t
                    else { controller.setTool(t); popoverTool = null }
                },
                onLock = { controller.floatingBump.value++; controller.toggleLock() },
                onUndo = { controller.floatingBump.value++; controller.undo() },
                onRedo = { controller.floatingBump.value++; controller.redo() },
                onTogglePanel = { panelOpen = !panelOpen },
                onSave = controller::save,
                onSaveCopy = onSaveCopy,
                onInsert = { insertSheet = InsertRequest(ui.currentPage + 1, ui.currentPage) },
                onAbout = { about = true },
                onTuning = onOpenTuning,
                onScrollbar = { controller.floatingBump.value++; controller.toggleScrollbar() },
                onNight = { controller.floatingBump.value++; controller.toggleNight() },
            )
        }
        if (!full) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { sz ->
                    barHeightPx = sz.height
                    controller.canvas.setTopInset(sz.height.toFloat())
                },
            ) {
                toolbar(false)
                if (ui.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        } else {
            AnimatedVisibility(
                visible = ui.floatingBar,
                enter = slideInVertically(tween(dur)) { -it } + fadeIn(tween(dur)),
                exit = slideOutVertically(tween(dur)) { -it } + fadeOut(tween(dur)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp)
                    .onSizeChanged { sz -> barHeightPx = sz.height + with(density) { 12.dp.roundToPx() } },
            ) { toolbar(true) }
        }
        popoverTool?.let { t ->
            ToolPopover(
                tool = t, prefs = tools, topOffsetPx = barHeightPx,
                onChange = { tools = it; controller.updateTools(it) },
                onDismiss = { popoverTool = null },
            )
        }

        // ---------------- text selection menu (Edge style)
        ui.selection?.let { sel ->
            SelectionMenu(
                sel = sel, minTopPx = if (full && !ui.floatingBar) 0 else barHeightPx,
                onHighlight = controller::highlightSelection,
                onCopy = controller::copySelection,
                onSelectAll = controller::selectAll,
                onRemove = controller::removeSelectionHighlights,
            )
        }

        // ---------------- draft banner
        if (ui.draftAvailable) {
            Surface(
                shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPad + 12.dp).widthIn(max = 560.dp).padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Restore unsaved changes?", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = controller::discardDraft, modifier = Modifier.height(48.dp)) { Text("Discard") }
                    FilledTonalButton(onClick = controller::restoreDraft, modifier = Modifier.height(48.dp)) { Text("Restore") }
                }
            }
        }

        // ---------------- page indicator
        AnimatedVisibility(
            visible = ui.pageIndicatorVisible && ui.pages.isNotEmpty(),
            enter = fadeIn(tween(dur)), exit = fadeOut(tween(if (reduce) 0 else 400)),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)) {
                Text("${ui.currentPage + 1} / ${ui.pages.size}", Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))

        if (ui.loading) {
            Box(Modifier.align(Alignment.Center)) { androidx.compose.material3.CircularProgressIndicator() }
        }
    }

    // ---------------- sheets & dialogs
    insertSheet?.let { req ->
        InsertPageSheet(
            req = req, pageCount = ui.pages.size, initialColor = tools.pageBackground,
            onDismiss = { insertSheet = null },
            onInsert = { at, rel, color -> insertSheet = null; controller.insertPage(at, rel, color); tools = controller.tools },
        )
    }
    deleteIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("Delete page ${idx + 1}?") },
            text = { Text("You can undo this.") },
            confirmButton = { TextButton(onClick = { deleteIndex = null; controller.deletePage(idx) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteIndex = null }) { Text("Cancel") } },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("PyDF") },
            text = { Text("Offline PDF reader & annotator.\nRendering by MuPDF (AGPL).\n\nThe pen is smart: swipe along a line of text to highlight it, write anywhere else. Hold the S Pen button to erase.\n\nLong-press About for Developer tuning.") },
            confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        )
    }
    if (ui.needsPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { controller.close() },
            title = { Text("Password required") },
            text = {
                Column {
                    Text(if (ui.wrongPassword) "That password didn't work. Try again." else "This PDF is protected. Enter its password to open it.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = { TextButton(onClick = { ui.uri?.let { controller.open(it, pw) } }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { controller.close() }) { Text("Cancel") } },
        )
    }
    ui.error?.let { err ->
        AlertDialog(
            onDismissRequest = { controller.close() },
            title = { Text("Can't open this file") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { controller.close() }) { Text("OK") } },
        )
    }
}

data class InsertRequest(val at: Int, val relativeTo: Int, val fixed: Boolean = false)

/** Single toolbar, Samsung Notes style: back · title │ pen · highlighter · eraser │ undo · redo · pages · more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Toolbar(
    ui: ReaderUi, tools: ToolPrefs, floating: Boolean, onFullscreen: () -> Unit,
    onBack: () -> Unit, onTool: (Tool) -> Unit, onLock: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onTogglePanel: () -> Unit,
    onSave: () -> Unit, onSaveCopy: () -> Unit, onInsert: () -> Unit, onAbout: () -> Unit, onTuning: () -> Unit,
    onScrollbar: () -> Unit, onNight: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = if (floating) 10.dp else 2.dp,
        shape = if (floating) RoundedCornerShape(28.dp) else androidx.compose.ui.graphics.RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            (if (floating) Modifier else Modifier.statusBarsPadding()).fillMaxWidth().height(TopBarHeight).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Row(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.title.removeSuffix(".pdf"), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ui.dirty) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = "Unsaved changes" })
                }
                // right of the document name, away from the tools, so it isn't pressed by accident
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (ui.fullscreen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(onClick = onFullscreen)
                        .semantics { contentDescription = if (ui.fullscreen) "Exit full screen" else "Full screen" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (ui.fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, null,
                        tint = if (ui.fullscreen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
            // tools
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (t in Tool.entries) {
                        val active = t == ui.tool
                        val swatch = when (t) { Tool.PEN -> Color(tools.penColor); Tool.HIGHLIGHTER -> Color(tools.highlighterColor); else -> null }
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { onTool(t) }
                                .semantics { contentDescription = toolName(t) + if (active) ", selected" else "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(toolPainter(t), null, tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            if (swatch != null) {
                                Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(9.dp).clip(CircleShape).background(swatch)
                                    .border(1.dp, if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (ui.locked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(onClick = onLock)
                    .semantics { contentDescription = if (ui.locked) "Unlock zoom and rotation" else "Lock zoom and rotation" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (ui.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null,
                    tint = if (ui.locked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (ui.scrollbar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(onClick = onScrollbar)
                    .semantics { contentDescription = if (ui.scrollbar) "Hide scroll bar" else "Show scroll bar" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_scrollbar), null,
                    tint = if (ui.scrollbar) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (ui.night) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(onClick = onNight)
                    .semantics { contentDescription = if (ui.night) "Turn off night mode" else "Night mode (invert pages)" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.InvertColors, null,
                    tint = if (ui.night) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onUndo, enabled = ui.canUndo, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(onClick = onRedo, enabled = ui.canRedo, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
            IconButton(onClick = onTogglePanel, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ViewSidebar, "Pages") }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Save") }, onClick = { menu = false; onSave() }, enabled = !ui.saving)
                    DropdownMenuItem(text = { Text("Save a copy") }, onClick = { menu = false; onSaveCopy() }, enabled = !ui.saving)
                    DropdownMenuItem(text = { Text("Insert page") }, onClick = { menu = false; onInsert() })
                    HorizontalDivider()
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).combinedClickable(
                            onClick = { menu = false; onAbout() },
                            onLongClick = { menu = false; onTuning() },
                        ).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text("About", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun toolPainter(t: Tool): Painter = when (t) {
    Tool.PEN -> rememberVectorPainter(Icons.Rounded.Edit)
    Tool.HIGHLIGHTER -> painterResource(R.drawable.ic_tool_highlighter)
    Tool.ERASER -> painterResource(R.drawable.ic_tool_eraser)
}

private fun toolName(t: Tool) = when (t) { Tool.PEN -> "Smart pen"; Tool.HIGHLIGHTER -> "Highlighter"; Tool.ERASER -> "Eraser" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolPopover(tool: Tool, prefs: ToolPrefs, topOffsetPx: Int, onChange: (ToolPrefs) -> Unit, onDismiss: () -> Unit) {
    val isPen = tool == Tool.PEN
    val colors = if (isPen) Palette.pen else Palette.highlighter
    val names = if (isPen) Palette.penNames else Palette.highlighterNames
    val widths = if (isPen) Palette.penWidths else Palette.highlighterWidths
    val widthNames = if (isPen) Palette.penWidthNames else Palette.highlighterWidthNames
    val color = if (isPen) prefs.penColor else prefs.highlighterColor
    val widthIdx = if (isPen) prefs.penWidthIndex else prefs.highlighterWidthIndex
    Popup(
        alignment = Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(-240, topOffsetPx + 8),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp, modifier = Modifier.width(320.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(toolName(tool), style = MaterialTheme.typography.titleSmall)
                if (tool == Tool.PEN) Text("Swipe along a line of text to highlight it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                // live preview stroke
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    val w = widths[widthIdx.coerceIn(0, widths.size - 1)]
                    Canvas(Modifier.fillMaxSize()) {
                        if (!isPen) {
                            drawRect(Color(0xFF9E9E9E), topLeft = androidx.compose.ui.geometry.Offset(24f, size.height / 2 - 14f),
                                size = androidx.compose.ui.geometry.Size(size.width - 48f, 6f))
                            drawRect(Color(0xFF9E9E9E), topLeft = androidx.compose.ui.geometry.Offset(24f, size.height / 2 + 6f),
                                size = androidx.compose.ui.geometry.Size(size.width * 0.6f, 6f))
                        }
                        val p = Path()
                        val h = size.height / 2
                        p.moveTo(24f, h)
                        var x = 24f
                        while (x < size.width - 24f) {
                            p.quadraticTo(x + 20f, h - (if (isPen) 14f else 4f), x + 40f, h)
                            p.quadraticTo(x + 60f, h + (if (isPen) 14f else 4f), x + 80f, h)
                            x += 80f
                        }
                        drawPath(p, Color(color).copy(alpha = if (isPen) 1f else 0.35f),
                            style = Stroke(width = w * 2.2f * density, cap = if (isPen) StrokeCap.Round else StrokeCap.Butt))
                    }
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    colors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).clickable {
                                onChange(if (isPen) prefs.copy(penColor = c) else prefs.copy(highlighterColor = c))
                            }.semantics { contentDescription = names[i] },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                            if (c == color) Icon(Icons.Rounded.Check, null, tint = if (isPen) Color.White else Color(0xFF1A1A1A), modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    widths.forEachIndexed { i, w ->
                        val sel = i == widthIdx
                        Column(
                            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { onChange(if (isPen) prefs.copy(penWidthIndex = i) else prefs.copy(highlighterWidthIndex = i)) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.width(28.dp).height((w * if (isPen) 1.6f else 0.7f).dp.coerceAtLeast(1.dp)).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface))
                            Spacer(Modifier.height(6.dp))
                            Text(widthNames[i], style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (isPen) {
                    Spacer(Modifier.height(12.dp))
                    Text("Highlight color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Palette.highlighter.forEachIndexed { i, c ->
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).clickable { onChange(prefs.copy(highlighterColor = c)) }
                                    .semantics { contentDescription = "Highlight " + Palette.highlighterNames[i] },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(width = 34.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(Color(c).copy(alpha = 0.6f)))
                                if (c == prefs.highlighterColor) Icon(Icons.Rounded.Check, null, tint = Color(0xFF1A1A1A), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagePanel(
    controller: ReaderController, pages: List<PageEntry>, current: Int,
    outline: List<io.github.o7py.margin.model.OutlineItem>, modifier: Modifier,
    onInsert: (InsertRequest) -> Unit, onDelete: (Int) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 pages, 1 outline
    val showTabs = outline.isNotEmpty()
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 4.dp) {
        Column {
            if (showTabs) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp)) {
                    listOf("Pages", "Outline").forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = tab == i, onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                            modifier = Modifier.height(44.dp),
                        ) { Text(l, maxLines = 1) }
                    }
                }
            }
            if (showTabs && tab == 1) OutlineList(controller, outline, pages, current)
            else PageThumbList(controller, pages, current, onInsert, onDelete)
        }
    }
}

@Composable
private fun OutlineList(
    controller: ReaderController, outline: List<io.github.o7py.margin.model.OutlineItem>, pages: List<PageEntry>, current: Int,
) {
    val indexOf = remember(pages) { pages.withIndex().associate { (i, p) -> p.id to i } }
    // the section being read: last entry starting at or before the current page
    val active = remember(outline, current, indexOf) { outline.indexOfLast { (indexOf[it.pageId] ?: Int.MAX_VALUE) <= current } }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (active > 0) listState.scrollToItem((active - 2).coerceAtLeast(0)) }
    LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        itemsIndexed(outline) { i, item ->
            val pageIdx = indexOf[item.pageId]
            val isActive = i == active
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = pageIdx != null) { controller.goToOutline(item) }
                    .padding(start = (10 + 12 * item.level.coerceAtMost(4)).dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title, Modifier.weight(1f),
                    style = if (item.level == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.level == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (pageIdx == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (pageIdx != null) Text("${pageIdx + 1}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun PageThumbList(
    controller: ReaderController, pages: List<PageEntry>, current: Int,
    onInsert: (InsertRequest) -> Unit, onDelete: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val version by controller.thumbVersion.collectAsState()
    LaunchedEffect(current) {
        val vis = listState.layoutInfo.visibleItemsInfo
        if (vis.isNotEmpty() && (current < vis.first().index || current > vis.last().index)) listState.animateScrollToItem(current)
    }
    run {
        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                var menu by remember { mutableStateOf(false) }
                val thumb by produceState<ImageBitmap?>(null, p.id, version) { value = controller.thumbnail(p)?.asImageBitmap() }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth().height((168f * p.height / p.width).dp.coerceAtMost(260.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (p is PageEntry.Inserted) Color(p.color) else Color.White)
                            .border(if (i == current) 3.dp else 1.dp, if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .clickable { controller.goToPage(i) },
                    ) {
                        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreVert, "Page ${i + 1} actions") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Insert blank page before") }, onClick = { menu = false; onInsert(InsertRequest(i, i, fixed = true)) })
                                DropdownMenuItem(text = { Text("Insert blank page after") }, onClick = { menu = false; onInsert(InsertRequest(i + 1, i, fixed = true)) })
                                DropdownMenuItem(text = { Text("Delete page") }, onClick = { menu = false; onDelete(i) }, enabled = pages.size > 1)
                            }
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = { onInsert(InsertRequest(pages.size, pages.size - 1, fixed = true)) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add page at end")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InsertPageSheet(req: InsertRequest, pageCount: Int, initialColor: Int, onDismiss: () -> Unit, onInsert: (Int, Int, Int) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var color by remember { mutableIntStateOf(initialColor) }
    // where: 0 before, 1 after, 2 end (only when not fixed by the page panel)
    var where by remember { mutableIntStateOf(1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding()) {
            Text("Insert blank page", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (!req.fixed) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Before this page", "After this page", "At end").forEachIndexed { i, l ->
                        SegmentedButton(selected = where == i, onClick = { where = i }, shape = SegmentedButtonDefaults.itemShape(i, 3), modifier = Modifier.height(48.dp)) { Text(l) }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Text("Background", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.pageBackgrounds.forEachIndexed { i, c ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                        Box(
                            Modifier.size(width = 48.dp, height = 60.dp).clip(RoundedCornerShape(6.dp)).background(Color(c))
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                .clickable { color = c }.semantics { contentDescription = Palette.pageBackgroundNames[i] },
                        )
                        Text(Palette.pageBackgroundNames[i], style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val at = if (req.fixed) req.at else when (where) { 0 -> req.relativeTo; 1 -> req.relativeTo + 1; else -> pageCount }
                    val rel = if (!req.fixed && where == 2) pageCount - 1 else req.relativeTo
                    onInsert(at, rel, color)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Insert page") }
        }
    }
}

/** Floating menu above (or below) the selection: highlight colors, Copy, Select all, Remove highlight. */
@Composable
private fun SelectionMenu(
    sel: io.github.o7py.margin.canvas.ReaderCanvas.SelectionInfo,
    minTopPx: Int,
    onHighlight: (Int) -> Unit, onCopy: () -> Unit, onSelectAll: () -> Unit, onRemove: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth
        val screenH = constraints.maxHeight
        val gap = with(density) { 12.dp.roundToPx() }
        val handleSpace = with(density) { 30.dp.roundToPx() }
        val cx = ((sel.left + sel.right) / 2f).toInt()
        val x = (cx - size.width / 2).coerceIn(gap, (screenW - size.width - gap).coerceAtLeast(gap))
        val above = sel.top.toInt() - size.height - gap
        val y = if (above > minTopPx + gap) above else (sel.bottom.toInt() + handleSpace + gap).coerceAtMost(screenH - size.height - gap)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(x, y) }
                .onSizeChanged { size = it }
                .alpha(if (size == androidx.compose.ui.unit.IntSize.Zero) 0f else 1f),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Palette.highlighter.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable { onHighlight(c) }
                            .semantics { contentDescription = "Highlight " + Palette.highlighterNames[i] },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                MenuAction(Icons.Rounded.ContentCopy, "Copy", onCopy)
                MenuAction(Icons.Rounded.SelectAll, "Select all", onSelectAll)
                if (sel.overlapsHighlight) MenuAction(Icons.Rounded.Delete, "Remove highlight", onRemove)
            }
        }
    }
}

@Composable
private fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
