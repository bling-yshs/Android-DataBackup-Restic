package com.xayah.feature.main.settings.storage

import android.content.Intent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.libpickyou.ui.model.PickerType
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import com.xayah.feature.main.settings.cache.CacheInfoCard
import com.xayah.feature.main.settings.cache.CacheManagementViewModel
import com.xayah.feature.main.settings.restic.ResticViewModel
import com.xayah.feature.setup.MainActivity as SetupActivity
import com.xayah.feature.setup.SetupRoutes

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageStorageStats(isOtg: Boolean = false) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val resticViewModel = hiltViewModel<ResticViewModel>()
    val cacheViewModel = hiltViewModel<CacheManagementViewModel>()

    val localRepoPath by resticViewModel.repoPathState.collectAsStateWithLifecycle()
    val otgRepoPath by resticViewModel.otgRepoPathState.collectAsStateWithLifecycle()
    val repoPath = if (isOtg) otgRepoPath else localRepoPath
    val localInitialized by resticViewModel.resticInitializedState
        .collectAsStateWithLifecycle(initialValue = false)
    val otgInitialized by resticViewModel.otgInitializedState
        .collectAsStateWithLifecycle(initialValue = false)
    val resticInitialized = if (isOtg) otgInitialized else localInitialized
    val cacheInfo by cacheViewModel.cacheInfo.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // --- 移动本地仓库到新位置 ---
    val moveState by resticViewModel.moveState.collectAsStateWithLifecycle()
    val isMoving = moveState is ResticViewModel.MoveState.Moving
    var pendingMoveDir by rememberSaveable { mutableStateOf("") }
    val moveDirLauncher = PickYouLauncher(
        checkPermission = false,
        title = stringResource(id = R.string.select_directory),
        pickerType = PickerType.DIRECTORY,
        permissionType = PermissionType.ROOT,
    )
    MoveRepoDialogs(
        moveState = moveState,
        currentPath = localRepoPath ?: "",
        targetPath = if (pendingMoveDir.isEmpty()) "" else resticViewModel.moveTargetOf(pendingMoveDir),
        onConfirm = {
            val dir = pendingMoveDir
            pendingMoveDir = ""
            resticViewModel.moveLocalRepository(dir)
        },
        onCancel = { pendingMoveDir = "" },
        onResultDismiss = { resticViewModel.resetMoveState() },
    )

    LaunchedEffect(Unit) {
        if (isOtg) resticViewModel.refreshOtgStatus()
        else resticViewModel.checkResticStatus()
        cacheViewModel.calculateCacheSize()
    }

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(
            id = if (isOtg) R.string.otg_storage_stats_title else R.string.storage_stats_title
        )
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // --- 1. Restic 备份仓库 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(id = R.string.restic_repo_path_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (resticInitialized && !repoPath.isNullOrEmpty())
                            repoPath!!
                        else
                            stringResource(id = R.string.restic_not_initialized),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (isOtg) {
                                // OTG 没有引导页第二步，仍走 OTG 专用初始化页
                                navController.navigateSingle(
                                    MainRoutes.ResticInitialization.getRoute(isOtg = true)
                                )
                            } else {
                                // 本地：与设置页胶囊一致，直接复用引导页第二阶段（setup_two）
                                context.startActivity(
                                    Intent(context, SetupActivity::class.java)
                                        .putExtra(SetupActivity.EXTRA_START_DESTINATION, SetupRoutes.Two.route)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(id = R.string.storage_reselect_and_reinit))
                    }
                    // 仅本地：把现有仓库连同全部备份整体移到别的目录
                    if (!isOtg && resticInitialized && !repoPath.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = !isMoving,
                            onClick = {
                                moveDirLauncher.launch(context) { pathString ->
                                    pendingMoveDir = pathString
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(id = R.string.restic_move_repo))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 2. 缓存统计（弱化路径，只提示可清理）---
            CacheInfoCard(
                title = stringResource(id = R.string.storage_cache_title),
                size = cacheInfo.restoreCacheSize,
                onClear = { cacheViewModel.clearRestoreCache() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.storage_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isOtg) {
                Spacer(modifier = Modifier.height(16.dp))
                com.xayah.core.ui.component.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = com.xayah.core.ui.material3.CardDefaults.cardColors(
                        containerColor = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.PrimaryContainer.value
                    ),
                ) {
                    Column(modifier = Modifier.padding(com.xayah.core.ui.token.SizeTokens.Level16)) {
                        Text(
                            text = stringResource(id = R.string.otg_storage_tips_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnPrimaryContainer.value
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        com.xayah.core.ui.component.BodyMediumText(
                            text = stringResource(id = R.string.otg_storage_tips_content),
                            color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnPrimaryContainer.value
                        )
                    }
                }
            }
        }
    }
}

/** 移动仓库的三类弹窗：确认、进行中（不可关闭）、结果。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveRepoDialogs(
    moveState: ResticViewModel.MoveState,
    currentPath: String,
    targetPath: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onResultDismiss: () -> Unit,
) {
    if (targetPath.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(id = R.string.restic_move_repo)) },
            text = { Text(stringResource(id = R.string.restic_move_confirm, currentPath, targetPath)) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.restic_move_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text(stringResource(id = R.string.cancel)) }
            },
        )
    }
    when (moveState) {
        ResticViewModel.MoveState.Moving -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(id = R.string.restic_move_repo)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(id = R.string.restic_move_in_progress))
                }
            },
            confirmButton = {},
        )

        is ResticViewModel.MoveState.Done -> AlertDialog(
            onDismissRequest = onResultDismiss,
            title = { Text(stringResource(id = R.string.restic_move_repo)) },
            text = { Text(stringResource(id = R.string.restic_move_done, moveState.repoPath)) },
            confirmButton = {
                TextButton(onClick = onResultDismiss) { Text(stringResource(id = R.string.restic_move_ok)) }
            },
        )

        is ResticViewModel.MoveState.Error -> AlertDialog(
            onDismissRequest = onResultDismiss,
            title = { Text(stringResource(id = R.string.restic_move_failed)) },
            text = { Text(moveState.message) },
            confirmButton = {
                TextButton(onClick = onResultDismiss) { Text(stringResource(id = R.string.restic_move_ok)) }
            },
        )

        ResticViewModel.MoveState.Idle -> {}
    }
}
