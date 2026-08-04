package com.mkread.app.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mkread.app.R

@Composable
fun ReaderBottomBar(
    chapterIndex: Int,
    chapterCount: Int,
    currentPage: Int,
    pageCount: Int,
    paginationComplete: Boolean,
    onPreviousChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPreviousChapter = chapterIndex > 0
    val hasNextChapter = chapterIndex + 1 < chapterCount
    val hasPreviousPage = currentPage > 0 || hasPreviousChapter
    val hasNextPage = currentPage + 1 < pageCount || (paginationComplete && hasNextChapter)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reader-bottom-bar"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_previous_chapter),
                    enabled = hasPreviousChapter,
                    onClick = onPreviousChapter,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = null)
                }
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_previous_page),
                    enabled = hasPreviousPage,
                    onClick = onPreviousPage,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                }
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .heightIn(min = 48.dp)
                        .testTag("reader-page-count"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (pageCount > 0) {
                            stringResource(R.string.reader_page_count, currentPage + 1, pageCount)
                        } else {
                            stringResource(R.string.reader_page_count_empty)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_next_page),
                    enabled = hasNextPage,
                    onClick = onNextPage,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_next_chapter),
                    enabled = hasNextChapter,
                    onClick = onNextChapter,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomIconButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = label },
        ) {
            icon()
        }
    }
}
