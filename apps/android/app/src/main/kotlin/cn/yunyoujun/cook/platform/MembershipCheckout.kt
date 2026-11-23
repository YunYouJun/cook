package cn.yunyoujun.cook.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.data.remote.PlayLedger

internal fun Context.canOpenWebMembershipCheckout(): Boolean =
    BuildConfig.COOK_WEB_MEMBERSHIP_CHECKOUT && installedThroughGooglePlay() == false

internal fun Context.playMembershipLedger(): PlayLedger? {
    val ledger = PlayLedger.entries.firstOrNull { it.wireValue == BuildConfig.COOK_PLAY_MEMBERSHIP_LEDGER }
        ?: return null
    return ledger.takeIf {
        it.acceptsInstallation(packageName, installedThroughGooglePlay(), BuildConfig.COOK_PLAY_LICENSE_TEST_SIDELOAD)
    }
}

internal fun PlayLedger.acceptsInstallation(
    packageName: String,
    installedThroughPlay: Boolean?,
    licenseTestSideload: Boolean,
): Boolean {
    if (packageName != PlayLedger.PACKAGE) return false
    return installedThroughPlay == true ||
        (installedThroughPlay == false && this == PlayLedger.SANDBOX && licenseTestSideload)
}

internal fun Context.installedThroughGooglePlay(): Boolean? =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            source.installingPackageName == PLAY_STORE || source.initiatingPackageName == PLAY_STORE
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName) == PLAY_STORE
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

private const val PLAY_STORE = "com.android.vending"
