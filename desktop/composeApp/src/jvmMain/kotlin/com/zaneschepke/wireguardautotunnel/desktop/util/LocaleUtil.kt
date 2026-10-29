package com.zaneschepke.wireguardautotunnel.desktop.util

import com.zaneschepke.wireguardautotunnel.composeApp.BuildConfig
import java.util.Locale

object LocaleUtil {

    /**
     * BCP-47 tags with existing translations derived at build time from the composeResources
     * folders.
     */
    val supportedLocales: List<String> = BuildConfig.LANGUAGES.split(",")

    /**
     * Determines OS reported locale and matches it against [supportedLocales], falling back to en
     */
    val systemLocaleTag: String by lazy {
        val system = Locale.getDefault()
        supportedLocales.firstOrNull { it.equals(system.toLanguageTag(), ignoreCase = true) }
            ?: supportedLocales.firstOrNull {
                Locale.forLanguageTag(it).language == system.language
            }
            ?: "en"
    }
}
