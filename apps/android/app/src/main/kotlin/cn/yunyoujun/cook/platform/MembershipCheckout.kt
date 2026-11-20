package cn.yunyoujun.cook.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cn.yunyoujun.cook.BuildConfig

internal fun Context.canOpenWebMembershipCheckout(): Boolean {
    if (!BuildConfig.COOK_WEB_MEMBERSHIP_CHECKOUT) return false
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            source.installingPackageName != PLAY_STORE && source.initiatingPackageName != PLAY_STORE
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName) != PLAY_STORE
        }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private const val PLAY_STORE = "com.android.vending"
