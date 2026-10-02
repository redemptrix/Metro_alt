/*
 * NCM 转换导入：解密 + 写入标签（标题/歌手/专辑/封面）+ 输出到音乐库。
 *
 * - Android 10 (API 29) 及以上：通过 MediaStore 插入，自动进入媒体库。
 * - Android 9 及以下：写入公共 Music 目录后触发 MediaScanner 扫描。
 */
package code.name.monkey.retromusic.ncm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import code.name.monkey.retromusic.util.getExternalStoragePublicDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

object NcmConverter {

    /**
     * 转换一个 NCM 文件到音乐库。成功返回输出文件名，失败返回 null。
     */
    suspend fun convert(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val input = openStream(context, uri) ?: return@withContext null
        input.use { raw ->
            val buffered = BufferedInputStream(raw)
            if (!ensureNcm(buffered)) {
                return@withContext null
            }

            // 1. 解密到缓存临时文件
            val workDir = File(context.cacheDir, "ncm_convert").apply { mkdirs() }
            val rawFile = File.createTempFile("raw_", ".tmp", workDir)
            val decrypted = NcmDecoder.decrypt(buffered, rawFile.outputStream())
            val info = MetadataFetcher.enrich(decrypted)
            val lyrics = MetadataFetcher.fetchLyrics(info)
            val ext = info.format.ifBlank { "mp3" }

            // 2. 写入标签
            val taggedFile = File(workDir, "tagged_${System.nanoTime()}.$ext")
            writeTags(context, rawFile, taggedFile, info, lyrics)
            rawFile.delete()

            // 3. 输出到音乐库
            val artist = info.artists.joinToString(" / ").ifBlank { null }
            val title = info.title ?: baseName(uri)
            val fileName = buildOutputFileName(title, artist, ext)
            val outName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStore(context, taggedFile, fileName, ext)
            } else {
                writeToPublicMusic(context, taggedFile, fileName)
            }
            taggedFile.delete()
            outName
        }
    }

    /** 本地媒体库中的一条歌曲（用于导入去重）。 */
    data class ExistingSong(val id: Long, val title: String, val artist: String, val data: String)

    /**
     * 仅解密读取 NCM 头元数据（不联网、不写标签、不输出文件），用于导入前的重复检测。
     */
    suspend fun peekInfo(context: Context, uri: Uri): NcmInfo? = withContext(Dispatchers.IO) {
        val input = openStream(context, uri) ?: return@withContext null
        input.use { raw ->
            val buffered = BufferedInputStream(raw)
            if (!ensureNcm(buffered)) {
                return@withContext null
            }
            val tmp = File.createTempFile("peek_", ".tmp", context.cacheDir)
            try {
                NcmDecoder.decrypt(buffered, tmp.outputStream())
            } finally {
                tmp.delete()
            }
        }
    }

    /** 查询本地媒体库中已存在的歌曲（标题/歌手，用于去重）。 */
    fun existingSongs(context: Context): List<ExistingSong> {
        val result = mutableListOf<ExistingSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    result.add(
                        ExistingSong(
                            cursor.getLong(idIdx),
                            cursor.getString(titleIdx) ?: "",
                            cursor.getString(artistIdx) ?: "",
                            cursor.getString(dataIdx) ?: "",
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** 删除一条本地歌曲（MediaStore 条目 + 尽力删除文件），用于「替换」重复项。 */
    fun deleteExisting(context: Context, song: ExistingSong) {
        try {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            context.contentResolver.delete(ContentUris.withAppendedId(base, song.id), null, null)
        } catch (_: Exception) {
        }
        if (song.data.isNotBlank()) {
            try {
                File(song.data).delete()
            } catch (_: Exception) {
            }
        }
    }

    /** 校验并复位流；非 NCM 返回 false。 */
    private fun ensureNcm(buffered: BufferedInputStream): Boolean {
        buffered.mark(8)
        val magic = ByteArray(8)
        var read = 0
        while (read < magic.size) {
            val n = buffered.read(magic, read, magic.size - read)
            if (n < 0) break
            read += n
        }
        if (read != magic.size || String(magic, Charsets.ISO_8859_1) != "CTENFDAM") {
            return false
        }
        buffered.reset()
        return true
    }

    /** 写入标题/歌手/专辑/封面/歌词标签。 */
    private fun writeTags(context: Context, from: File, to: File, info: NcmInfo, lyrics: String?) {
        from.copyTo(to, overwrite = true)
        try {
            val audioFile = AudioFileIO.read(to)
            val tag = audioFile.tagOrCreateAndSetDefault
            info.title?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.TITLE, it) }
            if (info.artists.isNotEmpty()) {
                val artist = info.artists.joinToString(" / ")
                tag.setField(FieldKey.ARTIST, artist)
                tag.setField(FieldKey.ALBUM_ARTIST, artist)
            }
            info.album?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM, it) }
            lyrics?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.LYRICS, it) }
            info.cover?.let { cover ->
                val coverFile = File(context.cacheDir, "ncm_cover_${System.nanoTime()}")
                coverFile.writeBytes(cover)
                try {
                    tag.deleteArtworkField()
                    tag.setField(AndroidArtwork.createArtworkFromFile(coverFile))
                } catch (e: Exception) {
                    // 封面写入失败不影响主流程
                } finally {
                    coverFile.delete()
                }
            }
            audioFile.commit()
        } catch (e: Exception) {
            // 标签写入失败时保留未打标签的文件
        }
    }

    /** API 29+：通过 MediaStore 写入，自动进入媒体库。 */
    private fun writeToMediaStore(context: Context, tagged: File, fileName: String, ext: String): String? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType(ext))
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                tagged.inputStream().use { it.copyTo(out) }
            } ?: return null
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            fileName
        } catch (e: Exception) {
            null
        }
    }

    /** API < 29：写入公共 Music 目录并扫描。 */
    private fun writeToPublicMusic(context: Context, tagged: File, fileName: String): String? {
        return try {
            val musicDir = getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (!musicDir.exists()) musicDir.mkdirs()
            val out = File(musicDir, fileName)
            tagged.copyTo(out, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(out.absolutePath),
                null,
                null
            )
            fileName
        } catch (e: Exception) {
            null
        }
    }

    private fun buildOutputFileName(title: String, artist: String?, ext: String): String {
        val base = if (!artist.isNullOrBlank()) "$artist - $title" else title
        return "${sanitize(base)}.$ext"
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Unknown" }
    }

    private fun mimeType(ext: String): String = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        else -> "audio/mpeg"
    }

    private fun baseName(uri: Uri): String {
        val last = uri.lastPathSegment ?: return "Unknown"
        val name = last.substringAfterLast('/').substringAfterLast('\\')
        return name.substringBeforeLast('.').ifBlank { "Unknown" }
    }

    private fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == null) {
                File(uri.toString()).takeIf { it.isFile }?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            null
        }
    }
}
