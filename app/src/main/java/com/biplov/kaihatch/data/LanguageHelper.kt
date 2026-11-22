package com.biplov.kaihatch.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.biplov.kaihatch.model.AppLanguage
import java.util.Locale

/**
 * Lightweight app-wide language manager for KaiHatch.
 *
 * Works seamlessly with [androidx.activity.ComponentActivity] across:
 * - Android 7.0+ (API 24+): via [Configuration.setLocales] and [Context.createConfigurationContext]
 * - Android 13+ (API 33+): via platform [LocaleManager.setApplicationLocales] and [LocaleManager.getApplicationLocales]
 */
object LanguageHelper {

    const val PREFS_NAME = "kaihatch_settings"
    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_LOCALE_MANAGER_SYNCED = "locale_manager_synced"

    private val initialJvmDefaultLocale: Locale = Locale.getDefault()

    @Volatile
    private var lastAppliedSystemLocales: LocaleList? = null

    /**
     * Optional override for unit tests to simulate any device system language tag
     * (e.g. "es-MX", "hi-IN", "zh-Hant-HK") without mutating global OS resources.
     */
    @Volatile
    var systemLanguageTagOverrideForTesting: String? = null

    /**
     * Returns the true primary device system language tag (BCP-47), unaffected by in-app
     * [Locale.setDefault] or Android 13+ per-app [LocaleManager.setApplicationLocales] overrides.
     */
    fun getDeviceSystemLanguageTag(context: Context? = null): String {
        systemLanguageTagOverrideForTesting?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context != null) {
            val osSystemLocales = getDeviceOsSystemLocales(context)
            if (osSystemLocales != null && !osSystemLocales.isEmpty) {
                val primary = osSystemLocales[0]
                if (primary != null) {
                    return primary.toLanguageTag()
                }
            }
        }

        try {
            val sysLocales = Resources.getSystem().configuration.locales
            if (sysLocales != null && !sysLocales.isEmpty) {
                val primary = sysLocales[0]
                if (primary != null) {
                    return primary.toLanguageTag()
                }
            }
        } catch (_: Throwable) {
            // Fallback in unit test environments where Resources.getSystem() is not initialized
        }

