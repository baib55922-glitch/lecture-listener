package com.lecturelistener.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecturelistener.data.CardStatus
import com.lecturelistener.data.QuestionCard

/** 底部问题卡片区：最新卡片在最下方自动展开，旧卡片折叠为标题 */
@Composable
fun CardsPanel(modifier: Modifier, vm: MainViewModel) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val lastId = cards.lastOrNull()?.id

    // 新卡片出现：折叠旧卡、展开新卡并滚动到底部
    LaunchedEffect(lastId) {
        if (lastId != null && !expanded.containsKey(lastId)) {
            expanded.clear()
            expanded[lastId] = true
        }
        if (cards.isNotEmpty()) listState.animateScrollToItem(cards.size - 1)
    }

    Column(modifier) {
        Text(
            "提问卡片",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    "识别到老师提问后\n答案卡片会出现在这里",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    QuestionCardView(
                        card = card,
                        expanded = expanded[card.id] == true,
                        onToggle = { expanded[card.id] = !(expanded[card.id] == true) },
                        vm = vm,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCardView(
    card: QuestionCard,
    expanded: Boolean,
    onToggle: () -> Unit,
    vm: MainViewModel,
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            Modifier
                .clickable(onClick = onToggle)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    statusIcon(card.status),
                    contentDescription = null,
                    tint = statusColor(card.status),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    card.question.ifBlank { card.rawText },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    when (card.status) {
                        CardStatus.JUDGING -> LoadingRow("正在确认问题…")
                        CardStatus.GENERATING -> LoadingRow("正在生成答案…")
                        CardStatus.DONE -> SelectionContainer {
                            Text(
                                card.answer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        CardStatus.FAILED -> Column {
                            Text(
                                "⚠ ${card.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row {
                                TextButton(onClick = { vm.retryCard(card.id) }) {
                                    Text("重试")
                                }
                                TextButton(onClick = { vm.deleteCard(card.id) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                    if (card.status == CardStatus.DONE) {
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(card.answer))
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("复制答案")
                            }
                            TextButton(onClick = { vm.deleteCard(card.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                    }
                    Text(
                        formatTime(card.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun statusIcon(status: CardStatus): ImageVector = when (status) {
    CardStatus.DONE -> Icons.Default.CheckCircle
    CardStatus.FAILED -> Icons.Default.Error
    CardStatus.JUDGING, CardStatus.GENERATING -> Icons.Default.HourglassEmpty
}

@Composable
private fun statusColor(status: CardStatus): Color = when (status) {
    CardStatus.DONE -> Color(0xFF2EBD59)
    CardStatus.FAILED -> MaterialTheme.colorScheme.error
    CardStatus.JUDGING, CardStatus.GENERATING -> MaterialTheme.colorScheme.primary
}
