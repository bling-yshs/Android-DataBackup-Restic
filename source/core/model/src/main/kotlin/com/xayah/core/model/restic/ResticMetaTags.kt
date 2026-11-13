package com.xayah.core.model.restic

import android.util.Base64

/**
 * 贴在 config 快照上的附加 tag：记录这次备份的应用版本与用户备注。
 *
 * 与四段式主 tag（`user_0-<pkg>-<ts>-<type>`）并存；主 tag 解析只认 `user_` 前缀，
 * 因此这些附加 tag 不影响既有的分组 / 还原 / 删除逻辑。
 *
 * rustic 的 `add_tags` 会把一条字符串按逗号再拆成多条 tag，所以自由文本一律先做
 * URL-safe Base64（字符集 A-Z a-z 0-9 - _，不含逗号）再入 tag。
 *
 *   版本：`dbver=<versionCode>:<base64(versionName)>`
 *   备注：`dbnote=<base64(remark)>`
 */
object ResticMetaTags {
    const val VERSION_PREFIX = "dbver="
    const val REMARK_PREFIX = "dbnote="
    const val REMARK_MAX_LENGTH = 50

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    private fun encode(text: String): String = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), FLAGS)

    private fun decode(text: String): String? = runCatching {
        String(Base64.decode(text, FLAGS), Charsets.UTF_8)
    }.getOrNull()

    /** 规整备注：去掉换行与首尾空白，并截断到 [REMARK_MAX_LENGTH]。 */
    fun normalizeRemark(remark: String): String =
        remark.lines().joinToString(" ").trim().take(REMARK_MAX_LENGTH)

    fun versionTag(versionName: String, versionCode: Long): String =
        "$VERSION_PREFIX$versionCode:${encode(versionName)}"

    /** 备注为空时返回 null（不贴 tag）。 */
    fun remarkTag(remark: String): String? =
        normalizeRemark(remark).takeIf { it.isNotEmpty() }?.let { "$REMARK_PREFIX${encode(it)}" }

    /** 新备份写 config 快照时附加的 tag 列表。 */
    fun build(versionName: String, versionCode: Long, remark: String): List<String> =
        listOfNotNull(versionTag(versionName, versionCode), remarkTag(remark))

    fun hasVersion(tags: List<String>): Boolean = tags.any { it.startsWith(VERSION_PREFIX) }

    /** @return versionName to versionCode；没有版本 tag 或格式不对时返回 null。 */
    fun parseVersion(tags: List<String>): Pair<String, Long>? {
        val raw = tags.firstOrNull { it.startsWith(VERSION_PREFIX) }?.removePrefix(VERSION_PREFIX) ?: return null
        val index = raw.indexOf(':')
        if (index <= 0) return null
        val code = raw.substring(0, index).toLongOrNull() ?: return null
        val name = decode(raw.substring(index + 1)) ?: return null
        return name to code
    }

    fun parseRemark(tags: List<String>): String =
        tags.firstOrNull { it.startsWith(REMARK_PREFIX) }
            ?.removePrefix(REMARK_PREFIX)
            ?.let { decode(it) }
            .orEmpty()
}
