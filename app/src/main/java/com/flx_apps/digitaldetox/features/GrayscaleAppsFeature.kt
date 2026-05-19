package com.flx_apps.digitaldetox.features

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flx_apps.digitaldetox.MainActivity
import com.flx_apps.digitaldetox.R
import com.flx_apps.digitaldetox.data.DataStoreProperty
import com.flx_apps.digitaldetox.feature_types.AppExceptionListType
import com.flx_apps.digitaldetox.feature_types.Feature
import com.flx_apps.digitaldetox.feature_types.FeatureTexts
import com.flx_apps.digitaldetox.feature_types.NeedsPermissionsFeature
import com.flx_apps.digitaldetox.feature_types.OnAppOpenedSubscriptionFeature
import com.flx_apps.digitaldetox.feature_types.OnScreenTurnedOffSubscriptionFeature
import com.flx_apps.digitaldetox.feature_types.ScreenTimeTrackingFeature
import com.flx_apps.digitaldetox.feature_types.SupportsAppExceptionsFeature
import com.flx_apps.digitaldetox.feature_types.SupportsScheduleFeature
import com.flx_apps.digitaldetox.features.GrayscaleAppsFeature.eventuallyIncreaseUsedUpScreenTime
import com.flx_apps.digitaldetox.features.GrayscaleAppsFeature.onAppOpened
import com.flx_apps.digitaldetox.ui.screens.feature.grayscale_apps.GrayscaleAppsFeatureSettingsSection
import com.flx_apps.digitaldetox.ui.screens.nav_host.NavViewModel
import com.flx_apps.digitaldetox.ui.screens.nav_host.NavigationRoutes
import com.flx_apps.digitaldetox.ui.screens.permissions_required.GrantPermissionsCommand
import com.flx_apps.digitaldetox.util.AccessibilityEventUtil
import com.flx_apps.digitaldetox.util.NavigationUtil
import timber.log.Timber

const val DISPLAY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
const val DISPLAY_DALTONIZER = "accessibility_display_daltonizer"
const val EXTRA_DIM = "reduce_bright_colors_activated"

val GrayscaleAppsFeatureId = Feature.createId(GrayscaleAppsFeature::class.java)

/**
 * The grayscale feature can be used to turn the screen grayscale depending on the schedule and
 * which app is currently in the foreground.
 */
