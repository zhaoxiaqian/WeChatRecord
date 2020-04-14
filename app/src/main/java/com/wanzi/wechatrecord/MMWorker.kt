package com.wanzi.wechatrecord

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.EncryptUtils
import com.blankj.utilcode.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteDatabaseHook
import org.jsoup.Jsoup
import java.io.File
import java.util.*

/**
 *     author : WZ
 *     e-mail : 1253437499@qq.com
 *     time   : 2020/04/14
 *     desc   :
 *     version: 1.0
 */
class MMWorker(private val context: Context, workerParameters: WorkerParameters) : CoroutineWorker(context, workerParameters) {

    private val TAG by lazy { this::class.java.simpleName }
    private var uinPath = ""
    private var uinSaltPath = ""

    @SuppressLint("RestrictedApi")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 获取数据库密码 数据库密码是IMEI和uin合并后计算MD5值取前7位
        val uin = Jsoup.parse(File(MM_SP_UIN_PATH), "UTF-8").select("int").first { it.attr("name") == "_auth_uin" }.attr("value")
        val imei = Jsoup.parse(File(MM_SP_IMEI_PATH), "UTF-8").select("string").first { it.attr("name") == "IMEI_DENGTA" }.text()
        val password = EncryptUtils.encryptMD5ToString("$imei$uin").subSequence(0, 7).toString().toLowerCase(Locale.ROOT)
        Log.d(TAG, "password:$password")

        // 获取当前微信登录用户的数据库文件父级文件夹名（MD5("mm"+uin) ）
        uinPath = EncryptUtils.encryptMD5ToString("mm$uin").toString().toLowerCase(Locale.ROOT)
        Log.d(TAG, "uinPath:$uinPath")

        // 在微信7.0.7以后（准确的说，从7.0.6的某个小版本开始），微信数据库地址中uinPath不再和微信图片、语音、视频等地址中的uinPath一致，多了一个转码的步骤
        // 1520是微信7.0.7的版本
        uinSaltPath = if (AppUtils.getAppInfo("com.tencent.mm").versionCode < 1520) {
            uinPath
        } else {
            Util.getUinSaltPath(uin, uinPath)
        }

        // 微信数据库地址
        val mmDBPath = "$MM_MICRO_MSG_PATH$uinPath/$MM_DB_FILE_NAME"
        Log.d(TAG, "wechat db path :$mmDBPath")

        // 将微信数据库拷贝出来，因为直连微信的db，会导致微信崩溃
        val dbLocalPath = "${context.externalCacheDir}/$MM_DB_FILE_NAME"
        FileUtils.copy(mmDBPath, dbLocalPath)

