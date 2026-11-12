package app.quartz.apprules.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.apprules.AppCatalog
import app.quartz.apprules.AppProfile
import app.quartz.apprules.AppRef
import app.quartz.apprules.AppRule
import app.quartz.apprules.AppRules
import app.quartz.apprules.CatalogApp
import app.quartz.apprules.ForegroundApp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Auto-tunnel → Apps (FORK.md §14): rules that connect a tunnel while one of their apps is on
 * screen, how apps are detected, and the editor for one rule (shown in place, Back closes it).
 */
@Composable
fun AppRulesScreen(
    rules: AppRules = koinInject(),
    tunnelRepository: TunnelRepository = koinInject(),
) {
    val data by rules.data.collectAsStateWithLifecycle()
    val tunnels by tunnelRepository.userTunnelsFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // "new" for a new rule, a rule's ID while editing it, null for the list.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    editing?.let { id ->
        val rule = data.rules.firstOrNull { it.id == id } ?: AppRule(id = NEW, tunnelId = -1)
        BackHandler { editing = null }
        AppRuleEditor(
            rule = rule,
            tunnels = tunnels,
            seen = data.seen,
            onSave = {
                scope.launch {
                    rules.save(if (it.id == NEW) it.copy(id = java.util.UUID.randomUUID().toString()) else it)
                }
                editing = null
            },
            onDelete = {
                scope.launch { rules.delete(rule.id) }
                editing = null
            },
        )
        return
    }

    // Detection settings change outside the app: read them again whenever the screen resumes.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val watcherOn = remember(resumed) { ForegroundApp.watcherEnabled(context) }
    val usageOn = remember(resumed) { ForegroundApp.hasUsageAccess(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                    title = stringResource(R.string.quartz_apps_enabled),
                    description = { DescriptionText(stringResource(R.string.quartz_apps_enabled_desc)) },
                    trailing = { modifier ->
                        ThemedSwitch(
                            checked = data.enabled,
                            onClick = { scope.launch { rules.setEnabled(it) } },
                            modifier = modifier,
                        )
                    },
                    onClick = { scope.launch { rules.setEnabled(!data.enabled) } },
                )
            }
        }
        item {
            Column {
                GroupLabel(
                    stringResource(R.string.quartz_apps_detection),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Accessibility, contentDescription = null) },
                    title = stringResource(R.string.quartz_apps_watcher),
                    description = {
                        DescriptionText(
                            stringResource(
                                if (watcherOn) R.string.quartz_apps_watcher_on
                                else R.string.quartz_apps_watcher_off
                            )
                        )
                    },
                    onClick = { context.open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                )
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.QueryStats, contentDescription = null) },
                    title = stringResource(R.string.quartz_apps_usage),
                    description = {
                        DescriptionText(
                            stringResource(
                                if (usageOn) R.string.quartz_apps_usage_on
                                else R.string.quartz_apps_usage_off
                            )
                        )
                    },
                    onClick = { context.open(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                )
                if (!watcherOn && !usageOn) {
                    Text(
                        stringResource(R.string.quartz_apps_no_detection),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        item {
            GroupLabel(
                stringResource(R.string.quartz_apps_rules),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(data.rules, key = { it.id }) { rule ->
            val tunnel = tunnels.firstOrNull { it.id == rule.tunnelId }
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                title = tunnel?.name ?: stringResource(R.string.quartz_apps_tunnel_missing),
                description = {
                    DescriptionText(rule.apps.joinToString(", ") { it.label }, maxLines = 2)
                },
                onClick = { editing = rule.id },
            )
        }
        item {
            Column {
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    title = stringResource(R.string.quartz_apps_add_rule),
                    onClick = { editing = NEW },
                )
                DescriptionText(
                    stringResource(R.string.quartz_apps_notes),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private const val NEW = "new"

private fun Context.open(action: String) {
    runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun AppRef.key() = "$packageName@$user"

@Composable
private fun AppRuleEditor(
    rule: AppRule,
    tunnels: List<TunnelConfig>,
    seen: List<AppRef>,
    onSave: (AppRule) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var tunnelId by rememberSaveable(rule.id) { mutableIntStateOf(rule.tunnelId) }
    var selected by remember(rule.id) { mutableStateOf(rule.apps.associateBy { it.key() }) }
    var search by rememberSaveable(rule.id) { mutableStateOf("") }
    val catalog by
        produceState<Map<AppProfile, List<CatalogApp>>?>(null, seen) {
            value = withContext(Dispatchers.IO) { AppCatalog.load(context, seen) }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            GroupLabel(
                stringResource(R.string.quartz_apps_tunnel),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(tunnels, key = { "t" + it.id }) { tunnel ->
            SurfaceRow(
                title = tunnel.name,
                trailing = {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        RadioButton(selected = tunnelId == tunnel.id, onClick = { tunnelId = tunnel.id })
                    }
                },
                onClick = { tunnelId = tunnel.id },
            )
        }
        item {
            Column {
                GroupLabel(
                    stringResource(R.string.quartz_apps_apps, selected.size),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ConfigurationTextBox(
                    value = search,
                    label = stringResource(R.string.quartz_apps_search),
                    hint = "",
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        val all = catalog
        if (all == null) {
            item { DescriptionText("…", modifier = Modifier.padding(16.dp)) }
        } else {
            all.forEach { (profile, apps) ->
                val shown =
                    apps.filter {
                        search.isBlank() ||
                            it.ref.label.contains(search, true) ||
                            it.ref.packageName.contains(search, true)
                    }
                if (shown.isEmpty()) return@forEach
                profileSection(profile, shown, selected) { app, checked ->
                    selected =
                        if (checked) selected + (app.key() to app) else selected - app.key()
                }
            }
            item {
                DescriptionText(
                    stringResource(R.string.quartz_apps_hidden_profiles),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Column {
                Spacer(Modifier.height(8.dp))
                val ready = tunnels.any { it.id == tunnelId } && selected.isNotEmpty()
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Save, contentDescription = null) },
                    title = stringResource(R.string.quartz_apps_save),
                    description =
                        if (!ready) {
                            { DescriptionText(stringResource(R.string.quartz_apps_save_hint)) }
                        } else null,
                    enabled = ready,
                    onClick = {
                        if (ready)
                            onSave(rule.copy(tunnelId = tunnelId, apps = selected.values.toList()))
                    },
                )
                if (rule.id != NEW) {
                    SurfaceRow(
                        leading = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        title = stringResource(R.string.quartz_apps_delete),
                        onClick = onDelete,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun LazyListScope.profileSection(
    profile: AppProfile,
    apps: List<CatalogApp>,
    selected: Map<String, AppRef>,
    onToggle: (AppRef, Boolean) -> Unit,
) {
    item(key = "p" + profile.name) {
        GroupLabel(
            stringResource(profileName(profile)),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    items(apps, key = { "a" + it.ref.key() }) { app ->
        val checked = app.ref.key() in selected
        SurfaceRow(
            leading = {
                if (app.icon != null)
                    Image(
                        painter = rememberDrawablePainter(app.icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                else Icon(Icons.Outlined.Apps, contentDescription = null, Modifier.size(24.dp))
            },
            title = app.ref.label,
            description = { DescriptionText(app.ref.packageName, maxLines = 1) },
            trailing = {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(app.ref, it) })
                }
            },
            onClick = { onToggle(app.ref, !checked) },
        )
    }
}

private fun profileName(profile: AppProfile) =
    when (profile) {
        AppProfile.MAIN -> R.string.quartz_profile_main
        AppProfile.WORK -> R.string.quartz_profile_work
        AppProfile.PRIVATE -> R.string.quartz_profile_private
        AppProfile.SECURE_FOLDER -> R.string.quartz_profile_secure_folder
        AppProfile.DUAL -> R.string.quartz_profile_dual
        AppProfile.OTHER -> R.string.quartz_profile_other
    }
