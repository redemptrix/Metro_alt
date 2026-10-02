/*
 * NCM 导入入口：
 * - 从设置进入：打开系统文件选择器（可多选），批量转换为 MP3/FLAC 并写入音乐库。
 * - 从外部 ACTION_VIEW 打开 .ncm 文件：直接解码播放。
 */
package code.name.monkey.retromusic.activities

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.ncm.NcmConverter
import code.name.monkey.retromusic.ncm.NcmPlaybackHelper
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.theme.getNightMode
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class NcmImportActivity : AppCompatActivity() {

    companion object {
        /** 通过 intent extra 开启「选择文件夹」模式。 */
        const val EXTRA_FOLDER_MODE = "extra_folder_mode"
        /** 通过 intent extra 开启「同步网易云目录」模式（自动跳过重复）。 */
        const val EXTRA_SYNC_MODE = "extra_sync_mode"
    }

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) {
                finish()
                return@registerForActivityResult
            }
            importUris(uris)
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            importFolder(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        updateTheme()
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val data = intent?.data
        if (action == Intent.ACTION_VIEW && data != null) {
            // 外部打开：直接解码播放
            lifecycleScope.launch {
                if (!NcmPlaybackHelper.play(this@NcmImportActivity, data)) {
                    Toast.makeText(
                        this@NcmImportActivity,
                        R.string.ncm_import_none,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                finish()
            }
            return
        }

        // 同步模式：直接扫描已设置的网易云目录，自动去重
        if (intent?.getBooleanExtra(EXTRA_SYNC_MODE, false) == true) {
            syncFromConfiguredFolder()
            return
        }

        // 设置入口：根据 extra 决定打开文件多选还是文件夹选择
        if (intent?.getBooleanExtra(EXTRA_FOLDER_MODE, false) == true) {
            folderPicker.launch(null)
        } else {
            picker.launch(arrayOf("*/*"))
        }
    }

    /** 读取已设置的网易云目录并同步（找不到目录则提示）。 */
    private fun syncFromConfiguredFolder() {
        val uriStr = PreferenceUtil.neteaseFolderUri
        if (uriStr.isBlank()) {
            Toast.makeText(
                this,
                R.string.netease_sync_no_folder,
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        importFolder(Uri.parse(uriStr), autoSkipDuplicates = true)
    }

    /** 扫描所选目录（递归）下的所有 .ncm 文件并批量转换。 */
    private fun importFolder(treeUri: Uri, autoSkipDuplicates: Boolean = false) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage(getString(R.string.ncm_scanning))
            setCancelable(false)
        }
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val uris = collectNcmUris(treeUri)
            if (uris.isEmpty()) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this@NcmImportActivity,
                        R.string.ncm_folder_none,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                return@launch
            }
            // importUris 会在主线程创建 ProgressDialog，必须切回主线程调用
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                importUris(uris, autoSkipDuplicates)
            }
        }
    }

    /** 递归收集目录树下的所有 .ncm 文件。 */
    private fun collectNcmUris(treeUri: Uri): List<Uri> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        val result = mutableListOf<Uri>()
        fun walk(dir: DocumentFile) {
            for (file in dir.listFiles()) {
                when {
                    file.isDirectory -> walk(file)
                    file.isFile && file.name?.lowercase()?.endsWith(".ncm") == true ->
                        result.add(file.uri)
                }
            }
        }
        walk(root)
        return result
    }

    private enum class DuplicateAction { REPLACE, SKIP, CANCEL }

    private fun importUris(uris: List<Uri>, autoSkipDuplicates: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. 检测重复：加载本地库 + 解密读取每个文件的头信息
            val detecting = withContext(Dispatchers.Main) {
                ProgressDialog(this@NcmImportActivity).apply {
                    setMessage(getString(R.string.ncm_scanning))
                    setCancelable(false)
                    show()
                }
            }

            val byKey = HashMap<String, NcmConverter.ExistingSong>()
            for (song in NcmConverter.existingSongs(this@NcmImportActivity)) {
                byKey.putIfAbsent(dupKey(song.title, song.artist), song)
            }

            data class Item(val uri: Uri, val dup: NcmConverter.ExistingSong?)

            val items = uris.map { uri ->
                val info = NcmConverter.peekInfo(this@NcmImportActivity, uri)
                val title = info?.title?.takeIf { it.isNotBlank() } ?: fileNameOf(uri)
                val artist = info?.artists?.joinToString(" / ").orEmpty()
                Item(uri, byKey[dupKey(title, artist)])
            }

            val duplicates = items.filter { it.dup != null }

            var toConvert = items
            var skipped = 0
            if (duplicates.isNotEmpty()) {
                if (autoSkipDuplicates) {
                    withContext(Dispatchers.Main) { detecting.dismiss() }
                    toConvert = items.filter { it.dup == null }
                    skipped = duplicates.size
                } else {
                    val action = withContext(Dispatchers.Main) {
                        detecting.dismiss()
                        askReplaceOrSkip(duplicates.size)
                    }
                    when (action) {
                        DuplicateAction.CANCEL -> {
                            finish()
                            return@launch
                        }
                        DuplicateAction.SKIP -> {
                            toConvert = items.filter { it.dup == null }
                            skipped = duplicates.size
                        }
                        DuplicateAction.REPLACE -> {
                            for (item in duplicates) {
                                item.dup?.let { NcmConverter.deleteExisting(this@NcmImportActivity, it) }
                            }
                        }
                    }
                }
            } else {
                withContext(Dispatchers.Main) { detecting.dismiss() }
            }

            if (toConvert.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NcmImportActivity,
                        getString(R.string.ncm_skipped_all, skipped),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                finish()
                return@launch
            }

            // 2. 转换
            val total = toConvert.size
            val progressDialog = withContext(Dispatchers.Main) {
                ProgressDialog(this@NcmImportActivity).apply {
                    setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                    setMessage(getString(R.string.ncm_importing))
                    max = total
                    setCancelable(false)
                    show()
                }
            }

            var success = 0
            var failed = 0
            for ((index, item) in toConvert.withIndex()) {
                val fileName = item.uri.lastPathSegment?.substringAfterLast('/') ?: ""
                withContext(Dispatchers.Main) {
                    progressDialog.progress = index
                    progressDialog.setMessage(
                        getString(R.string.ncm_importing_progress, index + 1, total, fileName)
                    )
                }
                try {
                    if (NcmConverter.convert(this@NcmImportActivity, item.uri) != null) {
                        success++
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    failed++
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                val message = when {
                    failed == 0 && skipped == 0 -> getString(R.string.ncm_import_success, success)
                    failed == 0 -> getString(R.string.ncm_import_skipped, success, skipped)
                    success == 0 && skipped == 0 -> getString(R.string.ncm_import_failed, failed)
                    success == 0 -> getString(R.string.ncm_import_failed_skipped, failed, skipped)
                    skipped == 0 -> getString(R.string.ncm_import_partial, success, failed)
                    else -> getString(R.string.ncm_import_partial_skipped, success, failed, skipped)
                }
                Toast.makeText(this@NcmImportActivity, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun dupKey(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun fileNameOf(uri: Uri): String {
        val last = uri.lastPathSegment ?: return "Unknown"
        val name = last.substringAfterLast('/').substringAfterLast('\\')
        return name.substringBeforeLast('.').ifBlank { "Unknown" }
    }

    private suspend fun askReplaceOrSkip(count: Int): DuplicateAction =
        suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ncm_duplicate_title)
                .setMessage(getString(R.string.ncm_duplicate_message, count))
                .setPositiveButton(R.string.ncm_replace) { _, _ ->
                    resumeAction(cont, DuplicateAction.REPLACE)
                }
                .setNegativeButton(R.string.ncm_skip) { _, _ ->
                    resumeAction(cont, DuplicateAction.SKIP)
                }
                .setNeutralButton(android.R.string.cancel) { _, _ ->
                    resumeAction(cont, DuplicateAction.CANCEL)
                }
                .setOnCancelListener { resumeAction(cont, DuplicateAction.CANCEL) }
                .show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }

    private fun resumeAction(cont: CancellableContinuation<DuplicateAction>, action: DuplicateAction) {
        if (cont.isActive) cont.resume(action)
    }

    private fun updateTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())

        // Apply dynamic colors to activity if enabled
        if (PreferenceUtil.materialYou) {
            DynamicColors.applyToActivityIfAvailable(
                this,
                DynamicColorsOptions.Builder()
                    .setThemeOverlay(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight)
                    .build()
            )
        }
    }
}
