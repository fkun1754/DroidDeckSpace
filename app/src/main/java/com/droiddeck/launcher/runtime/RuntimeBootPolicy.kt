package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log

/** Keeps DroidSpaces' per-container run_at_boot flag in sync with the active runtime only. */
object RuntimeBootPolicy {
    private const val TAG = "RuntimeBootPolicy"

    @Synchronized
    fun apply(context: Context) {
        val active = RuntimeStore.active(context)?.container
        val enabled = RuntimePrefs.runAtBoot(context) && active != null
        val names = LinkedHashSet<String>()
        RuntimeStore.entries(context).forEach { names.add(it.container) }
        runCatching { RuntimeDiscovery.list(context, true).forEach { names.add(it.name) } }
            .onFailure { Log.w(TAG, "could not list containers: $it") }
        for (name in names) {
            val wanted = enabled && name == active
            if (ContainerBootConfig.runAtBoot(context, name) == wanted) continue
            val error = ContainerBootConfig.setRunAtBoot(context, name, wanted)
            if (error != null) Log.w(TAG, "'$name' run_at_boot=$wanted: $error")
            else Log.i(TAG, "'$name' run_at_boot=$wanted")
        }
        // The legacy boot script must follow the same single-runtime policy.
        if (enabled && active != null) BootServiceScript.install(context, active) else BootServiceScript.remove()
    }
}
