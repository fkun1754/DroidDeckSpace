package com.droiddeck.launcher.hud

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

internal object HudFrames {
    init {
        System.loadLibrary("droiddeckwayland")
    }

    const val PATH = "run/droiddeck-hud/frames"
    const val CAPACITY = 1024

    /** Host side of the guest's /run/droiddeck-hud/frames in the container runtime. */
    fun hostFile(context: Context): File = File(LinuxRuntime.hudDir(context), "frames")

    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativePoll(handle: Long, frametimes: LongArray, times: LongArray): Int
    @JvmStatic external fun nativeInfo(handle: Long, info: IntArray): String?
}
