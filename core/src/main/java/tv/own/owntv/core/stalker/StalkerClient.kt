package tv.own.owntv.core.stalker

import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.TimeZone

/**
 * Stalker/Ministra (MAG portal) protocol client — Phase A of `future-plan/stalker-portal-plan.md`:
 * portal-URL normalization, the MAC handshake (`handshake` → Bearer token → `get_profile`), and the
 * MAG request headers, all derived in one place. Content lists and `create_link` come in later
 * phases. Responses are small JSON wrapped as `{"js": <payload>}` and parsed with [JsonReader].
 */
open class StalkerClient(okHttpClient: OkHttpClient) {

    /**
     * Same pool and dispatcher as the shared client, but with connection-failure recovery ON.
     *
     * The shared client sets `retryOnConnectionFailure(false)` so SyncManager owns stream retries. A
     * portal call is not a stream: it is one small request, often the first in minutes, and portals
     * drop idle keep-alive sockets. OkHttp then hands the dead one out and the request dies instantly
     * with `unexpected end of stream` — which is exactly how a catch-up pick failed with no network
     * round trip at all, three milliseconds after it was made.
     */
    private val client: OkHttpClient = okHttpClient.newBuilder().retryOnConnectionFailure(true).build()

    /** The MAC is not authorized / the token expired / the portal answered `{"js":false}`. */
    class StalkerAuthException(message: String) : IOException(message)

    /** Non-2xx portal response, carrying the status so callers can retry transient 429/5xx. */
    class StalkerHttpException(val code: Int, message: String) : IOException(message)

    /** A successful probe: which API endpoint answered, and the token it handed out. */
    data class Handshake(val apiBase: String, val token: String)

    /** A live TV genre (Stalker's channel category). */
    data class Genre(val id: String, val title: String)

    /**
     * A live channel from `get_ordered_list`. [cmd] is the portal command (NOT playable) — resolve it
     * to a real URL at play time via [createLink]. [id] is the stable portal id → used as remoteId.
     */
    data class Channel(
        val id: String,
        val name: String,
        val number: String?,
        val cmd: String,
        val logo: String?,
        val xmltvId: String?,
        val genreId: String?,
        val archive: Boolean,
        val archiveDuration: Int,
    )

    /**
     * A VOD (movie) or series-show item from `get_ordered_list` (§1.3). For a movie, [cmd] is the
     * portal command resolved to a real URL at play time via [createLink] (`type=vod`). For a series
     * SHOW, [cmd] carries the show command; per-episode URLs are minted with a `series=<ep>` param at
     * play time (episodes themselves are listed lazily when the show is opened). [id] is the stable
     * portal id → used as remoteId.
     */
    data class VodItem(
        val id: String,
        val name: String,
        val cmd: String?,
        val poster: String?,
        val year: Int?,
        val plot: String?,
        val rating: Double?,
        // The item's own category id (`category_id`). Used by the bulk `category=*` fast path to map each
        // item to its category (the per-category path already knows the category from the request).
        val categoryId: String?,
        // Epoch ms for the "Date added" sort, from Ministra's `video.added` column. Null on trimmed
        // reseller panels that omit it — the sort then falls through to playlist order.
        val addedAt: Long?,
    )

    /** One page of `get_ordered_list`: the items plus the totals needed to page through the rest. */
    data class Page<T>(val totalItems: Int, val maxPageItems: Int, val items: List<T>)

    /**
     * A season row from `get_ordered_list&movie_id=<show>` (D-2). [episodes] is the row's `series`
     * array — the episode numbers that exist. [cmd] is shared by the whole season; a playable URL is
     * minted per episode via [createLink] with `type=vod&series=<episode>`. [seasonNumber] is parsed
     * from the row id (`"280:2"` → 2) or the name ("Season 2"); callers fall back to the row index.
     */
    data class SeasonItem(
        val id: String,
        val name: String?,
        val cmd: String?,
        val seasonNumber: Int?,
        val episodes: List<Int>,
    )

    /** One programme from `get_short_epg` (Phase E, §5.5) — times in epoch ms. */
    data class ShortEpgEntry(val title: String, val description: String?, val startMs: Long, val stopMs: Long)

    /**
     * One row of the portal's per-day guide table, which is the only place the **archive token** for a
     * programme can be had. [id] is the portal's own `"<programme>_<channel>"` pair, and it is what
     * `tv_archive&action=create_link` expects inside its `cmd` — see [getArchiveDay]. [archived] is
     * the portal's `mark_archive`: its claim that this programme is inside the recorded window.
     */
    data class ArchiveEntry(val id: String, val startMs: Long, val stopMs: Long, val archived: Boolean)

