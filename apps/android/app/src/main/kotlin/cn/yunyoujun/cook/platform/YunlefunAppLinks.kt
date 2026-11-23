package cn.yunyoujun.cook.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import cn.yunyoujun.cook.data.remote.YunlefunSSO

private const val YUNLEFUN_APP = "fun.yunle.apps"
private const val AUTHORIZATION_HOST = "www.yunle.fun"

internal fun Context.canOpenYunlefunAuthorizationApp(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val manager = getSystemService(DomainVerificationManager::class.java) ?: return false
    // The authorization provider only delivers the result to a native callback handler.
    return manager.allowsVerifiedLinks(packageName, YunlefunSSO.CALLBACK_HOST) &&
        manager.allowsVerifiedLinks(YUNLEFUN_APP, AUTHORIZATION_HOST)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun DomainVerificationManager.allowsVerifiedLinks(app: String, host: String): Boolean {
    val state = try {
        getDomainVerificationUserState(app)
    } catch (_: PackageManager.NameNotFoundException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
    return state?.isLinkHandlingAllowed == true &&
        state.hostToStateMap[host] == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
}

internal fun Context.openYunlefunAuthorizationApp(url: String): Boolean {
    if (!canOpenYunlefunAuthorizationApp()) return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(YUNLEFUN_APP)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
