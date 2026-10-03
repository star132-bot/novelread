package com.mkread.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mkread.app.core.model.BookSummary
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun BookRow(
    book: BookSummary,
    onOpenBook: ((String) -> Unit)?,
    onRename: (BookSummary) -> Unit,
    onEditMetadata: (BookSummary) -> Unit,
    onMoveToFolder: (BookSummary) -> Unit,
    onRemove: (BookSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val rowInteraction = if (onOpenBook == null) {
        Modifier
    } else {
        Modifier.clickable { onOpenBook(book.id) }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .then(rowInteraction)
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BookCover(book)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val primaryMetadata = listOfNotNull(
                    book.author?.takeIf(String::isNotBlank),
                    book.chapterTitle?.takeIf(String::isNotBlank),
                ).joinToString(" · ").ifBlank { "尚未开始" }
                Text(
                    text = primaryMetadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { book.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(book.progressFraction.coerceIn(0f, 1f) * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatLastOpened(book.lastOpenedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box {
                LibraryTooltipIconButton(
                    label = "《${book.title}》更多操作",
                    onClick = { menuExpanded = true },
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuExpanded = false
                            onRename(book)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑信息") },
                        onClick = {
                            menuExpanded = false
                            onEditMetadata(book)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("移动到文件夹") },
                        onClick = {
                            menuExpanded = false
                            onMoveToFolder(book)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("移出书架") },
                        onClick = {
                            menuExpanded = false
                            onRemove(book)
                        },
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 80.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun BookCover(book: BookSummary) {
    val palette = listOf(
        Color(0xFF46645C),
        Color(0xFFA85B45),
        Color(0xFF645D82),
        Color(0xFF48627A),
    )
    val color = palette[(book.id.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
        modifier = Modifier
            .width(48.dp)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(4.dp))
            .drawBehind { drawRect(color) },
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = book.title,
            modifier = Modifier.padding(6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Serif,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatLastOpened(timestamp: Long?): String = if (timestamp == null) {
    "尚未阅读"
} else {
    "最近阅读 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))}"
}