    /**
     * Probe the API-endpoint candidates for [portalUrl] (§1.1: `portal.php`, `stalker_portal/server/
     * load.php`, `server/load.php`) and return the first that completes a handshake. The winning
     * [Handshake.apiBase] should be reused for every later call on this source.
     */
    open suspend fun resolveHandshake(portalUrl: String, mac: String, userAgent: String? = null): Handshake {
        var lastError: Exception? = null
        for (apiBase in apiCandidates(portalUrl)) {
            try {
                val token = handshake(apiBase, mac, userAgent)
                Log.i(TAG, "handshake ok apiBase=$apiBase")
                return Handshake(apiBase, token)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "handshake candidate failed apiBase=$apiBase message=${e.message}")
                // 429 is the portal itself answering "slow down" for THIS endpoint, not "wrong
                // endpoint" — trying the next candidate immediately only piles two more requests onto
                // an already unhappy portal. Stop the probe here; the caller's own retry/backoff
                // decides what happens next, same as it already does for a throttled page fetch.
                // 403/5xx are deliberately excluded: on first contact (no candidate confirmed working
                // yet) either can just as easily mean "a WAF sits in front of this one path" — a real
                // Stalker deployment has answered exactly that on portal.php while stalker_portal/…
                // worked fine — and stopping here would abandon the probe before ever reaching it.
                if (e is StalkerHttpException && e.code == 429) throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("No portal API endpoint candidates for $portalUrl")
    }

    /** `?type=stb&action=handshake` → the Bearer token used by every authorized call. */
    suspend fun handshake(apiBase: String, mac: String, userAgent: String? = null): String {
        val url = "$apiBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val js = request(url, mac, token = null, userAgent = userAgent) { readScalarFields(it) }
        return js["token"]?.takeIf { it.isNotBlank() }
            ?: throw IOException("Portal handshake returned no token")
    }

    /**
     * `?type=stb&action=get_profile` (with the Bearer token) — confirms the MAC is authorized and
     * returns the STB profile (scalar fields only; nested payloads are skipped). Optional second-step
     * device identity is sent only when supplied; MAC-only sources retain the original request shape.
     */
    open suspend fun getProfile(
        apiBase: String,
        mac: String,
        token: String,
        userAgent: String? = null,
        identity: StalkerDeviceIdentity = StalkerDeviceIdentity(),
    ): Map<String, String> {
        val url = profileUrl(apiBase, identity)
        val profile = request(url, mac, token, userAgent) { readScalarFields(it) }
        if (profile.isEmpty()) throw StalkerAuthException("Portal accepted the handshake but returned an empty profile — the MAC may not be authorized")
        return profile
    }

    /**
     * `?type=account_info&action=get_main_info` — subscription status/expiry (§1.2, Phase F).
     * Optional and portal-quirky (expiry is often surfaced oddly, e.g. in `phone`); callers must
     * treat a failure or an empty map as "no account info", never as an error.
     */
    open suspend fun getAccountInfo(apiBase: String, mac: String, token: String, userAgent: String? = null): Map<String, String> {
        val url = "$apiBase?type=account_info&action=get_main_info&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readScalarFields(it) }
    }

    // --- Content lists (Phase C) ---

    /** `?type=itv&action=get_genres` → live TV genres (the js payload is an array). */
    suspend fun getGenres(apiBase: String, mac: String, token: String, userAgent: String? = null): List<Genre> {
        val url = "$apiBase?type=itv&action=get_genres&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readGenreArray(it) }
    }

    /** `?type=vod&action=get_categories` → movie categories (same `[{id,title}]` shape as genres). */
    suspend fun getVodCategories(apiBase: String, mac: String, token: String, userAgent: String? = null): List<Genre> {
        val url = "$apiBase?type=vod&action=get_categories&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readGenreArray(it) }
    }

    /** `?type=series&action=get_categories` → series categories (same shape as genres). */
    suspend fun getSeriesCategories(apiBase: String, mac: String, token: String, userAgent: String? = null): List<Genre> {
        val url = "$apiBase?type=series&action=get_categories&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readGenreArray(it) }
    }

    /**
     * `?type=itv&action=get_short_epg&ch_id=<id>&size=<n>` → the channel's now/next programmes
     * (Phase E, §5.5 — the safe per-channel path; the OOM-prone bulk `get_epg_info` is never used).
     * The js payload is an array (some portals wrap it as `{data:[…]}`); entries with no readable
     * start/stop are dropped.
     */
    open suspend fun getShortEpg(
        apiBase: String, mac: String, token: String, userAgent: String?, channelId: String, size: Int = 8,
    ): List<ShortEpgEntry> {
        val url = "$apiBase?type=itv&action=get_short_epg&ch_id=${enc(channelId)}&size=$size&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readShortEpg(it) }
    }

    /**
     * `?type=epg&action=get_simple_data_table&ch_id=<id>&date=<yyyy-MM-dd>&p=<page>` → one page of that
     * channel's guide for that day, each row carrying the archive token [ArchiveEntry.id].
     *
     * **This call is what makes catch-up work at all on a portal.** The archive `cmd` is
     * `auto /media/<programme>_<channel>.mpg`, and that first half is a portal-side programme id we
     * cannot compute: a Ministra facade parses the filename as `<programme_id>_<stream_id>` and looks
     * the pair up in its own guide. Sending a synthesized `<channel>_<start>_<duration>` instead — as
     * this client did until 1.0.43 — finds no row, so the portal answers **HTTP 200 with an empty
     * body**, which surfaces as an unhelpful `EOFException` and left catch-up silently dead.
     *
     * Note `type=epg`, not `itv` and not `tv_archive`: the facades that serve this route everything
     * else to an empty body, which is a fast way to be misled about what the portal supports.
     *
     * Times come back as true epoch seconds in `start_timestamp`/`stop_timestamp`, already matching
     * the wall clock the portal shows for the timezone this client sends in its cookie.
     */
    open suspend fun getArchiveDay(
        apiBase: String, mac: String, token: String, userAgent: String?, channelId: String, date: String, page: Int,
    ): Page<ArchiveEntry> {
        val url = "$apiBase?type=epg&action=get_simple_data_table&ch_id=${enc(channelId)}" +
            "&date=${enc(date)}&p=$page&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readArchiveDay(it) }
    }

    private fun readArchiveDay(reader: JsonReader): Page<ArchiveEntry> {
        var total = 0
        var maxPer = 0
        val rows = ArrayList<Map<String, String>>()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return Page(0, 0, emptyList()) }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "total_items" -> total = nextIntLenient(reader) ?: 0
                "max_page_items" -> maxPer = nextIntLenient(reader) ?: 0
                "data" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) rows.add(readScalarFields(reader)) else reader.skipValue()
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val items = rows.mapNotNull { f ->
            val id = f["id"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = epgTimeMs(f["start_timestamp"], f["time"]) ?: return@mapNotNull null
            val stop = epgTimeMs(f["stop_timestamp"], f["time_to"]) ?: return@mapNotNull null
            ArchiveEntry(id, start, stop, (f["mark_archive"]?.trim()?.toIntOrNull() ?: 0) > 0)
        }
        return Page(total, maxPer, items)
    }

