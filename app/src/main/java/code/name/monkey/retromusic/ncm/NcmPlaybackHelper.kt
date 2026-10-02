/*
 * NCM 直接播放辅助：把 .ncm 解密到缓存临时文件，并构造可直接播放的 Song。
 */
package code.name.monkey.retromusic.ncm

import android.content.Context
import android.net.Uri
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jaudiotagger.audio.AudioFileIO
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/** 解密结果：元数据 + 解密后的临时音频文件。 */
data class NcmDecrypted(val info: NcmInfo, val file: File)

object NcmPlaybackHelper {

    /** 判断一个 Uri 是否指向 NCM 文件（按 magic header 判定，最可靠）。 */
    fun isNcm(context: Context, uri: Uri): Boolean {
        return openStream(context, uri)?.use { input ->
            val buffered = BufferedInputStream(input)
            buffered.mark(8)
            val magic = ByteArray(8)
            var read = 0
            while (read < magic.size) {
                val n = buffered.read(magic, read, magic.size - read)
                if (n < 0) break
                read += n
            }
            read == magic.size && String(magic, Charsets.ISO_8859_1) == "CTENFDAM"
        } ?: false
    }

    /** 解密到缓存临时文件（带正确扩展名），不是 NCM 时返回 null。 */
    suspend fun decryptToCache(context: Context, uri: Uri): NcmDecrypted? = withContext(Dispatchers.IO) {
        val input = openStream(context, uri) ?: return@withContext null
        input.use { raw ->
            val buffered = BufferedInputStream(raw)
            buffered.mark(8)
            val magic = ByteArray(8)
            var read = 0
            while (read < magic.size) {
                val n = buffered.read(magic, read, magic.size - read)
                if (n < 0) break
                read += n
            }
            if (read != magic.size || String(magic, Charsets.ISO_8859_1) != "CTENFDAM") {
                return@withContext null
            }
            buffered.reset()

            val dir = File(context.cacheDir, "ncm").apply { mkdirs() }
            val outFile = File.createTempFile("play_", ".tmp", dir)
            val decrypted = NcmDecoder.decrypt(buffered, outFile.outputStream())
            val info = withTimeoutOrNull(3000) { MetadataFetcher.enrich(decrypted) } ?: decrypted
            val ext = info.format.ifBlank { "mp3" }
            val renamed = File(dir, "${outFile.nameWithoutExtension}.$ext")
            if (outFile.renameTo(renamed)) outFile.delete()
            NcmDecrypted(info, if (renamed.exists()) renamed else outFile)
        }
    }

    /** 直接播放一个 NCM Uri：解密到缓存后立即播放，不写入音乐库。 */
    suspend fun play(context: Context, uri: Uri): Boolean {
        if (!isNcm(context, uri)) return false
        val decrypted = decryptToCache(context, uri) ?: return false
        withContext(Dispatchers.Main) {
            MusicPlayerRemote.openQueue(listOf(buildSong(decrypted)), 0, true)
        }
        return true
    }

    /** 由解密结果构造一个可播放的合成 Song（id < 0，播放时使用文件路径）。 */
    fun buildSong(decrypted: NcmDecrypted): Song {
        val info = decrypted.info
        val duration = try {
            AudioFileIO.read(decrypted.file).audioHeader.trackLength * 1000L
        } catch (e: Exception) {
            0L
        }
        return Song(
            id = -1,
            title = info.title ?: decrypted.file.nameWithoutExtension,
            trackNumber = -1,
            year = -1,
            duration = duration,
            data = decrypted.file.absolutePath,
            dateModified = decrypted.file.lastModified(),
            albumId = -1,
            albumName = info.album ?: "",
            artistId = -1,
            artistName = info.artists.joinToString(" / "),
            composer = "",
            albumArtist = info.artists.firstOrNull() ?: ""
        )
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
