package com.zaneschepke.wireguardautotunnel.quartz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.ui.LocalIsAndroidTV
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.util.StringValue
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import com.zaneschepke.wireguardautotunnel.viewmodel.SupportViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private const val QUARTZ_RELEASES_URL = "https://github.com/thebingchilling/quartz/releases"

// Replaces upstream's SupportScreen on the info tab: only About and Updates. Updates open
// Quartz's releases page; upstream's in-app updater checks wgtunnel/android.
@Composable
fun QuartzInfoScreen(viewModel: SupportViewModel = koinViewModel()) {
    val context = LocalContext.current
    val isTv = LocalIsAndroidTV.current
    val scope = rememberCoroutineScope()
    val clipboardManager = rememberClipboardHelper()

    val version = remember {
        "v${BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) "-debug" else ""}"
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (isTv) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Column {
            GroupLabel(stringResource(R.string.about), Modifier.padding(horizontal = 16.dp))
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                title = stringResource(R.string.app_name),
                description = {
                    Column {
                        DescriptionText(stringResource(R.string.version_template, version))
                        DescriptionText(
                            stringResource(R.string.flavor_template, BuildConfig.FLAVOR)
                        )
                    }
                },
                onClick = { clipboardManager.copy(version) },
                modifier = Modifier.focusRequester(focusRequester),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.InstallMobile, contentDescription = null) },
                title = stringResource(R.string.check_for_update),
                trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                onClick = {
                    context.openWebUrl(QUARTZ_RELEASES_URL).onFailure {
                        scope.launch {
                            viewModel.postSideEffect(
                                GlobalSideEffect.Snackbar(
                                    StringValue.StringResource(R.string.no_browser_detected),
                                    ToastType.Error,
                                )
                            )
                        }
                    }
                },
            )
        }
    }
}