object GrayscaleAppsFeature : Feature(), OnAppOpenedSubscriptionFeature,
    OnScreenTurnedOffSubscriptionFeature,
    SupportsScheduleFeature by SupportsScheduleFeature.Impl(GrayscaleAppsFeatureId),
    SupportsAppExceptionsFeature by SupportsAppExceptionsFeature.Impl(GrayscaleAppsFeatureId),
    ScreenTimeTrackingFeature by ScreenTimeTrackingFeature.Impl(GrayscaleAppsFeatureId),
    NeedsPermissionsFeature {
    override val texts: FeatureTexts = FeatureTexts(
        R.string.feature_grayscale,
        R.string.feature_grayscale_subtitle,
        R.string.feature_grayscale_description,
    )
    override val iconRes: Int = R.drawable.ic_contrast
    override val settingsContent: @Composable () -> Unit = { GrayscaleAppsFeatureSettingsSection() }

    override fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val granted = notificationManager.isNotificationPolicyAccessGranted
            Timber.d("debug_grayscale: hasPermissions (API 35+) = $granted")
            granted
        } else {
            val granted = context.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                    PackageManager.PERMISSION_GRANTED
            Timber.d("debug_grayscale: hasPermissions (Legacy) = $granted")
            granted
        }
    }

    override fun requestPermissions(context: Context, navViewModel: NavViewModel) {
        Timber.d("debug_grayscale: requestPermissions triggered (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            NavigationUtil.openDoNotDisturbSystemSettings(context)
        } else {
            navViewModel.openRoute(
                NavigationRoutes.PermissionsRequired(
                    GrantPermissionsCommand(
                        command = context.getString(R.string.rootCommand_grantWriteSecuritySettingsPermission),
                        supportsShizuku = true
                    )
                )
            )
        }
    }

    /**
     * Represents, whether the grayscale filter is currently active.
     * We use this variable in order to avoid unnecessary calls to the system settings (i.e. only
     * call the system settings when the grayscale filter should be turned on or off).
     */
    private var isCurrentlyGrayscale: Boolean = false

    /**
     * The ID of the AutomaticZenRule used for grayscale on Android 15+.
     */
    private var grayscaleRuleId: String? by DataStoreProperty(
        stringPreferencesKey("${id}_ruleId"), null
    )

    /**
     * We use this for people who use a color filter for color blindness. If the user has a color
     * filter enabled, we want to save the current filter and restore it when the grayscale filter
     * is turned off.
     * -1 means that the user does not have a color filter enabled.
     */
    private var defaultDaltonizer: Int = -1

    /**
     * We use this to check whether the daltonizer was enabled before we turned on the grayscale
     * filter. If it was not enabled, we want to disable it again when the grayscale filter is
     * turned off.
     */
    private var defaultDaltonizerEnabled: Int = 0

    /**
     * Whether the extra dim filter should be turned on when the grayscale filter is active.
     */
    var extraDim: Boolean by DataStoreProperty(
        booleanPreferencesKey("${id}_extraDim"), true
    )

    /**
     * Whether the grayscale filter should be ignored when the current app is not in full screen mode.
     * This is the case for example when the keyboard, sound controls or the notification bar are
     * shown.
     */
    var ignoreNonFullScreenApps: Boolean by DataStoreProperty(
        booleanPreferencesKey("${id}_ignoreNonFullScreenApps"), true
    )

    /**
     * The allowed daily color screen time in milliseconds. Once this limit is reached, the grayscale
     * filter will be turned on and will stay on until the next day.
     */
    var allowedDailyColorScreenTime: Long by DataStoreProperty(
        longPreferencesKey("${id}_allowedDailyColorScreenTime"), 0L
    )

    /**
     * On start, we trigger [onAppOpened] once to turn the grayscale filter on or off depending on
     * the current app. We also read the actual system state to initialize [isCurrentlyGrayscale],
     * so that saved defaults are not overwritten if the service (re-) starts while grayscale is active.
     */
    override fun onStart(context: Context) {
        Timber.d("debug_grayscale: onStart triggered")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ruleId = grayscaleRuleId
            Timber.d("debug_grayscale: grayscaleRuleId = $ruleId")
            isCurrentlyGrayscale = if (ruleId != null) {
                val rule = notificationManager.getAutomaticZenRule(ruleId)
                Timber.d("debug_grayscale: Existing Zen rule found: ${rule != null}")
                false 
            } else {
                false
            }
        } else {
            val contentResolver = context.contentResolver
            isCurrentlyGrayscale =
                Settings.Secure.getInt(contentResolver, DISPLAY_DALTONIZER_ENABLED, 0) == 1 &&
                        Settings.Secure.getInt(contentResolver, DISPLAY_DALTONIZER, -1) == 0
            Timber.d("debug_grayscale: isCurrentlyGrayscale (Legacy) = $isCurrentlyGrayscale")
        }
        val accessibilityEvent = AccessibilityEventUtil.createEvent()
        onAppOpened(context, accessibilityEvent.packageName.toString(), accessibilityEvent)
    }

    /**
     * On a pause, turn the grayscale filter off.
     */
    override fun onPause(context: Context) {
        setGrayscale(context, false)
    }

    /**
     * If an app is opened, turn the grayscale filter on or off depending on the app that is
     * currently in the foreground.
     */
    override fun onAppOpened(
        context: Context, packageName: String, accessibilityEvent: AccessibilityEvent
    ) {
        if (ignoreNonFullScreenApps && !accessibilityEvent.isFullScreen) {
            // we are not in full screen mode, so we do not want to interfere with the app
            return
        }
        val exceptionsContainApp = appExceptions.contains(packageName)
        // we want to turn the grayscale filter on if:
        // - the allowed screen time is exceeded and
        //   - either the app is not in the exceptions list and the exceptions list is a "not-list",
        //   - or if the app is in the exceptions list and the exceptions list is an "only-list"
        val shouldBeGrayscale =
            (!exceptionsContainApp && appExceptionListType == AppExceptionListType.NOT_LIST) || (exceptionsContainApp && appExceptionListType == AppExceptionListType.ONLY_LIST)

        Timber.d("debug_grayscale: onAppOpened - packageName: $packageName, shouldBeGrayscale: $shouldBeGrayscale, isCurrentlyGrayscale: $isCurrentlyGrayscale")

        if (!shouldBeGrayscale) {
            // the grayscale filter should not be turned on, so we increase the used up screen time
            eventuallyIncreaseUsedUpScreenTime()
        } else {
            eventuallyStartTracking()
            if (allowedDailyColorScreenTime > 0 && usedUpScreenTime <= allowedDailyColorScreenTime) {
                // the screen time has not been used up yet, so we do not need to turn the grayscale
                // filter on
                Timber.d("debug_grayscale: onAppOpened - Color screen time not yet used up")
                return
            }
        }

        if (shouldBeGrayscale != isCurrentlyGrayscale) {
            // only call the system settings if the grayscale filter state should be changed
            Timber.d("debug_grayscale: onAppOpened - Toggling grayscale to $shouldBeGrayscale")
            setGrayscale(context, shouldBeGrayscale)
            isCurrentlyGrayscale = shouldBeGrayscale
        }
    }

    /**
     * When the screen is turned off, the used up screen time is increased by the time since the
     * last grayscale app was opened.
     * @see eventuallyIncreaseUsedUpScreenTime
     */
    override fun onScreenTurnedOff(context: Context?) {
        eventuallyIncreaseUsedUpScreenTime()
    }

    /**
     * Function to turn the grayscale filter on or off.
     * The grayscale filter is a system-wide setting. In order for it to work, the app needs to
     * have the WRITE_SECURE_SETTINGS permission. This can be granted by running
     * `adb shell pm grant com.flx_apps.digitaldetox android.permission.WRITE_SECURE_SETTINGS`.
     *
     * Starting from Android 15, we use the AutomaticZenRule API which is more modern and doesn't
     * interfere with the user's global accessibility settings.
     *
     * @param context The context.
     * @param grayscale Whether the grayscale filter should be turned on.
     * @return Whether the operation was successful.
     */
    private fun setGrayscale(
        context: Context, grayscale: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return setGrayscaleZenMode(context, grayscale)
        }

        val contentResolver = context.contentResolver

        if (grayscale && !isCurrentlyGrayscale) {
            // save the current color correction state (enabled + mode)
            // Only do this when currently not in grayscale, to not save grayscale as a default.
            defaultDaltonizerEnabled = Settings.Secure.getInt(
                contentResolver, DISPLAY_DALTONIZER_ENABLED, 0
            )
            defaultDaltonizer = Settings.Secure.getInt(
                contentResolver, DISPLAY_DALTONIZER, -1
            )
        }

        // enable/disable grayscale or restore previous state
        val result1 = Settings.Secure.putInt(
            contentResolver,
            DISPLAY_DALTONIZER_ENABLED,
            if (grayscale) 1 else defaultDaltonizerEnabled
        )
        val result2 = Settings.Secure.putInt(
            contentResolver, DISPLAY_DALTONIZER, if (grayscale) 0 else defaultDaltonizer
        )
        var result3 = true
        if (extraDim) {
            // eventually enable/disable extra dim mode
            result3 = setExtraDim(context, grayscale)
        }
        if (result1 && result2 && result3) {
            isCurrentlyGrayscale = grayscale
            return true // everything went fine
        }
        return false // something went wrong
    }

    /**
     * Uses the Android 15 AutomaticZenRule API to toggle grayscale.
     */
    private fun setGrayscaleZenMode(context: Context, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false

        Timber.d("debug_grayscale: setGrayscaleZenMode triggered - enabled: $enabled")
        val ruleId = getOrRegisterGrayscaleRule(context)
        Timber.d("debug_grayscale: setGrayscaleZenMode - obtained ruleId: $ruleId")
        if (ruleId == null) return false
        
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val conditionId = Uri.parse("condition://com.flx_apps.digitaldetox/grayscale")
        Timber.d("debug_grayscale: setGrayscaleZenMode - setting rule state to ${if (enabled) "STATE_TRUE" else "STATE_FALSE"}")
        notificationManager.setAutomaticZenRuleState(
            ruleId,
            Condition(
                conditionId,
                if (enabled) "Grayscale Active" else "Grayscale Inactive",
                if (enabled) Condition.STATE_TRUE else Condition.STATE_FALSE
            )
        )
        
        // Also toggle extra dim if requested
        if (extraDim) {
            Timber.d("debug_grayscale: setGrayscaleZenMode - Toggling extra dim to $enabled")
            setExtraDim(context, enabled)
        }
        
        isCurrentlyGrayscale = enabled
        return true
    }

    /**
     * Registers the AutomaticZenRule for grayscale if it doesn't exist.
     */
    private fun getOrRegisterGrayscaleRule(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Check if we already have a valid rule ID
        grayscaleRuleId?.let { id ->
            val rule = notificationManager.getAutomaticZenRule(id)
            Timber.d("debug_grayscale: getOrRegisterGrayscaleRule - existing rule ID $id valid: ${rule != null}")
            if (rule != null) {
                return id
            }
        }

        Timber.d("debug_grayscale: getOrRegisterGrayscaleRule - registering NEW rule")
        // Create a new rule
        val deviceEffects = ZenDeviceEffects.Builder()
            .setShouldDisplayGrayscale(true)
            .build()

        val conditionId = Uri.parse("condition://com.flx_apps.digitaldetox/grayscale")
        val rule = AutomaticZenRule.Builder("DetoxDroid Grayscale", conditionId)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            .setDeviceEffects(deviceEffects)
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setEnabled(true)
            .build()

        return try {
            val newId = notificationManager.addAutomaticZenRule(rule)
            Timber.d("debug_grayscale: getOrRegisterGrayscaleRule - rule added with ID $newId")
            grayscaleRuleId = newId
            newId
        } catch (e: Exception) {
            Timber.e(e, "debug_grayscale: getOrRegisterGrayscaleRule - Error adding rule")
            null
        }
    }


    /**
     * Function to turn the extra dim filter on or off.
     * The extra dim filter is a system-wide setting. In order for it to work, the app needs to
     * have the WRITE_SECURE_SETTINGS permission. This can be granted by running
     * `adb shell pm grant com.flx_apps.digitaldetox android.permission.WRITE_SECURE_SETTINGS`.
     * @param context The context.
     * @param extraDim Whether the extra dim filter should be turned on.
     * @return Whether the operation was successful.
     */
    private fun setExtraDim(
        context: Context, extraDim: Boolean
    ): Boolean {
        val contentResolver = context.contentResolver
        return Settings.Secure.putInt(
            contentResolver, EXTRA_DIM, if (extraDim) 1 else 0
        )
    }
}
