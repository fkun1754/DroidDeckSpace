package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only mirror of the official DroidDeck release notes (stable + preview), no install action. */
object OfficialDeckFeed {
    data class Item(val stable: Boolean, val tag: String, val title: String, val notes: String, val published: Long)

    private const val PREFS = "official_deck_feed"
    private const val KEY_JSON = "json"
    private const val KEY_AT = "at"
    private const val CACHE_MS = 60L * 60L * 1000L
    private const val STABLE = "https://api.github.com/repos/Droid-Deck/DroidDeck/releases?per_page=5"
    private const val PREVIEW = "https://api.github.com/repos/Droid-Deck/DroidDeck-CI/releases?per_page=5"

    fun load(context: android.content.Context, force: Boolean = false): List<Item> {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_AT, 0L) < CACHE_MS) {
            prefs.getString(KEY_JSON, null)?.let { return parse(it) }
        }
        val items = ArrayList<Item>()
        fetch(STABLE, true)?.let { items.add(it) }
        fetch(PREVIEW, false)?.let { items.add(it) }
        if (items.isNotEmpty()) {
            prefs.edit().putString(KEY_JSON, toJson(items)).putLong(KEY_AT, now).apply()
            return items
        }
        return prefs.getString(KEY_JSON, null)?.let { parse(it) } ?: emptyList()
    }

    private fun fetch(url: String, stable: Boolean): Item? {
        val body = Downloader.downloadString(url) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optBoolean("draft")) continue
            if (stable && o.optBoolean("prerelease")) continue
            val tag = o.optString("tag_name").ifBlank { o.optString("name") }
            if (tag.isBlank()) continue
            return Item(
                stable = stable, tag = tag,
                title = o.optString("name").ifBlank { tag },
                notes = o.optString("body"),
                published = o.optString("published_at").let { raw ->
                    runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).parse(raw.take(19))?.time }.getOrNull() ?: 0L
                },
            )
        }
        return null
    }

    private fun toJson(items: List<Item>): String {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("s", it.stable).put("t", it.tag).put("n", it.title).put("b", it.notes).put("d", it.published)) }
        return array.toString()
    }

    private fun parse(raw: String): List<Item> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optBoolean("s"), o.optString("t"), o.optString("n"), o.optString("b"), o.optLong("d"))
        }
    }.getOrDefault(emptyList())
}

@Composable
internal fun OfficialFeed() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<OfficialDeckFeed.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { OfficialDeckFeed.load(ctx) }
        loading = false
    }
    val colors = MaterialTheme.colorScheme
    Text(
        stringResource(R.string.official_feed_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        color = colors.onSurface, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    if (loading) {
        Text(stringResource(R.string.official_feed_loading), fontSize = 13.sp, color = colors.onSurfaceVariant)
        return
    }
    if (items.isEmpty()) {
        Text(stringResource(R.string.official_feed_empty), fontSize = 13.sp, color = colors.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val isOpen = open == item.tag
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface)
                    .clickable { open = if (isOpen) null else item.tag }.padding(14.dp),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        stringResource(if (item.stable) R.string.official_feed_stable else R.string.official_feed_preview),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (item.stable) colors.primary else colors.tertiary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(item.tag, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Spacer(Modifier.weight(1f))
                    if (item.published > 0) Text(dateLabel(item.published), fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                if (item.title.isNotBlank() && item.title != item.tag) {
                    Text(item.title, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.padding(top = 4.dp))
                }
                val notes = item.notes.trim()
                if (notes.isNotEmpty()) {
                    Text(
                        notes, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = if (isOpen) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                    if (!isOpen && notes.length > 240) {
                        TextButton(onClick = { open = item.tag }) { Text(stringResource(R.string.official_feed_more)) }
                    }
                }
            }
        }
    }
}

private fun dateLabel(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))
