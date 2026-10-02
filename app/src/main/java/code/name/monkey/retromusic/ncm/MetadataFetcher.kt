package code.name.monkey.retromusic.ncm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 联网补全得到的结果。 */
private data class FetchedMetadata(
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val cover: ByteArray?,
)

/**
 * 元数据联网补全（三级兜底）：
 * 1. 网易云 —— 有 [NcmInfo.musicId] 时按 ID 精确查询，否则按关键词搜索；
 * 2. iTunes Search API —— 无需鉴权，兜底；
 * 3. Deezer API —— 二次兜底。
 *
 * 只补全 NCM 头里缺失的字段（title / artists / album / cover），
 * 全部失败或信息已完整时直接返回原 [NcmInfo]。
 */
object MetadataFetcher {

    private const val NETEASE_BASE = "https://music.163.com/weapi"
    private const val ITUNES_URL = "https://itunes.apple.com/search"
    private const val DEEZER_URL = "https://api.deezer.com/search"
    private const val LRCLIB_URL = "https://lrclib.net/api"

    private const val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded"
    private const val LRCLIB_USER_AGENT = "RetroMusic/1.0 (https://github.com/muntashirakon/Music)"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** 在 IO 线程补全 [original] 的缺失元数据。 */
    suspend fun enrich(original: NcmInfo): NcmInfo = withContext(Dispatchers.IO) {
        var info = original
        if (isComplete(info)) return@withContext info

        // 1. 网易云
        try {
            fetchNetease(info)?.let { info = merge(info, it) }
        } catch (_: Exception) {
        }

        // 2. iTunes
        if (!isComplete(info)) {
            try {
                buildQuery(info)?.let { fetchItunes(it) }?.let { info = merge(info, it) }
            } catch (_: Exception) {
            }
        }

        // 3. Deezer
        if (!isComplete(info)) {
            try {
                buildQuery(info)?.let { fetchDeezer(it) }?.let { info = merge(info, it) }
            } catch (_: Exception) {
            }
        }

        info
    }

    // ---- 网易云 ----

    private fun fetchNetease(info: NcmInfo): FetchedMetadata? {
        val musicId = info.musicId
        if (musicId != null && musicId > 0) {
            fetchNeteaseByMusicId(musicId)?.let { return it }
        }
        val query = buildQuery(info) ?: return null
        return fetchNeteaseByKeyword(query)
    }

    private fun fetchNeteaseByMusicId(musicId: Long): FetchedMetadata? {
        val data = mapOf(
            "c" to "[{\"id\":$musicId}]",
            "ids" to "[$musicId]"
        )
        val json = postWeApi("$NETEASE_BASE/v3/song/detail", data) ?: return null
        val song = json.optJSONArray("songs")?.optJSONObject(0) ?: return null
        return parseNeteaseSong(song)
    }

    private fun fetchNeteaseByKeyword(query: String): FetchedMetadata? {
        val data = mapOf("s" to query, "type" to 1, "limit" to 1, "offset" to 0)
        val json = postWeApi("$NETEASE_BASE/cloudsearch/pc", data) ?: return null
        val song = json.optJSONObject("result")?.optJSONArray("songs")?.optJSONObject(0) ?: return null
        return parseNeteaseSong(song)
    }

