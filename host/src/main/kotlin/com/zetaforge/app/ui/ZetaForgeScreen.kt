package com.zetaforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.components.CodeViewerDialog
import com.zetaforge.app.ui.components.LogConsole
import com.zetaforge.app.ui.components.PermissionBlockedDialog
import com.zetaforge.app.ui.components.PermissionRequestDialog
import com.zetaforge.app.ui.components.PluginCard
import com.zetaforge.app.ui.components.PluginDetailsSheet
import com.zetaforge.app.ui.components.SectionHeader
import com.zetaforge.app.ui.components.SpecialAccessDialog
import com.zetaforge.app.ui.components.ZetaLogo
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.sdk.ZetaLogLevel

/** Actions the screen can trigger; implemented by the view model. */
data class HostActions(
    val onImport: () -> Unit,
    val onStart: (PluginEntry) -> Unit,
    val onDetails: (PluginEntry) -> Unit,
    val onViewCode: (PluginEntry) -> Unit,
    val onCloseDetails: () -> Unit,
    val onCloseCode: () -> Unit,
    val onRunFailing: (PluginEntry) -> Unit,
    val onRunThrowing: (PluginEntry) -> Unit,
    val onUnload: (PluginEntry) -> Unit,
    val onUninstall: (PluginEntry) -> Unit,
    val onLevelChange: (ZetaLogLevel) -> Unit,
    val onClearLogs: () -> Unit,
    val onToggleLogs: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggleCard: (PluginEntry) -> Unit,
    val onDismissBanner: () -> Unit,
    val onPermissionPromptResult: (Boolean) -> Unit,
    val onSpecialAccessResult: (Boolean) -> Unit,
    val onDismissBlocked: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/**
 * Root screen. Responsive by construction: a single scrolling column on phones,
 * a two-pane layout (plugins | console) from ~840dp, which covers large phones
 * in landscape, foldables, tablets and desktop-sized windows. The log console
 * can also take over the whole screen.
 */
@Composable
fun ZetaForgeScreen(state: HostUiState, actions: HostActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val wide = maxWidth >= 840.dp
            val availableHeight = maxHeight
            val horizontalPadding = when {
                maxWidth >= 1200.dp -> 48.dp
                maxWidth >= 600.dp -> 28.dp
                else -> 18.dp
            }

            when {
                // Reading a long run is a first-class activity in a tool like
                // this, so the console can take the whole screen.
                state.logsExpanded -> LogConsole(
                    records = state.filteredLogs,
                    minLevel = state.minLevel,
                    onLevelChange = actions.onLevelChange,
                    onClear = actions.onClearLogs,
                    expanded = true,
                    onToggleExpand = actions.onToggleLogs,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = 12.dp),
                )

                wide -> Row(
                    Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .widthIn(max = 720.dp)
                            .fillMaxHeight(),
                    ) {
                        PluginPane(state, actions, Modifier.fillMaxSize())
                    }
                    LogConsole(
                        records = state.filteredLogs,
                        minLevel = state.minLevel,
                        onLevelChange = actions.onLevelChange,
                        onClear = actions.onClearLogs,
                        onToggleExpand = actions.onToggleLogs,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 12.dp),
                ) {
                    PluginPane(state, actions, Modifier.weight(1f))
                    Spacer(Modifier.height(14.dp))
                    LogConsole(
                        records = state.filteredLogs,
                        minLevel = state.minLevel,
                        onLevelChange = actions.onLevelChange,
                        onClear = actions.onClearLogs,
                        onToggleExpand = actions.onToggleLogs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 168.dp, max = availableHeight * 0.34f)
                            .height(availableHeight * 0.30f),
                    )
                }
            }
        }
    }

    state.details?.let { details ->
        PluginDetailsSheet(
            details = details,
            onDismiss = actions.onCloseDetails,
            onRunFailing = { actions.onRunFailing(details.entry) },
            onRunThrowing = { actions.onRunThrowing(details.entry) },
            onUnload = { actions.onUnload(details.entry) },
            onUninstall = { actions.onUninstall(details.entry) },
            onViewCode = { actions.onViewCode(details.entry) },
        )
    }

    state.codeViewer?.let { viewer ->
        CodeViewerDialog(
            pluginName = viewer.pluginName,
            files = viewer.files,
            onDismiss = actions.onCloseCode,
        )
    }

    state.permissionPrompt?.let { prompt ->
        PermissionRequestDialog(
            pluginName = prompt.pluginName,
            plan = prompt.plan,
            onConfirm = { actions.onPermissionPromptResult(true) },
            onDismiss = { actions.onPermissionPromptResult(false) },
        )
    }

    state.specialAccessPrompt?.let { prompt ->
        SpecialAccessDialog(
            access = prompt.access,
            reason = prompt.reason,
            onConfirm = { actions.onSpecialAccessResult(true) },
            onDismiss = { actions.onSpecialAccessResult(false) },
        )
    }

    state.blockedDialog?.let { blocked ->
        PermissionBlockedDialog(
            title = stringResource(blocked.titleRes),
            body = blocked.body,
            canOpenSettings = blocked.canOpenSettings,
            onOpenSettings = actions.onOpenAppSettings,
            onDismiss = actions.onDismissBlocked,
        )
    }
}

@Composable
private fun PluginPane(state: HostUiState, actions: HostActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { HeaderCard(state, actions) }

        state.banner?.let { banner ->
            item { BannerCard(banner, actions.onDismissBanner) }
        }

        item {
            val visible = state.visiblePlugins
            SectionHeader(
                title = stringResource(R.string.plugins_title),
                subtitle = when {
                    state.plugins.isEmpty() -> stringResource(R.string.plugins_none)
                    visible.size != state.plugins.size ->
                        stringResource(R.string.plugins_filtered_count, visible.size, state.plugins.size)

                    else -> stringResource(R.string.plugins_count, state.plugins.size)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        // The search box only earns its place once there is something to sift.
        if (state.plugins.size > 1 || state.query.isNotEmpty()) {
            item { SearchField(state.query, actions.onQueryChange) }
        }

        val visible = state.visiblePlugins
        when {
            state.plugins.isEmpty() -> item { EmptyState() }
            visible.isEmpty() -> item { NoMatchState(state.query) }
            else -> items(visible, key = { it.id }) { entry ->
                PluginCard(
                    entry = entry,
                    expanded = state.isExpanded(entry.id),
                    onToggleExpanded = { actions.onToggleCard(entry) },
                    onStart = { actions.onStart(entry) },
                    onDetails = { actions.onDetails(entry) },
                    onViewCode = { actions.onViewCode(entry) },
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(state: HostUiState, actions: HostActions) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                            )
                        )
                    )
            )
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZetaLogo(size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = actions.onImport,
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.action_importing).uppercase())
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.action_import_plugin).uppercase())
                    }
                }
            }
        }
    }
}

/** Real-time filter over the installed plugins. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = { Text(stringResource(R.string.plugins_search_hint)) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.plugins_search_clear),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun NoMatchState(query: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(R.string.plugins_search_empty, query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BannerCard(banner: HostUiState.Banner, onDismiss: () -> Unit) {
    val accents = zetaAccents()
    val color = when (banner.kind) {
        HostUiState.Banner.Kind.SUCCESS -> accents.success
        HostUiState.Banner.Kind.ERROR -> accents.danger
        HostUiState.Banner.Kind.INFO -> accents.info
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(banner.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss).uppercase()) }
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(26.dp),
                )
            }
            Text(stringResource(R.string.plugins_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.plugins_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
