package com.xayah.feature.main.restore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

/**
 * 设置 → 恢复设置 → 迁移旧备份数据：选择应用，为其旧备份补上「版本」tag。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResticMetaMigrationPage(viewModel: ResticMetaMigrationViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val selectedKeys = viewModel.selectedKeys
    val running = progress != null

    // 迁移进行中拦截返回：中途离开会取消剩余项（已完成的不受影响，可再次进入继续）
    BackHandler(enabled = running) {}

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = stringResource(R.string.restore_meta_migration),
        actions = {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !running && selectedKeys.isNotEmpty(),
                onClick = { viewModel.migrate() }
            ) {
                val current = progress
                Text(
                    text = if (current != null) {
                        stringResource(R.string.restore_meta_migration_running, current.current, current.total, current.label)
                    } else {
                        stringResource(R.string.restore_meta_migration_start, selectedKeys.size)
                    },
                    maxLines = 1
                )
            }
        }
    ) {
        when (val state = uiState) {
            is ResticMetaMigrationViewModel.UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is ResticMetaMigrationViewModel.UiState.NotConfigured -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TitleLargeText(text = stringResource(R.string.restore_meta_migration_not_configured))
                }
            }

            is ResticMetaMigrationViewModel.UiState.Ready -> {
                val apps = state.apps
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "__hint__") {
                        Column(
                            modifier = Modifier.padding(SizeTokens.Level16),
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            BodyMediumText(
                                text = stringResource(R.string.restore_meta_migration_hint),
                                color = ThemedColorSchemeKeyTokens.Outline.value
                            )
                            result?.let {
                                Text(
                                    text = stringResource(R.string.restore_meta_migration_result, it.success, it.failed),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (it.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (apps.isEmpty()) {
                        item(key = "__empty__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SizeTokens.Level24),
                                contentAlignment = Alignment.Center
                            ) {
                                TitleLargeText(text = stringResource(R.string.restore_meta_migration_empty))
                            }
                        }
                    } else {
                        item(key = "__select_all__") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !running) { viewModel.toggleAll() }
                                    .padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level8),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
                            ) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = stringResource(R.string.restore_meta_migration_select_all),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Checkbox(
                                    checked = selectedKeys.size == apps.size,
                                    enabled = !running,
                                    onCheckedChange = { viewModel.toggleAll() }
                                )
                            }
                            HorizontalDivider()
                        }

                        items(apps, key = { it.key }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !running) { viewModel.toggle(app.key) }
                                    .padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level8),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
                            ) {
                                PackageIconImage(
                                    packageName = app.packageName,
                                    size = SizeTokens.Level32,
                                    accountId = "local"
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    TitleLargeText(text = app.label, maxLines = 1)
                                    BodyMediumText(
                                        text = app.packageName,
                                        color = ThemedColorSchemeKeyTokens.Outline.value,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.restore_meta_migration_pending, app.pending.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = selectedKeys.contains(app.key),
                                    enabled = !running,
                                    onCheckedChange = { viewModel.toggle(app.key) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
