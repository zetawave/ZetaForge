package com.zetaforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.components.CodeViewerDialog
import com.zetaforge.app.ui.components.InstallPermissionDialog
import com.zetaforge.app.ui.components.PermissionBlockedDialog
import com.zetaforge.app.ui.components.PermissionRequestDialog
import com.zetaforge.app.ui.components.PluginCard
import com.zetaforge.app.ui.components.PluginDetailsSheet
import com.zetaforge.app.ui.components.PluginSettingsDialog
import com.zetaforge.app.ui.components.ReadinessPanel
import com.zetaforge.app.ui.components.ScheduleDialog
import com.zetaforge.app.ui.components.SectionHeader
import com.zetaforge.app.ui.components.SpecialAccessDialog
import com.zetaforge.app.ui.components.UpdateCard
import com.zetaforge.app.ui.components.ZetaLogo
import com.zetaforge.app.ui.screens.AboutScreen
import com.zetaforge.app.ui.screens.ActivityScreen
import com.zetaforge.app.ui.screens.AppSettingsScreen
import com.zetaforge.app.ui.screens.DiagnosticsScreen
import com.zetaforge.app.ui.screens.HelpScreen
import com.zetaforge.app.ui.screens.OnboardingScreen
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.runtime.schedule.Schedule
import com.zetaforge.sdk.ZetaLogLevel

/** Actions the screen can trigger; implemented by the view model. */
data class HostActions(
    val onImport: () -> Unit,
    val onStart: (PluginEntry) -> Unit,
    val onDetails: (PluginEntry) -> Unit,
    val onViewCode: (PluginEntry) -> Unit,
    val onSettings: (PluginEntry) -> Unit,
    val onSettingChange: (String, Any) -> Unit,
    val onSettingsAction: (String) -> Unit,
    val onPickFolder: (String) -> Unit,
    val onSaveSettings: () -> Unit,
    val onResetSettings: () -> Unit,
    val onCloseSettings: () -> Unit,
    val onCloseDetails: () -> Unit,
    val onCloseCode: () -> Unit,
    val onRunFailing: (PluginEntry) -> Unit,
    val onRunThrowing: (PluginEntry) -> Unit,
    val onShare: (PluginEntry) -> Unit,
    val onExport: (PluginEntry) -> Unit,
    val onUnload: (PluginEntry) -> Unit,
    val onUninstall: (PluginEntry) -> Unit,
    val onLevelChange: (ZetaLogLevel) -> Unit,
    val onClearLogs: () -> Unit,
    val onStopRun: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggleCard: (PluginEntry) -> Unit,
    val onDismissBanner: () -> Unit,
    val onPermissionPromptResult: (Boolean) -> Unit,
    val onSpecialAccessResult: (Boolean) -> Unit,
    val onDismissBlocked: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onSchedule: (PluginEntry) -> Unit,
    val onOpenScreen: (PluginEntry) -> Unit,
    val onScheduleEdit: ((Schedule) -> Schedule) -> Unit,
    val onScheduleSave: () -> Unit,
    val onScheduleClose: () -> Unit,
    val onFixReadiness: (ReadinessItem.Fix) -> Unit,
    val onCheckUpdates: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onDismissUpdate: () -> Unit,
    val onOpenInstallSettings: () -> Unit,
    val onDismissInstallPermission: () -> Unit,
    val onCheckUpdatesOnLaunch: (Boolean) -> Unit,
    val onNavigate: (HostUiState.Route) -> Unit,
    val onBack: () -> Unit,
    val onFinishOnboarding: () -> Unit,
    val onReplayOnboarding: () -> Unit,
    val onTheme: (AppPreferences.Theme) -> Unit,
    val onNotifyResults: (Boolean) -> Unit,
)

