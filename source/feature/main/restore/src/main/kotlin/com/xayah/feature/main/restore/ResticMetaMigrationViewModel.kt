package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticMetaTags
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticShared
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.iconDir
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * 旧备份迁移：旧版本创建的备份没有「版本」附加 tag，还原列表无法免解 config 展示版本。
 * 这里对选中应用的每个完整备份（有 config 快照且缺版本 tag）：
 *   解出 package_restore_config.json → 读 versionName / versionCode → 给 config 快照补上版本 tag。
 * 只处理本地仓库；已补过的自动跳过，可重复执行。
 */
@HiltViewModel
class ResticMetaMigrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val shared: ResticShared,
    private val rootService: RemoteRootService,
) : ViewModel() {

    companion object {
        private const val TAG = "ResticMetaMigration"
        private const val CONFIG_FILE = "package_restore_config.json"
    }

    /** 一个待迁移的应用：[pending] 是它名下所有缺版本 tag 的 config 快照。 */
    data class MigrationApp(
        val key: String,
        val packageName: String,
        val userId: Int,
        val label: String,
        val pending: List<ResticBackupApp>,
    )

    sealed interface UiState {
        data object Loading : UiState
        data object NotConfigured : UiState
        data class Ready(val apps: List<MigrationApp>) : UiState
    }

    data class Progress(val current: Int, val total: Int, val label: String)

    data class Result(val success: Int, val failed: Int)

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    val selectedKeys = mutableStateListOf<String>()

    init {
        load()
    }

    private suspend fun resolveRepo(): Pair<String, String>? {
        val repoPath = context.readResticRepoPath()
        val password = context.readResticPassword()
        if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return repoPath to password
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val repo = resolveRepo()
            if (repo == null) {
                _uiState.value = UiState.NotConfigured
                return@launch
            }
            val (repoPath, password) = repo
            val apps = withContext(Dispatchers.IO) {
                val labelMap = readLabelsMap()
                resticRepo.listBackedUpApps(repoPath, password)
                    .filter { it.dataType == DataType.PACKAGE_CONFIG && !ResticMetaTags.hasVersion(it.tags) }
                    // 同一快照带多条主 tag 时会被解析成多条，按快照 ID 去重
                    .distinctBy { it.snapshotId }
                    .groupBy { "${it.userId}-${it.packageName}" }
                    .map { (key, configs) ->
                        val first = configs.first()
                        MigrationApp(
                            key = key,
                            packageName = first.packageName,
                            userId = first.userId,
                            label = resolveAppLabel(labelMap, first.userId, first.packageName),
                            pending = configs.sortedByDescending { it.timestamp },
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            selectedKeys.retainAll(apps.map { it.key }.toSet())
            _uiState.value = UiState.Ready(apps)
        }
    }

    fun toggle(key: String) {
        if (selectedKeys.contains(key)) selectedKeys.remove(key) else selectedKeys.add(key)
    }

    fun toggleAll() {
        val apps = (_uiState.value as? UiState.Ready)?.apps ?: return
        if (selectedKeys.size == apps.size) {
            selectedKeys.clear()
        } else {
            selectedKeys.clear()
            selectedKeys.addAll(apps.map { it.key })
        }
    }

    fun clearResult() {
        _result.value = null
    }

    fun migrate() {
        if (_progress.value != null) return
        val targets = (_uiState.value as? UiState.Ready)?.apps?.filter { it.key in selectedKeys } ?: return
        if (targets.isEmpty()) return

        viewModelScope.launch {
            val repo = resolveRepo() ?: return@launch
            val (repoPath, password) = repo
            val total = targets.sumOf { it.pending.size }
            var done = 0
            var success = 0
            var failed = 0
            _result.value = null
            _progress.value = Progress(0, total, "")

            withContext(Dispatchers.IO) {
                val tmpRoot = "${context.localBackupSaveDir()}/restore/meta_migration"
                for (app in targets) {
                    for (config in app.pending) {
                        _progress.value = Progress(done + 1, total, app.label)
                        val ok = runCatching { migrateOne(repoPath, password, config, tmpRoot) }
                            .onFailure { Log.e(TAG, "迁移失败 ${config.packageName} ${config.snapshotId}: ${it.message}", it) }
                            .getOrDefault(false)
                        if (ok) success++ else failed++
                        done++
                    }
                }
                // 中转目录由 root 进程写入，同样交给 root 清理
                runCatching { rootService.deleteRecursively(tmpRoot) }
                // config 快照 ID 已变：重建还原列表的持久缓存，避免列表秒开时读到失效 ID
                runCatching { shared.refreshAppsDb("local", repoPath, password, emptyMap()) }
            }

            _progress.value = null
            _result.value = Result(success, failed)
            selectedKeys.clear()
            load()
        }
    }

    /** 解出该 config 快照里的 json，读出版本并补上版本 tag。 */
    private suspend fun migrateOne(
        repoPath: String,
        password: String,
        config: ResticBackupApp,
        tmpRoot: String,
    ): Boolean {
        val targetDir = "$tmpRoot/${config.userId}_${config.packageName}_${config.timestamp}/"
        val landed = File(targetDir, CONFIG_FILE)

        // 与恢复流程一致的路径兼容：旧中转快照带共享根前缀，新流式快照是裸名
        val currentBase = context.readBackupDirectory() ?: context.localBackupSaveDir()
        val candidates: List<String?> = linkedSetOf(
            "$currentBase/apps/${config.packageName}/user_${config.userId}",
            "${ConstantUtil.LEGACY_SHARED_PATH}/apps/${config.packageName}/user_${config.userId}",
            null
        ).toList()

        var text: String? = null
        for (sub in candidates) {
            val ok = runCatching {
                resticRepo.restoreSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = config.snapshotId,
                    targetPath = targetDir,
                    includePath = CONFIG_FILE,
                    snapshotSubPath = sub,
                )
            }.getOrDefault(false)
            if (!ok) continue
            text = runCatching { landed.takeIf { it.exists() }?.readText() }.getOrNull()
                ?: runCatching { rootService.readText(landed.path) }.getOrNull()
            if (!text.isNullOrBlank()) break
        }
        val json = text?.takeIf { it.isNotBlank() }
        if (json == null) {
            Log.e(TAG, "未能读出 config: ${config.packageName} ${config.snapshotId}")
            return false
        }

        val packageInfo = JSONObject(json).getJSONObject("packageInfo")
        val versionName = packageInfo.optString("versionName", "")
        val versionCode = packageInfo.optLong("versionCode", 0L)

        val newId = resticRepo.modifySnapshotTags(
            repoPath = repoPath,
            password = password,
            snapshotId = config.snapshotId,
            removePrefixes = listOf(ResticMetaTags.VERSION_PREFIX),
            addTags = listOf(ResticMetaTags.versionTag(versionName, versionCode)),
        )
        return newId != null
    }

    private fun readLabelsMap(): Map<String, String> = runCatching {
        val labelsFile = File(context.iconDir("local"), "labels.json")
        if (labelsFile.exists()) Json.decodeFromString<Map<String, String>>(labelsFile.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun resolveAppLabel(labelMap: Map<String, String>, userId: Int, packageName: String): String {
        labelMap["$userId-$packageName"]
            ?.takeIf { it.isNotEmpty() && it != packageName }
            ?.let { return it }
        return runCatching {
            val pm = context.packageManager
            pm.getPackageInfo(packageName, 0).applicationInfo?.loadLabel(pm)?.toString()
        }.getOrNull() ?: packageName
    }
}
