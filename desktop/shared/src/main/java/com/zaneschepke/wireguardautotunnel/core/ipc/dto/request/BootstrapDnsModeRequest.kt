package com.zaneschepke.wireguardautotunnel.core.ipc.dto.request

import kotlinx.serialization.Serializable

@Serializable data class BootstrapDnsModeRequest(val protocol: String, val endpoint: String? = null)
