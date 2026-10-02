package code.name.monkey.retromusic.ncm

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi 请求加密。
 *
 * 采用「固定 SECRET_KEY + 预计算 ENC_SEC_KEY」的简化实现（与 xihale/unncm 一致），
 * 省去运行时 RSA 计算，结果与标准 weapi 完全等价。
 */
object NeteaseCrypto {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val SECRET_KEY = "a8LWv2uAtXjzSfkQ"

    // SECRET_KEY 对应的 RSA(反序(SECRET_KEY)) 十六进制结果，服务端用它解出 SECRET_KEY。
    private const val ENC_SEC_KEY =
        "2d48fd9fb8e58bc9c1f14a7bda1b8e49a3520a67a2300a1f73766caee29f2411c5350bceb15ed196ca963d6a6d0b61f3734f0a0f4a172ad853f16dd06018bc5ca8fb640eaa8decd1cd41f66e166cea7a3023bd63960e656ec97751cfc7ce08d943928e9db9b35400ff3d138bda1ab511a06fbee75585191cabe0e6e63f7350d6"

    /**
     * 对 [text]（JSON 字符串）做 weapi 加密。
     *
     * @return (params, encSecKey)。params 已做 URL 编码，可直接拼进表单 body。
     */
    fun weapi(text: String): Pair<String, String> {
        val params1 = aesEncrypt(text, PRESET_KEY, IV)
        val params2 = aesEncrypt(params1, SECRET_KEY, IV)
        val params = java.net.URLEncoder.encode(params2, "UTF-8")
        return Pair(params, ENC_SEC_KEY)
    }

    private fun aesEncrypt(text: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.US_ASCII))
        )
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}
