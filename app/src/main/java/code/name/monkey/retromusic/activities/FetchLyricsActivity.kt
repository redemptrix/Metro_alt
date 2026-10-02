/*
 * 补歌词 Activity：联网抓取歌词（网易云 → LRCLIB）并写回音频标签。
 * 使用透明 Dialog 主题，仅以 Toast 反馈结果，完成后自动关闭。
 */
package code.name.monkey.retromusic.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsThemeActivity
import code.name.monkey.retromusic.activities.tageditor.TagWriter
import code.name.monkey.retromusic.extensions.uri
import code.name.monkey.retromusic.model.AudioTagInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.ncm.MetadataFetcher
import code.name.monkey.retromusic.ncm.NcmInfo
import code.name.monkey.retromusic.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.EnumMap

class FetchLyricsActivity : AbsThemeActivity() {

    companion object {
        const val EXTRA_SONG = "extra_song"

        fun createIntent(context: Context, song: Song): Intent =
            Intent(context, FetchLyricsActivity::class.java).putExtra(EXTRA_SONG, song)
    }

    private var song: Song? = null
    private var cacheFile: File? = null
    private lateinit var writeLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        song = intent.getParcelableExtra(EXTRA_SONG) as? Song
        if (song == null) {
            finish()
            return
        }
        writeLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                val s = song
                val cache = cacheFile
                if (it.resultCode == Activity.RESULT_OK && s != null && cache != null) {
                    FileUtils.copyFileToUri(this, cache, s.uri)
                    Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        fetchAndWrite()
    }

    private fun fetchAndWrite() {
        val s = song ?: return
        Toast.makeText(this, R.string.fetching_lyrics, Toast.LENGTH_SHORT).show()
        val info = NcmInfo(
            format = "",
            title = s.title,
            artists = listOf(s.artistName),
            album = s.albumName,
            cover = null,
            musicId = null,
        )
        GlobalScope.launch {
            val lrc = MetadataFetcher.fetchLyrics(info)
            withContext(Dispatchers.Main) {
                if (lrc.isNullOrBlank()) {
                    Toast.makeText(this@FetchLyricsActivity, R.string.lyrics_not_found, Toast.LENGTH_SHORT)
                        .show()
                    finish()
                } else {
                    write(lrc)
                }
            }
        }
    }

    private fun write(lrc: String) {
        val s = song ?: return
        val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
        fieldKeyValueMap[FieldKey.LYRICS] = lrc
        GlobalScope.launch {
            if (VersionUtils.hasR()) {
                val files = TagWriter.writeTagsToFilesR(
                    this@FetchLyricsActivity,
                    AudioTagInfo(listOf(s.data), fieldKeyValueMap, null)
                )
                withContext(Dispatchers.Main) {
                    if (files.isNotEmpty()) {
                        cacheFile = files[0]
                        val pendingIntent = MediaStore.createWriteRequest(
                            contentResolver,
                            listOf(s.uri)
                        )
                        writeLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                    } else {
                        finish()
                    }
                }
            } else {
                TagWriter.writeTagsToFiles(
                    this@FetchLyricsActivity,
                    AudioTagInfo(listOf(s.data), fieldKeyValueMap, null)
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FetchLyricsActivity, R.string.done, Toast.LENGTH_SHORT)
                        .show()
                    finish()
                }
            }
        }
    }
}
