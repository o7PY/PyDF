package io.github.o7py.margin.ui

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.o7py.margin.R
import io.github.o7py.margin.data.RecentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecentItem(val entity: RecentEntity, val readable: Boolean, val hasDraft: Boolean)

@Composable
fun HomeScreen(
    recents: List<RecentItem>,
    onOpenPdf: () -> Unit,
    onOpenRecent: (RecentEntity) -> Unit,
    onRemoveRecent: (RecentEntity) -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(176.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_margin_mark), null, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("PyDF", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(24.dp))
                        if (!landscape) {
                            Button(
                                onClick = onOpenPdf,
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = ButtonDefaults.ContentPadding,
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Open PDF", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                        if (recents.isNotEmpty()) Text("Recent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (recents.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
                }
                items(recents, key = { it.entity.uri }) { RecentCard(it, onOpenRecent, onRemoveRecent) }
            }
            if (landscape) {
                ExtendedFloatingActionButton(
                    onClick = onOpenPdf,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Open PDF") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.empty_state), null, Modifier.size(200.dp))
        Spacer(Modifier.height(16.dp))
        Text("Open a PDF to get started", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(item: RecentItem, onOpen: (RecentEntity) -> Unit, onRemove: (RecentEntity) -> Unit) {
    val e = item.entity
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, e.thumbnailPath) {
        value = withContext(Dispatchers.IO) { e.thumbnailPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .alpha(if (item.readable) 1f else 0.5f)
            .combinedClickable(onClick = { if (item.readable) onOpen(e) else menu = true }, onLongClick = { menu = true }),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.78f).padding(12.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            else Icon(Icons.Rounded.Description, null, Modifier.size(48.dp), tint = Color(0xFFB0ACA3))
            if (item.hasDraft) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        Row(Modifier.padding(start = 14.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(e.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (!item.readable) "File moved or deleted"
                    else DateUtils.getRelativeTimeSpanString(e.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() +
                        if (item.hasDraft) " · unsaved changes" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.readable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove from recents") }, onClick = { menu = false; onRemove(e) })
                }
            }
        }
    }
}

fun isReadable(ctx: android.content.Context, uri: String): Boolean = try {
    ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
} catch (t: Throwable) { false }

@Composable
fun rememberCtx() = LocalContext.current
