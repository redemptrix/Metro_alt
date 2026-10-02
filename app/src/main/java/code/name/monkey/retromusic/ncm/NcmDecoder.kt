/*
 * 纯 Kotlin 的 NCM (网易云音乐) 解密核心。
 *
 * 算法忠实移植自 xihale/unncm (MIT License) 的 Rust 实现 core/src/core.rs，
 * 保证与官方 ncmdump 解密结果一致。
 *
 * 只依赖 JDK (javax.crypto / java.io) 与 Android 内置的
 * android.util.Base64 / org.json，无第三方依赖，minSdk 21 即可使用。
 */
package code.name.monkey.retromusic.ncm

import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NcmDecoder {

    private const val MAGIC_HEADER = "CTENFDAM"

    // 16 字节 AES 密钥："hzHRAmso5kInbaxW"
    private val KEY_CORE = hexToBytes("687A4852416D736F356B496E62617857")

    // 16 字节元数据 AES 密钥："#14ljk_!\\]&0U<'("
    private val KEY_META = hexToBytes("2331346C6A6B5F215C5D2630553C2728")

    private const val KEY_PREFIX_LEN = 17 // "neteasecloudmusic"
    private const val META_PREFIX_LEN = 22 // "163 key(Don't modify):"

    private const val MAX_KEY_BYTES = 1024L * 1024L
    private const val MAX_META_BYTES = 16L * 1024L * 1024L
    private const val MAX_COVER_BYTES = 32L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 256 * 1024

    /**
     * 解密一个 .ncm 流，把原始音频写入 [output]，并返回头里的元数据。
     * 会消耗 [input]（读完音频为止），但不会关闭 [input] / [output]。
     */
    fun decrypt(input: InputStream, output: OutputStream): NcmInfo {
        val magic = ByteArray(MAGIC_HEADER.length)
        readFully(input, magic)
        if (String(magic, Charsets.ISO_8859_1) != MAGIC_HEADER) {
            throw NcmException("不是有效的 NCM 文件（magic header 不匹配）")
        }
        skipFully(input, 2)

        val keyLen = readU32Le(input).also {
            requireRange(it, 1L, MAX_KEY_BYTES, "密钥长度非法")
        }.toInt()
        val keyData = ByteArray(keyLen)
        readFully(input, keyData)
        for (i in keyData.indices) {
            keyData[i] = (keyData[i].toInt() xor 0x64).toByte()
        }
        val keyPlain = aesEcbDecryptPkcs7(keyData, KEY_CORE)
        if (keyPlain.size <= KEY_PREFIX_LEN) {
            throw NcmException("解密后的密钥过短")
        }
        val streamKey = keyPlain.copyOfRange(KEY_PREFIX_LEN, keyPlain.size)
        val keyBox = buildKeyBox(streamKey)

        val metaLen = readU32Le(input).also {
            requireRange(it, 0L, MAX_META_BYTES, "元数据长度非法")
        }.toInt()
        // 元数据是可选/可能损坏的，读取失败不应阻断解密音频。
        val metadata = if (metaLen == 0) {
            null
        } else {
            val metaData = ByteArray(metaLen)
            readFully(input, metaData)
            try {
                decryptMetadata(metaData)
            } catch (e: Exception) {
                null
            }
        }

        skipFully(input, 5)

        val coverSpace = readU32Le(input).also {
            requireRange(it, 0L, MAX_COVER_BYTES, "封面预留空间非法")
        }.toInt()
        val coverLen = readU32Le(input).also {
            requireRange(it, 0L, MAX_COVER_BYTES, "封面数据长度非法")
        }.toInt()
        if (coverLen > coverSpace) {
            throw NcmException("封面数据长度超过预留空间")
        }
        val cover = if (coverLen == 0) null else ByteArray(coverLen).also { readFully(input, it) }
        skipFully(input, coverSpace - coverLen)

        decryptAudio(input, output, keyBox)
        output.flush()

        return NcmInfo(
            format = sanitizeFormat(metadata?.optString("format") ?: ""),
            title = metadata?.optString("musicName")?.takeIf { it.isNotBlank() },
            artists = parseArtists(metadata),
            album = metadata?.optString("album")?.takeIf { it.isNotBlank() },
            cover = cover,
            musicId = parseMusicId(metadata),
        )
    }

    /** 解析元数据 JSON 里的 artist: [[name, id], ...]。 */
    private fun parseArtists(metadata: JSONObject?): List<String> {
        val arr = metadata?.optJSONArray("artist") ?: return emptyList()
        val result = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONArray(i) ?: continue
            val name = entry.optString(0)
            if (name.isNotBlank()) result.add(name)
        }
        return result
    }

    /** 从元数据 JSON 里解析网易云歌曲 ID（数字或字符串都可能出现）。 */
    private fun parseMusicId(metadata: JSONObject?): Long? {
        if (metadata == null) return null
        val asLong = metadata.optLong("musicId", -1L)
        if (asLong > 0) return asLong
        return metadata.optString("musicId").toLongOrNull()?.takeIf { it > 0 }
    }

    /** 解密并解析元数据 JSON。 */
    private fun decryptMetadata(data: ByteArray): JSONObject {
        if (data.size < META_PREFIX_LEN) {
            throw NcmException("元数据块过短")
        }
        val payload = data.copyOfRange(META_PREFIX_LEN, data.size)
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor 0x63).toByte()
        }
        val encrypted = Base64.decode(payload, Base64.NO_WRAP)
        val plain = aesEcbDecryptPkcs7(encrypted, KEY_META)
        val plainStr = String(plain, Charsets.UTF_8)
        val colon = plainStr.indexOf(':')
        if (colon < 0) {
            throw NcmException("元数据缺少 JSON 前缀分隔符")
        }
        return JSONObject(plainStr.substring(colon + 1))
    }

    /** 生成 256 字节的解密异或表。 */
    private fun buildKeyBox(key: ByteArray): IntArray {
        if (key.isEmpty()) {
            throw NcmException("空的流密钥")
        }
        val box = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (box[i] + j + (key[i % key.size].toInt() and 0xff)) and 0xff
            val tmp = box[i]
            box[i] = box[j]
            box[j] = tmp
        }
        val keyBox = IntArray(256)
        for (i in 0 until 256) {
            val next = (i + 1) and 0xff
            val first = box[next]
            val second = box[(next + first) and 0xff]
            keyBox[i] = box[(first + second) and 0xff]
        }
        return keyBox
    }

    /** 分块异或解密音频数据。 */
    private fun decryptAudio(input: InputStream, output: OutputStream, keyBox: IntArray) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var offset = 0
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            for (i in 0 until count) {
                buffer[i] = (buffer[i].toInt() xor keyBox[(offset + i) and 0xff]).toByte()
            }
            output.write(buffer, 0, count)
            offset += count
        }
    }

    /** AES-128-ECB（NoPadding）+ PKCS#7 去填充。 */
    private fun aesEcbDecryptPkcs7(data: ByteArray, key: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % 16 != 0) {
            throw NcmException("AES 数据未按 16 字节对齐")
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val plain = cipher.doFinal(data)
        return unpadPkcs7(plain)
    }

    private fun unpadPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            throw NcmException("缺少 PKCS#7 填充")
        }
        val padding = data[data.size - 1].toInt() and 0xff
        if (padding == 0 || padding > 16 || padding > data.size) {
            throw NcmException("PKCS#7 填充非法")
        }
        for (i in data.size - padding until data.size) {
            if ((data[i].toInt() and 0xff) != padding) {
                throw NcmException("PKCS#7 填充校验失败")
            }
        }
        return data.copyOf(data.size - padding)
    }

    /** 音频格式归一化：小写，空值默认 mp3。 */
    private fun sanitizeFormat(format: String): String {
        return format.lowercase().trim().ifBlank { "mp3" }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = hex.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }

    /** 读取一个 little-endian 的 u32，返回非负 Long。 */
    private fun readU32Le(input: InputStream): Long {
        val b = ByteArray(4)
        readFully(input, b)
        return (b[0].toLong() and 0xff) or
            ((b[1].toLong() and 0xff) shl 8) or
            ((b[2].toLong() and 0xff) shl 16) or
            ((b[3].toLong() and 0xff) shl 24)
    }

    private fun requireRange(value: Long, min: Long, max: Long, message: String) {
        if (value < min || value > max) {
            throw NcmException(message)
        }
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var offset = 0
        while (offset < dest.size) {
            val n = input.read(dest, offset, dest.size - offset)
            if (n < 0) throw NcmException("文件意外结束")
            offset += n
        }
    }

    private fun skipFully(input: InputStream, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() < 0) throw NcmException("文件意外结束")
                remaining--
            } else {
                remaining -= n
            }
        }
    }
}
