/*
 * NCM (网易云音乐) 解密结果的元数据。
 * 移植自 xihale/unncm (MIT) 的 Rust 核心 NcmInfo 结构。
 */
package code.name.monkey.retromusic.ncm

/** 解密 .ncm 文件时抛出的异常。 */
class NcmException(message: String) : Exception(message)

/**
 * 从 .ncm 头解析出的信息。
 *
 * @param format 音频格式（如 "mp3" / "flac"，已做小写归一化，可直接作为文件扩展名）
 * @param title  歌曲标题（头里没有则为 null）
 * @param artists 歌手列表（头里没有则为空列表）
 * @param album  专辑名（头里没有则为 null）
 * @param cover  内嵌封面图片字节（没有则为 null）
 * @param musicId 网易云歌曲 ID（头里没有则为 null，用于联网精确补全元数据）
 */
data class NcmInfo(
    val format: String,
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val cover: ByteArray?,
    val musicId: Long? = null,
)
