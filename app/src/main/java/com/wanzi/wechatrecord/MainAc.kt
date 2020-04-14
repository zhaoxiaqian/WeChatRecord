package com.wanzi.wechatrecord

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.PermissionUtils
import com.blankj.utilcode.util.ShellUtils
import com.blankj.utilcode.util.ToastUtils
import kotlinx.android.synthetic.main.ac_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainAc : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ac_main)

        ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_CODE)

        btn.setOnClickListener {
            // 必须要有Root权限才可以
            if (!AppUtils.isAppRoot()) {
                ToastUtils.showShort("请授予App Root权限")
                return@setOnClickListener
            }

            MainScope().launch(Dispatchers.IO) {
                // 修改微信根目录读写权限
                ShellUtils.execCmd("chmod -R 777 $MM_ROOT_PATH", true)

                 WorkManager.getInstance(this@MainAc)
                        .beginWith(OneTimeWorkRequest.Builder(MMWorker::class.java).build())
                        .enqueue()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (REQUEST_CODE != requestCode) return

        val result = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
        if (result) {
            AlertDialog.Builder(this)
                    .setMessage("请授予App相关权限")
                    .setPositiveButton("去授予") { _, _ ->
                        PermissionUtils.launchAppDetailsSettings()
                    }
                    .show()
        }
    }

    companion object {
        private const val REQUEST_CODE = 1
        private val PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        @SuppressLint("SdCardPath")
        const val MM_ROOT_PATH = "/data/data/com.tencent.mm/"
    }
}
