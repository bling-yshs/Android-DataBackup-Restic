package com.xayah.core.datastore

import android.content.Context

/**
 * 「下一次备份的备注」暂存：在应用详情（备份）里填写，备份成功写进快照后清空。
 *
 * 只是一次性的待用文本，不属于应用记录本身，故不进 Room（免去一次 schema 迁移），
 * 用独立的 SharedPreferences 按 `<userId>-<packageName>` 存放。
 */
object BackupRemarkStore {
    private const val PREFS_NAME = "backup_remarks"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(packageName: String, userId: Int) = "$userId-$packageName"

    fun read(context: Context, packageName: String, userId: Int): String =
        prefs(context).getString(key(packageName, userId), "") ?: ""

    /** 空白备注等同于清空。 */
    fun save(context: Context, packageName: String, userId: Int, remark: String) {
        val editor = prefs(context).edit()
        if (remark.isBlank()) editor.remove(key(packageName, userId)) else editor.putString(key(packageName, userId), remark)
        editor.apply()
    }

    fun clear(context: Context, packageName: String, userId: Int) {
        prefs(context).edit().remove(key(packageName, userId)).apply()
    }
}
