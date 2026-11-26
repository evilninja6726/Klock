package com.klock.app.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

enum class DeviceMemoryTier(
    val label: String,
    val maxNetworkConcurrency: Int,
    val maxResponseBytes: Long,
    val historyRetentionCount: Int,
    val enableSubtleTransitions: Boolean
) {
    /** 2 GB RAM & Android Go class devices (<= 2.6 GB physical RAM or isLowRamDevice). */
    LOW_RAM(
        label = "2 GB Low-Memory Profile",
        maxNetworkConcurrency = 2,
        maxResponseBytes = 196_608L, // 192 KB hard cap per HTTP payload
        historyRetentionCount = 200,
        enableSubtleTransitions = false
    ),

    /** Mainstream 3 GB - 6 GB RAM phones. */
    STANDARD_RAM(
        label = "Standard Profile (3–6 GB RAM)",
        maxNetworkConcurrency = 3,
        maxResponseBytes = 393_216L, // 384 KB
        historyRetentionCount = 400,
        enableSubtleTransitions = true
    ),

    /** 8 GB - 16 GB+ RAM devices. */
    HIGH_RAM(
        label = "High-Capacity Profile (8–16 GB RAM)",
        maxNetworkConcurrency = 5,
        maxResponseBytes = 524_288L, // 512 KB
        historyRetentionCount = 800,
        enableSubtleTransitions = true
    )
}

data class DeviceHardwareProfile(
    val totalRamMb: Long,
    val isLowRamFlag: Boolean,
    val tier: DeviceMemoryTier,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int
)

object MemoryTierDetector {

    private const val MB = 1024L * 1024L

    fun classify(totalRamBytes: Long, isLowRamDevice: Boolean): DeviceMemoryTier {
        val totalMb = totalRamBytes / MB
        return when {
            isLowRamDevice || totalMb <= 2700L -> DeviceMemoryTier.LOW_RAM
            totalMb <= 6500L -> DeviceMemoryTier.STANDARD_RAM
            else -> DeviceMemoryTier.HIGH_RAM
        }
    }

    fun detect(context: Context): DeviceHardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalBytes = if (memInfo.totalMem > 0L) memInfo.totalMem else 2048L * MB
        val lowRam = am?.isLowRamDevice ?: false
        val tier = classify(totalBytes, lowRam)
        return DeviceHardwareProfile(
            totalRamMb = totalBytes / MB,
            isLowRamFlag = lowRam,
            tier = tier,
            manufacturer = Build.MANUFACTURER ?: "Generic",
            model = Build.MODEL ?: "Android",
            sdkInt = Build.VERSION.SDK_INT
        )
    }
}

enum class OemVendor {
    XIAOMI_REDMI_POCO,
    SAMSUNG,
    MOTOROLA,
    OPPO_REALME_ONEPLUS,
    VIVO_IQOO,
    HUAWEI_HONOR,
    STANDARD_AOSP
}

data class OemGuidance(
    val vendor: OemVendor,
    val vendorDisplayName: String,
    val summary: String,
    val checklist: List<String>,
    val isBatteryUnrestricted: Boolean
)

object OemCompatibilityAdvisor {

    fun classifyManufacturer(manufacturer: String, brand: String = ""): OemVendor {
        val token = "${manufacturer.lowercase(Locale.ROOT)} ${brand.lowercase(Locale.ROOT)}"
        return when {
            token.contains("xiaomi") || token.contains("redmi") || token.contains("poco") ->
                OemVendor.XIAOMI_REDMI_POCO
            token.contains("samsung") ->
                OemVendor.SAMSUNG
            token.contains("motorola") || token.contains("moto") || token.contains("lenovo") ->
                OemVendor.MOTOROLA
            token.contains("oneplus") || token.contains("oppo") || token.contains("realme") ->
                OemVendor.OPPO_REALME_ONEPLUS
            token.contains("vivo") || token.contains("iqoo") ->
                OemVendor.VIVO_IQOO
            token.contains("huawei") || token.contains("honor") ->
                OemVendor.HUAWEI_HONOR
            else ->
                OemVendor.STANDARD_AOSP
        }
    }

    fun buildGuidance(
        context: Context,
        manufacturerOverride: String? = null,
        brandOverride: String? = null
    ): OemGuidance {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }

        val vendor = classifyManufacturer(
            manufacturer = manufacturerOverride ?: (Build.MANUFACTURER ?: ""),
            brand = brandOverride ?: (Build.BRAND ?: "")
        )

        return when (vendor) {
            OemVendor.XIAOMI_REDMI_POCO -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "Xiaomi / Redmi / POCO (MIUI & HyperOS)",
                summary = "MIUI and HyperOS restrict background autostart and lock-screen activity popups by default.",
                checklist = listOf(
                    "Enable 'Autostart' (Background autostart) for Klock in App Info.",
                    "In 'Other permissions', enable 'Show on Lock screen' and 'Display pop-up windows while running in the background'.",
                    "Set Battery Saver to 'No restrictions' so overnight alarms are not throttled."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
            OemVendor.SAMSUNG -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "Samsung (One UI)",
                summary = "One UI's 'Deep sleeping apps' list can suppress scheduled receivers if an app is unopened for several days.",
                checklist = listOf(
                    "Set Klock's Battery usage to 'Unrestricted' in App Info -> Battery.",
                    "In Device Care -> Battery -> Background usage limits, add Klock to 'Never auto sleeping apps'.",
                    "On Android 14+, ensure 'Allow full screen notifications' is enabled."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
            OemVendor.MOTOROLA -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "Motorola (My UX / Hello UI)",
                summary = "Motorola follows near-AOSP AlarmManager behavior with Adaptive Battery background limits on low-RAM models.",
                checklist = listOf(
                    "Set Battery usage to 'Unrestricted' in App Info -> App battery usage.",
                    "Ensure Lock-screen notifications and Full-screen intents are allowed.",
                    "Avoid force-stopping Klock from App Info (force-stop clears OS alarm registrations until reopened)."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
            OemVendor.OPPO_REALME_ONEPLUS -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "OnePlus / OPPO / Realme (OxygenOS & ColorOS)",
                summary = "ColorOS/OxygenOS aggressively freezes background services after screen-off unless auto-launch is permitted.",
                checklist = listOf(
                    "Enable 'Allow background activity' and 'Allow auto-launch' in App Info -> Battery usage.",
                    "Grant 'Display over lock screen' / Full-screen notification permission."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
            OemVendor.VIVO_IQOO -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "Vivo / iQOO (FuntouchOS)",
                summary = "FuntouchOS requires explicit Autostart and High background power permission for reliable lock-screen alarms.",
                checklist = listOf(
                    "Enable 'Autostart' and 'Show on lock screen' in App Info -> Permissions.",
                    "Set Background power consumption management to 'Unrestricted'."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
            OemVendor.HUAWEI_HONOR -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "Huawei / Honor (EMUI & MagicOS)",
                summary = "App Launch automatic management blocks boot recovery unless switched to manual control.",
                checklist = listOf(
                    "In Battery -> App launch, turn off 'Manage automatically' for Klock and enable Auto-launch + Run in background."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
            OemVendor.STANDARD_AOSP -> OemGuidance(
                vendor = vendor,
                vendorDisplayName = "Standard Android",
                summary = "Uses standard Android AlarmClock exact scheduling and Doze wakeups.",
                checklist = listOf(
                    "Set Battery usage to 'Unrestricted' for maximum overnight reliability.",
                    "Keep Notification and Full-screen alarm permissions enabled."
                ),
                isBatteryUnrestricted = batteryUnrestricted
            )
        }
    }

    fun buildBatterySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
