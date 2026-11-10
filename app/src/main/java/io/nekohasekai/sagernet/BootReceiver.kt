package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isLocked = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        val isUnlocked = Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked
        if (isLocked || !isUnlocked) return

        runOnDefaultDispatcher {
            SubscriptionUpdater.reconfigureUpdater()
        }

        if (!DataStore.persistAcrossReboot) {   // sanity check
            enabled = false
            return
        }

        runOnDefaultDispatcher {
            var targetProxy = DataStore.selectedProxy
            if (targetProxy <= 0L) {
                targetProxy = DataStore.lastSelectedProxy
                if (targetProxy <= 0L) targetProxy = DataStore.currentProfile
            }

            if (targetProxy > 0L) {
                val exists = SagerDatabase.proxyDao.getById(targetProxy) != null
                if (exists) {
                    DataStore.selectedProxy = targetProxy
                    SagerNet.startService()
                } else {
                    DataStore.selectedProxy = 0L
                    DataStore.lastSelectedProxy = 0L
                    DataStore.currentProfile = 0L
                }
            }
        }
    }
}
