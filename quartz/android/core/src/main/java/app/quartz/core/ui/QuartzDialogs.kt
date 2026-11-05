package app.quartz.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.warp.WarpRepository
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * App-wide Quartz dialogs, shown from one line in upstream's MainActivity. For now: an imported
 * WARP file whose slot already has that variant asks **Replace existing** or **Import as separate**
 * (FORK.md §9). Dismissing asks again next time the app starts.
 */
@Composable
fun QuartzDialogs(warpRepository: WarpRepository = koinInject()) {
    val conflicts by warpRepository.importConflicts.collectAsStateWithLifecycle()
    var postponed by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    val scope = rememberCoroutineScope()
    val conflict =
        remember(conflicts, postponed) { conflicts.firstOrNull { it.importedId !in postponed } }
            ?: return

    InfoDialog(
        title = stringResource(R.string.quartz_warp_import_conflict_title),
        body = {
            Text(
                stringResource(
                    R.string.quartz_warp_import_conflict_body,
                    conflict.importedName,
                    conflict.slot,
                    conflict.existingName,
                )
            )
        },
        confirmText = stringResource(R.string.quartz_warp_replace_existing),
        onAttest = { scope.launch { warpRepository.replaceExisting(conflict) } },
        dismissText = stringResource(R.string.quartz_warp_import_separately),
        onDismissButton = { scope.launch { warpRepository.importSeparately(conflict) } },
        onDismiss = { postponed = postponed + conflict.importedId },
    )
}