    private fun parseNeteaseSong(song: JSONObject): FetchedMetadata? {
        val title = song.optString("name").takeIf { it.isNotBlank() }
        val artists = song.optJSONArray("ar")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        } ?: emptyList()
        val album = song.optJSONObject("al")?.optString("name")?.takeIf { it.isNotBlank() }
        val coverUrl = song.optJSONObject("al")?.optString("picUrl")
            ?.replace("http://", "https://")
            ?.let { toOptimizedCoverUrl(it) }
        val cover = coverUrl?.let { downloadCover(it) }
        if (title == null && artists.isEmpty() && album == null && cover == null) return null
        return FetchedMetadata(title, artists, album, cover)
    }

    // ---- 歌词 ----

    /**
     * 联网获取歌词。返回 LRC 文本，失败返回 null。
     * 优先级：musicId 精确查询 → 网易云关键词搜索 → LRCLIB。
     */
    suspend fun fetchLyrics(info: NcmInfo): String? = withContext(Dispatchers.IO) {
        try {
            val musicId = info.musicId
            if (musicId != null && musicId > 0) {
                fetchNeteaseLyrics(musicId)?.let { return@withContext it }
            }
            val query = buildQuery(info) ?: return@withContext null
            fetchNeteaseLyricsByKeyword(query)?.let { return@withContext it }
            fetchLrclibLyrics(info)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchNeteaseLyrics(musicId: Long): String? {
        val data = mapOf("id" to musicId, "lv" to -1, "kv" to -1, "tv" to -1)
        val json = postWeApi("$NETEASE_BASE/song/lyric", data) ?: return null
        return json.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            ?: json.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }

    private fun fetchNeteaseLyricsByKeyword(query: String): String? {
        val data = mapOf("s" to query, "type" to 1, "limit" to 1, "offset" to 0)
        val json = postWeApi("$NETEASE_BASE/cloudsearch/pc", data) ?: return null
        val id = json.optJSONObject("result")?.optJSONArray("songs")
            ?.optJSONObject(0)?.optLong("id", 0L) ?: 0L
        if (id <= 0) return null
        return fetchNeteaseLyrics(id)
    }

    private fun fetchLrclibLyrics(info: NcmInfo): String? {
        val title = info.title?.takeIf { it.isNotBlank() } ?: return null
        val artist = info.artists.firstOrNull()?.takeIf { it.isNotBlank() }
        val params = StringBuilder("track_name=")
            .append(java.net.URLEncoder.encode(title, "UTF-8"))
        if (artist != null) {
            params.append("&artist_name=")
                .append(java.net.URLEncoder.encode(artist, "UTF-8"))
        }
        val arr = getJsonArray("$LRCLIB_URL/search?$params") ?: return null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val lrc = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
                ?: item.optString("plainLyrics").takeIf { it.isNotBlank() }
            if (lrc != null) return lrc
        }
        return null
    }

    // ---- iTunes ----

    private fun fetchItunes(query: String): FetchedMetadata? {
        val url = "$ITUNES_URL?term=${java.net.URLEncoder.encode(query, "UTF-8")}&media=music&entity=song&limit=5"
        val json = getJson(url) ?: return null
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val title = r.optString("trackName").takeIf { it.isNotBlank() } ?: continue
            val artist = r.optString("artistName").takeIf { it.isNotBlank() }
            val album = r.optString("collectionName").takeIf { it.isNotBlank() }
            val coverUrl = r.optString("artworkUrl100")
                .takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
                ?.replace("100x100", "600x600")
            val cover = coverUrl?.let { downloadCover(it) }
            return FetchedMetadata(title, artist?.let { listOf(it) } ?: emptyList(), album, cover)
        }
        return null
    }

    // ---- Deezer ----

    private fun fetchDeezer(query: String): FetchedMetadata? {
        val url = "$DEEZER_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=5"
        val json = getJson(url) ?: return null
        val data = json.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val t = data.optJSONObject(i) ?: continue
            val title = t.optString("title").takeIf { it.isNotBlank() } ?: continue
            val artist = t.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
            val album = t.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() }
            val coverUrl = t.optJSONObject("album")?.optString("cover_medium")?.takeIf { it.isNotBlank() }
            val cover = coverUrl?.let { downloadCover(it) }
            return FetchedMetadata(title, artist?.let { listOf(it) } ?: emptyList(), album, cover)
        }
        return null
    }

    // ---- 通用 HTTP ----

    private fun postWeApi(url: String, data: Map<String, Any>): JSONObject? {
        val text = JSONObject(data).toString()
        val (params, encSecKey) = NeteaseCrypto.weapi(text)
        val body = RequestBody.create(
            MediaType.parse(FORM_MEDIA_TYPE),
            "params=$params&encSecKey=$encSecKey"
        )
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            )
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val str = resp.body()?.string() ?: return@use null
            try {
                JSONObject(str)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val str = resp.body()?.string() ?: return@use null
            try {
                JSONObject(str)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getJsonArray(url: String): JSONArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LRCLIB_USER_AGENT)
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val str = resp.body()?.string() ?: return@use null
            try {
                JSONArray(str)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun downloadCover(url: String): ByteArray? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body()?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun toOptimizedCoverUrl(url: String): String {
        if (url.contains("param=")) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}param=500y500"
    }

    // ---- 合并 ----

    private fun isComplete(info: NcmInfo): Boolean =
        !info.title.isNullOrBlank() &&
            info.artists.isNotEmpty() &&
            !info.album.isNullOrBlank() &&
            info.cover != null

    private fun buildQuery(info: NcmInfo): String? {
        val title = info.title?.takeIf { it.isNotBlank() } ?: return null
        val artist = info.artists.firstOrNull()?.takeIf { it.isNotBlank() }
        return if (artist != null) "$title $artist" else title
    }

    private fun merge(base: NcmInfo, fetched: FetchedMetadata): NcmInfo = NcmInfo(
        format = base.format,
        title = base.title?.takeIf { it.isNotBlank() } ?: fetched.title,
        artists = base.artists.ifEmpty { fetched.artists },
        album = base.album?.takeIf { it.isNotBlank() } ?: fetched.album,
        cover = base.cover ?: fetched.cover,
        musicId = base.musicId,
    )
}
