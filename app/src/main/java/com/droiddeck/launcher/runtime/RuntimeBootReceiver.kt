package com.droiddeck.launcher.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.droiddeck.launcher.runtime.container.ContainerRuntime

/** Starts the selected runtime after boot when the user enabled "run at boot". */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!RuntimePrefs.runAtBoot(context)) return
        val app = context.applicationContext
        Thread({
            runCatching {
                RuntimeStatus.load(app)
                if (!RuntimeStatus.checked || !RuntimeStatus.root || !RuntimeStatus.droidspaces) return@runCatching
                Log.i("RuntimeBoot", "starting the runtime after boot")
                ContainerRuntime.startMaintenance(app)
            }.onFailure { Log.w("RuntimeBoot", "boot start failed", it) }
        }, "runtime-boot-start").start()
    }
}
