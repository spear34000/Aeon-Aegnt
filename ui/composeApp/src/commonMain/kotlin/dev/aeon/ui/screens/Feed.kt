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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import dev.aeon.ui.api.FeedPost
import dev.aeon.ui.api.RunReport
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.platform.koreanDayPrefix
import dev.aeon.ui.platform.nowMillis
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.SettingsPage
import dev.aeon.ui.state.UiState

/** "1시간 전", "어제", "3일 전": how old a post or headline is, in one short phrase. */
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

/** How long is left until a deadline, in one short phrase, and whether it is close: "3일 남음", "오늘 마감", "마감 지남". */
internal fun deadlineText(deadline: Double, now: Long): Pair<String, Boolean> {
    val left = deadline.toLong() - now
    return when {
        left < 0 -> "마감 지남" to false
        left < 24 * 3_600_000L -> "오늘 마감" to true
        else -> "마감 ${left / (24 * 3_600_000L) + 1}일 남음" to (left <= 3 * 24 * 3_600_000L)
    }
}

/**
 * 피드: posts written from the owner's interests, each with its sources and the reason it is shown. The server makes them on
 * its own timer or when the owner asks; opening the tab never starts a run. A post that is only a headline says so. The
 * order is the server's: unread, to-do and soon-due posts first, past-due ones last, and the owner's own order for the day.
 */
@Composable
fun FeedScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val f = state.feed
    val connected = state.connection is Connection.Connected
    LaunchedEffect(connected) { if (connected) model.loadFeed() }
    val data = f.data
    val interests = data?.interests.orEmpty()
    val online = data?.online ?: true
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        when {
            !f.loaded -> FeedHint("불러오는 중이에요.")
            interests.isEmpty() -> FeedMessage(AeonIcons.Feed, "관심사를 고르면 피드가 채워져요", "고른 관심사의 최근 기사에서 사실만 골라 짧은 글로 써 드려요. 출처가 없는 글은 쓰지 않아요.", "관심사 고르기") { model.openSettings(SettingsPage.Personal) }
            else -> {
                val now = nowMillis()
                val posts = data!!.posts.filter { f.filter == null || it.category == f.filter }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val last = data.status.lastRunAt
                    val line = when {
                        f.generating -> "새 글을 만드는 중이에요… 한 번에 1분쯤 걸려요."
                        last <= 0 -> "아직 만든 글이 없어요."
                        else -> "${koreanDayPrefix(last.toLong(), now)}${koreanClock(last.toLong())}에 갱신 · 오늘 ${data.status.today.runs}/${data.status.caps.runs}번 만들었어요"
                    }
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CircleButton(AeonIcons.Sliders, "피드 설정") { model.openSettings(SettingsPage.Feed) }
                    CircleButton(AeonIcons.Refresh, "새 글 만들기") { if (!f.generating) model.generateFeed() }
                }
                f.error?.let { Banner(it, Tone.Warn, "⚠") }
                RunBanner(f.report, data.status.lastRun, online) { model.dismissFeedReport() }
                if (interests.size > 1) {
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillChoice("전체", f.filter == null) { model.setFeedFilter(null) }
                        interests.forEach { i -> PillChoice(i, f.filter == i) { model.setFeedFilter(i) } }
                    }
                }
                if (posts.isEmpty()) {
                    if (data.posts.isEmpty()) FeedMessage(AeonIcons.Feed, "아직 쓴 글이 없어요", "관심사의 최근 기사에서 사실만 골라 짧은 글로 써 드려요. 출처가 없는 글은 쓰지 않아요.", if (f.generating) "만드는 중…" else "새 글 만들기") { model.generateFeed() }
                    else FeedHint("이 관심사의 글이 아직 없어요.")
                } else {
                    Box(Modifier.padding(top = 12.dp)) {
                        ListSurface {
                            posts.forEachIndexed { i, p ->
                                if (i > 0) RowDivider(16.dp)
                                PostRow(p, state, model, now)
                            }
                        }
                    }
                    FeedHint("글은 기사 내용에서만 쓰고 출처를 달아요.")
                }
            }
        }
    }
    val open = f.openId?.let { id -> data?.posts?.firstOrNull { it.id == id } }
    if (open != null) PostSheet(open, state, model)
}