/**
 * The shell: three tabs, one screen at a time, and a way back.
 *
 * ### What changed, and why
 * The first version was one screen that carried everything: an identity card
 * with the import button, the plugin list, and a log console nailed to the
 * bottom third. Every part of that was defensible on its own and the sum was
 * not. The console is the tool's most valuable view *when a run misbehaves* and
 * pure noise the rest of the time, and it was taking a third of the height from
 * the list somebody opened the app to use; the four other screens were behind
 * an overflow menu, which is where features go to be undiscovered.
 *
 * Three destinations answer the three questions the app is actually asked:
 *
 *  * **Plugins** - what do I have, and run it. The reason the app exists, and
 *    now the whole screen.
 *  * **Activity** - what is it doing, and what did it say. Where the console
 *    lives: one tap away, never in the way, and badged when something went
 *    wrong while you were elsewhere.
 *  * **Settings** - everything you set once and forget, with diagnostics, help
 *    and about as rows inside it rather than as entries in a hidden menu.
 *
 * The overflow menu is gone entirely. Nothing it held was worth hiding.
 *
 * ### Adaptive by construction
 * A bottom bar below ~720dp, a navigation rail beside the content above it, and
 * the content itself capped in width and centred - because a plugin card
 * stretched across a tablet is harder to read than one that is not, not easier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZetaForgeScreen(state: HostUiState, actions: HostActions) {
    // The wizard owns the whole window: nothing else is useful until the user
    // has been told what a plugin is.
    if (state.onboarding) {
        OnboardingScreen(
            readiness = state.readiness,
            onFix = actions.onFixReadiness,
            onFinish = actions.onFinishOnboarding,
        )
        return
    }

    BackHandler(enabled = state.route != HostUiState.Route.PLUGINS) { actions.onBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val onTab = state.route.isTab

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.systemBars,
            topBar = { ZetaTopBar(state, actions) },
            bottomBar = { if (onTab && !wide) ZetaBottomBar(state, actions) },
            floatingActionButton = {
                // Only where it means something. A floating button that changes
                // meaning per screen is a button nobody presses with confidence.
                if (state.route == HostUiState.Route.PLUGINS && state.plugins.isNotEmpty()) {
                    ImportButton(state, actions)
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (onTab && wide) ZetaNavRail(state, actions)

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                        Destination(state, actions)
                    }
                }
            }
        }
    }

    Dialogs(state, actions)
}

@Composable
private fun Destination(state: HostUiState, actions: HostActions) {
    when (state.route) {
        HostUiState.Route.PLUGINS -> PluginPane(state, actions, Modifier.fillMaxSize())

        HostUiState.Route.ACTIVITY -> ActivityScreen(
            state = state,
            onLevelChange = actions.onLevelChange,
            onClearLogs = actions.onClearLogs,
            onStop = actions.onStopRun,
        )

        HostUiState.Route.APP_SETTINGS -> AppSettingsScreen(
            state = state,
            onTheme = actions.onTheme,
            onLogLevel = actions.onLevelChange,
            onNotifyResults = actions.onNotifyResults,
            onCheckUpdatesOnLaunch = actions.onCheckUpdatesOnLaunch,
            onReplayOnboarding = actions.onReplayOnboarding,
            onClearLogs = actions.onClearLogs,
            onNavigate = actions.onNavigate,
        )

        HostUiState.Route.HELP -> HelpScreen()
        HostUiState.Route.DIAGNOSTICS -> DiagnosticsScreen(state, actions.onFixReadiness)
        HostUiState.Route.ABOUT -> AboutScreen(
            update = state.update,
            onCheckUpdates = actions.onCheckUpdates,
        )
    }
}

// -- navigation ---------------------------------------------------------------

/**
 * What each tab is called, drawn with, and badged with.
 *
 * The badges are the whole reason the bar is worth more than a menu: they are
 * the app saying "something happened over here" without stealing the screen to
 * say it.
 */
private data class TabSpec(
    val route: HostUiState.Route,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabSpec(
        HostUiState.Route.PLUGINS,
        R.string.nav_plugins,
        Icons.Filled.Extension,
        Icons.Outlined.Extension,
    ),
    TabSpec(
        HostUiState.Route.ACTIVITY,
        R.string.nav_activity,
        Icons.Filled.Terminal,
        Icons.Outlined.Terminal,
    ),
    TabSpec(
        HostUiState.Route.APP_SETTINGS,
        R.string.nav_settings,
        Icons.Filled.Tune,
        Icons.Outlined.Tune,
    ),
)

