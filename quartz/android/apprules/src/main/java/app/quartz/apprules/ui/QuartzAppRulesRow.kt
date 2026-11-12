package app.quartz.apprules.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.apprules.AppRules
import app.quartz.apprules.ForegroundApp
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import org.koin.compose.koinInject

/** Auto-tunnel screen row (one line in upstream's AutoTunnelScreen.kt) that opens [AppRulesScreen]. */
@Composable
fun QuartzAppRulesRow(rules: AppRules = koinInject()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val data by rules.data.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val detecting =
        remember(resumed) {
            ForegroundApp.watcherEnabled(context) || ForegroundApp.hasUsageAccess(context)
        }
    val description =
        when {
            data.rules.isEmpty() -> stringResource(R.string.quartz_apps_row_desc_none)
            else ->
                listOfNotNull(
                        pluralStringResource(R.plurals.quartz_apps_row_desc, data.rules.size, data.rules.size),
                        stringResource(R.string.quartz_apps_row_off).takeIf { !data.enabled },
                        stringResource(R.string.quartz_apps_row_no_detection).takeIf { !detecting },
                    )
                    .joinToString(" · ")
        }
    SurfaceRow(
        leading = { Icon(Icons.Outlined.Apps, contentDescription = null) },
        title = stringResource(R.string.quartz_apps_title),
        description = { DescriptionText(description) },
        onClick = { navController.push(Route.AppRules) },
    )
}
