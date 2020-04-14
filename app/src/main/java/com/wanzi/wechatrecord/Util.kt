package com.wanzi.wechatrecord

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.experimental.and

/**
 *     author : WZ
 *     e-mail : 1253437499@qq.com
 *     time   : 2020/04/14
 *     desc   :
 *     version: 1.0
 */
object Util {

    fun getUinSaltPath(uin: String, uinPath: String): String {
        val accountPath = "${MMWorker.MM_MICRO_MSG_PATH}$uinPath/${MMWorker.MM_ACCOUNT_FILE_NAME}"
        if (!File(accountPath).exists()) return ""

        val byteArray = file2ByteArray(File(accountPath)) ?: return ""

        return MessageDigest.getInstance("MD5").run {
            update(byteArray)
            update(uin.toByteArray())
            val digest = digest()
            val sb = StringBuilder(digest.size * 2)
            val cArr = arrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
            for (i in cArr.indices) {
                val byte = digest[i]
                sb.append(cArr[(byte.toInt() ushr 4) and 15]).append(cArr[(byte and 15).toInt()])
            }
            sb.toString()
        }
    }

    private fun file2ByteArray(file: File): ByteArray? {
        val length = file.length()
        return if (length == 4096L || length == 4112L) {
            try {
                val fis = FileInputStream(file)
                try {
                    val byteArr = ByteArray(4096)
                    var i = 0
                    do {
                        val read = fis.read(byteArr, i, 4096 - i)
                        if (read < 0) {
                            fis.close()
                            return null
                        }
                        i += read
                    } while (i < 4096)
                    if (length > 4096) {
                        val cacheByteArr = ByteArray(16)
                        var j = 0
                        do {
                            val read2 = fis.read(cacheByteArr, j, 16 - j)
                            if (read2 < 0) {
                                fis.close()
                                return null
                            }
                            j += read2
                        } while (j < 16)
                        if (!cacheByteArr.contentEquals(digest(byteArr))) {
                            fis.close()
                            return null
                        }
                    }
                    fis.close()
                    byteArr
                } catch (th: Throwable) {
                    fis.close()
                    null
                }
            } catch (e: IOException) {
                null
            }
        } else {
            null
        }
    }

    private fun digest(bArr: ByteArray): ByteArray {
        val instance = MessageDigest.getInstance("MD5")
        instance.update(bArr)
        return instance.digest()
    }
}