        // 打开数据库
        return@withContext File(dbLocalPath).let {
            if (it.exists()) {
                openMMDB(context, it, password)
                Result.success()
            } else {
                Result.failure()
            }
        }
    }

    private fun openMMDB(context: Context, file: File, password: String) {
        SQLiteDatabase.loadLibs(context.applicationContext)

        val databaseHook = object : SQLiteDatabaseHook {
            override fun preKey(database: SQLiteDatabase) {}

            override fun postKey(database: SQLiteDatabase) {
                database.rawExecSQL("PRAGMA cipher_migrate;") // 兼容2.0的数据库
            }
        }

        SQLiteDatabase.openOrCreateDatabase(file, password, null, databaseHook).run {
            userInfoTable(this)
            contactTable(this)
            chatRoomTable(this)
            messageTable(this)
            close()
        }
    }

    // 用户信息表
    private fun userInfoTable(db: SQLiteDatabase) {
        db.rawQuery("SELECT id,value FROM userinfo", arrayOf()).use {
            while (it.moveToNext()) {
                it.getString(it.getColumnIndex("id"))
                it.getString(it.getColumnIndex("value"))
                // 说一下id代表的值
                // 2：微信id（wxid_xxxxx） 4：昵称 6：手机号码 42：微信号（只允许修改一次的那个） 12291：签名 12293：地区
            }
        }
    }

    // 联系人表
    private fun contactTable(db: SQLiteDatabase) {
        // verifyFlag!=0：公众号等类型 type=33：微信功能 type=2：未知 type=4：非好友
        // 一般公众号原始ID开头都是gh_
        // 群ID的结尾是@chatroom
        db.rawQuery("SELECT * FROM rcontact WHERE " +
                "type != ? AND " +
                "type != ? AND " +
                "type != ? AND " +
                "verifyFlag = ? AND " +
                "username NOT LIKE 'gh_%' ",
                arrayOf("2", "33", "4", "0")).use {
            while (it.moveToNext()) {
                it.getString(it.getColumnIndex("username")) // 微信号
                it.getString(it.getColumnIndex("nickname")) // 昵称
                it.getString(it.getColumnIndex("conRemark")) // 备注名
            }
        }
    }

    // 微信群表
    private fun chatRoomTable(db: SQLiteDatabase) {
        db.rawQuery("SELECT * FROM chatroom", arrayOf()).use {
            while (it.moveToNext()) {
                it.getString(it.getColumnIndex("chatroomname")) // 群id
                it.getString(it.getColumnIndex("memberlist")) // 成员列表
                it.getString(it.getColumnIndex("roomowner")) // 群主
                it.getString(it.getColumnIndex("selfDisplayName")) // 群昵称
                it.getLong(it.getColumnIndex("modifytime")) // 最后修改时间
            }
        }
    }

    // 聊天记录表
    private fun messageTable(db: SQLiteDatabase) {
        // 这里过滤掉了公众号的消息
        db.rawQuery("SELECT * FROM message WHERE talker NOT LIKE 'gh_%'", arrayOf()).use {
            while (it.moveToNext()) {
                it.getLong(it.getColumnIndex("msgId")) // 本地消息id
                val msgSvrId = it.getString(it.getColumnIndex("msgSvrId")) // 服务端消息id
                val type = it.getInt(it.getColumnIndex("type")) // 消息类型
                it.getString(it.getColumnIndex("isSend")) // 1 发送，其余的接收
                it.getLong(it.getColumnIndex("createTime")) // 创建时间
                it.getString(it.getColumnIndex("talker")) // 聊天者微信id
                it.getString(it.getColumnIndex("content")) // 内容
                val imgPath = it.getString(it.getColumnIndex("imgPath"))
                // type 1：文本/小表情 3：图片 34：语音消息 42：名片 43：小视频 47：大表情 48：位置信息 49：文件/分享/小程序 50：语音/视频通话 64：群通话 10000：系统消息
                when (type) {
                    // 图片
                    3 -> {
                        var imgName = ""
                        // 图片文件需要根据msgSvrId在ImgInfo2表中查找
                        db.rawQuery("SELECT bigImgPath FROM ImgInfo2 WHERE msgSvrId = ? ", arrayOf(msgSvrId)).use { imgCur ->
                            if (imgCur.moveToFirst()) {
                                imgName = imgCur.getString(imgCur.getColumnIndex("bigImgPath"))
                            }
                        }
                        // 图片本地地址
                        val path = "$MM_FILE_PATH$uinSaltPath/image2/${imgName.subSequence(0, 2)}/${imgName.subSequence(2, 4)}/$imgName"
                    }
                    // 语音消息
                    34 -> {
                        val amrName = "msg_$imgPath.amr"
                        val nameEnc = EncryptUtils.encryptMD5ToString(imgPath).toString().toLowerCase(Locale.ROOT)
                        // 语音文件本地地址
                        val path = "$MM_FILE_PATH$uinSaltPath/voice2/${nameEnc.subSequence(0, 2)}/${nameEnc.subSequence(2, 4)}/$amrName"
                    }
                    // 小视频
                    43 -> {
                        val path = "$MM_FILE_PATH$uinSaltPath/video/${imgPath}.mp4"
                    }
                }
            }
        }
    }

    companion object {
        private const val MM_SP_UIN_PATH = "${MainAc.MM_ROOT_PATH}shared_prefs/auth_info_key_prefs.xml" // 微信保存uin的位置
        private const val MM_SP_IMEI_PATH = "${MainAc.MM_ROOT_PATH}shared_prefs/beacontbs_DENGTA_META.xml" // 微信保存IMEI的位置
        const val MM_MICRO_MSG_PATH = "${MainAc.MM_ROOT_PATH}MicroMsg/" // 微信保存聊天记录数据库的目录
        private const val MM_DB_FILE_NAME = "EnMicroMsg.db" // 微信聊天记录数据库
        const val MM_ACCOUNT_FILE_NAME = "account.bin"
        private const val MM_FILE_PATH = "/storage/emulated/0/tencent/MicroMsg/" // 微信保存聊天时语音、图片、视频文件的地址
    }
}