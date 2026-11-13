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
                Log.i("RuntimeBoot", "run-at-boot enabled; waiting for root and the runtime")
                repeat(4) { attempt ->
                    if (ContainerRuntime.isAvailable(app)) {
                        if (LinuxRuntimeInstaller.installedVersion(app) != null) {
                            Log.i("RuntimeBoot", "starting the runtime after boot")
                            ContainerRuntime.ensureStandbySupervisor(app)
                        } else {
                            Log.w("RuntimeBoot", "no installed runtime to start")
                        }
                        return@runCatching
                    }
                    Thread.sleep(3000L * (attempt + 1))
                }
                Log.w("RuntimeBoot", "root/DroidSpaces was not ready after boot")
            }.onFailure { Log.w("RuntimeBoot", "boot start failed", it) }
        }, "runtime-boot-start").start()
    }
}