    private fun readShortEpg(reader: JsonReader): List<ShortEpgEntry> {
        val rows = ArrayList<Map<String, String>>()
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) rows.add(readScalarFields(reader)) else reader.skipValue()
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) rows.add(readScalarFields(reader)) else reader.skipValue()
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
        return rows.mapNotNull { f ->
            val start = epgTimeMs(f["start_timestamp"], f["time"]) ?: return@mapNotNull null
            val stop = epgTimeMs(f["stop_timestamp"], f["time_to"]) ?: return@mapNotNull null
            val title = f["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ShortEpgEntry(title, f["descr"]?.takeIf { it.isNotBlank() }, start, stop)
        }
    }

    /**
     * `?type=itv&action=get_epg_info&period=<days>` → **the whole portal guide**, streamed.
     *
     * This is the call STBEmu and TiviMate make, and the reason their users see a full guide and a
     * working catch-up list on portals where we showed only now/next. It was avoided here as an OOM
     * risk, which it is *if the reply is collected into a list* — a week of a thousand channels is
     * hundreds of thousands of programmes. It is not a risk when the reply is never held: [onEntry]
     * is called as each programme is read, so the caller can write in batches and nothing bigger than
     * one programme is alive at a time. Exactly how the Xtream catalog lists are already handled.
     *
     * Both payload shapes are accepted, because portals disagree: an object keyed by channel id
     * (`{"data":{"1234":[…]}}` or `{"1234":[…]}`), or one flat array whose entries carry `ch_id`.
     * Entries with no readable start/stop or no title are skipped rather than failing the crawl.
     */
    open suspend fun streamEpgInfo(
        apiBase: String, mac: String, token: String, userAgent: String?, periodDays: Int,
        onEntry: suspend (channelId: String, entry: ShortEpgEntry) -> Unit,
    ) {
        val url = "$apiBase?type=itv&action=get_epg_info&period=$periodDays&JsHttpRequest=1-xml"
        request(url, mac, token, userAgent) { reader -> readEpgInfo(reader, onEntry) }
    }

    /**
     * Download the whole portal guide to [dest] **without parsing a byte of it**, returning the size.
     *
     * Written this way because of what a real portal did. The guide itself is quick — a measured
     * 9 MB in about a second for a 12 000-channel panel — but the first attempt parsed it as it
     * arrived and wrote each batch to the database with the response still open. Holding a
     * `Connection: keep-alive` socket idle while SQLite works invites the far end to hang up, and
     * that is exactly what happened: `unexpected end of stream`, no guide, every time.
     *
     * Taking the bytes at full speed and closing the connection *before* any parsing begins removes
     * the interaction altogether. A few megabytes on disk costs nothing, and the file is deleted as
     * soon as it has been read — unlike the XMLTV cache, nothing here is kept.
     *
     * [MAX_GUIDE_BYTES] stops a portal that answers with something absurd from filling the device.
     */
    open suspend fun downloadEpgInfo(
        apiBase: String, mac: String, token: String, userAgent: String?, periodDays: Int, dest: File,
    ): Long {
        val url = "$apiBase?type=itv&action=get_epg_info&period=$periodDays&JsHttpRequest=1-xml"
        return requestToFile(url, mac, token, userAgent, dest)
    }

    /** Parse a guide previously fetched by [downloadEpgInfo]. No network, no time limit. */
    open suspend fun parseEpgInfoFile(file: File, onEntry: suspend (channelId: String, entry: ShortEpgEntry) -> Unit) {
        file.inputStream().buffered().use { input -> parseEnvelope(input) { reader -> readEpgInfo(reader, onEntry) } }
    }

    private suspend fun readEpgInfo(reader: JsonReader, onEntry: suspend (String, ShortEpgEntry) -> Unit) {
        when (reader.peek()) {
            // One flat array — each entry names its own channel.
            JsonToken.BEGIN_ARRAY -> readEpgArray(reader, keyedChannelId = null, onEntry)
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    when {
                        // `{"data": …}` — one level of wrapping, then the same two shapes again.
                        name == "data" -> readEpgInfo(reader, onEntry)
                        // Any other key that holds an array is a channel id → its programmes.
                        reader.peek() == JsonToken.BEGIN_ARRAY -> readEpgArray(reader, keyedChannelId = name, onEntry)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }

    private suspend fun readEpgArray(
        reader: JsonReader, keyedChannelId: String?, onEntry: suspend (String, ShortEpgEntry) -> Unit,
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
            val f = readScalarFields(reader)
            val channelId = keyedChannelId ?: f["ch_id"]?.takeIf { it.isNotBlank() } ?: continue
            val start = epgTimeMs(f["start_timestamp"], f["time"]) ?: continue
            val stop = epgTimeMs(f["stop_timestamp"], f["time_to"]) ?: continue
            val title = f["name"]?.takeIf { it.isNotBlank() } ?: continue
            onEntry(channelId, ShortEpgEntry(title, f["descr"]?.takeIf { it.isNotBlank() }, start, stop))
        }
        reader.endArray()
    }

    /** Epoch-second field first; else the portal's `yyyy-MM-dd HH:mm:ss` wall-clock form (best effort —
     *  parsed in the device zone, which matches on the overwhelmingly common same-region portals). */
    private fun epgTimeMs(epochSec: String?, wallClock: String?): Long? {
        epochSec?.toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1000 }
        val text = wallClock?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(text)?.time
        }.getOrNull()
    }

    /** `?type=vod&action=get_ordered_list&category=<id>&p=<page>` → one page of movies + paging totals. */
    suspend fun getVodPage(
        apiBase: String, mac: String, token: String, userAgent: String?, categoryId: String, page: Int,
    ): Page<VodItem> {
        val url = "$apiBase?type=vod&action=get_ordered_list&category=${enc(categoryId)}&sortby=added&p=$page&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readVodPage(it) }
    }

    /** `?type=series&action=get_ordered_list&category=<id>&p=<page>` → one page of series shows + totals. */
    suspend fun getSeriesPage(
        apiBase: String, mac: String, token: String, userAgent: String?, categoryId: String, page: Int,
    ): Page<VodItem> {
        val url = "$apiBase?type=series&action=get_ordered_list&category=${enc(categoryId)}&sortby=added&p=$page&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readVodPage(it) }
    }

    /**
     * `?type=series&action=get_ordered_list&movie_id=<show remoteId>&p=<page>` → one page of the
     * show's SEASONS, each carrying its `series` episode-number array (§1.3). Category is included
     * because some portals require it alongside movie_id.
     */
    suspend fun getSeriesSeasons(
        apiBase: String, mac: String, token: String, userAgent: String?, categoryId: String?, movieId: String, page: Int,
    ): Page<SeasonItem> {
        val cat = categoryId?.takeIf { it.isNotBlank() }?.let { "&category=${enc(it)}" } ?: ""
        val url = "$apiBase?type=series&action=get_ordered_list$cat&movie_id=${enc(movieId)}&p=$page&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { readSeasonPage(it) }
    }

    private fun readSeasonPage(reader: JsonReader): Page<SeasonItem> {
        var total = 0
        var maxPer = 0
        val items = ArrayList<SeasonItem>()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return Page(0, 0, items) }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "total_items" -> total = nextIntLenient(reader) ?: 0
                "max_page_items" -> maxPer = nextIntLenient(reader) ?: 0
                "data" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) readSeasonItem(reader)?.let { items.add(it) }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Page(total, maxPer, items)
    }

    /** Like [readScalarFields] but also captures the row's `series` episode-number array. */
    private fun readSeasonItem(reader: JsonReader): SeasonItem? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        val fields = LinkedHashMap<String, String>()
        val episodes = ArrayList<Int>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when {
                name == "series" && reader.peek() == JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) nextIntLenient(reader)?.let { episodes.add(it) }
                    reader.endArray()
                }
                else -> when (reader.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> fields[name] = reader.nextString()
                    JsonToken.BOOLEAN -> fields[name] = reader.nextBoolean().toString()
                    JsonToken.NULL -> reader.nextNull()
                    else -> reader.skipValue()
                }
            }
        }
        reader.endObject()
        val id = fields["id"]?.takeIf { it.isNotBlank() } ?: return null
        return SeasonItem(
            id = id,
            name = fields["name"]?.takeIf { it.isNotBlank() },
            cmd = fields["cmd"]?.takeIf { it.isNotBlank() },
            seasonNumber = seasonNumberOf(id, fields["name"]),
            episodes = episodes.distinct().sorted(),
        )
    }

    private fun readGenreArray(reader: JsonReader): List<Genre> {
        val out = ArrayList<Genre>()
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                val fields = readScalarFields(reader)
                val id = fields["id"] ?: continue
                // "*" is the portal's synthetic "All" category — get_ordered_list with it returns everything.
                out.add(Genre(id, fields["title"] ?: fields["name"] ?: id))
            }
            reader.endArray()
        } else {
            reader.skipValue()
        }
        return out
    }

    private fun readVodPage(reader: JsonReader): Page<VodItem> {
        var total = 0
        var maxPer = 0
        val items = ArrayList<VodItem>()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return Page(0, 0, items) }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "total_items" -> total = nextIntLenient(reader) ?: 0
                "max_page_items" -> maxPer = nextIntLenient(reader) ?: 0
                "data" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) readVodItem(reader)?.let { items.add(it) }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Page(total, maxPer, items)
    }

    private fun readVodItem(reader: JsonReader): VodItem? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        // Nested fields (the series episode list, actors arrays) are skipped by readScalarFields — the
        // show/movie row only needs the scalar metadata; episodes are fetched lazily on open (Phase D-2).
        val f = readScalarFields(reader)
        val id = f["id"]?.takeIf { it.isNotBlank() } ?: return null
        return VodItem(
            id = id,
            name = f["name"] ?: f["o_name"] ?: id,
            cmd = f["cmd"]?.takeIf { it.isNotBlank() },
            poster = (f["screenshot_uri"] ?: f["poster"] ?: f["cover"])?.takeIf { it.isNotBlank() && it != "0" },
            year = parseYear(f["year"]),
            plot = f["description"]?.takeIf { it.isNotBlank() },
            rating = (f["rating_imdb"] ?: f["rating"])?.trim()?.toDoubleOrNull()?.takeIf { it > 0 },
            categoryId = (f["category_id"] ?: f["category"])?.takeIf { it.isNotBlank() && it != "*" },
            addedAt = parseAddedAt(f["added"]),
        )
    }

    private fun parseYear(raw: String?): Int? {
        val m = raw?.let { Regex("\\d{4}").find(it) } ?: return null
        return m.value.toIntOrNull()?.takeIf { it in 1900..2100 }
    }

    /**
     * Ministra's `video.added` → epoch ms. It is a portal-local datetime STRING
     * (`YYYY-MM-DD HH:MM:SS`, sometimes date-only), not an epoch — but a few panels do return a
     * numeric epoch, so both are accepted. Interpreted in the device timezone: the portal's own
     * offset is not exposed, and the value is only ever used to order items relative to each other.
     * Anything unparseable is null ("unknown"), which sorts last.
     */
    private fun parseAddedAt(raw: String?): Long? {
        val v = raw?.trim()?.takeIf { it.isNotBlank() && it != "0" } ?: return null
        // Numeric epoch (seconds or already-ms) — same guard the Xtream path uses.
        v.toLongOrNull()?.let { n ->
            return n.takeIf { it > 0 }?.let { if (it < 10_000_000_000L) it * 1000L else it }
        }
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?)?").find(v) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        return runCatching {
            java.util.GregorianCalendar(
                y.toInt(), mo.toInt() - 1, d.toInt(),
                h.toIntOrNull() ?: 0, mi.toIntOrNull() ?: 0, sec.toIntOrNull() ?: 0,
            ).apply { set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis.takeIf { it > 0 }
        }.getOrNull()
    }

    /**
     * `?type=itv&action=get_ordered_list&genre=<id>&p=<page>` → one page of live channels plus the
     * paging totals. Pages are small (max_page_items, typically ~14) so a page is parsed into a list.
     */
    suspend fun getLiveChannelsPage(
        apiBase: String, mac: String, token: String, userAgent: String?, genreId: String, page: Int,
    ): Page<Channel> {
        val url = "$apiBase?type=itv&action=get_ordered_list&genre=${enc(genreId)}&p=$page&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { reader -> readChannelPage(reader) }
    }

    /**
     * `?type=itv&action=get_all_channels` → the ENTIRE live list in one response (no paging). Far
     * faster than walking `get_ordered_list` per genre when a portal has thousands of channels served
     * ~14 per page. Some portals cap/deny it, so the caller falls back to per-genre paging on failure
     * or an empty result. Each channel still carries `tv_genre_id`, so categories map the same way.
     */
    suspend fun getAllChannels(apiBase: String, mac: String, token: String, userAgent: String? = null): List<Channel> {
        val url = "$apiBase?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        return request(url, mac, token, userAgent) { reader -> readAllChannels(reader) }
    }

    private fun readAllChannels(reader: JsonReader): List<Channel> {
        val out = ArrayList<Channel>()
        when (reader.peek()) {
            // js can be the data array directly, or {"data":[...]} (sometimes with total_items).
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) readChannel(reader)?.let { out.add(it) }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) readChannel(reader)?.let { out.add(it) }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
        return out
    }

    /**
     * `?type=itv&action=create_link&cmd=<cmd>` → the real, short-lived stream URL (the leading
     * `ffmpeg `/`auto ` prefix stripped). This is the one call that mints a playable URL (§1.4);
     * resolve immediately before playback, never at sync.
     */
    open suspend fun createLink(
        apiBase: String, mac: String, token: String, userAgent: String?, cmd: String,
        // "itv" for live; "vod" for movies AND series episodes (§1.4). [episode] is the series episode
        // number minted into the URL via `series=<ep>` — null for live/movies.
        type: String = "itv", episode: Int? = null,
    ): String {
        val seriesParam = episode?.let { "&series=$it" } ?: ""
        val url = "$apiBase?type=${enc(type)}&action=create_link&cmd=${enc(cmd)}$seriesParam&forced_storage=0&disable_ad=0&JsHttpRequest=1-xml"
        val js = request(url, mac, token, userAgent) { readScalarFields(it) }
        val raw = js["cmd"]?.takeIf { it.isNotBlank() }
            ?: throw StalkerAuthException("Portal create_link returned no cmd — token may have expired")
        val resolved = stripCmdPrefix(raw)
        Log.i(TAG, "create_link inCmd=${redactForLog(cmd)} rawOut=${redactForLog(raw)} resolved=${redactForLog(resolved)}")
        return resolved
    }

    /** Mask token-ish query params so the resolved URL can be logged for debugging without leaking creds. */
    private fun redactForLog(s: String): String =
        s.replace(Regex("(?i)(token|password|pass)=[^&]*"), "$1=***")

    private fun readChannelPage(reader: JsonReader): Page<Channel> {
        var total = 0
        var maxPer = 0
        val items = ArrayList<Channel>()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return Page(0, 0, items) }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "total_items" -> total = nextIntLenient(reader) ?: 0
                "max_page_items" -> maxPer = nextIntLenient(reader) ?: 0
                "data" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) readChannel(reader)?.let { items.add(it) }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Page(total, maxPer, items)
    }

    private fun readChannel(reader: JsonReader): Channel? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        val f = readScalarFields(reader)
        val id = f["id"]?.takeIf { it.isNotBlank() } ?: return null
        val cmd = f["cmd"]?.takeIf { it.isNotBlank() } ?: return null
        return Channel(
            id = id,
            name = f["name"] ?: f["title"] ?: id,
            number = f["number"]?.takeIf { it.isNotBlank() },
            cmd = cmd,
            logo = f["logo"]?.takeIf { it.isNotBlank() },
            xmltvId = f["xmltv_id"]?.takeIf { it.isNotBlank() },
            genreId = f["tv_genre_id"]?.takeIf { it.isNotBlank() },
            archive = hasArchive(f),
            archiveDuration = archiveDays(f["tv_archive_duration"]),
        )
    }


    /**
     * GET a portal URL with the MAG headers, unwrap the `{"js": <payload>}` envelope, and hand the
     * reader — positioned at the `js` value — to [parseJs]. `{"js": false/null/""}` (the portal's
     * "not authorized / token dead" shape) throws [StalkerAuthException] (§5.2: "re-handshake").
     */
    /**
     * GET [url] straight to [dest], returning the bytes written. Same headers and cancellation as
     * [request]; the difference is that nothing is parsed while the connection is open — see
     * [downloadEpgInfo] for why that matters.
     */
    private suspend fun requestToFile(
        url: String, mac: String, token: String?, userAgent: String?, dest: File,
    ): Long = withContext(Dispatchers.IO) {
        val call = client.newCall(portalRequest(url, mac, token, userAgent))
        val coroutineContext = currentCoroutineContext()
        val startedAt = SystemClock.elapsedRealtime()
        val safeUrl = url.substringBefore('?')
        val cancellationHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code, safeUrl)
                dest.parentFile?.mkdirs()
                var written = 0L
                response.body.byteStream().use { input ->
                    dest.outputStream().buffered().use { out ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_GUIDE_BYTES) {
                                throw IOException("Portal guide exceeded $MAX_GUIDE_BYTES bytes")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                Log.i(TAG, "guide download url=$safeUrl bytes=$written ms=${SystemClock.elapsedRealtime() - startedAt}")
                written
            }
        } finally {
            cancellationHook?.dispose()
        }
    }

    /** The MAG request every portal call is made with — headers in exactly one place. */
    private fun portalRequest(url: String, mac: String, token: String?, userAgent: String?): Request {
        val referer = "${portalRoot(url.substringBefore('?'))}/c/"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_MAG_USER_AGENT)
            .header("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${TimeZone.getDefault().id}")
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", referer)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    private suspend fun <T> request(
        url: String, mac: String, token: String?, userAgent: String?, parseJs: suspend (JsonReader) -> T,
    ): T = withContext(Dispatchers.IO) {
        val referer = "${portalRoot(url.substringBefore('?'))}/c/"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_MAG_USER_AGENT)
            .header("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${TimeZone.getDefault().id}")
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", referer)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val httpRequest = builder.build()

        val coroutineContext = currentCoroutineContext()
        val startedAt = SystemClock.elapsedRealtime()
        val safeUrl = url.substringBefore('?')
        val action = url.substringAfter("action=", "").substringBefore('&')
        val call = client.newCall(httpRequest)
        val cancellationHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        Log.d(TAG, "GET start url=$safeUrl action=$action")
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code, safeUrl)
                val body = response.body
                body.byteStream().use { input ->
                    parseEnvelope(input, parseJs).also {
                        Log.d(TAG, "GET done url=$safeUrl action=$action totalMs=${SystemClock.elapsedRealtime() - startedAt}")
                    }
                }
            }
        } finally {
            cancellationHook?.dispose()
        }
    }

    /**
     * Find the `js` field of the top-level object and run [parseJs] on its value.
     *
     * [parseJs] is a *suspending* function so that a reader can do real work per item — the guide
     * crawl writes each batch to the database as it parses, and therefore never holds the whole
     * payload. Callers that just build a value pass an ordinary lambda and are unaffected.
     */
    private suspend fun <T> parseEnvelope(input: InputStream, parseJs: suspend (JsonReader) -> T): T {
        JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
            reader.isLenient = true // some portals prefix/pad the JSON
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Portal response is not JSON (got ${reader.peek()})")
            reader.beginObject()
            var result: T? = null
            var found = false
            while (reader.hasNext()) {
                if (reader.nextName() == "js") {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> { result = parseJs(reader); found = true }
                        // {"js":false} / {"js":null} / {"js":""} — token expired / MAC not authorized.
                        else -> { reader.skipValue(); throw StalkerAuthException("Portal returned an empty payload — token expired or MAC not authorized") }
                    }
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            if (!found) throw IOException("Portal response has no \"js\" payload")
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }

    private fun nextIntLenient(reader: JsonReader): Int? = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextInt()
        JsonToken.STRING -> reader.nextString().toIntOrNull()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }

    private fun readScalarFields(reader: JsonReader): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING -> fields[name] = reader.nextString()
                JsonToken.NUMBER -> fields[name] = reader.nextString()
                JsonToken.BOOLEAN -> fields[name] = reader.nextBoolean().toString()
                JsonToken.NULL -> reader.nextNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return fields
    }

    /** URL-encode a query value (the `cmd` carries `/`, `:`, spaces the portal must receive intact). */
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "StalkerClient"

        /** A season row's number: the id suffix ("280:2"), 0 for Specials, else a trailing number in its name. */
        internal fun seasonNumberOf(id: String, name: String?): Int? {
            // "280:2" — the suffix after ':' is the season on classic portals.
            id.substringAfterLast(':', "").toIntOrNull()?.let { return it }
            // A "Specials" season is season 0 (#228). Without this it had no number, took its list position,
            // and — listed first — merged into Season 1.
            if (name != null && SPECIALS.containsMatchIn(name)) return 0
            // Else a trailing number in the name ("Season 2", "S02").
            return name?.let { Regex("(\\d+)\\s*$").find(it.trim())?.value?.toIntOrNull() }
        }

        /** "Specials", "Special", "Season 0 - Specials": the provider's season 0. */
        private val SPECIALS = Regex("""\bspecials?\b""", RegexOption.IGNORE_CASE)


        /** Ministra states an archive length in hours; the app stores and shows days. */
        private const val HOURS_PER_DAY = 24

        /** Copy buffer for the guide download — big enough that a 9 MB reply is a few hundred reads. */
        private const val DOWNLOAD_BUFFER = 64 * 1024

        /** A portal guide larger than this is not a guide; refuse it rather than fill the device. */
        private const val MAX_GUIDE_BYTES = 192L * 1024 * 1024

        /** Classic MAG-box UA most portals accept (§1.1); overridable per source (MAG254/270/420 presets in Phase B). */
        const val DEFAULT_MAG_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Safari/533.3"

        internal fun profileUrl(apiBase: String, identity: StalkerDeviceIdentity): String = buildString {
            append(apiBase)
            append("?type=stb&action=get_profile&hd=1&auth_second_step=")
            append(if (identity.hasAny) '1' else '0')
            identity.serialNumber?.takeIf { it.isNotBlank() }?.let { append("&sn=${URLEncoder.encode(it, "UTF-8")}") }
            identity.deviceId?.takeIf { it.isNotBlank() }?.let { append("&device_id=${URLEncoder.encode(it, "UTF-8")}") }
            identity.deviceId2?.takeIf { it.isNotBlank() }?.let { append("&device_id2=${URLEncoder.encode(it, "UTF-8")}") }
            identity.signature?.takeIf { it.isNotBlank() }?.let { append("&signature=${URLEncoder.encode(it, "UTF-8")}") }
            append("&JsHttpRequest=1-xml")
        }

        /**
         * Which exception a non-2xx portal response deserves — the one place that decides whether a
         * status means "your token is dead" or "you are asking too fast".
         *
         * **Only 401 is an auth failure.** 403 used to be lumped in with it, and that was wrong for
         * the portals people actually use: Ministra and its reseller panels answer 403 when a MAC has
         * too many connections open or is being crawled faster than the panel likes, which is a
         * *throttle*, not a logout. Treating it as auth made every 403 tear down a perfectly good
         * session and handshake again — the worst possible reply to "slow down", and the cause of the
         * 403 storms and endless loading reported after 4.2.4 removed the pause between page fetches.
         *
         * A token that genuinely died still reaches us either as 401 or, far more commonly, as the
         * portal's own `{"js":false}` body, which [parseEnvelope] already turns into a
         * [StalkerAuthException] — so nothing is lost by letting 403 back off and retry instead.
         */
        /**
         * Whether this channel has a catch-up archive.
         *
         * `tv_archive` is **Xtream's** field name and Ministra does not send it — a portal channel carries
         * `enable_tv_archive` and `archive` instead. Reading only the Xtream name meant every Stalker
         * channel was recorded as having no archive, which is why catch-up never appeared on a portal even
         * when the portal offered it: on the test portal, 427 of 11 545 channels have it.
         *
         * All three are accepted, because panels differ and any one of them saying yes is a yes.
         */
        internal fun hasArchive(f: Map<String, String>): Boolean =
            listOf("enable_tv_archive", "archive", "tv_archive")
                .any { (f[it]?.trim()?.toIntOrNull() ?: 0) > 0 }

        /**
         * How far back the archive goes, in **days**, from Ministra's `tv_archive_duration` — which is in
         * **hours**. The test portal reports 24, 48, 72 and 168: every value a multiple of 24, which is
         * one, two, three and seven days. Stored as days because that is what `ChannelEntity.catchupDays`
         * and the catch-up picker mean by it; keeping the raw hours there would have offered a 72-day
         * archive on a three-day one.
         *
         * A value below 24 is taken at face value as days: a panel reporting "7" plainly means a week, and
         * seven hours of archive is not a thing anyone sells.
         */
        internal fun archiveDays(raw: String?): Int {
            val value = raw?.trim()?.toIntOrNull() ?: return 0
            if (value <= 0) return 0
            return if (value < HOURS_PER_DAY) value else value / HOURS_PER_DAY
        }

        internal fun httpFailure(code: Int, safeUrl: String): IOException =
            if (code == 401) StalkerAuthException("Portal rejected the request (HTTP $code)")
            else StalkerHttpException(code, "HTTP $code for $safeUrl")

        /** Play-command prefixes minted by `create_link` (§1.4) — everything after them is the playable URL. */
        private val CMD_PREFIXES = listOf("ffmpeg ", "ffrt2 ", "ffrt3 ", "ffrt ", "auto ")

        /**
         * Canonicalize a MAC to `AA:BB:CC:DD:EE:FF`. Accepts `aabbccddeeff`, `aa-bb-…`, `aa.bb.…`
         * or colon form, any case. Returns null if it isn't 12 alphanumeric characters.
         *
         * Deliberately NOT restricted to hex: several Stalker panels hand out "virtual" MACs that
         * contain letters past F (e.g. …:PQ). The portal only ever echoes the string back to itself,
         * so rejecting them locked real users out for no protocol reason. Only the 12-symbol shape
         * is enforced, which still catches typos and truncated pastes.
         */
        fun canonicalizeMac(raw: String): String? {
            val body = raw.trim().replace(Regex("[:\\-. ]"), "")
            if (body.length != 12 || !body.all { it.isDigit() || it.lowercaseChar() in 'a'..'z' }) return null
            return body.uppercase().chunked(2).joinToString(":")
        }

        /** Strip the `/c/` UI path and trailing slashes off whatever the user pasted → the portal root. */
        fun portalRoot(input: String): String {
            var root = input.trim().trimEnd('/')
            if (root.endsWith("/c", ignoreCase = true)) root = root.dropLast(2).trimEnd('/')
            return root
        }

        /**
         * API-endpoint candidates for a portal URL, in probe order (§1.1). A pasted direct `.php`
         * endpoint is honored first; otherwise try `portal.php`, then the stalker_portal layouts.
         */
        fun apiCandidates(input: String): List<String> {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.endsWith(".php", ignoreCase = true)) {
                return listOf(trimmed) + apiCandidates(trimmed.substringBeforeLast('/')).filterNot { it == trimmed }
            }
            val root = portalRoot(trimmed)
            return listOf(
                "$root/portal.php",
                "$root/stalker_portal/server/load.php",
                "$root/server/load.php",
            )
        }

        /** True when the URL is at least parseable — the add-source form's cheap pre-check. */
        fun isValidPortalUrl(input: String): Boolean {
            val root = portalRoot(input)
            return (root.startsWith("http://", true) || root.startsWith("https://", true)) && root.toHttpUrlOrNull() != null
        }

        /** Drop the `ffmpeg `/`ffrt*`/`auto ` prefix a `create_link` cmd carries; plain URLs pass through. */
        fun stripCmdPrefix(cmd: String): String {
            val trimmed = cmd.trim()
            CMD_PREFIXES.forEach { prefix ->
                if (trimmed.startsWith(prefix, ignoreCase = true)) return trimmed.removeRange(0, prefix.length).trim()
            }
            return trimmed
        }

        /**
         * True when a prefix-stripped channel `cmd` is ALREADY a complete, playable URL (some portals
         * embed the real stream URL — mac/stream/token — directly in the channel list, e.g.
         * `…/play/live.php?mac=…&stream=12345&…`). Those must be played AS-IS: routing them through
         * `create_link` on such portals blanks the stream id (→ HTTP 405). A `localhost`/`127.0.0.1`
         * host means it's a placeholder that genuinely needs `create_link`.
         */
        fun isDirectPlayUrl(strippedCmd: String): Boolean {
            val url = strippedCmd.toHttpUrlOrNull() ?: return false
            val host = url.host
            if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return false
            // A real host + a stream/query is a ready-to-play URL. Placeholder cmds (…/ch/12345_) have
            // no query and rely on create_link.
            return url.querySize > 0 || url.encodedPath.contains(".")
        }
    }
}