        return initialJvmDefaultLocale.toLanguageTag()
    }

    /**
     * Matches the device's current system language against the 32 supported [AppLanguage] locales
     * (including regional variants), returning `null` if the device language is unsupported.
     */
    fun matchDeviceSystemLanguage(context: Context? = null): AppLanguage? {
        val sysTag = getDeviceSystemLanguageTag(context)
        return AppLanguage.matchSupportedLanguage(sysTag)
    }

    /**
     * Returns `true` if the device's current system language (or regional variant) is supported
     * by KaiHatch's 32 translations, or `false` if unsupported.
     */
    fun isDeviceLanguageSupported(context: Context? = null): Boolean {
        return matchDeviceSystemLanguage(context) != null
    }

    /**
     * Pure resolution logic for determining the active [AppLanguage] given persistence state and
     * the device's system language tag:
     * - Fresh installs (`hasUserSavedPreference == false`):
     *   - Supported device language -> [AppLanguage.SYSTEM]
     *   - Unsupported device language -> [AppLanguage.ENGLISH]
     * - Existing user manual selection (`hasUserSavedPreference == true`):
     *   - Explicit language (e.g., `SPANISH`, `ENGLISH`, `JAPANESE`) -> never overridden!
     *   - `SYSTEM` -> [AppLanguage.SYSTEM] when device language is supported, or falls back to
     *     [AppLanguage.ENGLISH] while device language is unsupported.
     */
    fun resolveEffectiveLanguageForState(
        hasUserSavedPreference: Boolean,
        savedPreferenceRaw: String?,
        systemLanguageTag: String
    ): AppLanguage {
        val isSystemSupported = AppLanguage.matchSupportedLanguage(systemLanguageTag) != null
        if (!hasUserSavedPreference) {
            return if (isSystemSupported) AppLanguage.SYSTEM else AppLanguage.ENGLISH
        }
        val savedLang = AppLanguage.fromSavedValue(savedPreferenceRaw)
        return if (savedLang == AppLanguage.SYSTEM && !isSystemSupported) {
            AppLanguage.ENGLISH
        } else {
            savedLang
        }
    }

    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasSavedPref = prefs.contains(KEY_APP_LANGUAGE)
        val savedRaw = prefs.getString(KEY_APP_LANGUAGE, null)
        val onboardingCompleted = prefs.getBoolean("onboarding_completed", false)
        val localeManagerSynced = prefs.getBoolean(KEY_LOCALE_MANAGER_SYNCED, false)
        val sysTag = getDeviceSystemLanguageTag(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val systemAppLocales = getSystemApplicationLocales(context)
            if (systemAppLocales != null) {
                if (!hasSavedPref && lastAppliedSystemLocales != null && systemAppLocales == lastAppliedSystemLocales) {
                    // Preferences were cleared in-process (e.g., reset to fresh state); clear stale LocaleManager state
                    lastAppliedSystemLocales = null
                    setSystemApplicationLocales(context, LocaleList.getEmptyLocaleList())
                    return resolveEffectiveLanguageForState(
                        hasUserSavedPreference = false,
                        savedPreferenceRaw = null,
                        systemLanguageTag = sysTag
                    )
                }
                if (!systemAppLocales.isEmpty) {
                    if (systemAppLocales != lastAppliedSystemLocales) {
                        val externalMatched = AppLanguage.matchSupportedLocaleList(systemAppLocales)
                        if (externalMatched != null) {
                            lastAppliedSystemLocales = systemAppLocales
                            if (hasSavedPref || onboardingCompleted) {
                                prefs.edit()
                                    .putString(KEY_APP_LANGUAGE, externalMatched.name)
                                    .putBoolean(KEY_LOCALE_MANAGER_SYNCED, true)
                                    .apply()
                            }
                            return externalMatched
                        }
                    }
                } else {
                    // systemAppLocales is empty ("System default" in Android 13+ per-app settings)
                    val wasResetExternallyToSystem =
                        (lastAppliedSystemLocales != null && !lastAppliedSystemLocales!!.isEmpty) ||
                            (hasSavedPref && localeManagerSynced && AppLanguage.fromSavedValue(savedRaw) != AppLanguage.SYSTEM)
                    if (wasResetExternallyToSystem) {
                        lastAppliedSystemLocales = LocaleList.getEmptyLocaleList()
                        if (hasSavedPref) {
                            prefs.edit()
                                .putString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.name)
                                .putBoolean(KEY_LOCALE_MANAGER_SYNCED, true)
                                .apply()
                        }
                        return resolveEffectiveLanguageForState(
                            hasUserSavedPreference = hasSavedPref,
                            savedPreferenceRaw = AppLanguage.SYSTEM.name,
                            systemLanguageTag = sysTag
                        )
                    }
                }
            }
        }

        if (hasSavedPref) {
            val savedLang = AppLanguage.fromSavedValue(savedRaw)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val targetSystemLocales = if (savedLang == AppLanguage.SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(savedLang.tag)
                }
                lastAppliedSystemLocales = targetSystemLocales
                setSystemApplicationLocales(context, targetSystemLocales)
                if (!localeManagerSynced) {
                    prefs.edit().putBoolean(KEY_LOCALE_MANAGER_SYNCED, true).apply()
                }
            }
            return resolveEffectiveLanguageForState(
                hasUserSavedPreference = true,
                savedPreferenceRaw = savedRaw,
                systemLanguageTag = sysTag
            )
        }

        return resolveEffectiveLanguageForState(
            hasUserSavedPreference = false,
            savedPreferenceRaw = null,
            systemLanguageTag = sysTag
        )
    }

    fun saveAndApplyLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_APP_LANGUAGE, language.name)
            .putBoolean(KEY_LOCALE_MANAGER_SYNCED, true)
            .apply()
        applyLanguage(context, language, syncPlatformLocaleManager = true)
    }

    fun applyLanguage(
        context: Context,
        language: AppLanguage,
        syncPlatformLocaleManager: Boolean = false
    ) {
        val targetLocales = resolveLocaleList(language, context)
        if (!targetLocales.isEmpty) {
            LocaleList.setDefault(targetLocales)
            Locale.setDefault(targetLocales[0])
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (syncPlatformLocaleManager || prefs.contains(KEY_APP_LANGUAGE))
        ) {
            val savedRaw = prefs.getString(KEY_APP_LANGUAGE, null)
            val persistedLang = if (prefs.contains(KEY_APP_LANGUAGE)) {
                AppLanguage.fromSavedValue(savedRaw)
            } else {
                language
            }
            val appLocales = if (persistedLang == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(persistedLang.tag)
            }
            lastAppliedSystemLocales = appLocales
            setSystemApplicationLocales(context, appLocales)
        }
    }

    fun createLocalizedConfiguration(
        baseConfig: Configuration,
        language: AppLanguage,
        context: Context? = null
    ): Configuration {
        val config = Configuration(baseConfig)
        val targetLocales = resolveLocaleList(language, context)
        if (!targetLocales.isEmpty) {
            config.setLocales(targetLocales)
            config.setLayoutDirection(targetLocales[0])
        }
        return config
    }

    fun wrapContext(
        base: Context,
        language: AppLanguage = getSavedLanguage(base)
    ): Context {
        val targetLocales = resolveLocaleList(language, base)
        if (targetLocales.isEmpty) return base

        LocaleList.setDefault(targetLocales)
        Locale.setDefault(targetLocales[0])

        val config = createLocalizedConfiguration(base.resources.configuration, language, base)
        return base.createConfigurationContext(config)
    }

    fun resolveLocaleList(language: AppLanguage, context: Context? = null): LocaleList {
        val effectiveLang = when (language) {
            AppLanguage.SYSTEM -> matchDeviceSystemLanguage(context) ?: AppLanguage.ENGLISH
            else -> language
        }
        val tags = if (effectiveLang == AppLanguage.FILIPINO) {
            "fil,tl"
        } else {
            effectiveLang.tag
        }
        val parsed = LocaleList.forLanguageTags(tags)
        return if (!parsed.isEmpty) parsed else LocaleList(Locale.ENGLISH)
    }

    fun isRtlLocale(locale: Locale?): Boolean {
        if (locale == null) return false
        val lang = locale.language.lowercase(Locale.ROOT)
        if (lang == "ar" || lang == "he" || lang == "iw" || lang == "fa" || lang == "ur") {
            return true
        }
        return android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL
    }

    fun isRtlLanguage(
        language: AppLanguage,
        configuration: Configuration? = null,
        context: Context? = null
    ): Boolean {
        if (language != AppLanguage.SYSTEM) {
            return language.isRtl
        }
        val matchedSystem = matchDeviceSystemLanguage(context)
        if (matchedSystem != null) {
            return matchedSystem.isRtl
        }
        if (configuration != null && configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
            return true
        }
        return false
    }

    private fun getDeviceOsSystemLocales(context: Context): LocaleList? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.systemLocales
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSystemApplicationLocales(context: Context): LocaleList? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales
        } catch (_: Throwable) {
            null
        }
    }

    private fun setSystemApplicationLocales(context: Context, locales: LocaleList) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null && localeManager.applicationLocales != locales) {
                localeManager.applicationLocales = locales
            }
        } catch (_: Throwable) {
            // Ignored on environments where LocaleManager service is unavailable
        }
    }
}
