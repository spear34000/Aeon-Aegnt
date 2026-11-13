package dev.aeon.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.FeedItem
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.platform.koreanDayPrefix
import dev.aeon.ui.platform.nowMillis
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.SettingsPage
import dev.aeon.ui.state.UiState

/** "1시간 전", "어제", "3일 전": how old a headline is, in one short phrase. */
internal fun agoText(millis: Double, now: Long): String {
    val minutes = ((now - millis.toLong()) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 2 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        minutes < 60 * 24 -> "${minutes / 60}시간 전"
        minutes < 60 * 48 -> "어제"
        else -> "${minutes / (60 * 24)}일 전"
    }
}

/**
 * 피드: headlines about the interests in the profile, nothing else. Each row is a plain statement of the fact with its
 * outlet and age; the picture is the article's when the server could keep it, else the outlet's mark. A tap opens the article.
 * Waiting approvals and activity live in 작업.
 */
@Composable
fun FeedScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val f = state.feed
    val connected = state.connection is Connection.Connected
    LaunchedEffect(connected) { if (connected) model.loadFeed() }
    val data = f.data
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        when {
            !f.loaded -> Hint("불러오는 중이에요.")
            f.interests.isEmpty() -> FeedMessage(AeonIcons.Feed, "관심사를 고르면 피드가 채워져요", "고른 관심사의 최신 소식만 제목으로 모아 보여 드려요. 자극적인 제목은 걸러내요.", "관심사 고르기") { model.openSettings(SettingsPage.Personal) }
            !f.online -> FeedMessage(AeonIcons.Globe, "인터넷 조회가 꺼져 있어요", "피드는 뉴스 출처에서 제목을 가져와요. 설정에서 켜면 다시 채워져요.", "약관 · 권한 열기") { model.openSettings(SettingsPage.Legal) }
            else -> {
                val now = nowMillis()
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val line = when {
                        f.refreshing -> "새 소식을 가져오는 중이에요…"
                        data == null || data.updatedAt <= 0 -> "아직 가져온 소식이 없어요."
                        else -> "${koreanDayPrefix(data.updatedAt.toLong(), now)}${koreanClock(data.updatedAt.toLong())}에 갱신 · " + rewriteLine(data)
                    }
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CircleButton(AeonIcons.Refresh, "새로고침") { if (!f.refreshing) model.refreshFeed() }
                }
                f.error?.let { Banner(it, Tone.Warn, "⚠") }
                if (f.interests.size > 1) {
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillChoice("전체", f.filter == null) { model.setFeedFilter(null) }
                        f.interests.forEach { i -> PillChoice(i, f.filter == i) { model.setFeedFilter(i) } }
                    }
                }
                val sections = data?.sections.orEmpty().filter { f.filter == null || it.interest == f.filter }
                sections.forEach { sec ->
                    GroupHeader(sec.interest, if (sec.items.isEmpty()) null else "${sec.items.size}건")
                    if (sec.items.isEmpty()) {
                        Text(sec.note ?: "새 소식이 없어요", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        ListSurface {
                            sec.items.forEachIndexed { i, item ->
                                if (i > 0) RowDivider(16.dp)
                                FeedRow(item, state, model, now)
                            }
                        }
                    }
                }
                Hint("출처 Google 뉴스. 사진이 없는 기사는 언론사 표시가 나와요. 누르면 원문이 열려요.")
            }
        }
    }
}

@Composable
private fun FeedRow(item: FeedItem, state: UiState, model: AppModel, now: Long) {
    val scheme = MaterialTheme.colorScheme
    if (item.image) LaunchedEffect(item.id) { model.loadFeedImage(item.id) }
    val bitmap = state.feed.images[item.id]
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).clickable { model.openArticle(item.url) }
            .semantics { contentDescription = "${item.title}, ${item.source}, ${agoText(item.publishedAt, now)}" }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${item.source} · ${agoText(item.publishedAt, now)}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Waiting for the picture, or none: the outlet's first letter is its mark.
            else if (!(item.image && !state.feed.images.containsKey(item.id))) Text(item.source.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "·", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FeedMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalAeonStatus.current.fg3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        AeonButton(button, onClick, Modifier.padding(top = 10.dp), kind = ButtonKind.Primary)
    }
}

/** What was done to the titles, said exactly: all rewritten, some, or none. */
private fun rewriteLine(d: dev.aeon.ui.api.FeedData): String {
    val all = d.sections.flatMap { it.items }
    val n = all.count { it.rewritten }
    return when {
        d.mode != "model" || n == 0 -> d.modeNote ?: "원문 제목 그대로예요"
        n == all.size -> "제목은 사실만 남겨 다시 썼어요"
        else -> "제목 ${n}개를 사실만 남겨 다시 썼어요. 나머지는 원문이에요"
    }
}
