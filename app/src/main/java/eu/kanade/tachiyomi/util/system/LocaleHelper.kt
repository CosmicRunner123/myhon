package eu.kanade.tachiyomi.util.system

import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Utility class to change the application's language in runtime.
 */
object LocaleHelper {

    fun getDisplayName(lang: String): String {
        val normalizedLang = when (lang) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> lang
        }

        return Locale.forLanguageTag(normalizedLang).displayName
    }

    fun getShortDisplayName(lang: String?, uppercase: Boolean = false): String {
        return when (lang) {
            null -> ""
            "es-419" -> "es-la"
            "zh-CN" -> "zh-hans"
            "zh-TW" -> "zh-hant"
            else -> lang
        }
            .let { if (uppercase) it.uppercase(Locale.ENGLISH) else it }
    }

    /**
     * Returns display name of a string language code.
     *
     * @param lang empty for system language
     */
    fun getLocalizedDisplayName(lang: String?): String {
        if (lang == null) {
            return ""
        }

        val locale = when (lang) {
            "" -> LocaleListCompat.getAdjustedDefault()[0]
            "zh-CN" -> Locale.forLanguageTag("zh-Hans")
            "zh-TW" -> Locale.forLanguageTag("zh-Hant")
            else -> Locale.forLanguageTag(lang)
        }
        return locale!!.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }
}