/**
 * How many things this tab wants to tell you about.
 *
 * Activity counts warnings and errors written since you last looked; Settings
 * counts what stops a scheduled run from happening, and only once something is
 * actually scheduled - before that it would be advice about a feature nobody
 * has used yet.
 */
@Composable
private fun badgeFor(route: HostUiState.Route, state: HostUiState): Int = when {
    // Never on the tab you are looking at. A badge means "something happened
    // over there"; on the current screen it is the app telling you about
    // something you can already see, which just makes it look stuck.
    route == state.route -> 0
    route == HostUiState.Route.ACTIVITY -> state.unseenIssues
    route == HostUiState.Route.APP_SETTINGS ->
        if (state.schedules.values.any { it.isAutomatic }) state.readiness?.blockingCount ?: 0 else 0

    else -> 0
}

@Composable
private fun ZetaBottomBar(state: HostUiState, actions: HostActions) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TABS.forEach { tab ->
            val selected = state.route == tab.route
            val badge = badgeFor(tab.route, state)
            NavigationBarItem(
                selected = selected,
                onClick = { actions.onNavigate(tab.route) },
                icon = { TabIcon(tab, selected, badge) },
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun ZetaNavRail(state: HostUiState, actions: HostActions) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Spacer(Modifier.size(8.dp))
            ZetaLogo(size = 32.dp)
        },
    ) {
        Spacer(Modifier.size(8.dp))
        TABS.forEach { tab ->
            val selected = state.route == tab.route
            val badge = badgeFor(tab.route, state)
            NavigationRailItem(
                selected = selected,
                onClick = { actions.onNavigate(tab.route) },
                icon = { TabIcon(tab, selected, badge) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@Composable
private fun TabIcon(tab: TabSpec, selected: Boolean, badge: Int) {
    androidx.compose.material3.BadgedBox(
        badge = {
            if (badge > 0) {
                Badge { Text(if (badge > 99) "99+" else badge.toString()) }
            }
        },
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.icon,
            contentDescription = null,
        )
    }
}

/**
 * The bar above everything.
 *
 * On a tab it is identity and nothing else - there is no menu left to put in
 * it, which is the point. On a pushed screen it is the way back, which is the
 * only thing it needs to be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZetaTopBar(state: HostUiState, actions: HostActions) {
    val onTab = state.route.isTab
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        navigationIcon = {
            when {
                !onTab -> IconButton(onClick = actions.onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }

                state.route == HostUiState.Route.PLUGINS ->
                    Box(Modifier.padding(start = 14.dp)) { ZetaLogo(size = 30.dp) }

                else -> Unit
            }
        },
        title = {
            Text(
                stringResource(
                    when (state.route) {
                        HostUiState.Route.PLUGINS -> R.string.app_name
                        HostUiState.Route.ACTIVITY -> R.string.nav_activity
                        HostUiState.Route.APP_SETTINGS -> R.string.nav_settings
                        HostUiState.Route.HELP -> R.string.help_title
                        HostUiState.Route.DIAGNOSTICS -> R.string.diagnostics_title
                        HostUiState.Route.ABOUT -> R.string.about_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        },
    )
}

@Composable
private fun ImportButton(state: HostUiState, actions: HostActions) {
    ExtendedFloatingActionButton(
        onClick = actions.onImport,
        expanded = true,
        icon = {
            if (state.importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
        text = {
            Text(
                stringResource(
                    if (state.importing) R.string.action_importing else R.string.action_import_plugin
                )
            )
        },
    )
}

// -- the plugin list ----------------------------------------------------------

@Composable
private fun PluginPane(state: HostUiState, actions: HostActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // The banner sits at the top of the list, so from anywhere further down it
    // would announce itself off-screen - and an answer nobody sees is the same
    // as no answer at all.
    LaunchedEffect(state.banner) {
        if (state.banner != null) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        // Room for the floating button to sit over nothing important.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
    ) {
        state.banner?.let { banner ->
            item { BannerCard(banner, actions.onDismissBanner) }
        }

        if (state.update.available != null) {
            item { UpdateCard(state.update, actions.onDownloadUpdate, actions.onDismissUpdate) }
        }

        // Only once something is actually scheduled: before that, asking for
        // battery exemptions is noise.
        val anyScheduled = state.schedules.values.any { it.isAutomatic }
        if (anyScheduled && state.readiness?.allGood == false) {
            item {
                ReadinessPanel(
                    readiness = state.readiness,
                    onFix = actions.onFixReadiness,
                    showWhenSatisfied = false,
                )
            }
        }

        if (state.plugins.isNotEmpty()) {
            item {
                val visible = state.visiblePlugins
                SectionHeader(
                    title = stringResource(R.string.plugins_title),
                    subtitle = if (visible.size != state.plugins.size) {
                        stringResource(R.string.plugins_filtered_count, visible.size, state.plugins.size)
                    } else {
                        stringResource(R.string.plugins_count, state.plugins.size)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // The search box only earns its place once there is something to sift.
        if (state.plugins.size > 1 || state.query.isNotEmpty()) {
            item { SearchField(state.query, actions.onQueryChange) }
        }

        val visible = state.visiblePlugins
        when {
            state.plugins.isEmpty() -> item { EmptyState(state, actions) }
            visible.isEmpty() -> item { NoMatchState(state.query) }
            else -> items(visible, key = { it.id }) { entry ->
                PluginCard(
                    entry = entry,
                    expanded = state.isExpanded(entry.id),
                    schedule = state.scheduleOf(entry.id),
                    onToggleExpanded = { actions.onToggleCard(entry) },
                    onStart = { actions.onStart(entry) },
                    onDetails = { actions.onDetails(entry) },
                    onViewCode = { actions.onViewCode(entry) },
                    onSettings = { actions.onSettings(entry) },
                    onSchedule = { actions.onSchedule(entry) },
                    onOpenScreen = { actions.onOpenScreen(entry) },
                )
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

/**
 * The first screen anybody sees, and the only one that has to teach.
 *
 * It carries its own button rather than relying on the floating one, because
 * with nothing installed there is no list for a floating button to float over -
 * and a first action hidden in a corner is a first action not taken.
 */
@Composable
private fun EmptyState(state: HostUiState, actions: HostActions) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            Spacer(Modifier.size(4.dp))
            Button(
                onClick = actions.onImport,
                enabled = !state.importing,
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (state.importing) R.string.action_importing else R.string.action_import_plugin
                    )
                )
            }
        }
    }
}

