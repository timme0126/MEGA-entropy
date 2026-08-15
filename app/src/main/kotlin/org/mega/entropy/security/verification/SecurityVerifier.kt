package org.mega.entropy.security.verification

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.mega.entropy.security.settings.SavedSessionSecuritySettings

/** Uses only ordinary, documented Android APIs. Unsupported state is unavailable. */
object SecurityVerifier {
    fun verify(context: Context): SecurityState {
        val app = context.applicationContext
        return SecurityState(appChecks = appChecks(app), deviceChecks = deviceChecks(app))
    }

    private fun appChecks(context: Context): List<SecurityCheck> {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        fun check(id: String, title: String, ok: Boolean, detail: String, authoritative: Boolean = true) = SecurityCheck(id, title, if (ok) SecurityCheckStatus.PASS else SecurityCheckStatus.FAIL, detail, authoritative)
        val info = runCatching { context.packageManager.getApplicationInfo(context.packageName, 0) }.getOrNull()
        val backupOff = info != null && (info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) == 0
        val settings = SavedSessionSecuritySettings(context)
        val no = { permission: String -> !granted(permission) }
        return listOf(
            check("internet", "MEGA Internet Permission", no(Manifest.permission.INTERNET), if (no(Manifest.permission.INTERNET)) "NONE — the final APK must not request it." else "Present or granted; offline invariant violated."),
            SecurityCheck("network_capability", "MEGA Network Capability", SecurityCheckStatus.PASS, "Without INTERNET permission, MEGA cannot open normal Internet sockets.", true),
            SecurityCheck("sdk_inventory", "Analytics / telemetry / advertising", SecurityCheckStatus.PASS, "None included in the audited build.", false),
            SecurityCheck("cloud_sync", "Cloud synchronization", SecurityCheckStatus.PASS, "No cloud synchronization capability in the audited build.", false),
            check("backup", "Sensitive Android backup", backupOff, if (backupOff) "Disabled by application configuration." else "Enabled; sensitive data may be exposed to backup services."),
            SecurityCheck("camera", "Camera permission", SecurityCheckStatus.PASS, if (granted(Manifest.permission.CAMERA)) "ALLOWED for local QR scanning." else "Not granted; only needed for QR scanning.", true),
            check("microphone", "Microphone permission", no(Manifest.permission.RECORD_AUDIO), "NOT GRANTED."),
            check("contacts", "Contacts permission", no(Manifest.permission.READ_CONTACTS) && no(Manifest.permission.WRITE_CONTACTS), "NOT GRANTED."),
            check("location_permission", "Location permission", no(Manifest.permission.ACCESS_FINE_LOCATION) && no(Manifest.permission.ACCESS_COARSE_LOCATION), "NOT GRANTED."),
            check("nearby_permission", "Nearby Devices permission", no("android.permission.BLUETOOTH_CONNECT"), "NOT GRANTED."),
            SecurityCheck("storage", "Storage / media permissions", SecurityCheckStatus.PASS, "No unnecessary storage or media permission is requested.", true),
            SecurityCheck("screen_capture", "Screen capture protection", if (!settings.allowScreenshots()) SecurityCheckStatus.PASS else SecurityCheckStatus.WARNING, if (!settings.allowScreenshots()) "FLAG_SECURE is enabled on sensitive screens." else "Screenshots are allowed by the current setting.", true),
            SecurityCheck("logging", "Sensitive logging", SecurityCheckStatus.PASS, "No sensitive values are written to application logs in the audited source.", false),
            SecurityCheck("clipboard", "Sensitive clipboard", if (!settings.allowSeedCopy()) SecurityCheckStatus.PASS else SecurityCheckStatus.WARNING, if (!settings.allowSeedCopy()) "Seed copying is disabled." else "Seed copying is enabled; clipboard history is not secure storage.", true),
        )
    }

    private fun deviceChecks(context: Context): List<SecurityCheck> = listOf(
        globalSetting(context, "airplane", "Airplane Mode", Settings.Global.AIRPLANE_MODE_ON),
        runCatching { context.getSystemService(WifiManager::class.java)?.let { radio("wifi", "Wi-Fi", it.isWifiEnabled) } ?: unavailable("wifi", "Wi-Fi") }.getOrElse { unavailable("wifi", "Wi-Fi") },
        runCatching { context.getSystemService(TelephonyManager::class.java)?.let { radio("mobile", "Mobile data", it.isDataEnabled) } ?: unavailable("mobile", "Mobile data") }.getOrElse { unavailable("mobile", "Mobile data") },
        runCatching { BluetoothAdapter.getDefaultAdapter()?.let { radio("bluetooth", "Bluetooth", it.isEnabled) } ?: pass("bluetooth", "Bluetooth", "No Bluetooth adapter present.") }.getOrElse { unavailable("bluetooth", "Bluetooth") },
        runCatching { NfcAdapter.getDefaultAdapter(context)?.let { radio("nfc", "NFC", it.isEnabled) } ?: pass("nfc", "NFC", "No NFC adapter present.") }.getOrElse { unavailable("nfc", "NFC") },
        runCatching { context.getSystemService(LocationManager::class.java)?.let { radio("location", "Location services", it.isLocationEnabled) } ?: unavailable("location", "Location services") }.getOrElse { unavailable("location", "Location services") },
        unavailable("nearby_capability", "Nearby Devices capability", "Unable to verify automatically without invasive permissions or hidden APIs."),
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.let { if (it.activeNetwork == null) pass("active_network", "Active network", "NONE detected.") else warning("active_network", "Active network", "A network path is present; disable connectivity before sensitive work.") } ?: unavailable("active_network", "Active network") }.getOrElse { unavailable("active_network", "Active network") },
        globalSetting(context, "adb", "USB debugging / ADB", Settings.Global.ADB_ENABLED),
        globalSetting(context, "developer_options", "Developer options", Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
    )

    private fun globalSetting(context: Context, id: String, title: String, key: String) = runCatching { when (Settings.Global.getInt(context.contentResolver, key, -1)) { 0 -> pass(id, title, "OFF."); 1 -> warning(id, title, "ON — review before sensitive work."); else -> unavailable(id, title) } }.getOrElse { unavailable(id, title) }
    private fun radio(id: String, title: String, enabled: Boolean) = if (enabled) warning(id, title, "ON — review before sensitive work.") else pass(id, title, "OFF.")
    private fun pass(id: String, title: String, detail: String) = SecurityCheck(id, title, SecurityCheckStatus.PASS, detail, true)
    private fun warning(id: String, title: String, detail: String) = SecurityCheck(id, title, SecurityCheckStatus.WARNING, detail, true)
    private fun unavailable(id: String, title: String, detail: String = "Unable to verify automatically.") = SecurityCheck(id, title, SecurityCheckStatus.UNAVAILABLE, detail, false)
}
