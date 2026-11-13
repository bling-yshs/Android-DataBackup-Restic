package com.xayah.feature.main.restore

import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticMetaTags
import kotlinx.serialization.Serializable

@Serializable
data class ResticBackupGroup(
    val packageName: String,
    val userId: Int,
    val timestamp: Long,
    val backups: List<ResticBackupApp>,
    val appLabel: String
) {
    val snapshotCount: Int get() = backups.size

    /** config 快照：有它才算完整备份，版本 / 备注 tag 也只贴在它上面。 */
    val configBackup: ResticBackupApp? get() = backups.firstOrNull { it.dataType == DataType.PACKAGE_CONFIG }

    /** versionName to versionCode；旧备份（未迁移）或不完整备份为 null。 */
    val version: Pair<String, Long>? get() = configBackup?.let { ResticMetaTags.parseVersion(it.tags) }

    val remark: String get() = configBackup?.let { ResticMetaTags.parseRemark(it.tags) }.orEmpty()

    /** 列表里包名下方的一行：「版本名 · 备注」，两者都没有时为空串（不显示）。 */
    val metaLine: String
        get() = listOfNotNull(
            version?.first?.takeIf { it.isNotEmpty() },
            remark.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
}