// -- everything that opens over the top ---------------------------------------

@Composable
private fun Dialogs(state: HostUiState, actions: HostActions) {
    state.details?.let { details ->
        PluginDetailsSheet(
            details = details,
            onDismiss = actions.onCloseDetails,
            onRunFailing = { actions.onRunFailing(details.entry) },
            onRunThrowing = { actions.onRunThrowing(details.entry) },
            onShare = { actions.onShare(details.entry) },
            onExport = { actions.onExport(details.entry) },
            onUnload = { actions.onUnload(details.entry) },
            onUninstall = { actions.onUninstall(details.entry) },
            onViewCode = { actions.onViewCode(details.entry) },
        )
    }

    state.settingsDialog?.let { settings ->
        PluginSettingsDialog(
            state = settings,
            onValueChange = actions.onSettingChange,
            onPickFolder = actions.onPickFolder,
            onAction = actions.onSettingsAction,
            onSave = actions.onSaveSettings,
            onReset = actions.onResetSettings,
            onDismiss = actions.onCloseSettings,
        )
    }

    state.scheduleDialog?.let { schedule ->
        ScheduleDialog(
            state = schedule,
            onEdit = actions.onScheduleEdit,
            onSave = actions.onScheduleSave,
            onDismiss = actions.onScheduleClose,
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

    if (state.update.needsPermission) {
        InstallPermissionDialog(
            onOpenSettings = actions.onOpenInstallSettings,
            onDismiss = actions.onDismissInstallPermission,
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
