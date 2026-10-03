package com.mkread.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mkread.app.R
import com.mkread.app.core.database.ChapterEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    chapters: List<ChapterEntity>,
    currentChapterId: String,
    onSelectChapter: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("chapter-list-sheet"),
    ) {
        Text(
            text = stringResource(R.string.chapter_list_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.chapter_list_count, chapters.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        LazyColumn {
            items(
                items = chapters,
                key = ChapterEntity::id,
            ) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    current = chapter.id == currentChapterId,
                    onClick = { onSelectChapter(chapter.id) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    current: Boolean,
    onClick: () -> Unit,
) {
    val currentDescription = stringResource(R.string.chapter_list_current, chapter.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (current) Modifier.semantics { contentDescription = currentDescription }
                else Modifier,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (current) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.chapter_character_count, chapter.characterCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}