/** What the last run said, in words: made, refused by the reviewer, or why nothing could be done. */
@Composable
private fun RunBanner(report: RunReport?, last: RunReport?, online: Boolean, onDismiss: () -> Unit) {
    val r = report
    when {
        r != null && r.status == "made" && r.mode == "written" -> Banner("새 글 ${r.made}개를 만들었어요." + if (r.rejected.isNotEmpty()) " ${r.rejected.size}개는 검수에서 걸렀어요: ${r.rejected.first().why}" else "", Tone.Ok, "✓", onDismiss)
        r != null && r.status == "made" -> Banner(r.message ?: "원문 제목만 보여 드려요.", Tone.Warn, "·", onDismiss)
        r != null && r.status == "nothing" -> Banner((r.message ?: "쓸 만한 기사가 없었어요") + if (r.rejected.isNotEmpty()) ": ${r.rejected.first().why}" else "", Tone.Neutral, "·", onDismiss)
        r != null && r.status == "skipped" -> Banner(r.message ?: "지금은 만들 수 없어요.", Tone.Warn, "·", onDismiss)
        !online -> Banner("인터넷 조회가 꺼져 있어서 새 글을 만들지 않아요.", Tone.Warn, "·")
        last != null && last.mode == "headlines" -> Banner(last.message ?: "모델을 쓸 수 없어서 원문 제목만 보여 드려요.", Tone.Warn, "·")
    }
}

@Composable
private fun PostRow(p: FeedPost, state: UiState, model: AppModel, now: Long) {
    val scheme = MaterialTheme.colorScheme
    if (p.image) LaunchedEffect(p.id) { model.loadFeedImage(p.id) }
    val bitmap = state.feed.images[p.id]
    val deadline = p.deadline?.let { deadlineText(it, now) }
    Column(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).clickable { model.openPost(p.id) }
            .semantics { contentDescription = "${p.title}, ${p.sources.firstOrNull()?.outlet ?: ""}, ${agoText(p.createdAt, now)}" }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.seenAt == null) Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(scheme.primary))
            Text(p.kicker, style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1)
            if (p.liked) Icon(AeonIcons.Heart, contentDescription = "좋아요", tint = scheme.primary, modifier = Modifier.size(14.dp))
            Box(Modifier.weight(1f))
            Text(agoText(p.createdAt, now), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (p.body.isNotBlank()) Text(p.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
        }
        Text(p.reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.sources.firstOrNull()?.outlet ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!p.written) Chip("원문 제목", Tone.Neutral)
            else if (deadline != null) Chip(deadline.first, if (deadline.second) Tone.Warn else Tone.Neutral)
        }
    }
}

/** One post in full: the text, why it is shown, where it comes from (each source opens the article), and what the owner can do with it. */
@Composable
private fun PostSheet(p: FeedPost, state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    val now = nowMillis()
    val deadline = p.deadline?.let { deadlineText(it, now) }
    SheetDialog(onDismiss = model::closePost) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.kicker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(agoText(p.createdAt, now), style = MaterialTheme.typography.labelMedium, color = s.fg3)
            if (!p.written) Chip("원문 제목", Tone.Neutral) else if (deadline != null) Chip(deadline.first, if (deadline.second) Tone.Warn else Tone.Neutral)
        }
        SelectionContainer { Text(p.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 2.dp)) }
        if (p.body.isNotBlank()) SelectionContainer { Text(p.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp)) }
        else Text("글을 쓰지 않았어요. 원문 제목만 보여 드려요.", style = MaterialTheme.typography.bodyMedium, color = s.fg3)
        Text("왜 보여 드리나요", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Text(p.reason, style = MaterialTheme.typography.bodyMedium, color = s.fg3)
        Text("출처", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        ListSurface {
            p.sources.forEachIndexed { i, src ->
                if (i > 0) RowDivider(16.dp)
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clickable { model.openArticle(src.url) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(src.outlet, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${src.title} · ${agoText(src.publishedAt, now)}", style = MaterialTheme.typography.labelMedium, color = s.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(AeonIcons.Link, contentDescription = "원문 열기", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton(if (p.liked) "좋아요 취소" else "좋아요", { model.likePost(p.id, !p.liked) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = null)
            AeonButton("지우기", { model.removePost(p.id) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("맨 위로", { model.movePost(p.id, "top") }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            AeonButton("맨 아래로", { model.movePost(p.id, "bottom") }, Modifier.weight(1f), kind = ButtonKind.Secondary)
        }
        Text("순서를 바꾸면 오늘은 그 순서를 지켜요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }
}

@Composable
internal fun FeedHint(text: String) {
    Text(text, Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FeedMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalAeonStatus.current.fg3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        AeonButton(button, onClick, Modifier.padding(top = 10.dp), kind = ButtonKind.Primary)
    }
}
